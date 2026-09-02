# 阶段技术报告：完整性修复冲刺（T1-T9 + Release 修复 + T10 冒烟中途）

> 生成日期：2026-09-02
> 对应交接包：`handoff/vivo_course_calendar_integrity_handoff/`
> 分支：main，最新提交 `58bd219`（远端 HEAD 一致，已推送）

---

## 一、执行状态总览

| 任务 | 状态 | 说明 |
|---|---|---|
| 第1步 基线 | ✅ 完成 | 25 测试 + assembleDebug 通过，schema v3 |
| 第2步 作息配置 | ✅ 完成 | 复合主键 + 约束校验 + 测试 |
| 第3步 兼职 ID 精度 | ✅ 完成 | 字符串读取 + 逐字符断言 |
| 第4步 identityKey/contentHash | ✅ 完成 | 规范化组件 + 解析器更新 |
| 第5步 持久化重构 | ✅ 完成 | managed_event + batch_event_action + import_batch 状态机 |
| 第6步 DiffEngine 重写 | ✅ 完成 | 7 状态纯内存 DiffPlan |
| 第7步 执行与撤销 | ✅ 完成 | 按动作类型逆操作 + 幂等 |
| 第8步 故障恢复 | ✅ 完成 | 启动扫描未完成批次 + 恢复策略 |
| 第9步 自动化测试 | ✅ 完成 | 45 用例全绿（0 fail / 0 err） |
| Release 验证 | ✅ 完成 | assembleRelease 通过，R8 修复 |
| 第10步 vivo 真机验收 | ⏸ 中断 | 见第五节，冒烟第 1 步进行中 |

---

## 二、修改文件清单

### 提交 1：`61b5fc6 refactor: 完整性修复冲刺 T1-T8+T11/T12`
覆盖 T1-T8、T11/T12 的全部领域层与数据层改动（见下）。

### 提交 2：`58bd219 fix: Release R8 缺失类告警 + T9 测试基础设施`
- `app/build.gradle.kts`：+Robolectric 依赖与 testOptions 配置
- `app/proguard-rules.pro`：+`-dontwarn` 规则（POI/xmlbeans 可选依赖缺失类）
- `app/src/test/resources/robolectric.properties`：`sqliteMode=NATIVE`
- `app/src/test/java/.../TestDb.kt`：单线程 executor 内存库（线程亲和）
- 测试改造：`DiffEngineTest` / `ImportManagerTest` / `ScheduleConfigDaoTest` 改用 TestDb
- `.gitignore`：忽略 `.robo-home/`、`VSM-1.1.7.jar`、日志

### 核心源码（T1-T8/T11/T12，位于 `61b5fc6`）
- 数据层：`data/local/entity/{ScheduleConfigEntity, ImportBatchEntity, ManagedEventEntity, BatchEventActionEntity}.kt`、`data/local/dao/AppDao.kt`、`AppDatabase.kt`（v3）
- 领域层：`domain/identity/{EventIdentity, Normalizer}.kt`、`domain/import/{DiffEngine, ImportManager, EventSnapshot, ImportPreview}.kt`、`domain/calendar/{CalendarWriter, CalendarGateway}.kt`、`domain/time/CourseTime.kt`、`domain/schedule/ScheduleConfig.kt`
- 解析器：`domain/parser/{UniversityScheduleParser, PartTimeScheduleParser, ScheduleParser, FormatDetector, ExcelIO}.kt`
- UI：`ui/import/*`、`ui/history/*`、`ui/schedule/*`、`ui/home/HomeScreen.kt`、`ui/navigation/AppNavHost.kt`

---

## 三、数据库 schema 变化

Room **version 3**（未发布版本，采用 `fallbackToDestructiveMigration` 清库重建，符合交接包《02》第五节策略）。

