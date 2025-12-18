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
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
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

    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == StepTrackingService.ACTION_STATS_UPDATE) {
                // Check if it's a water update
                if (intent.hasExtra("water_update") && intent.getBooleanExtra("water_update", false)) {
                    val glasses = intent.getIntExtra("water_glasses", 0)
                    pushWaterToWeb(glasses)
                    return
                }
                
                // Otherwise it's a stats update
                val json = intent.getStringExtra(StepTrackingService.EXTRA_STATS_JSON)
                if (json != null) {
                    pushStatsToWeb(json)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val wv: WebView = binding.webView

        wv.webViewClient = WebViewClient()
        
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
        
        // Handle intent extras (from notification)
        handleIntentExtras(intent)
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntentExtras(intent)
    }
    
    private fun handleIntentExtras(intent: Intent?) {
        if (intent?.getBooleanExtra("add_water", false) == true) {
            // Add water glass from notification click
            binding.webView.postDelayed({
                val js = "window.wiseWalkAddWater && window.wiseWalkAddWater(1);"
                binding.webView.evaluateJavascript(js, null)
            }, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        
        val filter = IntentFilter(StepTrackingService.ACTION_STATS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statsReceiver, filter)
        }
        
        updateTrackingStatusToWeb()
        
        // Sync water glasses on resume
        syncWaterGlassesFromPrefs()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statsReceiver)
        } catch (_: Exception) {}
    }
    
    private fun syncWaterGlassesFromPrefs() {
        val today = java.time.LocalDate.now().toString()
        val glasses = prefs.getInt("water_glasses_$today", 0)
        if (glasses > 0) {
            binding.webView.postDelayed({
                pushWaterToWeb(glasses)
            }, 300)
        }
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
        
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setWaitForAccurateLocation(true)
            .setMinUpdateIntervalMillis(500)
            .setMaxUpdates(1)
            .build()
        
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { location ->
                        sendLocationToWeb(location.latitude, location.longitude)
                    }
                    fusedLocationClient.removeLocationUpdates(this)
                }
            },
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
        binding.webView.post {
            binding.webView.evaluateJavascript(js, null)
        }
    }

    private fun updateTrackingStatusToWeb() {
        val status = JSONObject().apply {
            put("running", StepTrackingService.isRunning)
        }
        val js = "window.wiseWalkOnTrackingStatus && window.wiseWalkOnTrackingStatus($status);"
        binding.webView.post {
            binding.webView.evaluateJavascript(js, null)
        }
    }

    private fun pushStatsToWeb(statsJson: String) {
        val js = "window.wiseWalkOnStatsUpdate && window.wiseWalkOnStatsUpdate($statsJson);"
        binding.webView.post {
            binding.webView.evaluateJavascript(js, null)
        }
    }

    private fun pushWaterToWeb(glasses: Int) {
        // Update the display directly and sync localStorage
        val js = """
            (function() {
                var today = new Date().toISOString().split('T')[0];
                localStorage.setItem('wisewalk-water-' + today, '$glasses');
                if (document.getElementById('w-glasses')) {
                    document.getElementById('w-glasses').textContent = '$glasses';
                }
                if (typeof updateWaterDisplay === 'function') {
                    updateWaterDisplay();
                }
            })();
        """.trimIndent()
        binding.webView.post {
            binding.webView.evaluateJavascript(js, null)
        }
    }

    private fun updateGoalFromProfile(json: String) {
        try {
            val o = JSONObject(json)
            val sex = o.optString("sex", "M")
            val heightCm = o.optDouble("heightCm", 170.0)
            val weightKg = o.optDouble("weightKg", 70.0)
            val wakeTime = o.optString("wakeTime", "07:00")
            val sleepTime = o.optString("sleepTime", "23:00")
            val glassMl = o.optInt("glassMl", 300)

            val goals = o.optJSONObject("goals")
            val maintainKm = goals?.optDouble("maintainKm", 0.0) ?: 0.0
            val optimizeKm = goals?.optDouble("optimizeKm", 0.0) ?: 0.0
            val waterGlasses = goals?.optInt("waterGlasses", 8) ?: 8
            val plan = o.optString("activePlan", "maintain")

            prefs.edit()
                .putString("profile_sex", sex)
                .putInt("profile_height_cm", heightCm.toInt())
                .putFloat("profile_weight_kg", weightKg.toFloat())
                .putFloat("goal_km_maintain", maintainKm.toFloat())
                .putFloat("goal_km_optimize", optimizeKm.toFloat())
                .putInt("water_goal_glasses", waterGlasses)
                .putInt("glass_ml", glassMl)
                .putString("wake_time", wakeTime)
                .putString("sleep_time", sleepTime)
                .putString("active_plan", plan)
                .apply()

        } catch (_: Throwable) {}
    }

    private fun setWaterGlasses(glasses: Int) {
        val today = java.time.LocalDate.now().toString()
        prefs.edit().putInt("water_glasses_$today", glasses).apply()
    }
    
    private fun syncWater(glasses: Int) {
        setWaterGlasses(glasses)
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
        } else {
            startBackgroundTracking()
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
                binding.webView.post { binding.webView.evaluateJavascript(js, null) }
                
                val activityOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) 
                    hasPermission(Manifest.permission.ACTIVITY_RECOGNITION) else true
                if (activityOk) {
                    startBackgroundTracking()
                }
            }
            LOCATION_PERMISSION_REQUEST -> {
                val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
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
        
        binding.webView.postDelayed({
            updateTrackingStatusToWeb()
        }, 500)
    }

    private fun stopBackgroundTracking() {
        val serviceIntent = Intent(this, StepTrackingService::class.java)
        stopService(serviceIntent)
        updateTrackingStatusToWeb()
    }

    private fun openExternalUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                browserIntent.addCategory(Intent.CATEGORY_BROWSABLE)
                startActivity(browserIntent)
            } catch (_: Exception) {}
        }
    }

    companion object {
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

        @JavascriptInterface
        fun setWaterGlasses(glasses: Int) {
            activity.setWaterGlasses(glasses)
        }
        
        @JavascriptInterface
        fun syncWater(glasses: Int) {
            activity.syncWater(glasses)
        }
    }
}
