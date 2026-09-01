package com.newolf.monitor

/**
 * 主线程耗时监控配置
 *
 * @param threshold50ms 超过50ms的监控开关，默认开启
 * @param threshold100ms 超过100ms的监控开关，默认开启
 * @param threshold200ms 超过200ms的监控开关，默认开启
 * @param threshold500ms 超过500ms的监控开关，默认开启
 * @param sampleIntervalMs 采样间隔（ms），默认30ms，值越小精度越高但开销越大
 */
data class MonitorConfig(
    val threshold50ms: Boolean = true,
    val threshold100ms: Boolean = true,
    val threshold200ms: Boolean = true,
    val threshold500ms: Boolean = true,
    val sampleIntervalMs: Long = 30L
) {
    /** 获取所有已启用的阈值列表（升序） */
    fun getEnabledThresholds(): List<Long> {
        return buildList {
            if (threshold50ms) add(50L)
            if (threshold100ms) add(100L)
            if (threshold200ms) add(200L)
            if (threshold500ms) add(500L)
        }.sorted()
    }

    class Builder {
        private var threshold50ms: Boolean = true
        private var threshold100ms: Boolean = true
        private var threshold200ms: Boolean = true
        private var threshold500ms: Boolean = true
        private var sampleIntervalMs: Long = 30L

        fun threshold50ms(enabled: Boolean) = apply { this.threshold50ms = enabled }
        fun threshold100ms(enabled: Boolean) = apply { this.threshold100ms = enabled }
        fun threshold200ms(enabled: Boolean) = apply { this.threshold200ms = enabled }
        fun threshold500ms(enabled: Boolean) = apply { this.threshold500ms = enabled }
        fun sampleIntervalMs(intervalMs: Long) = apply { this.sampleIntervalMs = intervalMs }

        fun build() = MonitorConfig(
            threshold50ms = threshold50ms,
            threshold100ms = threshold100ms,
            threshold200ms = threshold200ms,
            threshold500ms = threshold500ms,
            sampleIntervalMs = sampleIntervalMs
        )
    }
}