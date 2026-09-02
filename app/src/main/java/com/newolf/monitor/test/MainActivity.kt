package com.newolf.monitor.test

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newolf.monitor.MainThreadMonitor
import com.newolf.monitor.MonitorConfig
import com.newolf.monitor.test.ui.theme.MonitorTheme
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var monitor: MainThreadMonitor
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 初始化主线程耗时监控
        monitor = MainThreadMonitor.Builder()
            .config(
                MonitorConfig.Builder()
                    .threshold50ms(true)
                    .threshold100ms(true)
                    .threshold200ms(true)
                    .threshold500ms(true)
                    .sampleIntervalMs(10L)
                    .build()
            )
            .listener { blockInfo ->
                Log.w(TAG, "收到主线程耗时超限回调: $blockInfo")
            }
            .build()

        monitor.start()

        setContent {
            MonitorTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TestScreen(
                        modifier = Modifier.padding(innerPadding),
                        monitor = monitor,
                        onBlock50 = { blockMainThread(50) },
                        onBlock100 = { blockMainThread(100) },
                        onBlock200 = { blockMainThread(200) },
                        onBlock500 = { blockMainThread(500) },
                        onBlock1000 = { blockMainThread(1000) },
                        onBlock2000 = { blockMainThread(2000) },
                        onOneClickTest = { onComplete -> runOneClickTest(onComplete) },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        monitor.stop()
    }

    /**
     * 在主线程上阻塞指定毫秒数，用于测试监控功能。
     * 通过调用带业务语义的方法制造耗时，让监控报告能显示真实的耗时方法名。
     */
    private fun blockMainThread(durationMs: Long) {
        Log.d(TAG, "开始阻塞主线程 ${durationMs}ms")
        // 根据时长分派到不同的"业务方法"，模拟真实场景中不同的耗时来源
        when {
            durationMs <= 50L -> parseLocalJson(durationMs)
            durationMs <= 100L -> decodeBitmapOnMainThread(durationMs)
            durationMs <= 200L -> queryDatabaseSync(durationMs)
            durationMs <= 500L -> computeHeavyLayout(durationMs)
            else -> doHeavyBusinessWork(durationMs)
        }
        Log.d(TAG, "主线程阻塞结束: ${durationMs}ms")
    }

    /**
     * 模拟：主线程解析本地 JSON。
     * 注意：每个业务方法都各自内联忙等待循环，而不是转发给公共的 busyWait，
     * 这样后台线程采样时栈顶帧就是本方法，监控报告才能定位到真实的业务方法名。
     */
    private fun parseLocalJson(durationMs: Long) {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < durationMs) {
            // 模拟 JSON 解析占用主线程
        }
    }

    /** 模拟：主线程解码 Bitmap */
    private fun decodeBitmapOnMainThread(durationMs: Long) {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < durationMs) {
            // 模拟 Bitmap 解码占用主线程
        }
    }

    /** 模拟：主线程同步查询数据库 */
    private fun queryDatabaseSync(durationMs: Long) {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < durationMs) {
            // 模拟同步数据库查询占用主线程
        }
    }

    /** 模拟：主线程执行复杂布局计算 */
    private fun computeHeavyLayout(durationMs: Long) {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < durationMs) {
            // 模拟复杂布局计算占用主线程
        }
    }

    /** 模拟：主线程执行重业务逻辑 */
    private fun doHeavyBusinessWork(durationMs: Long) {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < durationMs) {
            // 模拟重业务逻辑占用主线程
        }
    }

    /**
     * 一键测试：依次阻塞 50ms、100ms、200ms、500ms、1000ms、2000ms
     * 每次阻塞间隔 200ms，确保每次阻塞作为独立消息被监控。
     * 全部执行完毕后回调 onComplete，用于自动刷新并展示统计信息。
     */
    private fun runOneClickTest(onComplete: () -> Unit) {
        Log.d(TAG, "===== 一键测试开始 =====")
        val durations = longArrayOf(50, 100, 200, 500, 1000, 2000)
        var delay = 100L
        for (duration in durations) {
            mainHandler.postDelayed({
                blockMainThread(duration)
            }, delay)
            delay += duration + 200 // 阻塞时间 + 200ms间隔
        }
        // 所有阻塞任务派发完成后，再延迟一小段时间等待监控处理完最后一条消息，然后刷新统计
        mainHandler.postDelayed({
            Log.d(TAG, "===== 一键测试结束 =====")
            onComplete()
        }, delay + 300)
    }
}

