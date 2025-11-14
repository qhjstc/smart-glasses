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
 * AudioRecorderModule：
 * 支持语音活动检测（可开关）
 */
class AudioRecorderModule {

    companion object {
        private const val TAG = "AudioRecorderModule"
    }

    enum class VoiceDetectionMode {
        ENABLED,   // 开启语音活动检测（发 ASR_END）
        DISABLED   // 仅采集音频，不检测语音状态
    }

    private var audioManager: AudioManager? = null
    private var audioRecord: AudioRecord? = null
    private var bufferSizeInBytes: Int = 512
    @Volatile private var isRecording = false

    private var enableVoiceDetection = true

    // --- 语音活动检测参数 ---
    private var silenceThresholdDb = 45.0
    private var silenceTimeoutMs = 900L
    private var speaking = false
    private var lastSpeechTime = 0L

    private var lastDbLogTime = 0L
    private val dbWindow = ArrayDeque<Double>()
    private val smoothWindow = 5

    //========================= Basic Func =========================
    fun start(
        context: Context,
        sampleRateInHz: Int = 16000,
        channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
        audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
        bufferSizeInBytes: Int = 2048,
        sink: AudioDataSender,
        voiceDetectionMode: VoiceDetectionMode = VoiceDetectionMode.ENABLED // 🆕 控制开关
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
        speaking = false
        lastSpeechTime = System.currentTimeMillis()

        Log.i(TAG, "🎙 开始录音 buffer=$bufferSizeInBytes 检测开关=$enableVoiceDetection")

        thread(start = true) {
            val audioBuffer = ByteArray(bufferSizeInBytes)
            try {
                while (isRecording && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val bytesRead = audioRecord?.read(audioBuffer, 0, bufferSizeInBytes) ?: 0
                    if (bytesRead > 0) {
                        sink.onAudioData(audioBuffer, bytesRead)
                        if (enableVoiceDetection) {
                            processVoiceLevel(audioBuffer, bytesRead, sink)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "录音异常: ${e.message}")
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
            Log.i(TAG, "🛑 停止录音")
        } catch (e: Exception) {
            Log.e(TAG, "停止录音异常: ${e.message}")
        }
    }

    //========================= Voice Detection =========================
    private fun processVoiceLevel(data: ByteArray, length: Int, sink: AudioDataSender) {
        var sum = 0.0
        for (i in 0 until length step 2) {
            val sample = (data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)
            sum += (sample * sample).toDouble()
        }

        val rms = sqrt(sum / (length / 2))
        val db = 20 * log10(rms.coerceAtLeast(1.0))

        if (dbWindow.size >= smoothWindow) dbWindow.removeFirst()
        dbWindow.addLast(db)
        val avgDb = dbWindow.average()
        val now = System.currentTimeMillis()

        if (avgDb > silenceThresholdDb) {
            if (!speaking) {
                Log.d(TAG, "🗣 检测到开始说话 (db=${"%.1f".format(avgDb)})")
                speaking = true
            }
            lastSpeechTime = now
        } else {
            if (speaking && now - lastSpeechTime > silenceTimeoutMs) {
                speaking = false
                Log.i(TAG, "🤫 检测到语音结束, 发送 ASR_END (db=${"%.1f".format(avgDb)})")
                sink.sendAsrEnd()
            }
        }
    }
}