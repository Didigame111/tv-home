package br.arthur.tvhome

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Everything the home screen shows from JustWatch: the spotlight and the two trending grids. */
data class Discover(
    val spotlight: List<ContentItem> = emptyList(),
    val movies: List<ContentItem> = emptyList(),
    val shows: List<ContentItem> = emptyList(),
)

/**
 * What's popular and trending in the US right now, from JustWatch (the "where to watch"
 * service), in English and limited to the subscription services installed on this TV. Each
 * title comes with the link to its page in that service's app.
 *
 * Keeping it fresh: the lists are fetched again every few hours, and each title remembers when
 * it first appeared here. A title loses ground for every day it has been showing, so newly
 * trending ones rise to the top and the screen doesn't settle on the same faces for a week.
 */
class TrendingRepository(private val context: Context) {
    private val cache = File(context.filesDir, "trending.json")
    private val seen = File(context.filesDir, "trending-seen.json")

    fun isStale() = !cache.exists() || System.currentTimeMillis() - cache.lastModified() > MAX_AGE_MS

    fun load(installed: Set<String>, appOrder: Map<String, Int>): Discover {
        val services = SERVICES.filterValues { it in installed }
        if (services.isEmpty()) return Discover()
        // A list saved by an older version lacks the separate popular lists: fetch afresh.
        val raw = (if (isStale()) null else read()?.takeIf { it.has("popularMovies") })
            ?: runCatching { fetch(services.keys) }
                .onFailure { Log.w(TAG, "Couldn't fetch trending titles", it) }
                .getOrNull()
                ?.also { cache.writeText(it.toString()) }
            ?: read() // offline: the last list beats an empty screen
            ?: return Discover()

        val firstSeen = readSeen()
        fun list(kind: String, isShow: Boolean, wide: Boolean) =
            fresh(parse(raw.optJSONArray(kind) ?: JSONArray(), services, appOrder, isShow, wide), firstSeen)

        // The spotlight: popular films and shows with wide art, about two films to each show.
        // The day is cut into 3-hour parts, and each part shows a different stretch of the
        // popular lists, so the slides change through the day as well as day to day.
        val part = dayPart()
        val popularMovies = list("popularMovies", isShow = false, wide = true).filter { it.imageUri != null }.take(SPOTLIGHT_POOL)
        val popularShows = list("popularShows", isShow = true, wide = true).filter { it.imageUri != null }.take(SPOTLIGHT_POOL / 2)
        val spotMovies = window(popularMovies, part, SPOTLIGHT_MOVIES)
        val spotShows = window(popularShows, part, SPOTLIGHT_SHOWS)
        val spotlight = buildList {
            val m = spotMovies.iterator()
            val sh = spotShows.iterator()
            while (m.hasNext() || sh.hasNext()) {
                repeat(2) { if (m.hasNext()) add(m.next()) }
                if (sh.hasNext()) add(sh.next())
            }
        }
        // The grids below: trending now, without what the spotlight is already showing.
        val inSpotlight = spotlight.mapTo(HashSet()) { it.title.lowercase() }
        val movies = list("movies", isShow = false, wide = false).filter { it.title.lowercase() !in inSpotlight }.take(GRID)
        val shows = list("shows", isShow = true, wide = false).filter { it.title.lowercase() !in inSpotlight }.take(GRID)
        writeSeen(firstSeen)
        return Discover(spotlight, movies, shows)
    }

    /** Which 3-hour part of which day it is: changes eight times a day. */
    fun dayPart(): Long {
        val now = java.util.Calendar.getInstance()
        return now.get(java.util.Calendar.DAY_OF_YEAR) * 8L + now.get(java.util.Calendar.HOUR_OF_DAY) / 3
    }

    /** [count] items from [pool], starting further along it for each part of the day. */
    private fun window(pool: List<ContentItem>, part: Long, count: Int): List<ContentItem> {
        if (pool.size <= count) return pool
        val start = ((part * count) % pool.size).toInt()
        return List(count) { pool[(start + it) % pool.size] }
    }

    /** Ranks by place in the list, plus a penalty for each day a title has been on show here. */
    private fun fresh(items: List<ContentItem>, firstSeen: MutableMap<String, Long>): List<ContentItem> {
        val now = System.currentTimeMillis()
        return items.withIndex()
            .sortedBy { (rank, item) ->
                val since = firstSeen.getOrPut(item.key) { now }
                val days = (now - since) / DAY_MS.toDouble()
                // PG to TV-14 come first; G and kids' ratings only fill in when there aren't enough.
                val focus = if (item.rating in FOCUS_RATINGS) 0.0 else OFF_FOCUS_PENALTY
                rank + days * PENALTY_PER_DAY + focus
            }
            .map { it.value }
    }

    private fun readSeen(): MutableMap<String, Long> = runCatching {
        val json = JSONObject(seen.readText())
        json.keys().asSequence().associateWithTo(HashMap()) { json.getLong(it) }
    }.getOrDefault(HashMap())

    /** Forgets titles that haven't been around for a while, so a comeback counts as new. */
    private fun writeSeen(firstSeen: Map<String, Long>) {
        val cutoff = System.currentTimeMillis() - FORGET_AFTER_MS
        val json = JSONObject()
        firstSeen.filterValues { it > cutoff }.forEach { (k, v) -> json.put(k, v) }
        runCatching { seen.writeText(json.toString()) }
    }

    private fun read() = cache.takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    private fun fetch(shortNames: Set<String>): JSONObject = JSONObject()
        .put("movies", query(listOf("MOVIE"), "TRENDING", shortNames))
        .put("shows", query(listOf("SHOW"), "TRENDING", shortNames))
        .put("popularMovies", query(listOf("MOVIE"), "POPULAR", shortNames))
        .put("popularShows", query(listOf("SHOW"), "POPULAR", shortNames))

