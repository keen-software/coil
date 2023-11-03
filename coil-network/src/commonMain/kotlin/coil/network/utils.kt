package coil.network

import coil.disk.DiskCache
import io.ktor.http.HeadersBuilder
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.isEmpty
import io.ktor.utils.io.core.readFully
import okio.BufferedSink
import okio.Closeable

/** Parse a header string into name and value and append it. */
internal fun HeadersBuilder.append(line: String) = apply {
    val index = line.indexOf(':')
    require(index != -1) { "Unexpected header: $line" }
    append(line.substring(0, index).trim(), line.substring(index + 1))
}

internal const val HTTP_NOT_MODIFIED = 304
internal const val MIME_TYPE_TEXT_PLAIN = "text/plain"

internal fun Closeable.closeQuietly() {
    try {
        close()
    } catch (e: RuntimeException) {
        throw e
    } catch (_: Exception) {}
}

internal fun DiskCache.Editor.abortQuietly() {
    try {
        abort()
    } catch (_: Exception) {}
}

/** Write a [ByteReadChannel] to [sink] using streaming. */
internal suspend fun ByteReadChannel.readFully(sink: BufferedSink) {
    val buffer = ByteArray(OKIO_BUFFER_SIZE)

    while (!isClosedForRead) {
        val packet = readRemaining(buffer.size.toLong())
        if (packet.isEmpty) break

        val bytesRead = packet.remaining.toInt()
        packet.readFully(buffer, 0, bytesRead)
        sink.write(buffer, 0, bytesRead)
    }
}

// Okio uses 8 KB internally.
private const val OKIO_BUFFER_SIZE: Int = 8 * 1024

internal fun String.toNonNegativeInt(defaultValue: Int): Int {
    val value = toLongOrNull() ?: return defaultValue
    return when {
        value > Int.MAX_VALUE -> Int.MAX_VALUE
        value < 0 -> 0
        else -> value.toInt()
    }
}

internal expect fun assertNotOnMainThread()


