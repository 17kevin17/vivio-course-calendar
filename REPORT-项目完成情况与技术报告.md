# 项目完成情况与技术报告（全量整合）

> 生成日期：2026-09-03
> 仓库：`17kevin17/vivio-course-calendar`
> 合并基线：`main` @ `5d109ae`（Merge pull request #3，N1-N12 已并入）
> 覆盖范围：T1-T12 基线 → v2 交接包 F1-F10 → 下一轮 R1-R6 → vivo 验收交接 N1-N12

---

## 一、项目完成情况总览

| 阶段 | 交接包 | 工作内容 | 提交范围 | 状态 |
|---|---|---|---|---|
| T1 基线 | — | 基线 25 测试 + assembleDebug | `1d694dc`~`61b5fc6` 前 | ✅ |
| T1-T8 + T11/T12 | v1 | 作息配置、兼职 ID 精度、身份/哈希、持久化重构、DiffEngine 重写、执行与撤销、故障恢复、测试 | `61b5fc6` | ✅ |
| Release + T9 | v1 | R8 缺失类修复、Robolectric 测试基础设施 | `58bd219` | ✅ |
| F1-F5（P0） | v2 | 校内身份碰撞（occurrence key）、取消转 DELETE、撤销中断幂等、恢复器接入启动、真实状态核对 | `f1f3bc0`~`686490b` | ✅ |
| F6-F10（P1） | v2 | 导入范围（ImportScope）、提醒进最终哈希、时区统一 Asia/Shanghai、managed_event 去 REPLACE、R8 规则收窄（700 精确类） | `134e954`~`7904393` | ✅ |
| R1-R6 | 下一轮指示 | 恢复状态机闭环：APPLYING/UNDOING 分离、operation token、DELETE 完整链路、批次集中汇总、取消恢复开课 | `fee2855`~`05cc2d9` | ✅ |
| N1-N12 | vivo 验收交接 | 恢复闭环 P0/P1 修复（见第三节） | `f2c3ea5`~`6648ffe` | ✅ |
| 合并 | — | `wip/f6-f10` → `main`（PR #3） | `5d109ae` | ✅ |
| T10 真机 | — | vivo Release 验收 | — | ⏸ 待真机 |

**结论**：自动化闭环全部完成（F1-F10 中 F10 真机后半待验、N1-N12 全部修复），阶段判定从"有条件不通过"升级为"可进入真机验收"。

---

## 二、技术报告（单一基线）

### 2.1 基线信息

| 项 | 值 |
|---|---|
| 合并基线 | `main` @ `5d109ae` |
| Room schema | **v5**（`app/schemas/.../5.json`） |
| 测试规模 | **14 类 / 82 用例**，全绿（0 fail / 0 err） |
| Debug 构建 | `assembleDebug` BUILD SUCCESSFUL，24.34 MB |
| Release 构建 | `assembleRelease` BUILD SUCCESSFUL（R8 + 资源收缩），5.80 MB |
| R8 缺失类告警 | **0**（700 条精确 `-dontwarn`，依据 `missing_rules.txt`）；仅剩 1 条非阻断类型检查告警（SVGUserAgent，POI 可选功能） |
| vivo 真机 | vivo V2324A（PD2324）/ Android 16 / OriginOS 6；日历 com.bbk.calendar 7.5.3.4 |

### 2.2 核心设计

