# Release 与 vivo 真机验收

## 一、Release状态表述

当前只能声明：

```text
assembleRelease 构建通过。
```

不能声明：

```text
POI/xmlbeans R8兼容问题已完全修复。
```

因为大量 `-dontwarn` 只是压制缺失类告警，必须通过压缩后的 Release 运行验证。

## 二、Release验证准备

- 配置临时测试签名，生成可安装的 Release APK。
- 保留 R8 mapping 和 missing_rules 输出。
- 在真机安装 Release，而不是只测试 Debug。
- 记录 APK 体积、峰值内存和解析耗时。

## 三、vivo 验收顺序

1. 全新安装 Release 测试包。
2. 导入校内样表，预览数量必须为136。
3. 导入校内课表并随机核对10条。
4. 导入兼职课表，确认11条待上课按规则处理。
5. 重复导入，系统事件数量不增加。
6. 导入含取消状态的脱敏样表，确认删除逻辑。
7. 修改教室/时间后更新原事件。
8. 撤销UPDATE，恢复原标题、地点、时间和提醒。
9. 撤销DELETE，不重复重建。
10. 人为终止导入或撤销后重启，验证recover。
11. 清后台、重启手机，检查系统日历与提醒。

## 四、系统日历客观核对

可用 ADB 查询：

```bash
adb shell content query --uri content://com.android.calendar/events
adb shell content query --uri content://com.android.calendar/reminders
```

核对字段：calendar_id、title、dtstart、dtend、eventTimezone、事件数量及提醒分钟数。

注意：不要在公开日志中保存完整学生姓名或原始描述字段。

## 五、R8规则复查

优先收窄以下过宽规则：

```proguard
-dontwarn org.apache.poi.**
-dontwarn org.apache.commons.**
-dontwarn java.awt.**
```

无法收窄的规则必须在注释中写明触发来源、为何运行时不会调用，并有Release真机解析测试兜底。

