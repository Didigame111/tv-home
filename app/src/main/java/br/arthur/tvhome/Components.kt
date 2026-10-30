package br.arthur.tvhome

import android.content.Context
import android.text.format.DateFormat
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onPlaced
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import coil3.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Space around each card for the focus ring, so focusing never shifts the layout. */
val Ring = 6.dp
private val ArtShape = RoundedCornerShape(9.dp)
private val Quiet = Color.White.copy(alpha = 0.12f)

/** Top-bar buttons sit over artwork that can be anything from black to white: dark glass with a hairline reads on both. */
private val Glass = Color(0xFF0E1222).copy(alpha = 0.62f)
private val GlassEdge = Color.White.copy(alpha = 0.18f)

private class Press {
    var down = false
    var long = false
}

private fun KeyEvent.isSelect() = when (nativeKeyEvent.keyCode) {
    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, AndroidKeyEvent.KEYCODE_BUTTON_A -> true
    else -> false
}

/**
 * OK clicks; holding OK (or pressing a remote's menu key) asks for options. A click only counts
 * when this element also saw the key go down, so a press that began somewhere else, such as the
 * long press that opened a menu, can't fire it on release.
 */
@Composable
fun Modifier.tvClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier {
    val press = remember { Press() }
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    return onPreviewKeyEvent { e ->
        val native = e.nativeKeyEvent
        if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU && longClick != null) {
            if (native.action == AndroidKeyEvent.ACTION_UP) longClick?.invoke()
            return@onPreviewKeyEvent true
        }
        if (!e.isSelect()) return@onPreviewKeyEvent false
        when (native.action) {
            AndroidKeyEvent.ACTION_DOWN ->
                if (native.repeatCount == 0) {
                    press.down = true
                    press.long = false
                } else if (press.down && !press.long && longClick != null) {
                    press.long = true
                    longClick?.invoke()
                }
            AndroidKeyEvent.ACTION_UP -> {
                if (press.down && !press.long) click()
                press.down = false
                press.long = false
            }
        }
        true
    }
}

/** While an app is being moved, left and right move it and OK puts it down. */
@Composable
private fun Modifier.moveKeys(active: Boolean, onMove: (Int) -> Unit, onDone: () -> Unit): Modifier {
    val press = remember { Press() }
    val move by rememberUpdatedState(onMove)
    val done by rememberUpdatedState(onDone)
    return onPreviewKeyEvent { e ->
        if (!active) return@onPreviewKeyEvent false
        val native = e.nativeKeyEvent
        val isDown = native.action == AndroidKeyEvent.ACTION_DOWN
        when {
            native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (isDown) move(-1); true }
            native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { if (isDown) move(1); true }
            native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP ||
                native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN -> true
            e.isSelect() -> {
                if (isDown && native.repeatCount == 0) press.down = true
                if (!isDown && press.down) { press.down = false; done() }
                true
            }
            else -> false
        }
    }
}

private fun DrawScope.drawRing() {
    val stroke = 3.dp.toPx()
    drawRoundRect(
        color = Lamp,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(13.5.dp.toPx()),
        style = Stroke(stroke),
    )
}

@Composable
fun AppCard(
    app: TvApp,
    artWidth: Dp,
    focusRequester: FocusRequester,
    moving: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMove: (Int) -> Unit,
    onMoveDone: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = when {
            moving -> 1.12f
            focused -> 1.08f
            else -> 1f
        },
        animationSpec = tween(150),
        label = "scale",
    )
    val labelAlpha by animateFloatAsState(if (focused || moving) 1f else 0f, tween(150), label = "label")
    val artHeight = artWidth * 9f / 16f
    // While being moved the card changes place but keeps focus, so nothing would scroll the
    // row after it. Each time it lands somewhere new, it asks to be shown.
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .width(artWidth + Ring * 2)
            .bringIntoViewRequester(bringIntoView)
            .onPlaced { if (moving) scope.launch { bringIntoView.bringIntoView() } },
    ) {
        Box(
            Modifier
                .moveKeys(moving, onMove, onMoveDone)
                .tvClickable(onClick, onLongClick)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .focusRequester(focusRequester)
                .focusable()
                .semantics {
                    contentDescription = app.label
                    role = Role.Button
                }
                .size(artWidth + Ring * 2, artHeight + Ring * 2)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .drawBehind { if (focused || moving) drawRing() }
                .padding(Ring)
                .clip(ArtShape)
                .background(Tile),
        ) {
            if (app.isBanner) {
                Image(
                    bitmap = app.art,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Medium,
                )
            } else {
                // No TV banner: the app's icon and name on a plain tile.
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        bitmap = app.art,
                        contentDescription = null,
                        modifier = Modifier.size(artHeight * 0.5f),
                        filterQuality = FilterQuality.Medium,
                    )
                    Spacer(Modifier.width(10.dp))
                    BasicText(app.label, style = TileText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        BasicText(
            text = if (moving) "◀ Moving ▶" else app.label,
            style = CardLabel.copy(color = if (moving) Lamp else Paper),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = Ring, top = 8.dp)
                .graphicsLayer { alpha = labelAlpha },
        )
    }
}

