package com.xiaobei206.hongguotv

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodecList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.setContent
import com.xiaobei206.hongguotv.ui.TvFeedFilterChoice
import com.xiaobei206.hongguotv.ui.TvFeedFilterRow
import com.xiaobei206.hongguotv.ui.TvFeedScreen
import com.xiaobei206.hongguotv.ui.TvFeedUiState
import com.xiaobei206.hongguotv.ui.TvDetailScreen
import com.xiaobei206.hongguotv.ui.TvDetailUiState
import com.xiaobei206.hongguotv.ui.TvInfoScreen
import com.xiaobei206.hongguotv.ui.TvInfoAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.ui.PlayerView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.xiaobei206.hongguotv.model.AppModel
import com.xiaobei206.hongguotv.model.Card
import com.xiaobei206.hongguotv.model.Fav
import com.xiaobei206.hongguotv.model.Feed
import com.xiaobei206.hongguotv.model.PlayerState
import com.xiaobei206.hongguotv.model.SdkDiagnostics
import com.xiaobei206.hongguotv.model.SeriesPage
import com.xiaobei206.hongguotv.model.TimeControl
import com.xiaobei206.hongguotv.model.friendly
import com.xiaobei206.hongguotv.playback.PlaybackDiagnostics
import com.xiaobei206.hongguotv.model.metaLine
import com.xiaobei206.hongguotv.model.dayLabel
import com.xiaobei206.hongguotv.model.todayKey
import dev.openhongguo.Episode
import dev.openhongguo.RankBoard
import dev.openhongguo.RankContent
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

@androidx.media3.common.util.UnstableApi
class MainActivity : ComponentActivity() {
    private enum class Screen { HOME, RANK, NEW, SEARCH, FAVORITES, HISTORY, DETAIL, PLAYER, DEVICE, SETTINGS }

    private val tvApp get() = application as TvApplication
    private val model: AppModel? get() = tvApp.model
    private val updateManager by lazy { TvUpdateManager(this) }
    private var checkingForUpdate = false
    private var pendingInstallApk: File? = null
    private var screen by mutableStateOf(Screen.HOME)
    private var parentScreen = Screen.HOME
    private var searchReturnScreen = Screen.HOME
    private var currentFeed: Feed? = null
    private var currentSeries: SeriesPage? = null
    private var detailRevealPendingId: String? = null
    private var headerTitle = "推荐"
    private var renderedScreen: Screen? = null
    private var composeFeed by mutableStateOf<Feed?>(null)
    private var composeFeedUi by mutableStateOf<TvFeedUiState?>(null)
    private var composeDetailUi by mutableStateOf<TvDetailUiState?>(null)

    private lateinit var shell: LinearLayout
    private lateinit var rail: LinearLayout
    private lateinit var contentHost: FrameLayout
    private lateinit var playerLayer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var playerControls: LinearLayout
    private lateinit var playerTitle: TextView
    private lateinit var playerMessage: TextView
    private lateinit var playerSeek: SeekBar
    private lateinit var playButton: TextView
    private lateinit var speedButton: TextView
    private lateinit var qualityButton: TextView
    private lateinit var retryButton: TextView
    private lateinit var diagnosticButton: TextView
    private lateinit var progressText: TextView
    private lateinit var playerFocusButtons: List<TextView>
    private var episodeDialog: AlertDialog? = null

    private val handler = Handler(Looper.getMainLooper())
    private var progressTask: Runnable? = null
    private val hideControls = Runnable {
        if (screen == Screen.PLAYER && model?.player?.paused == false) {
            if (playerControls.findFocus() != null) playerView.requestFocus()
            playerControls.visibility = View.GONE
        }
    }

    private data class FilterChoice(
        val key: String,
        val label: String,
        val selected: Boolean,
        val onClick: () -> Unit,
    )

