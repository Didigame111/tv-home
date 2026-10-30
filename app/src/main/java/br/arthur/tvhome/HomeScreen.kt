package br.arthur.tvhome

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.scale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath

// The screen is laid out on a 960 x 540 dp canvas (1080p at xhdpi).
private val AppWidth = 124.dp // six fit across
private val CardWidth = 196.dp // four and a bit
private val PosterWidth = 124.dp // six across, like the app grid
private val TopHeight = 340.dp // top bar and spotlight, above "Your apps"
private val BackdropHeight = 540.dp // the whole screen
private val SectionGap = 16.dp

/** Below the top, the focused row glides up to sit this far from the top edge. */
private val RowPivot = 30.dp
private const val SPOTLIGHT_MAX = 12
private const val HIDDEN_KEY = "\u0000hidden"
private const val ALL_APPS_KEY = "\u0000all"
private const val SPOT_KEY = "\u0000spot"
private const val ALL_APPS_TILE = "\u0000alltile"
private const val LIVE_TV_KEY = "\u0000live"

/** A long-press menu: a title, what it's about, and its actions in order. */
private class Menu(
    val title: String,
    val actions: List<Pair<String, () -> Unit>>,
    val subtitle: String? = null,
    val appArt: ImageBitmap? = null,
    val appArtIsBanner: Boolean = true,
    val artUri: String? = null,
)

