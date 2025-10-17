package com.ffalcon.mercury.android.sdk.demo.ui.activity.test

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.*
import android.hardware.camera2.*
import android.media.*
import android.os.*
import android.util.Log
import android.util.Range
import android.view.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ffalcon.mercury.android.sdk.demo.databinding.ActivityTestBinding
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt


/**
 * ✅ 单端口双向通信 + Camera/IMU + WifiSender结构
 * 支持音频、IMU、相机、模式切换
 */
class TestActivity : BaseMirrorActivity<ActivityTestBinding>(), SensorEventListener {

    //========================= IMU =========================
    private lateinit var sensorManager: SensorManager
    private var gameRotationVectorSensor: Sensor? = null
    private var lastIMUUpdate = 0L

    //========================= Audio & Network =========================
    private lateinit var recorder: AudioRecorderModule
    private lateinit var wifiSender: WifiSender
    private lateinit var audioSink: WifiAudioSender
    private val RECORD_AUDIO_REQUEST_CODE = 200
    private val serverIP = "192.168.8.40"       // ⚠️ 改成你自己的服务器 IP
    private val unifiedPort = 50005
    private var unifiedSocket: Socket? = null
    private var unifiedIn: InputStream? = null

    //========================= 模式定义 =========================
    enum class Mode(val displayName: String) {
        DEFAULT("DEFAULT"),
        TALKING("TALK"),
        TRACKING("TRACK"),
        TRANSLATION("TRSL");

        fun next(): Mode = values()[(ordinal + 1) % values().size]
        fun previous(): Mode = values()[(ordinal - 1 + values().size) % values().size]
    }

    private var currentMode = Mode.DEFAULT

    //========================= Camera2 =========================
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var backThread: HandlerThread
    private lateinit var backHandler: Handler
    private val previewSurfaces = mutableListOf<Surface>()

    //========================= 生命周期 =========================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initIMU()
        initAudio()
        initUIEvents()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    //========================= 模式切换 =========================
    private fun switchMode(newMode: Mode) {
        if (currentMode == newMode) return
        currentMode = newMode
        mBindingPair.updateView { btnMode.text = currentMode.displayName }

        wifiSender.sendJson("""{"type":"MODE","mode":"${newMode.name}"}""")

        when (newMode) {
             Mode.TRACKING -> {
                stopRecordingIfNeeded()
                startTrackingMode()
            }
            Mode.TALKING, Mode.TRANSLATION -> {
                stopTrackingMode()
                if (wifiSender.isConnected()) startRecordingIfNeeded()
            }
            else -> {
                stopTrackingMode()
                stopRecordingIfNeeded()
            }
        }
    }

    //========================= IMU =========================
    private fun initIMU() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        gameRotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        mBindingPair.updateView {
            if (gameRotationVectorSensor == null) tvRotation.text = "IMU unavailable"
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
        mBindingPair.updateView {
            tvRotation.text = "Yaw: %.1f\nPitch: %.1f\nRoll: %.1f"
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

    //========================= Camera =========================
    private fun startTrackingMode() {
        mBindingPair.updateView {
            cameraContainer.visibility = View.VISIBLE
            cameraOverlay.visibility = View.GONE
        }

        backThread = HandlerThread("CameraThread", Process.THREAD_PRIORITY_MORE_FAVORABLE).apply { start() }
        backHandler = Handler(backThread.looper)
        previewSurfaces.clear()

        mBindingPair.updateView {
            val textureView = textureViewCamera
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                var surfaceRef: Surface? = null
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    st.setDefaultBufferSize(1920, 1080)
                    surfaceRef = Surface(st)
                    synchronized(previewSurfaces) {
                        previewSurfaces.add(surfaceRef!!)
                        openCameraIfNeeded()
                    }
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    synchronized(previewSurfaces) { previewSurfaces.remove(surfaceRef) }
                    surfaceRef?.release()
                    surfaceRef = null
                    return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
            if (textureView.isAvailable) {
                val st = textureView.surfaceTexture!!
                val s = Surface(st)
                synchronized(previewSurfaces) {
                    previewSurfaces.add(s)
                    Log.d("Camera", "Immediate Surface ready count = ${previewSurfaces.size}")
                    if (previewSurfaces.size == 2) openCameraIfNeeded()
                }
            }
        }
    }

    private fun stopTrackingMode() {
        try {
            captureSession?.close()
            cameraDevice?.close()
        } catch (_: Exception) {}
        captureSession = null
        cameraDevice = null
        mBindingPair.updateView { cameraContainer.visibility = View.GONE }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraIfNeeded() {
        if (cameraDevice != null || previewSurfaces.isEmpty()) return
        val camId = cameraManager.cameraIdList.first()
        cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                startCameraPreview()
            }
            override fun onDisconnected(device: CameraDevice) {
                device.close(); cameraDevice = null
            }
            override fun onError(device: CameraDevice, error: Int) {
                device.close(); cameraDevice = null
            }
        }, backHandler)
    }

