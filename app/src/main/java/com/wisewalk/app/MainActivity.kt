package com.wisewalk.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.core.content.FileProvider
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import java.io.File
import android.os.Looper
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.view.View
import android.widget.ImageButton
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
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("wisewalk_prefs", Context.MODE_PRIVATE) }
    private lateinit var mapView: MapView

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null
    private var locationReceiver: BroadcastReceiver? = null
    private var isWalkGpsModeActive: Boolean = false
    private var isCompassEnabled: Boolean = false
    private var pendingLocationRequest: Boolean = false
    private var oneShotLocationCallback: LocationCallback? = null
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private lateinit var myLocationOverlay: MyLocationNewOverlay
    private var routePolyline: Polyline? = null
    private var destinationMarker: PulsingMarkerOverlay? = null
    private var snappedLocationOverlay: SnappedLocationOverlay? = null
    private var isProgrammaticMapMove: Boolean = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build()
            )
        }

        Configuration.getInstance().load(applicationContext, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initMap()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

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
                        val geoPoint = GeoPoint(lat, lng)
                        isProgrammaticMapMove = true
                        myLocationOverlay.enableFollowLocation()
                        mapView.controller.animateTo(geoPoint, mapView.zoomLevelDouble, 300L)
                        mapView.postDelayed({ isProgrammaticMapMove = false }, 400)
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
        if (::myLocationOverlay.isInitialized) {
            myLocationOverlay.enableMyLocation()
            if (isWalkGpsModeActive) {
                myLocationOverlay.enableFollowLocation()
            }
        }
        destinationMarker?.startAnimation(mapView)
        if (isWalkGpsModeActive && isCompassEnabled) {
            rotationSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        if (::myLocationOverlay.isInitialized) myLocationOverlay.disableMyLocation()
        destinationMarker?.stopAnimation()
        sensorManager.unregisterListener(this)
    }

    override fun onStop() {
        super.onStop()
        removeOneShotLocationCallback()
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
        removeOneShotLocationCallback()
        pendingGeolocationCallback = null
        pendingGeolocationOrigin = null
        super.onDestroy()
    }

    private fun removeOneShotLocationCallback() {
        oneShotLocationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            oneShotLocationCallback = null
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun initMap() {
        mapView = binding.mapView
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.setBuiltInZoomControls(false)
        mapView.minZoomLevel = 3.0
        mapView.maxZoomLevel = 20.0
        mapView.controller.setZoom(15.0)
        mapView.isHorizontalMapRepetitionEnabled = false
        mapView.isVerticalMapRepetitionEnabled = false

        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView)
        myLocationOverlay.enableMyLocation()
        myLocationOverlay.enableFollowLocation()
        mapView.overlays.add(myLocationOverlay)

        findViewById<View>(R.id.btn_zoom_in).setOnClickListener {
            try {
                val currentZoom = mapView.zoomLevelDouble
                if (currentZoom < mapView.maxZoomLevel) {
                    isProgrammaticMapMove = true
                    mapView.controller.animateTo(
                        mapView.mapCenter,
                        (currentZoom + 1.0).coerceAtMost(mapView.maxZoomLevel),
                        300L
                    )
                    mapView.postDelayed({ isProgrammaticMapMove = false }, 400)
                }
            } catch (e: Exception) {
                Log.w("WiseWalk", "Error during zoom in", e)
                isProgrammaticMapMove = false
            }
        }
        findViewById<View>(R.id.btn_zoom_out).setOnClickListener {
            try {
                val currentZoom = mapView.zoomLevelDouble
                if (currentZoom > mapView.minZoomLevel) {
                    isProgrammaticMapMove = true
                    mapView.controller.animateTo(
                        mapView.mapCenter,
                        (currentZoom - 1.0).coerceAtLeast(mapView.minZoomLevel),
                        300L
                    )
                    mapView.postDelayed({ isProgrammaticMapMove = false }, 400)
                }
            } catch (e: Exception) {
                Log.w("WiseWalk", "Error during zoom out", e)
                isProgrammaticMapMove = false
            }
        }
        findViewById<ImageButton>(R.id.btn_center_me).setOnClickListener {
            try {
                isProgrammaticMapMove = true
                myLocationOverlay.enableFollowLocation()
                val loc = myLocationOverlay.myLocation
                if (loc != null) {
                    mapView.controller.animateTo(loc, 18.0, 500L)
                } else {
                    getCurrentLocationAndCenter()
                }
                mapView.postDelayed({ isProgrammaticMapMove = false }, 600)
            } catch (e: Exception) {
                Log.w("WiseWalk", "Error centering map", e)
                isProgrammaticMapMove = false
            }
        }

        val compassBtn = findViewById<ImageButton>(R.id.btn_compass_toggle)
        compassBtn.alpha = 0.5f
        compassBtn.setOnClickListener {
            isCompassEnabled = !isCompassEnabled
            compassBtn.alpha = if (isCompassEnabled) 1.0f else 0.5f
            if (isCompassEnabled) {
                rotationSensor?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
                }
            } else {
                sensorManager.unregisterListener(this)
                mapView.mapOrientation = 0f
                mapView.invalidate()
            }
        }

        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                if (!isProgrammaticMapMove && !isWalkGpsModeActive) {
                    myLocationOverlay.disableFollowLocation()
                }
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                if (!isProgrammaticMapMove && !isWalkGpsModeActive) {
                    myLocationOverlay.disableFollowLocation()
                }
                return false
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentLocationAndCenter() {
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let {
                    val geoPoint = GeoPoint(it.latitude, it.longitude)
                    isProgrammaticMapMove = true
                    myLocationOverlay.enableFollowLocation()
                    mapView.controller.animateTo(geoPoint, 18.0, 500L)
                    mapView.postDelayed({ isProgrammaticMapMove = false }, 600)
                    sendLocationToWeb(it.latitude, it.longitude)
                }
            }
        } catch (e: Exception) {
            Log.w("WiseWalk", "Error getting location for centering", e)
        }
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
                pendingLocationRequest = true
                Log.d("WiseWalk", "getCurrentLocation: permís no concedit, sol·licitant permisos")
                requestLocationPermission()
                return
            }

            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            if (!gpsEnabled && !networkEnabled) {
                Log.w("WiseWalk", "getCurrentLocation: GPS i xarxa desactivats")
                sendLocationErrorToWeb("El GPS està desactivat. Activa'l a la configuració del dispositiu.")
                return
            }

            removeOneShotLocationCallback()

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
                    if (oneShotLocationCallback === this) {
                        oneShotLocationCallback = null
                    }
                }
            }
            oneShotLocationCallback = callback

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
        } catch (e: SecurityException) {
            Log.e("WiseWalk", "Error de permisos obtenint la localització (SecurityException)", e)
            sendLocationErrorToWeb("Permís de localització necessari. Autoritza'l a la configuració.")
            requestLocationPermission()
        } catch (e: IllegalStateException) {
            Log.e("WiseWalk", "Possible problema de hardware GPS/servei de localització no disponible", e)
            sendLocationErrorToWeb("Servei de localització no disponible. Comprova el GPS.")
        } catch (e: Exception) {
            Log.e("WiseWalk", "Error inesperat obtenint la localització (permisos o hardware GPS)", e)
            sendLocationErrorToWeb("Error obtenint la ubicació. Torna-ho a provar.")
        }
    }

    private fun sendLocationToWeb(lat: Double, lng: Double) {
        val js = "window.wiseWalkSetLocation && window.wiseWalkSetLocation($lat, $lng);"
        binding.webView.post {
            binding.webView.evaluateJavascript(js, null)
        }
    }

    private fun sendLocationErrorToWeb(message: String) {
        val escaped = message.replace("'", "\\'")
        val js = "window.wiseWalkOnLocationError && window.wiseWalkOnLocationError('$escaped');"
        binding.webView.post {
            binding.webView.evaluateJavascript(js, null)
        }
    }

    private fun drawRoute(coordinatesJson: String) {
        thread(name = "WiseWalkRouteParser") {
            try {
                val coordinates = JSONArray(coordinatesJson)
                Log.d("WiseWalk", "drawRoute: rebudes ${coordinates.length()} coordenades")
                val points = mutableListOf<GeoPoint>()

                for (i in 0 until coordinates.length()) {
                    val point = coordinates.optJSONArray(i) ?: continue
                    if (point.length() < 2) continue

                    val lng = point.optDouble(0, Double.NaN)
                    val lat = point.optDouble(1, Double.NaN)
                    if (lat.isNaN() || lng.isNaN()) continue

                    points.add(GeoPoint(lat, lng))
                }

                if (points.size < 2) {
                    Log.w("WiseWalk", "drawRoute: només ${points.size} punts vàlids, cal mínim 2")
                    return@thread
                }

                runOnUiThread {
                    try {
                        mapView.overlays.clear()
                        if (::myLocationOverlay.isInitialized) {
                            mapView.overlays.add(myLocationOverlay)
                        }
                        val polyline = Polyline().apply {
                            setPoints(points)
                            outlinePaint.color = Color.parseColor("#2E7D32")
                            outlinePaint.strokeWidth = 18f
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.strokeJoin = Paint.Join.ROUND
                            outlinePaint.isAntiAlias = true
                        }
                        routePolyline = polyline
                        mapView.overlays.add(polyline)

                        // Add pulsing destination marker at last point
                        destinationMarker?.stopAnimation()
                        val marker = PulsingMarkerOverlay(points.last())
                        destinationMarker = marker
                        mapView.overlays.add(marker)
                        marker.startAnimation(mapView)

                        // Add snapped location overlay on top of everything
                        snappedLocationOverlay?.let { mapView.overlays.remove(it) }
                        val snappedOverlay = SnappedLocationOverlay()
                        snappedLocationOverlay = snappedOverlay
                        mapView.overlays.add(snappedOverlay)

                        if (mapView.width > 0 && mapView.height > 0) {
                            isProgrammaticMapMove = true
                            val boundingBox = BoundingBox.fromGeoPoints(points)
                            mapView.zoomToBoundingBox(boundingBox, true, (resources.displayMetrics.density * 72).toInt())
                            mapView.postDelayed({ isProgrammaticMapMove = false }, 600)
                        }

                        mapView.invalidate()
                        Log.d("WiseWalk", "drawRoute: ruta dibuixada amb ${points.size} punts")
                    } catch (e: Throwable) {
                        Log.e("WiseWalk", "drawRoute: error dibuixant ruta a la UI", e)
                    }
                }
            } catch (e: Throwable) {
                Log.e("WiseWalk", "drawRoute: error processant coordenades de ruta", e)
            }
        }
    }

    private fun updateRoute(coordinatesJson: String) {
        thread(name = "WiseWalkRouteUpdate") {
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

                if (points.size < 2) return@thread

                runOnUiThread {
                    try {
                        routePolyline?.let { polyline ->
                            polyline.setPoints(points)
                            // Update destination marker position to last point
                            destinationMarker?.setPosition(points.last())
                            // Ensure snapped overlay stays on top
                            snappedLocationOverlay?.let { overlay ->
                                mapView.overlays.remove(overlay)
                                mapView.overlays.add(overlay)
                            }
                            mapView.invalidate()
                        } ?: run {
                            val polyline = Polyline().apply {
                                setPoints(points)
                                outlinePaint.color = Color.parseColor("#2E7D32")
                                outlinePaint.strokeWidth = 18f
                                outlinePaint.strokeCap = Paint.Cap.ROUND
                                outlinePaint.strokeJoin = Paint.Join.ROUND
                                outlinePaint.isAntiAlias = true
                            }
                            routePolyline = polyline
                            mapView.overlays.add(polyline)
                            mapView.invalidate()
                        }
                    } catch (e: Throwable) {
                        Log.e("WiseWalk", "updateRoute: error actualitzant ruta", e)
                    }
                }
            } catch (e: Throwable) {
                Log.e("WiseWalk", "updateRoute: error processant coordenades", e)
            }
        }
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

                if (pendingLocationRequest) {
                    pendingLocationRequest = false
                    if (hasLocationPermission()) {
                        getCurrentLocation()
                    } else {
                        sendLocationErrorToWeb("Permís de localització denegat.")
                    }
                }
            }
            LOCATION_PERMISSION_REQUEST -> {
                val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, granted, false)
                pendingGeolocationCallback = null
                pendingGeolocationOrigin = null

                if (granted) {
                    getCurrentLocation()
                } else {
                    sendLocationErrorToWeb("Permís de localització denegat.")
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
                    activity.myLocationOverlay.enableFollowLocation()
                    if (activity.isCompassEnabled) {
                        activity.rotationSensor?.let {
                            activity.sensorManager.registerListener(activity, it, SensorManager.SENSOR_DELAY_UI)
                        }
                    }
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
                activity.sensorManager.unregisterListener(activity)
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
            activity.drawRoute(coordinatesJson)
        }

        @JavascriptInterface
        fun updateRoute(coordinatesJson: String) {
            activity.updateRoute(coordinatesJson)
        }

        @JavascriptInterface
        fun updateSnappedPosition(lat: Double, lng: Double, snapped: Boolean) {
            activity.runOnUiThread {
                activity.snappedLocationOverlay?.updatePosition(lat, lng, snapped)
                activity.mapView.invalidate()
            }
        }

        @JavascriptInterface
        fun requestMapCenter() {
            activity.runOnUiThread {
                val center = activity.mapView.mapCenter
                val js = "window.wiseWalkOnMapCenterSelected && window.wiseWalkOnMapCenterSelected(${center.latitude}, ${center.longitude});"
                activity.binding.webView.evaluateJavascript(js, null)
            }
        }

        @JavascriptInterface
        fun setMapModeNative(enabled: Boolean) {
            activity.runOnUiThread {
                if (enabled) {
                    activity.mapView.visibility = View.VISIBLE
                    activity.findViewById<View>(R.id.mapControlsContainer).visibility = View.VISIBLE
                    activity.mapView.onResume()
                    if (activity::myLocationOverlay.isInitialized) {
                        activity.myLocationOverlay.enableMyLocation()
                        activity.myLocationOverlay.enableFollowLocation()
                    }
                    activity.mapView.invalidate()
                } else {
                    activity.mapView.visibility = View.GONE
                    activity.findViewById<View>(R.id.mapControlsContainer).visibility = View.GONE
                    activity.mapView.mapOrientation = 0f
                    activity.destinationMarker?.stopAnimation()
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
            thread(name = "WiseWalkDebugExport", start = true) {
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
                    activity.runOnUiThread {
                        try {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "WiseWalk debug log")
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            activity.startActivity(Intent.createChooser(shareIntent, "Compartir log de depuració"))
                            Toast.makeText(activity, "Log exportat correctament", Toast.LENGTH_SHORT).show()
                        } catch (e: ActivityNotFoundException) {
                            Log.w("WiseWalk", "No s'ha trobat cap app per compartir el log", e)
                            Toast.makeText(activity, "No hi ha cap app compatible per compartir", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Log.e("WiseWalk", "Error compartint debug log", e)
                            Toast.makeText(activity, "No s'ha pogut compartir el log", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WiseWalk", "Error exportant debug log", e)
                    activity.runOnUiThread {
                        Toast.makeText(activity, "No s'ha pogut exportar el log", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR && isWalkGpsModeActive && isCompassEnabled) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientationAngles = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            mapView.mapOrientation = -azimuthDeg
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
