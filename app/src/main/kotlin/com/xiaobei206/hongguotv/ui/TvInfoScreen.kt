package com.xiaobei206.hongguotv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class TvInfoAction(
    val title: String,
    val description: String,
    val primary: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
internal fun TvInfoScreen(
    title: String,
    intro: String,
    details: List<Pair<String, String>>,
    diagnostics: String = "",
    actions: List<TvInfoAction>,
) {
    val firstActionFocus = remember(title) { FocusRequester() }
    LaunchedEffect(title) {
        if (actions.isNotEmpty()) runCatching { firstActionFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(InfoColors.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 28.dp, top = 20.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(InfoColors.accent),
            )
            Column {
                Text(
                    text = title,
                    color = InfoColors.text,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = intro,
                    color = InfoColors.muted,
                    fontSize = 14.sp,
                )
            }
        }

        if (details.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(InfoColors.panel)
                    .border(1.dp, InfoColors.border, RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                details.forEachIndexed { index, pair ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Text(
                            text = pair.first,
                            color = InfoColors.muted,
                            fontSize = 14.sp,
                            modifier = Modifier.width(115.dp),
                        )
                        Text(
                            text = pair.second,
                            color = InfoColors.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (index < details.lastIndex) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(InfoColors.border),
                        )
                    }
                }
            }
        }

        if (diagnostics.isNotBlank()) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = "设备诊断详情",
                color = InfoColors.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = diagnostics,
                color = InfoColors.description,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }

        if (actions.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Text(
                text = if (title == "设置") "快捷操作" else "诊断操作",
                color = InfoColors.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            actions.forEachIndexed { index, action ->
                InfoActionTile(
                    action = action,
                    focusRequester = if (index == 0) firstActionFocus else null,
                )
                Spacer(Modifier.height(9.dp))
            }
        }
    }
}

@Composable
private fun InfoActionTile(
    action: TvInfoAction,
    focusRequester: FocusRequester?,
) {
    var focused by remember(action.title) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    focused -> InfoColors.selected
                    action.primary -> InfoColors.primaryPanel
                    else -> InfoColors.panel
                },
            )
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = when {
                    focused -> InfoColors.focus
                    action.primary -> InfoColors.accent
                    else -> InfoColors.border
                },
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = action.onClick)
            .focusable()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = action.title,
                color = if (action.primary) InfoColors.accent else InfoColors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = action.description,
                color = InfoColors.muted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "›",
            color = if (focused) InfoColors.focus else InfoColors.muted,
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private object InfoColors {
    val background = Color(0xFF0A111B)
    val panel = Color(0xFF142131)
    val primaryPanel = Color(0xFF173A30)
    val border = Color(0xFF2B3B4E)
    val focus = Color(0xFFFFB45C)
    val selected = Color(0xFF19382E)
    val accent = Color(0xFF68D9A2)
    val text = Color(0xFFF0F5FA)
    val description = Color(0xFFD2D9E2)
    val muted = Color(0xFF9BACBE)
}
