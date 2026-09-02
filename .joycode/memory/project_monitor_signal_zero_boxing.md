---
name: 监控变更信号零装箱设计
description: 'MonitorStatistics.versionFlow 为何用 0L/1L 翻转而非自增,以实现高频变更通知的零对象分配'
type: project
---

MonitorStatistics 的统计变更信号 `_versionFlow`(MutableStateFlow<Long>)在 `emitSummary()` 中用 `0L ↔ 1L` 翻转,而不是 `value + 1` 自增。

**Why:** 主线程每条消息(60fps)都调 emitSummary。自增会让版本号很快超出 JVM 的 Long 缓存区间 [-128,127],此后每次 `MutableStateFlow.value = 新Long` 都装箱出一个新 java.lang.Long 对象,在高频路径持续制造小对象。而 0L / 1L 都落在 Long 缓存区内,`Long.valueOf` 直接返回被复用的缓存实例,零新增分配;同时 0 != 1 又能打破 StateFlow 的 equals 去重,保证每条消息都通知下游。

**How to apply:** versionFlow 的值无业务含义,只是「有变化」的开关信号,UI 侧用 `versionFlow.sample(500L).map { getSummary() }` 消费——sample 只关心是否有新发射,不关心值内容,翻转照样触发。真实计数读 totalMessages 等 Atomic 字段,不要把 versionFlow 的值当版本序号或消息数用。

**实测背景:** 通过 adb dumpsys meminfo + logcat ART GC 日志实测,监控运行时 GC 为 Background young concurrent copying,paused 仅约 56μs,20s 内 0~1 次,堆 14MB/26MB 健康。用户在 Profiler 看到的「内存波动」主要是 ART 分代回收正常锯齿 + Compose/协程框架开销,非监控库热路径。本零装箱优化是应用侧可动的最后一处微小分配源。