package com.ffalcon.mercury.android.sdk.demo.ui.activity.test.audio

import android.util.Log
import com.ffalcon.mercury.android.sdk.demo.ui.activity.test.WifiSender
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

class WifiAudioSender(private val wifiSender: WifiSender) : AudioDataSender {

    companion object { private const val TAG = "WifiAudioSender" }

    override fun onAudioData(data: ByteArray, bytesRead: Int) {
        try {
            val header = ByteBuffer.allocate(4)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(bytesRead)
                .array()
            wifiSender.sendBytes(header + data.copyOfRange(0, bytesRead))
        } catch (e: Exception) {
            Log.e(TAG, "send audio error", e)  // ✅ 不能写成具名参数
        }
    }

    override fun sendAsrEnd() {
        try {
            val json = JSONObject().apply {
                put("type", "ASR_END")
            }
            wifiSender.sendJson(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "sendAsrEnd error", e)
        }
    }

    override fun onError(e: Exception) {
        Log.e(TAG, "AudioSink error", e)
    }
    override fun onClose() {}  // 不关 socket
}