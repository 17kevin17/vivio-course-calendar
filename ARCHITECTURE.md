# 课程日历 App —— 架构与技术细节审查文档

> 生成日期：2026-09-02
> 目的：审查当前 MVP 的架构、技术细节与验收状态，确认后再进入下一步。

---

## 一、项目概览

| 项 | 内容 |
|---|---|
| 项目 | vivo 大学生课程日历（校内课表 + 兼职排课 → 系统日历） |
| 核心链路 | 选择 Excel → 自动识别格式 → 解析 → 预览校对 → 去重/冲突检测 → 写入系统日历 → 更新或撤销 |
| 技术栈 | 原生 Android · Kotlin · Jetpack Compose · Room · Apache POI |
| 构建 | Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21 / KSP |
| SDK | compileSdk 35 · minSdk 26（Android 8+）· targetSdk 35 |
| 构建产物 | `app/build/outputs/apk/debug/app-debug.apk` |
| 数据存储 | 全部本地（Room + 系统日历），无网络依赖 |

---

## 二、任务与模块架构总表

对应交接包《02》开发阶段 1–8，外加测试与提醒。

| 阶段 | 任务 | 模块 / 文件 | 职责与技术要点 | 状态 |
|---|---|---|---|---|
| 1 | 工程骨架 | `build.gradle.kts`、`gradle/`、`MainActivity.kt`、`VivioApp.kt`、`ui/theme`、`ui/navigation` | Gradle 配置（含 POI 所需 desugaring）、Compose 主题、4 个路由导航 | ✅ |
| 2 | 数据层 | `data/local/AppDatabase.kt`、`dao/AppDao.kt`、`entity/*` | Room 三张表：`schedule_config`、`import_batch`、`event_mapping`（详见第四节） | ✅ |
| 3 | 文件读取与格式识别 | `domain/parser/ExcelIO.kt`、`FormatDetector.kt`、`util/FileFingerprint.kt` | 按文件头 magic bytes 区分 OOXML/HSSF（不受扩展名误导）；关键词打分识别校内/兼职；SHA-256 文件指纹 | ✅ |
| 4 | 校内课表解析 | `domain/parser/UniversityScheduleParser.kt` | 网格自适应：日期行定位列、大节行归属、学年推断年份、多行单元格清洗、合并单元格、中文楼名教室（详见第六节） | ✅ |
| 5 | 兼职课表解析 | `domain/parser/PartTimeScheduleParser.kt` | 「学员排课」工作表；列名模糊匹配；跳过内部 ID 列；`上课时间` 范围拆分；状态映射；无结束时间默认 45 分钟 | ✅ |
| 6 | 作息配置与事件模型 | `domain/schedule/ScheduleConfig.kt`、`domain/model/UnifiedEvent.kt`、`domain/import/EventFingerprint.kt` | 春/夏各五大节（100 分钟事件）；统一事件模型；事件指纹 | ✅ |
| 7 | 校验/去重/冲突 | `domain/import/DedupEngine.kt`、`ConflictDetector.kt` | 指纹去重 + 差异比较（UNCHANGED/MODIFIED）；时间重叠冲突标记 | ✅ |
| 8 | 日历写入与更新撤销 | `domain/calendar/CalendarWriter.kt`、`domain/import/ImportManager.kt` | 创建「校内课程」「兼职课程」独立日历；事件增删改；导入批次与映射；按批次撤销 | ✅ |
| 9 | Compose UI | `ui/home`、`ui/import`、`ui/schedule`、`ui/history` | 首页、导入流程（选文件→类型/季节→预览校对→权限→结果）、作息设置、导入记录；深色模式适配 | ✅ |
| 10 | 课前提醒 | 系统日历 `Reminders` + 权限申请 | 关闭/10/20/30 分钟；Android 13+ 通知权限 | ✅ |
| 11 | 测试 | `app/src/test/...`（6 个测试类） | 25 个用例（详见第七节） | ✅ |
| — | 原子岛 | 未实现 | P2 可选适配层，非 MVP 前置（交接包确认） | ⏸ 待定 |

---

## 三、技术栈版本清单

| 组件 | 版本 | 用途 |
|---|---|---|
| Gradle | 8.9 | 构建 |
| Android Gradle Plugin | 8.7.3 | 构建 |
| Kotlin / Compose 插件 | 2.0.21 | 语言与 Compose 编译器 |
| Compose BOM | 2024.12.01 | Material3 UI |
| Room | 2.6.1（KSP） | 本地持久化 |
| Apache POI | 5.2.5（poi + poi-ooxml） | HSSF 二进制 .xls 与 OOXML 解析 |
| desugar_jdk_libs | 2.1.4 | POI 所需核心库脱糖 |
| coroutines | 1.9.0 | 异步 |
| 测试 | JUnit 4.13.2 + coroutines-test | 单元测试 |

