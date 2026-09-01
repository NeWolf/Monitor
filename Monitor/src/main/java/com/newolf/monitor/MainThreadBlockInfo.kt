package com.newolf.monitor

/**
 * 主线程耗时超限信息
 *
 * @param durationMs 实际耗时（ms）
 * @param thresholdMs 触发的阈值（ms）
 * @param culpritMethod 最可能的耗时方法（从采样堆栈中提取的业务代码栈顶）
 * @param stackTrace 主线程堆栈信息
 * @param timestamp 发生时间戳
 */
data class MainThreadBlockInfo(
    val durationMs: Long,
    val thresholdMs: Long,
    val culpritMethod: String,
    val stackTrace: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun toString(): String {
        return buildString {
            appendLine("===== 主线程耗时超限 =====")
            appendLine("触发阈值: ${thresholdMs}ms")
            appendLine("实际耗时: ${durationMs}ms")
            appendLine("耗时方法: $culpritMethod")
            appendLine("发生时间: $timestamp")
            appendLine("主线程堆栈:")
            appendLine(stackTrace)
            appendLine("========================")
        }
    }
}