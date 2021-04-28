package coil.decode

import android.graphics.drawable.ColorDrawable
import okio.BufferedSource
import okio.blackholeSink

/**
 * A [Decoder] that exhausts the source and returns a hardcoded, empty result.
 * This will be used automatically for disk-only preload requests.
 */
internal object EmptyDecoder : Decoder {

    private val result = DecodeResult(ColorDrawable(), false)
    private val sink = blackholeSink()

    /** Hardcode this to false to prevent accidental use. */
    override fun handles(source: BufferedSource, mimeType: String?) = false

    override suspend fun decode(source: BufferedSource, options: Options): DecodeResult {
        source.use { it.readAll(sink) }
        return result
    }
}