@Composable
fun HomeScreen(vm: HomeViewModel) {
    setSingletonImageLoaderFactory { context ->
        // Small caches: this TV has 1.8 GB of RAM for everything.
        ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizeBytes(32L * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("art").toOkioPath()).maxSizeBytes(128L * 1024 * 1024).build() }
            .crossfade(220)
            .build()
    }

    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }

    // Wait for the frame that composes the target before focusing it.
    fun focusLater(key: String?) {
        if (key == null) return
        scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { requester(key).requestFocus() }
        }
    }

    // A package-manager call: asked once, not on every rebuild of the screen.
    val liveTvAvailable = remember { Freeplay.isAvailable(context) }

    /** A line at the bottom of the screen while Live TV opens. */
    var status by remember { mutableStateOf<String?>(null) }

    fun open(intent: Intent?, what: String) {
        try {
            context.startActivity(intent ?: error("no intent"))
        } catch (_: Exception) {
            Toast.makeText(context, "Couldn't open $what", Toast.LENGTH_SHORT).show()
        }
    }

    fun openApp(pkg: String) {
        val app = (state.favorites + state.others + state.hiddenApps).firstOrNull { it.packageName == pkg }
        if (app != null) open(vm.repo.launchIntent(app), app.label)
    }

    fun play(item: ContentItem) {
        try {
            context.startActivity(TvContentRepository.launchIntent(item) ?: error("no link"))
        } catch (_: Exception) {
            // The app doesn't recognise this title's link: open the app, so it's one search away.
            openApp(item.packageName)
        }
    }

    fun openFreeplay() {
        if (status != null) return
        Freeplay.takeOnAtStandby(context)
        Freeplay.opening = true
        scope.launch {
            status = if (Freeplay.isApproved(context)) {
                "Opening Live TV…"
            } else {
                "Live TV needs a one-time OK. On the next screen, tick “Always allow” and choose Allow."
            }
            val switchedOn = withContext(Dispatchers.IO) {
                runCatching { Freeplay.switchOn(context) }
                    .onFailure { Freeplay.log("Couldn't switch Google TV on", it) }
                    .isSuccess
            }
            if (switchedOn) {
                FreeplayWatcher.start(context)
                open(Freeplay.intent(), "Live TV")
                Freeplay.opening = false
                delay(1_500)
                status = null
            } else {
                Freeplay.opening = false
                status = "Live TV didn't open. Press it again, and choose Allow if the TV asks."
                delay(6_000)
                status = null
            }
        }
    }

    var menu by remember { mutableStateOf<Menu?>(null) }
    var moving by remember { mutableStateOf<String?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var showAllApps by remember { mutableStateOf(false) }
    /** The spotlight title opened with More, if any. */
    var details by remember { mutableStateOf<ContentItem?>(null) }
    /** Where Back from All apps returns: the top bar's button or the tile it was opened from. */
    var allAppsFrom by remember { mutableStateOf(ALL_APPS_TILE) }

    // Spotlight: the most recent things to resume, one at a time.
    val spotlight = state.recommendations.take(SPOTLIGHT_MAX)
    var spotIndex by remember { mutableIntStateOf(0) }
    if (spotIndex >= spotlight.size) spotIndex = 0
    val spotItem = spotlight.getOrNull(spotIndex)
    var spotFocused by remember { mutableStateOf(false) }
    // On the spotlight's buttons the slide takes the whole screen, as on Google TV: the rows
    // below step away and the art shows in full. Read only in layers and drawing, so the
    // transition doesn't rebuild the screen.
    val immersion by animateFloatAsState(if (spotFocused) 1f else 0f, tween(420, easing = FastOutSlowInEasing), label = "immersion")

    // Sections, top to bottom. The page isn't a scroll container: it glides so the focused
    // section sits at a fixed height, and what's above it fades away.
    val order = buildList {
        add("top")
        add("apps")
        if (state.continueWatching.isNotEmpty()) add("continue")
        state.rows.forEach { row -> repeat((row.items.size + 5) / 6) { add("row:${row.key}:$it") } }
    }
    var section by remember { mutableStateOf("apps") }
    val atTop = section == "top" || section == "apps"
    val focusedIndex = order.indexOf(section)
    val sectionTops = remember { mutableStateMapOf<String, Float>() }
    var pageHeight by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val offset = remember { Animatable(0f) }
    LaunchedEffect(section, sectionTops[section], pageHeight, viewportHeight) {
        val target = if (atTop) {
            0f
        } else {
            val pivot = with(density) { RowPivot.toPx() }
            val max = (pageHeight - viewportHeight).coerceAtLeast(0).toFloat()
            ((sectionTops[section] ?: 0f) - pivot).coerceIn(0f, max)
        }
        offset.animateTo(target, tween(320, easing = FastOutSlowInEasing))
    }

    // The spotlight's art belongs to the top of the page: it fades out as soon as you head down
    // into the rows, so nothing sits behind them.
    val backdropAlpha by animateFloatAsState(if (atTop) 1f else 0f, tween(300), label = "backdrop")

    // The page takes a deep shade of the featured art. The fade is read only while drawing,
    // so it repaints the background without rebuilding the screen on every frame.
    var artColor by remember { mutableStateOf<Color?>(null) }
    val background = animateColorAsState(
        targetValue = if (spotItem != null) artColor ?: DuskTop else DuskTop,
        animationSpec = tween(450),
        label = "background",
    )

    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()

    // Switched on again after standby mid-Live TV: start on Live TV, one OK away. The screen also
    // resumes while it's going off (TV Home is brought forward then), so wait for it to be on.
    var offerLiveTv by remember { mutableStateOf(false) }
    var liveTvFocused by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycleState, state.loaded) {
        if (!liveTvAvailable || !state.loaded || !lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        val power = context.getSystemService(PowerManager::class.java)
        repeat(10) { if (!power.isInteractive) delay(200) }
        if (!power.isInteractive || !Freeplay.takeOnAtStandby(context)) return@LaunchedEffect
        // After Home's own move to the first app has landed.
        delay(400)
        focusLater(LIVE_TV_KEY)
        offerLiveTv = true
        delay(10_000)
        offerLiveTv = false
    }
    LaunchedEffect(spotIndex, spotFocused, spotlight.size, atTop, moving, lifecycleState) {
        // Holds still while you're on it or rearranging apps under it.
        if (spotlight.size > 1 && !spotFocused && moving == null && atTop && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
            delay(10_000)
            spotIndex = (spotIndex + 1) % spotlight.size
        }
    }

    fun firstKey(s: HomeState) =
        s.favorites.firstOrNull()?.packageName ?: s.others.firstOrNull()?.packageName ?: ALL_APPS_KEY

    // Which card has focus. Not state: it changes on every D-pad move and nothing draws from it.
    val focusedKey = remember { arrayOfNulls<String>(1) }
    LaunchedEffect(state) {
        // A refresh can take away the card that had focus (an episode finished, an app was
        // uninstalled). Then nothing would be focused, so land on the first app instead.
        val live = HashSet<String>().apply {
            (state.favorites + state.others + state.hiddenApps).forEach { add(it.packageName) }
            state.continueWatching.forEach { add(it.key) }
            state.recommendations.forEach { add(it.key) }
            state.rows.forEach { row -> row.items.forEach { add(it.key) } }
        }
        requesters.keys.retainAll { it.startsWith("\u0000") || it in live }
        val key = focusedKey[0] ?: return@LaunchedEffect
        // Buttons (the spotlight's, the top bar's) never disappear; only cards can.
        if (key.startsWith("\u0000")) return@LaunchedEffect
        val present = state.favorites.any { it.packageName == key } || state.others.any { it.packageName == key } ||
            state.continueWatching.any { it.key == key } || state.rows.any { row -> row.items.any { it.key == key } }
        if (!present) {
            focusedKey[0] = null
            focusLater(firstKey(state))
        }
    }

    var tip by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.loaded) {
        if (!state.loaded) return@LaunchedEffect
        focusLater(firstKey(state))
        // Holding OK is the one gesture nobody guesses, so say it the first few times.
        if (vm.repo.prefs.takeTip()) {
            delay(1_500)
            tip = "Hold OK on any app or show for more options."
            delay(7_000)
            tip = null
        }
    }
    LaunchedEffect(Unit) {
        vm.homePressed.collect {
            val closingDialog = menu != null || showHidden
            menu = null
            details = null
            showHidden = false
            showAllApps = false
            moving = null
            spotIndex = 0
            // Focus can only land once the dialog's window has gone and the screen has focus again.
            if (closingDialog) delay(350)
            focusLater(firstKey(vm.state.value))
        }
    }

    // Back never leaves the home screen. It puts down an app being moved, or goes back to the
    // first app, wherever you are.
    BackHandler {
        when {
            moving != null -> moving = null
            // All apps is a page of its own: Back returns to the home screen, to its tile.
            showAllApps -> {
                showAllApps = false
                focusLater(allAppsFrom)
            }
            else -> focusLater(firstKey(state))
        }
    }

    fun appMenu(app: TvApp) {
        val isFavorite = state.favorites.any { it.packageName == app.packageName }
        menu = Menu(
            title = app.label,
            appArt = app.art,
            appArtIsBanner = app.isBanner,
            actions = listOf(
                "Move" to {
                    moving = app.packageName
                    focusLater(app.packageName)
                },
                (if (isFavorite) "Remove from your apps" else "Add to your apps") to {
                    vm.toggleFavorite(app.packageName)
                    focusLater(app.packageName)
                },
                "Hide from home" to {
                    val next = neighbour(state, app.packageName) ?: ALL_APPS_KEY
                    vm.hide(app.packageName)
                    focusLater(next)
                },
                "App info" to {
                    focusLater(app.packageName)
                    open(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        "App info",
                    )
                },
            ),
        )
    }

    fun continueMenu(item: ContentItem, fromSpotlight: Boolean = false) {
        val appLabel = state.labels[item.packageName] ?: "app"
        menu = Menu(
            title = item.title,
            subtitle = listOfNotNull(timeLeft(item), "on $appLabel").joinToString(" ").replaceFirstChar { it.uppercase() },
            artUri = item.imageUri,
            actions = listOf(
                "Open $appLabel" to { openApp(item.packageName) },
                "Remove from Continue watching" to {
                    // Stay where you were: on the spotlight's button, or on the next show along
                    // the row (the previous one if this was the last), or the first app if it's empty.
                    val list = state.continueWatching
                    val i = list.indexOfFirst { it.key == item.key }
                    val next = when {
                        fromSpotlight && list.size > 1 -> SPOT_KEY
                        else -> (list.getOrNull(i + 1) ?: list.getOrNull(i - 1))?.key ?: firstKey(state)
                    }
                    // Hand the focus record over first, so losing the removed card doesn't
                    // send focus back to the first app.
                    focusedKey[0] = next
                    vm.hideContent(item)
                    // Only once the menu's window has gone can the row take focus back;
                    // sooner, and the screen re-focuses on its own when the window closes.
                    scope.launch {
                        delay(350)
                        runCatching { requester(next).requestFocus() }
                    }
                },
            ),
        )
    }

    fun openAllApps(from: String) {
        allAppsFrom = from
        showAllApps = true
        focusLater((state.favorites + state.others).firstOrNull()?.packageName ?: HIDDEN_KEY)
    }

    fun spotlightMenu(item: ContentItem) {
        val appLabel = state.labels[item.packageName] ?: "app"
        menu = Menu(
            title = item.title,
            subtitle = "Recommended by $appLabel",
            artUri = item.imageUri,
            actions = listOf("Open $appLabel" to { openApp(item.packageName) }),
        )
    }

    fun rowMenu(row: ContentRow, item: ContentItem) {
        val appLabel = state.labels[item.packageName] ?: "app"
        menu = Menu(
            title = item.title,
            subtitle = "On $appLabel",
            artUri = item.imageUri,
            actions = listOf(
                "Open $appLabel" to { openApp(item.packageName) },
                "Hide the ${row.title} row" to {
                    vm.hideRow(row.key)
                    focusLater(firstKey(state))
                },
            ),
        )
    }

    fun openInput(input: TvInput) {
        try {
            context.startActivity(vm.repo.inputIntent(input))
        } catch (_: Exception) {
            // Some TV players won't take a channel list; open the player itself instead.
            open(if (input.isTuner) vm.repo.tvPlayerIntent() else null, input.label)
        }
    }

    fun inputsMenu() {
        menu = Menu(
            title = "Inputs",
            subtitle = "Watch the antenna, or something plugged into the TV.",
            actions = state.inputs.map { input -> input.label to { openInput(input) } },
        )
    }

    @Composable
    fun Section(key: String, content: @Composable ColumnScope.() -> Unit) {
        // Sections above the focused one step out of the way once you're below the top.
        val above = !atTop && order.indexOf(key) in 0 until focusedIndex
        val alpha by animateFloatAsState(if (above) 0f else 1f, tween(200), label = "section")
        val stepsAside = key != "top"
        Column(
            Modifier
                .onPlaced { sectionTops[key] = it.positionInParent().y }
                .onFocusChanged { if (it.hasFocus) section = key }
                .graphicsLayer {
                    // Everything below the spotlight fades and drifts down while it's full screen.
                    this.alpha = alpha * (if (stepsAside) 1f - immersion else 1f)
                    translationY = if (stepsAside) immersion * 90.dp.toPx() else 0f
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
            content = content,
        )
    }

    @Composable
    fun Card(app: TvApp) = AppCard(
        app = app,
        artWidth = AppWidth,
        focusRequester = requester(app.packageName),
        moving = moving == app.packageName,
        onFocused = { focusedKey[0] = app.packageName },
        onClick = { open(vm.repo.launchIntent(app), app.label) },
        onLongClick = { appMenu(app) },
        onMove = {
            vm.move(app.packageName, it)
            focusLater(app.packageName) // a move across grid rows rebuilds the card
        },
        onMoveDone = { moving = null },
    )

    @Composable
    fun ContentRowView(items: List<ContentItem>, portrait: Boolean, detail: (ContentItem) -> String?, onLong: (ContentItem) -> Unit) {
        // No focus memory: when a card is removed it would send focus back to the row's start.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = EdgePad - Ring),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items.forEach { item ->
                key(item.key) {
                    ContentCard(
                        item = item,
                        artWidth = if (portrait) PosterWidth else CardWidth,
                        portrait = portrait,
                        detail = detail(item),
                        badge = state.icons[item.packageName],
                        focusRequester = requester(item.key),
                        onFocused = { focusedKey[0] = item.key },
                        onClick = { play(item) },
                        onLongClick = { onLong(item) },
                    )
                }
            }
        }
    }

    /** Every app, six to a row, on a page of its own. */
    @Composable
    fun AllAppsPage() {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 27.dp, bottom = 120.dp),
        ) {
            Row(Modifier.padding(start = EdgePad, end = EdgePad, bottom = 20.dp)) {
                BasicText("All apps", style = HeroTitle, modifier = Modifier.alignByBaseline())
                Spacer(Modifier.width(18.dp))
                BasicText("Press Back for the home screen", style = HeroSource, modifier = Modifier.alignByBaseline())
            }
            val grid: List<TvApp?> = state.favorites + state.others + if (state.hiddenCount > 0) listOf(null) else emptyList()
            val chunks = grid.chunked(6)
            chunks.forEachIndexed { i, chunk ->
                // The last row is usually shorter. From the right-hand side of the row above
                // there's nothing straight below, so Down would go nowhere: land on the last
                // item of the next row instead.
                val nextLast = chunks.getOrNull(i + 1)?.last()?.packageName ?: HIDDEN_KEY
                Row(
                    Modifier
                        .onPreviewKeyEvent { e ->
                            val down = e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN &&
                                e.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN
                            if (!down || moving != null || i == chunks.lastIndex) return@onPreviewKeyEvent false
                            if (!focusManager.moveFocus(FocusDirection.Down)) focusLater(nextLast)
                            true
                        }
                        .padding(start = EdgePad - Ring, end = EdgePad - Ring, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    chunk.forEach { app ->
                        if (app != null) {
                            key(app.packageName) { Card(app) }
                        } else {
                            TextTile("Show ${state.hiddenCount} hidden", AppWidth, requester(HIDDEN_KEY)) { showHidden = true }
                        }
                    }
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(background.value) }
            .clipToBounds()
            .onSizeChanged { viewportHeight = it.height },
    ) {
        if (showAllApps) {
            AllAppsPage()
        } else {
        if (spotItem != null) {
            Backdrop(
                spotItem,
                background = { background.value },
                immersion = { immersion },
                modifier = Modifier.graphicsLayer {
                    alpha = backdropAlpha
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
                onArtColor = { artColor = it },
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .graphicsLayer { translationY = -offset.value }
                .onSizeChanged { pageHeight = it.height },
        ) {
            Section("top") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(TopHeight),
                ) {
                    TopBar(
                        showClock = spotItem != null,
                        allAppsRequester = requester(ALL_APPS_KEY),
                        onSearch = { open(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "search") },
                        onAllApps = { openAllApps(ALL_APPS_KEY) },
                        onLiveTv = if (liveTvAvailable) ::openFreeplay else null,
                        liveTvModifier = Modifier
                            .focusRequester(requester(LIVE_TV_KEY))
                            .onFocusChanged { liveTvFocused = it.isFocused },
                        onInputs = if (state.inputs.isNotEmpty()) ::inputsMenu else null,
                        onSettings = { open(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Settings") },
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = EdgePad, bottom = 22.dp)
                            .graphicsLayer { translationY = immersion * 150.dp.toPx() },
                    ) {
                        if (spotItem == null) {
                            BigClock()
                        } else {
                            Spotlight(
                                item = spotItem,
                                appLabel = state.labels[spotItem.packageName],
                                appIcon = state.icons[spotItem.packageName],
                                index = spotIndex,
                                count = spotlight.size,
                                active = spotFocused,
                                focusRequester = requester(SPOT_KEY),
                                onFocus = { spotFocused = it },
                                onStep = { spotIndex = (spotIndex + it).mod(spotlight.size) },
                                onPlay = { play(spotItem) },
                                onMore = { details = spotItem },
                                onLongClick = { spotlightMenu(spotItem) },
                            )
                        }
                    }
                }
            }

            if (state.favorites.isNotEmpty()) {
                Section("apps") {
                    RowHeader("Your apps")
                    // No focus memory here (unlike the show rows): Back and Home always mean the first app.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = EdgePad - Ring),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        state.favorites.forEach { app -> key(app.packageName) { Card(app) } }
                        // At the end of the row, the way into every other app.
                        TextTile("All apps", AppWidth, requester(ALL_APPS_TILE)) { openAllApps(ALL_APPS_TILE) }
                    }
                }
            }

            if (state.continueWatching.isNotEmpty()) {
                Spacer(Modifier.height(SectionGap))
                Section("continue") {
                    RowHeader("Continue watching")
                    ContentRowView(
                        state.continueWatching,
                        portrait = false,
                        detail = { item ->
                            val app = state.labels[item.packageName].orEmpty()
                            val left = timeLeft(item)
                            when {
                                item.isNextEpisode -> "Next episode on $app"
                                left != null -> "$left on $app"
                                else -> "On $app"
                            }
                        },
                        onLong = { continueMenu(it) },
                    )
                }
            }

            state.rows.forEach { row ->
                row.items.chunked(6).forEachIndexed { i, chunk ->
                    key(row.key, i) {
                        if (i == 0) Spacer(Modifier.height(SectionGap))
                        Section("row:${row.key}:$i") {
                            if (i == 0) RowHeader(row.title)
                            Row(
                                Modifier.padding(start = EdgePad - Ring, end = EdgePad - Ring, bottom = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                chunk.forEach { item ->
                                    key(item.key) {
                                        ContentCard(
                                            item = item,
                                            artWidth = PosterWidth,
                                            portrait = true,
                                            detail = "On ${state.labels[item.packageName].orEmpty()}",
                                            badge = state.icons[item.packageName],
                                            focusRequester = requester(item.key),
                                            onFocused = { focusedKey[0] = item.key },
                                            onClick = { play(item) },
                                            onLongClick = { rowMenu(row, item) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Room below the last row, so even it can glide up to the top like the others.
            Spacer(Modifier.height(400.dp))
        }
        }

        val hint = status ?: when {
            moving != null -> "Use left and right to move it. Press OK when it's in place."
            offerLiveTv && liveTvFocused -> "Live TV was on. Press OK to go back to it."
            else -> tip
        }
        if (hint != null) {
            Hint(
                hint,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
            )
        }
    }

    details?.let { item ->
        DetailsView(
            item = item,
            appLabel = state.labels[item.packageName].orEmpty(),
            appIcon = state.icons[item.packageName],
            onWatch = {
                details = null
                play(item)
            },
            onClose = {
                details = null
                focusLater(SPOT_KEY)
            },
        )
    }

    menu?.let { m ->
        Dialog(
            onDismissRequest = { menu = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val first = remember { FocusRequester() }
            MenuPanel(m, first) { action ->
                menu = null
                action()
            }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                runCatching { first.requestFocus() }
            }
        }
    }

    if (showHidden) {
        Dialog(
            onDismissRequest = {
                showHidden = false
                focusLater(HIDDEN_KEY)
            },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val first = remember { FocusRequester() }
            val entries = state.hiddenApps.map { app -> Triple(app.packageName, app.label) { vm.unhide(app.packageName) } } +
                state.hiddenRows.map { row -> Triple(row.key, "${row.title} row") { vm.unhideRow(row.key) } } +
                state.hiddenShows.map { show ->
                    val app = state.labels[show.packageName].orEmpty()
                    Triple(show.key, "${show.title} on $app") { vm.unhideContent(show) }
                }
            PanelBox("Hidden from home", "Choose one to put it back.") {
                Column(
                    Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    entries.forEachIndexed { i, (k, label, restore) ->
                        key(k) {
                            MenuItem(label, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                                if (entries.size <= 1) {
                                    showHidden = false
                                    focusLater(if (state.hiddenApps.any { it.packageName == k }) k else firstKey(state))
                                }
                                restore()
                            }
                        }
                    }
                }
            }
            LaunchedEffect(entries.size) {
                withFrameNanos { }
                runCatching { first.requestFocus() }
            }
        }
    }
}

/** Where focus should land once [pkg] leaves its row: the next app, else the previous one. */
private fun neighbour(s: HomeState, pkg: String): String? {
    for (list in listOf(s.favorites, s.others)) {
        val i = list.indexOfFirst { it.packageName == pkg }
        if (i >= 0) {
            val near = list.getOrNull(i + 1) ?: list.getOrNull(i - 1)
            if (near != null) return near.packageName
        }
    }
    return null
}

/** The featured art, filling the top right and fading into the page on the left and below. */
@Composable
private fun Backdrop(item: ContentItem, background: () -> Color, immersion: () -> Float, modifier: Modifier, onArtColor: (Color) -> Unit) {
    val context = LocalContext.current
    Box(
        modifier
            .fillMaxWidth()
            .height(BackdropHeight)
            .clipToBounds(),
    ) {
        FadingArt(
            uri = item.imageUri,
            onShade = onArtColor,
            // Wider and taller than the area, anchored top right: the bottom of the art,
            // where apps print their titles, falls under the fade or off the edge.
            modifier = Modifier
                .align(Alignment.TopEnd)
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        )
        // The art fills the screen. Only light fades where words sit: the left behind the title,
        // the top behind the bar, and the bottom where the rows begin.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val bg = background()
                    drawRect(
                        Brush.horizontalGradient(
                            0f to bg.copy(alpha = 0.88f),
                            0.28f to bg.copy(alpha = 0.55f),
                            0.55f to Color.Transparent,
                        ),
                    )
                    // Full screen, the bottom only darkens enough to settle the picture's edge.
                    val full = immersion()
                    drawRect(
                        Brush.verticalGradient(
                            0f to bg.copy(alpha = 0.7f),
                            0.17f to Color.Transparent,
                            (0.48f + 0.3f * full) to Color.Transparent,
                            (0.6f + 0.3f * full) to bg.copy(alpha = 0.8f - 0.35f * full),
                            1f to bg.copy(alpha = 1f - 0.45f * full),
                        ),
                    )
                },
        )
    }
}

/**
 * Full-width art that changes without a blank moment: the previous picture stays underneath
 * until the new one has loaded, then the new one fades in. The fade multiplies alpha per draw
 * instead of compositing off-screen, which this TV's GPU can't afford at this size.
 */
@Composable
private fun FadingArt(uri: String?, onShade: (Color) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var under by remember { mutableStateOf<String?>(null) }
    var over by remember { mutableStateOf(uri) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(uri) {
        if (uri == over) return@LaunchedEffect
        under = over
        over = uri
        fade.snapTo(0f)
    }
    fun request(data: String?) = ImageRequest.Builder(context)
        .data(data)
        // Drawn ~1600 px wide; a little soft under the fades is worth the memory on a 1.8 GB TV.
        .size(1280, 720) // it fills the screen now
        .allowHardware(false) // read back for the page colour
        .crossfade(false)
        .build()
    Box(modifier) {
        under?.let { AsyncImage(request(it), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        AsyncImage(
            model = request(over),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onSuccess = { s ->
                val art = s.result.image.toBitmap()
                scope.launch {
                    launch { onShade(withContext(Dispatchers.Default) { shadeOf(art) }) }
                    fade.animateTo(1f, tween(320))
                    under = null
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = fade.value
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        )
    }
}

/** A deep, quiet version of the art's average colour, for the page behind it. */
private fun shadeOf(art: Bitmap): Color {
    val small = art.scale(12, 7)
    var r = 0L
    var g = 0L
    var b = 0L
    for (x in 0 until small.width) for (y in 0 until small.height) {
        val p = small.getPixel(x, y)
        r += (p shr 16) and 0xFF
        g += (p shr 8) and 0xFF
        b += p and 0xFF
    }
    val n = small.width * small.height
    val hsl = FloatArray(3)
    ColorUtils.RGBToHSL((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsl)
    hsl[1] = hsl[1].coerceAtMost(0.42f)
    hsl[2] = 0.13f
    return Color(ColorUtils.HSLToColor(hsl))
}

@Composable
private fun Spotlight(
    item: ContentItem,
    appLabel: String?,
    appIcon: ImageBitmap?,
    index: Int,
    count: Int,
    active: Boolean,
    focusRequester: FocusRequester,
    onFocus: (Boolean) -> Unit,
    onStep: (Int) -> Unit,
    onPlay: () -> Unit,
    onMore: () -> Unit,
    onLongClick: () -> Unit,
) {
    // Whether the description runs past the two lines the spotlight gives it.
    var cutOff by remember(item.key) { mutableStateOf(false) }
    // Watch and More report focus separately and are combined here: More reports "not
    // focused" the moment it appears, which must not switch the spotlight off.
    var watchFocused by remember { mutableStateOf(false) }
    var moreFocused by remember { mutableStateOf(false) }
    LaunchedEffect(watchFocused, moreFocused) { onFocus(watchFocused || moreFocused) }
    Column(Modifier.width(500.dp)) {
        // Only the words change between items; the buttons keep their focus.
        Crossfade(targetState = item, animationSpec = tween(400), label = "spotlight") { shown ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    appIcon?.let {
                        Image(
                            it,
                            contentDescription = null,
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(7.dp)),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    BasicText(appLabel.orEmpty(), style = HeroSource)
                    shown.rating?.let {
                        Spacer(Modifier.width(12.dp))
                        RatingBadge(it)
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Long titles step down a size, so two lines still clear the top bar.
                val titleStyle = if (shown.title.length > 22) HeroTitle.copy(fontSize = 42.sp, lineHeight = 44.sp) else HeroTitle
                var titleLines by remember(shown.key) { mutableIntStateOf(1) }
                BasicText(
                    shown.title,
                    style = titleStyle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { titleLines = it.lineCount },
                )
                episodeLabel(shown)?.let {
                    Spacer(Modifier.height(4.dp))
                    BasicText(it, style = HeroMeta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // The description opens when you're on the slide (Watch or More focused), and
                // folds away again when you move on: it answers the press, it isn't decoration.
                AnimatedVisibility(
                    visible = active && shown.description != null,
                    enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(150)),
                ) {
                    BasicText(
                        shown.description.orEmpty(),
                        style = HeroBody,
                        // A two-line title leaves room for one line here, clear of the top bar.
                        maxLines = if (titleLines > 1) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (shown.key == item.key) cutOff = it.hasVisualOverflow },
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        val showMore = active && cutOff
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayButton(
                text = if (item.progress != null) "Resume" else "Watch",
                canStep = count > 1,
                rightSteps = !showMore,
                focusRequester = focusRequester,
                onFocus = { watchFocused = it },
                onStep = onStep,
                onClick = onPlay,
                onLongClick = onLongClick,
            )
            if (showMore) {
                Spacer(Modifier.width(12.dp))
                MoreButton(
                    onClick = onMore,
                    // Right from More is the next slide; focus goes home to Watch first, since
                    // the next slide may not have a More.
                    onRight = {
                        runCatching { focusRequester.requestFocus() }
                        onStep(1)
                    },
                    onFocus = { moreFocused = it },
                )
            }
            item.progress?.let {
                Spacer(Modifier.width(18.dp))
                ProgressBar(it, Modifier.width(110.dp))
                Spacer(Modifier.width(10.dp))
                BasicText(timeLeft(item).orEmpty(), style = HeroMeta)
            }
            // The dots only matter while you're stepping through; idle, they're just clutter.
            if (count > 1) {
                Spacer(Modifier.width(22.dp))
                val dotsAlpha by animateFloatAsState(if (active) 1f else 0f, tween(250), label = "dots")
                Dots(index, count, Modifier.graphicsLayer { alpha = dotsAlpha })
            }
        }
    }
}

/**
 * Everything about one spotlight title, opened with More: the art filling the screen, the
 * service and rating, the full description, and a way to watch. Back closes it.
 */
@Composable
private fun DetailsView(item: ContentItem, appLabel: String, appIcon: ImageBitmap?, onWatch: () -> Unit, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val watch = remember { FocusRequester() }
        Box(
            Modifier
                .fillMaxSize()
                .background(Ink),
        ) {
            AsyncImage(
                model = item.imageUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Left two-thirds darkened for reading; the right keeps the picture.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Ink.copy(alpha = 0.94f),
                            0.45f to Ink.copy(alpha = 0.82f),
                            0.75f to Color.Transparent,
                        ),
                    ),
            )
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = EdgePad, end = EdgePad)
                    .width(560.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    appIcon?.let {
                        Image(
                            it,
                            contentDescription = null,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    BasicText(appLabel, style = HeroSource.copy(fontSize = 21.sp))
                    item.rating?.let {
                        Spacer(Modifier.width(14.dp))
                        RatingBadge(it)
                    }
                }
                Spacer(Modifier.height(10.dp))
                BasicText(item.title, style = DetailsTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(16.dp))
                BasicText(
                    item.description.orEmpty(),
                    style = DetailsBody,
                    maxLines = 9,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(26.dp))
                PlayButton(
                    text = "Watch on $appLabel",
                    canStep = false,
                    rightSteps = false,
                    focusRequester = watch,
                    onFocus = {},
                    onStep = {},
                    onClick = onWatch,
                    onLongClick = {},
                )
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { watch.requestFocus() }
        }
    }
}


@Composable
private fun TopBar(
    showClock: Boolean,
    allAppsRequester: FocusRequester,
    onSearch: () -> Unit,
    onAllApps: () -> Unit,
    onLiveTv: (() -> Unit)?,
    liveTvModifier: Modifier,
    onInputs: (() -> Unit)?,
    onSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = EdgePad, end = EdgePad, top = 27.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("Search", icon = Icons.Default.Search, onClick = onSearch)
            Pill("All apps", icon = AppsGlyph, modifier = Modifier.focusRequester(allAppsRequester), onClick = onAllApps)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onLiveTv != null) Pill("Live TV", modifier = liveTvModifier, onClick = onLiveTv)
            if (onInputs != null) Pill("Inputs", onClick = onInputs)
            RoundIcon(Icons.Default.Settings, "Settings", onSettings)
            if (showClock) {
                Spacer(Modifier.width(8.dp))
                BasicText(ClockFormat.time(LocalContext.current, rememberNow()), style = TopClock)
            }
        }
    }
}

/** A long-press menu: what it's about (with its art, so it's clear), then the actions. */
@Composable
private fun MenuPanel(menu: Menu, first: FocusRequester, onPick: (() -> Unit) -> Unit) {
    Column(
        Modifier
            .width(420.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Panel)
            .padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val art = Modifier
                .size(128.dp, 72.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Tile)
            when {
                menu.appArt != null -> Image(
                    menu.appArt,
                    contentDescription = null,
                    contentScale = if (menu.appArtIsBanner) ContentScale.Crop else ContentScale.Fit,
                    modifier = art,
                )
                menu.artUri != null -> AsyncImage(menu.artUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = art)
            }
            if (menu.appArt != null || menu.artUri != null) Spacer(Modifier.width(16.dp))
            Column {
                BasicText(menu.title, style = PanelTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                menu.subtitle?.let {
                    Spacer(Modifier.height(4.dp))
                    BasicText(it, style = PanelBody, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        menu.actions.forEachIndexed { i, (label, action) ->
            MenuItem(label, if (i == 0) Modifier.focusRequester(first) else Modifier) { onPick(action) }
        }
    }
}

/** The current time, updated on the minute while the home screen can be seen. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(60_000 - now % 60_000 + 50)
            }
        }
    }
    return now
}

/** With nothing to resume, the top of the screen is just the time. */
@Composable
private fun BigClock() {
    val context = LocalContext.current
    val now = rememberNow()
    Column {
        // Digits have no descenders, so the font's space below them is cut to tuck the date in.
        BasicText(
            ClockFormat.time(context, now),
            style = ClockText,
            modifier = Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height - 20.dp.roundToPx()) { placeable.place(0, 0) }
            },
        )
        BasicText(ClockFormat.date(now), style = DateText, modifier = Modifier.padding(start = 3.dp))
    }
}
