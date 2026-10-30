package br.arthur.tvhome

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.tv.TvContract
import android.media.tv.TvContract.PreviewPrograms
import android.media.tv.TvContract.WatchNextPrograms
import android.net.Uri
import android.util.Log

/** A programme an app published to the TV: something to resume, or a pick from one of its rows. */
data class ContentItem(
    val key: String,
    val packageName: String,
    val title: String,
    val season: String?,
    val episodeNumber: String?,
    val episodeTitle: String?,
    val description: String?,
    val imageUri: String?,
    /** Width / height of [imageUri]. */
    val aspect: Float,
    val intentUri: String?,
    val durationMs: Long,
    val positionMs: Long,
    val isNextEpisode: Boolean,
    val lastEngagement: Long,
    /** TvContract programme type; apps fill it in loosely (Netflix calls films episodes). */
    val type: Int,
    /** US age rating ("PG-13", "TV-14"…), when known. */
    val rating: String? = null,
) {
    /** How far through it is, 0..1, when the app said. */
    val progress: Float? get() =
        if (durationMs > 0 && positionMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null

    /** Stable across the app re-publishing the row, so "hide" sticks. */
    val hideKey get() = "$packageName|$title"
}

/** One of an app's own rows (Android TV "preview channels"), such as YouTube's recommendations. */
data class ContentRow(val key: String, val packageName: String, val title: String, val items: List<ContentItem>) {
    val portrait get() = items.count { it.aspect < 1f } * 2 > items.size
}

/**
 * Reads what apps publish to the system TV provider. Needs READ_TV_LISTINGS, which lets a
 * launcher see every app's searchable rows; granted once with "pm grant".
 */
class TvContentRepository(private val context: Context) {
    fun canRead() = context.checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED

    fun continueWatching(installed: Set<String>): List<ContentItem> {
        if (!canRead()) return emptyList()
        return query(WatchNextPrograms.CONTENT_URI, null) { c ->
            // "Continue" and "next episode" only; "new" and "watchlist" entries are where apps
            // (and TCL's browser in particular) slip in promotions.
            val type = c.int(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, -1)
            if (type != WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE && type != WatchNextPrograms.WATCH_NEXT_TYPE_NEXT) {
                return@query null
            }
            c.item("w", installed, isNext = type == WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)
        }
            .filter { (it.progress ?: 0f) < FINISHED }
            .sortedByDescending { it.lastEngagement }
            .distinctBy { it.hideKey }
    }

    fun appRows(installed: Set<String>): List<ContentRow> {
        if (!canRead()) return emptyList()
        data class Channel(val id: Long, val pkg: String, val name: String)
        // Google TV never set the "browsable" flag on app rows, so every preview channel counts.
        val channels = query(TvContract.Channels.CONTENT_URI, CHANNEL_COLUMNS) { c ->
            if (c.str(TvContract.Channels.COLUMN_TYPE) != TvContract.Channels.TYPE_PREVIEW) return@query null
            val pkg = c.str(TvContract.Channels.COLUMN_PACKAGE_NAME) ?: return@query null
            if (pkg !in installed || pkg in AD_SOURCES) return@query null
            Channel(c.long(TvContract.Channels._ID), pkg, c.str(TvContract.Channels.COLUMN_DISPLAY_NAME).orEmpty().trim())
        }
        if (channels.isEmpty()) return emptyList()
        val programs = query(PreviewPrograms.CONTENT_URI, null) { c ->
            val item = c.item("p", installed, isNext = false) ?: return@query null
            Triple(c.long(PreviewPrograms.COLUMN_CHANNEL_ID), c.int(PreviewPrograms.COLUMN_WEIGHT, 0), item)
        }.groupBy({ it.first }, { it.second to it.third })
        return channels
            .mapNotNull { ch ->
                val items = programs[ch.id].orEmpty()
                    .sortedByDescending { it.first }
                    .map { it.second }
                    .distinctBy { it.hideKey }
                    .take(MAX_ROW)
                if (items.size < MIN_ROW) null else ContentRow("${ch.pkg}|${ch.name}", ch.pkg, ch.name, items)
            }
            // Some apps publish the same row twice; keep the fuller one.
            .groupBy { it.key }.values.map { same -> same.maxBy { it.items.size } }
            // Within an app, rows about you come before rows selling something.
            .sortedByDescending { personalScore(it.title) }
    }

    private fun personalScore(name: String): Int {
        val n = name.lowercase()
        return when {
            PERSONAL.any { it in n } -> 1
            PROMOTIONAL.any { it in n } -> -1
            else -> 0
        }
    }

    private fun Cursor.item(prefix: String, installed: Set<String>, isNext: Boolean): ContentItem? {
        val pkg = str(WatchNextPrograms.COLUMN_PACKAGE_NAME) ?: return null
        if (pkg !in installed || pkg in AD_SOURCES) return null
        val title = str(WatchNextPrograms.COLUMN_TITLE)?.trim().orEmpty()
        if (title.isEmpty()) return null
        // Family TV: nothing above TV-14 / PG-13, where the app says what it's rated.
        val rating = str(WatchNextPrograms.COLUMN_CONTENT_RATING).orEmpty()
        if (MATURE_RATINGS.any { it in rating }) return null
        val (image, aspect) = art()
        return ContentItem(
            key = prefix + long(WatchNextPrograms._ID),
            packageName = pkg,
            title = title,
            season = str(WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER)?.trim()?.ifEmpty { null },
            episodeNumber = str(WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER)?.trim()?.ifEmpty { null },
            episodeTitle = str(WatchNextPrograms.COLUMN_EPISODE_TITLE)?.trim()?.takeIf { it.isNotEmpty() && it != title },
            description = str(WatchNextPrograms.COLUMN_SHORT_DESCRIPTION)?.trim()?.ifEmpty { null },
            imageUri = image,
            aspect = aspect,
            intentUri = str(WatchNextPrograms.COLUMN_INTENT_URI),
            durationMs = long(WatchNextPrograms.COLUMN_DURATION_MILLIS),
            positionMs = long(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS),
            isNextEpisode = isNext,
            lastEngagement = long(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS),
            type = int(WatchNextPrograms.COLUMN_TYPE, -1),
        )
    }

    /** Prefers landscape art (a poster the app marked landscape, else the thumbnail). */
    private fun Cursor.art(): Pair<String?, Float> {
        val poster = str(WatchNextPrograms.COLUMN_POSTER_ART_URI)
        val posterRatio = ratio(int(WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, -1))
        val thumb = str(WatchNextPrograms.COLUMN_THUMBNAIL_URI)
        val thumbRatio = ratio(int(WatchNextPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO, -1))
        return when {
            poster != null && (posterRatio ?: 0f) > 1f -> poster to posterRatio!!
            thumb != null -> thumb to (thumbRatio ?: LANDSCAPE)
            poster != null -> poster to (posterRatio ?: LANDSCAPE)
            else -> null to LANDSCAPE
        }
    }

    private fun ratio(code: Int): Float? = when (code) {
        WatchNextPrograms.ASPECT_RATIO_16_9 -> 16f / 9f
        WatchNextPrograms.ASPECT_RATIO_3_2 -> 3f / 2f
        WatchNextPrograms.ASPECT_RATIO_4_3 -> 4f / 3f
        WatchNextPrograms.ASPECT_RATIO_1_1 -> 1f
        WatchNextPrograms.ASPECT_RATIO_2_3, ASPECT_RATIO_MOVIE_POSTER -> 2f / 3f
        else -> null
    }

    private fun <T> query(uri: Uri, projection: Array<String>?, map: (Cursor) -> T?): List<T> = try {
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) map(c)?.let(::add) }
        }.orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "Can't read $uri", e)
        emptyList()
    }

    private fun Cursor.str(col: String) = getColumnIndex(col).takeIf { it >= 0 && !isNull(it) }?.let(::getString)
    private fun Cursor.long(col: String) = getColumnIndex(col).takeIf { it >= 0 && !isNull(it) }?.let(::getLong) ?: 0L
    private fun Cursor.int(col: String, default: Int) =
        getColumnIndex(col).takeIf { it >= 0 && !isNull(it) }?.let(::getInt) ?: default

    companion object {
        private const val TAG = "TvContent"
        const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
        private const val MAX_ROW = 20
        private val MATURE_RATINGS = listOf("US_TV_MA", "US_MV_R", "US_MV_NC17", "US_MV_X")
        private const val MIN_ROW = 2
        private const val FINISHED = 0.97f
        private const val LANDSCAPE = 16f / 9f
        private const val ASPECT_RATIO_MOVIE_POSTER = 5 // PreviewProgramColumns, not exposed on WatchNextPrograms

        private val CHANNEL_COLUMNS = arrayOf(
            TvContract.Channels._ID,
            TvContract.Channels.COLUMN_PACKAGE_NAME,
            TvContract.Channels.COLUMN_TYPE,
            TvContract.Channels.COLUMN_DISPLAY_NAME,
        )

        private val PERSONAL = listOf("subscription", "watchlist", "recommended", "recomendado", "continue", "my list", "for you", "para você")
        private val PROMOTIONAL = listOf("free movies", "trending", "featured", "top selling", "new on", "coming soon")

        /** Apps whose "content" on this TV is advertising: shopping, games and store promos. */
        private val AD_SOURCES = setOf(
            "com.seraphic.openinet.pre", // TCL's OPEN BROWSER
            "com.google.android.videos", // Google TV store rows
            "com.tcl.partnercustomizer", // TCL's promoted row
            "com.tcl.channelplus",
            // Not wanted on this TV's home screen: free/live channels, videos and local news.
            "tv.pluto.android",
            "com.sling",
            "com.google.android.youtube.tv",
            "com.maz.combo4052", // a local news station
        )

        /** Turns a programme's intent URI into something safe to start from the launcher. */
        fun launchIntent(item: ContentItem): Intent? {
            val uri = item.intentUri ?: return null
            return runCatching {
                Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).takeIf { intent ->
                    // A programme may only open its own app, never some other app's screen.
                    val target = intent.component?.packageName ?: intent.`package`
                    target == null || target == item.packageName
                }?.apply {
                    selector = null
                    // Web deep links (Netflix uses them) should open in the app that published them.
                    if (`package` == null && component == null) `package` = item.packageName
                    // A launcher never passes URI grants on to another app.
                    flags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).inv()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }.getOrNull()
        }
    }
}
