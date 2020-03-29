package coil.decode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build.VERSION.SDK_INT
import androidx.core.graphics.applyCanvas
import androidx.exifinterface.media.ExifInterface
import coil.bitmappool.BitmapPool
import coil.size.PixelSize
import coil.size.Size
import coil.util.normalize
import coil.util.toDrawable
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The base [Decoder] that uses [BitmapFactory] to decode a given [BufferedSource]. */
internal class BitmapFactoryDecoder(private val context: Context) : Decoder {

    companion object {
        private const val MIME_TYPE_JPEG = "image/jpeg"
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override fun handles(source: BufferedSource, mimeType: String?) = true

    override suspend fun decode(
        pool: BitmapPool,
        source: BufferedSource,
        size: Size,
        options: Options
    ): DecodeResult = BitmapFactory.Options().run {
        val safeSource = ExceptionCatchingSource(source)
        val safeBufferedSource = safeSource.buffer()

        // Read the image's dimensions.
        inJustDecodeBounds = true
        BitmapFactory.decodeStream(safeBufferedSource.peek().inputStream(), null, this)
        safeSource.exception?.let { throw it }
        inJustDecodeBounds = false

        // Read the image's EXIF data.
        val exifInterface = ExifInterface(AlwaysAvailableInputStream(safeBufferedSource.peek().inputStream()))
        val isFlipped = exifInterface.isFlipped
        val rotationDegrees = exifInterface.rotationDegrees
        val isRotated = rotationDegrees > 0
        val isSwapped = rotationDegrees == 90 || rotationDegrees == 270

        // srcWidth and srcHeight are the dimensions of the image after EXIF transformations (but before sampling).
        val srcWidth = if (isSwapped) outHeight else outWidth
        val srcHeight = if (isSwapped) outWidth else outHeight

        // Disable hardware bitmaps if we need to perform EXIF transformations.
        val safeConfig = if (isFlipped || isRotated) options.config.normalize() else options.config
        inPreferredConfig = if (allowRgb565(options.allowRgb565, safeConfig, outMimeType)) Bitmap.Config.RGB_565 else safeConfig

        if (SDK_INT >= 26 && options.colorSpace != null) {
            inPreferredColorSpace = options.colorSpace
        }

        inMutable = SDK_INT < 26 || inPreferredConfig != Bitmap.Config.HARDWARE
        inScaled = false

        when {
            outWidth <= 0 || outHeight <= 0 -> {
                // This occurs if there was an error decoding the image's size.
                inSampleSize = 1
                inBitmap = null
            }
            size !is PixelSize -> {
                // This occurs if size is OriginalSize.
                inSampleSize = 1

                if (inMutable) {
                    inBitmap = pool.getDirtyOrNull(outWidth, outHeight, inPreferredConfig)
                }
            }
            else -> {
                val (width, height) = size
                inSampleSize = DecodeUtils.calculateInSampleSize(srcWidth, srcHeight, width, height, options.scale)

                // Calculate the image's density scaling multiple.
                val rawScale = DecodeUtils.computeSizeMultiplier(
                    srcWidth = srcWidth / inSampleSize.toDouble(),
                    srcHeight = srcHeight / inSampleSize.toDouble(),
                    dstWidth = width.toDouble(),
                    dstHeight = height.toDouble(),
                    scale = options.scale
                )

                // Avoid loading the image larger than its original dimensions if allowed.
                val scale = if (options.allowInexactSize) rawScale.coerceAtMost(1.0) else rawScale

                inScaled = scale != 1.0
                if (inScaled) {
                    if (scale > 1) {
                        // Upscale
                        inDensity = (Int.MAX_VALUE / scale).roundToInt()
                        inTargetDensity = Int.MAX_VALUE
                    } else {
                        // Downscale
                        inDensity = Int.MAX_VALUE
                        inTargetDensity = (Int.MAX_VALUE * scale).roundToInt()
                    }
                }

                if (inMutable) {
                    // Allocate a slightly larger bitmap than necessary as the output bitmap's dimensions may not match the
                    // requested dimensions exactly. This is due to intricacies in Android's downsampling algorithm.
                    val sampledOutWidth = outWidth / inSampleSize.toDouble()
                    val sampledOutHeight = outHeight / inSampleSize.toDouble()
                    inBitmap = pool.getDirtyOrNull(
                        width = ceil(scale * sampledOutWidth + 0.5).toInt(),
                        height = ceil(scale * sampledOutHeight + 0.5).toInt(),
                        config = inPreferredConfig
                    )
                }
            }
        }

        // Decode the bitmap.
        val rawBitmap: Bitmap? = safeBufferedSource.use {
            BitmapFactory.decodeStream(it.inputStream(), null, this)
        }
        safeSource.exception?.let { exception ->
            rawBitmap?.let(pool::put)
            throw exception
        }

        // Apply any EXIF transformations.
        checkNotNull(rawBitmap) {
            "BitmapFactory returned a null Bitmap. Often this means BitmapFactory could not decode the image data " +
                "read from the input source (e.g. network or disk) as it's not encoded as a valid image format."
        }
        val bitmap = applyExifTransformations(pool, rawBitmap, inPreferredConfig, isFlipped, rotationDegrees)
        bitmap.density = Bitmap.DENSITY_NONE

        DecodeResult(
            drawable = bitmap.toDrawable(context),
            isSampled = inSampleSize > 1 || inScaled
        )
    }

    /** TODO: Peek the source to figure out its format (and if it has alpha) instead of relying on the MIME type. */
    private fun allowRgb565(
        allowRgb565: Boolean,
        config: Bitmap.Config,
        mimeType: String?
    ): Boolean {
        return allowRgb565 && (SDK_INT < 26 || config == Bitmap.Config.ARGB_8888) && mimeType == MIME_TYPE_JPEG
    }

    /** NOTE: This method assumes [config] is not [Bitmap.Config.HARDWARE] if the image has to be transformed. */
    private fun applyExifTransformations(
        pool: BitmapPool,
        inBitmap: Bitmap,
        config: Bitmap.Config,
        isFlipped: Boolean,
        rotationDegrees: Int
    ): Bitmap {
        // Short circuit if there are no transformations to apply.
        val isRotated = rotationDegrees > 0
        if (!isFlipped && !isRotated) {
            return inBitmap
        }

        val matrix = Matrix()
        val centerX = inBitmap.width / 2f
        val centerY = inBitmap.height / 2f
        if (isFlipped) {
            matrix.postScale(-1f, 1f, centerX, centerY)
        }
        if (isRotated) {
            matrix.postRotate(rotationDegrees.toFloat(), centerX, centerY)
        }

        val rect = RectF(0f, 0f, inBitmap.width.toFloat(), inBitmap.height.toFloat())
        matrix.mapRect(rect)
        if (rect.left != 0f || rect.top != 0f) {
            matrix.postTranslate(-rect.left, -rect.top)
        }

        val outBitmap = if (rotationDegrees == 90 || rotationDegrees == 270) {
            pool.get(inBitmap.height, inBitmap.width, config)
        } else {
            pool.get(inBitmap.width, inBitmap.height, config)
        }

        outBitmap.applyCanvas {
            drawBitmap(inBitmap, matrix, paint)
        }
        pool.put(inBitmap)
        return outBitmap
    }

    /** Prevent [BitmapFactory.decodeStream] from swallowing [Exception]s. */
    private class ExceptionCatchingSource(delegate: Source) : ForwardingSource(delegate) {

        var exception: Exception? = null
            private set

        override fun read(sink: Buffer, byteCount: Long): Long {
            try {
                return super.read(sink, byteCount)
            } catch (e: Exception) {
                exception = e
                throw e
            }
        }
    }

    /** Wrap [delegate] so that it always returns 1GB for [available]. */
    private class AlwaysAvailableInputStream(private val delegate: InputStream) : InputStream() {

        override fun read() = delegate.read()

        override fun read(b: ByteArray) = delegate.read(b)

        override fun read(b: ByteArray, off: Int, len: Int) = delegate.read(b, off, len)

        override fun skip(n: Long) = delegate.skip(n)

        override fun available() = 1024 * 1024 * 1024 // 1GB

        override fun close() = delegate.close()

        override fun mark(readlimit: Int) = delegate.mark(readlimit)

        override fun reset() = delegate.reset()

        override fun markSupported() = delegate.markSupported()
    }
}