    private fun startCameraPreview() {
        val cam = cameraDevice ?: return
        val validSurfaces = previewSurfaces.filter { it.isValid }
        if (validSurfaces.isEmpty()) return
        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            for (s in validSurfaces) addTarget(s)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(15, 30))
        }
        cam.createCaptureSession(validSurfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                session.setRepeatingRequest(req.build(), null, backHandler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, backHandler)
    }

    //========================= Audio & TCP =========================
    private fun initAudio() {
        recorder = AudioRecorderModule()

        // ✅ 在后台线程中建立连接，防止主线程被阻塞
        Thread {
            wifiSender = WifiSender(serverIP, unifiedPort)
            val ok = wifiSender.start()
            runOnUiThread {
                if (ok) {
                    updateNetworkStatusUI(true)
                    audioSink = WifiAudioSender(wifiSender)
                    unifiedSocket = wifiSender.socketRef
                    unifiedIn = wifiSender.socketRef?.getInputStream()
                    if (currentMode == Mode.TRANSLATION) startRecordingIfNeeded()
                    unifiedIn?.let { startUnifiedReceiver(it) }
                } else {
                    updateNetworkStatusUI(false)
                    Log.e("Network", "TCP connect failed")
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
            (statusIndicator.background as? GradientDrawable)?.setColor(color)
        }
    }

    private fun startRecordingIfNeeded() {
        if (recorderIsRecording() || !wifiSender.isConnected()) return
        recorder.start(
            context = this,
            sampleRateInHz = 16000,
            channelConfig = AudioFormat.CHANNEL_IN_MONO,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeInBytes = 2048,
            sink = audioSink
        )
    }

    private fun stopRecordingIfNeeded() {
        if (recorderIsRecording()) recorder.stop()
    }

    private fun recorderIsRecording(): Boolean {
        return try {
            val f = AudioRecorderModule::class.java.getDeclaredField("isRecording")
            f.isAccessible = true
            f.getBoolean(recorder)
        } catch (_: Exception) { false }
    }

    //========================= 接收服务器结果 =========================
    private fun startUnifiedReceiver(input: InputStream) {
        Thread {
            try {
                val lenBuf = ByteArray(4)
                while (true) {
                    val readHead = input.read(lenBuf)
                    if (readHead != 4) break

                    // 读取帧内容
                    val len = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).int
                    val data = ByteArray(len)
                    var totalRead = 0
                    while (totalRead < len) {
                        val count = input.read(data, totalRead, len - totalRead)
                        if (count <= 0) throw Exception("Stream closed mid-frame")
                        totalRead += count
                    }

                    // 解析 JSON
                    val jsonStr = String(data, Charsets.UTF_8)
                    val json = JSONObject(jsonStr)
                    val type = json.optString("type")

                    when (type) {

                        // ✅ 实时翻译结果（TRANSLATION 模式）
                        "TRANSLATION_PARTIAL" -> {
                            val zh = json.optString("zh")
                            val en = json.optString("en")
                            runOnUiThread {
                                mBindingPair.updateView {
                                    audioText.setText("原文：$zh\n译文：$en")
                                }
                            }
                        }

                        // ✅ 语音识别最终结果（TALKING 模式一句结束）
                        "RESULT" -> {
                            val text = json.optString("transcription")
                            runOnUiThread {
                                mBindingPair.updateView {
                                    audioText.setText("识别结果：$text")
                                }
                            }
                        }

                        // ✅ LLM 对话内容（AI回复）
                        "CHAT" -> {
                            val user = json.optString("user_text")
                            val reply = json.optString("reply")
                            runOnUiThread {
                                mBindingPair.updateView {
                                    audioText.setText("👤 用户：$user\n🤖 AI：$reply\n\n")
                                }
                            }
                        }

                        // ✅ 错误消息
                        "ERROR" -> {
                            val msg = json.optString("msg")
                            Log.e("UnifiedReceiver", "❌ 服务端错误：$msg")
                        }

                        // 其他未知类型，仅打印日志
                        else -> {
                            Log.w("UnifiedReceiver", "🔹 Unknown message: $jsonStr")
                        }
                    }

                }
            } catch (e: Exception) {
                Log.e("UnifiedReceiver", "❌ error", e)
                runOnUiThread { updateNetworkStatusUI(false) }
            }
        }.start()
    }

    //========================= UI 控件事件 =========================
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

    //========================= 生命周期 =========================
    override fun onResume() {
        super.onResume()
        gameRotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        if (currentMode == Mode.TRACKING) stopTrackingMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecordingIfNeeded()
        wifiSender.close()
        if (currentMode == Mode.TRACKING) stopTrackingMode()
    }
}