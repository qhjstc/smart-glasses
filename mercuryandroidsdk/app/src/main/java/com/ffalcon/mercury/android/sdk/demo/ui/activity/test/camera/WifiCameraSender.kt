package com.ffalcon.mercury.android.sdk.demo.ui.activity.test.camera

import com.ffalcon.mercury.android.sdk.demo.ui.activity.test.WifiSender

class WifiCameraSender(private val sender: WifiSender) : CameraDataSender {

    override fun onVideoData(data: ByteArray) {
        // CameraModule 已经做了 4字节长度头，这里直接发，不要再加头
        sender.sendBytes(data.copyOf())
    }

    override fun onError(e: Throwable) {
        e.printStackTrace()
    }

    fun stop() {
    }
}