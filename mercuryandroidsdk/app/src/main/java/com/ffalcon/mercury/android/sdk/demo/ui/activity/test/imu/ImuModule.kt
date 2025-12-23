package com.ffalcon.mercury.android.sdk.demo.ui.activity.test.imu

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

class ImuModule : SensorEventListener {

    companion object {
        private const val TAG = "ImuModule"
    }

    private var sensorManager: SensorManager? = null

    // Rotation
    private var gameRotationSensor: Sensor? = null

    // Inertial sensors
    private var gyroSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var magSensor: Sensor? = null

    private var sink: ImuDataSender? = null

    private var isRunning = false
    private var lastUpdateTime = 0L
    private var updateIntervalMs = 100L  // 更新周期：100ms

    enum class ImuMode { ROTATION_ONLY, INERTIAL_ONLY }
    private var mode: ImuMode = ImuMode.ROTATION_ONLY

    // Mode2 caches
    private val lastGyro = FloatArray(3)
    private val lastAccel = FloatArray(3)
    private val lastMag = FloatArray(3)
    private var hasGyro = false
    private var hasAccel = false
    private var hasMag = false

    // ===== 新增：WiFi发送开关（外部可控）=====
    @Volatile private var wifiSendEnabled: Boolean = true

    /** 外部调用：开启/关闭 WiFi 发送 */
    fun setWifiSendEnabled(enabled: Boolean) {
        wifiSendEnabled = enabled
        Log.i(TAG, "wifiSendEnabled = $enabled")
    }

    /** 外部可读当前状态 */
    fun isWifiSendEnabled(): Boolean = wifiSendEnabled

    //========================= 初始化 =========================//
    fun init(
        context: Context,
        updateIntervalMs: Long = 100L,
        mode: ImuMode = ImuMode.ROTATION_ONLY
    ) {
        this.updateIntervalMs = updateIntervalMs
        this.mode = mode

        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        gameRotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        Log.i(TAG, "✅ IMU 初始化成功 (interval=$updateIntervalMs ms, mode=$mode)")

        if (mode == ImuMode.ROTATION_ONLY && gameRotationSensor == null) {
            Log.e(TAG, "❌ 无法获取 TYPE_GAME_ROTATION_VECTOR 传感器")
        }
        if (mode == ImuMode.INERTIAL_ONLY) {
            if (gyroSensor == null) Log.e(TAG, "❌ 无法获取 Gyroscope")
            if (accelSensor == null) Log.e(TAG, "❌ 无法获取 Accelerometer")
            if (magSensor == null) Log.e(TAG, "❌ 无法获取 Magnetic Field")
        }
    }

    fun start(sink: ImuDataSender) {
        if (isRunning) return
        this.sink = sink

        val sm = sensorManager
        if (sm == null) {
            sink.onError(IllegalStateException("SensorManager not available"))
            return
        }

        when (mode) {
            ImuMode.ROTATION_ONLY -> {
                val s = gameRotationSensor
                if (s == null) {
                    sink.onError(IllegalStateException("Rotation sensor not available"))
                    return
                }
                sm.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST)
            }

            ImuMode.INERTIAL_ONLY -> {
                val g = gyroSensor
                val a = accelSensor
                val m = magSensor
                if (g == null || a == null || m == null) {
                    sink.onError(IllegalStateException("Inertial sensors not available"))
                    return
                }

                hasGyro = false; hasAccel = false; hasMag = false
                sm.registerListener(this, g, SensorManager.SENSOR_DELAY_FASTEST)
                sm.registerListener(this, a, SensorManager.SENSOR_DELAY_FASTEST)
                sm.registerListener(this, m, SensorManager.SENSOR_DELAY_FASTEST)
            }
        }

        isRunning = true
    }

    fun stop() {
        if (!isRunning) return
        sensorManager?.unregisterListener(this)
        isRunning = false
        Log.i(TAG, "🛑 IMU 停止采集")
    }

    //========================= 数据回调 =========================//
    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning || event == null) return

        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < updateIntervalMs) return
        lastUpdateTime = now

        try {
            when (mode) {
                ImuMode.ROTATION_ONLY -> {
                    if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return

                    val v = event.values
                    val qx: Float
                    val qy: Float
                    val qz: Float
                    val qw: Float

                    if (v.size >= 4) {
                        qx = v[0]; qy = v[1]; qz = v[2]; qw = v[3]
                    } else {
                        val t = 1f - v[0] * v[0] - v[1] * v[1] - v[2] * v[2]
                        qw = if (t > 0f) sqrt(t) else 0f
                        qx = v[0]; qy = v[1]; qz = v[2]
                    }

                    val euler = quaternionToEuler(qx, qy, qz, qw)

                    // ===== 发送前判断开关 =====
                    if (wifiSendEnabled) {
                        sink?.onRotData(euler[2], euler[0], euler[1], now)
                    }
                }

                ImuMode.INERTIAL_ONLY -> {
                    when (event.sensor.type) {
                        Sensor.TYPE_GYROSCOPE -> {
                            lastGyro[0] = event.values[0]
                            lastGyro[1] = event.values[1]
                            lastGyro[2] = event.values[2]
                            hasGyro = true
                        }

                        Sensor.TYPE_ACCELEROMETER -> {
                            lastAccel[0] = event.values[0]
                            lastAccel[1] = event.values[1]
                            lastAccel[2] = event.values[2]
                            hasAccel = true
                        }

                        Sensor.TYPE_MAGNETIC_FIELD -> {
                            lastMag[0] = event.values[0]
                            lastMag[1] = event.values[1]
                            lastMag[2] = event.values[2]
                            hasMag = true
                        }

                        else -> return
                    }

                    if (hasGyro && hasAccel && hasMag) {
                        // ===== 发送前判断开关 =====
                        if (wifiSendEnabled) {
                            sink?.onImuData(
                                lastGyro[0], lastGyro[1], lastGyro[2],
                                lastAccel[0], lastAccel[1], lastAccel[2],
                                lastMag[0], lastMag[1], lastMag[2],
                                now
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            sink?.onError(e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    //========================= 工具函数 =========================//
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
}