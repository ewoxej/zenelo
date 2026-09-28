package app.zenelo.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.zenelo.ZeneloApp
import app.zenelo.ui.theme.ZeneloColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun appContainer() = (LocalContext.current.applicationContext as ZeneloApp).container

/** Mono uppercase caption, e.g. "FOLDERS · 3". */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = ZeneloColors.TextMuted,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** Big screen title with an optional mono counter: "Favorites 148". */
@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, count: Int? = null) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) {
            Text(
                "$count",
                style = MaterialTheme.typography.labelMedium,
                color = ZeneloColors.TextMuted,
                modifier = Modifier.padding(start = 6.dp, bottom = 3.dp),
            )
        }
    }
}

/** 40dp rounded square holding a row's leading icon. */
@Composable
fun IconTile(
    icon: ImageVector,
    tint: Color,
    background: Color = ZeneloColors.Card,
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp,
) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Two-line list row: leading slot, title, mono subtitle, trailing slot. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    titleColor: Color = ZeneloColors.TextPrimary,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = ZeneloColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

/** Mustard round play button in the bottom-right corner. Long-press for the alternate action. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayFab(onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    Box(
        modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(ZeneloColors.Mustard)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.PlayArrow, "Play", tint = ZeneloColors.OnMustard, modifier = Modifier.size(28.dp))
    }
}

/** Mustard-tinted snackbar from the design ("Added to queue · #4   Undo"). */
@Composable
fun ZeneloSnackbar(data: SnackbarData, icon: ImageVector?) {
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ZeneloColors.Mustard.copy(alpha = 0.32f).compositeOver(ZeneloColors.Bar))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = ZeneloColors.Mustard, modifier = Modifier.size(18.dp))
        Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        data.visuals.actionLabel?.let { label ->
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = ZeneloColors.Mustard,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { data.performAction() }.padding(4.dp),
            )
        }
    }
}

/**
 * Embedded cover art. Decoded off the main thread, downsampled to roughly [decodeSizePx], and
 * kept in a small memory cache so the mini player and Now Playing share work.
 * Placeholder: diagonal stripes (small) or a disc icon (large), as in the design.
 */
@Composable
fun Artwork(
    bytes: ByteArray?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 6.dp,
    decodeSizePx: Int = 720,
    largePlaceholder: Boolean = false,
    /** Cover image file, used when there are no embedded [bytes] (folder image, download). */
    file: String? = null,
) {
    val bitmap by produceState(ArtworkCache.peek(bytes, file, decodeSizePx), bytes, file, decodeSizePx) {
        // Keys changed (or first run): drop the previous track's cover, then decode if not cached.
        value = ArtworkCache.peek(bytes, file, decodeSizePx)
        if (value == null) value = ArtworkCache.decode(bytes, file, decodeSizePx)
    }
    Box(
        modifier.clip(RoundedCornerShape(cornerRadius)).background(ZeneloColors.Placeholder),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        when {
            image != null -> Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            largePlaceholder -> Icon(Icons.Rounded.Album, null, tint = ZeneloColors.TextMuted, modifier = Modifier.size(40.dp))
            else -> Box(Modifier.fillMaxSize().stripes())
        }
    }
}

private fun Modifier.stripes() = drawBehind {
    val step = 7.dp.toPx()
    val stroke = 2.5.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(ZeneloColors.TextMuted.copy(alpha = 0.25f), Offset(x, size.height), Offset(x + size.height, 0f), stroke)
        x += step
    }
}

private object ArtworkCache {
    private val cache = LruCache<String, ImageBitmap>(8)

    private fun key(bytes: ByteArray?, file: String?, sizePx: Int): String? = when {
        file != null -> "$file:${java.io.File(file).lastModified()}:$sizePx"
        bytes != null -> "${bytes.contentHashCode()}:${bytes.size}:$sizePx"
        else -> null
    }

    fun peek(bytes: ByteArray?, file: String?, sizePx: Int): ImageBitmap? = key(bytes, file, sizePx)?.let { cache.get(it) }

    suspend fun decode(source: ByteArray?, file: String?, sizePx: Int): ImageBitmap? = withContext(Dispatchers.Default) {
        val key = key(source, file, sizePx) ?: return@withContext null
        // A cover file wins over embedded bytes: it's only set when art was missing or replaced.
        val bytes = file?.let { runCatching { java.io.File(it).readBytes() }.getOrNull() } ?: source ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
            ?.also { cache.put(key, it) }
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

/** Borderless inline search input used in screen top bars. Grabs focus when shown. */
@Composable
fun SearchField(query: String, onQueryChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        if (query.isEmpty()) Text(placeholder, style = MaterialTheme.typography.titleSmall, color = ZeneloColors.TextMuted)
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleSmall.copy(color = ZeneloColors.TextPrimary),
            cursorBrush = SolidColor(ZeneloColors.Mustard),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

/** Thin design slider: 3dp track, mustard fill, small white round thumb. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZeneloSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        modifier = modifier.height(24.dp),
        thumb = { Box(Modifier.size(12.dp).clip(CircleShape).background(ZeneloColors.TextPrimary)) },
        track = {
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(ZeneloColors.Card)) {
                Box(
                    Modifier
                        .fillMaxWidth(((value - valueRange.start) / span).coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(ZeneloColors.Mustard),
                )
            }
        },
    )
}

/** Vertical gap helper used between stacked blocks. */
@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))
