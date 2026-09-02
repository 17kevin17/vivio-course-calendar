# vivo Course Calendar

Android 本地课程日历应用，用于将高校课表和兼职排课 Excel 解析后写入系统日历。

## 功能

- 通过文件头识别 HSSF `.xls` 与 OOXML 工作簿，不依赖文件扩展名。
- 自动区分校内网格课表和兼职明细课表。
- 解析课程名称、教师、教室、日期、节次、学生、课节状态及起止时间。
- 支持春季、夏季两套作息配置，每天最多五个大节。
- 导入前提供事件预览、校对、排除和冲突提示。
- 通过 Android Calendar Provider 创建校内课程和兼职课程日历。
- 使用 Room 保存作息配置、导入批次和事件映射。
- 支持事件去重、更新与按导入批次撤销。
- 核心数据在本地处理，不依赖网络服务。

## 技术栈

| 组件 | 版本或用途 |
|---|---|
| Kotlin | 2.0.21 |
| Jetpack Compose | Material 3 UI |
| Room | 2.6.1 |
| Apache POI | 5.2.5，解析 HSSF 与 OOXML |
| Gradle | 8.9 |
| Android Gradle Plugin | 8.7.3 |
| Android SDK | minSdk 26 / compileSdk 35 / targetSdk 35 |
| Java | 17 |

## 工程结构

```text
app/src/main/java/com/vivio/coursecalendar/
├── data/
│   └── local/          Room 数据库、DAO 与实体
├── domain/
│   ├── calendar/       Calendar Provider 写入
│   ├── import/         去重、冲突检测与导入管理
│   ├── model/          统一事件模型
│   ├── parser/         Excel 格式识别与课表解析
│   └── schedule/       春夏作息配置
├── ui/
│   ├── home/
│   ├── import/
│   ├── history/
│   ├── navigation/
│   ├── schedule/
│   └── theme/
└── util/
```

## 构建

环境要求：

- JDK 17
- Android SDK 35
- Android Studio 或 Gradle Wrapper

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Debug APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 权限

应用使用以下 Android 日历权限：

```xml
android.permission.READ_CALENDAR
android.permission.WRITE_CALENDAR
```

系统日历事件提醒由 Calendar Provider 的 Reminders 数据表配置。

## Excel 解析

### 校内课表

解析按周、日期和大节展开的网格课表。日期缺少年份时，从学年标题推断；无法确定年份或作息时，将事件标记为不可直接导入。

### 兼职排课

解析“学员排课”工作表中的课节名称、学生、状态、课节 ID 和精确上课时间。待上课记录可导入系统日历，已结课记录默认作为历史数据处理。

## 样表回归基线

- 校内课表：136 个课程单元格。
- 兼职排课：14 条记录，其中 11 条待上课、3 条已结课。

真实 Excel 样表包含个人信息，不纳入公开仓库。

## 已知技术限制

- Calendar Provider 的创建、更新、撤销和提醒行为仍需要在 vivo / OriginOS 真机验证。
- 跨批次事件更新依赖稳定事件身份，课程调课等模糊匹配场景可能需要人工确认。
- Apache POI 在 Android Release 构建中的体积、内存和混淆兼容性需要持续测试。
- 原子岛未实现，项目不依赖非公开 vivo API。
- 仓库和 Android 包名当前使用 `vivio`，展示名称使用 `vivo Course Calendar`。

## 文档

详细架构和实现状态见 [ARCHITECTURE.md](ARCHITECTURE.md)。
