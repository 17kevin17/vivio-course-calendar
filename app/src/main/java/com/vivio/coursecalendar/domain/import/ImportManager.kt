package com.vivio.coursecalendar.domain.import

import com.vivio.coursecalendar.data.local.AppDatabase
import com.vivio.coursecalendar.data.local.entity.BatchActionState
import com.vivio.coursecalendar.data.local.entity.BatchActionType
import com.vivio.coursecalendar.data.local.entity.BatchEventActionEntity
import com.vivio.coursecalendar.data.local.entity.BatchPhase
import com.vivio.coursecalendar.data.local.entity.ImportBatchEntity
import com.vivio.coursecalendar.data.local.entity.ManagedEventEntity
import com.vivio.coursecalendar.data.local.entity.ManagedStatus
import com.vivio.coursecalendar.data.repository.ScheduleRepository
import com.vivio.coursecalendar.domain.calendar.CalendarGateway
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
import com.vivio.coursecalendar.domain.time.CourseTime
import com.vivio.coursecalendar.util.FileFingerprint
import java.time.ZoneId

/**
 * 导入总控（交接包《04》）：解析 → 差异 → 状态机执行 → 撤销 → 恢复。
 *
 * 关键原则：先记录操作意图（batch_event_action PLANNED），再修改系统日历。
 * CalendarProvider 与 Room 不共享事务，中断后通过操作日志恢复。
 */
