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
    private var gameRotationSensor: Sensor? = null
    private var listener: ImuDataSender? = null

    private var isRunning = false
    private var lastUpdateTime = 0L
    private var updateIntervalMs = 100L  // 更新周期：100ms

    //========================= 初始化 =========================//
    fun init(context: Context, updateIntervalMs: Long = 100L) {
        this.updateIntervalMs = updateIntervalMs
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gameRotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (gameRotationSensor == null) {
            Log.e(TAG, "❌ 无法获取 TYPE_GAME_ROTATION_VECTOR 传感器")
        } else {
            Log.i(TAG, "✅ IMU 初始化成功 (interval=$updateIntervalMs ms)")
        }
    }

    fun start(listener: ImuDataSender) {
        if (isRunning) return
        this.listener = listener
        gameRotationSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            isRunning = true
        } ?: listener.onError(IllegalStateException("IMU not available"))
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
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return

        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < updateIntervalMs) return
        lastUpdateTime = now

        try {
            val v = event.values
            val qx: Float
            val qy: Float
            val qz: Float
            val qw: Float

            if (v.size >= 4) {
                qx = v[0]; qy = v[1]; qz = v[2]; qw = v[3]
            } else {
                val t = 1f - v[0]*v[0]-v[1]*v[1]-v[2]*v[2]
                qw = if (t > 0f) sqrt(t) else 0f
                qx = v[0]; qy = v[1]; qz = v[2]
            }

            val euler = quaternionToEuler(qx, qy, qz, qw)
            listener?.onImuData(euler[2], euler[0], euler[1], now)
        } catch (e: Exception) {
            listener?.onError(e)
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