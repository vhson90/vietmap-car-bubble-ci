package vn.vietmap.carbubble.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import vn.vietmap.carbubble.MainActivity
import vn.vietmap.carbubble.R
import vn.vietmap.carbubble.audio.AudioAlertManager
import vn.vietmap.carbubble.engine.EdogPoint
import vn.vietmap.carbubble.engine.OfflineSpatialEngine
import vn.vietmap.carbubble.engine.RawGpsFix
import vn.vietmap.carbubble.engine.VietmapSpeedCalculator
import kotlin.math.*

/**
 * Dịch vụ chạy ngầm (Foreground Service) hiển thị Bong Bóng Tốc Độ Siêu Gọn (v2.1 Mini HUD)
 * đè lên trên mọi ứng dụng (Google Maps, YouTube, Navitel, Apple CarPlay...) trên Android Box ô tô.
 *
 * Cải tiến v2.1:
 * - Tích hợp bộ tính vận tốc chuẩn Vietmap Live (`VietmapSpeedCalculator`):
 *   + Thu thập đồng thời FusedProvider + GPSProvider với `minDistance = 0f` (cập nhật ngay cả khi xe dừng đèn đỏ).
 *   + Bộ lọc `isBetterLocation` loại bỏ nhiễu tín hiệu (`accuracy > 48m`).
 *   + Kết hợp vận tốc Doppler vệ tinh & vận tốc Geodesic (`elapsedRealtimeNanos`) cho Android Box thiếu trường `speed`.
 *   + Bộ lọc Kalman 1 chiều + Chặn vọt gai gia tốc (`<= 8.5 m/s^2`) + Watchdog tự trả tốc độ về `0 km/h` sau `3.2s` đứng yên.
 * - Loại bỏ nhãn SIM trên biển báo tốc độ; Chế độ giả lập tách biệt hoàn toàn thành công cụ Debug nội bộ.
 */
class FloatingBubbleService : Service(), LocationListener {

