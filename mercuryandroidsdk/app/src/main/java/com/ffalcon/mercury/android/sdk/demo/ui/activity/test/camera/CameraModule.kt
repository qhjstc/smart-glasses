package com.ffalcon.mercury.android.sdk.demo.ui.activity.test.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.ffalcon.mercury.android.sdk.demo.ui.activity.test.WifiSender
import kotlin.math.abs

/**
 * CameraModule - 负责管理摄像头采集与视频编码
 * 可独立开启、关闭，不依赖 Activity 生命周期
 */
class CameraModule(
    private val context: Context
) {

    private val TAG = "CameraModule"

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var encoder: MediaCodec
    private lateinit var inputSurface: Surface
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler

    private var chosenSize: Size = Size(1280, 720)
    private var isRunning = false

    private var videoSender: WifiSender? = null
    private val cameraLock = Any()

    /**
     * 初始化摄像头与配置
     */
    fun init(context: Context = this.context, sender: WifiSender, width: Int = 1280, height: Int = 720) {
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        videoSender = sender
        chosenSize = Size(width, height)
    }

    /**
     * 启动视频采集+编码+发送
     */
    @SuppressLint("MissingPermission")
    fun start() {
        synchronized(cameraLock) {
            if (isRunning) return
            if (videoSender?.isConnected() != true) {
                Log.w(TAG, "⚠️ 网络未连接，不启动摄像头")
                return
            }

            backgroundThread = HandlerThread("CameraBg").apply { start() }
            backgroundHandler = Handler(backgroundThread.looper)

            val cameraId = cameraManager.cameraIdList.first()
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
            if (sizes != null) {
                chosenSize = sizes.minByOrNull {
                    abs(it.width - chosenSize.width) + abs(it.height - chosenSize.height)
                } ?: sizes.first()
            }

            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            synchronized(cameraLock) {
                cameraDevice = camera
                prepareEncoder()
                startPreviewWithDummySurface()
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
            Log.e(TAG, "❌ Camera error: $error")
        }
    }

    private fun prepareEncoder() {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            chosenSize.width,
            chosenSize.height
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        Log.i(TAG, "🎬 Encoder surface created: ${chosenSize.width}x${chosenSize.height}")
    }

    private fun startPreviewWithDummySurface() {
        val cam = cameraDevice ?: return

        // 假预览 Surface，不显示UI避免部分手机“Broken pipe”
        val dummyTexture = SurfaceTexture(0).apply {
            setDefaultBufferSize(chosenSize.width, chosenSize.height)
        }
        val dummySurface = Surface(dummyTexture)

        val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(inputSurface)
            addTarget(dummySurface)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(15, 30))
        }

        cam.createCaptureSession(
            listOf(inputSurface, dummySurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    synchronized(cameraLock) {
                        captureSession = session
                        session.setRepeatingRequest(request.build(), null, backgroundHandler)
                        encoder.start()
                        startEncodingLoop()
                        isRunning = true
                        Log.i(TAG, "✅ Camera preview & encoding started (${chosenSize.width}x${chosenSize.height})")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "❌ createCaptureSession failed")
                }
            },
            backgroundHandler
        )
    }

    private fun startEncodingLoop() {
        Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            try {
                while (isRunning && videoSender?.isConnected() == true) {
                    val index = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (index >= 0) {
                        val buffer = encoder.getOutputBuffer(index) ?: continue
                        val bytes = ByteArray(bufferInfo.size)
                        buffer.get(bytes)
                        buffer.clear()
                        videoSender?.sendBytes(bytes)
                        encoder.releaseOutputBuffer(index, false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "📹 encode loop error", e)
            }
            Log.w(TAG, "📹 encode loop stopped")
        }.start()
    }

    fun stop() {
        synchronized(cameraLock) {
            if (!isRunning) return
            isRunning = false

            try { captureSession?.stopRepeating() } catch (_: Exception) {}
            try { captureSession?.close() } catch (_: Exception) {}
            captureSession = null

            try { cameraDevice?.close() } catch (_: Exception) {}
            cameraDevice = null

            try {
                encoder.stop()
                encoder.release()
            } catch (_: Exception) {}

            if (::backgroundThread.isInitialized) {
                backgroundThread.quitSafely()
                try { backgroundThread.join(300) } catch (_: Exception) {}
            }

            Log.i(TAG, "📷 Camera & encoder released")
        }
    }
}