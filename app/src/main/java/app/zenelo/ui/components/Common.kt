package app.zenelo.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    cornerRadius: Dp = 8.dp,
) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(cornerRadius)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/**
 * Leading cover for a track row: the cached thumbnail, or a note icon until one loads (or if there
 * is none). The playing track gets a waveform over its cover.
 */
@Composable
fun TrackThumb(path: String, isCurrent: Boolean = false) {
    val thumbnails = appContainer().thumbnails
    // Revision: a cover was just picked / written somewhere; re-read this row's thumbnail.
    val revision by thumbnails.revision.collectAsStateWithLifecycle()
    val bitmap by produceState(thumbnails.peek(path), path, revision) { value = thumbnails.peek(path) ?: thumbnails.load(path) }
    val image = bitmap
    if (image == null) {
        if (isCurrent) IconTile(Icons.Rounded.GraphicEq, ZeneloColors.Celadon, ZeneloColors.CeladonTint)
        else IconTile(Icons.Outlined.MusicNote, ZeneloColors.TextSecondary)
        return
    }
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        Image(image.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (isCurrent) {
            Box(Modifier.fillMaxSize().background(ZeneloColors.Background.copy(alpha = 0.55f)))
            Icon(Icons.Rounded.GraphicEq, null, tint = ZeneloColors.Celadon, modifier = Modifier.size(18.dp))
        }
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
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, maxLines = 1, modifier = Modifier.marquee())
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

/** ← of a sub-page header. */
@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.size(20.dp))
    }
}

/** Check mark of a row in selection mode; the side is a setting ([app.zenelo.data.settings.SelectionMarkerSide]). */
@Composable
fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    Icon(
        if (selected) Icons.Rounded.CheckCircle else Icons.Outlined.Circle,
        if (selected) "Selected" else "Not selected",
        tint = if (selected) ZeneloColors.Mustard else ZeneloColors.TextMuted,
        modifier = modifier.size(20.dp),
    )
}

/**
 * Select all / deselect all button icon: a check in a square. Select all: accent square with the
 * check cut out; deselect all ([allSelected]): white check in a white outline.
 */
@Composable
fun SelectAllIcon(allSelected: Boolean) {
    if (allSelected) {
        Box(
            Modifier
                .size(17.dp)
                .border(1.8.dp, ZeneloColors.TextPrimary, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, "Deselect all", tint = ZeneloColors.TextPrimary, modifier = Modifier.size(14.dp))
        }
    } else {
        Icon(Icons.Rounded.CheckBox, "Select all", tint = ZeneloColors.Mustard, modifier = Modifier.size(22.dp))
    }
}

/** Mustard round play button in the bottom-right corner. Long-press for the alternate action. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    icon: ImageVector = Icons.Rounded.PlayArrow,
    description: String = "Play",
) {
    Box(
        modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(ZeneloColors.Mustard)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = ZeneloColors.OnMustard, modifier = Modifier.size(28.dp))
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

/**
 * Thin design slider: 3dp track, mustard fill, 12dp white thumb, drawn on one canvas so the thumb
 * sits exactly on the track's centre line. Tap or drag to seek.
 */
@Composable
fun ZeneloSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val onChange by rememberUpdatedState(onValueChange)
    val onFinished by rememberUpdatedState(onValueChangeFinished)
    val range by rememberUpdatedState(valueRange)
    val radius = 6.dp

    // Only a tap or a horizontal drag moves it. A touch that turns into a vertical gesture (list
    // scroll, swiping Now Playing down) is left to that gesture and changes nothing: seeking or
    // setting a value where the finger first landed used to rewind tracks / turn on crossfade.
    fun PointerInputScope.valueAt(x: Float): Float {
        val r = radius.toPx()
        val f = ((x - r) / (size.width - 2 * r)).coerceIn(0f, 1f)
        return range.start + f * (range.endInclusive - range.start).coerceAtLeast(0f)
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onChange(valueAt(offset.x))
                    onFinished?.invoke()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> onChange(valueAt(offset.x)) },
                    onDragEnd = { onFinished?.invoke() },
                    onDragCancel = { onFinished?.invoke() },
                ) { change, _ ->
                    change.consume()
                    onChange(valueAt(change.position.x))
                }
            },
    ) {
        val r = radius.toPx()
        val y = size.height / 2
        val stroke = 3.dp.toPx()
        val x = r + (size.width - 2 * r) * fraction
        drawLine(ZeneloColors.Card, Offset(r, y), Offset(size.width - r, y), stroke, StrokeCap.Round)
        drawLine(ZeneloColors.Mustard, Offset(r, y), Offset(x, y), stroke, StrokeCap.Round)
        drawCircle(ZeneloColors.TextPrimary, r, Offset(x, y))
    }
}

/** Scrolls single-line text that doesn't fit, in a loop with a short pause. Short text stays still. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.marquee(): Modifier = basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500, repeatDelayMillis = 1500)

/** Vertical gap helper used between stacked blocks. */
@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))
