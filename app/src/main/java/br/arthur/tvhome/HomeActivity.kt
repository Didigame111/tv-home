package br.arthur.tvhome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class HomeActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = vm.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The home screen is never see-through. An opaque window lets the TV's display hardware
        // scale it to the 4K panel instead of the GPU blending it on every frame.
        window.setFormat(PixelFormat.OPAQUE)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        // System broadcasts still arrive with NOT_EXPORTED.
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent { HomeScreen(vm) }
        // Continue watching needs this; if it's ever lost (a reinstall, a reset), ask for it on the TV.
        if (checkSelfPermission(TvContentRepository.READ_TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            askListings.launch(TvContentRepository.READ_TV_LISTINGS)
        }
    }

    private val askListings = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }

    override fun onResume() {
        super.onResume()
        vm.refresh()
        // Back from Live TV: switch Google TV off again so Home stays with TV Home.
        if (Freeplay.opening || CrashGuard.recentlyHandedBack(this)) return
        FreeplayWatcher.stop(this)
        if (Freeplay.isApproved(this) && Freeplay.isGoogleHomeEnabled(this)) {
            thread(name = "freeplay-off") {
                runCatching { Freeplay.switchOff(this, bringHomeForward = false) }
                    .onFailure { Freeplay.log("Couldn't switch Google TV off", it) }
            }
        }
    }

    /** Home was pressed: from an app, or while already here. Either way, go back to the top. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.onHomePressed()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }
}
