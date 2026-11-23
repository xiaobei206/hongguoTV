package com.xiaobei206.hongguotv.ui

import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Alignment
import com.xiaobei206.hongguotv.TvArtworkLoader
import com.xiaobei206.hongguotv.model.Card
import com.xiaobei206.hongguotv.model.Feed
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

internal data class TvFeedFilterChoice(
    val key: String,
    val label: String,
    val selected: Boolean,
)

internal data class TvFeedFilterRow(
    val label: String,
    val choices: List<TvFeedFilterChoice>,
)

internal data class TvFeedUiState(
    val screenKey: String,
    val feedKey: String,
    val title: String,
    val status: String,
    val cards: List<Card>,
    val loading: Boolean,
    val error: String,
    val emptyMessage: String,
    val filterRows: List<TvFeedFilterRow>,
)

/**
 * Compose owns the browsable TV grid. Navigation, playback, device handoff,
 * update installation and diagnostics remain in MainActivity while their
 * behavior is migrated deliberately rather than discarded.
 */
@Composable
internal fun TvFeedScreen(
    state: TvFeedUiState,
    feed: Feed?,
    artwork: TvArtworkLoader,
    onCardSelected: (Card) -> Unit,
    onToggleFavorite: (Card) -> Unit,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    onFilterSelected: (String) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val firstCardFocus = remember(state.feedKey) { FocusRequester() }
    val firstFilterFocus = remember(state.screenKey, state.feedKey) { FocusRequester() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.background)
            .padding(start = 22.dp, end = 24.dp, top = 14.dp, bottom = 14.dp),
    ) {
        val columns = (maxWidth / 166.dp).toInt().coerceIn(3, 7)
        val initialTarget = max(30, columns * 4)

        LaunchedEffect(feed, state.feedKey, columns) {
            val source = feed ?: return@LaunchedEffect
            var rounds = 0
            while (rounds < 3 && source.wantsMore) {
                if (source.loading) {
                    val completed = withTimeoutOrNull(30_000L) {
                        snapshotFlow { source.loading }.first { !it }
                    } != null
                    if (!completed) break
                }
                if (source.cards.size >= initialTarget || !source.wantsMore) break
                val before = source.cards.size
                source.loadMore(minAdditionalCards = (initialTarget - before).coerceAtLeast(1))
                val completed = withTimeoutOrNull(30_000L) {
                    snapshotFlow { source.loading }.first { !it }
                } != null
                rounds++
                if (!completed || source.error != null || source.cards.size <= before) break
            }
        }

        LaunchedEffect(feed, state.feedKey, gridState, columns) {
            val source = feed ?: return@LaunchedEffect
            snapshotFlow {
                val info = gridState.layoutInfo
                Triple(
                    info.totalItemsCount,
                    info.visibleItemsInfo.lastOrNull()?.index ?: -1,
                    source.loading,
                )
            }.distinctUntilChanged().collect { (count, lastVisible, loading) ->
                val threshold = (columns * 3).coerceAtLeast(9)
                if (!loading && source.wantsMore && count > 0 && lastVisible >= count - threshold) {
                    source.loadMore()
                }
            }
        }

        LaunchedEffect(state.screenKey, state.feedKey) {
            runCatching { gridState.scrollToItem(0) }
        }

        LaunchedEffect(
            state.screenKey,
            state.feedKey,
            if (state.filterRows.isEmpty()) state.cards.firstOrNull()?.id else null,
        ) {
            if (state.filterRows.isNotEmpty()) {
                runCatching { firstFilterFocus.requestFocus() }
            } else if (state.cards.isNotEmpty()) {
                runCatching { firstCardFocus.requestFocus() }
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .size(11.dp)
                                .background(TvColors.accent, CircleShape),
                        )
                        Text(
                            text = state.title,
                            color = TvColors.text,
                            fontSize = 29.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = state.status,
                        color = TvColors.muted,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TvActionButton(label = "搜剧", onClick = onSearch, width = 88.dp)
                TvActionButton(label = "刷新", onClick = onRefresh, width = 88.dp)
            }

            if (state.filterRows.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                state.filterRows.forEachIndexed { rowIndex, row ->
                    FilterChoiceRow(
                        row = row,
                        rowIndex = rowIndex,
                        firstFilterFocus = firstFilterFocus,
                        onSelected = onFilterSelected,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (state.cards.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .background(TvColors.panel, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (state.loading) "…" else "剧",
                                color = TvColors.accent,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = when {
                                state.loading -> "正在获取剧集"
                                state.error.isNotBlank() -> state.error
                                else -> state.emptyMessage
                            },
                            color = TvColors.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!state.loading && state.error.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            TvActionButton(label = "重试加载", onClick = onRefresh, width = 140.dp)
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(start = 2.dp, end = 6.dp, top = 2.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp),
                    horizontalArrangement = Arrangement.spacedBy(15.dp),
                ) {
                    items(state.cards, key = { it.id }) { card ->
                        PosterTile(
                            card = card,
                            artwork = artwork,
                            focusRequester = if (card.id == state.cards.firstOrNull()?.id) firstCardFocus else null,
                            onClick = { onCardSelected(card) },
                            onLongClick = { onToggleFavorite(card) },
                        )
                    }
                    if (state.loading || state.error.isNotBlank() || (feed?.wantsMore == true)) {
                        item(key = "feed-status", span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = when {
                                        state.error.isNotBlank() -> "本批加载失败，可按右上角刷新重试"
                                        state.loading -> "正在补载更多剧集…"
                                        else -> "继续向下浏览，将自动加载后续内容"
                                    },
                                    color = TvColors.muted,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChoiceRow(
    row: TvFeedFilterRow,
    rowIndex: Int,
    firstFilterFocus: FocusRequester,
    onSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(43.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = row.label,
            color = TvColors.muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(60.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LazyRow(
            modifier = Modifier.fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(row.choices, key = { it.key }) { choice ->
                FilterChoiceButton(
                    choice = choice,
                    focusRequester = if (rowIndex == 0 && choice == row.choices.firstOrNull()) firstFilterFocus else null,
                    onClick = { onSelected(choice.key) },
                )
            }
        }
    }
}

@Composable
private fun FilterChoiceButton(
    choice: TvFeedFilterChoice,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember(choice.key) { mutableStateOf(false) }
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (choice.selected) TvColors.selected else TvColors.panel)
            .border(
                width = when {
                    focused -> 3.dp
                    choice.selected -> 1.dp
                    else -> 1.dp
                },
                color = when {
                    focused -> TvColors.focus
                    choice.selected -> TvColors.accent
                    else -> TvColors.border
                },
                shape = shape,
            )
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 15.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = choice.label,
            color = if (choice.selected) TvColors.accent else TvColors.text,
            fontSize = 14.sp,
            fontWeight = if (choice.selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterTile(
    card: Card,
    artwork: TvArtworkLoader,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var focused by remember(card.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                val scale = if (focused) 1.035f else 1f
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (focused) 12.dp else 0.dp, shape)
            .clip(shape)
            .background(TvColors.card)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) TvColors.focus else TvColors.border,
                shape = shape,
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .focusable()
            .padding(7.dp),
    ) {
        PosterArtwork(
            url = card.cover,
            title = card.title,
            artwork = artwork,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.69f)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = card.title.ifBlank { "未命名剧集" },
            color = TvColors.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 18.sp,
        )
        if (card.sub.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = card.sub,
                color = TvColors.muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PosterArtwork(
    url: String,
    title: String,
    artwork: TvArtworkLoader,
    modifier: Modifier = Modifier,
) {
    var imageView by remember { mutableStateOf<ImageView?>(null) }
    Box(modifier = modifier.background(TvColors.imagePlaceholder)) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(android.graphics.Color.rgb(31, 47, 65))
                }.also { imageView = it }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { view ->
                artwork.cancel(view)
                if (imageView === view) imageView = null
            },
        )
        DisposableEffect(imageView, url) {
            val image = imageView
            if (image != null) artwork.load(url, image)
            onDispose {
                if (image != null) artwork.cancel(image)
            }
        }
        if (url.isBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = title.take(1).ifBlank { "红" },
                    color = TvColors.muted,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun TvActionButton(
    label: String,
    onClick: () -> Unit,
    width: Dp,
) {
    var focused by remember(label) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .width(width)
            .height(43.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) TvColors.selected else TvColors.panel)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) TvColors.focus else TvColors.border,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (focused) TvColors.accent else TvColors.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private object TvColors {
    val background = Color(0xFF0A111B)
    val panel = Color(0xFF142131)
    val card = Color(0xFF111D2A)
    val imagePlaceholder = Color(0xFF1F2F41)
    val border = Color(0xFF2B3B4E)
    val focus = Color(0xFFFFB45C)
    val selected = Color(0xFF19382E)
    val accent = Color(0xFF68D9A2)
    val text = Color(0xFFF0F5FA)
    val muted = Color(0xFF9BACBE)
}
