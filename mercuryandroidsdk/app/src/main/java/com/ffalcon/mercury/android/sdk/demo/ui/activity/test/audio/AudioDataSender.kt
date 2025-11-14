package com.ffalcon.mercury.android.sdk.demo.ui.activity.test.audio

interface AudioDataSender {
    fun onAudioData(data: ByteArray, length: Int)
    fun sendAsrEnd()
    fun onError(e: Exception)
    fun onClose()
}