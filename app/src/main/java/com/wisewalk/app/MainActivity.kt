package com.wisewalk.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.wisewalk.app.databinding.ActivityMainBinding
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("wisewalk_prefs", Context.MODE_PRIVATE) }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null
    private var locationCallback: LocationCallback? = null

    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != StepTrackingService.ACTION_STATS_UPDATE) return

            if (intent.getBooleanExtra("water_update", false)) {
                val glasses = intent.getIntExtra("water_glasses", 0)
                val js = "window.wiseWalkUpdateStats && window.wiseWalkUpdateStats({waterGlasses:$glasses});"
                evaluateJs(js)
                return
            }

            val json = intent.getStringExtra(StepTrackingService.EXTRA_STATS_JSON) ?: return
            val js = "window.wiseWalkUpdateStats && window.wiseWalkUpdateStats($json);"
            evaluateJs(js)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val wv: WebView = binding.webView

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString().orEmpty()
                return if (url.startsWith("http://") || url.startsWith("https://")) {
                    openExternalUrl(url)
                    true
                } else {
                    false
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (hasLocationPermission()) {
                    callback?.invoke(origin, true, false)
                } else {
                    pendingGeolocationCallback = callback
                    pendingGeolocationOrigin = origin
                    requestLocationPermission()
                }
            }
        }

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            setGeolocationEnabled(true)
            setGeolocationDatabasePath(filesDir.path)
        }

        wv.addJavascriptInterface(WiseWalkBridge(this, wv), "WiseWalkAndroid")

        wv.loadUrl("file:///android_asset/wisewalk.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (wv.canGoBack()) wv.goBack() else finish()
            }
        })

    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(StepTrackingService.ACTION_STATS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statsReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statsReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Stats receiver was not registered when pausing.", e)
        }
    }

    override fun onDestroy() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
        binding.webView.removeJavascriptInterface("WiseWalkAndroid")
        binding.webView.stopLoading()
        binding.webView.webChromeClient = null
        binding.webView.webViewClient = null
        binding.webView.destroy()
        super.onDestroy()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            LOCATION_PERMISSION_REQUEST
        )
    }

    @SuppressLint("MissingPermission")
    fun getCurrentLocation() {
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }

        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setWaitForAccurateLocation(true)
            .setMinUpdateIntervalMillis(500)
            .setMaxUpdates(1)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    sendLocationToWeb(location.latitude, location.longitude)
                }
                fusedLocationClient.removeLocationUpdates(this)
                if (locationCallback == this) {
                    locationCallback = null
                }
            }
        }
        locationCallback = callback

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            callback,
            Looper.getMainLooper()
        )

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            location?.let {
                sendLocationToWeb(it.latitude, it.longitude)
            }
        }
    }

    private fun sendLocationToWeb(lat: Double, lng: Double) {
        val js = "window.wiseWalkSetLocation && window.wiseWalkSetLocation($lat, $lng);"
        evaluateJs(js)
    }

    private fun evaluateJs(js: String) {
        binding.webView.post { binding.webView.evaluateJavascript(js, null) }
    }

    private fun updateGoalFromProfile(json: String) {
        try {
            val o = JSONObject(json)
            prefs.edit()
                .putString("profile_sex", o.optString("sex", "M"))
                .putInt("profile_height_cm", o.optInt("heightCm", 170))
                .putFloat("profile_weight_kg", o.optDouble("weightKg", 70.0).toFloat())
                .putString("wake_time", o.optString("wakeTime", "07:00"))
                .putString("sleep_time", o.optString("sleepTime", "23:00"))
                .apply()

        } catch (t: Throwable) {
            Log.e(TAG, "Failed to update profile from JS payload.", t)
        }
    }

    private fun hasPermission(p: String): Boolean {
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
                perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERMISSIONS_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            PERMISSIONS_REQUEST -> {
                val s = JSONObject().apply {
                    put("permissionActivity", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        hasPermission(Manifest.permission.ACTIVITY_RECOGNITION) else true)
                    put("permissionNotif", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        hasPermission(Manifest.permission.POST_NOTIFICATIONS) else true)
                    put("permissionLocation", hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
                }
                val js = "window.wiseWalkOnPermissionUpdate && window.wiseWalkOnPermissionUpdate($s);"
                evaluateJs(js)
            }
            LOCATION_PERMISSION_REQUEST -> {
                val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
                pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, granted, false)
                pendingGeolocationCallback = null
                pendingGeolocationOrigin = null

                if (granted) {
                    getCurrentLocation()
                }
            }
        }
    }

    private fun startBackgroundTracking() {
        if (StepTrackingService.isRunning) return

        val serviceIntent = Intent(this, StepTrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

    }

    private fun stopBackgroundTracking() {
        val serviceIntent = Intent(this, StepTrackingService::class.java)
        stopService(serviceIntent)
    }

    private fun openExternalUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open url with default ACTION_VIEW, trying browsable intent: $url", e)
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                browserIntent.addCategory(Intent.CATEGORY_BROWSABLE)
                startActivity(browserIntent)
            } catch (inner: Exception) {
                Log.e(TAG, "Unable to open external url: $url", inner)
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSIONS_REQUEST = 2001
        private const val LOCATION_PERMISSION_REQUEST = 2002
    }

    class WiseWalkBridge(private val activity: MainActivity, private val webView: WebView) {

        @JavascriptInterface
        fun requestPermissions() {
            activity.runOnUiThread { activity.requestNeededPermissions() }
        }

        @JavascriptInterface
        fun setProfile(profileJson: String) {
            activity.updateGoalFromProfile(profileJson)
        }

        @JavascriptInterface
        fun startBackgroundTracking() {
            activity.runOnUiThread { activity.startBackgroundTracking() }
        }

        @JavascriptInterface
        fun stopBackgroundTracking() {
            activity.runOnUiThread { activity.stopBackgroundTracking() }
        }

        @JavascriptInterface
        fun isTrackingRunning(): Boolean {
            return StepTrackingService.isRunning
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            activity.runOnUiThread { activity.openExternalUrl(url) }
        }

        @JavascriptInterface
        fun getLocation() {
            activity.runOnUiThread { activity.getCurrentLocation() }
        }

    }
}
