---
name: 采样期避免字符串拼接以抑制 GC
description: 'MainThreadMonitor 抑制频繁 GC 的核心手段:延迟启动采样(短消息零分配)+ 采样期只存原始栈帧不拼字符串 + 热路径消除重复分配'
type: project
---

MainThreadMonitor 的主线程耗时监控设计为低内存开销,避免引发频繁 GC。

**Why:** Looper.setMessageLogging 的 Printer 对主线程每条消息(触摸/动画帧/布局,每秒数十上百条)都回调。若对每条消息都启动采样协程并高频 `mainThread.stackTrace`(每次分配几十个 StackTraceElement)+ 拼字符串,会产生海量临时对象,触发频繁 GC,干扰被监控应用性能。

**How to apply:** 修改采样/统计逻辑时必须遵守以下已落地的优化,勿倒退:
1. **延迟启动采样(核心)**：`startSampling()` 无启用阈值直接 return;协程内先 `delay(firstSampleDelayMs)`(= 最小阈值 - 一个采样间隔)再进入抓栈循环。绝大多数短消息在采样启动前就结束并被 stopSampling 取消,实现零堆栈分配。
2. **采样期只存原始 StackTraceElement[]**,不拼字符串;展示字符串延迟到 `checkThresholds`/`buildSampledStackTrace` 低频路径(仅真正超阈值时)才构建。
3. **maxSamples 限上限**(默认 200),超出仅计 droppedSampleCount,防内存无界增长。
4. **热路径消除重复分配**：`SYSTEM_PREFIXES` 提为 companion 常量(避免 isBusinessFrame 每帧 arrayOf);`enabledThresholds`/`minThresholdMs`/`firstSampleDelayMs`/`mainThread` 均构造时算一次并缓存。
5. **禁止在 startSampling 热路径做任何非必要分配**。

调精度优先调 sampleIntervalMs/maxSamples。注意:延迟采样会减少刚好略超最小阈值消息的采样次数,若耗时方法定位精度不足可适当调小 firstSampleDelayMs 的推导边界。