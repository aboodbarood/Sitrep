package com.sitrep.ulm

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
            settings.textZoom = 100
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }
        setContentView(webView)

        startForegroundService(Intent(this, SitrepMonitorService::class.java))
        continuePermissionFlow()
        waitForServerAndLoad()
    }

    /**
     * One request at a time (Android drops a second concurrent request), each asked
     * once per install so a "no" isn't nagged about (except foreground location):
     *  1. notifications  2. location (while in use)  3. battery-optimisation exemption,
     * so Doze doesn't defer the ~15-min screen-off refresh. Location stays "while in
     * use" by choice: after a reboot the app has to be opened once before background
     * fixes work again (and on HyperOS, before the service runs at all).
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
            // Essential, so asked on every launch (Android itself stops after two refusals).
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

    /** The service starts the server in its onCreate, which may not have run yet — poll briefly. */
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
     * screen unless told otherwise — and the service keeps this process alive,
     * so the dashboard went on polling DING every 30 s (3 departure boards) and
     * every 2 min (2 trip plans) indefinitely in the background. Pausing here
     * stops all of it; the page catches up in sitrepResume() when it's back.
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
        // Page reloads everything now (data may be stale) and, in automatic
        // location mode, takes one accurate fix. No-op before the page has loaded.
        webView.evaluateJavascript("window.sitrepResume && window.sitrepResume()", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back closes the home-setup sheet first if it's open, then leaves the app.
        webView.evaluateJavascript("window.sitrepBack ? window.sitrepBack() : false") { handled ->
            if (handled != "true") {
                if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
            }
        }
    }
}
