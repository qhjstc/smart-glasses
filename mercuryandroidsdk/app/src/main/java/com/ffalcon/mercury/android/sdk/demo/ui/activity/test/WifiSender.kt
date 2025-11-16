package com.ffalcon.mercury.android.sdk.demo.ui.activity.test

import android.util.Log
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

class WifiSender(
    private val host: String,
    private val port: Int
) {
    companion object {
        private const val TAG = "WifiSender"
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var outputStream: OutputStream? = null
    @Volatile private var running = false
    @Volatile private var connected = false

    private val executor = Executors.newSingleThreadExecutor()

    fun start() {
        if (running) return
        running = true

        // 🌐 在独立线程中进行连接循环
        executor.execute {
            while (running) {
                if (!connected) {
                    try {
                        val sock = Socket()
                        sock.connect(InetSocketAddress(host, port), 2000) // 2秒超时
                        socket = sock
                        outputStream = sock.getOutputStream()
                        connected = true
                        Log.i(TAG, "✅ Connected to $host:$port")
                    } catch (e: Exception) {
                        connected = false
                        Log.w(TAG, "⚠️ Connect failed: ${e.message}")
                        Thread.sleep(3000) // 3秒后重试
                    }
                } else {
                    // 连接保持时，可在此检测连接心跳或执行轻任务
                    Thread.sleep(1000)
                }
            }

            closeInternal()
        }
    }

    fun isConnected() = connected

    fun sendBytes(data: ByteArray) {
        executor.execute {
            if (!connected) return@execute
            try {
                outputStream?.let {
                    it.write(data)
                    it.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendBytes failed: ${e.message}")
                connected = false
                safeCloseSocket()
            }
        }
    }

    fun sendJson(json: String) {
        val payload = json.toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size)
            .array()
        sendBytes(header + payload)
    }

    fun close() {
        running = false
        executor.shutdownNow()
        closeInternal()
        Log.i(TAG, "Socket closed by user")
    }

    private fun closeInternal() {
        safeCloseSocket()
        connected = false
        outputStream = null
        socket = null
        Log.d(TAG, "Socket resources released")
    }

    private fun safeCloseSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {}
    }
}