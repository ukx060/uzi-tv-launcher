package ee.uzi.tvlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable

/** Cheap background blur for API 24+ (no RenderEffect): draw tiny, box-blur, upscale. */
object Blur {
    private const val W = 192
    private const val H = 108

    fun fromDrawable(src: Drawable, isBanner: Boolean): Bitmap {
        val d = src.constantState?.newDrawable()?.mutate() ?: src
        val small = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(small)
        c.drawColor(Color.rgb(28, 28, 30))
        if (isBanner) d.setBounds(0, 0, W, H) else d.setBounds(0, (H - W) / 2, W, (H + W) / 2)
        d.draw(c)
        val px = IntArray(W * H)
        small.getPixels(px, 0, W, 0, 0, W, H)
        repeat(3) { boxBlur(px, 10) }
        small.setPixels(px, 0, W, 0, 0, W, H)
        return small
    }

    private fun boxBlur(p: IntArray, r: Int) {
        val tmp = IntArray(p.size)
        pass(p, tmp, W, H, r, true)
        pass(tmp, p, W, H, r, false)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = 2 * r + 1
        for (l in 0 until lines) {
            var sr = 0; var sg = 0; var sb = 0
            fun idx(i: Int): Int {
                val k = i.coerceIn(0, len - 1)
                return if (horizontal) l * w + k else k * w + l
            }
            for (i in -r..r) { val c = src[idx(i)]; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
            for (i in 0 until len) {
                dst[idx(i)] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val a = src[idx(i - r)]; val b = src[idx(i + r + 1)]
                sr += ((b shr 16) and 255) - ((a shr 16) and 255)
                sg += ((b shr 8) and 255) - ((a shr 8) and 255)
                sb += (b and 255) - (a and 255)
            }
        }
    }
}
