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
                val events = result.events.map { it.withId() }
                // v2 F1：禁止静默 distinctBy 丢弃。重复 identityKey 显式标记，不静默保留。
                val duplicates = events.groupingBy { it.identityKey }
                    .eachCount()
                    .filter { it.value > 1 }
                val warnings = result.warnings.toMutableList()
                duplicates.forEach { (key, count) ->
                    warnings.add("检测到 $count 条 identityKey 重复（$key），已全部保留并标记待确认")
                }
                // v2 F6：导入范围（校内同学期；兼职按本文件日期窗口），用于限定 MISSING。
                val scope = buildImportScope(events, detected)
                val plan = diffEngine.compute(events, detected, season, scope)
                val items = plan.items.map { item ->
                    val isDuplicateKey = duplicates.containsKey(item.event.identityKey)
                    PreviewItem(
                        event = item.event,
                        state = if (isDuplicateKey) EventState.AMBIGUOUS else item.state,
                        conflictWith = item.conflictWith,
                        excluded = item.excluded || isDuplicateKey,
                        oldMappingId = item.existingManagedId
                    )
                }
                return ParseOutcome.Previewed(
                    ImportPreview(
                        source = detected,
                        season = season,
                        items = items,
                        warnings = warnings,
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
        // v2 F7：把用户确认的最终提醒应用到事件并重算哈希；提醒使哈希变化 → 重新判定为 MODIFIED。
        // Triple(item, finalEvent, effectiveState)
        val prepared = preview.items
            .filter { !it.excluded && it.event.identityKey !in excludedIdentityKeys }
            .map { item ->
                val finalEvent = item.event.withFinalReminder(reminderMinutes)
                val effectiveState = if (item.state == EventState.UNCHANGED) {
                    val existing = managedEventDao.getByIdentity(preview.source.name, finalEvent.identityKey)
                    if (existing != null && existing.contentHash != finalEvent.contentHash) {
                        EventState.MODIFIED
                    } else {
                        EventState.UNCHANGED
                    }
                } else {
                    item.state
                }
                Triple(item, finalEvent, effectiveState)
            }
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

        // 2) 生成 PLANNED 操作落库（基于最终事件与最终状态）
        val actionRows = mutableListOf<BatchEventActionEntity>()
        for ((item, finalEvent, state) in prepared) {
            val actionType = when (state) {
                EventState.NEW, EventState.CONFLICT -> BatchActionType.CREATE
                EventState.MODIFIED -> BatchActionType.UPDATE
                EventState.CANCELLED -> BatchActionType.DELETE
                else -> BatchActionType.NOOP
            }
            val existing = if (actionType == BatchActionType.UPDATE || actionType == BatchActionType.DELETE) {
                managedEventDao.getByIdentity(preview.source.name, finalEvent.identityKey)
            } else null
            // R2：CREATE 在调用 CalendarProvider 前生成稳定 operation token 并落库，崩溃后可按 token 找回
            val operationToken = if (actionType == BatchActionType.CREATE) "op-" + java.util.UUID.randomUUID() else null
            actionRows += BatchEventActionEntity(
                batchId = batchId,
                managedEventId = existing?.id,
                identityKey = finalEvent.identityKey,
                actionType = actionType,
                beforeSnapshot = existing?.let { EventSnapshot.toJson(it.toEvent(preview.source)) },
                afterSnapshot = if (actionType != BatchActionType.NOOP) EventSnapshot.toJson(finalEvent) else null,
                calendarEventIdBefore = existing?.calendarEventId,
                calendarEventIdAfter = null,
                state = BatchActionState.PLANNED,
                errorCode = null,
                operationToken = operationToken
            )
        }
        val actionIds = batchActionDao.insertAll(actionRows)

        // 3) APPLYING
        // 注意：batch 为新构造实体（id=0），更新时必须带上 batchId，否则主键 0 不匹配导致 phase 静默不更新
        importBatchDao.update(batch.copy(id = batchId, phase = BatchPhase.APPLYING))

        // 4) 逐条执行
        for ((i, triple) in prepared.withIndex()) {
            val (item, event, _) = triple
            val actionId = actionIds[i]
            var action = batchActionDao.getByBatch(batchId).firstOrNull { it.id == actionId } ?: continue

            when (action.actionType) {
                BatchActionType.CREATE -> {
                    // R2：先按 operation token 找回（崩溃后 ID 未落库时复用，避免重复创建）
                    val found = action.operationToken?.let { calendarWriter.findEventByOperationToken(it) }
                    val cid = when {
                        found != null && found.ambiguousTokenMatch -> {
                            action = action.copy(state = BatchActionState.FAILED, errorCode = "TOKEN_AMBIGUOUS")
                            batchActionDao.update(action)
                            failed++
                            continue
                        }
                        found != null -> found.calendarEventId
                        else -> calendarWriter.insertEvent(preview.source, event, action.operationToken)
                    }
                    if (cid == null) {
                        action = action.copy(state = BatchActionState.FAILED, errorCode = "CALENDAR_INSERT_FAILED")
                        batchActionDao.update(action)
                        failed++
                        continue
                    }
                    action = action.copy(state = BatchActionState.CALENDAR_APPLIED, calendarEventIdAfter = cid)
                    batchActionDao.update(action)
                    val newId = upsertManaged(event.toManaged(preview.source, cid, batchId))
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
                            reminderMinutes = event.reminderMinutes,
                            // R5：更新成功必须恢复 ACTIVE（含 CANCELLED → PENDING 重新开课场景）
                            status = ManagedStatus.ACTIVE,
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
                    if (existing != null && existing.calendarEventId != null) {
                        // R4：调用删除后核验真实状态；删除未生效不得记成功
                        calendarWriter.deleteEvent(existing.calendarEventId)
                        if (calendarWriter.eventExists(existing.calendarEventId)) {
                            action = action.copy(state = BatchActionState.FAILED, errorCode = "CALENDAR_DELETE_NOT_EFFECTIVE")
                            batchActionDao.update(action)
                            failed++
                            continue
                        }
                        // R3：确认删除后同步 managed 为 CANCELLED 并清空 calendarEventId
                        managedEventDao.update(
                            existing.copy(status = ManagedStatus.CANCELLED, calendarEventId = null, updatedAt = System.currentTimeMillis())
                        )
                    } else if (existing != null) {
                        // 系统事件已不存在：仅补写取消状态
                        managedEventDao.update(
                            existing.copy(status = ManagedStatus.CANCELLED, updatedAt = System.currentTimeMillis())
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
        val finalUnchanged = prepared.count { it.third == EventState.UNCHANGED }
        importBatchDao.update(
            batch.copy(
                id = batchId,
                phase = if (failed > 0) BatchPhase.PARTIAL else BatchPhase.APPLIED,
                completedAt = System.currentTimeMillis(),
                createdCount = created,
                updatedCount = updated,
                unchangedCount = finalUnchanged,
                invalidCount = preview.items.count { it.state == EventState.INVALID || it.excluded },
                errorSummary = if (failed > 0) "$failed 条操作失败" else null
            )
        )

        return CommitResult(
            batchId = batchId,
            created = created,
            updated = updated,
            unchanged = finalUnchanged,
            deleted = deleted,
            invalid = preview.items.count { it.state == EventState.INVALID || it.excluded },
            failed = failed
        )
    }

    /**
     * 撤销某次导入（交接包《04》第三节）：按动作类型逆操作。
     * CREATE → 删除；UPDATE → 用 beforeSnapshot 恢复；DELETE → 重建。
     * v2 F3：只处理未 REVERTED 的动作，中断后重试幂等，不重复创建/误删。
     * v2 R1：与恢复共用同一套单动作逆操作函数（revertAction）。
     */
    suspend fun undo(batchId: Long): Boolean {
        val batch = importBatchDao.getById(batchId) ?: return false
        if (batch.phase == BatchPhase.UNDONE) return true // 幂等
        importBatchDao.update(batch.copy(phase = BatchPhase.UNDOING))

        val actions = batchActionDao.getByBatch(batchId).sortedByDescending { it.id }
        for (action in actions) {
            // v2 F3：已恢复的动作跳过，中断后可安全重试
            if (action.state == BatchActionState.REVERTED) continue
            val r = revertAction(action, batch.sourceOf())
            if (r.ok) {
                // 逆操作成功 → 立即标记 REVERTED
                batchActionDao.update(action.copy(state = BatchActionState.REVERTED))
            }
            // 失败时 revertAction 已写 REVERT_FAILED + errorCode
        }

        // R6：批次阶段由动作最终状态集中汇总，不得无条件 UNDONE
        val phase = summarizeBatchPhase(batchId, Direction.UNDO)
        importBatchDao.update(
            batch.copy(phase = phase, completedAt = System.currentTimeMillis())
        )
        return true
    }

    /**
     * 启动恢复（交接包《04》第四节，v2 F4/F5/R1/R6）：扫描超时未完成批次。
     * APPLYING 与 UNDOING 使用不同方向的恢复流程（resumeApplying / resumeUndoing）；
     * 批次阶段由动作最终状态集中汇总，失败动作不得让批次进入 APPLIED/UNDONE。
     * 返回处理的批次数量。
     */
    suspend fun recover(): Int {
        val now = System.currentTimeMillis()
        val stuck = (importBatchDao.getByPhase(BatchPhase.APPLYING) + importBatchDao.getByPhase(BatchPhase.UNDOING))
            .filter { now - it.createdAt > RECOVER_TIMEOUT_MS }

        for (batch in stuck) {
            val phase = if (batch.phase == BatchPhase.UNDOING) {
                resumeUndoing(batch)
            } else {
                resumeApplying(batch)
            }
            val failed = batchActionDao.getByBatch(batch.id)
                .count { it.state == BatchActionState.FAILED || it.state == BatchActionState.REVERT_FAILED }
            importBatchDao.update(
                batch.copy(
                    phase = phase,
                    errorSummary = if (failed > 0) "$failed 条操作失败" else null,
                    completedAt = now
                )
            )
        }

        // v2 F5：managed_event 指向不存在的系统事件 → 标记 BROKEN，待人工处理
        val managedEvents = managedEventDao.getAll()
        for (me in managedEvents) {
            val cid = me.calendarEventId ?: continue
            if (me.status == ManagedStatus.ACTIVE && !calendarWriter.eventExists(cid)) {
                managedEventDao.update(
                    me.copy(status = ManagedStatus.BROKEN, updatedAt = System.currentTimeMillis())
                )
            }
        }
        return stuck.size
    }

    private enum class Direction { APPLY, UNDO }

    /** 单动作结果（R6：禁止 Unit + 调用方猜测）。 */
    private data class ActionResult(val ok: Boolean, val errorCode: String? = null) {
        companion object {
            val OK = ActionResult(true)
            fun fail(code: String) = ActionResult(false, code)
        }
    }

    /** 正向恢复（APPLYING）：按 id 顺序重放未完成动作。 */
    private suspend fun resumeApplying(batch: ImportBatchEntity): String {
        for (action in batchActionDao.getByBatch(batch.id)) {
            if (action.state == BatchActionState.DB_APPLIED ||
                action.state == BatchActionState.REVERTED ||
                action.state == BatchActionState.FAILED ||
                action.state == BatchActionState.REVERT_FAILED
            ) continue
            when (action.actionType) {
                BatchActionType.CREATE -> applyCreate(batch, action)
                BatchActionType.UPDATE -> applyUpdate(batch, action)
                BatchActionType.DELETE -> applyDelete(action)
                BatchActionType.NOOP, BatchActionType.MARK_MISSING ->
                    batchActionDao.update(action.copy(state = BatchActionState.DB_APPLIED))
            }
        }
        return summarizeBatchPhase(batch.id, Direction.APPLY)
    }

    /** 逆向恢复（UNDOING）：按 id 逆序执行逆操作，与 undo() 共用 revertAction。 */
    private suspend fun resumeUndoing(batch: ImportBatchEntity): String {
        for (action in batchActionDao.getByBatch(batch.id).sortedByDescending { it.id }) {
            if (action.state == BatchActionState.REVERTED ||
                action.state == BatchActionState.REVERT_FAILED ||
                action.state == BatchActionState.FAILED
            ) continue
            val r = revertAction(action, batch.sourceOf())
            if (r.ok) batchActionDao.update(action.copy(state = BatchActionState.REVERTED))
        }
        return summarizeBatchPhase(batch.id, Direction.UNDO)
    }

    /** 集中汇总批次阶段（R6）：由动作最终状态推导，禁止无条件 APPLIED/UNDONE。 */
    private suspend fun summarizeBatchPhase(batchId: Long, direction: Direction): String {
        val actions = batchActionDao.getByBatch(batchId)
        return when (direction) {
            Direction.APPLY -> when {
                actions.isEmpty() || actions.all { it.state == BatchActionState.DB_APPLIED } -> BatchPhase.APPLIED
                actions.any { it.state == BatchActionState.FAILED || it.state == BatchActionState.REVERT_FAILED } -> BatchPhase.PARTIAL
                else -> BatchPhase.APPLYING
            }
            Direction.UNDO -> when {
                actions.isEmpty() || actions.all { it.state == BatchActionState.REVERTED } -> BatchPhase.UNDONE
                actions.any { it.state == BatchActionState.REVERT_FAILED || it.state == BatchActionState.FAILED } -> BatchPhase.PARTIAL
                else -> BatchPhase.UNDOING
            }
        }
    }

    /** 单动作逆操作（undo 与 resumeUndoing 共用）。 */
    private suspend fun revertAction(action: BatchEventActionEntity, source: EventSource): ActionResult {
        return when (action.actionType) {
            BatchActionType.CREATE -> revertCreate(action)
            BatchActionType.UPDATE -> revertUpdate(action, source)
            BatchActionType.DELETE -> revertDelete(action, source)
            BatchActionType.NOOP, BatchActionType.MARK_MISSING -> ActionResult.OK
            else -> ActionResult.OK
        }
    }

    /** 撤销 CREATE：删除系统事件（token 找回兜底）并删除对应 managed。 */
    private suspend fun revertCreate(action: BatchEventActionEntity): ActionResult {
        val cid = action.calendarEventIdAfter ?: action.operationToken?.let {
            calendarWriter.findEventByOperationToken(it)?.takeIf { snap -> !snap.ambiguousTokenMatch }?.calendarEventId
        }
        if (cid != null) calendarWriter.deleteEvent(cid)
        action.managedEventId?.let { mid ->
            val me = managedEventDao.getById(mid)
            if (me != null && me.calendarEventId == cid) managedEventDao.delete(me)
        }
        return ActionResult.OK
    }

    /** 撤销 UPDATE：用 beforeSnapshot 恢复系统日历与 managed。 */
    private suspend fun revertUpdate(action: BatchEventActionEntity, source: EventSource): ActionResult {
        val before = EventSnapshot.fromJson(action.beforeSnapshot) ?: return ActionResult.OK
        val cid = action.calendarEventIdBefore
        if (cid != null) {
            val ok = calendarWriter.updateEvent(source, cid, before)
            if (!ok) {
                batchActionDao.update(action.copy(state = BatchActionState.REVERT_FAILED, errorCode = "CALENDAR_UPDATE_FAILED"))
                return ActionResult.fail("CALENDAR_UPDATE_FAILED")
            }
        }
        action.managedEventId?.let { mid ->
            managedEventDao.getById(mid)?.let { me ->
                managedEventDao.update(
                    me.copy(
                        contentHash = before.contentHash,
                        calendarEventId = cid,
                        title = before.title,
                        location = before.location,
                        description = before.description,
                        startMillis = before.millisStart(),
                        endMillis = before.millisEnd(),
                        reminderMinutes = before.reminderMinutes,
                        status = ManagedStatus.ACTIVE,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        return ActionResult.OK
    }

    /** 撤销 DELETE：重建系统事件（token 幂等，不重复创建）并恢复 managed ACTIVE。 */
    private suspend fun revertDelete(action: BatchEventActionEntity, source: EventSource): ActionResult {
        val before = EventSnapshot.fromJson(action.beforeSnapshot) ?: return ActionResult.OK
        // R2：已保存重建后的 ID 直接复用；否则先按 token 找回，避免重复 insert
        val newCid = when {
            action.calendarEventIdAfter != null -> action.calendarEventIdAfter
            else -> {
                val found = action.operationToken?.let { calendarWriter.findEventByOperationToken(it) }
                when {
                    found != null && found.ambiguousTokenMatch -> {
                        batchActionDao.update(action.copy(state = BatchActionState.REVERT_FAILED, errorCode = "TOKEN_AMBIGUOUS"))
                        return ActionResult.fail("TOKEN_AMBIGUOUS")
                    }
                    found != null -> found.calendarEventId
                    else -> {
                        val token = action.operationToken ?: ("op-" + java.util.UUID.randomUUID())
                        val cid = calendarWriter.insertEvent(source, before, token)
                        if (cid != null) {
                            // 首次重建成功立即写回 ID 与 token，避免中断后重复 insert
                            batchActionDao.update(action.copy(calendarEventIdAfter = cid, operationToken = token))
                        }
                        cid
                    }
                }
            }
        }
        if (newCid == null) {
            batchActionDao.update(action.copy(state = BatchActionState.REVERT_FAILED, errorCode = "CALENDAR_INSERT_FAILED"))
            return ActionResult.fail("CALENDAR_INSERT_FAILED")
        }
        action.managedEventId?.let { mid ->
            managedEventDao.getById(mid)?.let { me ->
                managedEventDao.update(
                    me.copy(
                        status = ManagedStatus.ACTIVE,
                        calendarEventId = newCid,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        return ActionResult.OK
    }

    /** 正向 CREATE 恢复：已有 cid 时核对真实状态（F5）；无 cid 时按 token 找回（R2），未找到才插入，不重复创建。 */
    private suspend fun applyCreate(batch: ImportBatchEntity, action: BatchEventActionEntity): ActionResult {
        val after = EventSnapshot.fromJson(action.afterSnapshot) ?: return ActionResult.fail("RECOVER_NO_SNAPSHOT")
        val event = after.copy(source = batch.sourceOf())
        // 情况 A：日历操作已发生（CALENDAR_APPLIED 且 cid 已落库）→ 核对系统真实状态，不重复插入
        if (action.calendarEventIdAfter != null) {
            val cid = action.calendarEventIdAfter
            if (!calendarWriter.eventExists(cid)) {
                // 系统事件曾丢失：不盲目创建，标记 FAILED 由用户重新导入
                batchActionDao.update(action.copy(state = BatchActionState.FAILED, errorCode = "RECOVER_MISSING_EVENT"))
                return ActionResult.fail("RECOVER_MISSING_EVENT")
            }
            val done = action.managedEventId?.let { managedEventDao.getById(it) } != null
            batchActionDao.update(
                action.copy(
                    state = if (done) BatchActionState.DB_APPLIED else BatchActionState.FAILED,
                    errorCode = if (done) null else "RECOVER_NO_MANAGED"
                )
            )
            return if (done) ActionResult.OK else ActionResult.fail("RECOVER_NO_MANAGED")
        }
        // 情况 B：ID 未落库（R2）→ 按 token 找回；未找到才插入
        val found = action.operationToken?.let { calendarWriter.findEventByOperationToken(it) }
        return when {
            found != null && found.ambiguousTokenMatch -> {
                batchActionDao.update(action.copy(state = BatchActionState.FAILED, errorCode = "TOKEN_AMBIGUOUS"))
                ActionResult.fail("TOKEN_AMBIGUOUS")
            }
            found != null -> {
                // 已存在：复用 ID 并补写 managed / DB_APPLIED
                val newId = upsertManaged(event.toManaged(batch.sourceOf(), found.calendarEventId, batch.id))
                batchActionDao.update(
                    action.copy(managedEventId = newId, calendarEventIdAfter = found.calendarEventId, state = BatchActionState.DB_APPLIED)
                )
                ActionResult.OK
            }
            else -> {
                val cid = calendarWriter.insertEvent(batch.sourceOf(), event, action.operationToken)
                if (cid == null) {
                    batchActionDao.update(action.copy(state = BatchActionState.FAILED, errorCode = "CALENDAR_INSERT_FAILED"))
                    return ActionResult.fail("CALENDAR_INSERT_FAILED")
                }
                val newId = upsertManaged(event.toManaged(batch.sourceOf(), cid, batch.id))
                batchActionDao.update(
                    action.copy(managedEventId = newId, calendarEventIdAfter = cid, state = BatchActionState.DB_APPLIED)
                )
                ActionResult.OK
            }
        }
    }

    /** 正向 UPDATE 恢复：与系统真实状态比对，补写/重试/人工确认；成功必须写 ACTIVE（R5）。 */
    private suspend fun applyUpdate(batch: ImportBatchEntity, action: BatchEventActionEntity): ActionResult {
        val cid = action.calendarEventIdBefore ?: return ActionResult.fail("RECOVER_NO_CID")
        val before = EventSnapshot.fromJson(action.beforeSnapshot)
        val after = EventSnapshot.fromJson(action.afterSnapshot)
        val snap = calendarWriter.getEvent(cid)
        if (snap == null) {
            // 系统事件已不存在：标记 BROKEN，人工处理
            action.managedEventId?.let { mid ->
                managedEventDao.getById(mid)?.let { me ->
                    managedEventDao.update(me.copy(status = ManagedStatus.BROKEN, updatedAt = System.currentTimeMillis()))
                }
            }
            batchActionDao.update(action.copy(state = BatchActionState.FAILED, errorCode = "RECOVER_EVENT_MISSING"))
            return ActionResult.fail("RECOVER_EVENT_MISSING")
        }
        val matchesAfter = after != null && snap.startMillis == after.millisStart() && snap.endMillis == after.millisEnd()
        val matchesBefore = before != null && snap.startMillis == before.millisStart() && snap.endMillis == before.millisEnd()
        return when {
            matchesAfter -> {
                updateManagedFrom(action, after!!, cid, ManagedStatus.ACTIVE)
                batchActionDao.update(action.copy(state = BatchActionState.DB_APPLIED))
                ActionResult.OK
            }
            matchesBefore -> {
                // 系统仍是旧状态：安全重试 UPDATE
                val ok = calendarWriter.updateEvent(batch.sourceOf(), cid, after ?: return ActionResult.fail("RECOVER_NO_SNAPSHOT"))
                if (!ok) {
                    batchActionDao.update(action.copy(state = BatchActionState.FAILED, errorCode = "RECOVER_UPDATE_FAILED"))
                    return ActionResult.fail("RECOVER_UPDATE_FAILED")
                }
                updateManagedFrom(action, after!!, cid, ManagedStatus.ACTIVE)
                batchActionDao.update(action.copy(state = BatchActionState.DB_APPLIED))
                ActionResult.OK
            }
            else -> {
                // 第三种状态：不自动覆盖
                batchActionDao.update(action.copy(state = BatchActionState.FAILED, errorCode = "RECOVER_NEEDS_REVIEW"))
                ActionResult.fail("RECOVER_NEEDS_REVIEW")
            }
        }
    }

    /** 正向 DELETE 恢复（R3/R4）：删除后核验真实状态，失败不得记成功。 */
    private suspend fun applyDelete(action: BatchEventActionEntity): ActionResult {
        val cid = action.calendarEventIdBefore
        if (cid == null) {
            // R4：无 cid 不能默认成功；结合 managed 状态判断是否已完成
            val me = action.managedEventId?.let { managedEventDao.getById(it) }
            if (me != null && me.status == ManagedStatus.CANCELLED) {
                batchActionDao.update(action.copy(state = BatchActionState.DB_APPLIED))
                return ActionResult.OK
            }
            batchActionDao.update(action.copy(state = BatchActionState.FAILED, errorCode = "CALENDAR_DELETE_NO_CID"))
            return ActionResult.fail("CALENDAR_DELETE_NO_CID")
        }
        if (!calendarWriter.eventExists(cid)) {
            // 系统事件已不存在：补写取消状态
            action.managedEventId?.let { mid ->
                managedEventDao.getById(mid)?.let { me ->
                    managedEventDao.update(
                        me.copy(status = ManagedStatus.CANCELLED, calendarEventId = null, updatedAt = System.currentTimeMillis())
                    )
                }
            }
            batchActionDao.update(action.copy(state = BatchActionState.DB_APPLIED))
            return ActionResult.OK
        }
        calendarWriter.deleteEvent(cid)
        if (calendarWriter.eventExists(cid)) {
            // R4：删除未生效 → FAILED，保留映射
            batchActionDao.update(action.copy(state = BatchActionState.FAILED, errorCode = "CALENDAR_DELETE_NOT_EFFECTIVE"))
            return ActionResult.fail("CALENDAR_DELETE_NOT_EFFECTIVE")
        }
        action.managedEventId?.let { mid ->
            managedEventDao.getById(mid)?.let { me ->
                managedEventDao.update(
                    me.copy(status = ManagedStatus.CANCELLED, calendarEventId = null, updatedAt = System.currentTimeMillis())
                )
            }
        }
        batchActionDao.update(action.copy(state = BatchActionState.DB_APPLIED))
        return ActionResult.OK
    }

    /** 用目标事件同步 managed 行（恢复 UPDATE 时，成功必须写 ACTIVE）。 */
    private suspend fun updateManagedFrom(action: BatchEventActionEntity, e: UnifiedEvent, cid: Long, status: String) {
        action.managedEventId?.let { mid ->
            managedEventDao.getById(mid)?.let { me ->
                managedEventDao.update(
                    me.copy(
                        contentHash = e.contentHash,
                        calendarEventId = cid,
                        title = e.title,
                        location = e.location,
                        description = e.description,
                        startMillis = e.millisStart(),
                        endMillis = e.millisEnd(),
                        reminderMinutes = e.reminderMinutes,
                        status = status,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
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
            reminderMinutes = reminderMinutes,
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
            reminderMinutes = reminderMinutes,
            status = ManagedStatus.ACTIVE,
            lastSeenBatchId = batchId,
            createdAt = now,
            updatedAt = now
        )
    }

    /**
     * v2 F9：按 (source, identityKey) 先查后写，复用已有主键，避免 REPLACE 重建主键
     * 破坏 batch_event_action.managedEventId 对 managed_event 的引用。
     */
    private suspend fun upsertManaged(event: ManagedEventEntity): Long {
        val existing = managedEventDao.getByIdentity(event.source, event.identityKey)
        return if (existing != null) {
            managedEventDao.update(event.copy(id = existing.id, createdAt = existing.createdAt))
            existing.id
        } else {
            managedEventDao.insert(event)
        }
    }

    private fun UnifiedEvent.millisStart(): Long = CourseTime.toMillis(startTime)

    private fun UnifiedEvent.millisEnd(): Long = CourseTime.toMillis(endTime)

    private fun ImportBatchEntity.sourceOf(): EventSource = EventSource.valueOf(source)

    /** F6：从解析事件构建导入范围。仅用可导入（无 blocker）事件计算日期窗口。 */
    private fun buildImportScope(events: List<UnifiedEvent>, source: EventSource): ImportScope {
        val usable = events.filter { it.blocker == null }
        val dateFrom = usable.minOfOrNull { it.startTime.toLocalDate() }
        val dateTo = usable.maxOfOrNull { it.startTime.toLocalDate() }
        val semester = if (source == EventSource.UNIVERSITY) usable.firstNotNullOfOrNull { it.semester } else null
        return ImportScope(semester = semester, dateFrom = dateFrom, dateTo = dateTo)
    }

    companion object {
        /** 超时判定：进程中断超过该时长视为需要恢复 */
        private const val RECOVER_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
