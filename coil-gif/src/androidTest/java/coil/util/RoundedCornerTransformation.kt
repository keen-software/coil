package coil.transform

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build.VERSION.SDK_INT

class RoundedCornerTransformation : AnimatedTransformation {

    private val paint = Paint().apply {
        isAntiAlias = true
        color = Color.TRANSPARENT
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }
    private val path = Path().apply {
        fillType = Path.FillType.INVERSE_EVEN_ODD
    }

    override fun transform(canvas: Canvas): AnimatedTransformation.PixelFormat {
        if (SDK_INT >= 21) {
            path.addRoundRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), 20f, 20f, Path.Direction.CW)
        } else {
            path.addRoundRect(RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()), 20f, 20f, Path.Direction.CW)
        }
        canvas.drawPath(path, paint)
        return AnimatedTransformation.PixelFormat.TRANSLUCENT
    }
}
