package com.ffalcon.mercury.android.sdk.demo.ui.activity.test.imu

interface ImuDataSender {
    fun onImuData(yaw: Float, pitch: Float, roll: Float, timestamp: Long)
    fun onError(e: Exception)
}