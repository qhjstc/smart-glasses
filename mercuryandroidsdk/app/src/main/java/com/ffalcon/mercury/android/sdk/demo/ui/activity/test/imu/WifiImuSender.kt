package com.ffalcon.mercury.android.sdk.demo.ui.activity.test.imu

import com.ffalcon.mercury.android.sdk.demo.ui.activity.test.WifiSender
import org.json.JSONObject

/**
 * WifiImuSender.kt
 * 负责将 IMU 数据打包为 JSON，通过 WifiSender 发送
 */
class WifiImuSender(private val sender: WifiSender) : ImuDataSender {

    override fun onImuData(yaw: Float, pitch: Float, roll: Float, timestamp: Long) {
        val json = JSONObject().apply {
            put("yaw", yaw)
            put("pitch", pitch)
            put("roll", roll)
            put("ts", timestamp)
        }.toString()

        sender.sendJson(json)
    }

    override fun onError(e: Exception) {
        e.printStackTrace()
    }
}