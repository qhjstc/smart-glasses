package com.ffalcon.mercury.android.sdk.demo.ui.activity.test.camera

interface CameraDataSender {
    fun onVideoData(data: ByteArray)
    fun onError(e: Throwable)
}