@Composable
fun TestScreen(
    modifier: Modifier = Modifier,
    monitor: MainThreadMonitor,
    onBlock50: () -> Unit,
    onBlock100: () -> Unit,
    onBlock200: () -> Unit,
    onBlock500: () -> Unit,
    onBlock1000: () -> Unit,
    onBlock2000: () -> Unit,
    onOneClickTest: (onComplete: () -> Unit) -> Unit,
) {
    // 统计摘要以 Flow 形式自动观测：MonitorStatistics 每次更新都会发射最新摘要，
    // UI 通过 collectAsState 自动重组，无需手动点击刷新。
    // 注意：statisticsText 的读取被限制在下方 StatisticsSection 内部，
    // 使其变化只重组统计文本区域，而不波及上方的标题与全部按钮。
    var isTesting by remember { mutableStateOf(false) }

    // 大屏（车机横屏）优化：限制内容最大宽度并居中，放大字号与按钮高度
    val contentModifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 720.dp)

    val buttonModifier = Modifier
        .then(contentModifier)
        .height(64.dp)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "主线程耗时监控测试",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "点击按钮模拟主线程阻塞",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 一键测试
        Button(
            onClick = {
                isTesting = true
                onOneClickTest {
                    // 统计摘要通过 summaryFlow 自动观测刷新，无需在此手动赋值
                    isTesting = false
                }
            },
            enabled = !isTesting,
            modifier = buttonModifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text(
                text = if (isTesting) "一键测试进行中…" else "一键测试（依次阻塞 50/100/200/500/1000/2000ms）",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        HorizontalDivider(modifier = contentModifier)

        Spacer(modifier = Modifier.height(16.dp))

        // 单项测试按钮
        BlockButton("阻塞 50ms（触发50ms阈值）", onBlock50, buttonModifier)
        Spacer(modifier = Modifier.height(12.dp))
        BlockButton("阻塞 100ms（触发100ms阈值）", onBlock100, buttonModifier)
        Spacer(modifier = Modifier.height(12.dp))
        BlockButton("阻塞 200ms（触发200ms阈值）", onBlock200, buttonModifier)
        Spacer(modifier = Modifier.height(12.dp))
        BlockButton("阻塞 500ms（触发500ms阈值）", onBlock500, buttonModifier)
        Spacer(modifier = Modifier.height(12.dp))
        BlockButton("阻塞 1000ms（触发500ms阈值）", onBlock1000, buttonModifier)
        Spacer(modifier = Modifier.height(12.dp))
        BlockButton("阻塞 2000ms（触发500ms阈值）", onBlock2000, buttonModifier)

        Spacer(modifier = Modifier.height(20.dp))

        HorizontalDivider(modifier = contentModifier)

        Spacer(modifier = Modifier.height(20.dp))

        // 统计信息区域（Flow 自动观测，实时刷新，无需手动点击）
        Text(
            text = "统计信息（实时自动刷新）",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = contentModifier
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 统计文本区域隔离为独立 Composable：summaryFlow 的观测与读取都发生在其内部，
        // 因此摘要刷新时只重组这一小块 Text，上方标题和所有按钮不会参与重组。
        StatisticsSection(
            monitor = monitor,
            isTesting = isTesting,
            modifier = contentModifier
        )
    }
}

/**
 * 统计信息展示区。将统计观测收敛到此 Composable 内部，
 * 使刷新的重组范围被限制在这块文本，避免波及外层按钮列表。
 *
 * 关键 GC 优化：发射端(versionFlow)在主线程每条消息只自增版本号(Long,零分配)，不拼字符串。
 * 这里在 UI 侧先用 [sample] 按 500ms 采样，再在采样后的低频路径才 map { getSummary() } 构建
 * 字符串。因此摘要字符串每 500ms 最多构建一次，而非随每条主线程消息高频构建，从根本上消除
 * 大量临时字符串对象、抑制频繁 GC。
 */
@Composable
private fun StatisticsSection(
    monitor: MainThreadMonitor,
    isTesting: Boolean,
    modifier: Modifier = Modifier,
) {
    // remember 确保派生 Flow 只创建一次：版本号 -> 采样 -> 低频构建字符串。
    val summaryFlow = remember(monitor) {
        monitor.statistics.versionFlow
            .sample(500L)
            .map { monitor.statistics.getSummary() }
    }
    // 首帧同步构建一次作为初值，之后仅按采样节奏在低频路径构建。
    val statisticsText by summaryFlow.collectAsState(
        initial = monitor.statistics.getSummary()
    )
    Text(
        text = if (isTesting) "一键测试进行中，请稍候…\n\n$statisticsText" else statisticsText,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier
    )
}

@Composable
private fun BlockButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(onClick = onClick, modifier = modifier) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}