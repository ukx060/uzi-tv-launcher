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
        val clear = 0x00000000; val solid = 0xFF000000.toInt()
        val ease = intArrayOf(clear, 0x2E000000, 0x99000000.toInt(), 0xE6000000.toInt(), solid)
        val pos = floatArrayOf(0f, 0.3f, 0.6f, 0.82f, 1f)
        left = LinearGradient(0f, 0f, w * 0.55f, 0f, ease, pos, Shader.TileMode.CLAMP)
        bottom = LinearGradient(0f, h.toFloat(), 0f, h * 0.45f, ease, pos, Shader.TileMode.CLAMP)
        top = LinearGradient(0f, 0f, 0f, h * 0.22f, 0x8C000000.toInt(), solid, Shader.TileMode.CLAMP)
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
