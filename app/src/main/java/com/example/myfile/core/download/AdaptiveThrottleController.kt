package com.example.myfile.core.download

import com.example.myfile.core.DownloadLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 自适应限速规避控制器。
 *
 * 原理：运营商对「持续大流量」限速（速度骤降到某个低值，冷却一段时间后恢复）。
 * 检测到速度骤降 → 触发「换端口」信号，让引擎暂停当前连接、切换到新端口继续，
 * 利用断点续传不丢进度。
 *
 * 关键：区分「被限速」和「网络本身差」——
 *  - 被限速：之前达到过高速度（峰值），突然骤降并持续 → 触发规避
 *  - 网络差：从未达到过高速度 → 不触发（换端口也没用）
 */
class AdaptiveThrottleController(
    private val scope: CoroutineScope,
    private val onThrottleDetected: () -> Unit
) {
    companion object {
        private const val TAG = "ThrottleCtrl"
        // 判定参数
        private const val SAMPLE_INTERVAL_MS = 500L   // 采样间隔
        private const val PEAK_KEEP_MS = 10_000L      // 峰值记忆时长（10秒内的峰值）
        private const val DROP_THRESHOLD_RATIO = 0.2  // 当前速度 < 峰值 20% 视为骤降
        private const val DROP_SUSTAIN_SAMPLES = 6    // 持续 6 个采样（3秒）才判定限速
        private const val MIN_PEAK_BPS = 1_000_000L   // 峰值至少要达到 1MB/s 才认为"曾经快过"
    }

    private val totalBytes = AtomicLong(0)
    private val lastBytes = AtomicLong(0)
    // 峰值跟踪：记录最近 N 秒的最高速度
    private var peakBps = 0L
    private var peakTimestamp = 0L
    private var slowSampleCount = 0
    private var job: Job? = null
    @Volatile private var enabled = false

    /** 启动监测 */
    fun start() {
        enabled = true
        job = scope.launch(Dispatchers.IO) {
            while (enabled) {
                delay(SAMPLE_INTERVAL_MS)
                val now = totalBytes.get()
                val prev = lastBytes.getAndSet(now)
                val instantBps = (now - prev) * (1000 / SAMPLE_INTERVAL_MS)
                sample(instantBps)
            }
        }
    }

    /** 停止监测 */
    fun stop() {
        enabled = false
        job?.cancel()
        job = null
    }

    /** 上报累计下载字节（由引擎调用） */
    fun reportBytes(delta: Long) {
        totalBytes.addAndGet(delta)
    }

    private fun sample(instantBps: Long) {
        val now = System.currentTimeMillis()
        // 更新峰值
        if (instantBps > peakBps) {
            peakBps = instantBps
            peakTimestamp = now
        }
        // 峰值过期（超过记忆时长），衰减
        if (now - peakTimestamp > PEAK_KEEP_MS) {
            peakBps = (peakBps * 0.8).toLong()
            peakTimestamp = now
        }

        // 判定是否被限速
        val wasFast = peakBps >= MIN_PEAK_BPS
        val isSlow = instantBps < peakBps * DROP_THRESHOLD_RATIO

        if (wasFast && isSlow) {
            slowSampleCount++
            DownloadLog.log(TAG, "slow detected: ${instantBps}B/s vs peak ${peakBps}B/s (count=$slowSampleCount)")
            if (slowSampleCount >= DROP_SUSTAIN_SAMPLES) {
                DownloadLog.log(TAG, "throttle detected! peak=${peakBps}B/s now=${instantBps}B/s -> switch port")
                slowSampleCount = 0
                // 重置峰值，避免连续触发
                peakBps = 0L
                onThrottleDetected()
            }
        } else {
            if (slowSampleCount > 0) slowSampleCount--
        }
    }
}
