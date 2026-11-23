package com.xiaobei206.hongguotv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
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
        val old = items
        val updated = next.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = updated.size
            override fun areItemsTheSame(oldPosition: Int, newPosition: Int) =
                old[oldPosition].id == updated[newPosition].id
            override fun areContentsTheSame(oldPosition: Int, newPosition: Int) =
                old[oldPosition] == updated[newPosition]
        }, false)
        items = updated
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            clipChildren = false
            background = cardBackground(false)
            setPadding(dp(6), dp(5), dp(6), dp(6))
            layoutParams = RecyclerView.LayoutParams(-1, dp(310)).apply {
                setMargins(dp(4), dp(5), dp(4), dp(8))
            }
        }
        val posterFrame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(11).toFloat()
                setColor(0xff202c3b.toInt())
            }
            clipToOutline = true
        }
        val poster = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xff202c3b.toInt())
            contentDescription = "剧集海报"
        }
        posterFrame.addView(poster, FrameLayout.LayoutParams(-1, -1))
        posterFrame.addView(View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0x00000000, 0xb9000000.toInt()),
            )
        }, FrameLayout.LayoutParams(-1, -1))
        val rankBadge = TextView(context).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xff10151c.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(4), dp(9), dp(4))
            background = badgeBackground(true)
            visibility = View.GONE
        }
        posterFrame.addView(rankBadge, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(25), Gravity.TOP or Gravity.START,
        ).apply { leftMargin = dp(8); topMargin = dp(8) })
        root.addView(posterFrame, LinearLayout.LayoutParams(-1, dp(230)))
        val title = TextView(context).apply {
            setTextColor(0xfff5f7fb.toInt())
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(4), dp(3), 0)
        }
        val subtitle = TextView(context).apply {
            setTextColor(0xff92a4b8.toInt())
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(3), 0, dp(3), 0)
        }
        root.addView(title, LinearLayout.LayoutParams(-1, dp(40)))
        root.addView(subtitle, LinearLayout.LayoutParams(-1, dp(20)))
        return Holder(root, poster, title, subtitle, rankBadge)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = items[position]
        holder.title.text = card.title
        holder.subtitle.text = card.sub.ifBlank {
            if (card.episodes > 0) card.episodes.toString() + " 集" else "精彩短剧"
        }
        holder.rankBadge.visibility = if (card.rank > 0) View.VISIBLE else View.GONE
        if (card.rank > 0) {
            holder.rankBadge.text = "TOP " + card.rank.toString().padStart(2, '0')
            holder.rankBadge.setTextColor(if (card.rank <= 3) 0xff10151c.toInt() else 0xffedf3fb.toInt())
            holder.rankBadge.background = badgeBackground(card.rank <= 3)
        }
        artwork.load(card.cover, holder.poster)
        holder.itemView.setOnClickListener { onClick(card) }
        holder.itemView.setOnLongClickListener { onLongClick(card); true }
        holder.itemView.onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
            view.background = cardBackground(focused)
            view.scaleX = if (focused) 1.035f else 1f
            view.scaleY = if (focused) 1.035f else 1f
            view.elevation = if (focused) dp(8).toFloat() else 0f
            holder.title.setTextColor(if (focused) 0xffffd19b.toInt() else 0xfff5f7fb.toInt())
        }
        holder.itemView.contentDescription = card.title
    }

    override fun onViewRecycled(holder: Holder) {
        artwork.cancel(holder.poster)
        super.onViewRecycled(holder)
    }

    class Holder(
        root: View,
        val poster: ImageView,
        val title: TextView,
        val subtitle: TextView,
        val rankBadge: TextView,
    ) : RecyclerView.ViewHolder(root)

    private fun cardBackground(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(13).toFloat()
        setColor(if (focused) 0xff1e2d3e.toInt() else 0xff111a26.toInt())
        setStroke(dp(if (focused) 3 else 1),
            if (focused) 0xffffb45c.toInt() else 0xff29384a.toInt())
    }

    private fun badgeBackground(topThree: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(if (topThree) 0xffffbd68.toInt() else 0xe51a2635.toInt())
        if (!topThree) setStroke(dp(1), 0xff61748a.toInt())
    }

    private fun dp(value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}

class TvEpisodeAdapter(private val context: Context, private val onClick: (Episode) -> Unit) :
    RecyclerView.Adapter<TvEpisodeAdapter.Holder>() {
    private var items: List<Episode> = emptyList()
    private var selected = 0

    fun submit(next: List<Episode>, current: Int) {
        if (items == next && selected == current) return
        val old = items
        val oldSelected = selected
        val updated = next.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = updated.size
            override fun areItemsTheSame(oldPosition: Int, newPosition: Int) =
                old[oldPosition].number == updated[newPosition].number
            override fun areContentsTheSame(oldPosition: Int, newPosition: Int): Boolean {
                val before = old[oldPosition]
                val after = updated[newPosition]
                return before == after &&
                    (before.number == oldSelected) == (after.number == current)
            }
        }, false)
        items = updated
        selected = current
        diff.dispatchUpdatesTo(this)
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
        holder.label.contentDescription = "第 " + episode.number + " 集" + if (episode.playable) "" else "暂不可播放"
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