    private data class UiSnapshot(
        val cards: List<Card>,
        val loading: Boolean,
        val feedError: String,
        val series: dev.openhongguo.Series?,
        val episodes: List<Episode>,
        val detailLoading: Boolean,
        val detailError: String,
        val playerState: PlayerState,
        val playerNumber: Int,
        val playerSpeed: Float,
        val playerQuality: Int,
        val playerQualities: List<Int>,
        val favoritesCount: Int,
        val historyCount: Int,
        val rankContents: List<RankContent>,
        val rankBoards: List<RankBoard>,
        val rankContent: String,
        val rankBoard: String,
        val currentRankContent: String,
        val newDates: List<String>,
        val newDate: String,
        val detailPart: Int = 0,
        val detailParts: List<String> = emptyList(),
        val currentEpisodeNumber: Int = 0,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        buildShell()
        val m = model
        if (m == null) {
            showStartupError(tvApp.startupError)
            return
        }
        enterFeed(Screen.HOME)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                snapshotFlow { makeSnapshot() }.distinctUntilChanged().collect { renderSnapshot(it) }
            }
        }
        if (m.lastSession.player != null) {
            parentScreen = Screen.HOME
            screen = Screen.PLAYER
            m.reopenPlayer()
            showPlayer()
        }
        handler.postDelayed({
            if (!isFinishing && screen != Screen.PLAYER) checkForUpdate(manual = false)
        }, 5_000L)
    }

    private fun buildShell() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xff0b1018.toInt()) }
        shell = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(18), dp(10), dp(12))
            setBackgroundColor(0xff101925.toInt())
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(2), dp(7), dp(2))
            addView(text("红果 TV", 23f, 0xff72dba0.toInt(), true), LinearLayout.LayoutParams(-1, dp(34)))
            addView(text("大屏短剧", 12f, 0xff9bacbe.toInt()), LinearLayout.LayoutParams(-1, dp(22)))
        }
        rail.addView(brand, LinearLayout.LayoutParams(-1, dp(68)).apply { bottomMargin = dp(8) })
        addNav("首页", Screen.HOME)
        addNav("排行榜", Screen.RANK)
        addNav("新剧上线", Screen.NEW)
        addNav("搜索", Screen.SEARCH) { openSearchDialog() }
        addNav("我的收藏", Screen.FAVORITES)
        addNav("观看历史", Screen.HISTORY)
        rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(View(this).apply { setBackgroundColor(0xff273648.toInt()) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(7); bottomMargin = dp(7) })
        addNav("设备信息", Screen.DEVICE)
        addNav("设置", Screen.SETTINGS)
        contentHost = FrameLayout(this).apply { setPadding(dp(14), dp(8), dp(22), dp(12)) }
        shell.addView(rail, LinearLayout.LayoutParams(dp(174), -1))
        shell.addView(contentHost, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))
        playerLayer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }
        root.addView(playerLayer, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        buildPlayerLayer()
    }

    private fun addNav(label: String, target: Screen, action: (() -> Unit)? = null) {
        val button = navButton(label) { if (action != null) action() else navigate(target) }
        button.tag = target
        rail.addView(button, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(3); bottomMargin = dp(3); leftMargin = dp(2); rightMargin = dp(2)
        })
    }

    private fun navigate(target: Screen) {
        when (target) {
            Screen.HOME, Screen.RANK, Screen.NEW -> enterFeed(target)
            Screen.FAVORITES, Screen.HISTORY, Screen.DEVICE, Screen.SETTINGS -> {
                parentScreen = Screen.HOME
                currentFeed = null
                headerTitle = when (target) {
                    Screen.FAVORITES -> "我的收藏"
                    Screen.HISTORY -> "观看历史"
                    Screen.DEVICE -> "设备兼容性诊断"
                    Screen.SETTINGS -> "设置"
                    else -> headerTitle
                }
                screen = target
                showShell()
            }
            Screen.SEARCH -> openSearchDialog()
            else -> Unit
        }
    }

    private fun enterFeed(target: Screen) {
        val m = model ?: return
        parentScreen = target
        currentSeries = null
        currentFeed = when (target) {
            Screen.HOME -> m.home
            Screen.RANK -> m.rankFeed
            Screen.NEW -> m.newFeed
            else -> null
        }
        headerTitle = when (target) {
            Screen.HOME -> "推荐"
            Screen.RANK -> "排行榜"
            Screen.NEW -> "新剧上线"
            else -> "搜索结果"
        }
        screen = target
        currentFeed?.let { if (!it.started || it.stale) it.loadMore() }
        showShell()
    }

    private fun showShell() {
        updateNavigationSelection()
        shell.visibility = View.VISIBLE
        playerLayer.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (renderedScreen != screen) {
            renderedScreen = screen
            when (screen) {
                Screen.HOME, Screen.RANK, Screen.NEW, Screen.SEARCH, Screen.FAVORITES, Screen.HISTORY -> buildFeedContent()
                Screen.DETAIL -> buildDetailContent()
                Screen.DEVICE -> buildInfoContent(false)
                Screen.SETTINGS -> buildInfoContent(true)
                Screen.PLAYER -> showPlayer()
            }
        }
        renderSnapshot(makeSnapshot())
    }

    private fun buildFeedContent() {
        contentHost.removeAllViews()
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        }
        contentHost.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.setContent {
            val state = composeFeedUi
            if (state != null) {
                TvFeedScreen(
                    state = state,
                    feed = composeFeed,
                    artwork = tvApp.artwork,
                    onCardSelected = ::onCardSelected,
                    onToggleFavorite = { card ->
                        val added = model?.favorites?.toggle(Fav.of(card)) == true
                        toast(if (added) "已收藏：${card.title}" else "已取消收藏：${card.title}")
                    },
                    onSearch = ::openSearchDialog,
                    onRefresh = ::refreshCurrentFeed,
                    onFilterSelected = ::onComposeFeedFilterSelected,
                )
            }
        }
    }

    private fun renderFeed(snapshot: UiSnapshot) {
        composeFeed = currentFeed
        val m = model
        val today = todayKey()
        val filterRows = when (screen) {
            Screen.RANK -> buildList {
                if (snapshot.rankContents.isNotEmpty()) {
                    add(TvFeedFilterRow("分类", snapshot.rankContents.map { content ->
                        TvFeedFilterChoice(
                            key = "rank-content:${content.id}",
                            label = content.name.ifBlank { content.id },
                            selected = snapshot.currentRankContent == content.id,
                        )
                    }))
                    if (snapshot.rankBoards.isNotEmpty()) {
                        val choices = ArrayList<TvFeedFilterChoice>()
                        choices += TvFeedFilterChoice(
                            key = "rank-board:default",
                            label = "默认",
                            selected = snapshot.rankBoard.isEmpty() && snapshot.rankBoards.none { it.selected },
                        )
                        snapshot.rankBoards.forEach { board ->
                            choices += TvFeedFilterChoice(
                                key = "rank-board:${board.id}",
                                label = board.name.ifBlank { board.id },
                                selected = board.id == snapshot.rankBoard ||
                                    (snapshot.rankBoard.isEmpty() && board.selected),
                            )
                        }
                        add(TvFeedFilterRow("榜单", choices))
                    }
                }
            }
            Screen.NEW -> listOf(
                TvFeedFilterRow(
                    "上线日期",
                    (listOf(today) + snapshot.newDates).distinct().map { date ->
                        TvFeedFilterChoice(
                            key = "new-date:$date",
                            label = dayLabel(date, today),
                            selected = m?.dayIsOn(date) == true,
                        )
                    },
                ),
            )
            else -> emptyList()
        }
        val emptyMessage = when (screen) {
            Screen.FAVORITES -> "还没有收藏\n在剧集详情中保存喜欢的作品"
            Screen.HISTORY -> "还没有观看记录\n开始播放后会自动保存在这里"
            else -> "暂时没有内容；确认电视已联网后，可以按刷新重试"
        }
        val status = when {
            snapshot.cards.isNotEmpty() && snapshot.loading && currentFeed?.wantsMore == true ->
                "已加载 ${snapshot.cards.size} 部 · 正在后台补载更多剧集"
            snapshot.cards.isNotEmpty() && snapshot.feedError.isNotBlank() ->
                "已加载 ${snapshot.cards.size} 部 · 本批续载失败，可刷新重试"
            snapshot.cards.isNotEmpty() -> "已加载 ${snapshot.cards.size} 部 · 长按海报收藏"
            snapshot.loading -> "正在连接红果并获取剧集"
            else -> "电视遥控器可直接浏览海报；长按确认键可收藏"
        }
        val feedKey = buildString {
            append(screen.name).append('|').append(headerTitle)
            when (screen) {
                Screen.RANK -> append('|').append(snapshot.currentRankContent).append('|').append(snapshot.rankBoard)
                Screen.NEW -> append('|').append(snapshot.newDate)
                else -> Unit
            }
        }
        composeFeedUi = TvFeedUiState(
            screenKey = screen.name,
            feedKey = feedKey,
            title = headerTitle,
            status = status,
            cards = snapshot.cards,
            loading = snapshot.loading,
            error = snapshot.feedError,
            emptyMessage = emptyMessage,
            filterRows = filterRows,
        )
    }

    private fun onComposeFeedFilterSelected(key: String) {
        val m = model ?: return
        when {
            key.startsWith("rank-content:") -> {
                val content = key.removePrefix("rank-content:")
                if (m.rankContent != content) {
                    m.rankContent = content
                    m.rankBoard = ""
                    enterFeed(Screen.RANK)
                }
            }
            key == "rank-board:default" -> {
                if (m.rankBoard.isNotEmpty()) {
                    m.rankBoard = ""
                    enterFeed(Screen.RANK)
                }
            }
            key.startsWith("rank-board:") -> {
                val board = key.removePrefix("rank-board:")
                if (m.rankBoard != board) {
                    m.rankBoard = board
                    enterFeed(Screen.RANK)
                }
            }
            key.startsWith("new-date:") -> {
                val date = key.removePrefix("new-date:")
                if (!m.dayIsOn(date)) {
                    m.chooseDay(date)
                    enterFeed(Screen.NEW)
                }
            }
        }
    }

    private fun refreshCurrentFeed() {
        val feed = currentFeed ?: return
        feed.retry()
    }

    private fun openSearchDialog() {
        val edit = EditText(this).apply {
            setSingleLine(true)
            textSize = 20f
            setTextColor(Color.WHITE)
            hint = "输入剧名（可使用电视语音输入）"
            setHintTextColor(0xff9ca9b8.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("搜索红果短剧")
            .setView(edit)
            .setPositiveButton("搜索") { _, _ ->
                val query = edit.text.toString().trim()
                if (query.isNotEmpty()) doSearch(query)
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            edit.requestFocus()
            edit.post {
                val input = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                input.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.show()
    }

    private fun doSearch(query: String) {
        val m = model ?: return
        if (screen != Screen.SEARCH) searchReturnScreen = screen
        currentFeed = m.searchFeed(query)
        headerTitle = "搜索：$query"
        screen = Screen.SEARCH
        currentFeed?.loadMore()
        showShell()
    }

    private fun onCardSelected(card: Card) {
        if (screen == Screen.HISTORY) {
            val m = model ?: return
            parentScreen = Screen.HISTORY
            m.playSeason(card.id)
            screen = Screen.PLAYER
            showPlayer()
            return
        }
        openDetail(card)
    }

    private fun openDetail(card: Card) {
        val m = model ?: return
        parentScreen = screen
        currentSeries = m.seriesPage(card.id, restartIfLoading = true)
        detailRevealPendingId = card.id
        screen = Screen.DETAIL
        showShell()
    }

    private fun buildDetailContent() {
        contentHost.removeAllViews()
        composeFeed = null
        composeFeedUi = null
        composeDetailUi = null
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        }
        contentHost.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.setContent {
            val detail = composeDetailUi
            if (detail != null) {
                TvDetailScreen(
                    state = detail,
                    artwork = tvApp.artwork,
                    onPlay = {
                        val page = currentSeries
                        if (page != null) {
                            val resume = model?.resumePoint(page)
                            if (resume != null) startPlayer(resume.seriesID, resume.number, resume.start)
                            else startPlayer(page.id, 1, 0.0)
                        }
                    },
                    onFavorite = {
                        val page = currentSeries
                        val series = page?.series
                        if (page != null && series != null) {
                            val added = model?.favorites?.toggle(Fav.of(Card.of(series))) == true
                            toast(if (added) "已收藏" else "已取消收藏")
                        }
                    },
                    onEpisodeSelected = { episode ->
                        val page = currentSeries
                        if (page != null) {
                            if (episode.playable) startPlayer(page.id, episode.number, 0.0)
                            else toast("这一集暂不可播放")
                        }
                    },
                    onPartSelected = { part -> currentSeries?.part = part },
                    onRetry = { currentSeries?.let { model?.loadSeries(it, force = true) } },
                )
            }
        }
    }

    private fun renderDetail(snapshot: UiSnapshot) {
        val page = currentSeries ?: return
        if (detailRevealPendingId == page.id && snapshot.series != null && snapshot.episodes.isNotEmpty()) {
            model?.resumePoint(page)?.let { page.reveal(it.number) }
            detailRevealPendingId = null
        }
        val resume = model?.resumePoint(page)
        composeDetailUi = TvDetailUiState(
            seriesId = page.id,
            series = snapshot.series,
            meta = snapshot.series?.let(::metaLine).orEmpty(),
            resumeLabel = resume?.label ?: "播放第 1 集",
            episodes = snapshot.episodes,
            partLabels = snapshot.detailParts,
            selectedPart = snapshot.detailPart.coerceIn(0, (snapshot.detailParts.size - 1).coerceAtLeast(0)),
            currentEpisodeNumber = snapshot.currentEpisodeNumber,
            favorite = model?.favorites?.has(page.id) == true,
            loading = snapshot.detailLoading,
            error = snapshot.detailError,
        )
    }

    private fun startPlayer(seriesId: String, episode: Int, start: Double) {
        val m = model ?: return
        parentScreen = screen
        m.player.play(seriesId, episode, start)
        screen = Screen.PLAYER
        showPlayer()
    }

    private fun buildPlayerLayer() {
        playerLayer.removeAllViews()
        playerView = PlayerView(this).apply {
            id = View.generateViewId()
            player = tvApp.video.exo
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { togglePlayerPause() }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (playerControls.visibility == View.VISIBLE) false
                        else { model?.player?.skip(-10.0); revealPlayerControls(); true }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (playerControls.visibility == View.VISIBLE) false
                        else { model?.player?.skip(10.0); revealPlayerControls(); true }
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (playerControls.visibility == View.VISIBLE) false
                        else { model?.player?.step(-1); revealPlayerControls(); true }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (playerControls.visibility == View.VISIBLE) false
                        else { model?.player?.step(1); revealPlayerControls(); true }
                    }
                    KeyEvent.KEYCODE_MENU -> { revealPlayerControls(focusControls = true); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        togglePlayerPause(focusControls = true)
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { model?.player?.video?.play(); revealPlayerControls(); true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { model?.player?.video?.pause(); revealPlayerControls(); true }
                    else -> false
                }
            }
        }
        playerLayer.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xdd07101b.toInt(), 0x0007101b))
        }
        playerTitle = text("红果 TV", 20f, Color.WHITE, true)
        top.addView(playerTitle, LinearLayout.LayoutParams(-1, dp(46)))
        playerLayer.addView(top, FrameLayout.LayoutParams(-1, dp(66), Gravity.TOP))

        playerControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xee07101b.toInt(), 0x0007101b))
        }
        playerMessage = text("", 15f, 0xffffc77f.toInt())
        playerControls.addView(playerMessage, LinearLayout.LayoutParams(-1, dp(28)))
        playerSeek = SeekBar(this).apply {
            id = View.generateViewId()
            isFocusable = true
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(0xffffb45c.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xffffb45c.toInt())
        }
        playerControls.addView(playerSeek, LinearLayout.LayoutParams(-1, dp(34)))
        progressText = text("00:00 / 00:00", 13f, 0xffd1d9e2.toInt())
        playerControls.addView(progressText)
        playerSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
            override fun onStartTrackingTouch(seekBar: SeekBar?) = revealPlayerControls()
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val player = model?.player ?: return
                val duration = player.video.duration
                if (duration.isFinite()) player.video.seek(duration * ((seekBar?.progress ?: 0) / 1000.0))
            }
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        playButton = navButton("播放/暂停") { togglePlayerPause() }
        val previousEpisodeButton = navButton("上一集") { model?.player?.step(-1) }
        val nextEpisodeButton = navButton("下一集") { model?.player?.step(1) }
        val episodeSelectButton = navButton("选集") { openEpisodeDialog() }
        speedButton = navButton("倍速 1×") { chooseNextSpeed() }
        qualityButton = navButton("画质") { chooseNextQuality() }
        row.addView(playButton, LinearLayout.LayoutParams(dp(126), dp(46)).apply { rightMargin = dp(7) })
        row.addView(previousEpisodeButton, LinearLayout.LayoutParams(dp(102), dp(46)).apply { rightMargin = dp(7) })
        row.addView(nextEpisodeButton, LinearLayout.LayoutParams(dp(102), dp(46)).apply { rightMargin = dp(7) })
        row.addView(episodeSelectButton, LinearLayout.LayoutParams(dp(90), dp(46)).apply { rightMargin = dp(7) })
        row.addView(speedButton, LinearLayout.LayoutParams(dp(108), dp(46)).apply { rightMargin = dp(7) })
        row.addView(qualityButton, LinearLayout.LayoutParams(dp(90), dp(46)))
        val focusButtons = listOf(playButton, previousEpisodeButton, nextEpisodeButton, episodeSelectButton, speedButton, qualityButton)
        playerFocusButtons = focusButtons
        playerView.nextFocusUpId = playButton.id
        playerView.nextFocusDownId = playerSeek.id
        playerView.nextFocusLeftId = playButton.id
        playerView.nextFocusRightId = playButton.id
        playerSeek.nextFocusUpId = playerView.id
        playerSeek.nextFocusDownId = playButton.id
        playButton.nextFocusUpId = playerSeek.id
        focusButtons.forEachIndexed { index, button ->
            button.nextFocusLeftId = focusButtons[(index - 1 + focusButtons.size) % focusButtons.size].id
            button.nextFocusRightId = focusButtons[(index + 1) % focusButtons.size].id
            button.nextFocusUpId = playerSeek.id
            button.nextFocusDownId = button.id
        }
        playerControls.addView(row)
        retryButton = navButton("重试播放") { model?.player?.retry() }
        retryButton.visibility = View.GONE
        retryButton.nextFocusUpId = playButton.id
        retryButton.nextFocusDownId = retryButton.id
        playerControls.addView(retryButton, LinearLayout.LayoutParams(dp(140), dp(42)).apply { topMargin = dp(6) })
        diagnosticButton = navButton("发送诊断到手机") { sendPlaybackDiagnosticsToPhones() }
        diagnosticButton.visibility = View.GONE
        diagnosticButton.nextFocusUpId = retryButton.id
        diagnosticButton.nextFocusDownId = diagnosticButton.id
        playerControls.addView(diagnosticButton, LinearLayout.LayoutParams(dp(190), dp(42)).apply { topMargin = dp(6) })
        playerLayer.addView(playerControls, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        bindPlayerMediaKeys(playerControls)
    }

    /**
     * Media transport keys should work when a player control owns focus too.
     * Keep this local to the player instead of overriding the restricted
     * ComponentActivity.dispatchKeyEvent API.
     */
    private fun bindPlayerMediaKeys(view: View) {
        if (view.isFocusable || view.isClickable) {
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        togglePlayerPause(focusControls = true)
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        model?.player?.video?.play()
                        revealPlayerControls()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        model?.player?.video?.pause()
                        revealPlayerControls()
                        true
                    }
                    else -> false
                }
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) bindPlayerMediaKeys(view.getChildAt(index))
        }
    }

    private fun revealPlayerControls(focusControls: Boolean = false) {
        playerControls.visibility = View.VISIBLE
        handler.removeCallbacks(hideControls)
        if (focusControls) playButton.requestFocus()
        if (model?.player?.paused == false) handler.postDelayed(hideControls, 6_000)
    }

    private fun togglePlayerPause(focusControls: Boolean = false) {
        model?.player?.togglePause()
        revealPlayerControls(focusControls)
    }

    private fun showPlayer() {
        renderedScreen = Screen.PLAYER
        // Stop any hidden feed-prefetch coroutine before handing the screen to the player.
        composeFeed = null
        composeFeedUi = null
        composeDetailUi = null
        shell.visibility = View.GONE
        playerLayer.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playerControls.visibility = View.GONE
        renderPlayerState()
        playerView.requestFocus()
        progressTask?.let(handler::removeCallbacks)
        val task = object : Runnable {
            override fun run() {
                if (screen != Screen.PLAYER) return
                updateProgress()
                handler.postDelayed(this, 700)
            }
        }
        progressTask = task
        handler.post(task)
    }

    private fun renderPlayerState() {
        val m = model ?: return
        val player = m.player
        val title = player.seriesID?.let(m::seriesEntry)?.series?.title ?: "红果 TV"
        playerTitle.text = "$title  ·  第 ${player.number} 集"
        playerMessage.text = when (val state = player.state) {
            PlayerState.Idle -> "播放器已停止"
            PlayerState.Resolving -> "正在获取播放地址…"
            PlayerState.ResettingDevice -> "正在刷新播放会话…"
            PlayerState.Buffering -> "正在缓冲…"
            PlayerState.Playing -> "播放中"
            is PlayerState.Failed -> state.message
        }
        val failed = player.state as? PlayerState.Failed
        retryButton.visibility = if (failed != null) View.VISIBLE else View.GONE
        diagnosticButton.visibility = if (failed != null) View.VISIBLE else View.GONE
        playerFocusButtons.forEach { it.nextFocusDownId = if (failed != null) retryButton.id else it.id }
        if (failed != null) {
            playerControls.visibility = View.VISIBLE
            handler.removeCallbacks(hideControls)
        }
        playButton.text = if (player.video.wantsToPlay && player.video.status != TimeControl.PAUSED) "暂停" else "播放"
        speedButton.text = "倍速 ${"%.2f".format(player.speed)}×"
        qualityButton.text = if (player.quality > 0) "${player.quality}P" else "画质"
    }

    private fun updateProgress() {
        val player = model?.player ?: return
        val duration = player.video.duration
        val position = player.video.position
        if (duration.isFinite() && duration > 0) {
            playerSeek.progress = (position / duration * 1000).toInt().coerceIn(0, 1000)
            progressText.text = "${timeLabel(position)} / ${timeLabel(duration)}"
        } else {
            playerSeek.progress = 0
            progressText.text = "正在读取时长…"
        }
        renderPlayerState()
    }

    private fun sendPlaybackDiagnosticsToPhones() {
        val report = buildPlaybackDiagnosticReport()
        diagnosticButton.isEnabled = false
        diagnosticButton.text = "正在搜索手机…"
        lifecycleScope.launch {
            try {
                val result = PhoneDiagnosticClient.sendToAll(this@MainActivity, report)
                val message = when {
                    result.found == 0 -> "没有发现开启“接收诊断”的开心果手机，请确认同一局域网并保持手机 App 在前台"
                    result.delivered > 0 -> "完整诊断已发送到 ${result.delivered} 台手机"
                    else -> "发现 ${result.found} 台手机，但没有成功送达；请检查接收开关或网络隔离"
                }
                toast(message)
            } catch (e: Exception) {
                toast("发送诊断失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                diagnosticButton.isEnabled = true
                diagnosticButton.text = "发送诊断到手机"
            }
        }
    }

    private fun buildPlaybackDiagnosticReport(): String {
        val failure = (model?.player?.state as? PlayerState.Failed)?.diagnostic
        val stats = PlaybackDiagnostics.snapshot()
        return buildString {
            appendLine("hongguoTV Android TV 播放故障诊断")
            appendLine("应用版本：${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）")
            appendLine("采集时间：${java.time.Instant.now()}")
            appendLine()
            appendLine("设备与解码器环境：")
            appendLine(deviceReport())
            appendLine()
            if (failure != null) {
                appendLine(failure.toReport())
            } else {
                appendLine("播放失败对象没有提供 SDK 异常诊断；附带最近 SDK 请求记录：")
                SdkDiagnostics.snapshot().forEach { appendLine("${it.time}  ${it.message}") }
            }
            appendLine()
            appendLine("播放器运行状态：")
            appendLine("视频编码：${stats.videoCodec}")
            appendLine("音频编码：${stats.audioCodec}")
            appendLine("解码器：${stats.decoderName}")
            appendLine("硬件解码：${stats.decoderHardware}")
            appendLine("分辨率：${stats.width}×${stats.height}")
            appendLine("帧率：${stats.frameRate}")
            appendLine("码率：${stats.bitrate}")
            appendLine("丢帧数：${stats.droppedFrames}")
            appendLine("最近 20 秒网络字节：${stats.networkBytesLast20Seconds}")
            appendLine("隐私：不包含 Cookie、Token、签名值或匿名设备原始标识。")
        }
    }

    private fun openEpisodeDialog() {
        val m = model ?: return
        val player = m.player
        val id = player.seriesID ?: return
        val page = m.seriesEntry(id)
        if (page.episodes.isEmpty()) {
            toast("剧集信息尚未加载")
            m.loadSeries(page)
            return
        }
        val scroll = ScrollView(this)
        val grid = GridLayout(this).apply { columnCount = 6; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        var selected: View? = null
        page.episodes.forEach { episode ->
            val cell = navButton(episode.number.toString()) {
                if (!episode.playable) toast("这一集暂不可播放")
                else {
                    m.player.play(id, episode.number)
                    episodeDialog?.dismiss()
                }
            }
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = dp(90); height = dp(48)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
            if (episode.number == player.number) selected = cell
        }
        scroll.addView(grid)
        episodeDialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("选择剧集")
            .setView(scroll)
            .setNegativeButton("返回", null)
            .create()
        episodeDialog?.setOnShowListener { selected?.requestFocus() }
        episodeDialog?.show()
    }

    private fun chooseNextSpeed() {
        val player = model?.player ?: return
        val speeds = com.xiaobei206.hongguotv.model.Player.speeds
        val index = speeds.indexOf(player.speed).coerceAtLeast(-1)
        player.chooseSpeed(speeds[(index + 1) % speeds.size])
        renderPlayerState()
    }

    private fun chooseNextQuality() {
        val player = model?.player ?: return
        if (player.qualities.isEmpty()) { toast("当前剧集没有可切换的清晰度"); return }
        val index = player.qualities.indexOf(player.quality).coerceAtLeast(-1)
        player.chooseQuality(player.qualities[(index + 1) % player.qualities.size])
        renderPlayerState()
    }

    private fun leavePlayer() {
        handler.removeCallbacks(hideControls)
        model?.cancelPendingHistoryResume()
        model?.player?.leave()
        progressTask?.let(handler::removeCallbacks)
        screen = parentScreen
        showShell()
    }

    private fun buildInfoContent(settings: Boolean) {
        contentHost.removeAllViews()
        composeFeed = null
        composeFeedUi = null
        composeDetailUi = null
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        }
        val identity = tvApp.handoffServer
        val report = if (settings) "" else deviceReport()
        val details = if (settings) buildList {
            add("版本" to BuildConfig.VERSION_NAME)
            add("最低系统" to "Android 6.0 / API 23")
            add("播放器" to "Media3 ExoPlayer")
            add("签名" to "hongguoTV 独立签名")
            if (identity != null) {
                add("接力名称" to identity.displayName)
                add("设备编号" to identity.shortCode)
            }
        } else emptyList()
        val actions = if (settings) buildList {
            if (identity != null) {
                add(TvInfoAction(
                    title = "修改电视显示名称",
                    description = "修改开心果设备列表中显示的电视名称。",
                    onClick = {
                        val input = EditText(this@MainActivity).apply {
                            setSingleLine(true)
                            setText(identity.displayName)
                            setSelection(text.length)
                        }
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("电视显示名称")
                            .setMessage("这个名称会显示在开心果的电视搜索列表中。")
                            .setView(input)
                            .setNegativeButton("取消", null)
                            .setPositiveButton("保存") { _, _ ->
                                if (identity.setDisplayName(input.text.toString())) {
                                    toast("电视名称已更新")
                                    buildInfoContent(true)
                                } else {
                                    toast("名称不能为空")
                                }
                            }
                            .show()
                    },
                ))
            }
            add(TvInfoAction(
                title = "清理封面缓存",
                description = "释放海报图片占用的空间；不会删除收藏和观看历史。",
                onClick = {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("清理缓存")
                        .setMessage("删除封面缓存，不会删除收藏和观看历史。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("清理") { _, _ ->
                            File(cacheDir, "tv-covers").deleteRecursively()
                            toast("封面缓存已清理")
                        }
                        .show()
                },
            ))
            add(TvInfoAction(
                title = "立即检查更新",
                description = "检查正式 Release；下载完成后由 Android 系统确认安装。",
                primary = true,
                onClick = { checkForUpdate(manual = true) },
            ))
        } else listOf(
            TvInfoAction(
                title = "复制诊断信息",
                description = "将设备、存储和视频解码器信息复制到剪贴板，方便排查兼容性。",
                primary = true,
                onClick = {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("hongguoTV device report", report))
                    toast("诊断信息已复制")
                },
            ),
        )
        view.setContent {
            TvInfoScreen(
                title = if (settings) "设置" else "设备兼容性诊断",
                intro = if (settings) "版本信息、设备接力与缓存管理。" else "检查系统、存储和解码器，定位电视兼容性问题。",
                details = details,
                diagnostics = report,
                actions = actions,
            )
        }
        contentHost.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    private fun checkForUpdate(manual: Boolean) {
        if (checkingForUpdate) {
            if (manual) toast("正在检查更新，请稍候")
            return
        }
        if (!manual && !updateManager.shouldAutoCheck()) return
        if (!manual) updateManager.markAutoCheck()
        checkingForUpdate = true
        if (manual) toast("正在检查更新…")
        lifecycleScope.launch {
            val result = updateManager.check()
            checkingForUpdate = false
            when (result) {
                is TvUpdateCheck.UpToDate -> if (manual) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("已是最新版本")
                        .setMessage("当前版本：${BuildConfig.VERSION_NAME}\n暂未发现更新。")
                        .setPositiveButton("确定", null)
                        .show()
                }
                is TvUpdateCheck.Failed -> if (manual) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("检查更新失败")
                        .setMessage(result.message)
                        .setPositiveButton("确定", null)
                        .show()
                }
                is TvUpdateCheck.Available -> showUpdateDialog(result.release)
            }
        }
    }

    private fun showUpdateDialog(release: TvUpdateRelease) {
        if (isFinishing || isDestroyed) return
        val notes = release.notes.lineSequence()
            .dropWhile { !it.trim().startsWith("## [${release.version}]") }
            .drop(1)
            .takeWhile { !it.trim().startsWith("## [") }
            .joinToString("\n")
            .trim()
            .take(1000)
            .ifBlank { "修复问题并改善稳定性。" }
        AlertDialog.Builder(this)
            .setTitle("发现新版本 v${release.version}")
            .setMessage("当前版本：${BuildConfig.VERSION_NAME}\n新版本：${release.version}\n\n${notes}\n\n下载后将打开系统安装器确认更新。")
            .setNegativeButton("稍后", null)
            .setNeutralButton("查看 Release") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl))) }
                    .onFailure { toast("无法打开 Release 页面") }
            }
            .setPositiveButton("下载并安装") { _, _ -> downloadAndInstall(release) }
            .show()
    }

    private fun downloadAndInstall(release: TvUpdateRelease) {
        val progress = AlertDialog.Builder(this)
            .setTitle("正在下载更新")
            .setMessage("版本 v${release.version}\n正在下载并校验安装包，请保持网络连接。")
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch {
            try {
                val apk = updateManager.downloadAndVerify(release)
                progress.dismiss()
                pendingInstallApk = apk
                installVerifiedApk(apk)
            } catch (e: Exception) {
                progress.dismiss()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("更新下载失败")
                    .setMessage(e.message ?: "下载失败，请检查网络后重试。")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private fun installVerifiedApk(apk: File) {
        if (!apk.isFile || apk.length() <= 0L) {
            toast("安装包不存在，请重新检查更新")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            try {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_INSTALL_PERMISSION)
            } catch (e: Exception) {
                toast("请在系统设置中允许 hongguoTV 安装未知应用")
            }
            return
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.updatefileprovider", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            toast("无法打开系统安装器：${e.message ?: "未知错误"}")
        }
    }

    @Deprecated("Deprecated in Android 13; retained for Android TV compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_INSTALL_PERMISSION) {
            val apk = pendingInstallApk
            pendingInstallApk = null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
                if (apk != null) installVerifiedApk(apk)
            } else {
                toast("未允许安装未知应用，更新没有安装")
            }
        }
    }

    private fun deviceReport(): String {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        val stat = StatFs(filesDir.absolutePath)
        val types = listOf("video/avc", "video/hevc", "video/x-vnd.on2.vp9", "video/av01")
        val codecs = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { !it.isEncoder }
                .mapNotNull { info ->
                    val supported = info.supportedTypes.filter { type -> types.any { it.equals(type, true) } }
                    if (supported.isEmpty()) null else "${info.name}: ${supported.joinToString()}"
                }.distinct().take(24).joinToString("\n")
        }.getOrDefault("无法读取解码器列表")
        return buildString {
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("产品：${Build.PRODUCT} / ${Build.DEVICE}")
            appendLine("Android：${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("RAM：系统总量 ${memory.totalMem / 1024 / 1024} MB；应用内存级别 ${manager.memoryClass} MB")
            appendLine("可用内存：${memory.availMem / 1024 / 1024} MB")
            appendLine("数据分区可用：${stat.availableBytes / 1024 / 1024} MB")
            appendLine("屏幕：${resources.displayMetrics.widthPixels}×${resources.displayMetrics.heightPixels}")
            appendLine()
            appendLine("视频解码器：")
            appendLine(codecs)
            appendLine()
            appendLine("解码器列表不代表所有编码、分辨率和码率都能稳定播放；最终以实际出画面为准。")
        }
    }

    private fun makeSnapshot(): UiSnapshot {
        val m = model
        if (m == null) return UiSnapshot(emptyList(), false, tvApp.startupError, null, emptyList(),
            false, tvApp.startupError, PlayerState.Idle, 0, 1f, 0, emptyList(), 0, 0,
            emptyList(), emptyList(), "", "", "", emptyList(), "")
        val cards = when (screen) {
            Screen.FAVORITES -> m.favorites.cards
            Screen.HISTORY -> m.history.list.map {
                val position = if (it.position.isFinite()) it.position.coerceAtLeast(0.0) else 0.0
                Card(it.seriesID, it.title, it.cover, "第 ${it.episode} 集 · ${timeLabel(position)}", episodes = it.total)
            }
            else -> currentFeed?.cards.orEmpty()
        }
        val page = currentSeries
        return UiSnapshot(
            cards, currentFeed?.loading == true, currentFeed?.error?.let(::friendly).orEmpty(),
            page?.series, page?.partEpisodes.orEmpty(), page?.loading == true, page?.error?.let(::friendly).orEmpty(),
            m.player.state, m.player.number, m.player.speed, m.player.quality, m.player.qualities,
            m.favorites.list.size, m.history.list.size,
            m.chartContents, m.rankBoards, m.rankContent, m.rankBoard, m.currentRankContent,
            m.newDates, m.newDate,
            page?.part ?: 0, page?.parts.orEmpty(),
            if (page != null && m.player.seriesID == page.id) m.player.number else 0,
        )
    }

    private fun renderSnapshot(snapshot: UiSnapshot) {
        if (screen == Screen.PLAYER) {
            if (renderedScreen != Screen.PLAYER) showPlayer()
            renderPlayerState()
            return
        }
        if (renderedScreen != screen) {
            renderedScreen = screen
            when (screen) {
                Screen.HOME, Screen.RANK, Screen.NEW, Screen.SEARCH, Screen.FAVORITES, Screen.HISTORY -> buildFeedContent()
                Screen.DETAIL -> buildDetailContent()
                Screen.DEVICE -> buildInfoContent(false)
                Screen.SETTINGS -> buildInfoContent(true)
                Screen.PLAYER -> return
            }
        }
        shell.visibility = View.VISIBLE
        playerLayer.visibility = View.GONE
        when (screen) {
            Screen.HOME, Screen.RANK, Screen.NEW, Screen.SEARCH, Screen.FAVORITES, Screen.HISTORY -> renderFeed(snapshot)
            Screen.DETAIL -> renderDetail(snapshot)
            else -> Unit
        }
    }

    private fun showStartupError(message: String) {
        contentHost.removeAllViews()
        val label = text("红果 TV 初始化失败\n\n$message\n\n请检查网络后重新启动应用。", 19f, Color.WHITE)
        label.gravity = Gravity.CENTER
        contentHost.addView(label, FrameLayout.LayoutParams(-1, -1))
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (screen) {
            Screen.PLAYER -> leavePlayer()
            Screen.DETAIL -> {
                screen = parentScreen
                currentSeries = null
                showShell()
            }
            Screen.SEARCH -> {
                screen = searchReturnScreen
                headerTitle = when (screen) {
                    Screen.HOME -> "推荐"
                    Screen.RANK -> "排行榜"
                    Screen.NEW -> "新剧上线"
                    Screen.FAVORITES -> "我的收藏"
                    Screen.HISTORY -> "观看历史"
                    Screen.DEVICE -> "设备兼容性诊断"
                    Screen.SETTINGS -> "设置"
                    else -> headerTitle
                }
                currentFeed = feedFor(searchReturnScreen)
                showShell()
            }
            Screen.HOME -> super.onBackPressed()
            else -> enterFeed(Screen.HOME)
        }
    }

    private fun feedFor(target: Screen): Feed? = when (target) {
        Screen.HOME -> model?.home
        Screen.RANK -> model?.rankFeed
        Screen.NEW -> model?.newFeed
        else -> null
    }

    override fun onStart() {
        super.onStart()
        tvApp.handoffServer?.let { server ->
            server.listener = ::handleHandoffRequest
            server.setAvailable(true)
        }
        model?.player?.returnToApp()
    }

    override fun onStop() {
        tvApp.handoffServer?.let { server ->
            server.listener = null
            server.setAvailable(false)
        }
        model?.player?.leaveApp()
        model?.let { it.saveSession(it.session()); it.flush() }
        super.onStop()
    }

    private fun handleHandoffRequest(request: TvHandoffRequest, reply: (TvHandoffReply) -> Unit) {
        runOnUiThread {
            val m = model
            if (m == null) {
                reply(TvHandoffReply(request.transferId, false, "电视播放器尚未初始化", tvApp.handoffServer?.deviceId.orEmpty()))
                return@runOnUiThread
            }
            parentScreen = Screen.HOME
            screen = Screen.PLAYER
            m.player.play(request.seriesId, request.episode, request.positionMs / 1000.0)
            showPlayer()
            lifecycleScope.launch {
                val state = withTimeoutOrNull(34_000L) {
                    snapshotFlow { m.player.state }.first {
                        it == PlayerState.Playing || it is PlayerState.Failed
                    }
                }
                val current = m.player.seriesID == request.seriesId && m.player.number == request.episode
                val server = tvApp.handoffServer
                val response = when {
                    !current -> TvHandoffReply(request.transferId, false, "电视播放任务已被其他操作替代", server?.deviceId.orEmpty())
                    state == PlayerState.Playing -> TvHandoffReply(request.transferId, true, "电视已开始播放", server?.deviceId.orEmpty())
                    state is PlayerState.Failed -> {
                        m.player.leave()
                        TvHandoffReply(request.transferId, false, state.message, server?.deviceId.orEmpty())
                    }
                    else -> {
                        m.player.leave()
                        TvHandoffReply(request.transferId, false, "电视加载视频超时", server?.deviceId.orEmpty())
                    }
                }
                reply(response)
                if (response.accepted) toast("已接收手机接力播放")
                else toast("接力播放失败：${response.message}")
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideControls)
        progressTask?.let(handler::removeCallbacks)
        if (isFinishing) model?.player?.leave()
        super.onDestroy()
    }

    private fun updateNavigationSelection() {
        val active = when (screen) {
            Screen.HOME, Screen.RANK, Screen.NEW, Screen.FAVORITES, Screen.HISTORY, Screen.DEVICE, Screen.SETTINGS -> screen
            else -> null
        }
        for (index in 0 until rail.childCount) {
            val button = rail.getChildAt(index) as? TextView ?: continue
            if (button.tag !is Screen) continue
            button.isSelected = button.tag == active
            button.background = buttonBackground(button.hasFocus(), button.isSelected)
        }
    }

    private fun navButton(label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            id = View.generateViewId()
            text = label
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = buttonBackground(false)
            setOnClickListener { action() }
            onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
                view.background = buttonBackground(focused, view.isSelected)
                view.scaleX = if (focused) 1.045f else 1f
                view.scaleY = if (focused) 1.045f else 1f
                view.elevation = if (focused) dp(6).toFloat() else 0f
            }
        }

    private fun buttonBackground(focused: Boolean, selected: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(when {
            focused -> 0xff34465a.toInt()
            selected -> 0xff1d382f.toInt()
            else -> 0xff1a2635.toInt()
        })
        setStroke(dp(if (focused) 3 else if (selected) 2 else 1),
            when {
                focused -> 0xffffb45c.toInt()
                selected -> 0xff62d99a.toInt()
                else -> 0xff334256.toInt()
            })
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun timeLabel(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0) return "--:--"
        val total = seconds.toInt()
        return if (total >= 3600) "%02d:%02d:%02d".format(total / 3600, (total / 60) % 60, total % 60)
        else "%02d:%02d".format(total / 60, total % 60)
    }

    private fun toast(message: String) =
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_INSTALL_PERMISSION = 7314
    }
}