/** A card-sized tile with only words on it: "All apps", "3 hidden". */
@Composable
fun TextTile(text: String, artWidth: Dp, focusRequester: FocusRequester, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val artHeight = artWidth * 9f / 16f
    Box(
        Modifier
            .tvClickable(onClick)
            .onFocusChanged { focused = it.isFocused }
            .focusRequester(focusRequester)
            .focusable()
            .semantics { role = Role.Button }
            .size(artWidth + Ring * 2, artHeight + Ring * 2)
            .drawBehind { if (focused) drawRing() }
            .padding(Ring)
            .clip(ArtShape)
            .background(Quiet),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = TileText.copy(color = if (focused) Paper else Mist))
    }
}

/** A show or film: its art, its app's badge, a progress bar if started, and its name once focused. */
@Composable
fun ContentCard(
    item: ContentItem,
    artWidth: Dp,
    portrait: Boolean,
    detail: String?,
    badge: ImageBitmap?,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(150), label = "scale")
    val labelAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "label")
    val artHeight = if (portrait) artWidth * 1.5f else artWidth * 9f / 16f

    Column(Modifier.width(artWidth + Ring * 2)) {
        Box(
            Modifier
                .tvClickable(onClick, onLongClick)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .focusRequester(focusRequester)
                .focusable()
                .semantics {
                    contentDescription = item.title
                    role = Role.Button
                }
                .size(artWidth + Ring * 2, artHeight + Ring * 2)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .drawBehind { if (focused) drawRing() }
                .padding(Ring)
                .clip(ArtShape)
                .background(Tile),
        ) {
            // Shows through only if the art is missing or still loading.
            BasicText(
                item.title,
                style = TileText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
            )
            AsyncImage(
                model = item.imageUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.fillMaxSize(),
            )
                // Which app it opens in, on a dark plate so it reads on any artwork.
            if (badge != null) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(36.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Glass),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = badge,
                        contentDescription = null,
                        filterQuality = FilterQuality.Medium,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                }
            }
            item.progress?.let {
                ProgressBar(
                    it,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                )
            }
        }
        Column(
            Modifier
                .padding(start = Ring, top = 8.dp)
                .graphicsLayer { alpha = labelAlpha },
        ) {
            BasicText(item.title, style = CardLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(detail.orEmpty(), style = CardDetail, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(4.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0.03f, 1f))
                .height(4.dp)
                .background(Lamp),
        )
    }
}

/** Row title, with the app it comes from in a quieter voice beside it. */
@Composable
fun RowHeader(title: String, source: String? = null) {
    Row(Modifier.padding(start = EdgePad, bottom = 8.dp)) {
        BasicText(title, style = RowTitle, modifier = Modifier.alignByBaseline())
        if (source != null) {
            Spacer(Modifier.width(10.dp))
            BasicText(source, style = RowSource, modifier = Modifier.alignByBaseline())
        }
    }
}

/**
 * The spotlight's one button. Left and right step through the spotlight while it has focus;
 * with a single item they move focus as usual.
 */
