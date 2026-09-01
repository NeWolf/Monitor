---
name: 主线程卡顿监控采样设计原理
description: Monitor 模块为何用后台线程过程采样定位耗时方法，而非超时后单次抓主线程栈
type: project
---

Monitor 模块（主线程卡顿监控库）通过后台线程在消息分发期间持续采样主线程堆栈来定位耗时方法，而不是在发现超时时才抓一次栈。

**Why:**
- Looper Printer 机制只有在 `<<<<<`（消息结束）时才知道本次分发超时，但此刻主线程已执行完那段耗时代码、耗时方法早已出栈返回，事后抓栈抓不到"案发现场"。
- 抓的是主线程的栈，执行抓取动作必须是另一个线程（HandlerThread "MonitorSampler"），因为主线程被业务代码占死时自己无法执行抓栈。

**How to apply:**
- 在 `>>>>>`（消息开始）启动后台采样，`<<<<<` 停止；事后从多次快照中聚合出现次数最多的业务帧作为元凶方法。
- 讨论/修改该库的堆栈定位逻辑时，不要建议改成"超时后单次抓栈"——那是无效方案。
- 可优化方向：惰性采样（耗时超过下限才开始抓）、动态调间隔、采样降频，用精度换性能。
- 关键代码位置：MainThreadMonitor.kt 的 printer / startSampling / findCulpritMethod / isBusinessFrame。
- 已知坑：isBusinessFrame 排除 `com.newolf.monitor.` 时必须保留 `com.newolf.monitor.test`（测试业务类），否则业务帧被误过滤。