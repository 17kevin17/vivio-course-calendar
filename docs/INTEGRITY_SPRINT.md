# MVP 完整性修复冲刺

## 目标

把当前“解析可用原型”推进到“可以安全进行 vivo 真机日历测试”的状态。本阶段不扩展 P1/P2 功能。

## P0 阻断任务

| ID | 任务 | 完成标准 |
|---|---|---|
| T1 | 修复 `schedule_config` 主键 | 使用 `(season, periodNumber)`，春/夏各可保存五条记录 |
| T2 | 拆分事件身份与内容哈希 | 内容变化仍能匹配同一个逻辑事件 |
| T3 | 修复18位课节 ID 读取 | 与工作簿原始字符串逐字符一致 |
| T4 | 建立长期 `managed_event` 映射 | 一个逻辑事件只有一个当前日历映射 |
| T5 | 新增 `batch_event_action` | CREATE/UPDATE/DELETE/NOOP 全部可追溯 |
| T6 | 重写差异算法 | NEW/UNCHANGED/MODIFIED/CANCELLED/MISSING/AMBIGUOUS 正确 |
| T7 | 重写撤销 | UPDATE 恢复旧快照，只有 CREATE 才直接删除 |
| T8 | 增加中断恢复 | CalendarProvider 与 Room 之间中断后可以安全恢复 |
| T9 | 补核心测试 | 去重、更新、撤销、幂等和故障恢复有自动化证明 |
| T10 | vivo 真机验收 | 连续导入、修改、撤销和提醒均通过 |

## 数据模型调整

### 作息配置

同一季节包含五个大节，主键必须是：

```kotlin
@Entity(
    tableName = "schedule_config",
    primaryKeys = ["season", "periodNumber"]
)
```

### 事件身份

禁止继续用单一 `eventFingerprint` 同时表示身份和内容：

- `identityKey`：判断新旧记录是不是同一个逻辑事件。
- `contentHash`：判断同一事件的标题、地点、时间等是否改变。

兼职课程优先以原始课节 ID 作为身份。读取数值 ID 时禁止 `Double → Long → String`。

校内课程没有上游事件 ID，应使用确定性匹配加低置信度候选匹配；无法唯一匹配时进入人工确认。

### 长期事件与批次操作

建议将长期状态和导入历史拆开：

- `managed_event`：当前受应用管理的逻辑事件和 CalendarProvider ID。
- `import_batch`：一次导入的总体状态。
- `batch_event_action`：每个事件在该批次中的 CREATE/UPDATE/DELETE/NOOP 及 before/after 快照。

## 导入状态机

```text
PREPARED → APPLYING → APPLIED
                    ↘ PARTIAL
                    ↘ FAILED

APPLIED/PARTIAL → UNDOING → UNDONE
```

执行顺序：

1. 生成完整 DiffPlan。
2. 在 Room 落库批次和全部 PLANNED 操作。
3. 逐条修改 CalendarProvider。
4. 保存返回的 calendarEventId 和操作状态。
5. 更新 managed_event。
6. 全部完成后再将批次标为 APPLIED。

先记录操作意图，再修改系统日历。

## 撤销语义

| 原操作 | 撤销动作 |
|---|---|
| CREATE | 删除新建事件 |
| UPDATE | 使用 beforeSnapshot 恢复旧事件 |
| DELETE | 使用 beforeSnapshot 重建事件 |
| NOOP | 不操作 |
| MARK_MISSING | 恢复旧状态标记 |

撤销必须幂等，重复执行不能误删或重复创建。

## 自动化验收

- [ ] 同一文件连续导入三次，系统事件数量不增加。
- [ ] 兼职课时间变化但课节 ID 相同，结果为 MODIFIED。
- [ ] 校内教师或教室变化，能够更新原事件。
- [ ] 撤销 UPDATE 恢复原标题、地点和时间。
- [ ] 撤销 CREATE 只删除本批次新建事件。
- [ ] 导入中断后不存在不可追踪的系统日历事件。
- [ ] 样表回归保持：校内136个课程单元格；兼职14条记录。
- [ ] Debug、Unit Test、Release 构建均通过。

## vivo 真机冒烟步骤

1. 全新安装。
2. 导入校内课表。
3. 导入兼职课表。
4. 随机核对10条系统日历事件。
5. 重复导入相同文件，确认数量不变。
6. 导入修改过教室和时间的脱敏样表。
7. 撤销修改批次，确认恢复旧内容。
8. 撤销首次兼职导入，确认只删除对应事件。
9. 拒绝日历权限，确认解析预览仍可用。
10. 清后台并重启设备，检查事件与提醒。

## 本阶段边界

暂不实现：

- 原子岛
- ICS 导入导出
- 桌面组件
- 云同步和账号
- 完整隐私设置页面

最低隐私约束保持不变：不联网、不在日志输出完整 ID、真实样表不进入公开仓库。
