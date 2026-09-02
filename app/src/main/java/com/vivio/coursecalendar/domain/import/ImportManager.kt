package com.vivio.coursecalendar.domain.import

import com.vivio.coursecalendar.data.local.AppDatabase
import com.vivio.coursecalendar.data.local.entity.EventMappingEntity
import com.vivio.coursecalendar.data.local.entity.ImportBatchEntity
import com.vivio.coursecalendar.data.repository.ScheduleRepository
import com.vivio.coursecalendar.domain.calendar.CalendarWriter
import com.vivio.coursecalendar.domain.model.CourseStatus
import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.model.EventState
import com.vivio.coursecalendar.domain.model.UnifiedEvent
import com.vivio.coursecalendar.domain.parser.ExcelIO
import com.vivio.coursecalendar.domain.parser.FormatDetector
import com.vivio.coursecalendar.domain.parser.ParseContext
import com.vivio.coursecalendar.domain.parser.PartTimeScheduleParser
import com.vivio.coursecalendar.domain.parser.ScheduleParser
import com.vivio.coursecalendar.domain.parser.ScheduleTable
import com.vivio.coursecalendar.domain.parser.UniversityScheduleParser
import com.vivio.coursecalendar.domain.schedule.Season
import com.vivio.coursecalendar.util.FileFingerprint
import java.time.ZoneId

/**
 * 导入总控：选择文件 → 识别 → 解析 → 去重/冲突 → 写入 → 记录批次 → 撤销。
 * 对应交接包《02》内部处理流程。
 */
