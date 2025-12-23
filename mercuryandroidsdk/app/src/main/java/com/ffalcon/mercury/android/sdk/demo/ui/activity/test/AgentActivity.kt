package com.ffalcon.mercury.android.sdk.demo.ui.activity.test

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.media.AudioFormat
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.util.Range
import android.view.View
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ffalcon.mercury.android.sdk.demo.databinding.ActivityAgentBinding
import com.ffalcon.mercury.android.sdk.demo.ui.activity.test.audio.AudioModule
import com.ffalcon.mercury.android.sdk.demo.ui.activity.test.audio.WifiAudioSender
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * 单端口双向通信：
 * - TALKING/TRANSLATION：仅发送 Audio
 * - TRACKING：点击拍照 -> JPEG -> WiFi 发送
 *
 * ✅ 更新：
 * - 支持服务端 TALKING 流式返回：type=CHAT_STREAM, delta, is_final, full_reply
 * - 显示机制：TALKING 下展示 “你：ASR / AI：流式回复”
 * - ✅ 30s 心跳保活：{"type":"PING","ts":..., "mode":...}
 */
class AgentActivity : BaseMirrorActivity<ActivityAgentBinding>(), SensorEventListener {

    companion object { private const val TAG = "AgentActivity" }

    //------------------------------ IMU（本地显示） ------------------------------//
    private lateinit var sensorManager: SensorManager
    private var gameRotationVectorSensor: Sensor? = null
    private var lastIMUUpdate = 0L

    //------------------------------ Audio & Network ------------------------------//
    private lateinit var recorder: AudioModule
    private lateinit var wifiSender: WifiSender
    private lateinit var audioSink: WifiAudioSender

    private val serverIP = "192.168.8.40"
    private val unifiedPort = 50005

    private var unifiedIn: InputStream? = null
    private var receiverStarted = false

    //------------------------------ 状态 ------------------------------//
    private val PERMISSION_REQUEST_CODE = 1001
    private var isNetworkReady = false
    private var isRecording = false
    private var networkLoopJob: Job? = null

    // ✅ 30s 心跳
    private var heartbeatJob: Job? = null
    private val HEARTBEAT_INTERVAL_MS = 30_000L

    // ✅ 防止重复点击并发分析
    private val isAnalyzingPhoto = AtomicBoolean(false)

    // ✅ TALKING 显示缓存（流式）
    @Volatile private var lastAsrText: String = ""
    private val aiReplySb = StringBuilder()

    //------------------------------ 模式定义 ------------------------------//
    enum class Mode(val displayName: String) {
        DEFAULT("DEFAULT"),
        TALKING("TALK"),
        TRACKING("TRACK"),
        TRANSLATION("TRSL");

        fun next(): Mode = values()[(ordinal + 1) % values().size]
        fun previous(): Mode = values()[(ordinal - 1 + values().size) % values().size]
    }

    private var currentMode = Mode.DEFAULT

    //------------------------------ Camera2（后台拍照，无预览） ------------------------------//
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler

    private val takePhoto = AtomicBoolean(false)
    private var openTime = -1L

    //========================= 生命周期 =========================//
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initUIEvents()
        initIMU()

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        ensureAllPermissions()
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

