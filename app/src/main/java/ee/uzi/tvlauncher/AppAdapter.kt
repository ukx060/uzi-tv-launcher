package ee.uzi.tvlauncher

import android.content.res.ColorStateList
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** App tiles. Focus = scale up, lift, full brightness, light rim, sheen sweep, name. Others stay dimmed. */
class AppAdapter(
    private val tileW: Int,
    private val tileH: Int,
    private val liftPx: Float,
    private val onFocus: (AppEntry, Int) -> Unit,
    private val onClick: (AppEntry) -> Unit,
    private val onLong: (AppEntry, Int) -> Unit,
    private val onUp: (Int) -> Boolean
) : RecyclerView.Adapter<AppAdapter.VH>() {

    val items = ArrayList<AppEntry>()
    private val interp = DecelerateInterpolator(1.8f)

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = items[position].pkg.hashCode().toLong()
    override fun getItemCount(): Int = items.size

    fun submit(list: List<AppEntry>) { items.clear(); items.addAll(list); notifyDataSetChanged() }

    fun move(from: Int, to: Int) {
        if (from == to) return
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tile: View = v.findViewById(R.id.tile)
        val banner: ImageView = v.findViewById(R.id.banner)
        val icon: ImageView = v.findViewById(R.id.icon)
        val shine: View = v.findViewById(R.id.shine)
        val dim: View = v.findViewById(R.id.dim)
        val rim: View = v.findViewById(R.id.rim)
        val label: TextView = v.findViewById(R.id.label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        val h = VH(v)
        h.tile.layoutParams = h.tile.layoutParams.apply { width = tileW; height = tileH }
        h.tile.clipToOutline = true
        h.tile.setOnFocusChangeListener { _, has ->
            animateFocus(h, has)
            val pos = h.bindingAdapterPosition
            if (has && pos != RecyclerView.NO_POSITION) onFocus(items[pos], pos)
        }
        h.tile.setOnClickListener {
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
        }
        h.tile.setOnLongClickListener {
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onLong(items[pos], pos)
            true
        }
        h.tile.setOnKeyListener { _, code, ev ->
            code == KeyEvent.KEYCODE_DPAD_UP && ev.action == KeyEvent.ACTION_DOWN &&
                h.bindingAdapterPosition != RecyclerView.NO_POSITION && onUp(h.bindingAdapterPosition)
        }
        return h
    }

    private fun animateFocus(h: VH, has: Boolean) {
        val t = h.tile
        val s = if (has) FOCUS_SCALE else 1f
        t.animate().scaleX(s).scaleY(s).translationZ(if (has) liftPx else 0f)
            .setDuration(180).setInterpolator(interp).start()
        h.dim.animate().alpha(if (has) 0f else DIM).setDuration(180).start()
        h.rim.animate().alpha(if (has) 1f else 0f).setDuration(180).start()
        h.label.animate().alpha(if (has) 1f else 0f).translationY(if (has) tileH * 0.075f else 0f)
            .setDuration(180).start()
        h.shine.animate().cancel()
        if (has) {
            h.shine.translationX = -tileW.toFloat()
            h.shine.alpha = 1f
            h.shine.animate().translationX(tileW.toFloat()).setStartDelay(120).setDuration(650)
                .withEndAction { h.shine.alpha = 0f }.start()
        } else h.shine.alpha = 0f
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner); h.banner.visibility = View.VISIBLE
            h.icon.setImageDrawable(null); h.icon.visibility = View.GONE
        } else {
            h.banner.setImageDrawable(null); h.banner.visibility = View.GONE
            h.icon.setImageDrawable(e.icon); h.icon.visibility = View.VISIBLE
        }
        h.tile.backgroundTintList = e.tint?.let { ColorStateList.valueOf(it) }
        h.label.text = e.label
        h.tile.contentDescription = e.label
        val f = h.tile.isFocused
        h.tile.scaleX = if (f) FOCUS_SCALE else 1f
        h.tile.scaleY = h.tile.scaleX
        h.tile.translationZ = if (f) liftPx else 0f
        h.dim.alpha = if (f) 0f else DIM
        h.rim.alpha = if (f) 1f else 0f
        h.label.alpha = if (f) 1f else 0f
        h.shine.alpha = 0f
    }

    companion object {
        const val FOCUS_SCALE = 1.15f
        const val DIM = 0.42f
    }
}