class ImportManager(
    private val db: AppDatabase,
    private val scheduleRepository: ScheduleRepository,
    private val calendarWriter: CalendarWriter
) {
    private val eventMappingDao = db.eventMappingDao()
    private val importBatchDao = db.importBatchDao()
    private val dedupEngine = DedupEngine { fp ->
        eventMappingDao.getByFingerprint(fp).map {
            ExistingMapping(it.title, it.location, it.startMillis, it.endMillis)
        }
    }

    sealed interface ParseOutcome {
        data class Previewed(val preview: ImportPreview) : ParseOutcome
        data class Error(val message: String, val detectedSource: EventSource?) : ParseOutcome
    }

    /** 仅识别课表类型（用于 UI 先确认类型，再决定是否需要选择季节）。 */
    suspend fun detectSource(bytes: ByteArray): EventSource? {
        val workbook = ExcelIO.openSafely(bytes.inputStream(), bytes) ?: return null
        val source = FormatDetector.detect(workbook)?.source
        workbook.close()
        return source
    }

    /**
     * 解析并生成预览。format 识别失败时返回 Error(detectedSource=null)，
     * UI 引导用户手动选择类型后再以 forcedSource 重试。
     */
    suspend fun parseAndPreview(
        bytes: ByteArray,
        fileName: String,
        season: Season?,
        schedule: ScheduleTable?,
        forcedSource: EventSource? = null
    ): ParseOutcome {
        val fileHash = FileFingerprint.sha256(bytes)
        val workbook = ExcelIO.openSafely(bytes.inputStream(), bytes)
            ?: return ParseOutcome.Error("文件无法读取或已损坏，请确认是有效的 Excel 文件", null)

        val detected = forcedSource ?: FormatDetector.detect(workbook)?.source
        if (detected == null) {
            workbook.close()
            return ParseOutcome.Error("无法自动识别课表类型，请手动选择（校内课表 / 兼职课表）", null)
        }

        val parser: ScheduleParser = when (detected) {
            EventSource.UNIVERSITY -> UniversityScheduleParser()
            EventSource.PART_TIME -> PartTimeScheduleParser()
        }
        val context = ParseContext(fileHash, season, schedule)
        val result = parser.parse(workbook, context)
        workbook.close()

        when (result) {
            is com.vivio.coursecalendar.domain.parser.ParseResult.Failure ->
                return ParseOutcome.Error(result.message, detected)
            is com.vivio.coursecalendar.domain.parser.ParseResult.Success -> {
                // 同一指纹的事件视为同一课程，预览前按指纹去重
                val events = result.events.map { it.withId() }.distinctBy { it.eventFingerprint }
                val stateMap = dedupEngine.evaluate(events)
                val conflicts = ConflictDetector.detect(events)

                val items = events.map { event ->
                    val state = when {
                        event.blocker != null -> EventState.INVALID
                        else -> stateMap[event.eventFingerprint] ?: EventState.NEW
                    }
                    val excluded = when {
                        event.blocker != null -> true
                        event.status == CourseStatus.COMPLETED -> true
                        else -> false
                    }
                    PreviewItem(
                        event = event,
                        state = state,
                        conflictWith = conflicts[event.eventFingerprint]?.conflictWith ?: emptyList(),
                        excluded = excluded
                    )
                }

                // 新课表中消失的旧事件：仅提示
                val missing = computeMissing(fileHash, events)

                return ParseOutcome.Previewed(
                    ImportPreview(
                        source = detected,
                        season = season,
                        items = items,
                        warnings = result.warnings,
                        fileHash = fileHash,
                        fileName = fileName,
                        missing = missing
                    )
                )
            }
        }
    }

    private suspend fun computeMissing(fileHash: String, newEvents: List<UnifiedEvent>): List<MissingEvent> {
        val previous = importBatchDao.getLatestByFileHash(fileHash) ?: return emptyList()
        val oldMappings = eventMappingDao.getActiveByBatch(previous.id)
        val newFps = newEvents.map { it.eventFingerprint }.toSet()
        return oldMappings
            .filter { it.eventFingerprint !in newFps }
            .map { MissingEvent(it.title, it.startMillis, it.eventFingerprint) }
    }

    /**
     * 执行导入：NEW/CONFLICT 创建；MODIFIED 更新原事件；CANCELLED 删除原事件；
     * UNCHANGED/INVALID/被排除项跳过。单条失败不回滚其他成功事件。
     */
    suspend fun commit(
        preview: ImportPreview,
        reminderMinutes: Int?,
        excludedFingerprints: Set<String>
    ): CommitResult {
        var created = 0
        var updated = 0
        var deleted = 0
        var failed = 0

        val processed = mutableListOf<EventMappingEntity>()
        val toDelete = mutableListOf<Pair<Long, EventMappingEntity>>() // calendarEventId to mapping

        for (item in preview.items) {
            val event = item.event
            if (item.excluded || event.eventFingerprint in excludedFingerprints) continue

            when (item.state) {
                EventState.INVALID, EventState.UNCHANGED -> continue
                EventState.NEW, EventState.CONFLICT -> {
                    val written = event.copy(reminderMinutes = reminderMinutes ?: event.reminderMinutes)
                    val calendarId = calendarWriter.insertEvent(preview.source, written)
                    if (calendarId == null) {
                        failed++
                        continue
                    }
                    created++
                    processed += event.toMapping(calendarId, item.state, excluded = false)
                }
                EventState.MODIFIED -> {
                    val existing = eventMappingDao.getByFingerprint(event.eventFingerprint).firstOrNull()
                    if (existing == null) {
                        failed++
                        continue
                    }
                    val written = event.copy(reminderMinutes = reminderMinutes ?: event.reminderMinutes)
                    if (calendarWriter.updateEvent(preview.source, existing.calendarEventId, written)) {
                        updated++
                        toDelete += existing.calendarEventId to existing
                        processed += event.toMapping(existing.calendarEventId, EventState.MODIFIED, excluded = false)
                    } else {
                        failed++
                    }
                }
                EventState.CANCELLED -> {
                    val existing = eventMappingDao.getByFingerprint(event.eventFingerprint).firstOrNull()
                    if (existing != null) {
                        calendarWriter.deleteEvent(existing.calendarEventId)
                        toDelete += existing.calendarEventId to existing
                        deleted++
                    }
                }
                EventState.MISSING -> Unit
            }
        }

        // 清理被替换/删除的旧映射
        toDelete.forEach { (_, mapping) -> eventMappingDao.delete(mapping) }

        val invalidCount = preview.items.count { it.state == EventState.INVALID || it.excluded }
        val batch = ImportBatchEntity(
            fileHash = preview.fileHash,
            fileName = preview.fileName,
            source = preview.source.name,
            season = preview.season?.name,
            createdAt = System.currentTimeMillis(),
            totalCount = preview.items.size,
            createdCount = created,
            updatedCount = updated,
            unchangedCount = preview.items.count { it.state == EventState.UNCHANGED },
            invalidCount = invalidCount,
            status = if (failed > 0) "PARTIAL" else "COMPLETED"
        )
        val batchId = importBatchDao.insert(batch)

        // 写入映射：批次创建后再回填 batchId，保证撤销/更新可定位
        eventMappingDao.insertAll(processed.map { it.copy(batchId = batchId) })

        return CommitResult(
            batchId = batchId,
            created = created,
            updated = updated,
            unchanged = preview.items.count { it.state == EventState.UNCHANGED },
            deleted = deleted,
            invalid = invalidCount,
            failed = failed
        )
    }

    /** 撤销某次导入：只删除该批次创建/更新的事件。 */
    suspend fun undo(batchId: Long): Boolean {
        val batch = importBatchDao.getById(batchId) ?: return false
        val mappings = eventMappingDao.getByBatch(batchId)
        mappings.forEach { mapping ->
            calendarWriter.deleteEvent(mapping.calendarEventId)
        }
        eventMappingDao.deleteByBatch(batchId)
        importBatchDao.update(batch.copy(status = "UNDONE"))
        return true
    }

    private fun UnifiedEvent.toMapping(calendarEventId: Long, state: EventState, excluded: Boolean) =
        EventMappingEntity(
            batchId = 0, // commit 后由 batchId 回填
            source = source.name,
            sourceRecordId = sourceRecordId,
            eventFingerprint = eventFingerprint,
            calendarEventId = calendarEventId,
            title = title,
            location = location,
            startMillis = startTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            endMillis = endTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            state = state.name,
            excluded = excluded
        )
}
