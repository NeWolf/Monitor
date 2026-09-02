- [主线程卡顿监控采样设计原理](project_monitor_sampling_design.md) — Monitor 模块为何用后台线程过程采样定位耗时方法，而非超时后单次抓主线程栈

- [采样期避免字符串拼接以抑制 GC](project_monitor_sampling_gc.md) — MainThreadMonitor 抑制频繁 GC 的核心手段:延迟启动采样(短消息零分配)+ 采样期只存原始栈帧不拼字符串 + 热路径消除重复分配

- [监控统计 UI 重组与 GC 优化](project_monitor_ui_recomposition.md) — 统计更新高频拼字符串引发 GC 与整页重组的根因与修复:发射端只发版本号(零分配),UI 侧 sample 后才 map 构建字符串 + 隔离统计文本 Composable

- [监控变更信号零装箱设计](project_monitor_signal_zero_boxing.md) — MonitorStatistics.versionFlow 为何用 0L/1L 翻转而非自增,以实现高频变更通知的零对象分配
