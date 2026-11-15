package com.hako.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

class CategoryAdapter(
    private val isLocked: (Category) -> Boolean = { false },
    private val onLong: (Category) -> Unit = {},
    private val onClick: (Category) -> Unit,
) : RecyclerView.Adapter<CategoryAdapter.VH>() {
    var items: List<Category> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }
    /** Yalnızca değişen satırlar yenilenir; tümünü yenilemek kumandadaki odağı kaybettirir */
    var selectedId: String? = null
        set(v) {
            if (field == v) return
            val old = field; field = v
            items.indexOfFirst { it.id == old }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
            items.indexOfFirst { it.id == v }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        }
    var horizontal = false

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val count: TextView = v.findViewById(R.id.count)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_category, p, false)
        if (horizontal) v.layoutParams = v.layoutParams.apply { width = ViewGroup.LayoutParams.WRAP_CONTENT }
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, i: Int) {
        val c = items[i]
        h.name.text = if (isLocked(c)) "🔒 ${c.name}" else c.name
        h.count.text = if (c.count > 0) c.count.toString() else ""
        h.itemView.isSelected = c.id == selectedId
        h.itemView.setOnClickListener { onClick(c) }
        h.itemView.setOnLongClickListener { onLong(c); true }
    }

    override fun getItemCount() = items.size
}

class ItemAdapter(
    private val grid: Boolean,
    private val isFav: (Item) -> Boolean,
    private val onClick: (Item, Int) -> Unit,
    private val onLong: (Item, Int) -> Unit,
) : RecyclerView.Adapter<ItemAdapter.VH>() {
    var items: List<Item> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }
    /** Önizlemede oynayan öğe (vurgulanır) */
    var activeKey: String? = null
        set(v) {
            if (field == v) return
            val old = field; field = v
            items.indexOfFirst { it.key == old }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
            items.indexOfFirst { it.key == v }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val num: TextView = v.findViewById(R.id.num)
        val logo: ImageView = v.findViewById(R.id.logo)
        val star: TextView = v.findViewById(R.id.star)
        init {
            // İmleç gelen satırda uzun ad "..." yerine kayarak tamamen okunur
            v.setOnFocusChangeListener { _, f ->
                name.ellipsize = if (f) android.text.TextUtils.TruncateAt.MARQUEE else android.text.TextUtils.TruncateAt.END
                name.isSelected = f
            }
        }
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(
        LayoutInflater.from(p.context).inflate(if (grid) R.layout.item_poster else R.layout.item_channel, p, false)
    )

    override fun onBindViewHolder(h: VH, i: Int) {
        val it = items[i]
        h.name.text = it.name
        h.num.text = if (grid) (it.extra?.let { r -> "★ $r" } ?: "") else (i + 1).toString()
        // Sıra no sütunu listedeki en uzun numara kadar: logolar hizalı kalır, boşa yer gitmez
        if (!grid) h.num.minWidth = h.num.paint.measureText("8".repeat(items.size.toString().length)).toInt() + 1
        h.star.visibility = if (isFav(it)) View.VISIBLE else View.GONE
        h.itemView.isSelected = it.key == activeKey
        if (it.logo != null) h.logo.load(it.logo) { error(android.R.color.transparent) }
        else { h.logo.dispose(); h.logo.setImageDrawable(null) }
        h.itemView.setOnClickListener { _ -> onClick(it, h.bindingAdapterPosition) }
        h.itemView.setOnLongClickListener { _ -> onLong(it, h.bindingAdapterPosition); true }
    }

    override fun getItemCount() = items.size
}
