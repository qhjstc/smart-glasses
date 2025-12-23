package com.ffalcon.mercury.android.sdk.demo.ui.activity.test.camera

import java.nio.ByteBuffer

object H264AnnexBConverter {
    private val START_CODE_4 = byteArrayOf(0, 0, 0, 1)

    /**
     * 自动把编码器输出转换成 AnnexB：
     * - 若 input 已经是 AnnexB(00 00 00 01 / 00 00 01)，直接返回
     * - 否则按 AVCC 解析，自动尝试 nalLengthFieldBytes = 4 / 2 / 1
     * - 都失败则原样返回（便于你继续定位）
     */
    fun toAnnexBAuto(input: ByteArray): ByteArray {
        if (input.isEmpty()) return input
        if (startsWithStartCode(input)) return input

        var best: ByteArray? = null
        var bestScore = -1

        for (n in intArrayOf(4, 2, 1)) {
            val out = avccToAnnexBStrict(input, n) ?: continue
            if (out.size > bestScore) {
                bestScore = out.size
                best = out
            }
        }
        return best ?: input
    }

    /**
     * 严格 AVCC -> AnnexB。
     * 解析失败返回 null（不会返回“只有5字节”的假数据）。
     */
    private fun avccToAnnexBStrict(avcc: ByteArray, nalLengthFieldBytes: Int): ByteArray? {
        if (nalLengthFieldBytes != 1 && nalLengthFieldBytes != 2 && nalLengthFieldBytes != 4) return null

        var offset = 0
        val out = ByteArrayOutputStreamFast(avcc.size + 64)
        var nalCount = 0

        while (offset + nalLengthFieldBytes <= avcc.size) {
            var nalSize = 0
            for (i in 0 until nalLengthFieldBytes) {
                nalSize = (nalSize shl 8) or (avcc[offset + i].toInt() and 0xFF)
            }
            offset += nalLengthFieldBytes

            if (nalSize <= 0) return null
            if (offset + nalSize > avcc.size) return null

            out.write(START_CODE_4)
            out.write(avcc, offset, nalSize)
            offset += nalSize
            nalCount++
        }

        val outBytes = out.toByteArray()
        // 至少一个 NAL，且不能小到离谱（避免你现在这种“5字节假成功”）
        return if (nalCount >= 1 && outBytes.size >= 8) outBytes else null
    }

    fun wrapNalWithStartCode(nal: ByteArray): ByteArray {
        val out = ByteArray(START_CODE_4.size + nal.size)
        System.arraycopy(START_CODE_4, 0, out, 0, START_CODE_4.size)
        System.arraycopy(nal, 0, out, START_CODE_4.size, nal.size)
        return out
    }

    fun byteBufferToByteArray(buf: ByteBuffer): ByteArray {
        val dup = buf.duplicate()
        val out = ByteArray(dup.remaining())
        dup.get(out)
        return out
    }

    fun startsWithStartCode(b: ByteArray): Boolean {
        if (b.size >= 4 &&
            b[0].toInt() == 0 && b[1].toInt() == 0 && b[2].toInt() == 0 && b[3].toInt() == 1
        ) return true
        if (b.size >= 3 &&
            b[0].toInt() == 0 && b[1].toInt() == 0 && b[2].toInt() == 1
        ) return true
        return false
    }

    private class ByteArrayOutputStreamFast(initial: Int) {
        private var buf = ByteArray(maxOf(256, initial))
        private var count = 0

        fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)

        fun write(bytes: ByteArray, off: Int, len: Int) {
            ensure(count + len)
            System.arraycopy(bytes, off, buf, count, len)
            count += len
        }

        private fun ensure(min: Int) {
            if (min <= buf.size) return
            var newSize = buf.size
            while (newSize < min) newSize *= 2
            buf = buf.copyOf(newSize)
        }

        fun toByteArray(): ByteArray = buf.copyOf(count)
    }
}