package com.ffalcon.mercury.android.sdk.demo.ui.activity.test.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * AudioModule：
 * - 采集 PCM16
 * - 可选 VAD（语音活动检测）：检测到“说话结束”调用 sink.sendAsrEnd()
 *
 * ✅ 针对你“环境噪声 45~47 但 floor 被锁到 2.x 导致永远 speaking=true”的修复：
 * 1) noiseFloor 初始化时跳过异常低 db（首包/空包）
 * 2) noiseFloor 采用“快速下降 + 慢速上升”的跟踪策略
 * 3) 即使 speaking 也允许 noiseFloor 慢速上升，避免一次误判后阈值卡死
 * 4) noiseFloor 夹紧到合理区间，避免离谱数值
 *
 * 调参建议：
 * - startMarginDb：噪声地板上方多少 dB 认为开始说话（6~15）
 * - endMarginDb：噪声地板上方多少 dB 认为仍在说话（通常比 start 小 3~6）
 * - silenceTimeoutMs：静音多久判定结束（600~1200ms）
 */
class AudioModule {

    companion object { private const val TAG = "AudioModule" }

    enum class VoiceDetectionMode { ENABLED, DISABLED }

    private var audioManager: AudioManager? = null
    private var audioRecord: AudioRecord? = null
    private var bufferSizeInBytes: Int = 512
    @Volatile private var isRecording = false

    private var enableVoiceDetection = true

    // ---------------- VAD 参数（默认可用，按现场再调） ----------------
    private var silenceTimeoutMs = 900L

    // 迟滞阈值：start > end，避免抖动
    private var startMarginDb = 8.0
    private var endMarginDb = 3.0

    // 噪声地板（自适应）
    private var noiseFloorDb = 60.0
    private var noiseInit = false

    private var speaking = false
    private var lastSpeechTime = 0L

    private var lastDbgLog = 0L

    //========================= Basic Func =========================
    fun start(
        context: Context,
        sampleRateInHz: Int = 16000,
        channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
        audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
        bufferSizeInBytes: Int = 2048,
        sink: AudioDataSender,
        voiceDetectionMode: VoiceDetectionMode = VoiceDetectionMode.ENABLED
    ) {
        this.bufferSizeInBytes = bufferSizeInBytes
        this.enableVoiceDetection = (voiceDetectionMode == VoiceDetectionMode.ENABLED)

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager?.setParameters("audio_source_record=record_origin3")

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRateInHz,
            channelConfig,
            audioFormat,
            this.bufferSizeInBytes
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            sink.onError(IllegalStateException("AudioRecord init failed"))
            return
        }

        audioRecord?.startRecording()
        isRecording = true

        // reset VAD
        speaking = false
        lastSpeechTime = System.currentTimeMillis()
        noiseInit = false
        noiseFloorDb = 60.0
        lastDbgLog = 0L

        Log.i(TAG, "🎙 start buffer=$bufferSizeInBytes VAD=$enableVoiceDetection")

        thread(start = true, name = "AudioModule-Recorder") {
            val audioBuffer = ByteArray(bufferSizeInBytes)
            try {
                while (isRecording && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val bytesRead = audioRecord?.read(audioBuffer, 0, bufferSizeInBytes) ?: 0
                    if (bytesRead > 0) {
                        // ✅ 先做 VAD 再交给 sink，降低 buffer 复用导致的抖动/误判
                        if (enableVoiceDetection) {
                            processVoiceLevel(audioBuffer, bytesRead, sink)
                        }
                        sink.onAudioData(audioBuffer, bytesRead)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "record error: ${e.message}", e)
                sink.onError(e)
            } finally {
                sink.onClose()
            }
        }
    }

    fun stop() {
        isRecording = false
        try {
            audioRecord?.let { record ->
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
                audioRecord = null
            }
            audioManager?.setParameters("audio_source_record=off")
            Log.i(TAG, "🛑 stop")
        } catch (e: Exception) {
            Log.e(TAG, "stop error: ${e.message}", e)
        }
    }

    //========================= Voice Detection =========================
    private fun processVoiceLevel(data: ByteArray, length: Int, sink: AudioDataSender) {
        val n = length and 0xFFFE // ✅ 偶数，避免 i+1 越界
        if (n <= 0) return

        var sum = 0.0
        var i = 0
        while (i < n) {
            // PCM16 little-endian
            val lo = data[i].toInt() and 0xFF
            val hi = data[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val s = sample.toShort().toInt()
            sum += (s * s).toDouble()
            i += 2
        }

        val rms = sqrt(sum / (n / 2))
        val db = 20.0 * log10(rms.coerceAtLeast(1.0))
        val now = System.currentTimeMillis()

        // 1) 初始化：跳过异常低 db（首包/空包），避免 floor 锁死到 0/2
        if (!noiseInit) {
            if (db > 10.0) {
                noiseFloorDb = db
                noiseInit = true
            }
        } else {
            // 2) 噪声地板跟踪：快速下降、慢速上升（环境变吵也能跟上）
            val alphaDown = 0.20  // 向下跟随速度（更安静时更快下降）
            val alphaUp = 0.01    // 向上跟随速度（更吵时慢慢上升，避免把人声当噪声）

            noiseFloorDb = if (db < noiseFloorDb) {
                noiseFloorDb * (1.0 - alphaDown) + db * alphaDown
            } else {
                noiseFloorDb * (1.0 - alphaUp) + db * alphaUp
            }

            // 3) 安全夹紧，避免离谱值
            noiseFloorDb = noiseFloorDb.coerceIn(20.0, 80.0)
        }

        val startTh = noiseFloorDb + startMarginDb
        val endTh = noiseFloorDb + endMarginDb

        // 调试日志：每 500ms 一次
        if (now - lastDbgLog > 500) {
            lastDbgLog = now
            Log.d(
                TAG,
                "VAD db=${"%.1f".format(db)} floor=${"%.1f".format(noiseFloorDb)} " +
                        "startTh=${"%.1f".format(startTh)} endTh=${"%.1f".format(endTh)} speaking=$speaking"
            )
        }

        if (!speaking) {
            if (noiseInit && db > startTh) {
                speaking = true
                lastSpeechTime = now
                Log.i(TAG, "🗣 start (db=${"%.1f".format(db)})")
            }
        } else {
            if (db > endTh) {
                lastSpeechTime = now
            } else if (now - lastSpeechTime > silenceTimeoutMs) {
                speaking = false
                Log.i(TAG, "🤫 end -> ASR_END (db=${"%.1f".format(db)})")
                sink.sendAsrEnd()
            }
        }
    }
}