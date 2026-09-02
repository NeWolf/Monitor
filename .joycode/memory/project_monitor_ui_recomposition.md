---
name: 监控统计 UI 重组与 GC 优化
description: >-
  统计更新高频拼字符串引发 GC 与整页重组的根因与修复:发射端只发版本号(零分配),UI 侧 sample 后才 map 构建字符串 + 隔离统计文本
  Composable
type: project
---

test app 的统计更新以 `MonitorStatistics.versionFlow`(StateFlow<Long> 版本号) 暴露,UI 观测到更新后在低频路径才构建摘要字符串。

**Why:** `onMessageProcessed` 在主线程每处理一条消息(60fps 下每秒几十上百条)都触发统计更新。早期实现每次都 `buildSummary()` 用 buildString 拼完整摘要并通过 StateFlow<String> 发射,产生海量临时字符串对象引发频繁 GC;UI 侧 sample 只降低重组,字符串照样被高频构建后丢弃。用户明确要求发射端发真实数据、不节流,且要解决频繁 GC。

**How to apply:** 保持以下分层,勿倒退:
1. **发射端零分配(Monitor 侧)**：统计变化只调 `emitSummary()` 自增 `_versionFlow`(Long),**绝不在此高频路径构建字符串**。不要把 versionFlow 改回 StateFlow<String>,不要在发射端 buildSummary。字符串构建保留在 `getSummary()`/`buildSummary()`,只供 UI 低频调用。
2. **UI 侧低频构建(app 侧 StatisticsSection)**：`remember(monitor){ versionFlow.sample(500L).map { getSummary() } }` —— 先按 500ms 采样版本号,再在采样后的低频路径才 map 构建字符串;`collectAsState(initial = getSummary())` 提供首帧。派生 Flow 必须 remember。
3. **UI 侧隔离**：观测收敛在独立 `StatisticsSection` Composable,刷新只重组该文本块,不波及外层按钮。

调实时性改 UI 侧 sample 间隔(500L)。