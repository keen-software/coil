package coil3.transform

import android.graphics.Bitmap
import android.graphics.Bitmap.Config.ARGB_8888
import android.graphics.Bitmap.Config.RGBA_F16
import android.graphics.drawable.BitmapDrawable
import coil3.decode.DecodeResult
import coil3.fetch.ImageFetchResult
import coil3.request.ImageRequest
import coil3.size.Size
import coil3.request.transformations

/**
 * An interface for making transformations to an image's pixel data.
 *
 * NOTE: If [ImageFetchResult.image] or [DecodeResult.image] is not a [BitmapDrawable],
 * it will be converted to one. This will cause animated drawables to only draw the first frame of
 * their animation.
 *
 * @see ImageRequest.Builder.transformations
 */
interface Transformation {

    /**
     * The unique cache key for this transformation.
     *
     * The key is added to the image request's memory cache key and should contain any params that
     * are part of this transformation (e.g. size, scale, color, radius, etc.).
     */
    val cacheKey: String

    /**
     * Apply the transformation to [input] and return the transformed [Bitmap].
     *
     * @param input The input [Bitmap] to transform.
     *  Its config will always be [ARGB_8888] or [RGBA_F16].
     * @param size The size of the image request.
     * @return The transformed [Bitmap].
     */
    suspend fun transform(input: Bitmap, size: Size): Bitmap
}