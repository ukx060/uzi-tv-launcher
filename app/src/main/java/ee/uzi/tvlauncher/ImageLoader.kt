package ee.uzi.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Minimal image loader: http(s)/content/android.resource, downsampled, memory-cached. */
class ImageLoader(private val ctx: Context) {
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(uri: String, maxW: Int): Bitmap? = cache.get("$maxW|$uri")

    fun load(uri: String, maxW: Int, done: (Bitmap?) -> Unit) {
        val key = "$maxW|$uri"
        cache.get(key)?.let { done(it); return }
        pool.execute {
            val bmp = try { decode(uri, maxW) } catch (_: Throwable) { null }
            if (bmp != null) cache.put(key, bmp)
            main.post { done(bmp) }
        }
    }

    fun into(view: ImageView, uri: String?, maxW: Int) {
        view.tag = uri
        view.setImageDrawable(null)
        if (uri == null) return
        load(uri, maxW) { b -> if (view.tag == uri) view.setImageBitmap(b) }
    }

    private fun open(uri: String): InputStream? {
        val u = Uri.parse(uri)
        return when (u.scheme) {
            "http", "https" -> (URL(uri).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000; readTimeout = 8000; instanceFollowRedirects = true
            }.inputStream
            else -> ctx.contentResolver.openInputStream(u)
        }
    }

    private fun decode(uri: String, maxW: Int): Bitmap? {
        val bytes = open(uri)?.use { it.readBytes() } ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outWidth / (s * 2) >= maxW) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
    }

    fun shutdown() = pool.shutdownNow()
}
