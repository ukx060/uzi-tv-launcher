package ee.uzi.tvlauncher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable

data class AppEntry(
    val pkg: String,
    val label: String,
    val banner: Drawable?,
    val icon: Drawable?,
    val isSettings: Boolean = false,
    val tint: Int? = null
)

object AppRepo {
    const val SETTINGS = "__settings__"

    private val EXCLUDE = setOf(
        "com.android.tv.settings",
        "com.android.settings",
        "com.google.android.tvlauncher",
        "com.mitv.tvhome.atv"
    )

    fun load(ctx: Context, prefs: Prefs): List<AppEntry> {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, AppEntry>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val ris = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)
            for (ri in ris) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName
                if (pkg == ctx.packageName || pkg in EXCLUDE || seen.containsKey(pkg)) continue
                val banner = if (cat == Intent.CATEGORY_LEANBACK_LAUNCHER) {
                    ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
                } else null
                val icon = if (banner == null) ri.loadIcon(pm) else null
                seen[pkg] = AppEntry(pkg, ri.loadLabel(pm).toString(), banner, icon, tint = icon?.let { tileColor(it) })
            }
        }
        val hidden = prefs.hidden
        val order = prefs.order
        val apps = seen.values
            .filter { it.pkg !in hidden }
            .sortedWith(compareBy<AppEntry>({ order.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.label.lowercase() }))
        return apps + AppEntry(SETTINGS, "Settings", null, ctx.getDrawable(R.drawable.ic_settings), true, Color.rgb(58, 58, 60))
    }

    /** Average opaque colour of an icon, darkened: gives icon-only apps a matching tile. */
    private fun tileColor(src: Drawable): Int {
        val d = src.constantState?.newDrawable()?.mutate() ?: src
        val b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 24, 24); d.draw(Canvas(b))
        var r = 0L; var g = 0L; var bl = 0L; var n = 0L
        for (y in 0 until 24) for (x in 0 until 24) {
            val c = b.getPixel(x, y)
            if (Color.alpha(c) < 200) continue
            val mx = maxOf(Color.red(c), Color.green(c), Color.blue(c))
            val mn = minOf(Color.red(c), Color.green(c), Color.blue(c))
            val w = 1 + (mx - mn) / 16 // favour saturated pixels over white/grey
            r += Color.red(c) * w; g += Color.green(c) * w; bl += Color.blue(c) * w; n += w
        }
        b.recycle()
        if (n == 0L) return Color.rgb(44, 44, 46)
        val k = 0.55f
        return Color.rgb((r / n * k).toInt(), (g / n * k).toInt(), (bl / n * k).toInt())
    }
}
