# 阶段技术报告：完整性修复（单一基线）

> 生成日期：2026-09-02
> 对应交接包：`handoff/下一轮完整性修复工作指示.md`（R1-R8）
> 分支：`wip/f6-f10`，HEAD `05cc2d97aebbad942be519b90db10710fe82075a`
> 审查基线：GitHub `main` @ `dc9794f74163ed84535ee7cf67b2be812d83504e`

---

## 一、基线信息

| 项 | 值 |
|---|---|
| 分支 | `wip/f6-f10`（本地，未推送） |
| HEAD SHA | `05cc2d97aebbad942be519b90db10710fe82075a` |
| Room schema | **v5**（`app/schemas/.../5.json`） |
| 测试规模 | **13 类 / 71 用例**，全绿（0 fail / 0 err） |
| Debug 构建 | `assembleDebug` BUILD SUCCESSFUL，app-debug.apk **24.34 MB** |
| Release 构建 | `assembleRelease` BUILD SUCCESSFUL（R8 + 资源收缩），app-release-unsigned.apk **5.80 MB** |
| R8 缺失类告警 | **0**（700 条精确 `-dontwarn`，来源 `missing_rules.txt`）；仅剩 1 条非阻断类型检查告警（`SVGUserAgent.getViewbox()`） |
| vivo 真机 | vivo V2324A（PD2324）/ Android 16 / OriginOS 6；日历 com.bbk.calendar 7.5.3.4 |

提交范围（`wip/f6-f10`，自 `dc9794f` 起）：
1. `134e954` feat(import): F6-F9 完整性修复 - 导入范围/提醒哈希/时区统一/去REPLACE + schema v4
2. `708defe` test(import): F6-F9 回归测试
3. `7904393` build: 收窄并记录 R8 抑制规则（700 精确类）
4. `4d7b440` docs: 阶段报告与 handoff 交接材料
5. `fee2855` fix(import): 恢复状态机闭环 R1-R6（拆分 APPLYING/UNDOING、operation token、DELETE 完整性、取消恢复开课）+ schema v5
6. `05cc2d9` test(import): R1-R6 崩溃边界与生命周期回归

---

## 二、已通过项（自动化验证）

| 项 | 结论 | 证据（测试类 / 提交） |
|---|---|---|
| 校内样表解析预览 136 条，身份唯一不静默丢失（F1） | ✅ | F1DiagnosisTest；`f1f3bc0` |
| 取消状态识别（F2）：已存在→CANCELLED/DELETE；不存在→忽略不创建 | ✅ | DiffEngineTest、ImportManagerTest |
| 撤销中断幂等（F3）：REVERTED 跳过、DELETE 重建不重复 | ✅ | ImportManagerTest |
| 恢复器接入启动（F4）：VivioApp.onCreate → recover() | ✅ | RecoveryTest |
| 恢复核对真实状态（F5）：eventExists/getEvent 三路径 | ✅ | RecoveryTest |
| 导入范围（F6）：MISSING 按学期/日期窗口；学期段**精确匹配**（非松散 contains） | ✅ | DiffEngineTest（导入新学期/局部日期范围） |
| 提醒进入最终哈希/快照（F7）：改提醒→MODIFIED、撤销恢复；**三态语义明确（null=显式关闭）** | ✅ | ImportManagerTest（提醒变化/同提醒重复/撤销恢复提醒） |
| 时区统一（F8）：DTSTART/DTEND 与 EVENT_TIMEZONE/END 均 Asia/Shanghai | ✅ | CalendarWriter 字段核对 |
| managed_event 去 REPLACE（F9）：insert/update/upsert 明确，主键不重建 | ✅ | ImportManagerTest（重复插入不重建主键） |
| R8 规则收窄（F10 前半）：700 精确类，缺失类告警 0 | ✅ | Release 构建输出（missing_rules.txt 不再生成） |
| R1：APPLYING 与 UNDOING 恢复方向分离；UNDOING 只能进 UNDONE/PARTIAL | ✅ | RecoveryIntegrityTest（UNDOING中断后恢复→UNDONE） |
| R2：CREATE/重建 operation token 幂等；崩溃后按 token 找回不重复创建 | ✅ | RecoveryIntegrityTest（token 找回/崩溃后仅一条事件） |
| R3：DELETE 恢复后 managed 同步 CANCELLED 并清空 calendarEventId | ✅ | RecoveryIntegrityTest（DELETE恢复后managed CANCELLED） |
| R4：DELETE 失败不计成功；action FAILED + batch PARTIAL + 映射保留 | ✅ | RecoveryIntegrityTest（DELETE失败批次PARTIAL） |
| R5：取消课重新开课（CANCELLED→PENDING）强制恢复 ACTIVE | ✅ | ImportManagerTest（取消/恢复/再取消生命周期）；DiffEngineTest |
| R6：批次阶段集中汇总；FAILED 动作不得让批次 APPLIED/UNDONE | ✅ | RecoveryIntegrityTest（recoverCreate FAILED 批次 PARTIAL） |
| 恢复幂等：连续调用 recover 三次不增删事件 | ✅ | RecoveryIntegrityTest |
| commit 批次阶段更新主键修复（新发现 bug：`batch.copy` 缺 id 致 phase 静默不更新） | ✅ | RecoveryIntegrityTest（崩溃重启恢复） |