    private fun query(objectTypes: List<String>, sortBy: String, shortNames: Set<String>): JSONArray {
        val body = JSONObject()
            .put("operationName", "Discover")
            .put("query", QUERY)
            .put(
                "variables",
                JSONObject()
                    .put("country", "US")
                    .put("language", "en") // English titles, whatever language the apps' profiles use
                    .put("first", FETCH)
                    .put("sortBy", sortBy)
                    .put(
                        "filter",
                        JSONObject()
                            .put("objectTypes", JSONArray(objectTypes))
                            .put("packages", JSONArray(shortNames.toList()))
                            .put("monetizationTypes", JSONArray(listOf("FLATRATE"))),
                    ),
            )
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("content-type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val reply = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            return reply.getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges")
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(edges: JSONArray, services: Map<String, String>, appOrder: Map<String, Int>, isShow: Boolean, wide: Boolean): List<ContentItem> {
        val items = ArrayList<ContentItem>()
        for (i in 0 until edges.length()) {
            val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
            val content = node.optJSONObject("content") ?: continue
            val title = content.optString("title").trim()
            if (title.isEmpty()) continue
            // Family TV: nothing rated above TV-14 / PG-13, and nothing unrated.
            val rating = content.optString("ageCertification").trim().uppercase()
            if (rating !in ALLOWED_RATINGS) continue
            val show = isShow
            // Where to watch it: included with a subscription installed here, preferring the
            // service earliest in Your apps.
            val offers = node.optJSONArray("offers") ?: JSONArray()
            val offer = (0 until offers.length()).mapNotNull { offers.optJSONObject(it) }
                .filter { it.optString("monetizationType") == "FLATRATE" }
                .mapNotNull { o ->
                    val pkg = services[o.optJSONObject("package")?.optString("shortName")] ?: return@mapNotNull null
                    val url = o.optString("standardWebURL").ifEmpty { return@mapNotNull null }
                    pkg to url
                }
                .minByOrNull { (pkg, _) -> appOrder[pkg] ?: Int.MAX_VALUE }
                ?: continue
            val (pkg, url) = offer
            // The spotlight wants wide art; the grids want posters.
            val backdrop = content.optJSONArray("backdrops")?.optJSONObject(0)?.optString("backdropUrl")
            items += ContentItem(
                key = "jw:" + node.optString("id", title),
                packageName = pkg,
                title = title,
                season = null,
                episodeNumber = null,
                episodeTitle = null,
                description = content.optString("shortDescription").trim().ifEmpty { null },
                imageUri = if (wide) image(backdrop, "s1440") else image(content.optString("posterUrl"), "s332"),
                aspect = if (wide) 16f / 9f else 2f / 3f,
                intentUri = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg).toUri(Intent.URI_INTENT_SCHEME),
                durationMs = 0,
                positionMs = 0,
                isNextEpisode = false,
                lastEngagement = 0,
                type = if (show) 1 else 0,
                rating = rating,
            )
        }
        return items.distinctBy { it.title.lowercase() }
    }

    private fun image(path: String?, profile: String) = path?.ifEmpty { null }
        ?.let { IMAGES + it.replace("{profile}", profile).replace("{format}", "jpg") }

    private companion object {
        const val TAG = "Trending"
        const val ENDPOINT = "https://apis.justwatch.com/graphql"
        const val IMAGES = "https://images.justwatch.com"
        const val MAX_AGE_MS = 3 * 60 * 60 * 1000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val FORGET_AFTER_MS = 21 * DAY_MS
        const val PENALTY_PER_DAY = 4.0 // a week on show costs a title ~28 places
        const val FETCH = 100 // most popular titles are rated R or TV-MA, so look further down
        const val GRID = 12 // two rows of six
        const val SPOTLIGHT_MOVIES = 8
        const val SPOTLIGHT_SHOWS = 4
        const val SPOTLIGHT_POOL = 24 // three parts of the day before a film comes round again

        /** The ratings the home screen leans on; the rest of ALLOWED_RATINGS are fill-ins. */
        val FOCUS_RATINGS = setOf("PG", "PG-13", "TV-PG", "TV-14")
        const val OFF_FOCUS_PENALTY = 25.0

        /** US ratings up to TV-14 / PG-13. */
        val ALLOWED_RATINGS = setOf("G", "PG", "PG-13", "TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "TV-PG", "TV-14")

        /**
         * JustWatch's short names for the subscription services on this TV, and their apps.
         * Pluto TV, Sling, YouTube and local news are left out on purpose.
         */
        val SERVICES = mapOf(
            "nfx" to "com.netflix.ninja",
            "amp" to "com.amazon.amazonvideo.livingroom",
            "dnp" to "com.disney.disneyplus",
            "mxx" to "com.wbd.stream",
        )

        val QUERY = """
            query Discover(${'$'}country: Country!, ${'$'}language: Language!, ${'$'}first: Int!, ${'$'}sortBy: PopularTitlesSorting!, ${'$'}filter: TitleFilter) {
              popularTitles(country: ${'$'}country, first: ${'$'}first, sortBy: ${'$'}sortBy, filter: ${'$'}filter) {
                edges { node { id objectType
                  content(country: ${'$'}country, language: ${'$'}language) { title shortDescription ageCertification posterUrl backdrops { backdropUrl } }
                  offers(country: ${'$'}country, platform: WEB) { monetizationType standardWebURL package { shortName } }
                } }
              }
            }
        """.trimIndent()
    }
}
