package com.ffalcon.mercury.android.sdk.demo.ui.activity.test

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ffalcon.mercury.android.sdk.demo.databinding.ActivityRecordBinding
import com.ffalcon.mercury.android.sdk.demo.ui.activity.test.audio.AudioModule
import com.ffalcon.mercury.android.sdk.demo.ui.activity.test.audio.WifiAudioSender
import com.ffalcon.mercury.android.sdk.demo.ui.activity.test.camera.CameraModule
import com.ffalcon.mercury.android.sdk.demo.ui.activity.test.camera.WifiCameraSender
import com.ffalcon.mercury.android.sdk.demo.ui.activity.test.imu.ImuModule
import com.ffalcon.mercury.android.sdk.demo.ui.activity.test.imu.WifiImuSender
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RecordActivity : BaseMirrorActivity<ActivityRecordBinding>() {

    //------------------------------ Modules ------------------------------//
    private lateinit var imuModule: ImuModule
    private lateinit var cameraModule: CameraModule
    private lateinit var recorder: AudioModule

    //------------------------------ Network ------------------------------//
    private lateinit var audioSink: WifiAudioSender
     private lateinit var videoSink: WifiCameraSender
     private lateinit var imuSink: WifiImuSender

    private lateinit var audioSender: WifiSender
    private lateinit var videoSender: WifiSender
    private lateinit var imuSender: WifiSender

    private val serverIP = "192.168.8.40"
    private val portAudio = 50005
    private val portVideo = 50006
    private val portIMU = 50007

    //------------------------------ 状态 ------------------------------//
    private var isNetworkReady = false
    private val PERMISSION_REQUEST_CODE = 1001

    // 采集/推流状态（避免重复 start/stop）
    private var isStreaming = false
    private var networkLoopJob: Job? = null

    //------------------------------ 模式 ------------------------------//
    enum class Mode(val displayName: String) {
        DEFAULT("DEFAULT"), RECORD("RECORD"), STORE("STORE");
        fun next(): Mode = values()[(ordinal + 1) % values().size]
        fun previous(): Mode = values()[(ordinal - 1 + values().size) % values().size]
    }
    private var currentMode = Mode.DEFAULT

    //========================= 生命周期 =========================//
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initModules()
        initUIEvents()
        ensureAllPermissions()
    }

    override fun onPause() {
        super.onPause()
        stopStreaming()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        networkLoopJob?.cancel()

        // 模块级资源再兜底
        stopIMU()
        stopCamera()

        if (::audioSender.isInitialized) audioSender.close()
        if (::videoSender.isInitialized) videoSender.close()
        if (::imuSender.isInitialized) imuSender.close()
    }

    //========================= 权限与初始化 =========================//
    private fun ensureAllPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            initNetwork()
        }
    }

    override fun onRequestPermissionsResult(req: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(req, permissions, results)
        if (req == PERMISSION_REQUEST_CODE && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            initNetwork()
        } else {
            Toast.makeText(this, "❌ 权限被拒绝", Toast.LENGTH_LONG).show()
        }
    }

    private fun initModules() {
        imuModule = ImuModule().apply { init(this@RecordActivity) }
        cameraModule = CameraModule(this)
    }

    //========================= 网络 =========================//
    private fun initNetwork() {
        audioSender = WifiSender(serverIP, portAudio).apply { start() }
        videoSender = WifiSender(serverIP, portVideo).apply { start() }
        imuSender = WifiSender(serverIP, portIMU).apply { start() }

        // IMU 先启动采集（但是否发送由模式/网络控制）
        initIMU()

        networkLoopJob?.cancel()
        networkLoopJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)

                val ready = audioSender.isConnected() && videoSender.isConnected() && imuSender.isConnected()
                runOnUiThread { updateNetworkStatusUI(ready) }

                if (ready && !isNetworkReady) {
                    isNetworkReady = true
                    Log.i("RecordActivity", "🌐 网络恢复")
                    startStreamingIfPossible()
                } else if (!ready && isNetworkReady) {
                    isNetworkReady = false
                    Log.w("RecordActivity", "⚠️ 网络断开，停止音视频与IMU")
                    stopStreaming()
                } else {
                    startStreamingIfPossible()
                }
            }
        }
    }

    private fun updateNetworkStatusUI(isConnected: Boolean) {
        val text = if (isConnected) "Connected" else "Disconnected"
        val color = ContextCompat.getColor(
            this,
            if (isConnected) android.R.color.holo_green_light else android.R.color.holo_red_light
        )
        mBindingPair.updateView {
            tvNetworkStatus.text = text
            (viewStatusIndicator.background as? GradientDrawable)?.setColor(color)
        }
    }

    //========================= 采集/推流控制（只在 RECORD） =========================//
    private fun updateImuSendByMode() {
        val enable = (currentMode == Mode.RECORD) && isNetworkReady
        imuModule.setWifiSendEnabled(enable)
    }

    private fun startStreamingIfPossible() {
        if (isStreaming) return
        if (!isNetworkReady) return
        if (currentMode != Mode.RECORD) return

        // Audio
        initAudio()

        // Video
        initCamera()

        // IMU
        updateImuSendByMode()

        isStreaming = true
        Log.i("RecordActivity", "▶️ startStreaming (mode=RECORD)")
    }

    private fun stopStreaming() {
        if (!isStreaming) {
            // 防御式 stop
            stopCamera()
            stopAudio()
            // IMU 采集可以继续（你也可以选择停），但发送一定要关掉
            updateImuSendByMode()
            return
        }

        stopCamera()
        stopAudio()

        // 退出 RECORD 或网络断开时，立刻停发 IMU
        updateImuSendByMode()

        isStreaming = false
        Log.i("RecordActivity", "⏹ stopStreaming")
    }

    //========================= 音频 =========================//
    private fun initAudio() {
        if (!audioSender.isConnected()) return
        if (this::recorder.isInitialized) stopAudio()

        recorder = AudioModule()
        audioSink = WifiAudioSender(audioSender)
        recorder.start(
            context = this,
            sampleRateInHz = 16000,
            channelConfig = AudioFormat.CHANNEL_IN_MONO,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeInBytes = 2048,
            sink = audioSink,
            voiceDetectionMode = AudioModule.VoiceDetectionMode.DISABLED
        )
        Log.i("RecordActivity", "🎙️ 录音启动")
    }

    private fun stopAudio() {
        try {
            if (this::recorder.isInitialized) recorder.stop()
            Log.i("RecordActivity", "🔇 录音停止")
        } catch (_: Exception) {}
    }

    //========================= IMU =========================//
    private fun initIMU() {
        try {
            // 采集启动（是否发送由 setWifiSendEnabled 控制）
            imuSink = WifiImuSender(imuSender)
            imuModule.start(imuSink)
            imuModule.setWifiSendEnabled(false) // 默认不发，等 RECORD + 网络就绪再打开
            Log.i("RecordActivity", "🧭 IMU 启动(采集), 默认不发送")
        } catch (e: Exception) {
            Log.e("RecordActivity", "IMU init failed", e)
        }
    }

    private fun stopIMU() {
        try {
            imuModule.setWifiSendEnabled(false)
        } catch (_: Exception) {}

        try {
            imuModule.stop()
            Log.i("RecordActivity", "🛑 IMU 停止")
        } catch (_: Exception) {}
    }

    //========================= Camera =========================//
    private fun initCamera() {
        try {
            videoSink = WifiCameraSender(videoSender)
            cameraModule.init()
            cameraModule.start(videoSink)
            Log.i("RecordActivity", "📷 Camera 启动")
        } catch (e: Exception) {
            Log.e("RecordActivity", "Camera init/start failed", e)
        }
    }

    private fun stopCamera() {
        try {
            cameraModule.stop()
            Log.i("RecordActivity", "🛑 Camera 停止")
        } catch (_: Exception) {}
    }

    //========================= UI交互 =========================//
    private fun initUIEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect {
                    when (it) {
                        is TempleAction.DoubleClick -> finish()
                        is TempleAction.SlideBackward -> switchMode(currentMode.previous())
                        is TempleAction.SlideForward -> switchMode(currentMode.next())
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun switchMode(mode: Mode) {
        currentMode = mode
        mBindingPair.updateView { btnMode.text = mode.displayName }
        Toast.makeText(this, "切换模式: ${mode.displayName}", Toast.LENGTH_SHORT).show()

        if (currentMode == Mode.RECORD) startStreamingIfPossible() else stopStreaming()
    }
}