---

## 三、待验证项（真机依赖）

| 项 | 说明 | 对应交接包要求 |
|---|---|---|
| F10 后半：Release 真机解析两类样表 | Release 构建已通过；需在 vivo 安装并解析校内（136 条）/兼职样表 | 《05》/ 下一轮步骤 7 |
| operation token 在 vivo CalendarProvider 的同步字段（SYNC_DATA1）是否保留 | 代码已写入并实现按 token 查询；**必须真机验证**。若 vivo 不保留，需停止宣称恢复闭环并替换可查询标识方案 | R2 完成标准 |
| 真机中断恢复：正向导入/撤销中强制结束进程，重启自动恢复 | 单测已模拟（FaultyGateway crash）；需真机复现 | 步骤 7 用例 7 |
| 真机提醒同步：修改/关闭提醒后系统日历提醒正确 | 逻辑已实现；需真机核对 | 步骤 7 用例 4 |
| 重启手机后事件、时区、提醒核对 | — | 步骤 7 用例 8 |

---

## 四、已知缺陷 / 风险

| 项 | 影响 | 处置 |
|---|---|---|
| R8：SVGUserAgent.getViewbox() 类型检查告警 | 非阻断；POI 的 SVG 渲染可选功能，Android 运行时不用 | 保留，Release 真机解析兜底 |
| kotlin daemon 连接失败（环境相关） | 偶发编译中断 | `gradle.properties` 已固定 `kotlin.compiler.execution.strategy=in-process` |
| uiautomator dump 真机不可用（exit 137） | 真机 UI 自动化受阻 | 冒烟需人工或坐标点击辅助 |
| operation token 真机保留性未验证 | 若 vivo 丢弃同步字段则恢复闭环不成立 | 步骤 7 用例 9 前置验证 |

---

## 五、F1-F10 验收对照

| ID | 完成标准 | 状态 |
|---|---|---|
| F1 | 解析→预览仍为 136 条 | ✅ 自动化通过（真机双样表仍待） |
| F2 | 已存在取消→CANCELLED；不存在→忽略 | ✅ |
| F3 | 任意动作后中断均可继续 | ✅ |
| F4 | 启动自动扫描 | ✅ |
| F5 | CREATE/UPDATE/DELETE 均可核对真实状态 | ✅ |
| F6 | 只在同学期/日期窗口内计算 MISSING | ✅（精确学期匹配） |
| F7 | 改提醒→MODIFIED，撤销恢复旧提醒 | ✅（三态语义明确） |
| F8 | 时区字段统一 Asia/Shanghai | ✅ |
| F9 | 明确 insert/update/upsert，避免主键重建 | ✅ |
| F10 | Release 运行样表验证 + 收窄 dontwarn | ⏸ **未完成**：R8 收窄已完，真机解析样表待 T10 |

> F10 未全部完成，故按交接包闸门条件，**不判定 F1-F10 全部通过**，亦不写"完整性修复完成"。

---

## 六、测试矩阵对照（下一轮指示《5》）

| 类别 | 覆盖 | 证据 |
|---|---|---|
| 正向 CREATE：Calendar 后 ID 落库前中断 | ✅ | RecoveryIntegrityTest（token 找回） |
| 正向 DELETE：删除后 managed 前中断 | ✅ | RecoveryIntegrityTest（DELETE 恢复后 managed CANCELLED） |
| 正向 DELETE：Provider 删除失败 | ✅ | RecoveryIntegrityTest（DELETE 失败批次 PARTIAL） |
| 撤销 CREATE/UPDATE/DELETE 中断窗口 | ✅ | ImportManagerTest（撤销幂等）+ RecoveryIntegrityTest（UNDOING 恢复） |
| 撤销 DELETE：重建后 ID 落库前中断 | ✅ | RecoveryIntegrityTest（撤销 DELETE 按 token 找回） |
| 批次汇总：任一动作 FAILED | ✅ | RecoveryIntegrityTest（recoverCreate FAILED） |
| 生命周期：CANCELLED→PENDING | ✅ | ImportManagerTest（取消/恢复/再取消） |
| 范围：新学期导入旧学期不 MISSING | ✅ | DiffEngineTest |
| 提醒：10 分钟→关闭→10 分钟 | ✅ | ImportManagerTest（撤销恢复提醒）；F7 三态语义 |
| BROKEN→PENDING REPAIR | ⏸ 未单测 | 按系统真实状态人工处理（R5 表格） |

FakeCalendarGateway 能力（下一轮指示要求）均已实现于 `FaultyCalendarGateway.kt`：指定失败、模拟崩溃、按 token 查询、重复 token、真实存在检查、调用计数。

---

## 七、未解决问题

1. **operation token 真机保留性**：唯一可能阻塞恢复闭环的外部依赖，需 vivo 真机验证 CalendarProvider 是否保留 SYNC_DATA1。若不保留，需设计替代可查询标识方案（交接包 R2 明示）。
2. **BROKEN→PENDING 自动 REPAIR**：当前保守标记人工处理，未实现自动修复（交接包允许 REPAIR 或人工确认）。