| 表 | 主要变化 |
|---|---|
| `schedule_config` | 复合主键 `(season, periodNumber)`，替代单列主键；新增时段重叠约束校验 |
| `managed_event` | 新增；一 identityKey 对应一行，记录 identityKey/contentHash、来源、事件状态与系统日历事件 ID |
| `batch_event_action` | 新增；每批次每 identityKey 一条动作，含 actionType、before/after 快照、执行状态 |
| `import_batch` | 改为状态机：PLANNED / PARTIAL / APPLIED / FAILED / REVERTED |

---

## 四、核心算法变化与测试结果

### 4.1 身份与差异
- **拆分 identityKey / contentHash**：identityKey 用于逻辑身份匹配，contentHash 用于内容比对，算法带版本号。
- **DiffEngine 重写**：纯内存生成 DiffPlan，区分 NEW / UNCHANGED / MODIFIED / CANCELLED / MISSING / AMBIGUOUS / INVALID；MISSING 与 AMBIGUOUS 不自动删除/更新。
- **Normalizer**：日期、星期、教室、教师字段规范化。

### 4.2 执行与撤销
- 先落 PLANNED 操作 → 写 CalendarProvider → 标记 APPLIED；对每条操作保存 before/after。
- 撤销按动作类型逆操作（CREATE→删、UPDATE→还原、DELETE→重建、NOOP→不变），幂等可重试。

### 4.3 故障恢复
- 启动扫描 PARTIAL 批次与未完成动作；Calendar 与 Room 之间的中断点可恢复；无法确定状态进入人工处理，不自动复制事件。

### 4.4 测试结果（testDebugUnitTest）
10 个测试类，**45 用例全部通过（failures=0, errors=0）**：

| 测试类 | 用例数 |
|---|---|
| ScheduleAndFingerprintTest | 9 |
| DiffEngineTest | 6 |
| ImportManagerTest | 5 |
| PartTimeScheduleParserTest | 5 |
| ScheduleValidationTest | 5 |
| ScheduleConfigDaoTest | 4 |
| UniversityScheduleParserTest | 4 |
| ConflictDetectorTest | 4 |
| SampleIntegrationTest | 2 |
| LessonIdPrecisionCheckTest | 1 |

关键断言覆盖：同 key 同 hash→UNCHANGED；兼职时间变→MODIFIED；校内教师/教室变→MODIFIED；新逻辑事件→NEW；旧事件缺失→MISSING；多候选→AMBIGUOUS；连续导入不重复；撤销幂等；18 位课节 ID 逐字符一致。

---

## 五、构建与 Release 验证

| 项目 | 结果 |
|---|---|
| `assembleDebug` | ✅ BUILD SUCCESSFUL，app-debug.apk **24.33 MB** |
| `testDebugUnitTest` | ✅ 45/45 通过 |
| `assembleRelease` | ✅ BUILD SUCCESSFUL（R8 + 资源收缩），app-release-unsigned.apk **5.79 MB** |
| R8 缺失类 | ✅ 已修复：POI/xmlbeans 引用的可选类（saxon/javaparser/maven/ant/graphbuilder/awt）加入 `-dontwarn`；仅剩一条非阻断 warning |
| 测试环境 | Robolectric NATIVE SQLite + `.robo-home` 缓存目录（沙箱外） |

---

## 六、第 10 步 vivo 真机冒烟（中断中）

### 6.1 设备环境（已记录）
- 机型：**vivo V2324A（PD2324）**
- Android：**16**（build compiler260715211935）
- 系统：**OriginOS 6**（ro.vivo.os.build.display.id）
- 日历：**com.bbk.calendar 7.5.3.4（versionCode 7534, minSdk 29, targetSdk 35）**
- 屏幕：1260x2800，density 560

### 6.2 已完成步骤
1. ✅ 全新安装：`adb install -r app-debug.apk` 成功（首次安装，uninstall 报 DELETE_FAILED_INTERNAL_ERROR 属预期）
2. ✅ App 启动：`am start -n .../.MainActivity`，topResumedActivity 确认为本应用
3. ✅ 样表已推送：`/sdcard/Download/校内课表.xls`（download (5).xls，校内）、`/sdcard/Download/兼职课表.xls`（download (6).xls，兼职）

