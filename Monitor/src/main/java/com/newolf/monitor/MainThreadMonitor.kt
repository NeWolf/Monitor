package com.newolf.monitor

import android.os.Looper
import android.util.Log
import android.util.Printer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 主线程耗时监控器
 *
 * 原理：通过 Looper.setMessageLogging(Printer) 监控主线程每个消息的分发耗时。
 * 主线程 Looper 在 dispatchMessage 前后会打印 ">>>>>" 和 "<<<<<" 标记，
 * 通过计算两个标记之间的时间差即可得到消息处理耗时。
 * 同时在消息处理期间，后台线程会定期采样主线程堆栈，以便捕获阻塞时的真实调用栈。
 *
 * 使用方式：
 * ```
 * val monitor = MainThreadMonitor.Builder()
 *     .config(MonitorConfig())
 *     .listener { blockInfo -> Log.w("Monitor", blockInfo.toString()) }
 *     .build()
 * monitor.start()
 * // ...
 * monitor.stop()
 * ```
 */
class MainThreadMonitor private constructor(
    private val config: MonitorConfig,
    private val listener: OnMainThreadBlockListener
) {
    companion object {
        private const val TAG = "MainThreadMonitor"
        private const val DISPATCH_START = ">>>>>"
        private const val DISPATCH_END = "<<<<<"

        /**
         * 系统框架类前缀。提取为类级常量，避免 isBusinessFrame 每次采样都 arrayOf 新建数组，
         * 从而消除该热路径上的重复对象分配（findCulpritMethod 会对每帧调用 isBusinessFrame）。
         */
        private val SYSTEM_PREFIXES = arrayOf(
            "android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.",
            "com.android.", "dalvik.", "libcore.", "sun."
        )
    }

    /** 统计信息 */
    val statistics = MonitorStatistics()

    @Volatile
    private var isRunning = false

    @Volatile
    private var dispatchStartTime = 0L

    /** 主线程引用缓存，避免每次采样都通过 Looper 重新查找。 */
    private val mainThread: Thread = Looper.getMainLooper().thread

    /**
     * 启用阈值列表（升序）缓存。构造时计算一次，避免 checkThresholds 每条超阈值消息都重建 List。
     */
    private val enabledThresholds: List<Long> = config.getEnabledThresholds()

    /**
     * 启用的最小阈值（ms），采样延迟启动的依据。构造时计算一次，避免每条消息重复计算。
     * 为 null 表示没有任何阈值启用，此时不采样。
     */
    private val minThresholdMs: Long? = enabledThresholds.minOrNull()

    /**
     * 采样首次启动前的延迟：略小于最小阈值。只有当一条消息运行时长逼近最小阈值时才开始抓栈，
     * 使绝大多数短消息（触摸、动画帧等，远低于阈值）在采样启动前就结束，实现零堆栈分配，
     * 这是抑制频繁 GC 的关键。取「最小阈值 - 一个采样间隔」，至少 0。
     */
    private val firstSampleDelayMs: Long =
        ((minThresholdMs ?: Long.MAX_VALUE) - config.sampleIntervalMs).coerceAtLeast(0L)

    /**
     * 采样到的原始堆栈帧（用于提取耗时方法，取采样次数最多的业务帧）。
     *
     * 采样期只保留原始 StackTraceElement 数组、不拼接字符串，展示用的字符串延迟到
    * 触发阈值时（低频）才在 [checkThresholds] 中构建，避免每 10~30ms 就产生一个
     * 长字符串造成频繁 GC。数组容量上限由 config.maxSamples 控制。
     */
    private val sampledStackFrames = ArrayList<Array<StackTraceElement>>(config.maxSamples)

    /** 因超出上限而被丢弃、未保留堆栈的采样次数（仅用于展示统计）。 */
    @Volatile
    private var droppedSampleCount = 0

    /** 采样调度协程作用域（后台线程，delay 挂起而非阻塞线程） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 当前消息的采样协程；每条消息开始时新建、结束时取消 */
    @Volatile
    private var sampleJob: Job? = null

    private val printer = Printer { logMsg ->
        if (logMsg.startsWith(DISPATCH_START)) {
            // 消息开始分发，记录时间并启动堆栈采样
            dispatchStartTime = System.currentTimeMillis()
            sampledStackFrames.clear()
            droppedSampleCount = 0
            startSampling()
        } else if (logMsg.startsWith(DISPATCH_END)) {
            // 消息分发结束，计算耗时并检查阈值
            if (dispatchStartTime != 0L) {
                val duration = System.currentTimeMillis() - dispatchStartTime
                dispatchStartTime = 0L
                stopSampling()
                statistics.onMessageProcessed(duration)
                checkThresholds(duration)
            }
        }
    }

    /**
     * 启动监控
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "Monitor is already running")
            return
        }
        isRunning = true

        // 采样改由协程调度（delay 挂起而非独占线程），scope 已随实例创建，此处无需额外初始化。

        // 设置自定义 Printer 监控主线程消息分发
        Looper.getMainLooper().setMessageLogging(printer)

        Log.d(TAG, "Monitor started with thresholds: ${config.getEnabledThresholds()}")
    }

    /**
     * 停止监控
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false

        // 移除 Printer 监控
        Looper.getMainLooper().setMessageLogging(null)

        stopSampling()
        dispatchStartTime = 0L
        sampledStackFrames.clear()
        droppedSampleCount = 0
        statistics.reset()

        Log.d(TAG, "Monitor stopped")
    }

    /**
     * 启动堆栈采样。
     *
     * 用协程 delay 循环替代 HandlerThread：delay 只挂起协程、不阻塞底层线程，
     * 比独立后台线程更省资源。首次立即采样（无初始 delay），避免短消息（如 50ms）
     * 在首个采样间隔到来前就结束、导致一次都采不到业务帧。
     */
    private fun startSampling() {
        // 没有启用任何阈值时无需采样，直接返回，避免无谓的协程与抓栈开销。
        if (minThresholdMs == null) return
        // 取消上一条消息可能残留的采样协程，保证每条消息独立采样。
        sampleJob?.cancel()
        sampleJob = scope.launch {
            // 关键优化：先延迟到逼近最小阈值时才开始抓栈。绝大多数消息（触摸、动画帧等）
            // 远低于阈值，会在此 delay 期间就结束并被 stopSampling 取消，从而完全不产生
            // StackTraceElement[] 分配，这是抑制频繁 GC 的核心手段。
            if (firstSampleDelayMs > 0) {
                delay(firstSampleDelayMs)
            }
            while (isActive && dispatchStartTime != 0L) {
                // 只抓取原始堆栈帧、不拼接字符串，最大限度减少采样期的临时对象分配。
                // 达到上限后不再累积（避免内存无界增长引发频繁 GC），仅记录被丢弃的次数。
                if (sampledStackFrames.size < config.maxSamples) {
                    sampledStackFrames.add(mainThread.stackTrace)
                } else {
                    droppedSampleCount++
                }
                delay(config.sampleIntervalMs)
            }
        }
    }

    /**
     * 停止堆栈采样
     */
    private fun stopSampling() {
        sampleJob?.cancel()
        sampleJob = null
    }

    /**
     * 检查耗时是否超过阈值，按区间触发：只触发最高匹配的阈值级别
     * 例如：耗时150ms，阈值列表[50,100,200,500]，只触发100ms级别
     */
    private fun checkThresholds(duration: Long) {
        val thresholds = enabledThresholds
        if (thresholds.isEmpty()) return

        // 找到最高匹配的阈值（区间触发）
        val matchedThreshold = thresholds.lastOrNull { duration >= it } ?: return

        // 构建堆栈信息（延迟到此处才拼接字符串：仅在真正触发阈值时执行，属于低频操作）
        val stackTrace = buildSampledStackTrace()

        val blockInfo = MainThreadBlockInfo(
            durationMs = duration,
            thresholdMs = matchedThreshold,
            culpritMethod = findCulpritMethod(),
            stackTrace = stackTrace
        )
        Log.w(TAG, blockInfo.toString())

        // 更新统计信息
        statistics.onBlock(blockInfo)

        listener.onBlock(blockInfo)
    }

    /**
     * 从采样堆栈中提取最可能的耗时方法。
     *
     * 做法：对每次采样，取"业务代码栈顶帧"（跳过 android./java./kotlin. 等系统框架帧），
     * 统计该帧出现的次数，出现次数最多的即为阻塞期间停留最久的方法。
     * 若采样中全是系统帧，则回退为整体栈顶帧。
     */
    private fun findCulpritMethod(): String {
        if (sampledStackFrames.isEmpty()) return "未采样到堆栈（消息处理过快或采样间隔过大）"

        val counter = HashMap<String, Int>()
        var businessSampleCount = 0
        for (frames in sampledStackFrames) {
     // 只统计业务栈顶帧；若某次采样瞬间栈顶全是系统帧（如渲染、GC），视为噪声跳过，
            // 避免把 HardwareRenderer.nSetStopped 等系统方法误判为耗时方法。
            val top = frames.firstOrNull { isBusinessFrame(it) } ?: continue
            businessSampleCount++
            val key = "${top.className}.${top.methodName}(${top.fileName}:${top.lineNumber})"
            counter[key] = (counter[key] ?: 0) + 1
        }
        if (counter.isEmpty()) return "未识别到业务方法（采样均命中系统帧）"
        val best = counter.maxByOrNull { it.value }!!
        return "${best.key}  [采样命中 ${best.value}/${businessSampleCount} 次]"
    }

    /**
     * 判断是否为业务代码帧（排除系统框架），用于定位真正的耗时方法。
     */
    private fun isBusinessFrame(element: StackTraceElement): Boolean {
        val cls = element.className
        // 监控库自身的类（com.newolf.monitor.MainThreadMonitor 等）应排除，
        // 但测试业务类位于 com.newolf.monitor.test 下，属于业务帧，必须保留。
        if (cls.startsWith("com.newolf.monitor.") && !cls.startsWith("com.newolf.monitor.test")) {
            return false
        }
        return SYSTEM_PREFIXES.none { cls.startsWith(it) }
    }

    /**
     * 从采样到的原始堆栈帧延迟构建展示字符串。
     *
     * 仅在触发阈值（低频）时调用，避免采样期高频拼接字符串。若因超出上限丢弃过采样，
     * 会在标题中标注丢弃次数。无采样时回退为当前主线程栈。
     */
    private fun buildSampledStackTrace(): String {
        if (sampledStackFrames.isEmpty()) return getMainThreadStackTrace()
        return buildString {
            val dropped = droppedSampleCount
            if (dropped > 0) {
                appendLine("采样堆栈（共${sampledStackFrames.size}次，另有${dropped}次超上限未保留）:")
            } else {
                appendLine("采样堆栈（共${sampledStackFrames.size}次）:")
            }
            sampledStackFrames.forEachIndexed {index, frames ->
                appendLine("  #${index + 1}:")
                frames.forEach { appendLine("        at $it") }
            }
        }
    }

    /**
     * 获取主线程堆栈信息
     */
    private fun getMainThreadStackTrace(): String {
        return mainThread.stackTrace.joinToString("\n") { "    at $it" }
    }

    /**
     * Builder 模式构建 MainThreadMonitor
     */
    class Builder {
        private var config: MonitorConfig = MonitorConfig()
        private var listener: OnMainThreadBlockListener = DefaultListener()

        fun config(config: MonitorConfig) = apply { this.config = config }
        fun listener(listener: OnMainThreadBlockListener) = apply { this.listener = listener }
        fun listener(block: (MainThreadBlockInfo) -> Unit) = apply {
            this.listener = object : OnMainThreadBlockListener {
                override fun onBlock(blockInfo: MainThreadBlockInfo) {
                    block(blockInfo)
                }
            }
        }

        fun build() = MainThreadMonitor(config, listener)
    }

    /**
     * 默认监听器，仅打印日志
     */
    private class DefaultListener : OnMainThreadBlockListener {
        override fun onBlock(blockInfo: MainThreadBlockInfo) {
            Log.w(TAG, blockInfo.toString())
        }
    }
}