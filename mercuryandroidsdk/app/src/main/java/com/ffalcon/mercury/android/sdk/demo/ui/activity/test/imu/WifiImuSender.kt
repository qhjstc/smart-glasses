package com.ffalcon.mercury.android.sdk.demo.ui.activity.test.imu

import android.util.Log
import com.ffalcon.mercury.android.sdk.demo.ui.activity.test.WifiSender
import org.json.JSONObject

class WifiImuSender(private val sender: WifiSender) : ImuDataSender {
    companion object { private const val TAG = "WifiImuSender" }

    override fun onImuData(
        gx: Float, gy: Float, gz: Float,
        ax: Float, ay: Float, az: Float,
        mx: Float, my: Float, mz: Float,
        timestamp: Long
    ) {
        try {
            val jsonLine = JSONObject().apply {
                put("type", "imu")
                put("gx", gx); put("gy", gy); put("gz", gz)
                put("ax", ax); put("ay", ay); put("az", az)
                put("mx", mx); put("my", my); put("mz", mz)
                put("ts", timestamp)
            }.toString() + "\n"

            sender.sendJson(jsonLine)
        } catch (e: Exception) {
            Log.e(TAG, "send imu data error", e)
        }
    }

    override fun onRotData(yaw: Float, pitch: Float, roll: Float, timestamp: Long) {
        try {
            val jsonLine = JSONObject().apply {
                put("type", "rot")
                put("yaw", yaw)
                put("pitch", pitch)
                put("roll", roll)
                put("ts", timestamp)
            }.toString() + "\n"

            sender.sendJson(jsonLine)
        } catch (e: Exception) {
            Log.e(TAG, "send rot data error", e)
        }
    }

    override fun onError(e: Exception) {
        e.printStackTrace()
    }
}