package ee.uzi.tvlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.ImageView

/** ImageView whose left, bottom and top edges dissolve to transparent (no box edges). */
class FadeImageView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : ImageView(ctx, attrs) {

    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var left: Shader? = null
    private var bottom: Shader? = null
    private var top: Shader? = null

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        // smoothstep-shaped alpha ramp, so there is no visible start or end of the fade
        val n = 12
        val ramp = IntArray(n + 1) { i -> val t = i / n.toFloat(); ((t * t * (3 - 2 * t)) * 255).toInt() shl 24 }
        val pos = FloatArray(n + 1) { it / n.toFloat() }
        left = LinearGradient(0f, 0f, w * 0.6f, 0f, ramp, pos, Shader.TileMode.CLAMP)
        // fully transparent over the bottom 18% (behind the app row), fading in up to 30% height
        bottom = LinearGradient(0f, h * 0.82f, 0f, h * 0.30f, ramp, pos, Shader.TileMode.CLAMP)
        top = LinearGradient(0f, 0f, 0f, h * 0.25f, 0x80000000.toInt(), 0xFF000000.toInt(), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val sc = canvas.saveLayer(0f, 0f, w, h, null)
        super.onDraw(canvas)
        for (s in arrayOf(left, bottom, top)) {
            mask.shader = s ?: continue
            canvas.drawRect(0f, 0f, w, h, mask)
        }
        canvas.restoreToCount(sc)
    }
}
