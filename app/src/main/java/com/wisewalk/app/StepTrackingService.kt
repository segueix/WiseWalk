package com.wisewalk.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class StepTrackingService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var stepCounter: Sensor? = null

    private val prefs by lazy { getSharedPreferences("wisewalk_prefs", Context.MODE_PRIVATE) }

    private var lastTotalSteps: Long? = null
    private var lastEventTimestampNs: Long? = null
    private var walkingTimeMs: Long = 0L

    private var strideMeters: Double = 0.75
    private var goalKmActive: Double = 0.0
    private var activePlan: String = "maintain"
    private var weightKg: Double = 70.0
    private var waterGoalGlasses: Int = 8
    private var glassMl: Int = 300
    private var wakeTime: String = "07:00"
    private var sleepTime: String = "23:00"

    private var waterReminderReceiver: BroadcastReceiver? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var lastLocationForBearing: android.location.Location? = null

    companion object {
        const val CHANNEL_ID_SERVICE = "wisewalk_tracking"
        const val CHANNEL_ID_GOAL = "wisewalk_goal"
        const val CHANNEL_ID_WATER = "wisewalk_water"
        const val NOTIF_ID_SERVICE = 1
        const val NOTIF_ID_GOAL = 1001
        const val NOTIF_ID_WATER = 2001
        
        const val ACTION_STATS_UPDATE = "com.wisewalk.app.STATS_UPDATE"
        const val ACTION_WATER_REMINDER = "com.wisewalk.app.WATER_REMINDER"
        const val ACTION_WATER_DRINK = "com.wisewalk.app.WATER_DRINK"
        const val ACTION_LOCATION_UPDATE = "com.wisewalk.app.LOCATION_UPDATE"
        const val ACTION_START_GPS = "com.wisewalk.app.START_GPS"
        const val ACTION_STOP_GPS = "com.wisewalk.app.STOP_GPS"
        const val EXTRA_STATS_JSON = "stats_json"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_BEARING = "bearing"

        @Volatile
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        createNotificationChannels()
        loadProfileFromPrefs()
        registerWaterReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_GPS -> {
                if (!isRunning) {
                    isRunning = true
                    resetIfNewDay()
                    loadProfileFromPrefs()
                    startTrackingForeground()
                    registerStepListener()
                    scheduleWaterReminders()
                }
                startLocationUpdates()
            }
            ACTION_STOP_GPS -> stopLocationUpdates()
            else -> {
                isRunning = true
                resetIfNewDay()
                loadProfileFromPrefs()
                startTrackingForeground()
                registerStepListener()
                scheduleWaterReminders()
            }
        }
        return START_STICKY
    }

    private fun startTrackingForeground() {
        val notification = buildServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIF_ID_SERVICE, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        isRunning = false
        unregisterStepListener()
        unregisterWaterReceiver()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerStepListener() {
        val s = stepCounter ?: return
        sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun unregisterStepListener() {
        sensorManager.unregisterListener(this)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (locationCallback != null) return

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setWaitForAccurateLocation(true)
            .setMinUpdateIntervalMillis(500)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val previousLocation = lastLocationForBearing
                    val bearing = when {
                        location.hasBearing() -> location.bearing
                        previousLocation != null -> {
                            computeBearing(
                                previousLocation.latitude,
                                previousLocation.longitude,
                                location.latitude,
                                location.longitude
                            )
                        }
                        else -> 0f
                    }
                    lastLocationForBearing = location

                    val locationIntent = Intent(ACTION_LOCATION_UPDATE).apply {
                        putExtra(EXTRA_LAT, location.latitude)
                        putExtra(EXTRA_LNG, location.longitude)
                        putExtra(EXTRA_BEARING, bearing)
                        setPackage(packageName)
                    }
                    sendBroadcast(locationIntent)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            Looper.getMainLooper()
        )
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
            lastLocationForBearing = null
        }
    }

    private fun computeBearing(startLat: Double, startLng: Double, endLat: Double, endLng: Double): Float {
        val startLatRad = Math.toRadians(startLat)
        val endLatRad = Math.toRadians(endLat)
        val deltaLngRad = Math.toRadians(endLng - startLng)

        val y = sin(deltaLngRad) * cos(endLatRad)
        val x = cos(startLatRad) * sin(endLatRad) - sin(startLatRad) * cos(endLatRad) * cos(deltaLngRad)
        val bearingDeg = Math.toDegrees(atan2(y, x))
        return (((bearingDeg + 360.0) % 360.0)).toFloat()
    }

    private fun registerWaterReceiver() {
        waterReminderReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_WATER_REMINDER -> sendWaterReminder()
                    ACTION_WATER_DRINK -> {
                        addWaterGlass()
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(NOTIF_ID_WATER)
                        // Schedule next reminder
                        scheduleNextWaterReminder()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_WATER_REMINDER)
            addAction(ACTION_WATER_DRINK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(waterReminderReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(waterReminderReceiver, filter)
        }
    }

    private fun unregisterWaterReceiver() {
        waterReminderReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "Seguiment de passos",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mostra el progrés del comptador de passos"
                setShowBadge(false)
            }
            nm.createNotificationChannel(serviceChannel)
            
            val goalChannel = NotificationChannel(
                CHANNEL_ID_GOAL,
                "Objectius WiseWalk",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificacions quan s'assoleix l'objectiu diari"
            }
            nm.createNotificationChannel(goalChannel)
            
            val waterChannel = NotificationChannel(
                CHANNEL_ID_WATER,
                "Recordatoris d'hidratació",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Recordatoris per beure aigua"
            }
            nm.createNotificationChannel(waterChannel)
        }
    }

    private fun buildServiceNotification(): Notification {
        val stepsToday = getTodaySteps()
        val distanceKm = stepsToday.toDouble() * strideMeters / 1000.0
        val waterGlasses = getTodayWaterGlasses()
        val pct = if (goalKmActive > 0) ((distanceKm / goalKmActive) * 100).toInt().coerceIn(0, 100) else 0
        
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("WiseWalk actiu")
            .setContentText("$stepsToday passos · ${String.format("%.1f", distanceKm)} km ($pct%) · 💧$waterGlasses gots")
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateServiceNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_SERVICE, buildServiceNotification())
    }

    private fun todayKey(): String = LocalDate.now().toString()

    private fun resetIfNewDay() {
        val today = todayKey()
        val saved = prefs.getString("today_key", null)
        if (saved != today) {
            prefs.edit()
                .putString("today_key", today)
                .remove("baseline_steps_total")
                .remove("walking_time_ms")
                .remove("last_total_steps")
                .remove("last_event_ns")
                .remove("notified_${today}_maintain")
                .remove("notified_${today}_optimize")
                .apply()
            lastTotalSteps = null
            lastEventTimestampNs = null
            walkingTimeMs = 0L
        } else {
            walkingTimeMs = prefs.getLong("walking_time_ms", 0L)
            lastTotalSteps = if (prefs.contains("last_total_steps")) prefs.getLong("last_total_steps", 0L) else null
            lastEventTimestampNs = if (prefs.contains("last_event_ns")) prefs.getLong("last_event_ns", 0L) else null
        }
    }

    private fun baselineTotalOrNull(): Long? {
        return if (prefs.contains("baseline_steps_total")) prefs.getLong("baseline_steps_total", 0L) else null
    }

    private fun setBaselineTotal(v: Long) {
        prefs.edit().putLong("baseline_steps_total", v).apply()
    }

    private fun getTodaySteps(): Long {
        val baseline = baselineTotalOrNull() ?: return 0L
        val last = lastTotalSteps ?: return 0L
        return (last - baseline).coerceAtLeast(0L)
    }

    // Water tracking (persistent)
    private fun getTodayWaterGlasses(): Int {
        return prefs.getInt("water_glasses_${todayKey()}", 0)
    }

    private fun setTodayWaterGlasses(glasses: Int) {
        prefs.edit().putInt("water_glasses_${todayKey()}", glasses).apply()
        updateServiceNotification()
        broadcastWaterUpdate(glasses)
    }

    fun addWaterGlass() {
        val current = getTodayWaterGlasses()
        val newVal = current + 1
        setTodayWaterGlasses(newVal)
        
        // Broadcast to update UI
        val intent = Intent(ACTION_STATS_UPDATE).apply {
            putExtra("water_update", true)
            putExtra("water_glasses", newVal)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastWaterUpdate(glasses: Int) {
        val intent = Intent(ACTION_STATS_UPDATE).apply {
            putExtra("water_update", true)
            putExtra("water_glasses", glasses)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // Water reminders (fins 3h abans de dormir)
    private fun scheduleWaterReminders() {
        // Cancel existing reminders first
        cancelWaterReminders()
        
        // Check if within allowed time (not within 3h of sleep)
        if (!isWithinWaterTime()) return
        
        // Schedule first reminder in 90 minutes
        scheduleNextWaterReminder()
    }

    private fun isWithinWaterTime(): Boolean {
        val now = java.time.LocalTime.now()
        val sleepH = sleepTime.split(":").getOrNull(0)?.toIntOrNull() ?: 23
        val sleepM = sleepTime.split(":").getOrNull(1)?.toIntOrNull() ?: 0
        val sleepLocalTime = java.time.LocalTime.of(sleepH, sleepM)
        val stopTime = sleepLocalTime.minusHours(3)
        
        // If stop time is after midnight (e.g., sleep at 1am, stop at 10pm previous day)
        return if (stopTime.isAfter(sleepLocalTime)) {
            now.isBefore(sleepLocalTime) || now.isAfter(stopTime)
        } else {
            now.isBefore(stopTime)
        }
    }

    private fun scheduleNextWaterReminder() {
        if (!isWithinWaterTime()) return
        if (getTodayWaterGlasses() >= waterGoalGlasses) return
        
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val reminderIntervalMs = 90 * 60 * 1000L // 90 minutes
        
        val intent = Intent(ACTION_WATER_REMINDER).apply {
            setPackage(packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            NOTIF_ID_WATER,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + reminderIntervalMs,
                pendingIntent
            )
        } catch (_: SecurityException) {
            // Fallback for devices without exact alarm permission
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + reminderIntervalMs,
                pendingIntent
            )
        }
    }

    private fun cancelWaterReminders() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ACTION_WATER_REMINDER).apply {
            setPackage(packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            NOTIF_ID_WATER,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun sendWaterReminder() {
        val currentGlasses = getTodayWaterGlasses()
        if (currentGlasses >= waterGoalGlasses) return
        if (!isWithinWaterTime()) return
        
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Open app intent
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("add_water", true)
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Drink water action - direct broadcast
        val drinkIntent = Intent(ACTION_WATER_DRINK).apply {
            setPackage(packageName)
        }
        val drinkPendingIntent = PendingIntent.getBroadcast(
            this, 100, drinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val remaining = waterGoalGlasses - currentGlasses
        val litersTotal = (waterGoalGlasses * glassMl / 1000.0)
        
        val notif = NotificationCompat.Builder(this, CHANNEL_ID_WATER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("💧 Hora de beure aigua!")
            .setContentText("Portes $currentGlasses de $waterGoalGlasses gots (${glassMl}ml). Falten $remaining.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Portes $currentGlasses de $waterGoalGlasses gots.\nObjectiu: ${String.format("%.1f", litersTotal)}L\nFalten $remaining gots per completar l'objectiu d'avui."))
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_input_add, "✓ +1 got begut", drinkPendingIntent)
            .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
            .build()

        nm.notify(NOTIF_ID_WATER, notif)
    }

    private fun setNotifiedToday(plan: String) {
        prefs.edit().putBoolean("notified_${todayKey()}_${plan}", true).apply()
    }

    private fun wasNotifiedToday(plan: String): Boolean {
        return prefs.getBoolean("notified_${todayKey()}_${plan}", false)
    }

    private fun loadProfileFromPrefs() {
        val sex = prefs.getString("profile_sex", "M") ?: "M"
        val heightCm = prefs.getInt("profile_height_cm", 170).toDouble()
        strideMeters = heightCm / 100.0 * when (sex.uppercase()) {
            "M" -> 0.415
            "F" -> 0.413
            else -> 0.414
        }
        weightKg = prefs.getFloat("profile_weight_kg", 70f).toDouble()
        activePlan = prefs.getString("active_plan", "maintain") ?: "maintain"
        goalKmActive = prefs.getFloat("goal_km_${activePlan}", 0f).toDouble()
        waterGoalGlasses = prefs.getInt("water_goal_glasses", 8)
        glassMl = prefs.getInt("glass_ml", 300)
        wakeTime = prefs.getString("wake_time", "07:00") ?: "07:00"
        sleepTime = prefs.getString("sleep_time", "23:00") ?: "23:00"
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        resetIfNewDay()

        val total = event.values[0].toLong()
        val baseline = baselineTotalOrNull()
        if (baseline == null) {
            setBaselineTotal(total)
        }

        val base = baselineTotalOrNull() ?: total
        val stepsToday = (total - base).coerceAtLeast(0L)

        // Walking time estimation
        val lastT = lastTotalSteps
        val lastNs = lastEventTimestampNs
        if (lastT != null && lastNs != null) {
            val deltaSteps = total - lastT
            val deltaMs = (event.timestamp - lastNs) / 1_000_000L
            if (deltaSteps > 0 && deltaMs in 1..300_000) {
                walkingTimeMs += deltaMs
            }
        }
        lastTotalSteps = total
        lastEventTimestampNs = event.timestamp

        // Save to prefs (persistent)
        prefs.edit()
            .putLong("walking_time_ms", walkingTimeMs)
            .putLong("last_total_steps", total)
            .putLong("last_event_ns", event.timestamp)
            .apply()

        val distanceKm = stepsToday.toDouble() * strideMeters / 1000.0
        val walkingMin = (walkingTimeMs / 60000L).toInt()
        val speedKmh = if (walkingTimeMs > 0L) {
            distanceKm / (walkingTimeMs.toDouble() / 3_600_000.0)
        } else 0.0
        
        val met = (2.0 + (speedKmh - 3.0) * 0.5).coerceIn(2.5, 5.0)
        val kcal = ((met * 3.5 * weightKg) / 200.0) * (walkingTimeMs / 60000.0)

        val goalReached = goalKmActive > 0.0 && distanceKm >= goalKmActive

        val stats = JSONObject().apply {
            put("date", todayKey())
            put("steps", stepsToday)
            put("distanceKm", round1(distanceKm))
            put("walkingMin", walkingMin)
            put("avgSpeedKmh", round1(speedKmh))
            put("goalKm", round1(goalKmActive))
            put("kcal", Math.round(kcal))
            put("activePlan", activePlan)
            put("goalReached", goalReached)
            put("waterGlasses", getTodayWaterGlasses())
        }

        broadcastStats(stats)
        updateServiceNotification()

        if (goalReached && !wasNotifiedToday(activePlan)) {
            setNotifiedToday(activePlan)
            sendGoalNotification()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun round1(v: Double): Double = kotlin.math.round(v * 10.0) / 10.0

    private fun broadcastStats(stats: JSONObject) {
        val intent = Intent(ACTION_STATS_UPDATE).apply {
            putExtra(EXTRA_STATS_JSON, stats.toString())
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun sendGoalNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notif = NotificationCompat.Builder(this, CHANNEL_ID_GOAL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🎉 Objectiu assolit!")
            .setContentText("Has completat l'objectiu de caminar d'avui. Bona feina!")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
            .build()

        nm.notify(NOTIF_ID_GOAL, notif)
    }
}