---

## 四、数据模型

### 4.1 统一事件模型（`UnifiedEvent`）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | String | 应用内部稳定 ID（由指纹派生） |
| source | Enum | `UNIVERSITY` / `PART_TIME` |
| sourceRecordId | String? | 原始课节 ID（兼职课优先使用） |
| title / description / location | String? | 课程名、描述、教室 |
| startTime / endTime | LocalDateTime | 事件时间 |
| status | Enum | `PENDING` / `COMPLETED` / `CANCELLED` / `UNKNOWN` |
| reminderMinutes | Int? | 提前提醒分钟 |
| sourceFileHash | String? | 来源文件指纹 |
| eventFingerprint | String | 内容去重指纹 |
| calendarEventId | Long? | Android 日历事件 ID |
| rawText | String? | 原单元格文本（校对审计用） |
| periodIndex / weekRange / periodCode | Int? / String? | 校内大节、周次、节次码 |
| blocker | String? | 不可直接导入的原因（非空默认排除） |

### 4.2 Room 表

**schedule_config**（作息配置）

| 字段 | 说明 |
|---|---|
| season (PK) | SPRING / SUMMER |
| periodNumber | 1–5 大节 |
| startMinute / endMinute | 自午夜起分钟数 |
| configVersion | 版本号（修改后提示是否更新已导入课程） |

内置默认：夏季五大节全配置（08:00/10:10/15:00/17:10/20:00 各 100 分钟）；春季仅配置第 1/2/5 大节，下午时段留空（待用户确认，导入前必须补齐）。

**import_batch**（导入批次，用于更新与撤销）

| 字段 | 说明 |
|---|---|
| id (PK auto) | 批次 ID |
| fileHash / fileName | 来源文件指纹与名称 |
| source / season | 来源类型与季节 |
| createdAt | 时间戳 |
| createdCount / updatedCount / unchangedCount / invalidCount | 各状态计数 |
| status | COMPLETED / PARTIAL / FAILED / UNDONE |

**event_mapping**（事件映射，撤销/更新定位依据）

| 字段 | 说明 |
|---|---|
| id (PK auto) | — |
| batchId | 所属批次（唯一索引：batchId + eventFingerprint） |
| source / sourceRecordId | 来源 |
| eventFingerprint | 去重指纹 |
| calendarEventId | 系统日历事件 ID |
| title / location / startMillis / endMillis | 事件快照 |
| state / excluded | 状态与排除标记 |

---

## 五、核心流程

### 5.1 导入主流程

```
选择 Excel(SAF) → 读字节 + SHA-256
  → magic bytes 判定 OOXML/HSSF
  → FormatDetector 识别 校内/兼职
      ├─ 兼职：直接解析（不使用作息表）
      └─ 校内：选季节 → 加载作息 → 校验春季下午是否配置
  → 解析器 → 统一事件模型
  → DedupEngine 对比历史映射（NEW / UNCHANGED / MODIFIED）
  → ConflictDetector 时间重叠标记
  → 预览校对（可排除/编辑，异常默认不写入，原始文本对照）
  → 日历权限（READ/WRITE_CALENDAR + Android13 通知权限）
  → 确认导入：NEW 创建 / MODIFIED 更新原事件 / CANCELLED 删除
  → 记录导入批次 + 事件映射
```

### 5.2 再次导入（差异更新）

- 按 `eventFingerprint` 对比旧批次映射：内容一致 → 跳过；时间/标题/教室变化 → 更新原事件。
- 旧事件未出现在新文件 → 仅提示（`MISSING`），**不自动删除**。

### 5.3 撤销

- 按批次删除该批次映射的日历事件（只删本应用创建的事件），清理映射，批次标记 UNDONE。

### 5.4 写入安全

- 只更新/删除有映射关系的事件；单条失败不回滚其他成功事件（批次状态 PARTIAL）；
- 权限被拒仍可完成解析与预览。

---

## 六、关键设计决策（技术细节）

### 6.1 文件格式识别
- 不信任扩展名：按文件头 `PK\x03\x04`（OOXML）/ `D0 CF 11 E0`（OLE2 二进制）判定，兼职课表「.xls 实为 OOXML」因此正确识别。
- 模板识别用关键词打分：工作表名（学员排课/排课）+ 表头词（课节名称/学员/上课时间/状态 vs 周次/星期/大节/日期），失败时允许用户手动选择类型。

