package com.newolf.monitor

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Printer

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
    }

    /** 统计信息 */
    val statistics = MonitorStatistics()

    @Volatile
    private var isRunning = false

    @Volatile
    private var dispatchStartTime = 0L

    /** 采样到的堆栈信息 */
    private val sampledStackTraces = mutableListOf<String>()

    /** 采样到的原始堆栈帧（用于提取耗时方法，取采样次数最多的业务帧） */
    private val sampledStackFrames = mutableListOf<Array<StackTraceElement>>()

    private var sampleThread: HandlerThread? = null
    private var sampleHandler: Handler? = null

    private val sampleRunnable = object : Runnable {
        override fun run() {
            if (dispatchStartTime != 0L) {
                val stackTrace = Looper.getMainLooper().thread.stackTrace
                sampledStackFrames.add(stackTrace)
                sampledStackTraces.add(
                    stackTrace.joinToString("\n") { "        at $it" }
                )
            }
            sampleHandler?.postDelayed(this, config.sampleIntervalMs)
        }
    }

    private val printer = Printer { logMsg ->
        if (logMsg.startsWith(DISPATCH_START)) {
            // 消息开始分发，记录时间并启动堆栈采样
            dispatchStartTime = System.currentTimeMillis()
            sampledStackTraces.clear()
            sampledStackFrames.clear()
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

        // 启动堆栈采样后台线程
        sampleThread = HandlerThread("MonitorSampler").also {
            it.start()
            sampleHandler = Handler(it.looper)
        }

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
        sampleThread?.quitSafely()
        sampleThread = null
        sampleHandler = null
        dispatchStartTime = 0L
        sampledStackTraces.clear()
        sampledStackFrames.clear()
        statistics.reset()

        Log.d(TAG, "Monitor stopped")
    }

    /**
     * 启动堆栈采样
     */
    private fun startSampling() {
        sampleHandler?.removeCallbacks(sampleRunnable)
        // 首次立即采样（延迟 0），避免短消息（如 50ms）在首个采样间隔到来前就结束、
        // 导致一次都采不到业务帧；后续采样由 sampleRunnable 自身按间隔续期。
        sampleHandler?.post(sampleRunnable)
    }

    /**
     * 停止堆栈采样
     */
    private fun stopSampling() {
        sampleHandler?.removeCallbacks(sampleRunnable)
    }

    /**
     * 检查耗时是否超过阈值，按区间触发：只触发最高匹配的阈值级别
     * 例如：耗时150ms，阈值列表[50,100,200,500]，只触发100ms级别
     */
    private fun checkThresholds(duration: Long) {
        val thresholds = config.getEnabledThresholds()
        if (thresholds.isEmpty()) return

        // 找到最高匹配的阈值（区间触发）
        val matchedThreshold = thresholds.lastOrNull { duration >= it } ?: return

        // 构建堆栈信息
        val stackTrace = if (sampledStackTraces.isNotEmpty()) {
            buildString {
                appendLine("采样堆栈（共${sampledStackTraces.size}次）:")
                sampledStackTraces.forEachIndexed { index, stack ->
                    appendLine("  #${index + 1}:")
                    appendLine(stack)
                }
            }
        } else {
            getMainThreadStackTrace()
        }

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
        val systemPrefixes = arrayOf(
            "android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.",
            "com.android.", "dalvik.", "libcore.", "sun."
        )
        return systemPrefixes.none { cls.startsWith(it) }
    }

    /**
     * 获取主线程堆栈信息
     */
    private fun getMainThreadStackTrace(): String {
        val mainThread = Looper.getMainLooper().thread
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