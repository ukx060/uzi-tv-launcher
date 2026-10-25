package ee.uzi.tvlauncher

import android.graphics.Bitmap

/** Soft ambient background from artwork: tiny downscale + box blur; the view's upscale keeps it smooth. */
object Ambient {
    private const val W = 64
    private const val H = 36

    fun blur(src: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(src, W, H, true).copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(W * H)
        small.getPixels(px, 0, W, 0, 0, W, H)
        repeat(3) { pass(px, true); pass(px, false) }
        small.setPixels(px, 0, W, 0, 0, W, H)
        return small
    }

    private fun pass(p: IntArray, horizontal: Boolean) {
        val r = 3
        val out = IntArray(p.size)
        val len = if (horizontal) W else H
        val lines = if (horizontal) H else W
        for (l in 0 until lines) for (i in 0 until len) {
            var sr = 0; var sg = 0; var sb = 0; var n = 0
            for (k in -r..r) {
                val j = (i + k).coerceIn(0, len - 1)
                val c = p[if (horizontal) l * W + j else j * W + l]
                sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255; n++
            }
            out[if (horizontal) l * W + i else i * W + l] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
        }
        System.arraycopy(out, 0, p, 0, p.size)
    }
}
