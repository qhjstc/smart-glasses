package com.ffalcon.mercury.android.sdk.demo.ui.activity.test

import android.Manifest
import android.content.Context
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
import com.ffalcon.mercury.android.sdk.demo.ui.activity.test.audio.AudioRecorderModule
import com.ffalcon.mercury.android.sdk.demo.ui.activity.test.audio.WifiAudioSender
import com.ffalcon.mercury.android.sdk.demo.ui.activity.test.camera.CameraModule
import com.ffalcon.mercury.android.sdk.demo.ui.activity.test.imu.ImuModule
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RecordActivity : BaseMirrorActivity<ActivityRecordBinding>() {

    //------------------------------ Modules ------------------------------//
    private lateinit var imuModule: ImuModule
    private lateinit var cameraModule: CameraModule
    private lateinit var recorder: AudioRecorderModule
    private lateinit var audioSink: WifiAudioSender

    //------------------------------ Network ------------------------------//
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
        ensureAllPermissions()
        initModules()
        initUIEvents()
    }

    override fun onPause() {
        super.onPause()
        imuModule.stop()
        cameraModule.stop()
        stopAudio()
    }

    override fun onDestroy() {
        super.onDestroy()
        imuModule.stop()
        cameraModule.stop()
        stopAudio()
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
        if (req == PERMISSION_REQUEST_CODE && results.all { it == PackageManager.PERMISSION_GRANTED })
            initNetwork()
        else Toast.makeText(this, "❌ 权限被拒绝", Toast.LENGTH_LONG).show()
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

        lifecycleScope.launch {
            while (true) {
                delay(1000)
                val ready = audioSender.isConnected() && videoSender.isConnected() && imuSender.isConnected()
                runOnUiThread { updateNetworkStatusUI(ready) }

                if (ready && !isNetworkReady) {
                    isNetworkReady = true
                    Log.i("RecordActivity", "🌐 网络恢复")
                    lifecycleScope.launch {
                        delay(500)
                        initAudio()
                        cameraModule.init(videoSender)
                        cameraModule.start()
                    }
                } else if (!ready && isNetworkReady) {
                    isNetworkReady = false
                    Log.w("RecordActivity", "⚠️ 网络断开，停止音视频与IMU")
                    cameraModule.stop()
                    stopAudio()
                    imuModule.stop()
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

    //========================= 音频 =========================//
    private fun initAudio() {
        if (!audioSender.isConnected()) return
        if (this::recorder.isInitialized) stopAudio()
        recorder = AudioRecorderModule()
        audioSink = WifiAudioSender(audioSender)
        recorder.start(
            context = this,
            sampleRateInHz = 16000,
            channelConfig = AudioFormat.CHANNEL_IN_MONO,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeInBytes = 2048,
            sink = audioSink,
            voiceDetectionMode = AudioRecorderModule.VoiceDetectionMode.DISABLED
        )
        Log.i("RecordActivity", "🎙️ 录音启动")
    }

    private fun stopAudio() {
        try {
            recorder.stop()
            Log.i("RecordActivity", "🔇 录音停止")
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
        Toast.makeText(this, "切换模式: ${mode.displayName}", Toast.LENGTH_SHORT).show()
    }
}