### 6.3 中断原因
- 本机模型不支持直接读图，且 `uiautomator dump` 在真机被系统 Kill（exit 137，多次尝试无效）。
- 无浏览器/模拟器可用（SDK 未安装 emulator 组件），Playwright MCP 为浏览器工具不适配 Android。
- 已采用「坐标点击 + 系统日历 content 查询」方案推进，进入导入页成功（截图 hash 变化），但继续盲操作文件选择器存在误触风险，经确认后**按用户要求中断**。

### 6.4 尚未验证的行为（后续真机必须执行）
按交接包《05》第五节顺序：
1. 导入校内课表（需选季节）→ 预览 → 确认
2. 导入兼职课表
3. vivo 日历核对随机 10 条事件
4. 再次导入相同文件，数量不变（幂等）
5. 修改教室/时间后的文件导入 → 更新原事件
6. 撤销修改批次 → 恢复原内容
7. 撤销首次兼职导入 → 只删对应事件
8. 拒绝日历权限 → 预览仍可用
9. 清后台、重启手机 → 事件与提醒正常

---

## 七、可复现的已知问题

1. **Kotlin daemon 连接失败**（环境相关）：偶发 `Could not connect to Kotlin compile daemon` + `AccessDeniedException: C:\Users\kkbru\AppData\Local\kotlin\daemon\...tmp`。缓解：`gradlew --stop` 后重试（已在本次会话中复现并处理）。判断与沙箱对 daemon 目录的访问限制有关，需在沙箱外运行或配置白名单。
2. **R8 missing class**（已修复）：POI/xmlbeans 可选依赖类在 classpath 缺失，R8 全量模式将其报为错误；已通过 `-dontwarn` 抑制。运行时不触碰这些可选功能，风险低。
3. **uiautomator dump 真机不可用**：被系统 Kill（exit 137），真机 UI 自动化受阻，冒烟需人工或坐标点击辅助。
4. **`uninstall` 首次报 DELETE_FAILED_INTERNAL_ERROR**：首次未安装场景下属预期，非缺陷。

---

## 八、下一任务建议

1. **完成 T10 真机冒烟**：建议在有屏设备/人工配合下完成第五节 9 步；期间可用 `adb shell content query --uri content://com.android.calendar/events` 客观核对系统日历事件写入。
2. 冒烟通过后：更新项目状态文档（README/ARCHITECTURE 中的验收记录），标注真机环境与结果。
3. Release 正式签名（当前为 unsigned），如需分发。
4. 处理 Kotlin daemon 环境问题：考虑在 gradle.properties 固定 `kotlin.compiler.execution.strategy=in-process` 规避 daemon 连接失败。

---

## 十、第二轮修复（v2 交接包 F1-F5 P0）已完成

> 交接包：`handoff/vivo_course_calendar_integrity_handoff_v2.zip`
> 提交范围：`f1f3bc0` ~ `686490b`

### 10.1 修复清单与验证

| ID | 问题 | 修复 | 验证 |
|---|---|---|---|
| F1 | 校内 identityKey 碰撞，136 事件被 `distinctBy` 静默丢 18 条 | 新增 `universityOccurrenceKey`（含实际日期）；`parseAndPreview` 移除 `distinctBy`，重复 key 显式标记 AMBIGUOUS + 排除 | 诊断断言 136 事件 → 136 唯一 key，碰撞组数 0 |
| F2 | `CourseStatus.CANCELLED` 未转 DELETE | DiffEngine 先处理业务状态：已存在→CANCELLED；不存在→UNCHANGED+排除（不创建） | DiffEngine 8 用例、ImportManager 取消相关用例通过 |
| F3 | 撤销不跳过 REVERTED、DELETE 重复 insert | `undo` 跳过 REVERTED；DELETE 重建成功即保存新 ID；UPDATE 失败标记 REVERT_FAILED | 撤销 DELETE 重建不重复创建、撤销 NOOP 不变，用例通过 |
| F4 | `recover()` 从未接入 | `VivioApp.onCreate` IO 协程启动时执行 | RecoveryTest 覆盖启动路径调用 |
| F5 | 恢复无法核对真实状态 | `CalendarGateway` 增加 `eventExists/getEvent`；recover 按 CREATE/UPDATE/DELETE 核对系统状态，managed_event 指向不存在事件标记 BROKEN | RecoveryTest 4 用例通过 |

