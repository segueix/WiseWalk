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
import androidx.core.content.FileProvider
import android.os.Build
import android.os.Bundle
import java.io.File
import android.os.Looper
import android.graphics.Color
import android.util.Log
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.view.View
import android.widget.Toast
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.wisewalk.app.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("wisewalk_prefs", Context.MODE_PRIVATE) }
    private lateinit var mapView: MapView

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null
    private var locationReceiver: BroadcastReceiver? = null
    private var isWalkGpsModeActive: Boolean = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(applicationContext, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initMap()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val wv: WebView = binding.webView

        wv.webViewClient = WebViewClient()
        wv.setBackgroundColor(Color.TRANSPARENT)
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)

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
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            setGeolocationEnabled(true)
            setGeolocationDatabasePath(filesDir.path)
        }

        wv.addJavascriptInterface(WiseWalkBridge(this, wv), "WiseWalkAndroid")

        wv.loadUrl("file:///android_asset/wisewalk.html")

        locationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != StepTrackingService.ACTION_LOCATION_UPDATE) return
                val lat = intent.getDoubleExtra(StepTrackingService.EXTRA_LAT, 0.0)
                val lng = intent.getDoubleExtra(StepTrackingService.EXTRA_LNG, 0.0)
                val bearing = intent.getFloatExtra(StepTrackingService.EXTRA_BEARING, 0f)
                if (lat != 0.0 && lng != 0.0) {
                    sendLocationToWeb(lat, lng)
                    if (isWalkGpsModeActive) {
                        animateCameraForWalkMode(lat, lng, bearing)
                    }
                }
            }
        }
        val locationFilter = IntentFilter(StepTrackingService.ACTION_LOCATION_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(locationReceiver, locationFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(locationReceiver, locationFilter)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (wv.canGoBack()) wv.goBack() else finish()
            }
        })

    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroy() {
        locationReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.w("WiseWalk", "Location receiver was already unregistered", e)
            }
        }
        locationReceiver = null
        super.onDestroy()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun initMap() {
        mapView = binding.mapView
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)
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
        try {
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
        } catch (e: SecurityException) {
            Log.e("WiseWalk", "Error de permisos obtenint la localització (SecurityException)", e)
            requestLocationPermission()
        } catch (e: IllegalStateException) {
            Log.e("WiseWalk", "Possible problema de hardware GPS/servei de localització no disponible", e)
        } catch (e: Exception) {
            Log.e("WiseWalk", "Error inesperat obtenint la localització (permisos o hardware GPS)", e)
        }
    }

    private fun sendLocationToWeb(lat: Double, lng: Double) {
        val js = "window.wiseWalkSetLocation && window.wiseWalkSetLocation($lat, $lng);"
        binding.webView.post {
            binding.webView.evaluateJavascript(js, null)
        }
    }

    private fun drawRoute(coordinatesJson: String) {
        try {
            val coordinates = JSONArray(coordinatesJson)
            val points = mutableListOf<GeoPoint>()

            for (i in 0 until coordinates.length()) {
                val point = coordinates.optJSONArray(i) ?: continue
                if (point.length() < 2) continue

                val lng = point.optDouble(0, Double.NaN)
                val lat = point.optDouble(1, Double.NaN)
                if (lat.isNaN() || lng.isNaN()) continue

                points.add(GeoPoint(lat, lng))
            }

            mapView.overlays.clear()
            if (points.size < 2) return

            val polyline = Polyline().apply {
                setPoints(points)
                outlinePaint.color = Color.parseColor("#2E7D32")
                outlinePaint.strokeWidth = 12f
            }
            mapView.overlays.add(polyline)

            val boundingBox = BoundingBox.fromGeoPoints(points)
            mapView.zoomToBoundingBox(boundingBox, true, (resources.displayMetrics.density * 72).toInt())
            mapView.invalidate()
        } catch (_: Throwable) {
            // Ignore malformed route payloads from JS bridge.
        }
    }

    private fun animateCameraForWalkMode(lat: Double, lng: Double, bearing: Float) {
        val point = GeoPoint(lat, lng)
        mapView.controller.animateTo(point, 18.0, 800)
        mapView.mapOrientation = -bearing
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

        } catch (_: Throwable) {}
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
                binding.webView.post { binding.webView.evaluateJavascript(js, null) }

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
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }

        try {
            val serviceIntent = Intent(this, StepTrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.w("WiseWalk", "Failed to start background tracking", e)
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
        fun startWalkLocationUpdates() {
            activity.runOnUiThread {
                if (!activity.hasLocationPermission()) {
                    activity.requestLocationPermission()
                    return@runOnUiThread
                }
                try {
                    activity.isWalkGpsModeActive = true
                    val intent = Intent(activity, StepTrackingService::class.java).apply {
                        action = StepTrackingService.ACTION_START_GPS
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        activity.startForegroundService(intent)
                    } else {
                        activity.startService(intent)
                    }
                } catch (e: Exception) {
                    Log.w("WiseWalk", "Failed to start walk location updates", e)
                    activity.isWalkGpsModeActive = false
                }
            }
        }

        @JavascriptInterface
        fun stopWalkLocationUpdates() {
            activity.runOnUiThread {
                activity.isWalkGpsModeActive = false
                activity.mapView.mapOrientation = 0f
                try {
                    val intent = Intent(activity, StepTrackingService::class.java).apply {
                        action = StepTrackingService.ACTION_STOP_GPS
                    }
                    activity.startService(intent)
                } catch (e: Exception) {
                    Log.w("WiseWalk", "Failed to stop walk location updates", e)
                }
            }
        }

        @JavascriptInterface
        fun drawRoute(coordinatesJson: String) {
            activity.runOnUiThread { activity.drawRoute(coordinatesJson) }
        }

        @JavascriptInterface
        fun setMapModeNative(enabled: Boolean) {
            activity.runOnUiThread {
                activity.mapView.visibility = if (enabled) View.VISIBLE else View.GONE
                if (!enabled) {
                    activity.mapView.mapOrientation = 0f
                }
            }
        }

        @JavascriptInterface
        fun logError(message: String) {
            Log.e("WiseWalkJS", message)
            if (message.contains("Error", ignoreCase = true)) {
                activity.runOnUiThread {
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun exportDebugLog(content: String) {
            activity.runOnUiThread {
                try {
                    val logsDir = File(activity.cacheDir, "logs")
                    if (!logsDir.exists() && !logsDir.mkdirs()) {
                        throw IllegalStateException("No s'ha pogut crear cacheDir/logs")
                    }

                    val fileName = "wisewalk-debug-${System.currentTimeMillis()}.txt"
                    val logFile = File(logsDir, fileName)
                    logFile.writeText(content)

                    val uri = FileProvider.getUriForFile(
                        activity,
                        "${activity.packageName}.fileprovider",
                        logFile
                    )

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "WiseWalk debug log")
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    activity.startActivity(Intent.createChooser(shareIntent, "Compartir log de depuració"))
                    Toast.makeText(activity, "Log exportat correctament", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("WiseWalk", "Error exportant debug log", e)
                    Toast.makeText(activity, "No s'ha pogut exportar el log", Toast.LENGTH_SHORT).show()
                }
            }
        }

    }
}
