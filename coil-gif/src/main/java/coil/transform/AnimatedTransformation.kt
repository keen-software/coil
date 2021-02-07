package coil.transform

import android.graphics.Canvas
import coil.size.Size
import android.graphics.PixelFormat as AndroidPixelFormat

/**
 * An interface for applying transformation on GIFs, animated WebPs, and animated HEIFs.
 */
interface AnimatedTransformation {

    /**
     * Apply transformation on [canvas]
     *
     * Note: Do not allocate objects in this method as it will be invoked for each frame of the animation.
     *
     * @return Opacity of the result after drawing.
     * @see AndroidPixelFormat
     */
    fun transform(canvas: Canvas, size: Size): PixelFormat

    /**
     * Opacity of the result after drawing.
     */
    enum class PixelFormat(val opacity: Int) {
        UNKNOWN(AndroidPixelFormat.UNKNOWN),
        TRANSLUCENT(AndroidPixelFormat.TRANSLUCENT),
        OPAQUE(AndroidPixelFormat.OPAQUE),
        TRANSPARENT(AndroidPixelFormat.TRANSPARENT)
    }
}
