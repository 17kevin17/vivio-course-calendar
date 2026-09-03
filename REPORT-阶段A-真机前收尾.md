# 阶段 A 技术报告：投入使用前真机收尾（U1-U6）

> 生成日期：2026-09-03
> 交接包：《投入使用前阶段工作与具体处理步骤》
> 分支：`release/0.2.0-rc1-vivo-validation`（自 `main@5d109ae`），HEAD `dea67e1`
> 版本：`versionCode=2` / `versionName=0.2.0-rc1`（候选构建）
> 判定：RC0 → 阶段 A 完成，待 vivo 真机验收（阶段 B）

---

## 一、基线信息

| 项 | 值 |
|---|---|
| 分支 | `release/0.2.0-rc1-vivo-validation`（本地，未推送） |
| HEAD | `dea67e1` |
| Room schema | v5（未变） |
| 测试规模 | **15 类 / 87 用例全绿**（新增 5 个 U 回归） |
| 构建 | `clean testDebugUnitTest assembleDebug assembleRelease` 全过；Debug 24.34MB / Release 5.80MB |
| R8 | 缺失类告警 0（仅已知 SVGUserAgent 非阻断） |

---

## 二、修复清单（U1-U6）

| 编号 | 级别 | 问题 | 修复 | 证据 |
|---|---|---|---|---|
| U1 | P0 | 撤销"恢复开课"删除结果未核验 | 统一 `deleteAndConfirm`（删除后回读 `eventExists`）；失败 → REVERT_FAILED / CALENDAR_DELETE_NOT_EFFECTIVE，managed 保持 ACTIVE + after ID，batch PARTIAL | `ImportManager.revertUpdate`；U1 测试 |
| U2 | P1 | 撤销 DELETE 复用已保存 ID 未核验事件存在 | `revertDelete` 回读 `getEvent`；存在且与 before 完整匹配才复用；丢失按 token 重建；内容不匹配 → RECOVER_CONTENT_MISMATCH | `ImportManager.revertDelete`；U2 测试 |
| U3 | P1 | CREATE 提醒无回读验证 | commit CREATE 插入后回读核验提醒（REMINDER_MISMATCH → failed/PARTIAL，recover 经 token 找回不重复创建）；`CalendarWriter.insertEvent` 回读兜底 | `ImportManager` CREATE 分支；U3 测试 |
| U4 | P1 | 损坏快照路径状态不落库 | 统一 `failAction(action, state, errorCode)`；空快照/非法 JSON/缺 ID 全部持久化，禁止裸 fail | `revertDelete`/`applyCreate`/`applyUpdate`；U4 测试 |
| U5 | P1 | 权限撤销 / Provider 异常缺兜底 | `VivioApp` 启动恢复权限闸门；commit/undo/recover 捕获 SecurityException → PROVIDER_ACCESS_DENIED；`ImportViewModel` 异常映射 Failed（提示重新授权） | `VivioApp`/`ImportViewModel`；U5 测试 |
| U6 | P2 | 声明了不需要的通知权限 | 删除 POST_NOTIFICATIONS | AndroidManifest |

---

## 三、自动化验证

- 先写失败测试（`PreDeviceIntegrityTest`，5 用例）修复前全部失败命中 U1-U5 → 修复后全绿。
- 全量 87 用例全绿（82 + 5），无回归；既有压力/生命周期/崩溃边界用例保持通过。
- R8 缺失类告警 0，Debug/Release 构建正常。

---

## 四、提交清单（6 个）

| 提交 | 内容 |
|---|---|
| `8bcbfc2` | test: reproduce final pre-device integrity gaps（U1-U5 失败测试） |
| `af2e795` | fix: U1-U5（deleteAndConfirm、撤销 DELETE 回读重建、CREATE 提醒回读、failAction、权限异常捕获） |
| `aa4b127` | fix: U5 权限兜底（启动恢复权限闸门 + ViewModel 异常映射） |
| `e363605` | chore: U6 删除 POST_NOTIFICATIONS |
| `e16cee3` | build: 候选版本 0.2.0-rc1（versionCode 2） |
| `dea67e1` | docs: 整合报告追加阶段 A |

---

## 五、待办（阶段 B/C，需真机与用户）

| 项 | 说明 |
|---|---|
| 推送分支 | `release/0.2.0-rc1-vivo-validation` 未推送 |
| 候选签名 RC APK | 需用户提供 keystore（C1 要求，keystore 不入库） |
| vivo SYNC_DATA1 保留性 | 前置闸门，未通过则暂停恢复闭环验收 |
| Release 双样表 / 幂等 / 提醒 / 撤销 / 中断恢复 / 权限 | 阶段 B 全量真机用例 |
| 分发工程（阶段 C） | 正式签名、去 destructive migration + Migration 测试、CI 闸门 |

---

> 依据交接包闸门条件：仅当 vivo token 保留性 + Release 双样表 + 真机中断恢复全部通过，方可写"完整性修复完成"。
