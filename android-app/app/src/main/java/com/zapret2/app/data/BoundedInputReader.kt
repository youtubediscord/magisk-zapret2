package com.zapret2.app.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Converts a nonconforming zero-progress bulk read into one byte of real progress or EOF. */
internal fun InputStream.readWithProgress(buffer: ByteArray): Int {
    require(buffer.isNotEmpty()) { "buffer must not be empty" }
    val count = read(buffer)
    if (count != 0) return count
    val nextByte = read()
    if (nextByte < 0) return -1
    buffer[0] = nextByte.toByte()
    return 1
}

/** Reads at most [maxBytes] without spinning when a nonconforming stream reports zero progress. */
internal fun InputStream.readBoundedBytes(
    maxBytes: Int,
    bufferSize: Int = DEFAULT_BOUNDED_READ_BUFFER_SIZE,
): ByteArray? {
    require(maxBytes >= 0) { "maxBytes must be nonnegative" }
    require(bufferSize > 0) { "bufferSize must be positive" }

    val output = ByteArrayOutputStream(minOf(maxBytes, bufferSize))
    val buffer = ByteArray(bufferSize)
    var total = 0
    while (true) {
        val count = readWithProgress(buffer)
        when {
            count < 0 -> break
            count > maxBytes - total -> return null
            else -> {
                output.write(buffer, 0, count)
                total += count
            }
        }
    }
    return output.toByteArray()
}

private const val DEFAULT_BOUNDED_READ_BUFFER_SIZE = 8 * 1024
