package com.sitrep.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val notifPermCode = 1001
    private val locationPermCode = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }
        setContentView(webView)

        startForegroundService(Intent(this, SitrepMonitorService::class.java))
        continuePermissionFlow()
        waitForServerAndLoad()
    }

    /**
     * One request at a time (Android drops a second concurrent request):
     *  1. notifications  2. location (while in use, asked every launch — essential)
     *  3. battery-optimisation exemption (asked once), so Doze doesn't defer the
     *     ~15-min screen-off refresh.
     * Location stays "while in use" by choice: after a reboot the app has to be
     * opened once before background fixes work again.
     */
    private fun continuePermissionFlow() {
        val prefs = getSharedPreferences("sitrep", MODE_PRIVATE)
        fun askedOnce(key: String): Boolean {
            if (prefs.getBoolean(key, false)) return true
            prefs.edit().putBoolean(key, true).apply()
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !askedOnce("askedNotif")
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), notifPermCode)
            return
        }
        if (!LocationTracker.hasPermission(this)) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                locationPermCode
            )
            return
        }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName) && !askedOnce("askedBattery")) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) { /* ROM without this dialog — the app still works, just throttled */ }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locationPermCode &&
            grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        ) {
            // Re-delivers onStartCommand, which upgrades the service to the location
            // FGS type now that permission exists.
            startForegroundService(Intent(this, SitrepMonitorService::class.java))
        }
        continuePermissionFlow()
    }

    /** The foreground service starts the local server synchronously in onCreate,
     * but that happens on a separate process lifecycle tick — poll briefly rather
     * than assume it's already listening the instant startForegroundService returns. */
    private fun waitForServerAndLoad() {
        CoroutineScope(Dispatchers.Main).launch {
            var attempts = 0
            while (!SitrepMonitorService.serverReady && attempts < 100) {
                delay(100)
                attempts++
            }
            webView.loadUrl("http://127.0.0.1:${SitrepMonitorService.PORT}/")
        }
    }

    /**
     * A WebView keeps running its JavaScript timers after the app leaves the
     * screen unless told otherwise, and the service keeps this process alive, so
     * the dashboard went on polling MVG every 30 s in the background forever.
     * Pausing stops all of it; sitrepResume() catches the page up on return.
     */
    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.pauseTimers() // process-wide, but this is the only WebView
    }

    override fun onResume() {
        super.onResume()
        webView.resumeTimers()
        webView.onResume()
        webView.evaluateJavascript("window.sitrepResume && window.sitrepResume()", null)
        // Background updates are Wi-Fi/cell only now; one precise fix per app open.
        SitrepMonitorService.onAppOpened()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
