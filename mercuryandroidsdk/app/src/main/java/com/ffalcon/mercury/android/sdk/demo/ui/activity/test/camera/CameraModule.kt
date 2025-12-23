package com.ffalcon.mercury.android.sdk.demo.ui.activity.test.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class CameraModule(private val context: Context) {

    private val TAG = "CameraModule"
    private val DBG = "CameraDbg"

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null

    private var dummyReader: ImageReader? = null
    private var dummySurface: Surface? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var dummyThread: HandlerThread? = null
    private var dummyHandler: Handler? = null

    private var encodeThread: Thread? = null
    private var chosenSize: Size = Size(1280, 720)

    @Volatile private var isRunning = false
    private var sink: CameraDataSender? = null
    private val cameraLock = Any()

    private val dbgDequeueOk = AtomicLong(0L)
    private val dbgPacketsSent = AtomicLong(0L)
    private val dbgPayloadBytes = AtomicLong(0L)

    fun init(width: Int = 1280, height: Int = 720) {
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        chosenSize = Size(width, height)
    }

    @SuppressLint("MissingPermission")
    fun start(sink: CameraDataSender) {
        this.sink = sink
        synchronized(cameraLock) {
            if (isRunning) return
            resetDbg()
            startBackgroundThreads()

            try {
                val cameraId = cameraManager.cameraIdList.first()
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
                if (sizes != null) {
                    chosenSize = sizes.minByOrNull {
                        abs(it.width - chosenSize.width) + abs(it.height - chosenSize.height)
                    } ?: sizes.first()
                }

                Log.i(TAG, "start cameraId=$cameraId size=${chosenSize.width}x${chosenSize.height}")
                cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to open camera", e)
                sink.onError(e)
            }
        }
    }

    private fun resetDbg() {
        dbgDequeueOk.set(0)
        dbgPacketsSent.set(0)
        dbgPayloadBytes.set(0)
    }

    private fun startBackgroundThreads() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraControl").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
        if (dummyThread == null) {
            dummyThread = HandlerThread("DummyDrainer").apply { start() }
            dummyHandler = Handler(dummyThread!!.looper)
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            synchronized(cameraLock) {
                cameraDevice = camera
                try {
                    prepareEncoder()
                    prepareDummyReader()
                    startPreviewDualStream()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Setup error", e)
                    stop()
                }
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            synchronized(cameraLock) { stop() }
        }

        override fun onError(camera: CameraDevice, error: Int) {
            synchronized(cameraLock) {
                Log.e(TAG, "❌ Camera error: $error")
                stop()
            }
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

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        encoder = codec
        Log.i(TAG, "🎬 Encoder surface created")
    }

    private fun prepareDummyReader() {
        dummyReader = ImageReader.newInstance(
            chosenSize.width,
            chosenSize.height,
            ImageFormat.YUV_420_888,
            4
        )

        dummyReader?.setOnImageAvailableListener({ reader ->
            try {
                var image = reader.acquireLatestImage()
                while (image != null) {
                    image.close()
                    image = reader.acquireLatestImage()
                }
            } catch (_: Exception) {
            }
        }, dummyHandler)

        dummySurface = dummyReader!!.surface
        Log.i(TAG, "👻 Dummy Reader created (Threaded)")
    }

    private fun startPreviewDualStream() {
        val cam = cameraDevice ?: return
        val inSurf = inputSurface ?: return
        val dumSurf = dummySurface ?: return
        val handler = backgroundHandler ?: return

        val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(inSurf)
            addTarget(dumSurf)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(20, 30))
        }

        cam.createCaptureSession(
            listOf(inSurf, dumSurf),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    synchronized(cameraLock) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            session.setRepeatingRequest(request.build(), null, handler)
                            isRunning = true
                            encoder?.start()
                            startEncodingLoop()
                            Log.i(TAG, "✅ Camera & Encoder started")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ setRepeatingRequest failed", e)
                        }
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "❌ createCaptureSession failed")
                }
            },
            handler
        )
    }

    private fun startEncodingLoop() {
        encodeThread?.interrupt()
        encodeThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()

            var cachedSpsPpsAnnexB: ByteArray? = null

            var lastPrintMs = SystemClock.elapsedRealtime()
            var lastOk = 0L
            var lastSent = 0L
            var lastPayloadBytes = 0L
            var sampleBudget = 3

            try {
                while (isRunning && !Thread.currentThread().isInterrupted) {
                    val codec = encoder ?: break
                    val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)

                    if (index >= 0) {
                        dbgDequeueOk.incrementAndGet()
                        try {
                            val outBuf = codec.getOutputBuffer(index)
                            if (outBuf != null && bufferInfo.size > 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)

                                val avcc = ByteArray(bufferInfo.size)
                                outBuf.get(avcc)

                                val flags = bufferInfo.flags
                                val isConfig = (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                val isKey = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                                if (isConfig) {
                                    // 把 config 也尝试转成 AnnexB 缓存（部分机型这里就能拿到 SPS/PPS）
                                    val annexB = H264AnnexBConverter.toAnnexBAuto(avcc)
                                    cachedSpsPpsAnnexB = annexB
                                    if (sampleBudget > 0) {
                                        sampleBudget--
                                        Log.i(DBG, "CONFIG: avcc=${avcc.size} cachedSpsPpsAnnexB=${annexB.size} flags=$flags")
                                    }
                                } else {
                                    val annexB = H264AnnexBConverter.toAnnexBAuto(avcc)

                                    val payload =
                                        if (isKey && cachedSpsPpsAnnexB != null) cachedSpsPpsAnnexB!! + annexB
                                        else annexB

                                    val packet = makeLengthPrefixedPacket(payload)

                                    dbgPayloadBytes.addAndGet(payload.size.toLong())
                                    dbgPacketsSent.incrementAndGet()

                                    if (sampleBudget > 0) {
                                        sampleBudget--
                                        Log.i(DBG, "SAMPLE: avcc=${avcc.size} annexB=${annexB.size} payload=${payload.size} packet=${packet.size} isKey=$isKey flags=$flags")
                                        val len = ((packet[0].toInt() and 0xFF) shl 24) or
                                                ((packet[1].toInt() and 0xFF) shl 16) or
                                                ((packet[2].toInt() and 0xFF) shl 8) or
                                                (packet[3].toInt() and 0xFF)
                                        Log.i(DBG, "SAMPLE: prefixLen=$len")
                                    }

                                    sink?.onVideoData(packet)
                                }
                            }
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }

                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val fmt = codec.outputFormat
                        Log.i(TAG, "Format changed: $fmt")

                        // 兜底：从 csd-0 / csd-1 拿 SPS/PPS（很多机型更可靠）
                        val csd0 = fmt.getByteBuffer("csd-0")
                        val csd1 = fmt.getByteBuffer("csd-1")
                        if (csd0 != null && csd1 != null) {
                            val spsRaw = byteBufferToByteArraySafe(csd0)
                            val ppsRaw = byteBufferToByteArraySafe(csd1)

                            val sps = if (H264AnnexBConverter.startsWithStartCode(spsRaw)) spsRaw
                            else H264AnnexBConverter.wrapNalWithStartCode(spsRaw)

                            val pps = if (H264AnnexBConverter.startsWithStartCode(ppsRaw)) ppsRaw
                            else H264AnnexBConverter.wrapNalWithStartCode(ppsRaw)

                            cachedSpsPpsAnnexB = ByteArray(sps.size + pps.size).apply {
                                System.arraycopy(sps, 0, this, 0, sps.size)
                                System.arraycopy(pps, 0, this, sps.size, pps.size)
                            }

                            Log.i(DBG, "CSD: sps=${sps.size} pps=${pps.size} cached=${cachedSpsPpsAnnexB!!.size}")
                        }
                    }

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPrintMs >= 1000) {
                        val ok = dbgDequeueOk.get()
                        val sent = dbgPacketsSent.get()
                        val totalBytes = dbgPayloadBytes.get()

                        val deltaOk = ok - lastOk
                        val deltaSent = sent - lastSent
                        val deltaBytes = totalBytes - lastPayloadBytes
                        val deltaKb = deltaBytes / 1024

                        Log.i(DBG, "1s: Encoded=$deltaOk Sent=$deltaSent KB=$deltaKb (bytes=$deltaBytes totalKB=${totalBytes / 1024})")

                        lastOk = ok
                        lastSent = sent
                        lastPayloadBytes = totalBytes
                        lastPrintMs = now

                        sampleBudget = 3
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Loop error", e)
                sink?.onError(e)
            }
        }.apply {
            name = "EncodeLoop"
            start()
        }
    }

    private fun makeLengthPrefixedPacket(payload: ByteArray): ByteArray {
        val len = payload.size
        val packet = ByteArray(4 + len)
        packet[0] = ((len ushr 24) and 0xFF).toByte()
        packet[1] = ((len ushr 16) and 0xFF).toByte()
        packet[2] = ((len ushr 8) and 0xFF).toByte()
        packet[3] = (len and 0xFF).toByte()
        System.arraycopy(payload, 0, packet, 4, len)
        return packet
    }

    private fun byteBufferToByteArraySafe(bb: ByteBuffer): ByteArray {
        val dup = bb.duplicate()
        val out = ByteArray(dup.remaining())
        dup.get(out)
        return out
    }

    fun stop() {
        synchronized(cameraLock) {
            if (!isRunning) return
            isRunning = false

            try { encodeThread?.interrupt() } catch (_: Exception) {}
            try { encodeThread?.join(500) } catch (_: Exception) {}
            encodeThread = null

            try { captureSession?.stopRepeating() } catch (_: Exception) {}
            try { captureSession?.close() } catch (_: Exception) {}
            captureSession = null

            try { cameraDevice?.close() } catch (_: Exception) {}
            cameraDevice = null

            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            encoder = null
            inputSurface = null

            try { dummyReader?.close() } catch (_: Exception) {}
            dummyReader = null
            dummySurface = null

            try { backgroundThread?.quitSafely(); backgroundThread?.join(300) } catch (_: Exception) {}
            backgroundThread = null
            backgroundHandler = null

            try { dummyThread?.quitSafely(); dummyThread?.join(300) } catch (_: Exception) {}
            dummyThread = null
            dummyHandler = null

            Log.i(TAG, "🛑 Camera Stopped")
        }
    }
}