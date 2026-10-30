package br.arthur.tvhome

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap

data class TvApp(
    val packageName: String,
    val component: ComponentName,
    val label: String,
    val art: ImageBitmap,
    /** True when [art] is the app's 16:9 TV banner, false when it is only a square icon. */
    val isBanner: Boolean,
    /** The square icon, for the badge on programme cards. */
    val icon: ImageBitmap,
)

data class TvInput(
    val id: String,
    val label: String,
    val connected: Boolean,
    /** The aerial tuner: opens the TV's channel player rather than an HDMI port. */
    val isTuner: Boolean = false,
)

/** Apps the launcher never lists, because a button elsewhere on the screen already covers them. */
private val EXCLUDED = setOf(
    "br.arthur.tvhome",
    "com.android.tv.settings", // gear button
    Freeplay.GOOGLE_HOME, // only ever on while Freeplay is open
    "com.google.android.videos", // Google's movie store; kept installed for Google TV, never shown
    "com.tcl.dashboard", // the remote's settings key
    "com.tcl.suspension", // TCL quick panel
)

/** First-run layout, picked from what this TV actually watches most. */
private val DEFAULT_FAVORITES = listOf(
    "com.google.android.youtube.tv",
    "com.netflix.ninja",
    "com.amazon.amazonvideo.livingroom",
    "com.disney.disneyplus",
    "tv.pluto.android",
    "com.wbd.stream",
)
private val DEFAULT_ORDER = listOf(
    "com.nuvio.app",
    "com.tcl.tv.plus",
    "com.valvesoftware.steamlink",
    "com.stremio.one",
    "com.sling",
    "com.playstation.remoteplay.tv",
    "com.limelight",
    "com.surfshark.vpnclient.android",
    "com.android.vending",
    "com.phlox.tvwebbrowser",
    "com.seraphic.openinet.pre",
)

class LauncherPrefs(context: Context) {
    private val sp = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    init {
        if (!sp.contains("favorites")) {
            sp.edit {
                putString("favorites", DEFAULT_FAVORITES.joinToString("|"))
                putString("order", DEFAULT_ORDER.joinToString("|"))
            }
        }
    }

    var favorites: List<String>
        get() = sp.getString("favorites", "").orEmpty().split('|').filter { it.isNotEmpty() }
        set(value) = sp.edit { putString("favorites", value.joinToString("|")) }

    /** Order of the apps below the favourites row. Apps not listed go last, alphabetically. */
    var order: List<String>
        get() = sp.getString("order", "").orEmpty().split('|').filter { it.isNotEmpty() }
        set(value) = sp.edit { putString("order", value.joinToString("|")) }

    var hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet()).orEmpty().toSet()
        set(value) = sp.edit { putStringSet("hidden", value) }

    /** App rows hidden from home, by "package|row name". */
    var hiddenRows: Set<String>
        get() = sp.getStringSet("hiddenRows", emptySet()).orEmpty().toSet()
        set(value) = sp.edit { putStringSet("hiddenRows", value) }

    /** True for the first few launches, while the hold-OK tip is still worth showing. */
    fun takeTip(): Boolean {
        val shown = sp.getInt("tips", 0)
        if (shown >= 3) return false
        sp.edit { putInt("tips", shown + 1) }
        return true
    }

    /** Shows taken out of Continue watching, by "package|title". */
    var hiddenContent: Set<String>
        get() = sp.getStringSet("hiddenContent", emptySet()).orEmpty().toSet()
        set(value) = sp.edit { putStringSet("hiddenContent", value) }
}

class AppRepository(private val context: Context) {
    val prefs = LauncherPrefs(context)
    private val pm: PackageManager = context.packageManager

    /** Decoded art by package, activity and install time, so a refresh only decodes what changed. */
    private val artCache = HashMap<String, Art>()

    private class Art(val art: ImageBitmap, val isBanner: Boolean, val icon: ImageBitmap)

    fun loadApps(): List<TvApp> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val seen = HashSet<String>()
        val apps = pm.queryIntentActivities(query, 0).mapNotNull { info ->
            val activity = info.activityInfo
            val pkg = activity.packageName
            if (pkg in EXCLUDED || !seen.add(pkg)) return@mapNotNull null
            // An app updating or uninstalling mid-scan can make its lookups throw; skip just that one.
            runCatching {
                val updated = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
                val art = artCache.getOrPut("$pkg/${activity.name}@$updated") { loadArt(activity) }
                TvApp(pkg, ComponentName(pkg, activity.name), info.loadLabel(pm).toString().trim(), art.art, art.isBanner, art.icon)
            }.getOrNull()
        }
        val live = apps.mapTo(HashSet()) { "${it.packageName}/${it.component.className}" }
        artCache.keys.retainAll { key -> key.substringBeforeLast('@') in live }
        return apps
    }

    private fun loadArt(activity: ActivityInfo): Art {
        val banner = runCatching { activity.loadBanner(pm) }.getOrNull()
        val icon = activity.loadIcon(pm).toBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888).also { it.prepareToDraw() }
        val bitmap = banner?.toBitmap(BANNER_W, BANNER_H, Bitmap.Config.ARGB_8888)?.also { it.prepareToDraw() } ?: icon
        return Art(bitmap.asImageBitmap(), banner != null, icon.asImageBitmap())
    }

    fun loadInputs(): List<TvInput> {
        val tim = context.getSystemService(TvInputManager::class.java) ?: return emptyList()
        val all = runCatching { tim.tvInputList }.getOrDefault(emptyList())
        fun label(info: TvInputInfo) =
            (info.loadCustomLabel(context) ?: info.loadLabel(context)).toString().trim()

        val hdmi = all
            .filter { it.type == TvInputInfo.TYPE_HDMI && it.isPassthroughInput && it.parentId == null }
            .filterNot { it.isHidden(context) }
            .map { TvInput(it.id, label(it), tim.getInputState(it.id) != TvInputManager.INPUT_STATE_DISCONNECTED) }
            .sortedBy { it.label }
        // The aerial comes from the TV's own system input service. Streaming apps (Pluto TV,
        // Sling) register tuner inputs too; those are already reachable as apps.
        val aerial = all.firstOrNull { info ->
            info.type == TvInputInfo.TYPE_TUNER && !info.isHidden(context) &&
                info.serviceInfo.packageName !in STREAMING_TUNERS &&
                runCatching {
                    pm.getApplicationInfo(info.serviceInfo.packageName, 0).flags and
                        android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
                }.getOrDefault(false)
        }?.let { TvInput(it.id, "Antenna", connected = true, isTuner = true) }
        return listOfNotNull(aerial) + hdmi
    }

    fun launchIntent(app: TvApp): Intent =
        (pm.getLeanbackLaunchIntentForPackage(app.packageName)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setComponent(app.component))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    fun inputIntent(input: TvInput): Intent {
        val uri = if (input.isTuner) {
            // The aerial's channel list; the TV's own player opens on the last channel watched.
            TvContract.buildChannelsUriForInput(input.id)
        } else {
            TvContract.buildChannelUriForPassthroughInput(input.id)
        }
        return Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** The TV's own channel player, for when it won't take the aerial's channel list. */
    fun tvPlayerIntent(): Intent? = TV_PLAYERS.firstNotNullOfOrNull { pm.getLeanbackLaunchIntentForPackage(it) }
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        const val BANNER_W = 320
        const val BANNER_H = 180
        const val ICON_PX = 96
        private val STREAMING_TUNERS = setOf("com.google.android.videos", "com.tcl.channelplus")
        private val TV_PLAYERS = listOf("com.tcl.tv", "com.android.tv", "com.google.android.tv")
    }
}