### 10.2 测试规模

- 修复前 45 用例（10 类）→ 修复后 **56 用例（12 类）全绿**（0 fail / 0 err）

### 10.3 尚未验证（真机依赖）

- Release 构建在 vivo 真机解析两类样表（F10）
- 启动恢复器在真机中断场景下的实际行为
- vivo CalendarProvider 是否保留自定义字段（03 文档建议，未实现自定义 token 字段）

---

## 十一、待办（v2 P1 项 F6-F10）

| ID | 问题 | 状态 |
|---|---|---|
| F6 | MISSING 无导入范围（学期/日期窗口） | ✅ 完成 |
| F7 | 提醒未进入最终哈希/快照 | ✅ 完成 |
| F8 | Calendar 时区字段不统一 | ✅ 完成 |
| F9 | managed_event 用 REPLACE | ✅ 完成 |
| F10 | R8 规则过宽 | ✅ 规则收窄完成（见 14.1） |
| F10 | Release 真机解析样表验证 | ⏸ 待真机（T10 冒烟时一并执行） |

---

## 十二、可复现的已知问题（更新）

1. **Kotlin daemon 连接失败**（环境相关）：已在 `gradle.properties` 固定 `kotlin.compiler.execution.strategy=in-process`，规避 daemon 连接失败。
2. **R8 missing class**（已修复）：POI/xmlbeans 可选依赖类经 `-dontwarn` 抑制；F10 需在真机 Release 解析验证兜底。
3. **uiautomator dump 真机不可用**：被系统 Kill（exit 137），冒烟需人工或坐标点击辅助。

---

## 十三、交接包要求的后续审查材料（更新）

- ✅ 完整源码仓库 / 提交范围：本地 `main` @ `686490b`（GitHub 推送待网络恢复；F6-F10 改动未提交）
- ✅ Room schema JSON：`app/schemas/com.vivio.coursecalendar.data.local.AppDatabase/4.json`（已导出，v4 新增 reminderMinutes）
- ✅ 单元测试输出：`app/build/test-results/testDebugUnitTest/`（12 类 62 用例全绿）
- ✅ Debug/Release 构建输出：app-debug.apk 24.33MB / app-release-unsigned.apk 5.80MB（F10 收窄后）
- ⏸ vivo 真机测试记录：**尚未执行完成，明确标注待办**

---

## 十四、第三轮修复（v2 交接包 F6-F10 P1）已完成（真机验证待续）

> 交接包：`handoff/vivo_course_calendar_integrity_handoff_v2/`
> 提交范围：本地 `686490b` 之后未提交（改动见 14.2）

### 14.1 修复清单与验证

