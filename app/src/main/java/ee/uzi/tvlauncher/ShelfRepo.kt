package ee.uzi.tvlauncher

import android.content.Context
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri

data class ShelfItem(
    val pkg: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val intentUri: String?,
    val upNext: Boolean,
    val sortKey: Long
)

/**
 * Reads other apps' "Continue watching" (Watch Next) and recommendation (preview) rows
 * from the system TvProvider. Needs READ_TV_LISTINGS; only rows marked searchable are
 * visible to non-system launchers, and the query must not carry a selection.
 */
object ShelfRepo {

    fun load(ctx: Context): Map<String, List<ShelfItem>> {
        val out = HashMap<String, MutableList<ShelfItem>>()
        query(ctx, TvContract.WatchNextPrograms.CONTENT_URI, true, out)
        query(ctx, TvContract.PreviewPrograms.CONTENT_URI, false, out)
        return out.mapValues { (_, v) ->
            v.sortedWith(compareByDescending<ShelfItem> { it.upNext }.thenByDescending { it.sortKey })
                .distinctBy { it.title }
                .take(8)
        }
    }

    private fun query(ctx: Context, uri: Uri, upNext: Boolean, out: HashMap<String, MutableList<ShelfItem>>) {
        val c: Cursor = try {
            ctx.contentResolver.query(uri, null, null, null, null)
        } catch (_: Exception) { null } ?: return
        c.use {
            val iPkg = it.getColumnIndex("package_name")
            val iTitle = it.getColumnIndex("title")
            val iEp = it.getColumnIndex("episode_title")
            val iDesc = it.getColumnIndex("short_description")
            val iPoster = it.getColumnIndex("poster_art_uri")
            val iThumb = it.getColumnIndex("thumbnail_uri")
            val iIntent = it.getColumnIndex("intent_uri")
            val iBrowsable = it.getColumnIndex("browsable")
            val iEngage = it.getColumnIndex("last_engagement_time_utc_millis")
            val iWeight = it.getColumnIndex("weight")
            while (it.moveToNext()) {
                val pkg = it.str(iPkg) ?: continue
                val title = it.str(iTitle) ?: continue
                if (!upNext && iBrowsable >= 0 && !it.isNull(iBrowsable) && it.getInt(iBrowsable) == 0) continue
                val image = it.str(iThumb) ?: it.str(iPoster)
                val sub = it.str(iEp) ?: it.str(iDesc)
                val key = if (upNext) it.long(iEngage) else it.long(iWeight)
                out.getOrPut(pkg) { mutableListOf() }
                    .add(ShelfItem(pkg, title, sub, image, it.str(iIntent), upNext, key))
            }
        }
    }

    private fun Cursor.str(i: Int): String? = if (i < 0 || isNull(i)) null else getString(i)?.takeIf { s -> s.isNotBlank() }
    private fun Cursor.long(i: Int): Long = if (i < 0 || isNull(i)) 0L else getLong(i)
}