    companion object {
        private const val TAG = "FloatingBubbleService"
        private const val TELEMETRY_TAG = "VietmapTelemetry"
        private const val NOTIFICATION_ID = 2026
        private const val CHANNEL_ID = "vietmap_car_bubble_channel"

        const val PREFS_NAME = "vietmap_car_bubble_prefs"
        const val KEY_IS_SIMULATION = "is_simulation_mode"
        const val KEY_SIM_SPEED_OVERRIDE = "sim_speed_override_kmh"
        const val KEY_GPS_OFFSET_KMH = "gps_offset_kmh"
        const val KEY_OVERSPEED_THRESHOLD = "overspeed_threshold_kmh"
        const val KEY_VOICE_ENABLED = "voice_enabled"
        const val KEY_AUTO_START_BOOT = "auto_start_boot"
        const val KEY_AUTO_START_ON_OPEN = "auto_start_on_open"
        const val KEY_AUTO_MINIMIZE_ON_START = "auto_minimize_on_start"
        const val KEY_BUBBLE_X = "bubble_pos_x"
        const val KEY_BUBBLE_Y = "bubble_pos_y"
        const val KEY_BUBBLE_SCALE = "bubble_scale_percent"   // 70..130 (Mặc định 100%)
        const val KEY_IDLE_ALPHA = "bubble_idle_alpha_pct"    // 50..100 (Mặc định 78%)
        const val KEY_ULTRA_MINI = "bubble_ultra_mini_mode"   // true = Chỉ hiện 2 vòng tròn cố định

        const val ACTION_START_BUBBLE = "vn.vietmap.carbubble.ACTION_START_BUBBLE"
        const val ACTION_STOP_BUBBLE = "vn.vietmap.carbubble.ACTION_STOP_BUBBLE"
        const val ACTION_RELOAD_SETTINGS = "vn.vietmap.carbubble.ACTION_RELOAD_SETTINGS"
        const val ACTION_RESET_SIMULATION = "vn.vietmap.carbubble.ACTION_RESET_SIMULATION"
        const val ACTION_DEBUG_INJECT_GPS = "vn.vietmap.carbubble.ACTION_DEBUG_INJECT_GPS"

        const val ACTION_STATE_BROADCAST = "vn.vietmap.carbubble.ACTION_STATE_BROADCAST"

        @Volatile
        var isServiceRunning: Boolean = false
            private set
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var windowManager: WindowManager
    private lateinit var spatialEngine: OfflineSpatialEngine
    private lateinit var audioManager: AudioAlertManager
    private val speedCalculator = VietmapSpeedCalculator()
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

    private val bitmapCache = HashMap<String, Bitmap>()
    private var currentLimitAssetPath: String = ""
    private var currentAlertAssetPath: String = ""

    // Cấu hình người dùng
    private var isSimulationMode: Boolean = false
    private var simSpeedOverrideKmh: Int = 0
    private var gpsOffsetKmh: Int = 0
    private var overspeedThresholdKmh: Int = 3
    private var bubbleScalePercent: Int = 100
    private var idleAlphaPercent: Int = 78
    private var isUltraMiniMode: Boolean = false

    // Trạng thái di chuyển thực tế / debug
    private var currentLat: Double = 10.772109
    private var currentLon: Double = 106.698272
    private var currentBearing: Float = 65.0f
    private var currentSpeedKmh: Int = 0
    private var lastSpeedSource: String = "IDLE_0"

    private data class RouteWaypoint(val lat: Double, val lon: Double)
    private val simRoutePoints = ArrayList<RouteWaypoint>()
    private var simRouteIndex: Int = 0
    private var simSubStep: Double = 0.0
    private val mainHandler = Handler(Looper.getMainLooper())

    private val simulationRunnable = object : Runnable {
        override fun run() {
            if (!isServiceRunning || !isSimulationMode) return
            stepSimulation()
            mainHandler.postDelayed(this, 350L)
        }
    }

    /**
     * Bộ canh thời gian Watchdog (chu kỳ 1.0 giây) cho chế độ GPS thực tế:
     * Tự động trả vận tốc về 0 km/h nếu xe dừng đèn đỏ hoặc đi vào hầm mất sóng quá 3.2 giây.
     */
    private val gpsWatchdogRunnable = object : Runnable {
        override fun run() {
            if (!isServiceRunning || isSimulationMode) return
            val newSpeed = speedCalculator.checkWatchdogTimeout(
                nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                gpsOffsetKmh = gpsOffsetKmh
            )
            if (newSpeed == 0 && currentSpeedKmh != 0) {
                currentSpeedKmh = 0
                lastSpeedSource = "WATCHDOG_TIMEOUT_0"
                Log.i(TELEMETRY_TAG, "GPS_WATCHDOG_ZERO: Không có nhịp GPS mới sau 3.2s -> Trả vận tốc về 0 km/h")
                processSpatialQueryAndUpdateUI()
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        spatialEngine = OfflineSpatialEngine(this)
        val dbOk = spatialEngine.initializeDatabase()
        Log.i(TELEMETRY_TAG, "INIT_SERVICE_V2_1: db_initialized=$dbOk")

        audioManager = AudioAlertManager(this)

        loadSettings()
        loadSample30KmRoute()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Mini HUD v2.1 đang hoạt động"))

        createFloatingBubbleWindow()
        applyLocationMode()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val editor = prefs.edit()
            var hasOverrides = false
            if (intent.hasExtra("is_sim")) {
                editor.putBoolean(KEY_IS_SIMULATION, intent.getBooleanExtra("is_sim", false))
                hasOverrides = true
            }
            if (intent.hasExtra("sim_speed")) {
                editor.putInt(KEY_SIM_SPEED_OVERRIDE, intent.getIntExtra("sim_speed", 0))
                hasOverrides = true
            }
            if (intent.hasExtra("gps_offset")) {
                editor.putInt(KEY_GPS_OFFSET_KMH, intent.getIntExtra("gps_offset", 0))
                hasOverrides = true
            }
            if (intent.hasExtra("sim_index")) {
                val idx = intent.getIntExtra("sim_index", 0)
                if (simRoutePoints.isNotEmpty()) {
                    simRouteIndex = idx.coerceIn(0, simRoutePoints.size - 1)
                    simSubStep = 0.0
                }
            }
            if (intent.hasExtra("bubble_scale")) {
                editor.putInt(KEY_BUBBLE_SCALE, intent.getIntExtra("bubble_scale", 100))
                hasOverrides = true
            }
            if (intent.hasExtra("idle_alpha")) {
                editor.putInt(KEY_IDLE_ALPHA, intent.getIntExtra("idle_alpha", 78))
                hasOverrides = true
            }
            if (intent.hasExtra("ultra_mini")) {
                editor.putBoolean(KEY_ULTRA_MINI, intent.getBooleanExtra("ultra_mini", false))
                hasOverrides = true
            }
            if (intent.hasExtra("bubble_x") && intent.hasExtra("bubble_y")) {
                val bx = intent.getIntExtra("bubble_x", 16)
                val by = intent.getIntExtra("bubble_y", 110)
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
            ACTION_DEBUG_INJECT_GPS -> {
                if (intent != null) {
                    handleDebugInjectedGpsFix(intent)
                }
            }
            ACTION_RELOAD_SETTINGS, ACTION_START_BUBBLE -> {
                val oldSimMode = isSimulationMode
                loadSettings()
                applyBubbleScaleAndAlpha(isActiveAlertOrOverspeed = false)
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
        gpsOffsetKmh = prefs.getInt(KEY_GPS_OFFSET_KMH, 0).coerceIn(-5, 5)
        overspeedThresholdKmh = prefs.getInt(KEY_OVERSPEED_THRESHOLD, 3)
        bubbleScalePercent = prefs.getInt(KEY_BUBBLE_SCALE, 100).coerceIn(70, 130)
        idleAlphaPercent = prefs.getInt(KEY_IDLE_ALPHA, 78).coerceIn(50, 100)
        isUltraMiniMode = prefs.getBoolean(KEY_ULTRA_MINI, false)
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

        setAssetImage(ivSpeedLimitSign, "speed/60.png", isSpeedSign = true)
        applyBubbleScaleAndAlpha(isActiveAlertOrOverspeed = false)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val savedX = prefs.getInt(KEY_BUBBLE_X, 16)
        val savedY = prefs.getInt(KEY_BUBBLE_Y, 105)

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
            Log.i(TELEMETRY_TAG, "COMPACT_OVERLAY_ADDED: x=$savedX, y=$savedY, scale=${bubbleScalePercent}%")
        } catch (e: Exception) {
            Log.e(TAG, "Không thể thêm Bubble View vào WindowManager: ${e.message}", e)
        }
    }

    private fun applyBubbleScaleAndAlpha(isActiveAlertOrOverspeed: Boolean) {
        val scale = bubbleScalePercent / 100f
        rootBubbleContainer?.pivotX = 0f
        rootBubbleContainer?.pivotY = 0f
        rootBubbleContainer?.scaleX = scale
        rootBubbleContainer?.scaleY = scale

        val targetAlpha = if (isActiveAlertOrOverspeed) {
            1.0f
        } else {
            (idleAlphaPercent / 100f).coerceIn(0.5f, 1.0f)
        }
        rootBubbleContainer?.alpha = targetAlpha
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
                    rootBubbleContainer?.alpha = 1.0f // Sáng rõ 100% khi người dùng chạm vào
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
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
                        // Tự động hút sát vào mép Trái hoặc mép Phải màn hình (Auto Edge-Snap) để tránh che bản đồ ở giữa
                        val screenWidth = resources.displayMetrics.widthPixels
                        val bubbleWidth = bubbleView?.width ?: 200
                        val centerX = layoutParams.x + bubbleWidth / 2
                        layoutParams.x = if (centerX < screenWidth / 2) {
                            14 // Sát mép trái
                        } else {
                            max(14, screenWidth - bubbleWidth - 14) // Sát mép phải
                        }
                        try {
                            windowManager.updateViewLayout(bubbleView, layoutParams)
                        } catch (_: Exception) {
                        }
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
                            // Chạm nhẹ 1 lần -> Bật/tắt Chế độ Tối Giản Tuyệt Đối (Chỉ 2 vòng tròn vs Tự bung khi có Camera)
                            isUltraMiniMode = !isUltraMiniMode
                            prefs.edit().putBoolean(KEY_ULTRA_MINI, isUltraMiniMode).apply()
                            val msg = if (isUltraMiniMode) {
                                "Chế độ Tối Giản: Chỉ hiện 2 vòng tròn"
                            } else {
                                "Chế độ Thông Minh: Tự bung nhãn khi có Camera < 350m"
                            }
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
        mainHandler.removeCallbacks(gpsWatchdogRunnable)
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {
        }

        if (isSimulationMode) {
            lastSpeedSource = "DEBUG_SIM_30KM"
            mainHandler.post(simulationRunnable)
            updateForegroundNotification("Mini HUD • [DEBUG] Giả lập 30km")
            Log.i(TELEMETRY_TAG, "MODE_SWITCH: DEBUG_SIMULATION_30KM_ACTIVE")
        } else {
            speedCalculator.reset()
            currentSpeedKmh = 0
            lastSpeedSource = "REAL_GPS_INIT"
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            try {
                val allProviders = locationManager?.allProviders ?: emptyList()

                // 1. Đăng ký Fused Location Provider (kết hợp GPS + Gia tốc kế quán tính IMU giống Vietmap Live)
                if (allProviders.contains("fused")) {
                    try {
                        locationManager?.requestLocationUpdates(
                            "fused",
                            500L,
                            0.0f, // minDistance = 0f để nhận bản tin 0 km/h ngay cả khi xe dừng đèn đỏ
                            this
                        )
                        Log.i(TELEMETRY_TAG, "GPS_PROVIDER_REGISTERED: fused (minTime=500ms, minDist=0m)")
                    } catch (_: Exception) {
                    }
                }

                // 2. Đăng ký vệ tinh phần cứng GPS_PROVIDER (minDistance = 0f)
                if (allProviders.contains(LocationManager.GPS_PROVIDER) &&
                    locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
                ) {
                    locationManager?.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        500L,
                        0.0f,
                        this
                    )
                    Log.i(TELEMETRY_TAG, "GPS_PROVIDER_REGISTERED: gps (minTime=500ms, minDist=0m)")
                }

                // 3. Đăng ký Network Provider dự phòng khi xe mới khởi động trong hầm/mái che
                if (allProviders.contains(LocationManager.NETWORK_PROVIDER) &&
                    locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
                ) {
                    locationManager?.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1000L,
                        0.0f,
                        this
                    )
                }

                // Khởi chạy Watchdog kiểm tra mất tín hiệu / dừng xe mỗi 1 giây
                mainHandler.postDelayed(gpsWatchdogRunnable, 1000L)
                processSpatialQueryAndUpdateUI()

                updateForegroundNotification("Mini HUD • Đang thu tín hiệu GPS thực tế")
                Log.i(TELEMETRY_TAG, "MODE_SWITCH: REAL_GPS_ACTIVE (VietmapSpeedCalculator v2.1)")
            } catch (e: SecurityException) {
                Log.e(TAG, "Thiếu quyền truy cập GPS: ${e.message}")
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        if (isSimulationMode) return

        val elapsedNanos = if (location.elapsedRealtimeNanos > 0L) {
            location.elapsedRealtimeNanos
        } else {
            SystemClock.elapsedRealtimeNanos()
        }
        val hasSpeedAcc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()
        val speedAccMs = if (hasSpeedAcc && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            location.speedAccuracyMetersPerSecond
        } else {
            0f
        }

        val rawFix = RawGpsFix(
            lat = location.latitude,
            lon = location.longitude,
            timeMs = if (location.time > 0L) location.time else System.currentTimeMillis(),
            elapsedRealtimeNanos = elapsedNanos,
            provider = location.provider ?: LocationManager.GPS_PROVIDER,
            hasAccuracy = location.hasAccuracy(),
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else 12f,
            hasSpeed = location.hasSpeed(),
            speedMetersPerSecond = if (location.hasSpeed()) location.speed else 0f,
            hasSpeedAccuracy = hasSpeedAcc,
            speedAccuracyMetersPerSecond = speedAccMs,
            hasBearing = location.hasBearing(),
            bearingDegrees = if (location.hasBearing()) location.bearing else 0f
        )

        applyRawGpsFixWithVietmapEngine(rawFix)
    }

    /**
     * Cho phép bơm bản tin GPS thực nghiệm qua ADB (`ACTION_DEBUG_INJECT_GPS`) khi kiểm thử tự động
     * trên trình giả lập để xác minh trực tiếp thuật toán `VietmapSpeedCalculator` ở chế độ Real GPS.
     */
    private fun handleDebugInjectedGpsFix(intent: Intent) {
        if (isSimulationMode) {
            isSimulationMode = false
            prefs.edit().putBoolean(KEY_IS_SIMULATION, false).apply()
            applyLocationMode()
        }
        val lat = intent.getDoubleExtra("lat", currentLat)
        val lon = intent.getDoubleExtra("lon", currentLon)
        val hasSpeed = intent.getBooleanExtra("has_speed", true)
        val speedMs = intent.getFloatExtra("speed_ms", 0f)
        val accM = intent.getFloatExtra("acc_m", 6.0f)
        val hasBearing = intent.getBooleanExtra("has_bearing", false)
        val bearingDeg = intent.getFloatExtra("bearing_deg", currentBearing)
        val provider = intent.getStringExtra("provider") ?: LocationManager.GPS_PROVIDER

        val rawFix = RawGpsFix(
            lat = lat,
            lon = lon,
            timeMs = System.currentTimeMillis(),
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            provider = provider,
            hasAccuracy = true,
            accuracyMeters = accM,
            hasSpeed = hasSpeed,
            speedMetersPerSecond = speedMs,
            hasSpeedAccuracy = hasSpeed,
            speedAccuracyMetersPerSecond = 0.4f,
            hasBearing = hasBearing,
            bearingDegrees = bearingDeg
        )
        applyRawGpsFixWithVietmapEngine(rawFix)
    }

    private fun applyRawGpsFixWithVietmapEngine(rawFix: RawGpsFix) {
        val calcResult = speedCalculator.processGpsFix(rawFix, gpsOffsetKmh)
        if (!calcResult.isAccepted) {
            Log.w(
                TELEMETRY_TAG,
                "GPS_FIX_REJECTED: provider=${rawFix.provider} acc=${rawFix.accuracyMeters}m reason=${calcResult.sourceType}"
            )
            return
        }

        currentLat = rawFix.lat
        currentLon = rawFix.lon
        currentSpeedKmh = calcResult.speedKmh
        currentBearing = calcResult.bearingDeg
        lastSpeedSource = calcResult.sourceType

        processSpatialQueryAndUpdateUI()
    }

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
                "STATE: wp=%d | gps=(%.5f,%.5f) | brg=%.0f | speed=%d km/h (%s) | limit=%d km/h | overspeed=%b | nearby=%d | alert=%s",
                simRouteIndex,
                currentLat,
                currentLon,
                currentBearing,
                currentSpeedKmh,
                lastSpeedSource,
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

        updateBubbleWidgets(result.currentSpeedLimit, result.upcomingAlert)

        val bIntent = Intent(ACTION_STATE_BROADCAST).apply {
            setPackage(packageName)
            putExtra("speed_kmh", currentSpeedKmh)
            putExtra("speed_source", lastSpeedSource)
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

    private fun getCompactAlertTitle(alert: EdogPoint): String {
        return when (alert.type) {
            1 -> "Biển ${alert.speedLimit}"
            2 -> "Khu dân cư"
            3 -> "Hết KDC"
            4 -> if (alert.speedLimit > 0) "Cam ${alert.speedLimit}" else "Cam phạt nguội"
            5 -> "Cấm vượt"
            6 -> "Hết cấm vượt"
            7 -> "Trạm ETC"
            8 -> "Đường hầm"
            9 -> "Cam giám sát"
            10 -> "Đường sắt"
            11 -> "Giao lộ"
            12 -> "Đèn tín hiệu"
            else -> "Chú ý"
        }
    }

    private fun updateBubbleWidgets(
        speedLimit: Int,
        upcomingAlert: EdogPoint?
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

        // Cơ chế Smart Auto-Collapse:
        // Chỉ hiển thị nhãn cảnh báo bên phải khi thực sự có biển báo/camera trong tầm <= 350m và không bật khóa Tối Giản
        val hasCloseAlert = (upcomingAlert != null && upcomingAlert.distanceMeters <= 350.0)
        if (hasCloseAlert && !isUltraMiniMode && upcomingAlert != null) {
            alertExpandPanel?.visibility = View.VISIBLE
            val distM = upcomingAlert.distanceMeters.roundToInt()
            tvUpcomingAlertDistance?.text = "${distM}m"
            tvUpcomingAlertName?.text = getCompactAlertTitle(upcomingAlert)

            val alertAsset = upcomingAlert.getAlertAssetPath()
            if (alertAsset != currentAlertAssetPath) {
                currentAlertAssetPath = alertAsset
                setAssetImage(ivUpcomingAlertIcon, alertAsset, isSpeedSign = (upcomingAlert.type == 1))
            }
        } else {
            // Đường thông thoáng -> Thu gọn hoàn toàn chỉ còn 2 vòng tròn (92x48dp)!
            alertExpandPanel?.visibility = View.GONE
        }

        // Làm mờ nhẹ khi chạy bình thường (Idle Alpha) và sáng rõ 100% khi có cảnh báo / vượt tốc độ
        applyBubbleScaleAndAlpha(isActiveAlertOrOverspeed = (isOverspeed || hasCloseAlert))
    }

    private fun setAssetImage(imageView: ImageView?, assetPath: String, isSpeedSign: Boolean) {
        if (imageView == null) return
        try {
            val cacheKey = if (isSpeedSign) "circle_$assetPath" else assetPath
            var bmp = bitmapCache[cacheKey]
            if (bmp == null) {
                assets.open(assetPath).use { stream ->
                    val raw = BitmapFactory.decodeStream(stream)
                    bmp = if (raw != null && isSpeedSign) {
                        cropCircularSignBitmap(raw)
                    } else {
                        raw
                    }
                }
                if (bmp != null) {
                    bitmapCache[cacheKey] = bmp!!
                }
            }
            if (bmp != null) {
                imageView.setImageBitmap(bmp)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không tìm thấy ảnh asset $assetPath")
        }
    }

    /**
     * Cắt bo tròn hoàn hảo ảnh biển báo tốc độ để loại bỏ 4 góc vuông màu đen thừa trong file PNG gốc
     */
    private fun cropCircularSignBitmap(src: Bitmap): Bitmap {
        val size = min(src.width, src.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius * 0.94f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val left = (src.width - size) / 2f
        val top = (src.height - size) / 2f
        canvas.drawBitmap(src, -left, -top, paint)
        return output
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
            .setContentTitle("Vietmap Car Bubble v2.1 (Mini HUD)")
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
        mainHandler.removeCallbacks(gpsWatchdogRunnable)
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
