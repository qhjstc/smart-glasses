package com.ffalcon.mercury.android.sdk.demo.ui.activity.test

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
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
import com.ffalcon.mercury.android.sdk.demo.databinding.ActivityPagentBinding
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

/**
 * Tracking 专用：
 * - 单端口双向通信：点击拍照 -> JPEG -> WiFi 发送
 * - 接收 PHOTO_VL_RESULT 并显示
 * - ✅ 连接到服务器后，发送当前模式：{"type":"MODE","mode":"TRACKING"}
 * - ✅ 30s 心跳保活：{"type":"PING","ts":...}
 * - ✅ SlideBackward/SlideForward 用于滚动信息窗口（scrollText）
 */
class PAgentActivity : BaseMirrorActivity<ActivityPagentBinding>() {

    companion object { private const val TAG = "PAgentActivity" }

    //------------------------------ Network ------------------------------//
    private lateinit var wifiSender: WifiSender
    private val serverIP = "192.168.8.40"
    private val unifiedPort = 50005

    private var unifiedIn: InputStream? = null
    private var receiverStarted = false

    //------------------------------ 状态 ------------------------------//
    private val PERMISSION_REQUEST_CODE = 1001
    private var isNetworkReady = false
    private var networkLoopJob: Job? = null

    // ✅ 30s 心跳
    private var heartbeatJob: Job? = null
    private val HEARTBEAT_INTERVAL_MS = 30_000L

    // 防止重复点击并发分析
    private val isAnalyzingPhoto = AtomicBoolean(false)

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

        initUIForTracking()
        initUIEvents()

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        ensureAllPermissions()
    }

    override fun onPause() {
        super.onPause()
        stopTracking()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
        networkLoopJob?.cancel()
        stopHeartbeat()
        if (::wifiSender.isInitialized) wifiSender.close()
    }

    //========================= 权限 =========================//
    private fun ensureAllPermissions() {
        val permissions = arrayOf(Manifest.permission.CAMERA)

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            initNetwork()
            startTracking()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            initNetwork()
            startTracking()
        } else {
            Toast.makeText(this, "❌ 权限被拒绝", Toast.LENGTH_LONG).show()
        }
    }

    //========================= UI（Tracking） =========================//
    private fun initUIForTracking() {
        mBindingPair.updateView {
            // Tracking 无预览：隐藏容器/overlay（沿用你原来的布局字段）
            layoutCameraContainer.visibility = View.GONE
            viewCameraOverlay.visibility = View.GONE

            btnMode.text = "TRACK"
            audioText.setText("")
        }
    }

    private fun updateNetworkStatusUI(isConnected: Boolean) {
        val text = if (isConnected) "Connected" else "Disconnected"
        mBindingPair.updateView {
            tvNetworkStatus.text = text
            viewStatusIndicator?.background?.let { bg ->
                (bg as? android.graphics.drawable.GradientDrawable)?.setColor(
                    ContextCompat.getColor(
                        this@PAgentActivity,
                        if (isConnected) android.R.color.holo_green_light else android.R.color.holo_red_light
                    )
                )
            }
        }
    }

    //========================= ✅ 连接后上报模式 =========================//
    private fun sendCurrentMode() {
        if (!::wifiSender.isInitialized || !wifiSender.isConnected()) return
        wifiSender.sendJson("""{"type":"MODE","mode":"TRACKING"}""")
        Log.i(TAG, "➡️ MODE sent: TRACKING")
    }

    //========================= ✅ 30s 心跳 =========================//
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = lifecycleScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (::wifiSender.isInitialized && wifiSender.isConnected()) {
                    wifiSender.sendJson("""{"type":"PING","ts":${System.currentTimeMillis()}}""")
                    Log.d(TAG, "❤️ PING sent")
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    //========================= Network 初始化/轮询 =========================//
    private fun initNetwork() {
        if (!::wifiSender.isInitialized) {
            wifiSender = WifiSender(serverIP, unifiedPort).apply { start() }
        }
        startNetworkLoop()
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
                        Log.w(TAG, "⚠️ 网络断开：停止等待/清理状态")
                        receiverStarted = false
                        isAnalyzingPhoto.set(false)
                        stopHeartbeat()
                    } else {
                        Log.i(TAG, "🌐 网络恢复：挂载 receiver + 上报模式 + 心跳")
                        unifiedIn = wifiSender.inputStreamRef

                        // ✅ 仅在“刚连上”的时刻发送一次
                        sendCurrentMode()

                        startReceiverIfNeeded()

                        // ✅ 连接后启动心跳
                        startHeartbeat()
                    }
                } else {
                    if (ready) startReceiverIfNeeded()
                }
            }
        }
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

                        "ERROR" -> {
                            isAnalyzingPhoto.set(false)
                            Log.e(TAG, "❌ 服务端错误：${json.optString("msg")}")
                        }

                        else -> {
                            // PONG / 其他消息也会到这里（你服务端目前不会发PONG也没关系）
                            Log.w(TAG, "Unknown message: $jsonStr")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ receiver error", e)
                receiverStarted = false
                isAnalyzingPhoto.set(false)
                runOnUiThread { updateNetworkStatusUI(false) }
            }
        }.apply { name = "UnifiedReceiver-$unifiedPort"; start() }
    }

    //========================= Tracking（Camera） =========================//
    private fun startTracking() {
        takePhoto.set(false)
        openTime = -1L
        isAnalyzingPhoto.set(false)

        cameraThread = HandlerThread("CameraThread", Process.THREAD_PRIORITY_MORE_FAVORABLE).apply { start() }
        cameraHandler = Handler(cameraThread.looper)

        openCameraForStill()
    }

    private fun stopTracking() {
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

        // 你现有协议：先发 framed JSON(header) 再发 framed bytes(jpeg)
        wifiSender.sendJson(header)
        wifiSender.sendBytes(jpeg)

        Log.i(TAG, "📷 photo sent len=${jpeg.size} ($w x $h)")
    }

    //========================= UI 交互（滑动滚动信息） =========================//
    private fun scrollInfoUp() {
        mBindingPair.updateView {
            // 这里假设你的布局里是 NestedScrollView id=scroll_text -> binding=scrollText
            val step = (scrollText.height * 0.7f).toInt().coerceAtLeast(1)
            scrollText.smoothScrollBy(0, -step)
        }
    }

    private fun scrollInfoDown() {
        mBindingPair.updateView {
            val step = (scrollText.height * 0.7f).toInt().coerceAtLeast(1)
            scrollText.smoothScrollBy(0, step)
        }
    }

    private fun initUIEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect {
                    when (it) {
                        is TempleAction.Click -> {
                            if (isNetworkReady) {
                                runOnUiThread {
                                    mBindingPair.updateView { audioText.setText("📷 拍照中…") }
                                }
                                takePhoto.set(true)
                            } else {
                                Log.w(TAG, "click photo ignored: network not ready")
                            }
                        }

                        is TempleAction.SlideBackward -> scrollInfoUp()
                        is TempleAction.SlideForward -> scrollInfoDown()

                        is TempleAction.DoubleClick -> finish()
                        else -> Unit
                    }
                }
            }
        }
    }
}