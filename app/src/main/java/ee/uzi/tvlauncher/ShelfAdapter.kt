package ee.uzi.tvlauncher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ShelfAdapter(
    private val images: ImageLoader,
    private val w: Int,
    private val h: Int,
    private val liftPx: Float,
    private val onFocus: (ShelfItem, Int) -> Unit,
    private val onClick: (ShelfItem) -> Unit
) : RecyclerView.Adapter<ShelfAdapter.VH>() {

    val items = ArrayList<ShelfItem>()
    var active = -1
        set(v) { val old = field; field = v; if (old >= 0) notifyItemChanged(old, 1); if (v >= 0) notifyItemChanged(v, 1) }
    private val interp = DecelerateInterpolator(1.6f)

    fun submit(list: List<ShelfItem>) { items.clear(); items.addAll(list); active = -1; notifyDataSetChanged() }
    override fun getItemCount() = items.size

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tile: View = v.findViewById(R.id.stile)
        val img: ImageView = v.findViewById(R.id.simg)
        val title: TextView = v.findViewById(R.id.stitle)
        val bar: View = v.findViewById(R.id.sbar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_shelf, parent, false)
        val vh = VH(v)
        vh.tile.layoutParams = vh.tile.layoutParams.apply { width = w; height = h }
        vh.tile.clipToOutline = true
        vh.tile.setOnFocusChangeListener { t, has ->
            val s = if (has) 1.1f else 1f
            t.animate().scaleX(s).scaleY(s).translationZ(if (has) liftPx else 0f).setDuration(170).setInterpolator(interp).start()
            val p = vh.bindingAdapterPosition
            if (has && p != RecyclerView.NO_POSITION) onFocus(items[p], p)
        }
        vh.tile.setOnClickListener {
            val p = vh.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(items[p])
        }
        return vh
    }

    override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) { h.bar.alpha = if (position == active) 1f else 0f; return }
        super.onBindViewHolder(h, position, payloads)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        images.into(h.img, e.image, w)
        h.title.text = e.title
        h.tile.contentDescription = e.title
        h.bar.alpha = if (position == active) 1f else 0f
    }
}