| ID | 问题 | 修复 | 验证 |
|---|---|---|---|
| F6 | MISSING 无导入范围 | 新增 `ImportScope`（semester/dateFrom/dateTo/isCompleteSnapshot）；DiffEngine `compute` 增加 `scope` 参数，MISSING 事件按学期/日期窗口过滤，避免跨学期误标 | DiffEngineTest 新增 3 用例：局部日期范围不影响范围外事件、范围内缺失仍提示不自动删除、导入新学期不把旧学期标成 MISSING |
| F7 | 提醒未进入最终哈希/快照 | `UnifiedEvent.withFinalReminder` 按 source 重算 contentHash 并写入最终 reminderMinutes；ImportManager commit 应用最终提醒，UPDATE 分支持久化 reminderMinutes；managed_event 新增列 | ImportManagerTest 新增 2 用例：改提醒→MODIFIED 且 managed 哈希与最终提醒一致、同提醒重复导入不产生更新 |
| F8 | Calendar 时区字段不统一 | CalendarWriter 的 DTSTART/DTEND 解释与 `EVENT_TIMEZONE`/`EVENT_END_TIMEZONE` 均统一为 `CourseTime.ZONE`（Asia/Shanghai） | 字段核对 |
| F9 | managed_event 用 REPLACE | `AppDao.insert` 去 REPLACE 改为 ABORT；ImportManager 新增 `upsertManaged`（先查后 insert/update，保留原主键与 createdAt） | ImportManagerTest 新增 1 用例：重复插入不 REPLACE 重建主键 |
| F10 | R8 规则过宽 | 基于 `build/outputs/mapping/release/missing_rules.txt` 将宽泛 `-dontwarn`（org.apache.poi.** / org.apache.commons.** / java.awt.** 等 13 条通配）收窄为 **700 条精确类清单**；删除本就不触发告警的宽规则（org.apache.poi.**、org.apache.commons.**、org.etsi.**、org.apache.logging.log4j.**、com.graphbuilder.**） | `assembleRelease` BUILD SUCCESSFUL（2m6s）；R8 缺失类告警 **0**（missing_rules.txt 不再生成）；APK 5.80MB；仅剩 1 条非阻断类型检查告警（`SVGUserAgent.getViewbox()`，POI 的 SVG 渲染可选功能，运行时不用） |

### 14.2 改动文件

- 领域层：`domain/import/ImportPreview.kt`（ImportScope）、`domain/import/DiffEngine.kt`（scope 过滤）、`domain/import/ImportManager.kt`（withFinalReminder/upsertManaged）、`domain/model/UnifiedEvent.kt`（withFinalReminder + teacher/student 扩展）、`domain/calendar/CalendarWriter.kt`（时区统一）
- 数据层：`data/local/entity/ManagedEventEntity.kt`（reminderMinutes）、`data/local/dao/AppDao.kt`（去 REPLACE）、`data/local/AppDatabase.kt`（v3→v4）
- R8：`proguard-rules.pro`（精确 -dontwarn 收窄）

### 14.3 测试规模

- 修复前 56 用例（12 类）→ 修复后 **62 用例（12 类）全绿**（0 fail / 0 err），本轮 +6：
  - DiffEngineTest +3（F6）
  - ImportManagerTest +3（F7×2、F9×1）

### 14.4 数据库 schema v4

`managed_event` 新增 `reminderMinutes` 列（版本 3→4，沿用 `fallbackToDestructiveMigration` 清库重建策略，未发布版本）。

### 14.5 尚未验证（真机依赖，F10 剩余部分）

- Release 构建在 vivo 真机解析两类样表（F10 完成标准）
- 提醒修改后的系统日历提醒同步
- R8 收窄后 Release 运行（临时测试签名安装）

### 14.6 R8 收窄说明

- 无法按类精确收窄的规则已随精确清单一并覆盖，均注明触发来源为 POI/xmlbeans 可选依赖（java.awt、javax.imageio、pdfbox、batik、saxon、javaparser、bouncycastle、ant、maven、osgi、xml-security 等），Android 运行时不存在且不调用，仅被可选功能引用。
- 保留 POI/xmlbeans/microsoft.schemas/openxmlformats 的 `-keep`（运行时必需，不可混淆）。
- 兜底：Release 真机解析样表验证。

---

## 十五、综合审查（v2 F1-F10 全量复核）

> 审查范围：`f1f3bc0` ~ 当前工作区（F1-F10 全部改动），对照《01》验收标准 /《02》设计 /《04》测试矩阵。
> 审查方式：逐文件核对源码 + 双子代理交叉验证（2/2 一致）。

### 15.1 验收标准对照