- **身份与内容分离**：`identityKey`（跨批次稳定身份，校内含实际日期 `universityOccurrenceKey`）+ `contentHash`（内容哈希，含提醒、教师/学员等可见字段）。
- **状态机**：`import_batch`（PREPARED/APPLYING/APPLIED/PARTIAL/UNDOING/UNDONE/FAILED）+ `batch_event_action`（PLANNED/CALENDAR_APPLIED/DB_APPLIED/FAILED/REVERTED/REVERT_FAILED）；批次阶段由动作最终状态集中汇总。
- **跨存储幂等**：所有 insert 路径（CREATE、UPDATE 重建、DELETE 撤销重建）在调用 CalendarProvider 前预持久化 `operationToken`（写 SYNC_DATA1），统一经 `findOrInsertByToken` 先查后插入；崩溃后按 token 找回，不重复/不孤儿。
- **恢复方向分离**：APPLYING 正向重放（applyAction），UNDOING 逆向执行（revertAction 与 undo 共用），均单动作结构化结果。
- **生命周期语义**：CANCELLED↔PENDING 显式转换（恢复开课强制 MODIFIED + ACTIVE），撤销"恢复开课"删除新事件还原 CANCELLED/null。
- **时区统一**：DTSTART/DTEND 解释与 EVENT_TIMEZONE/END 均 Asia/Shanghai。
- **数据库约束**：managed_event 去 REPLACE（insert/update/upsert 明确），避免主键重建破坏 action 引用。

### 2.3 已通过项（自动化验证，14 类 82 用例）

- 解析/身份：校内 136 条身份唯一不静默丢失（F1）、课节 ID 精度、规范化。
- 差异：NEW/UNCHANGED/MODIFIED/CANCELLED/MISSING/AMBIGUOUS/INVALID 七态；导入范围学期精确匹配（F6）。
- 执行/撤销：连续导入幂等、撤销 CREATE/UPDATE/DELETE 幂等、撤销恢复提醒、取消恢复开课生命周期。
- 恢复：APPLYING/UNDOING 分离、CREATE token 找回、UPDATE 完整快照比较、DELETE 完整链路、批次集中汇总、快照缺失报错、undo 结果一致。
- 提醒：两态最终值语义（null=显式关闭、Int=分钟值）；提醒可核验（回读）。
- 时区/DAO/R8：字段统一、主键不重建、700 精确类告警 0。
- 压力：导入 20 次稳定、取消/恢复/撤销循环 10 次无孤儿、recover 10 次不漂移。

### 2.4 待验证项（真机依赖）

| 项 | 说明 |
|---|---|
| F10 后半：Release 真机解析双样表 | 校内预览 136 条、兼职导入；重复导入不增；修改标题/教室/时间/提醒更新原事件 |
| operation token（SYNC_DATA1）保留性 | 若 vivo CalendarProvider 不保留同步字段 → 恢复闭环不成立，需换标识方案（唯一外部阻塞项） |
| 真机中断恢复 | 正向导入/撤销中强制结束进程，重启自动恢复 |
| 提醒同步/重启核对 | 系统日历提醒、时区、事件数量 |

### 2.5 已知缺陷 / 风险

| 项 | 影响 | 处置 |
|---|---|---|
| SVGUserAgent 类型检查告警 | 非阻断，POI SVG 可选功能运行时不用 | 保留，Release 真机解析兜底 |
| kotlin daemon 偶发失败（Windows 文件锁） | 偶发编译/clean 中断 | `kotlin.compiler.execution.strategy=in-process`；必要时 `--stop` 释放 |
| uiautomator 真机不可用（exit 137） | 真机 UI 自动化受阻 | 冒烟需人工/坐标辅助 |
| operation token 真机保留性未验证 | 唯一可能阻塞恢复闭环 | vivo 步骤 8 前置验证 |
| BROKEN→PENDING 自动 REPAIR | 当前保守人工处理 | 交接包允许 REPAIR 或人工确认 |

---

## 三、本轮 vivo 验收交接工作整合（N1-N12）

> 交接包：《下一阶段完整性闭环与 vivo 验收交接》，审查 `wip/f6-f10`@`05cc2d9`，判定"有条件不通过"。
> 处置：按文档步骤 0-10 完成修复、测试、回归、合并。

### 3.1 P0（合并阻塞，已修复 + 真实路径测试）

