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

## 九、交接包要求的后续审查材料

- ✅ 完整源码仓库 / 提交范围：GitHub `17kevin17/vivio-course-calendar` @ `58bd219`
- ⚠️ Room schema JSON：`exportSchema=false`，未导出（如需可临时开启）
- ✅ 单元测试输出：`app/build/test-results/testDebugUnitTest/`（10 类 45 用例全绿）
- ✅ Debug/Release 构建输出：app-debug.apk 24.33MB / app-release-unsigned.apk 5.79MB
- ⏸ vivo 真机测试记录：**尚未执行完成，明确标注待办**
