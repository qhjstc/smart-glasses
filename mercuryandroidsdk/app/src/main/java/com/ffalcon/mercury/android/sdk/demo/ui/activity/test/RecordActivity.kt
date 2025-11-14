package com.ffalcon.mercury.android.sdk.demo.ui.activity.test

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.hardware.*
import android.hardware.camera2.*
import android.media.*
import android.os.*
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ffalcon.mercury.android.sdk.demo.databinding.ActivityTestBinding
import com.ffalcon.mercury.android.sdk.demo.ui.activity.test.audio.AudioRecorderModule
import com.ffalcon.mercury.android.sdk.demo.ui.activity.test.audio.WifiAudioSender
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import kotlinx.coroutines.launch
import java.net.Socket
import kotlin.math.sqrt

class RecordActivity : BaseMirrorActivity<ActivityTestBinding>(), SensorEventListener {

    //------------------------------ Sensors (IMU) ------------------------------//
    private lateinit var sensorManager: SensorManager
    private var gameRotationVectorSensor: Sensor? = null
    private var lastIMUUpdate = 0L

    //------------------------------ Camera ------------------------------//
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var backThread: HandlerThread
    private lateinit var backHandler: Handler
    private lateinit var videoEncoder: MediaCodec
    private lateinit var inputSurface: Surface

    //------------------------------ Audio ------------------------------//
    private lateinit var recorder: AudioRecorderModule
    private lateinit var audioSink: WifiAudioSender

    //------------------------------ Network Senders ------------------------------//
    private lateinit var audioSender: WifiSender
    private lateinit var videoSender: WifiSender
    private lateinit var imuSender: WifiSender

    private val serverIP = "192.168.43.225"
    private val portAudio = 50005
    private val portVideo = 50006
    private val portIMU = 50007

    //------------------------------ Mode ------------------------------//
    enum class Mode(val displayName: String) {
        DEFAULT("DEFAULT"),
        RECORD("RECORD"),
        STORE("STORE");
        fun next(): Mode = values()[(ordinal + 1) % values().size]
        fun previous(): Mode = values()[(ordinal - 1 + values().size) % values().size]
    }

    private var currentMode = Mode.DEFAULT
    private val PERMISSION_REQUEST_CODE = 1001
    private var isNetworkReady = false

    //========================= 生命周期 =========================//
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureAllPermissions()
        initIMU()
        initUIEvents()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    override fun onResume() {
        super.onResume()
        gameRotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        stopCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudio()
        stopCamera()
        if (::audioSender.isInitialized) audioSender.close()
        if (::videoSender.isInitialized) videoSender.close()
        if (::imuSender.isInitialized) imuSender.close()
        if (::backThread.isInitialized) backThread.quitSafely()
    }