| 编号 | 问题 | 修复 | 测试 |
|---|---|---|---|
| N1 | 撤销 DELETE 的 token 未在真实插入前持久化 | 所有 insert 路径统一预持久化 token；`findOrInsertByToken` 单一入口（先查后插，FOUND/CREATED/AMBIGUOUS/FAILED） | 撤销DELETE重建崩溃后 token 已落库不重复创建 |
| N2 | UPDATE 恢复只比较起止时间 | `CalendarEventSnapshot` 扩展完整可见字段 + 提醒；`matchesVisibleFields` 完整比较（标题/地点/描述/时间/提醒/时区） | UPDATE只改标题/只改提醒写前中断 |
| N3 | 撤销"恢复开课"留下孤儿事件 | beforeSnapshot 保留 managed 生命周期（toEvent 映射 CANCELLED）；revertUpdate 对 CANCELLED+null 删除 after 新事件、还原 CANCELLED/null | 恢复开课后撤销恢复CANCELLED不留孤儿 |
| N4 | CREATE 已写 cid 未写 managed 不能自动补写 | applyCreate 系统事件与 after 完整匹配时自动 upsertManaged + DB_APPLIED | CREATE已写cid未写managed恢复自动补映射 |

### 3.2 P1（已修复）

| 编号 | 问题 | 修复 |
|---|---|---|
| N5 | 撤销 CREATE 不核验删除结果 | revertCreate 删除后 eventExists 核验；失败→REVERT_FAILED/CALENDAR_DELETE_NOT_EFFECTIVE；token 歧义→TOKEN_AMBIGUOUS |
| N6 | UPDATE 重建路径无 token | applyUpdate 对 cid=null（恢复开课/重建）经 findOrInsertByToken，崩溃后不重复 |
| N7 | undo() 无条件返回 true | undo 返回 `phase==UNDONE`，与批次最终状态一致 |
| N8 | 缺失/损坏快照被当作成功 | revertUpdate/revertDelete 快照缺失→REVERT_FAILED/SNAPSHOT_MISSING |
| N9 | 提醒无结果核验 | getEvent 回读提醒；updateEvent 更新后回读核验，不一致返回 false |
| N10 | 删除前先删提醒 | deleteEvent 不提前破坏提醒，依赖 Provider 级联 |
| N11 | SYNC_DATA1 查询范围过宽 | findEventByOperationToken 限定本应用两个 calendar ID |

### 3.3 P2（已处理）

- N12：报告表述收紧——F7 改为"两态最终值语义（null=显式关闭、Int=分钟值）"；中断覆盖如实标注。

### 3.4 附带修复（新发现）

- `EventSnapshot` null 字段经 `JSONObject.NULL` + `optString` 反序列化为字符串 `"null"` 的 bug（已用 `isNull` 判断修复）。
- `commit()` 批次更新 `batch.copy` 缺 `id` 导致 phase 静默不更新（已补 `id=batchId`）。

### 3.5 压力与回归

- **StressTest**：同一文件导入 20 次事件数稳定；取消/恢复/撤销循环 10 次无孤儿；recover 连续 10 次状态不漂移。
- 全量：`clean testDebugUnitTest assembleDebug assembleRelease` 通过，82 用例全绿。

---

## 四、合并与交付状态

| 项 | 状态 |
|---|---|
| 分支推送 | ✅ `wip/f6-f10` → 远端 `6648ffe` |
| 合并 | ✅ PR #3 → `main` @ `5d109ae`（用户手动合并） |
| 本地 main 同步 | ✅ fast-forward 到 `5d109ae`，工作区干净 |
| schema | ✅ v4、v5 JSON 均已提交 |
| 单元测试产物 | ✅ `app/build/test-results/testDebugUnitTest/`（14 类 82 用例） |
| Debug/Release APK | ✅ 24.34 MB / 5.80 MB |
| 阶段技术报告 | ✅ [REPORT-阶段技术报告.md](REPORT-阶段技术报告.md)（单一基线） |

提交清单（`wip/f6-f10`，已并入 main）：
`134e954` F6-F9 源码 · `708defe` F6-F9 测试 · `7904393` R8 收窄 · `4d7b440` 交接材料 · `fee2855` R1-R6 状态机闭环+schema v5 · `05cc2d9` 崩溃边界测试 · `501e6cf` 单一基线报告 · `f2c3ea5` N1-N8 · `cf67e34` N9-N11 · `0cb600d` N1-N12 测试 · `6648ffe` 报告更新

