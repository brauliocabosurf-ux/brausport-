package com.brau.sportsplus

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.brau.sportsplus.core.playlist.Channel

class CategoryAdapter(
    private val onSelect: (String) -> Unit,
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    private var items: List<Pair<String, Int>> = emptyList()
    private var selected: String = ""

    fun submit(list: List<Pair<String, Int>>, selectedName: String) {
        items = list
        selected = selectedName
        notifyDataSetChanged()
    }

    fun select(name: String) {
        val old = items.indexOfFirst { it.first == selected }
        selected = name
        val new = items.indexOfFirst { it.first == name }
        if (old >= 0) notifyItemChanged(old)
        if (new >= 0) notifyItemChanged(new)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.catName)
        val count: TextView = view.findViewById(R.id.catCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (name, count) = items[position]
        holder.name.text = name
        holder.count.text = count.toString()
        holder.itemView.isSelected = name == selected
        holder.itemView.setOnClickListener { onSelect(name) }
        // Como en las apps de TV: al pasar por una categoría se muestran sus canales.
        // (post: evita actualizar la lista mientras RecyclerView está haciendo scroll)
        holder.itemView.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) v.post { onSelect(name) } }
    }
}

class ChannelAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Channel) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private var items: List<Channel> = emptyList()
    private var favorites: Set<String> = emptySet()

    fun submit(list: List<Channel>, favs: Set<String>) {
        items = list
        favorites = favs
        notifyDataSetChanged()
    }

    fun updateFavorites(favs: Set<String>) {
        favorites = favs
        notifyItemRangeChanged(0, items.size)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.logo)
        val initials: TextView = view.findViewById(R.id.initials)
        val name: TextView = view.findViewById(R.id.name)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val live: TextView = view.findViewById(R.id.live)
        val star: TextView = view.findViewById(R.id.star)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.name.text = ch.name
        // Partidos: se muestra el deporte en grande; canales: sus iniciales.
        if (ch.isEvent) {
            holder.initials.text = (ch.sport ?: "").uppercase()
            holder.initials.textSize = 18f
        } else {
            holder.initials.text = ch.name.filter { it.isLetterOrDigit() }.take(3).uppercase()
            holder.initials.textSize = 26f
        }
        holder.subtitle.text = ch.subtitle
        holder.subtitle.visibility = if (ch.subtitle.isEmpty()) View.GONE else View.VISIBLE
        holder.live.visibility = if (ch.isEvent) View.VISIBLE else View.GONE
        holder.star.visibility = if (ch.key in favorites) View.VISIBLE else View.GONE

        bindLogo(holder.logo, ch.logoName)

        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener { onLongClick(ch); true }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.08f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }
    }
}

/**
 * Logo LOCAL sobre fondo claro; si el canal no tiene logo se quedan las iniciales.
 * No hay descargas: un fallo aquí nunca impide usar el canal.
 */
fun bindLogo(view: ImageView, logoName: String?) {
    val res = LogoResources.forName(logoName)
    if (res == null) {
        view.setImageDrawable(null)
        view.background = null
    } else {
        view.setBackgroundResource(R.drawable.logo_plate)
        view.setImageResource(res)
    }
}

/** Lista de canales a la derecha del reproductor. */
class SideAdapter(
    private val items: List<Channel>,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<SideAdapter.VH>() {

    var playing: Int = -1
        set(value) {
            val old = field
            field = value
            if (old >= 0) notifyItemChanged(old)
            if (value >= 0) notifyItemChanged(value)
        }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.sideLogo)
        val initials: TextView = view.findViewById(R.id.sideInitials)
        val name: TextView = view.findViewById(R.id.sideName)
        val subtitle: TextView = view.findViewById(R.id.sideSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_side, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.name.text = ch.name
        holder.initials.text = if (ch.isEvent) (ch.sport ?: "").uppercase()
            else ch.name.filter { it.isLetterOrDigit() }.take(3).uppercase()
        val sub = if (position == playing) "▶ Reproduciendo" else ch.subtitle
        holder.subtitle.text = sub
        holder.subtitle.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        holder.itemView.isSelected = position == playing
        bindLogo(holder.logo, ch.logoName)
        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
    }
}
