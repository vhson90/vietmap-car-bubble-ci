package vn.vietmap.carbubble.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import vn.vietmap.carbubble.MainActivity
import vn.vietmap.carbubble.R
import vn.vietmap.carbubble.audio.AudioAlertManager
import vn.vietmap.carbubble.engine.OfflineSpatialEngine
import kotlin.math.*

/**
 * Dịch vụ chạy ngầm (Foreground Service) hiển thị Bong Bóng Tốc Độ & Cảnh Báo Giao Thông Offline
 * đè lên trên mọi ứng dụng (Google Maps, YouTube, Navitel, Apple CarPlay...) trên Android Box ô tô.
 */
class FloatingBubbleService : Service(), LocationListener {

    companion object {
        private const val TAG = "FloatingBubbleService"
        private const val TELEMETRY_TAG = "VietmapTelemetry"
        private const val NOTIFICATION_ID = 2026
        private const val CHANNEL_ID = "vietmap_car_bubble_channel"

        const val PREFS_NAME = "vietmap_car_bubble_prefs"
        const val KEY_IS_SIMULATION = "is_simulation_mode"
        const val KEY_SIM_SPEED_OVERRIDE = "sim_speed_override_kmh" // 0 = Tự động theo cung đường
        const val KEY_GPS_OFFSET_KMH = "gps_offset_kmh"
        const val KEY_OVERSPEED_THRESHOLD = "overspeed_threshold_kmh"
        const val KEY_VOICE_ENABLED = "voice_enabled"
        const val KEY_AUTO_START_BOOT = "auto_start_boot"
        const val KEY_BUBBLE_X = "bubble_pos_x"
        const val KEY_BUBBLE_Y = "bubble_pos_y"

        const val ACTION_START_BUBBLE = "vn.vietmap.carbubble.ACTION_START_BUBBLE"
        const val ACTION_STOP_BUBBLE = "vn.vietmap.carbubble.ACTION_STOP_BUBBLE"
        const val ACTION_RELOAD_SETTINGS = "vn.vietmap.carbubble.ACTION_RELOAD_SETTINGS"
        const val ACTION_RESET_SIMULATION = "vn.vietmap.carbubble.ACTION_RESET_SIMULATION"

        // Broadcast gửi trạng thái trực tiếp về MainActivity để hiển thị Telemetry
        const val ACTION_STATE_BROADCAST = "vn.vietmap.carbubble.ACTION_STATE_BROADCAST"

        @Volatile
        var isServiceRunning: Boolean = false
            private set
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var windowManager: WindowManager
    private lateinit var spatialEngine: OfflineSpatialEngine
    private lateinit var audioManager: AudioAlertManager
    private var locationManager: LocationManager? = null

    private var bubbleView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    // UI Components trên Bong bóng nổi
    private var rootBubbleContainer: View? = null
    private var speedCircleContainer: LinearLayout? = null
    private var tvCurrentSpeed: TextView? = null
    private var tvSpeedUnit: TextView? = null
    private var ivSpeedLimitSign: ImageView? = null
    private var alertExpandPanel: LinearLayout? = null
    private var ivUpcomingAlertIcon: ImageView? = null
    private var tvUpcomingAlertDistance: TextView? = null
    private var tvUpcomingAlertName: TextView? = null
    private var tvModeBadge: TextView? = null

    // Bộ nhớ đệm Bitmap biển báo từ assets/
    private val bitmapCache = HashMap<String, Bitmap>()
    private var currentLimitAssetPath: String = ""
    private var currentAlertAssetPath: String = ""

    // Cấu hình người dùng
    private var isSimulationMode: Boolean = false
    private var simSpeedOverrideKmh: Int = 0
    private var gpsOffsetKmh: Int = 0
    private var overspeedThresholdKmh: Int = 3
    private var isAlertPanelForceExpanded: Boolean = true

    // Trạng thái di chuyển thực tế / mô phỏng
    private var currentLat: Double = 10.772109
    private var currentLon: Double = 106.698272
    private var currentBearing: Float = 65.0f
    private var currentSpeedKmh: Int = 0
    private var lastKnownGpsLat: Double = 0.0
    private var lastKnownGpsLon: Double = 0.0

    // Bộ mô phỏng lộ trình 30km xuyên TP.HCM (Dành cho việc test trong nhà hoặc khi xe đang đỗ)
    private data class RouteWaypoint(val lat: Double, val lon: Double)
    private val simRoutePoints = ArrayList<RouteWaypoint>()
    private var simRouteIndex: Int = 0
    private var simSubStep: Double = 0.0
    private val mainHandler = Handler(Looper.getMainLooper())

    private val simulationRunnable = object : Runnable {
        override fun run() {
            if (!isServiceRunning || !isSimulationMode) return
            stepSimulation()
            mainHandler.postDelayed(this, 350L) // Cập nhật mượt ~3 lần/giây
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        spatialEngine = OfflineSpatialEngine(this)
        val dbOk = spatialEngine.initializeDatabase()
        Log.i(TELEMETRY_TAG, "INIT_SERVICE: db_initialized=$dbOk")

        audioManager = AudioAlertManager(this)

        loadSettings()
        loadSample30KmRoute()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Đang hoạt động • Chờ tín hiệu GPS"))

        createFloatingBubbleWindow()
        applyLocationMode()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Hỗ trợ nhận tham số điều khiển trực tiếp qua ADB / Intent
        if (intent != null) {
            val editor = prefs.edit()
            var hasOverrides = false
            if (intent.hasExtra("is_sim")) {
                editor.putBoolean(KEY_IS_SIMULATION, intent.getBooleanExtra("is_sim", true))
                hasOverrides = true
            }
            if (intent.hasExtra("sim_speed")) {
                editor.putInt(KEY_SIM_SPEED_OVERRIDE, intent.getIntExtra("sim_speed", 0))
                hasOverrides = true
            }
            if (intent.hasExtra("sim_index")) {
                val idx = intent.getIntExtra("sim_index", 0)
                if (simRoutePoints.isNotEmpty()) {
                    simRouteIndex = idx.coerceIn(0, simRoutePoints.size - 1)
                    simSubStep = 0.0
                }
            }
            if (intent.hasExtra("bubble_x") && intent.hasExtra("bubble_y")) {
                val bx = intent.getIntExtra("bubble_x", 32)
                val by = intent.getIntExtra("bubble_y", 160)
                editor.putInt(KEY_BUBBLE_X, bx)
                editor.putInt(KEY_BUBBLE_Y, by)
                hasOverrides = true
                if (::layoutParams.isInitialized && bubbleView != null) {
                    layoutParams.x = bx
                    layoutParams.y = by
                    try {
                        windowManager.updateViewLayout(bubbleView, layoutParams)
                    } catch (_: Exception) {
                    }
                }
            }
            if (hasOverrides) {
                editor.apply()
            }
        }

        when (intent?.action) {
            ACTION_STOP_BUBBLE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD_SETTINGS, ACTION_START_BUBBLE -> {
                val oldSimMode = isSimulationMode
                loadSettings()
                if (oldSimMode != isSimulationMode) {
                    applyLocationMode()
                } else {
                    refreshBubbleUI()
                }
            }
            ACTION_RESET_SIMULATION -> {
                simRouteIndex = 0
                simSubStep = 0.0
                spatialEngine.resetLatchedState(60)
            }
        }
        return START_STICKY
    }

    private fun loadSettings() {
        isSimulationMode = prefs.getBoolean(KEY_IS_SIMULATION, false)
        simSpeedOverrideKmh = prefs.getInt(KEY_SIM_SPEED_OVERRIDE, 0)
        gpsOffsetKmh = prefs.getInt(KEY_GPS_OFFSET_KMH, 0)
        overspeedThresholdKmh = prefs.getInt(KEY_OVERSPEED_THRESHOLD, 3)
        audioManager.isVoiceEnabled = prefs.getBoolean(KEY_VOICE_ENABLED, true)
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun createFloatingBubbleWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Chưa được cấp quyền vẽ trên ứng dụng khác (SYSTEM_ALERT_WINDOW)")
            return
        }

        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.layout_floating_bubble, null)

        rootBubbleContainer = bubbleView?.findViewById(R.id.rootBubbleContainer)
        speedCircleContainer = bubbleView?.findViewById(R.id.speedCircleContainer)
        tvCurrentSpeed = bubbleView?.findViewById(R.id.tvCurrentSpeed)
        tvSpeedUnit = bubbleView?.findViewById(R.id.tvSpeedUnit)
        ivSpeedLimitSign = bubbleView?.findViewById(R.id.ivSpeedLimitSign)
        alertExpandPanel = bubbleView?.findViewById(R.id.alertExpandPanel)
        ivUpcomingAlertIcon = bubbleView?.findViewById(R.id.ivUpcomingAlertIcon)
        tvUpcomingAlertDistance = bubbleView?.findViewById(R.id.tvUpcomingAlertDistance)
        tvUpcomingAlertName = bubbleView?.findViewById(R.id.tvUpcomingAlertName)
        tvModeBadge = bubbleView?.findViewById(R.id.tvModeBadge)

        // Nạp biển tốc độ 60 km/h mặc định ban đầu
        setAssetImage(ivSpeedLimitSign, "speed/60.png", isSpeedSign = true)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val savedX = prefs.getInt(KEY_BUBBLE_X, 28)
        val savedY = prefs.getInt(KEY_BUBBLE_Y, 150)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        setupTouchAndDragListener()

        try {
            windowManager.addView(bubbleView, layoutParams)
            Log.i(TELEMETRY_TAG, "OVERLAY_WINDOW_ADDED: x=$savedX, y=$savedY")
        } catch (e: Exception) {
            Log.e(TAG, "Không thể thêm Bubble View vào WindowManager: ${e.message}", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchAndDragListener() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var touchDownTime = 0L

        bubbleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    touchDownTime = SystemClock.elapsedRealtime()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(bubbleView, layoutParams)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = SystemClock.elapsedRealtime() - touchDownTime
                    if (isDragging) {
                        prefs.edit()
                            .putInt(KEY_BUBBLE_X, layoutParams.x)
                            .putInt(KEY_BUBBLE_Y, layoutParams.y)
                            .apply()
                    } else {
                        if (elapsed >= 550L) {
                            val openIntent = Intent(this, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            startActivity(openIntent)
                        } else {
                            isAlertPanelForceExpanded = !isAlertPanelForceExpanded
                            refreshBubbleUI()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun applyLocationMode() {
        mainHandler.removeCallbacks(simulationRunnable)
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {
        }

        if (isSimulationMode) {
            tvModeBadge?.visibility = View.VISIBLE
            tvModeBadge?.text = "SIM 30KM"
            mainHandler.post(simulationRunnable)
            updateForegroundNotification("Chế độ Mô phỏng 30km TP.HCM đang chạy")
            Log.i(TELEMETRY_TAG, "MODE_SWITCH: SIMULATION_30KM_ACTIVE")
        } else {
            tvModeBadge?.visibility = View.GONE
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            try {
                if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                    locationManager?.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        500L,
                        1.0f,
                        this
                    )
                }
                if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                    locationManager?.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1000L,
                        2.0f,
                        this
                    )
                }
                updateForegroundNotification("Đang thu tín hiệu GPS thực tế trên xe...")
                Log.i(TELEMETRY_TAG, "MODE_SWITCH: REAL_GPS_ACTIVE")
            } catch (e: SecurityException) {
                Log.e(TAG, "Thiếu quyền truy cập GPS: ${e.message}")
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        if (isSimulationMode) return

        currentLat = location.latitude
        currentLon = location.longitude

        val rawSpeedKmh = if (location.hasSpeed()) {
            (location.speed * 3.6f).roundToInt()
        } else {
            0
        }
        currentSpeedKmh = if (rawSpeedKmh <= 2) 0 else max(0, rawSpeedKmh + gpsOffsetKmh)

        if (location.hasBearing() && location.bearing > 0f) {
            currentBearing = location.bearing
        } else if (lastKnownGpsLat != 0.0 && lastKnownGpsLon != 0.0) {
            val movedDist = haversineMeters(lastKnownGpsLat, lastKnownGpsLon, currentLat, currentLon)
            if (movedDist >= 3.5) {
                currentBearing = calculateBearing(lastKnownGpsLat, lastKnownGpsLon, currentLat, currentLon).toFloat()
            }
        }
        lastKnownGpsLat = currentLat
        lastKnownGpsLon = currentLon

        processSpatialQueryAndUpdateUI()
    }

    /**
     * Mô phỏng xe chạy dọc theo lộ trình 30km TP.HCM (Chợ Bến Thành -> Hầm Thủ Thiêm -> Mai Chí Thọ -> Võ Nguyên Giáp -> XLHN -> Suối Tiên)
     */
    private fun stepSimulation() {
        if (simRoutePoints.size < 2) return

        val p1 = simRoutePoints[simRouteIndex]
        val nextIdx = (simRouteIndex + 1) % simRoutePoints.size
        val p2 = simRoutePoints[nextIdx]

        val segDistM = max(1.0, haversineMeters(p1.lat, p1.lon, p2.lat, p2.lon))
        currentBearing = calculateBearing(p1.lat, p1.lon, p2.lat, p2.lon).toFloat()

        val currentLimit = spatialEngine.latchedSpeedLimit
        currentSpeedKmh = if (simSpeedOverrideKmh > 0) {
            simSpeedOverrideKmh
        } else {
            val wave = (sin(simRouteIndex * 0.25) * 6.0).roundToInt()
            max(35, currentLimit + wave)
        }

        val stepMeters = (currentSpeedKmh / 3.6) * 0.35 * 2.2
        simSubStep += stepMeters / segDistM

        while (simSubStep >= 1.0) {
            simSubStep -= 1.0
            simRouteIndex = (simRouteIndex + 1) % (simRoutePoints.size - 1)
        }

        val a = simRoutePoints[simRouteIndex]
        val b = simRoutePoints[(simRouteIndex + 1) % simRoutePoints.size]
        currentLat = a.lat + (b.lat - a.lat) * simSubStep
        currentLon = a.lon + (b.lon - a.lon) * simSubStep

        processSpatialQueryAndUpdateUI()
    }

    private fun processSpatialQueryAndUpdateUI() {
        val result = spatialEngine.queryLocation(currentLat, currentLon, currentBearing)
        val isOverspeed = (result.currentSpeedLimit > 0) &&
                (currentSpeedKmh > result.currentSpeedLimit + overspeedThresholdKmh)

        val alertInfo = if (result.upcomingAlert != null) {
            "${result.upcomingAlert.getTypeName()} (${result.upcomingAlert.distanceMeters.roundToInt()}m)"
        } else {
            "NONE"
        }

        Log.i(
            TELEMETRY_TAG,
            String.format(
                "STATE: wp=%d | gps=(%.5f,%.5f) | brg=%.0f | speed=%d km/h | limit=%d km/h | overspeed=%b | nearby=%d | alert=%s",
                simRouteIndex,
                currentLat,
                currentLon,
                currentBearing,
                currentSpeedKmh,
                result.currentSpeedLimit,
                isOverspeed,
                result.nearbyPointCount,
                alertInfo
            )
        )

        audioManager.checkAndAnnounceSpeedLimitChange(result.currentSpeedLimit)
        audioManager.checkAndAnnounceUpcomingAlert(result.upcomingAlert)
        audioManager.checkAndAnnounceOverspeed(
            currentSpeedKmh = currentSpeedKmh,
            speedLimitKmh = result.currentSpeedLimit,
            thresholdKmh = overspeedThresholdKmh
        )

        updateBubbleWidgets(result.currentSpeedLimit, result.activeZoneName, result.upcomingAlert)

        val bIntent = Intent(ACTION_STATE_BROADCAST).apply {
            setPackage(packageName)
            putExtra("speed_kmh", currentSpeedKmh)
            putExtra("speed_limit", result.currentSpeedLimit)
            putExtra("zone_name", result.activeZoneName)
            putExtra("lat", currentLat)
            putExtra("lon", currentLon)
            putExtra("bearing", currentBearing)
            putExtra("nearby_count", result.nearbyPointCount)
            if (result.upcomingAlert != null) {
                putExtra("has_alert", true)
                putExtra("alert_name", result.upcomingAlert.getTypeName())
                putExtra("alert_dist_m", result.upcomingAlert.distanceMeters.roundToInt())
                putExtra("alert_asset", result.upcomingAlert.getAlertAssetPath())
            } else {
                putExtra("has_alert", false)
            }
        }
        sendBroadcast(bIntent)
    }

    private fun refreshBubbleUI() {
        processSpatialQueryAndUpdateUI()
    }

    private fun updateBubbleWidgets(
        speedLimit: Int,
        zoneName: String,
        upcomingAlert: vn.vietmap.carbubble.engine.EdogPoint?
    ) {
        tvCurrentSpeed?.text = currentSpeedKmh.toString()

        val isOverspeed = (speedLimit > 0) && (currentSpeedKmh > speedLimit + overspeedThresholdKmh)
        if (isOverspeed) {
            speedCircleContainer?.setBackgroundResource(R.drawable.bg_speedometer_overspeed)
            tvCurrentSpeed?.setTextColor(0xFFFFFFFF.toInt())
        } else {
            speedCircleContainer?.setBackgroundResource(R.drawable.bg_speedometer_normal)
            tvCurrentSpeed?.setTextColor(0xFF38BDF8.toInt())
        }

        val validSignSpeed = if (speedLimit in listOf(30, 40, 50, 60, 70, 80, 90, 100, 120)) speedLimit else 60
        val signAsset = "speed/${validSignSpeed}.png"
        if (signAsset != currentLimitAssetPath) {
            currentLimitAssetPath = signAsset
            setAssetImage(ivSpeedLimitSign, signAsset, isSpeedSign = true)
        }

        if (upcomingAlert != null) {
            alertExpandPanel?.visibility = View.VISIBLE
            val distM = upcomingAlert.distanceMeters.roundToInt()
            tvUpcomingAlertDistance?.text = "${distM} m"
            tvUpcomingAlertName?.text = upcomingAlert.getTypeName()

            val alertAsset = upcomingAlert.getAlertAssetPath()
            if (alertAsset != currentAlertAssetPath) {
                currentAlertAssetPath = alertAsset
                setAssetImage(ivUpcomingAlertIcon, alertAsset, isSpeedSign = false)
            }
        } else if (isAlertPanelForceExpanded) {
            alertExpandPanel?.visibility = View.VISIBLE
            tvUpcomingAlertDistance?.text = "${speedLimit} km/h"
            tvUpcomingAlertName?.text = zoneName
            if (currentAlertAssetPath != signAsset) {
                currentAlertAssetPath = signAsset
                setAssetImage(ivUpcomingAlertIcon, signAsset, isSpeedSign = false)
            }
        } else {
            alertExpandPanel?.visibility = View.GONE
        }
    }

    private fun setAssetImage(imageView: ImageView?, assetPath: String, isSpeedSign: Boolean) {
        if (imageView == null) return
        try {
            var bmp = bitmapCache[assetPath]
            if (bmp == null) {
                assets.open(assetPath).use { stream ->
                    bmp = BitmapFactory.decodeStream(stream)
                }
                if (bmp != null) {
                    bitmapCache[assetPath] = bmp!!
                }
            }
            if (bmp != null) {
                imageView.setImageBitmap(bmp)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không tìm thấy ảnh asset $assetPath (isSpeedSign=$isSpeedSign)")
        }
    }

    private fun loadSample30KmRoute() {
        try {
            val jsonText = assets.open("sample_route_30km.json").bufferedReader().use { it.readText() }
            val root = JSONObject(jsonText)
            val coords = root.optJSONArray("points") ?: root.getJSONArray("coordinates")
            simRoutePoints.clear()
            for (i in 0 until coords.length()) {
                val pair = coords.getJSONArray(i)
                val lat = pair.getDouble(0)
                val lon = pair.getDouble(1)
                simRoutePoints.add(RouteWaypoint(lat, lon))
            }
            Log.i(TELEMETRY_TAG, "ROUTE_LOADED: points=${simRoutePoints.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi đọc sample_route_30km.json: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bong bóng Tốc độ & Cảnh báo Vietmap Offline",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Duy trì bong bóng hiển thị tốc độ giới hạn trên màn hình ô tô"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_STOP_BUBBLE
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_bubble)
            .setContentTitle("Vietmap Car Bubble (Offline 100%)")
            .setContentText(statusText)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .addAction(0, "Tắt Bong Bóng", pendingStop)
            .build()
    }

    private fun updateForegroundNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildForegroundNotification(statusText))
    }

    override fun onDestroy() {
        isServiceRunning = false
        mainHandler.removeCallbacks(simulationRunnable)
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {
        }
        try {
            if (bubbleView != null) {
                windowManager.removeView(bubbleView)
                bubbleView = null
            }
        } catch (_: Exception) {
        }
        audioManager.shutdown()
        spatialEngine.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        return 2.0 * r * asin(sqrt(a))
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