---

## 五、待办与阻塞项

1. **vivo 真机验收（T10，唯一剩余）**——按交接包步骤 8/9：
   - 先验证 SYNC_DATA1 token 保留性（未通过则暂停恢复闭环验收，换标识方案）；
   - Release 安装后解析双样表（校内 136 条）、重复导入、更新、取消/恢复/撤销、中断恢复、重启核对。
2. **BROKEN→PENDING 自动 REPAIR**：当前保守人工处理，可评估后续自动修复。
3. 可选：删除已合并的 `wip/f6-f10` 分支（本地 + 远端）。

---

## 六、投入使用前阶段 A（U1-U6 收尾，已完成）

> 交接包：《投入使用前阶段工作与具体处理步骤》，判定 RC0 → 需先完成阶段 A（真机前代码收尾）。
> 分支：`release/0.2.0-rc1-vivo-validation`（自 `main@5d109ae`）

### 6.1 修复清单（U1-U6）

| 编号 | 级别 | 问题 | 修复 | 测试 |
|---|---|---|---|---|
| U1 | P0 | 撤销"恢复开课"删除结果未核验 | 统一 `deleteAndConfirm`（删除后回读 eventExists）；失败 → REVERT_FAILED/CALENDAR_DELETE_NOT_EFFECTIVE，managed 保持 ACTIVE + after ID | U1撤销恢复开课删除失败REVERT_FAILED且managed保留ACTIVE |
| U2 | P1 | 撤销 DELETE 复用已保存 ID 未核验事件存在 | revertDelete 回读 getEvent；存在且与 before 完整匹配才复用；丢失按 token 重建；内容不匹配 → RECOVER_CONTENT_MISMATCH | U2撤销DELETE复用ID但事件已删除时按token重建 |
| U3 | P1 | CREATE 提醒无回读验证 | commit CREATE 插入后回读核验提醒（REMINDER_MISMATCH → failed/PARTIAL，recover 经 token 找回不重复）；CalendarWriter.insertEvent 回读兜底 | U3CREATE提醒写入失败不得假成功 |
| U4 | P1 | 损坏快照路径状态不落库 | 统一 `failAction(action, state, errorCode)`；空快照/非法 JSON/缺 ID 全部持久化 | U4空快照恢复必须持久化FAILED |
| U5 | P1 | 权限撤销/Provider 异常缺兜底 | VivioApp 启动恢复权限闸门；commit/undo/recover 捕获 SecurityException → PROVIDER_ACCESS_DENIED；ViewModel 异常映射 Failed（提示重新授权） | U5Provider权限异常不崩溃且动作失败落库 |
| U6 | P2 | 声明了不需要的通知权限 | 删除 POST_NOTIFICATIONS | — |

### 6.2 版本与验证

- 版本：`versionCode=2`、`versionName=0.2.0-rc1`（候选构建）
- 全量：`clean testDebugUnitTest assembleDebug assembleRelease` 通过，**15 类 / 87 用例全绿**（新增 U1-U5 回归）
- Debug 24.34MB / Release 5.80MB，R8 缺失类告警 0（仅已知 SVGUserAgent 非阻断）
- 提交：`8bcbfc2` 测试 · `af2e795` U1-U5 修复 · `aa4b127` U5 权限 · `e363605` U6 权限声明 · `e16cee3` 版本

### 6.3 下一步（阶段 B）

- 候选签名构建 RC APK（需用户提供 keystore）
- vivo 真机：SYNC_DATA1 保留性前置闸门 → Release 双样表 → 幂等/修改/提醒 → 生命周期/撤销 → 中断恢复 → 权限/系统行为
- 阶段 C（分发）：正式签名、非破坏性迁移、CI

---

> 依据交接包闸门条件：仅当 vivo token 保留性 + Release 双样表 + 真机中断恢复全部通过，方可写"完整性修复完成"。
