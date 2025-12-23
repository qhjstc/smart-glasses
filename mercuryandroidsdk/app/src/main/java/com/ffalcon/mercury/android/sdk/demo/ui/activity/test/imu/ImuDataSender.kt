package com.ffalcon.mercury.android.sdk.demo.ui.activity.test.imu

interface ImuDataSender {

    /** IMU 原始数据：陀螺仪(rad/s) + 加速度(m/s^2) + 磁力计(µT) */
    fun onImuData(
        gx: Float, gy: Float, gz: Float,
        ax: Float, ay: Float, az: Float,
        mx: Float, my: Float, mz: Float,
        timestamp: Long
    )

    /** 旋转欧拉角：yaw/pitch/roll (deg) */
    fun onRotData(
        yaw: Float, pitch: Float, roll: Float,
        timestamp: Long
    )

    fun onError(e: Exception)
}