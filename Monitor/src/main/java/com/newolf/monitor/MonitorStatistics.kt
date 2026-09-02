package com.newolf.monitor

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
/**
 * 主线程耗时监控统计信息
 *
 * 记录各阈值级别的触发次数、最大耗时、总消息数等统计数据。
 * 线程安全，可在任意线程读取。
 *
 * 统计更新以 [versionFlow]（版本号 [StateFlow]）形式暴露：每当统计数据发生变化（处理消息、
 * 触发阻塞、重置），只自增版本号（零对象分配），UI 层 collect 观测到更新后，在低频路径（采样后）
 * 自行调用 [getSummary] 构建摘要字符串，从而避免在高频统计路径拼接字符串引发频繁 GC。
 */
class MonitorStatistics {

    /** 监控期间处理的总消息数 */
    val totalMessages = AtomicLong(0)

    /** 监控期间触发阻塞的总次数（任一阈值被触发即计一次） */
    val totalBlocks = AtomicLong(0)

    /** 监控期间记录到的最大单次耗时（ms） */
    val maxDurationMs = AtomicLong(0)

    /** 各阈值级别的触发次数，key 为阈值（ms），value 为触发次数 */
    private val thresholdHitCounts = ConcurrentHashMap<Long, AtomicLong>()

    /** 各阈值级别的累计耗时（ms），key 为阈值（ms），value 为累计耗时 */
    private val thresholdTotalDurations = ConcurrentHashMap<Long, AtomicLong>()

    /** 各阈值级别下各耗时方法的触发次数，key 为阈值（ms），value 为「方法名 -> 次数」 */
    private val thresholdCulpritCounts = ConcurrentHashMap<Long, ConcurrentHashMap<String, AtomicLong>>()

    /**
     * 统计变更信号流（内部写入）。它只作为「统计发生了变化」的开关信号，值本身无业务含义，
     * UI 层观测到值变化即知道需要在低频路径重新构建摘要。
     *
     * 关键(零装箱)：这里不再自增版本号。自增会让 [MutableStateFlow] 每次装箱出一个新的
     * java.lang.Long(值超出 JVM 的 Long 缓存区间 [-128,127] 后必然新建对象),在 60fps 高频
     * 路径上持续制造小对象。改为在两个固定值 0L / 1L 之间交替:二者都落在 Long 缓存区间内,
     * Long.valueOf 直接返回被复用的缓存实例,不产生任何新对象;而 0 != 1 又能打破 StateFlow
     * 的 equals 去重,确保每条消息都能通知下游。由此实现「零新增装箱」的变更通知。
     */
    private val _versionFlow = MutableStateFlow(0L)

    /**
     * 统计变更信号流,UI 层可 collect 观测「是否有更新」。
     *
     * 值在 0L / 1L 间交替翻转,仅表示「有新变化」,不代表版本序号或消息计数。真实计数请读取
     * [totalMessages] 等 Atomic 字段。字符串摘要由 UI 侧 sample 之后的低频路径调用 [getSummary] 构建。
     */
    val versionFlow: StateFlow<Long> = _versionFlow.asStateFlow()

    /**
     * 标记统计已更新:在 0L / 1L 间翻转信号值,不做任何字符串拼接、不新建对象(零装箱)。
     * 任意统计更新后调用。翻转而非自增是为了让发射值始终落在 JVM Long 缓存区间内,复用缓存实例。
     */
    private fun emitSummary() {
        _versionFlow.value = if (_versionFlow.value == 0L) 1L else 0L
    }

    /**
     * 当一个消息处理完成时调用，更新总消息数和最大耗时
     */
    fun onMessageProcessed(durationMs: Long) {
        totalMessages.incrementAndGet()
        // 更新最大耗时
        var current: Long
        do {
            current = maxDurationMs.get()
            if (durationMs <= current) break
        } while (!maxDurationMs.compareAndSet(current, durationMs))
        emitSummary()
    }

    /**
     * 当触发一次阻塞回调时调用，更新对应阈值的统计
     */
    fun onBlock(blockInfo: MainThreadBlockInfo) {
        totalBlocks.incrementAndGet()
        val threshold = blockInfo.thresholdMs
        thresholdHitCounts.computeIfAbsent(threshold) { AtomicLong(0) }.incrementAndGet()
        thresholdTotalDurations.computeIfAbsent(threshold) { AtomicLong(0) }.addAndGet(blockInfo.durationMs)
        // 记录该阈值下的耗时方法名（去掉采样命中次数等附加信息，只保留方法主体）
        val method = normalizeCulprit(blockInfo.culpritMethod)
        thresholdCulpritCounts
            .computeIfAbsent(threshold) { ConcurrentHashMap() }
            .computeIfAbsent(method) { AtomicLong(0) }
            .incrementAndGet()
        emitSummary()
    }

    /**
     * 归一化耗时方法名：去除 "[采样命中 N/M 次]" 之类的后缀，便于聚合统计
     */
    private fun normalizeCulprit(culprit: String): String {
        return culprit.substringBefore("  [").trim().ifEmpty { "未知方法" }
    }

    /**
     * 获取指定阈值的触发次数
     */
    fun getHitCount(thresholdMs: Long): Long {
        return thresholdHitCounts[thresholdMs]?.get() ?: 0L
    }

    /**
     * 获取指定阈值的累计耗时（ms）
     */
    fun getTotalDuration(thresholdMs: Long): Long {
        return thresholdTotalDurations[thresholdMs]?.get() ?: 0L
    }

    /**
     * 获取所有已记录的阈值级别
     */
    fun getRecordedThresholds(): Set<Long> {
        return thresholdHitCounts.keys
    }

    /**
     * 重置所有统计数据
     */
    fun reset() {
        totalMessages.set(0)
        totalBlocks.set(0)
        maxDurationMs.set(0)
        thresholdHitCounts.clear()
        thresholdTotalDurations.clear()
        thresholdCulpritCounts.clear()
        emitSummary()
    }

    /**
     * 生成统计摘要信息。UI 侧应在观测到 [versionFlow] 更新并做低频采样后再调用此方法构建字符串。
     */
    fun getSummary(): String = buildSummary()

    /**
     * 生成统计摘要字符串
     */
    private fun buildSummary(): String {
        return buildString {
            appendLine("===== 主线程监控统计 =====")
            appendLine("总消息数: ${totalMessages.get()}")
            appendLine("总阻塞次数: ${totalBlocks.get()}")
            appendLine("最大耗时: ${maxDurationMs.get()}ms")
            val thresholds = thresholdHitCounts.keys.sorted()
            if (thresholds.isNotEmpty()) {
                appendLine("各阈值统计:")
                for (threshold in thresholds) {
                    val hitCount = thresholdHitCounts[threshold]?.get() ?: 0
                    val totalDuration = thresholdTotalDurations[threshold]?.get() ?: 0
                    val avgDuration = if (hitCount > 0) totalDuration / hitCount else 0
                    appendLine("  ${threshold}ms: 触发${hitCount}次, 累计${totalDuration}ms, 平均${avgDuration}ms")
                    // 展示该阈值下的耗时方法（按触发次数降序）
                   val culprits = thresholdCulpritCounts[threshold]
                    if (!culprits.isNullOrEmpty()) {
                        culprits.entries
                            .sortedByDescending { it.value.get() }
                            .forEach { (method, count) ->
                                appendLine("      - $method（${count.get()}次）")
                            }
                    }
                }
            }
            appendLine("========================")
        }
    }

    override fun toString(): String = getSummary()
}