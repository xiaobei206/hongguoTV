package com.xiaobei206.hongguotv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xiaobei206.hongguotv.model.Card
import dev.openhongguo.Episode

class TvCardAdapter(
    private val context: Context,
    private val artwork: TvArtworkLoader,
    private val onClick: (Card) -> Unit,
    private val onLongClick: (Card) -> Unit,
) : RecyclerView.Adapter<TvCardAdapter.Holder>() {
    private var items: List<Card> = emptyList()

    fun submit(next: List<Card>) {
        if (items == next) return
        items = next
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            background = cardBackground(false)
            setPadding(dp(5), dp(5), dp(5), dp(6))
            layoutParams = RecyclerView.LayoutParams(-1, dp(242)).apply {
                setMargins(dp(5), dp(6), dp(5), dp(8))
            }
        }
        val poster = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(-1, dp(174))
            setBackgroundColor(0xff243449.toInt())
        }
        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), 0)
        }
        val subtitle = TextView(context).apply {
            setTextColor(0xffa9b5c5.toInt())
            textSize = 12f
            maxLines = 1
            setPadding(dp(4), dp(2), dp(4), 0)
        }
        root.addView(poster)
        root.addView(title, LinearLayout.LayoutParams(-1, dp(38)))
        root.addView(subtitle)
        return Holder(root, poster, title, subtitle)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = items[position]
        holder.title.text = card.title
        holder.subtitle.text = card.sub.ifBlank { card.episodes.takeIf { it > 0 }?.let { "$it 集" }.orEmpty() }
        artwork.load(card.cover, holder.poster)
        holder.itemView.setOnClickListener { onClick(card) }
        holder.itemView.setOnLongClickListener { onLongClick(card); true }
        holder.itemView.onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
            view.background = cardBackground(focused)
            view.scaleX = if (focused) 1.035f else 1f
            view.scaleY = if (focused) 1.035f else 1f
            view.elevation = if (focused) dp(7).toFloat() else 0f
        }
        holder.itemView.contentDescription = card.title
    }

    class Holder(root: View, val poster: ImageView, val title: TextView, val subtitle: TextView) :
        RecyclerView.ViewHolder(root)

    private fun cardBackground(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(9).toFloat()
        setColor(if (focused) 0xff26364a.toInt() else 0xff151e2b.toInt())
        setStroke(dp(if (focused) 3 else 1), if (focused) 0xffffb45c.toInt() else 0xff2b394a.toInt())
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}

class TvEpisodeAdapter(private val context: Context, private val onClick: (Episode) -> Unit) :
    RecyclerView.Adapter<TvEpisodeAdapter.Holder>() {
    private var items: List<Episode> = emptyList()
    private var selected = 0

    fun submit(next: List<Episode>, current: Int) {
        items = next
        selected = current
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val label = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.WHITE)
            isFocusable = true
            isClickable = true
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = episodeBackground(false, false)
            layoutParams = RecyclerView.LayoutParams(-1, dp(54)).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
        }
        return Holder(label)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val episode = items[position]
        holder.label.text = episode.number.toString()
        holder.label.alpha = if (episode.playable) 1f else 0.45f
        holder.label.background = episodeBackground(episode.number == selected, false)
        holder.label.setOnClickListener { if (episode.playable) onClick(episode) }
        holder.label.onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
            view.background = episodeBackground(episode.number == selected, focused)
        }
        holder.label.contentDescription = "第 ${episode.number} 集" + if (episode.playable) "" else "暂不可播放"
    }

    class Holder(val label: TextView) : RecyclerView.ViewHolder(label)

    private fun episodeBackground(selected: Boolean, focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(when {
            focused -> 0xff4a3826.toInt()
            selected -> 0xff34445a.toInt()
            else -> 0xff1b2736.toInt()
        })
        setStroke(dp(if (focused) 3 else if (selected) 2 else 1),
            if (focused) 0xffffb45c.toInt() else if (selected) 0xffe79c47.toInt() else 0xff344255.toInt())
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
