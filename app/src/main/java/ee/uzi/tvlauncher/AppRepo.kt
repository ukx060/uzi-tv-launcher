package ee.uzi.tvlauncher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

data class AppEntry(
    val pkg: String,
    val label: String,
    val banner: Drawable?,
    val icon: Drawable?,
    val isSettings: Boolean = false
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
                seen[pkg] = AppEntry(pkg, ri.loadLabel(pm).toString(), banner, icon)
            }
        }
        val hidden = prefs.hidden
        val order = prefs.order
        val apps = seen.values
            .filter { it.pkg !in hidden }
            .sortedWith(compareBy<AppEntry>({ order.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.label.lowercase() }))
        return apps + AppEntry(SETTINGS, "Settings", null, ctx.getDrawable(R.drawable.ic_settings), true)
    }
}