class ImportManager(
    private val db: AppDatabase,
    private val scheduleRepository: ScheduleRepository,
    private val calendarWriter: CalendarGateway
) {
    private val importBatchDao = db.importBatchDao()
    private val managedEventDao = db.managedEventDao()
    private val batchActionDao = db.batchEventActionDao()
    private val diffEngine = DiffEngine(managedEventDao)

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
     * 解析并生成预览（DiffPlan）。格式识别失败时返回 Error(detectedSource=null)，
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
                val events = result.events.map { it.withId() }.distinctBy { it.identityKey }
                val plan = diffEngine.compute(events, detected, season)
                val items = plan.items.map { item ->
                    PreviewItem(
                        event = item.event,
                        state = item.state,
                        conflictWith = item.conflictWith,
                        excluded = item.excluded,
                        oldMappingId = item.existingManagedId
                    )
                }
                return ParseOutcome.Previewed(
                    ImportPreview(
                        source = detected,
                        season = season,
                        items = items,
                        warnings = result.warnings + plan.warnings,
                        fileHash = fileHash,
                        fileName = fileName,
                        missing = plan.missing.map { MissingEvent(it.identityKey, it.title, it.startMillis) }
                    )
                )
            }
        }
    }

    /**
     * 执行导入（交接包《04》第二节）：
     * PREPARED → 落 PLANNED 操作 → APPLYING → 逐条执行 → APPLIED / PARTIAL。
     * 单条失败不回滚其他成功事件。
     */
    suspend fun commit(
        preview: ImportPreview,
        reminderMinutes: Int?,
        excludedIdentityKeys: Set<String>
    ): CommitResult {
        val targets = preview.items.filter { !it.excluded && it.event.identityKey !in excludedIdentityKeys }
        var created = 0
        var updated = 0
        var deleted = 0
        var failed = 0

        // 1) 批次 PREPARED
        val batch = ImportBatchEntity(
            fileHash = preview.fileHash,
            fileName = preview.fileName,
            source = preview.source.name,
            season = preview.season?.name,
            createdAt = System.currentTimeMillis(),
            phase = BatchPhase.PREPARED,
            totalCount = preview.items.size,
            createdCount = 0,
            updatedCount = 0,
            unchangedCount = preview.items.count { it.state == EventState.UNCHANGED },
            invalidCount = preview.items.count { it.state == EventState.INVALID || it.excluded }
        )
        val batchId = importBatchDao.insert(batch)

        // 2) 生成 PLANNED 操作落库
        val actionRows = mutableListOf<BatchEventActionEntity>()
        for (item in targets) {
            val event = item.event
            val actionType = when (item.state) {
                EventState.NEW, EventState.CONFLICT -> BatchActionType.CREATE
                EventState.MODIFIED -> BatchActionType.UPDATE
                EventState.CANCELLED -> BatchActionType.DELETE
                else -> BatchActionType.NOOP
            }
            val existing = if (actionType == BatchActionType.UPDATE || actionType == BatchActionType.DELETE) {
                managedEventDao.getByIdentity(preview.source.name, event.identityKey)
            } else null
            actionRows += BatchEventActionEntity(
                batchId = batchId,
                managedEventId = existing?.id,
                identityKey = event.identityKey,
                actionType = actionType,
                beforeSnapshot = existing?.let { EventSnapshot.toJson(it.toEvent(preview.source)) },
                afterSnapshot = if (actionType != BatchActionType.NOOP) EventSnapshot.toJson(event) else null,
                calendarEventIdBefore = existing?.calendarEventId,
                calendarEventIdAfter = null,
                state = BatchActionState.PLANNED,
                errorCode = null
            )
        }
        val actionIds = batchActionDao.insertAll(actionRows)

        // 3) APPLYING
        importBatchDao.update(batch.copy(phase = BatchPhase.APPLYING))

        // 4) 逐条执行
        for ((i, item) in targets.withIndex()) {
            val actionId = actionIds[i]
            var action = batchActionDao.getByBatch(batchId).firstOrNull { it.id == actionId } ?: continue
            val event = item.event.copy(reminderMinutes = reminderMinutes ?: item.event.reminderMinutes)

            when (action.actionType) {
                BatchActionType.CREATE -> {
                    val cid = calendarWriter.insertEvent(preview.source, event)
                    if (cid == null) {
                        action = action.copy(state = BatchActionState.FAILED, errorCode = "CALENDAR_INSERT_FAILED")
                        batchActionDao.update(action)
                        failed++
                        continue
                    }
                    action = action.copy(state = BatchActionState.CALENDAR_APPLIED, calendarEventIdAfter = cid)
                    batchActionDao.update(action)
                    val newId = managedEventDao.insert(event.toManaged(preview.source, cid, batchId))
                    action = action.copy(managedEventId = newId, state = BatchActionState.DB_APPLIED)
                    batchActionDao.update(action)
                    created++
                }
                BatchActionType.UPDATE -> {
                    val existing = action.managedEventId?.let { managedEventDao.getById(it) }
                        ?: managedEventDao.getByIdentity(preview.source.name, item.event.identityKey)
                    if (existing == null) {
                        action = action.copy(state = BatchActionState.FAILED, errorCode = "MANAGED_EVENT_MISSING")
                        batchActionDao.update(action)
                        failed++
                        continue
                    }
                    val ok = if (existing.calendarEventId != null) {
                        calendarWriter.updateEvent(preview.source, existing.calendarEventId, event)
                    } else {
                        // 系统事件已丢失：重新创建
                        val newCid = calendarWriter.insertEvent(preview.source, event)
                        if (newCid != null) {
                            action = action.copy(calendarEventIdAfter = newCid)
                            batchActionDao.update(action)
                        }
                        newCid != null
                    }
                    if (!ok) {
                        action = action.copy(state = BatchActionState.FAILED, errorCode = "CALENDAR_UPDATE_FAILED")
                        batchActionDao.update(action)
                        failed++
                        continue
                    }
                    val effectiveCid = action.calendarEventIdAfter ?: existing.calendarEventId
                    managedEventDao.update(
                        existing.copy(
                            contentHash = event.contentHash,
                            calendarEventId = effectiveCid,
                            title = event.title,
                            location = event.location,
                            description = event.description,
                            startMillis = event.millisStart(),
                            endMillis = event.millisEnd(),
                            lastSeenBatchId = batchId,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    action = action.copy(state = BatchActionState.DB_APPLIED, managedEventId = existing.id)
                    batchActionDao.update(action)
                    updated++
                }
                BatchActionType.DELETE -> {
                    val existing = managedEventDao.getByIdentity(preview.source.name, item.event.identityKey)
                    if (existing != null) {
                        existing.calendarEventId?.let { calendarWriter.deleteEvent(it) }
                        managedEventDao.update(
                            existing.copy(status = ManagedStatus.CANCELLED, calendarEventId = null, updatedAt = System.currentTimeMillis())
                        )
                    }
                    action = action.copy(state = BatchActionState.DB_APPLIED)
                    batchActionDao.update(action)
                    deleted++
                }
                BatchActionType.NOOP, BatchActionType.MARK_MISSING -> {
                    action = action.copy(state = BatchActionState.DB_APPLIED)
                    batchActionDao.update(action)
                }
            }
        }

        // 5) 收尾
        importBatchDao.update(
            batch.copy(
                phase = if (failed > 0) BatchPhase.PARTIAL else BatchPhase.APPLIED,
                completedAt = System.currentTimeMillis(),
                createdCount = created,
                updatedCount = updated,
                unchangedCount = preview.items.count { it.state == EventState.UNCHANGED },
                invalidCount = preview.items.count { it.state == EventState.INVALID || it.excluded },
                errorSummary = if (failed > 0) "$failed 条操作失败" else null
            )
        )

        return CommitResult(
            batchId = batchId,
            created = created,
            updated = updated,
            unchanged = preview.items.count { it.state == EventState.UNCHANGED },
            deleted = deleted,
            invalid = preview.items.count { it.state == EventState.INVALID || it.excluded },
            failed = failed
        )
    }

    /**
     * 撤销某次导入（交接包《04》第三节）：按动作类型逆操作。
     * CREATE → 删除；UPDATE → 用 beforeSnapshot 恢复；DELETE → 重建。
     */
    suspend fun undo(batchId: Long): Boolean {
        val batch = importBatchDao.getById(batchId) ?: return false
        if (batch.phase == BatchPhase.UNDONE) return true // 幂等
        importBatchDao.update(batch.copy(phase = BatchPhase.UNDOING))

        val actions = batchActionDao.getByBatch(batchId).sortedByDescending { it.id }
        for (action in actions) {
            when (action.actionType) {
                BatchActionType.CREATE -> {
                    action.calendarEventIdAfter?.let { calendarWriter.deleteEvent(it) }
                    action.managedEventId?.let { managedEventDao.getById(it) }?.let { managedEventDao.delete(it) }
                }
                BatchActionType.UPDATE -> {
                    val before = EventSnapshot.fromJson(action.beforeSnapshot)
                    if (before != null) {
                        val cid = action.calendarEventIdBefore
                        if (cid != null) calendarWriter.updateEvent(batch.sourceOf(), cid, before)
                        val me = action.managedEventId?.let { managedEventDao.getById(it) }
                        if (me != null) {
                            managedEventDao.update(
                                me.copy(
                                    contentHash = before.contentHash,
                                    calendarEventId = cid,
                                    title = before.title,
                                    location = before.location,
                                    description = before.description,
                                    startMillis = before.millisStart(),
                                    endMillis = before.millisEnd(),
                                    status = ManagedStatus.ACTIVE,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
                BatchActionType.DELETE -> {
                    val before = EventSnapshot.fromJson(action.beforeSnapshot)
                    if (before != null) {
                        val newCid = calendarWriter.insertEvent(batch.sourceOf(), before)
                        val me = action.managedEventId?.let { managedEventDao.getById(it) }
                        if (me != null) {
                            managedEventDao.update(
                                me.copy(
                                    status = ManagedStatus.ACTIVE,
                                    calendarEventId = newCid,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
                BatchActionType.NOOP, BatchActionType.MARK_MISSING -> Unit
            }
            batchActionDao.update(action.copy(state = BatchActionState.REVERTED))
        }

        importBatchDao.update(
            batch.copy(phase = BatchPhase.UNDONE, completedAt = System.currentTimeMillis())
        )
        return true
    }

    /**
     * 启动恢复（交接包《04》第四节）：扫描超时未完成的 APPLYING / UNDOING 批次。
     * 能确认完成的补记状态；无法确认的标记 FAILED，不盲目创建。
     * 返回处理的批次数量。
     */
    suspend fun recover(): Int {
        val stuck = (importBatchDao.getByPhase(BatchPhase.APPLYING) + importBatchDao.getByPhase(BatchPhase.UNDOING))
            .filter { System.currentTimeMillis() - it.createdAt > RECOVER_TIMEOUT_MS }
        for (batch in stuck) {
            val actions = batchActionDao.getByBatchAndState(batch.id, BatchActionState.CALENDAR_APPLIED)
            for (action in actions) {
                // 日历操作已完成的：若 managed_event 已写入则补 DB_APPLIED，否则标记 FAILED 待人工确认
                val done = action.managedEventId?.let { managedEventDao.getById(it) } != null
                batchActionDao.update(action.copy(state = if (done) BatchActionState.DB_APPLIED else BatchActionState.FAILED))
            }
            importBatchDao.update(
                batch.copy(
                    phase = BatchPhase.PARTIAL,
                    errorSummary = "进程中断，部分操作需人工确认",
                    completedAt = System.currentTimeMillis()
                )
            )
        }
        return stuck.size
    }

    private fun ManagedEventEntity.toEvent(source: EventSource): UnifiedEvent =
        UnifiedEvent(
            source = source,
            sourceRecordId = sourceRecordId,
            title = title,
            description = description,
            location = location,
            startTime = CourseTime.fromMillis(startMillis),
            endTime = CourseTime.fromMillis(endMillis),
            status = CourseStatus.PENDING,
            identityKey = identityKey,
            contentHash = contentHash,
            calendarEventId = calendarEventId
        )

    private fun UnifiedEvent.toManaged(source: EventSource, calendarEventId: Long, batchId: Long): ManagedEventEntity {
        val now = System.currentTimeMillis()
        return ManagedEventEntity(
            source = source.name,
            identityKey = identityKey,
            contentHash = contentHash,
            sourceRecordId = sourceRecordId,
            calendarEventId = calendarEventId,
            title = title,
            location = location,
            description = description,
            startMillis = millisStart(),
            endMillis = millisEnd(),
            status = ManagedStatus.ACTIVE,
            lastSeenBatchId = batchId,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun UnifiedEvent.millisStart(): Long = CourseTime.toMillis(startTime)

    private fun UnifiedEvent.millisEnd(): Long = CourseTime.toMillis(endTime)

    private fun ImportBatchEntity.sourceOf(): EventSource = EventSource.valueOf(source)

    companion object {
        /** 超时判定：进程中断超过该时长视为需要恢复 */
        private const val RECOVER_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
