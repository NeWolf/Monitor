package com.newolf.monitor

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 主线程耗时监控统计信息
 *
 * 记录各阈值级别的触发次数、最大耗时、总消息数等统计数据。
 * 线程安全，可在任意线程读取。
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
    }

    /**
     * 生成统计摘要信息
     */
    fun getSummary(): String {
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