    //========================= 权限请求 =========================//
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
            Toast.makeText(this, "✅ 权限已授权", Toast.LENGTH_SHORT).show()
            initNetwork()
        }
    }

    /**
     * 🔁 当用户点击“允许 / 拒绝”权限弹窗按钮后系统回调到这里
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val denied = permissions.zip(grantResults.toTypedArray())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }

            if (denied.isEmpty()) {
                Toast.makeText(this, "✅ 权限全部授予", Toast.LENGTH_SHORT).show()
                initNetwork() // 🔸 所有权限通过后再初始化音视频
            } else {
                Toast.makeText(
                    this,
                    "❌ 被拒绝权限: ${denied.joinToString { it.first.split('.').last() }}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    //========================= 网络 =========================//
    private fun initNetwork() {
        Thread {
            audioSender = WifiSender(serverIP, portAudio).apply { start() }
            videoSender = WifiSender(serverIP, portVideo).apply { start() }
            imuSender = WifiSender(serverIP, portIMU).apply { start() }

            isNetworkReady =
                audioSender.isConnected() && videoSender.isConnected() && imuSender.isConnected()

            runOnUiThread {
                updateNetworkStatusUI(isNetworkReady)
                if (isNetworkReady) {
                    initAudio()
                    initVideo()
                }
            }
        }.start()
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
        recorder = AudioRecorderModule()
        audioSink = WifiAudioSender(audioSender)
        startAudio()
    }

    private fun startAudio() {
        recorder.start(
            context = this,
            sampleRateInHz = 16000,
            channelConfig = AudioFormat.CHANNEL_IN_MONO,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeInBytes = 2048,
            sink = audioSink,
            voiceDetectionMode = AudioRecorderModule.VoiceDetectionMode.DISABLED
        )
    }

    private fun stopAudio() {
        try { recorder.stop() } catch (_: Exception) {}
    }

    //========================= 视频 =========================//
    @SuppressLint("MissingPermission")
    private fun initVideo() {
        backThread = HandlerThread("CamThread").apply { start() }
        backHandler = Handler(backThread.looper)
        val camId = cameraManager.cameraIdList.first()
        cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(cd: CameraDevice) {
                cameraDevice = cd
                startVideoEncoder()
            }
            override fun onDisconnected(cd: CameraDevice) {
                cd.close(); cameraDevice = null
            }
            override fun onError(cd: CameraDevice, error: Int) {
                cd.close(); cameraDevice = null
            }
        }, backHandler)
    }

    private fun startVideoEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = videoEncoder.createInputSurface()
        videoEncoder.start()
        startCameraPreview()
        startVideoEncodeLoop()
    }

    private fun startCameraPreview() {
        val cam = cameraDevice ?: return
        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(inputSurface)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(15, 30))
        }
        cam.createCaptureSession(listOf(inputSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                session.setRepeatingRequest(req.build(), null, backHandler)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, backHandler)
    }

    private fun startVideoEncodeLoop() {
        Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val outIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    val buffer = videoEncoder.getOutputBuffer(outIndex) ?: continue
                    val bytes = ByteArray(bufferInfo.size)
                    buffer.get(bytes)
                    buffer.clear()
                    videoSender.sendBytes(bytes)  // 🚀 发送视频帧
                    videoEncoder.releaseOutputBuffer(outIndex, false)
                }
            }
        }.start()
    }

    private fun stopCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { videoEncoder.stop(); videoEncoder.release() } catch (_: Exception) {}
    }

    //========================= IMU =========================//
    private fun initIMU() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        gameRotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        mBindingPair.updateView {
            if (gameRotationVectorSensor == null) tvRot.text = "IMU unavailable"
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return
        val now = System.currentTimeMillis()
        if (now - lastIMUUpdate < 100) return
        lastIMUUpdate = now

        val v = event.values
        val (qx, qy, qz, qw) = if (v.size >= 4)
            arrayOf(v[0], v[1], v[2], v[3])
        else {
            val t = 1f - v[0]*v[0]-v[1]*v[1]-v[2]*v[2]
            arrayOf(v[0], v[1], v[2], if (t>0f) sqrt(t) else 0f)
        }

        val euler = quaternionToEuler(qx, qy, qz, qw)
        val json = """{"yaw":${euler[2]},"pitch":${euler[0]},"roll":${euler[1]},"ts":$now}"""
        imuSender.sendJson(json)
        mBindingPair.updateView {
            tvRot.text = "Yaw: %.1f\nPitch: %.1f\nRoll: %.1f"
                .format(euler[2], euler[0], euler[1])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun quaternionToEuler(x: Float, y: Float, z: Float, w: Float): FloatArray {
        val e = FloatArray(3)
        val sinP = 2f * (w * x + y * z)
        val cosP = 1f - 2f * (x * x + y * y)
        e[0] = Math.toDegrees(Math.atan2(sinP.toDouble(), cosP.toDouble())).toFloat()
        val sinR = 2.0 * (w * y - z * x)
        e[1] = Math.toDegrees(Math.asin(sinR.coerceIn(-1.0, 1.0))).toFloat()
        val sinY = 2f * (w * z + x * y)
        val cosY = 1f - 2f * (y * y + z * z)
        e[2] = Math.toDegrees(Math.atan2(sinY.toDouble(), cosY.toDouble())).toFloat()
        return e
    }

    //========================= UI 事件 =========================//
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