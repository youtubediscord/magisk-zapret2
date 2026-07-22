package com.zapret2.app.data

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedInputReaderTest {

    @Test
    fun exactLimit_isAcceptedAndOneExtraByteIsRejected() {
        val exact = byteArrayOf(1, 2, 3, 4)

        assertArrayEquals(byteArrayOf(), ByteArrayInputStream(byteArrayOf()).readBoundedBytes(0))
        assertNull(ByteArrayInputStream(byteArrayOf(1)).readBoundedBytes(0))
        assertArrayEquals(exact, ByteArrayInputStream(exact).readBoundedBytes(4, bufferSize = 2))
        assertNull(ByteArrayInputStream(exact).readBoundedBytes(3, bufferSize = 2))
    }

    @Test
    fun zeroLengthBulkRead_fallsBackToOneByteAndMakesProgress() {
        val stream = ZeroOnceInputStream(byteArrayOf(7, 8, 9))

        assertArrayEquals(byteArrayOf(7, 8, 9), stream.readBoundedBytes(3, bufferSize = 2))
    }

    @Test
    fun progressingRead_rejectsAnEmptyDestinationBuffer() {
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(byteArrayOf()).readWithProgress(byteArrayOf())
        }
    }

    @Test
    fun invalidLimitsAreRejectedBeforeReading() {
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(byteArrayOf()).readBoundedBytes(-1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(byteArrayOf()).readBoundedBytes(0, bufferSize = 0)
        }
    }

    private class ZeroOnceInputStream(
        private val bytes: ByteArray,
    ) : InputStream() {
        private var position = 0
        private var returnedZero = false

        override fun read(): Int =
            if (position >= bytes.size) -1 else bytes[position++].toInt() and 0xff

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!returnedZero) {
                returnedZero = true
                return 0
            }
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }
}
