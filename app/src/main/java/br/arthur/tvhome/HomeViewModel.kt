package br.arthur.tvhome

import android.app.Application
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeState(
    val favorites: List<TvApp> = emptyList(),
    val others: List<TvApp> = emptyList(),
    val hiddenApps: List<TvApp> = emptyList(),
    val inputs: List<TvInput> = emptyList(),
    val continueWatching: List<ContentItem> = emptyList(),
    /** The spotlight: what the streaming apps recommend (no YouTube videos). */
    val recommendations: List<ContentItem> = emptyList(),
    val rows: List<ContentRow> = emptyList(),
    val hiddenRows: List<ContentRow> = emptyList(),
    /** Shows taken out of Continue watching, so they can be put back. */
    val hiddenShows: List<ContentItem> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
    val icons: Map<String, ImageBitmap> = emptyMap(),
    val loaded: Boolean = false,
) {
    val hiddenCount get() = hiddenApps.size + hiddenRows.size + hiddenShows.size
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    val repo = AppRepository(app)
    private val content = TvContentRepository(app)
    private val trending = TrendingRepository(app)
    private val prefs get() = repo.prefs

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    /** Fires when Home is pressed while the launcher is already in front. */
    private val _homePressed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val homePressed: SharedFlow<Unit> = _homePressed

    private var apps: List<TvApp> = emptyList()
    private var inputs: List<TvInput> = emptyList()
    private var watching: List<ContentItem> = emptyList()
    private var discover = Discover()
    private var shownPart = -1L

    init {
        // Trending changes through the day: look again every half hour (the repository only
        // goes to the network once its list is 3 hours old), even if the TV just sits on home.
        // The spotlight also moves on to a new set of slides every 3 hours.
        viewModelScope.launch {
            while (true) {
                delay(30 * 60_000L)
                if (trending.isStale() || trending.dayPart() != shownPart) refresh()
            }
        }
    }
    private var refreshJob: Job? = null
    private var refreshAgain = false

    fun refresh() {
        // One refresh at a time touches the repository's art cache; a request that arrives
        // mid-refresh runs once more afterwards so a just-installed app isn't missed.
        if (refreshJob?.isActive == true) {
            refreshAgain = true
            return
        }
        refreshJob = viewModelScope.launch {
            do {
                refreshAgain = false
                withContext(Dispatchers.IO) {
                    apps = repo.loadApps()
                    inputs = repo.loadInputs()
                }
                publish() // apps first, so the screen is usable before the content queries finish
                withContext(Dispatchers.IO) {
                    val installed = apps.mapTo(HashSet()) { it.packageName }
                    watching = content.continueWatching(installed)
                }
                publish()
                withContext(Dispatchers.IO) {
                    val order = (_state.value.favorites + _state.value.others).withIndex()
                        .associate { (i, a) -> a.packageName to i }
                    shownPart = trending.dayPart()
                    discover = trending.load(apps.mapTo(HashSet()) { it.packageName }, order)
                }
                publish()
            } while (refreshAgain)
        }
    }

    fun onHomePressed() {
        _homePressed.tryEmit(Unit)
    }

    private fun publish() {
        val hidden = prefs.hidden
        val byPackage = apps.associateBy { it.packageName }
        val favorites = prefs.favorites.mapNotNull { byPackage[it] }.filter { it.packageName !in hidden }
        val favoriteSet = favorites.mapTo(HashSet()) { it.packageName }
        val rank = prefs.order.withIndex().associate { (i, pkg) -> pkg to i }
        val others = apps
            .filter { it.packageName !in favoriteSet && it.packageName !in hidden }
            .sortedWith(compareBy<TvApp> { rank[it.packageName] ?: Int.MAX_VALUE }.thenBy { it.label.lowercase() })

        val hiddenContent = prefs.hiddenContent
        val continueWatching = watching
            .filter { it.packageName !in hidden && it.hideKey !in hiddenContent }
            .take(MAX_CONTINUE)
        val appOrder = (favorites + others).withIndex().associate { (i, a) -> a.packageName to i }
        val rows = listOf(
            ContentRow(TRENDING_MOVIES, "", "Trending movies", discover.movies.filter { it.hideKey !in hiddenContent }),
            ContentRow(TRENDING_SHOWS, "", "Trending shows", discover.shows.filter { it.hideKey !in hiddenContent }),
        ).filter { it.items.size >= 2 }
        val hiddenRowKeys = prefs.hiddenRows

        _state.value = HomeState(
            favorites = favorites,
            others = others,
            hiddenApps = apps.filter { it.packageName in hidden }.sortedBy { it.label.lowercase() },
            inputs = inputs,
            continueWatching = continueWatching,
            recommendations = discover.spotlight.filter { it.packageName !in hidden && it.hideKey !in hiddenContent },
            rows = rows.filter { it.key !in hiddenRowKeys },
            hiddenRows = rows.filter { it.key in hiddenRowKeys },
            hiddenShows = watching.filter { it.packageName !in hidden && it.hideKey in hiddenContent },
            labels = apps.associate { it.packageName to it.label },
            icons = apps.associate { it.packageName to it.icon },
            loaded = true,
        )
    }

    fun toggleFavorite(pkg: String) {
        val current = prefs.favorites
        prefs.favorites = if (pkg in current) current - pkg else current + pkg
        publish()
    }

    fun hide(pkg: String) {
        prefs.hidden = prefs.hidden + pkg
        prefs.favorites = prefs.favorites - pkg
        publish()
    }

    fun unhide(pkg: String) {
        prefs.hidden = prefs.hidden - pkg
        publish()
    }

    fun hideRow(key: String) {
        prefs.hiddenRows = prefs.hiddenRows + key
        publish()
    }

    fun unhideRow(key: String) {
        prefs.hiddenRows = prefs.hiddenRows - key
        publish()
    }

    fun hideContent(item: ContentItem) {
        prefs.hiddenContent = prefs.hiddenContent + item.hideKey
        publish()
    }

    fun unhideContent(item: ContentItem) {
        prefs.hiddenContent = prefs.hiddenContent - item.hideKey
        publish()
    }

    /** Moves an app one place left (-1) or right (+1) within its own row. */
    fun move(pkg: String, delta: Int) {
        val s = _state.value
        val inFavorites = s.favorites.any { it.packageName == pkg }
        val list = (if (inFavorites) s.favorites else s.others).map { it.packageName }.toMutableList()
        val from = list.indexOf(pkg)
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        if (inFavorites) prefs.favorites = list else prefs.order = list
        publish()
    }

    private companion object {
        const val MAX_CONTINUE = 12
        const val TRENDING_MOVIES = "movies"
        const val TRENDING_SHOWS = "shows"
    }
}