@Composable
fun PlayButton(
    text: String,
    canStep: Boolean,
    rightSteps: Boolean,
    focusRequester: FocusRequester,
    onFocus: (Boolean) -> Unit,
    onStep: (Int) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val step by rememberUpdatedState(onStep)
    Row(
        Modifier
            .onPreviewKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                val horizontal = code == AndroidKeyEvent.KEYCODE_DPAD_LEFT || code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
                if (!canStep || !horizontal) return@onPreviewKeyEvent false
                // With More beside it, Right goes there first; More's own Right moves on.
                if (code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT && !rightSteps) return@onPreviewKeyEvent false
                if (e.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN) {
                    step(if (code == AndroidKeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                }
                true
            }
            .tvClickable(onClick, onLongClick)
            .onFocusChanged {
                focused = it.isFocused
                onFocus(it.isFocused)
            }
            .focusRequester(focusRequester)
            .focusable()
            .semantics { role = Role.Button }
            .height(46.dp)
            .clip(CircleShape)
            .background(if (focused) Lamp else Glass)
            .border(1.dp, if (focused) Color.Transparent else GlassEdge, CircleShape)
            .padding(start = 16.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (focused) Ink else Paper
        Image(Icons.Default.PlayArrow, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(color))
        Spacer(Modifier.width(6.dp))
        BasicText(text, style = ControlText.copy(fontSize = 16.sp, color = color))
    }
}

/** Beside Watch when a description is longer than the spotlight shows: opens all of it. */
@Composable
fun MoreButton(onClick: () -> Unit, onRight: () -> Unit, onFocus: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val right by rememberUpdatedState(onRight)
    Row(
        Modifier
            .onPreviewKeyEvent { e ->
                if (e.nativeKeyEvent.keyCode != AndroidKeyEvent.KEYCODE_DPAD_RIGHT) return@onPreviewKeyEvent false
                if (e.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN) right()
                true
            }
            .tvClickable(onClick)
            .onFocusChanged {
                focused = it.isFocused
                onFocus(it.isFocused)
            }
            .focusable()
            .semantics { role = Role.Button }
            .height(46.dp)
            .clip(CircleShape)
            .background(if (focused) Lamp else Glass)
            .border(1.dp, if (focused) Color.Transparent else GlassEdge, CircleShape)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("More", style = ControlText.copy(fontSize = 16.sp, color = if (focused) Ink else Paper))
    }
}

/** A US age rating, boxed the way TV guides and box art show it. */
@Composable
fun RatingBadge(rating: String) {
    Box(
        Modifier
            .border(1.5.dp, Paper.copy(alpha = 0.85f), RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) {
        BasicText(rating, style = RatingText)
    }
}

@Composable
fun Dots(index: Int, count: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(width = if (i == index) 18.dp else 6.dp, height = 6.dp)
                    .clip(CircleShape)
                    .background(if (i == index) Lamp else Color.White.copy(alpha = 0.3f)),
            )
        }
    }
}

/** Material's "apps" glyph: a three-by-three grid. */
val AppsGlyph: ImageVector = ImageVector.Builder("Apps", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        addPathNodes(
            "M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4zm-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z",
        ),
        fill = SolidColor(Color.White),
    )
    .build()

/** "37 min left", "1 h 5 min left"; null when the app didn't say how far in it is. */
fun timeLeft(item: ContentItem): String? {
    if (item.progress == null) return null
    val minutes = ((item.durationMs - item.positionMs) / 60_000).toInt()
    return when {
        minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60} h left"
        minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min left"
        minutes >= 1 -> "$minutes min left"
        else -> "Almost done"
    }
}

/** "S1 E2: Episode title", "S1 E2", or the episode title alone. */
fun episodeLabel(item: ContentItem): String? {
    val number = listOfNotNull(item.season?.let { "S$it" }, item.episodeNumber?.let { "E$it" })
        .joinToString(" ").ifEmpty { null }
    return when {
        number != null && item.episodeTitle != null -> "$number: ${item.episodeTitle}"
        else -> number ?: item.episodeTitle
    }
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    dim: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(150), label = "pill")
    Row(
        modifier
            .tvClickable(onClick)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .semantics { role = Role.Button }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(42.dp)
            .clip(CircleShape)
            .background(if (focused) Lamp else Glass)
            .border(1.dp, if (focused) Color.Transparent else GlassEdge, CircleShape)
            .padding(start = if (icon != null) 13.dp else 18.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = when {
            focused -> Ink
            dim -> Mist
            else -> Paper
        }
        if (icon != null) {
            Image(icon, null, Modifier.size(19.dp), colorFilter = ColorFilter.tint(color))
            Spacer(Modifier.width(8.dp))
        }
        BasicText(text, style = ControlText.copy(color = color), maxLines = 1)
    }
}

@Composable
fun RoundIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(150), label = "icon")
    Box(
        Modifier
            .tvClickable(onClick)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(42.dp)
            .clip(CircleShape)
            .background(if (focused) Lamp else Glass)
            .border(1.dp, if (focused) Color.Transparent else GlassEdge, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(icon, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(if (focused) Ink else Paper))
    }
}

@Composable
fun PanelBox(title: String, body: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .width(380.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Panel)
            .padding(24.dp),
    ) {
        BasicText(title, style = PanelTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            BasicText(body, style = PanelBody)
        }
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
fun MenuItem(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .tvClickable(onClick)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .semantics { role = Role.Button }
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Lamp else Color.Transparent)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            text,
            style = ControlText.copy(fontSize = 16.sp, color = if (focused) Ink else Paper),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(CircleShape)
            .background(Panel)
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        BasicText(text, style = ControlText.copy(color = Paper))
    }
}

/** Time and date text, shared by the home screen and the screensaver. */
object ClockFormat {
    fun time(context: Context, millis: Long): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
    }

    fun date(millis: Long): String {
        val locale = Locale.getDefault()
        return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale).format(Date(millis))
    }
}
