package ee.uzi.tvlauncher

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("uzi", Context.MODE_PRIVATE)

    var order: List<String>
        get() = sp.getString("order", null)?.split(",")?.filter { it.isNotEmpty() } ?: DEFAULT_ORDER
        set(v) = sp.edit().putString("order", v.joinToString(",")).apply()

    var hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("hidden", HashSet(v)).apply()

    companion object {
        val DEFAULT_ORDER = listOf(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.plexapp.android",
            "ee.telia.teliatv",
            "com.crunchyroll.crunchyroid",
            "com.google.android.youtube.tvmusic"
        )
    }
}
