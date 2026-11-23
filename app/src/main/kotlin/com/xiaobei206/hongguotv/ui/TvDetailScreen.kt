package com.xiaobei206.hongguotv.ui

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.AndroidView
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import com.xiaobei206.hongguotv.TvArtworkLoader
import dev.openhongguo.Episode
import dev.openhongguo.Series

internal data class TvDetailUiState(
    val seriesId: String,
    val series: Series?,
    val meta: String,
    val resumeLabel: String,
    val episodes: List<Episode>,
    val partLabels: List<String>,
    val selectedPart: Int,
    val currentEpisodeNumber: Int,
    val favorite: Boolean,
    val loading: Boolean,
    val error: String,
)

@Composable
internal fun TvDetailScreen(
    state: TvDetailUiState,
    artwork: TvArtworkLoader,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onEpisodeSelected: (Episode) -> Unit,
    onPartSelected: (Int) -> Unit,
    onRetry: () -> Unit,
) {
    val playFocus = remember(state.seriesId) { FocusRequester() }
    LaunchedEffect(state.seriesId, state.series != null) {
        if (state.series != null) runCatching { playFocus.requestFocus() }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(TvDetailColors.background)
            .padding(start = 22.dp, end = 24.dp, top = 14.dp, bottom = 14.dp),
    ) {
        val leftWidth = (maxWidth * 0.37f).coerceIn(300.dp, 480.dp)
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(
                modifier = Modifier
                    .width(leftWidth)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = state.series?.title ?: "剧集详情",
                    color = TvDetailColors.text,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 32.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        state.series != null -> state.meta
                        state.error.isNotBlank() -> state.error
                        else -> "正在读取剧集信息…"
                    },
                    color = TvDetailColors.muted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    DetailCover(
                        url = state.series?.coverUrl.orEmpty(),
                        title = state.series?.title.orEmpty(),
                        artwork = artwork,
                        modifier = Modifier
                            .width(142.dp)
                            .aspectRatio(0.69f),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.series != null) {
                            DetailActionButton(
                                label = state.resumeLabel,
                                primary = true,
                                focusRequester = playFocus,
                                onClick = onPlay,
                            )
                            DetailActionButton(
                                label = if (state.favorite) "★ 已收藏" else "☆ 收藏",
                                primary = false,
                                onClick = onFavorite,
                            )
                        } else if (state.error.isNotBlank()) {
                            DetailActionButton(
                                label = "重新加载",
                                primary = false,
                                onClick = onRetry,
                            )
                        } else {
                            Text(
                                text = "正在准备播放信息…",
                                color = TvDetailColors.muted,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "剧情简介",
                    color = TvDetailColors.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = state.series?.description?.ifBlank { "暂无剧情简介" } ?: "",
                    color = TvDetailColors.description,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
                Spacer(Modifier.height(18.dp))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "选择剧集",
                        color = TvDetailColors.text,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = when {
                            state.episodes.isNotEmpty() -> "当前显示 ${state.episodes.size} 集"
                            state.loading -> "正在加载"
                            state.error.isNotBlank() -> "读取失败"
                            else -> "等待剧集列表"
                        },
                        color = TvDetailColors.muted,
                        fontSize = 13.sp,
                    )
                }

                if (state.partLabels.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.partLabels.indices.toList(), key = { it }) { part ->
                            DetailChip(
                                label = state.partLabels[part],
                                selected = state.selectedPart == part,
                                onClick = { onPartSelected(part) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                when {
                    state.episodes.isNotEmpty() -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(8),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(bottom = 18.dp, end = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(state.episodes, key = { it.number }) { episode ->
                                DetailEpisodeTile(
                                    number = episode.number,
                                    current = episode.number == state.currentEpisodeNumber,
                                    playable = episode.playable,
                                    onClick = { onEpisodeSelected(episode) },
                                )
                            }
                        }
                    }
                    state.loading -> {
                        DetailEmptyState("正在加载剧集列表…", secondary = "稍等片刻，剧集会显示在这里", modifier = Modifier.weight(1f))
                    }
                    state.error.isNotBlank() -> {
                        DetailEmptyState(state.error, secondary = "可返回重试或重新进入详情", modifier = Modifier.weight(1f))
                    }
                    else -> {
                        DetailEmptyState("尚无可显示的剧集", secondary = "请检查网络或稍后重试", modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailCover(
    url: String,
    title: String,
    artwork: TvArtworkLoader,
    modifier: Modifier,
) {
    var imageView by remember { mutableStateOf<ImageView?>(null) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(TvDetailColors.imagePlaceholder)
            .border(1.dp, TvDetailColors.border, RoundedCornerShape(12.dp)),
    ) {
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
            onDispose { if (image != null) artwork.cancel(image) }
        }
        if (url.isBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = title.take(1).ifBlank { "红" },
                    color = TvDetailColors.muted,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun DetailActionButton(
    label: String,
    primary: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember(label) { mutableStateOf(false) }
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .fillMaxWidth()
            .height(47.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    focused -> TvDetailColors.selected
                    primary -> TvDetailColors.accentDeep
                    else -> TvDetailColors.panel
                },
            )
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = when {
                    focused -> TvDetailColors.focus
                    primary -> TvDetailColors.accent
                    else -> TvDetailColors.border
                },
                shape = shape,
            )
            .clickable(onClick = onClick)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                primary -> TvDetailColors.accent
                focused -> TvDetailColors.accent
                else -> TvDetailColors.text
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember(label) { mutableStateOf(false) }
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .height(38.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (selected) TvDetailColors.selected else TvDetailColors.panel)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) TvDetailColors.focus else if (selected) TvDetailColors.accent else TvDetailColors.border,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) TvDetailColors.accent else TvDetailColors.text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun DetailEpisodeTile(
    number: Int,
    current: Boolean,
    playable: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember(number) { mutableStateOf(false) }
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                val scale = if (focused) 1.06f else 1f
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (focused) 9.dp else 0.dp, shape)
            .clip(shape)
            .background(
                when {
                    current -> TvDetailColors.selected
                    else -> TvDetailColors.panel
                },
            )
            .border(
                width = when {
                    focused -> 3.dp
                    current -> 2.dp
                    else -> 1.dp
                },
                color = when {
                    focused -> TvDetailColors.focus
                    current -> TvDetailColors.accent
                    else -> TvDetailColors.border
                },
                shape = shape,
            )
            .clickable(onClick = onClick)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = number.toString(),
                color = if (current) TvDetailColors.accent else TvDetailColors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            if (!playable) {
                Text(text = "不可播", color = TvDetailColors.muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun DetailEmptyState(label: String, secondary: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = label,
                color = TvDetailColors.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = secondary,
                color = TvDetailColors.muted,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private object TvDetailColors {
    val background = Color(0xFF0A111B)
    val panel = Color(0xFF142131)
    val imagePlaceholder = Color(0xFF1F2F41)
    val border = Color(0xFF2B3B4E)
    val focus = Color(0xFFFFB45C)
    val selected = Color(0xFF19382E)
    val accent = Color(0xFF68D9A2)
    val accentDeep = Color(0xFF173A30)
    val text = Color(0xFFF0F5FA)
    val description = Color(0xFFD2D9E2)
    val muted = Color(0xFF9BACBE)
}