### 6.2 校内课表解析规则
- **网格定位**：扫描日期行（一行 ≥2 个可解析日期、不含换行的单元格）确定日期列；大节行（行首匹配 `第[一二三四五六七1-7]大节/节`）归属其上方最近的日期行。
- **年份推断**：样表日期为 `MM-dd` 无年份。从标题（如「陈志杰2026-2027-1课表」）提取学年对 (y1, y2)，月份 ≥9 取 y1、否则取 y2；无学年信息则不猜测（标记 blocker）。
- **课程单元格**：按换行清洗（处理前导/尾随换行与 `\r`）；第 1 行 = 课程名（保留 `[理论]` 类型）；识别节次码 `n-0102`、周次区间、教室（支持 `A101`、`求是西楼102`、`西校区新联楼0103` 等）、多教师（`王长清,冯惠粉`）。
- **容错**：同一天同一大节多课程进入冲突校验；空大节行忽略；第 6/7 大节行若未配置作息则跳过并告警。

### 6.3 兼职课表解析规则
- 定位「学员排课」工作表；表头行列名模糊匹配（课节名称/学员姓名/主讲/课节状态/课节类型/上课时间）。
- **跳过内部 ID 列**（学员ID/主讲ID/班级ID/课程ID）：既防误映射，也符合隐私规则；`课节number` 识别为课节 ID。
- **时间**：`上课时间` 列支持 `2026-08-31 08:40:00-09:10:00` 范围拆分；无结束时间时从课节名称/类型解析分钟数，否则默认 45 分钟并告警。
- **状态规则**：待上课 → 导入；已结课 → 保留为历史（默认不写入日历）；取消/停课 → CANCELLED；未知 → 进入预览要求用户确认（不静默丢弃）。

### 6.4 事件指纹
- 校内：`source + 日期 + 大节编号 + 规范化课程名 + 规范化教室`（教师变化视为修改，不产生重复事件）。
- 兼职：优先课节 ID；退化 `source + 开始 + 结束 + 学员 + 课节名称`。
- 规范化：全角转半角、去空白、转大写。

### 6.5 日历写入
- 创建两个独立日历：`校内课程`（蓝 #1B6FE0）、`兼职课程`（橙 #E8871E），ACCOUNT_TYPE=LOCAL。
- 事件映射：标题=课程名、地点=教室、描述=教师/学员/来源/状态、时区=设备当前时区。
- 提醒：系统 `Reminders`（METHOD_ALERT），默认只提醒一次。

### 6.6 隐私与本地化
- 全程本地解析与存储；不上传文件/学员姓名/学员 ID/班级 ID。
- 不落库内部 ID（学员ID/主讲ID/班级ID 等）；rawText 仅含可读字段；崩溃不附原始 Excel。

---

## 七、测试覆盖（25 个用例）

| 测试类 | 覆盖点 | 用例数 |
|---|---|---|
| UniversityScheduleParserTest | 网格解析、无年份日期、作息未配置、合并单元格 | 4 |
| PartTimeScheduleParserTest | 待上课/已结课/未知状态、时间范围、ID 退化指纹 | 5 |
| SampleIntegrationTest | **真实样表端到端**：校内 136 事件、兼职 14 事件（年份/教室/100分钟/时间范围/隐私） | 2 |
| DedupEngineTest | 一致→无变化、时间/标题变化→修改、新指纹→新增 | 4 |
| ConflictDetectorTest | 重叠/不重叠/同大节多课/blocker 不参与 | 4 |
| ScheduleAndFingerprintTest | 夏季五大节 100 分钟、春季下午未配置、指纹、规范化 | 6 |

---

## 八、《05-测试与验收清单》状态核对

| 验收项 | 状态 |
|---|---|
| 两份样表均可完成解析预览 | ✅ 集成测试通过（136 + 14 事件） |
| 校内未来课程日期/大节/课程名/教室准确 | ✅ 年份推断、100 分钟、教室、节次码已验证 |
| 兼职「待上课」起止时间准确 | ✅ 范围拆分已验证 |
| 同一文件连续导入三次仍只有一份事件 | ⚠️ 逻辑已实现（指纹+映射），待真机验证 |
| 更新与撤销无误删 | ⚠️ 逻辑已实现，待真机验证 |
| vivo 真机完整导入流程 | ⏸ 未执行 |
| 原子岛不可用不影响其他功能 | ✅ 未依赖隐藏 API |

---

## 九、已知限制与待确认

| 项 | 说明 |
|---|---|
| 春季下午时间 | 待用户确认第 3 大节开始时间；导入春季下午课程前强制引导配置 |
| 第 6/7 大节 | 课表含行但作息仅 5 节，未配置时跳过并告警（交接包确认「一天最多五个大节」） |
| 年份推断依赖标题 | 校内样表日期无年份，靠标题「2026-2027」推断；无标题学年的表会标记异常 |
| 兼职新状态值 | 「调课/冻结」等新值按未知状态处理，进入人工确认 |
| 真机测试 | 日历权限流、锁屏提醒、后台清理、深色模式、字体缩放未验证 |
| ICS 导出 | P1 能力，未实现 |
| 原子岛 | P2，无公开 SDK 前不实现 |
