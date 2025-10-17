package com.ffalcon.mercury.android.sdk.demo.ui.activity.test

import android.util.Log
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

class WifiSender(private val host: String, private val port: Int) {
    companion object { private const val TAG = "WifiSender" }

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private val executor = Executors.newSingleThreadExecutor()

    // ✅ 对外只读访问
    val socketRef: Socket?
        get() = socket

    fun start(): Boolean {
        return try {
            socket = Socket()
            socket!!.connect(InetSocketAddress(host, port), 5000)
            outputStream = socket!!.getOutputStream()
            Log.i(TAG, "Connected to $host:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            false
        }
    }

    fun isConnected() = socket?.isConnected == true

    fun sendBytes(data: ByteArray) {
        executor.execute {
            try {
                synchronized(this) {
                    outputStream?.write(data)
                    outputStream?.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendBytes error", e)
            }
        }
    }

    fun sendJson(json: String) {
        executor.execute {
            try {
                val payload = json.toByteArray(Charsets.UTF_8)
                val header = ByteBuffer.allocate(4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(payload.size)
                    .array()
                sendBytes(header + payload)
                Log.d(TAG, "sendJson: $json")
            } catch (e: Exception) {
                Log.e(TAG, "sendJson failed", e)
            }
        }
    }

    fun close() {
        try {
            socket?.close()
            executor.shutdownNow()
            Log.i(TAG, "Socket closed")
        } catch (e: Exception) {
            Log.e(TAG, "close error", e)
        }
    }
}