| ID | 完成标准 | 结论 |
|---|---|---|
| F1 | 解析→预览仍为 136 条 | ✅ identityKey 唯一 136（F1DiagnosisTest）；重复 key 显式标 AMBIGUOUS，不静默丢弃 |
| F2 | 已存在取消→CANCELLED；不存在→忽略 | ✅ DiffEngine 状态优先于哈希；不存在→excluded UNCHANGED，不创建 |
| F3 | 任意动作后中断均可继续 | ✅ undo 跳过 REVERTED；DELETE 重建立即保存新 ID，幂等 |
| F4 | 启动自动扫描 | ✅ VivioApp.onCreate → importManager.recover() |
| F5 | CREATE/UPDATE/DELETE 均可核对真实状态 | ✅ eventExists/getEvent + recoverCreate/Update/Delete 三路径 |
| F6 | 只在同学期/日期窗口内计算 MISSING | ✅ ImportScope + DiffEngine.inScope（见 15.2 Issue2） |
| F7 | 改提醒→MODIFIED，撤销恢复旧提醒 | ✅ withFinalReminder 重算 hash；managed 持久化；undo 恢复旧提醒（见 15.2 Issue3） |
| F8 | DTSTART/DTEND 与 EVENT_TIMEZONE 统一 Asia/Shanghai | ✅ CalendarWriter 插入/更新均统一 CourseTime.ZONE |
| F9 | 明确 insert/update/upsert，避免主键重建 | ✅ DAO 去 REPLACE + upsertManaged 先查后写 |
| F10 | Release 运行样表 + 收窄 dontwarn | ✅ R8 规则收窄（700 精确类，缺失类告警 0）；真机运行待 T10 |

### 15.2 发现的问题（双代理已验证）

| No. | 严重度 | 问题 | 位置 | 建议 |
|---|---|---|---|---|
| 1 | Major | 取消事件"粘性"：已 CANCELLED 的 managed 行被同内容 PENDING 事件判为 UNCHANGED→NOOP，已取消课程无法重导恢复；且 UPDATE 分支 `existing.copy(...)` 未重置 status=ACTIVE，对 CANCELLED 行重建系统事件后 managed 仍标 CANCELLED，状态不一致 | DiffEngine.kt L76-82；ImportManager.kt L256-269 | ① PENDING 命中 CANCELLED 行不再判 UNCHANGED（视为恢复/重建）；② UPDATE 分支 update 增加 `status = ACTIVE` |
| 2 | Minor | F6 inScope 用 `contains("\|semester\|")` 松散子串匹配身份段，理论上可能误匹配课程名等其它段 | DiffEngine.kt L146-150 | 按 "\|" 拆分 identityKey 取第 2 段（学期）精确比较 |
| 3 | Low | F7 `reminderMinutes ?: this.reminderMinutes` 依赖"解析器恒产出 null"的隐含约定；当前流程"关闭"可正确清除提醒（回退仍 null→hash 变→MODIFIED），但未来若解析器携带默认提醒则"关闭"失效 | UnifiedEvent.kt L97 | 显式区分"未设置/显式关闭"，或注释说明该约定 |

### 15.3 测试矩阵（《04》）逐条对照

- 已覆盖：136 条链路（F1DiagnosisTest）、身份/取消/撤销中断/执行恢复/导入范围/提醒 MODIFIED/数据库约束均有对应用例。
- 未单测（逻辑已实现，建议补）：
  - 《04》七节「撤销恢复 10 分钟提醒」：undo UPDATE 已恢复 `before.reminderMinutes`（ImportManager.kt L366），无独立用例。
  - 《04》八节「fallback destructive migration 仅限未发布构建」：设计约定，无单测。

### 15.4 审查结论

- F1-F10 验收标准全部达成（F10 真机解析待 T10 冒烟一并执行）。
- 全量 12 类 62 用例全绿；Release 构建通过、R8 缺失类告警 0。
- Issue1 超出交接包验收范围（交接包未要求"取消后重导恢复"），属数据一致性建议项，建议顺手修复后再提交。
- Issue2/Issue3 为低风险健壮性建议，不影响当前验收。