        stopRecordingIfNeeded()
        if (currentMode == Mode.TRACKING) stopTrackingMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecordingIfNeeded()
        networkLoopJob?.cancel()
        stopHeartbeat()
        if (::wifiSender.isInitialized) wifiSender.close()
        if (currentMode == Mode.TRACKING) stopTrackingMode()
    }

    //========================= 权限 =========================//
    private fun ensureAllPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            initNetworkAndAudio()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            initNetworkAndAudio()
        } else {
            Toast.makeText(this, "❌ 权限被拒绝", Toast.LENGTH_LONG).show()
        }
    }

    //========================= 网络 + 录音初始化 =========================//
    private fun initNetworkAndAudio() {
        if (!::recorder.isInitialized) recorder = AudioModule()

        if (!::wifiSender.isInitialized) {
            wifiSender = WifiSender(serverIP, unifiedPort).apply { start() }
        }

        startNetworkLoop()
    }

    //========================= ✅ 30s 心跳 =========================//
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = lifecycleScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (::wifiSender.isInitialized && wifiSender.isConnected()) {
                    wifiSender.sendJson(
                        """{"type":"PING","ts":${System.currentTimeMillis()},"mode":"${currentMode.name}"}"""
                    )
                    Log.d(TAG, "❤️ PING sent")
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun startNetworkLoop() {
        networkLoopJob?.cancel()
        networkLoopJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)

                val ready = ::wifiSender.isInitialized && wifiSender.isConnected()
                if (ready != isNetworkReady) {
                    isNetworkReady = ready
                    runOnUiThread { updateNetworkStatusUI(ready) }

                    if (!ready) {
                        Log.w(TAG, "⚠️ 网络断开：停止录音/等待重连")
                        stopRecordingIfNeeded()
                        receiverStarted = false
                        isAnalyzingPhoto.set(false)
                        clearTalkingCache()
                        stopHeartbeat()
                    } else {
                        Log.i(TAG, "🌐 网络恢复：挂载 audioSink/receiver，并按模式启动录音 + 心跳")
                        audioSink = WifiAudioSender(wifiSender)
                        unifiedIn = wifiSender.inputStreamRef
                        startReceiverIfNeeded()
                        startOrStopAudioByMode()
                        startHeartbeat()
                    }
                } else {
                    if (ready) startOrStopAudioByMode() else stopRecordingIfNeeded()
                }
            }
        }
    }

    private fun updateNetworkStatusUI(isConnected: Boolean) {
        val text = if (isConnected) "Connected" else "Disconnected"
        mBindingPair.updateView {
            tvNetworkStatus.text = text
            viewStatusIndicator?.background?.let { bg ->
                (bg as? android.graphics.drawable.GradientDrawable)?.setColor(
                    ContextCompat.getColor(
                        this@AgentActivity,
                        if (isConnected) android.R.color.holo_green_light else android.R.color.holo_red_light
                    )
                )
            }
        }
    }

    //========================= TALKING 显示 =========================//
    private fun clearTalkingCache() {
        lastAsrText = ""
        aiReplySb.setLength(0)
    }

    private fun renderTalkingUi() {
        val asr = lastAsrText
        val ai = aiReplySb.toString()

        runOnUiThread {
            mBindingPair.updateView {
                audioText.setText(
                    buildString {
                        if (asr.isNotBlank()) append("🎤 你：").append(asr).append('\n')
                        if (ai.isNotBlank()) append("🤖 AI：").append(ai)
                        if (asr.isBlank() && ai.isBlank()) append("")
                    }
                )
            }
        }
    }

    //========================= 模式切换 =========================//
    private fun switchMode(newMode: Mode) {
        if (currentMode == newMode) return
        currentMode = newMode
        mBindingPair.updateView { btnMode.text = currentMode.displayName }

        if (::wifiSender.isInitialized && wifiSender.isConnected()) {
            wifiSender.sendJson("""{"type":"MODE","mode":"${newMode.name}"}""")
        }

        when (newMode) {
            Mode.TRACKING -> {
                clearTalkingCache()
                startTrackingMode()
                stopRecordingIfNeeded()
            }
            Mode.TALKING -> {
                clearTalkingCache()
                stopTrackingMode()
                startOrStopAudioByMode()
                renderTalkingUi()
            }
            Mode.TRANSLATION -> {
                clearTalkingCache()
                stopTrackingMode()
                startOrStopAudioByMode()
            }
            else -> {
                clearTalkingCache()
                stopTrackingMode()
                stopRecordingIfNeeded()
            }
        }
    }

    private fun startOrStopAudioByMode() {
        val shouldRecord = isNetworkReady && (currentMode == Mode.TALKING || currentMode == Mode.TRANSLATION)
        if (shouldRecord) startRecordingIfNeeded() else stopRecordingIfNeeded()
    }

    //========================= Audio =========================//
    private fun startRecordingIfNeeded() {
        if (isRecording) return
        if (!isNetworkReady) return
        if (!::audioSink.isInitialized) return

        val vadMode = when (currentMode) {
            Mode.TALKING, Mode.TRANSLATION -> AudioModule.VoiceDetectionMode.ENABLED
            else -> AudioModule.VoiceDetectionMode.DISABLED
        }

        recorder.start(
            context = this,
            sampleRateInHz = 16000,
            channelConfig = AudioFormat.CHANNEL_IN_MONO,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeInBytes = 2048,
            sink = audioSink,
            voiceDetectionMode = vadMode
        )
        isRecording = true
        Log.i(TAG, "🎙️ startRecording (mode=$currentMode, vad=$vadMode)")
    }

    private fun stopRecordingIfNeeded() {
        if (!isRecording) return
        try { recorder.stop() } catch (_: Exception) {}
        isRecording = false
        Log.i(TAG, "🔇 stopRecording")
    }

    //========================= 接收服务器结果（JSON） =========================//
    private fun startReceiverIfNeeded() {
        if (receiverStarted) return
        val input = unifiedIn ?: return
        receiverStarted = true
        startUnifiedReceiver(input)
    }

    private fun startUnifiedReceiver(input: InputStream) {
        Thread {
            try {
                val lenBuf = ByteArray(4)
                while (true) {
                    val readHead = input.read(lenBuf)
                    if (readHead != 4) break

                    val len = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).int
                    if (len <= 0 || len > 2_000_000) throw Exception("Invalid frame len=$len")

                    val data = ByteArray(len)
                    var totalRead = 0
                    while (totalRead < len) {
                        val count = input.read(data, totalRead, len - totalRead)
                        if (count <= 0) throw Exception("Stream closed mid-frame")
                        totalRead += count
                    }

                    val jsonStr = String(data, Charsets.UTF_8)
                    val json = JSONObject(jsonStr)

                    when (json.optString("type")) {

                        "PHOTO_VL_RESULT" -> {
                            val ts = json.optLong("ts")
                            val w = json.optInt("w", -1)
                            val h = json.optInt("h", -1)
                            val text = json.optString("text")

                            isAnalyzingPhoto.set(false)

                            runOnUiThread {
                                mBindingPair.updateView {
                                    audioText.setText("📷 图像分析结果：\n$text\n\n(ts=$ts, ${w}x$h)")
                                }
                            }
                        }

                        "TRANSLATION_PARTIAL" -> {
                            val zh = json.optString("zh")
                            val en = json.optString("en")
                            runOnUiThread {
                                mBindingPair.updateView {
                                    audioText.setText("原文：$zh\n译文：$en")
                                }
                            }
                        }

                        "CHAT_STREAM" -> {
                            val delta = json.optString("delta", "")
                            val isFinal = json.optBoolean("is_final", false)
                            val full = json.optString("full_reply", "")

                            if (delta.isNotEmpty()) aiReplySb.append(delta)
                            if (isFinal && full.isNotEmpty()) {
                                aiReplySb.setLength(0)
                                aiReplySb.append(full)
                            }

                            if (currentMode == Mode.TALKING) renderTalkingUi()
                        }

                        "RESULT" -> {
                            val text = json.optString("transcription", "")
                            if (currentMode == Mode.TALKING) {
                                lastAsrText = text
                                aiReplySb.setLength(0)
                                renderTalkingUi()
                            } else {
                                runOnUiThread {
                                    mBindingPair.updateView { audioText.setText("识别结果：$text") }
                                }
                            }
                        }

                        "CHAT" -> {
                            val user = json.optString("user_text")
                            val reply = json.optString("reply")
                            if (currentMode == Mode.TALKING) {
                                lastAsrText = user
                                aiReplySb.setLength(0)
                                aiReplySb.append(reply)
                                renderTalkingUi()
                            } else {
                                runOnUiThread {
                                    mBindingPair.updateView { audioText.setText("用户：$user\nAI：$reply\n\n") }
                                }
                            }
                        }

                        "ERROR" -> {
                            isAnalyzingPhoto.set(false)
                            Log.e("UnifiedReceiver", "❌ 服务端错误：${json.optString("msg")}")
                        }

                        else -> Log.w("UnifiedReceiver", "Unknown message: $jsonStr")
                    }
                }
            } catch (e: Exception) {
                Log.e("UnifiedReceiver", "❌ receiver error", e)
                receiverStarted = false
                isAnalyzingPhoto.set(false)
                clearTalkingCache()
                runOnUiThread {
                    updateNetworkStatusUI(false)
                    stopRecordingIfNeeded()
                }
            }
        }.apply { name = "UnifiedReceiver-$unifiedPort"; start() }
    }

    //========================= IMU（本地显示） =========================//
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
            val t = 1f - v[0] * v[0] - v[1] * v[1] - v[2] * v[2]
            arrayOf(v[0], v[1], v[2], if (t > 0f) sqrt(t) else 0f)
        }

        val euler = quaternionToEuler(qx, qy, qz, qw)
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

    //========================= Camera（TRACK：无预览，点击拍照发WiFi） =========================//
    private fun startTrackingMode() {
        mBindingPair.updateView {
            layoutCameraContainer.visibility = View.GONE
            viewCameraOverlay.visibility = View.GONE
        }

        takePhoto.set(false)
        openTime = -1L
        isAnalyzingPhoto.set(false)

        cameraThread = HandlerThread("CameraThread", Process.THREAD_PRIORITY_MORE_FAVORABLE).apply { start() }
        cameraHandler = Handler(cameraThread.looper)

        openCameraForStill()
    }

    private fun stopTrackingMode() {
        closeStillCamera()
        try { if (::cameraThread.isInitialized) cameraThread.quitSafely() } catch (_: Exception) {}
        isAnalyzingPhoto.set(false)
    }

    @SuppressLint("MissingPermission")
    private fun openCameraForStill() {
        if (cameraDevice != null) return

        val camId = cameraManager.cameraIdList.first()
        cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                setUpImageReaderAndSession(device)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                cameraDevice = null
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                cameraDevice = null
            }
        }, cameraHandler)
    }

    private fun setUpImageReaderAndSession(cam: CameraDevice) {
        imageReader?.close()

        val width = 1920
        val height = 1080

        imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)

        openTime = -1L
        imageReader!!.setOnImageAvailableListener({ reader ->
            if (openTime == -1L) {
                openTime = System.currentTimeMillis()
                return@setOnImageAvailableListener
            }
            if (System.currentTimeMillis() - openTime < 500L) return@setOnImageAvailableListener

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (takePhoto.getAndSet(false)) {
                    val jpeg = yuv420ToJpegBytes(image, quality = 90)
                    sendPhotoJpeg(jpeg, image.width, image.height)
                }
            } catch (e: Exception) {
                Log.e(TAG, "photo capture/send failed", e)
                isAnalyzingPhoto.set(false)
            } finally {
                image.close()
            }
        }, cameraHandler)

        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(imageReader!!.surface)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(5, 10))
        }

        cam.createCaptureSession(
            listOf(imageReader!!.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    cameraCaptureSession = session
                    session.setRepeatingRequest(req.build(), null, cameraHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            },
            cameraHandler
        )
    }

    private fun closeStillCamera() {
        takePhoto.set(false)
        openTime = -1L
        try { cameraCaptureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        cameraCaptureSession = null
        cameraDevice = null
        imageReader = null
    }

    private fun yuv420ToJpegBytes(image: Image, quality: Int = 90): ByteArray {
        val planes = image.planes
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer

        val ySize = yBuf.remaining()
        val uSize = uBuf.remaining()
        val vSize = vBuf.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuf.get(nv21, 0, ySize)
        vBuf.get(nv21, ySize, vSize)
        uBuf.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
        return out.toByteArray()
    }

    private fun sendPhotoJpeg(jpeg: ByteArray, w: Int, h: Int) {
        if (!::wifiSender.isInitialized || !wifiSender.isConnected()) {
            Log.w(TAG, "skip send photo: not connected")
            return
        }

        if (!isAnalyzingPhoto.compareAndSet(false, true)) {
            Log.w(TAG, "skip send photo: already analyzing")
            return
        }

        val ts = System.currentTimeMillis()
        val header =
            """{"type":"PHOTO","format":"jpeg","len":${jpeg.size},"w":$w,"h":$h,"ts":$ts}"""

        runOnUiThread {
            mBindingPair.updateView {
                audioText.setText("📷 已发送照片，正在分析中…\n(ts=$ts, ${w}x$h, len=${jpeg.size})")
            }
        }

        wifiSender.sendJson(header)
        wifiSender.sendBytes(jpeg)

        Log.i(TAG, "📷 photo sent len=${jpeg.size} ($w x $h)")
    }

    //========================= UI 交互 =========================//
    private fun initUIEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect {
                    when (it) {
                        is TempleAction.Click -> {
                            if (currentMode == Mode.TRACKING) {
                                if (isNetworkReady) {
                                    runOnUiThread {
                                        mBindingPair.updateView { audioText.setText("📷 拍照中…") }
                                    }
                                    takePhoto.set(true)
                                } else {
                                    Log.w(TAG, "click photo ignored: network not ready")
                                }
                            }
                        }
                        is TempleAction.DoubleClick -> finish()
                        is TempleAction.SlideBackward -> switchMode(currentMode.previous())
                        is TempleAction.SlideForward -> switchMode(currentMode.next())
                        else -> Unit
                    }
                }
            }
        }
    }
}

