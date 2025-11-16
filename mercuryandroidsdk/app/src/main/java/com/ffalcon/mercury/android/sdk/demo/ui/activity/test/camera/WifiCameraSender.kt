package com.ffalcon.mercury.android.sdk.demo.ui.activity.test.camera

import com.ffalcon.mercury.android.sdk.demo.ui.activity.test.WifiSender

class WifiCameraSender(
    private val sender: WifiSender
) : CameraDataSender {
    override fun onVideoData(data: ByteArray) {
        sender.sendBytes(data)
    }

    override fun onError(e: Throwable) {
        e.printStackTrace()
    }
}