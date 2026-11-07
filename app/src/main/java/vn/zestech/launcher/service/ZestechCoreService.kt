package vn.zestech.launcher.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import vn.zestech.launcher.MainActivity
import vn.zestech.launcher.R
import vn.zestech.launcher.audio.AudioAlertManager
import vn.zestech.launcher.engine.*
import kotlin.math.*

/**
 * Dịch vụ Lõi Zestech Pro Launcher (ZestechCoreService):
 * 1. Tích hợp Engine Cảnh báo tốc độ Offline 69,965 điểm (OfflineSpatialEngine) + Lọc vận tốc GpsSpeedCalculator.
 * 2. Tích hợp Engine Bản đồ dẫn đường Turn-by-Turn (NavigationEngine) đồng bộ giữa Màn hình chính Launcher và Bong bóng nổi.
 * 3. Quản lý Bong Bóng Nổi Thông Minh (Auto-Overlay State Machine):
 *    - Khi người dùng đang ở màn hình chính Zestech Launcher (isLauncherInForeground = true): Tự động ẩn Bong bóng nổi
 *      (trừ khi người dùng bật chế độ "Luôn hiện Bong bóng trên Launcher").
 *    - Ngay khi có bất kỳ ứng dụng nào khác (NCT Car Player, Zestech Tivi, YouTube, Cài đặt...) đè lên Launcher
 *      (isLauncherInForeground = false): Tự động bung Bong bóng nổi đè lên trên ứng dụng đó kèm thông tin
 *      Tốc độ + Biển báo giới hạn + Camera phía trước + Mũi tên chỉ đường Maps Turn-by-Turn!
 */
class ZestechCoreService : Service(), LocationListener {

    companion object {
        private const val TAG = "ZestechCoreService"
        private const val TELEMETRY_TAG = "ZestechTelemetry"
        private const val NOTIFICATION_ID = 2028
        private const val CHANNEL_ID = "zestech_launcher_channel"

        const val PREFS_NAME = "zestech_launcher_prefs"
        const val KEY_IS_SIMULATION = "is_simulation_mode"
        const val KEY_SIM_SPEED_OVERRIDE = "sim_speed_override_kmh"
        const val KEY_GPS_OFFSET_KMH = "gps_offset_kmh"
        const val KEY_OVERSPEED_THRESHOLD = "overspeed_threshold_kmh"
        const val KEY_VOICE_ENABLED = "voice_enabled"
        const val KEY_SOUND_MODE = "sound_mode_type"
        const val KEY_BUBBLE_X = "bubble_pos_x"
        const val KEY_BUBBLE_Y = "bubble_pos_y"
        const val KEY_BUBBLE_SCALE = "bubble_scale_percent"
        const val KEY_ULTRA_MINI = "bubble_ultra_mini_mode"
        const val KEY_FORCE_BUBBLE_ON_LAUNCHER = "force_bubble_on_launcher"
        const val KEY_ACTIVE_ROUTE_ID = "active_route_preset_id"

        const val ACTION_START_SERVICE = "vn.zestech.launcher.ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "vn.zestech.launcher.ACTION_STOP_SERVICE"
        const val ACTION_RELOAD_SETTINGS = "vn.zestech.launcher.ACTION_RELOAD_SETTINGS"
        const val ACTION_RESET_SIMULATION = "vn.zestech.launcher.ACTION_RESET_SIMULATION"
        const val ACTION_LAUNCHER_VISIBILITY = "vn.zestech.launcher.ACTION_LAUNCHER_VISIBILITY"
        const val ACTION_SELECT_ROUTE = "vn.zestech.launcher.ACTION_SELECT_ROUTE"
        const val ACTION_CUSTOM_DESTINATION = "vn.zestech.launcher.ACTION_CUSTOM_DESTINATION"
        const val ACTION_DEBUG_INJECT_GPS = "vn.zestech.launcher.ACTION_DEBUG_INJECT_GPS"

        const val ACTION_STATE_BROADCAST = "vn.zestech.launcher.ACTION_STATE_BROADCAST"

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        @Volatile
        var instance: ZestechCoreService? = null
            private set

        @Volatile
        var isLauncherVisible: Boolean = true
            private set
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var windowManager: WindowManager
    lateinit var spatialEngine: OfflineSpatialEngine
        private set
    lateinit var navigationEngine: NavigationEngine
        private set
    lateinit var audioManager: AudioAlertManager
        private set

    private val speedCalculator = GpsSpeedCalculator()
    private var locationManager: LocationManager? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var bubbleView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    // UI Components trên Bong bóng nổi
    private var rootBubbleContainer: LinearLayout? = null
    private var speedCircleContainer: LinearLayout? = null
    private var tvCurrentSpeed: TextView? = null
    private var tvSpeedUnit: TextView? = null
    private var signCircleContainer: FrameLayout? = null
    private var ivSpeedLimitSign: ImageView? = null
    private var alertExpandPanel: LinearLayout? = null
    private var ivUpcomingAlertIcon: ImageView? = null
    private var tvBubbleManeuverBadge: TextView? = null
    private var alertTextContainer: LinearLayout? = null
    private var tvUpcomingAlertDistance: TextView? = null
    private var tvUpcomingAlertName: TextView? = null
    private var btnBubbleBackHome: TextView? = null

    private val bitmapCache = HashMap<String, Bitmap>()
    private var currentLimitAssetPath: String = ""
    private var currentAlertAssetPath: String = ""

    // Cấu hình
    var isSimulationMode: Boolean = true
        private set
    private var simSpeedOverrideKmh: Int = 0
    private var gpsOffsetKmh: Int = 0
    private var overspeedThresholdKmh: Int = 3
    private var bubbleScalePercent: Int = 100
    private var isUltraMiniMode: Boolean = false
    var forceBubbleOnLauncher: Boolean = false
        private set

    // Trạng thái di chuyển thực tế / mô phỏng
    var currentLat: Double = 10.772109
        private set
    var currentLon: Double = 106.698272
        private set
    var currentBearing: Float = 65.0f
        private set
    var currentSpeedKmh: Int = 0
        private set
    var lastSpatialResult: SpatialStateResult? = null
        private set
    var lastNavTelemetry: NavigationTelemetry? = null
        private set

    private var lastSpeedSource: String = "SIM_ROUTE"
    private var lastLocationCallbackRealtimeMs: Long = 0L
    private var isDebugGpsInjected: Boolean = false

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
                processSpatialQueryAndUpdateUI()
            }
            val nowRealtime = SystemClock.elapsedRealtime()
            if (!isDebugGpsInjected && nowRealtime - lastLocationCallbackRealtimeMs >= 8_500L) {
                lastLocationCallbackRealtimeMs = nowRealtime
                rebindAndPollLocationListeners()
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        instance = this
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        acquireHardwareLocks()

        spatialEngine = OfflineSpatialEngine(this)
        val dbOk = spatialEngine.initializeDatabase()
        navigationEngine = NavigationEngine(this)
        audioManager = AudioAlertManager(this)

        Log.i(TELEMETRY_TAG, "ZESTECH_CORE_INIT: dbOk=$dbOk routes=${navigationEngine.presets.size}")

        loadSettings()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Zestech Pro Launcher đang hoạt động"))

        createFloatingBubbleWindow()
        updateBubbleVisibilityForForegroundState()
        applyLocationMode()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireHardwareLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "ZestechLauncher:LocationWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && wifiLock == null) {
                val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(lockType, "ZestechLauncher:WifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không thể cấp phát WakeLock: ${e.message}")
        }
    }

    private fun releaseHardwareLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        } finally {
            wakeLock = null
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        } finally {
            wifiLock = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bubbleView == null) {
            createFloatingBubbleWindow()
        }
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
            if (intent.hasExtra("force_bubble_on_launcher")) {
                editor.putBoolean(
                    KEY_FORCE_BUBBLE_ON_LAUNCHER,
                    intent.getBooleanExtra("force_bubble_on_launcher", false)
                )
                hasOverrides = true
            }
            if (intent.hasExtra("sound_mode")) {
                editor.putInt(KEY_SOUND_MODE, intent.getIntExtra("sound_mode", AudioAlertManager.SOUND_MODE_FULL))
                hasOverrides = true
            }
            if (hasOverrides) {
                editor.apply()
            }
        }

        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LAUNCHER_VISIBILITY -> {
                val visible = intent.getBooleanExtra("is_launcher_visible", true)
                isLauncherVisible = visible
                Log.i(
                    TELEMETRY_TAG,
                    "LAUNCHER_VISIBILITY_CHANGED: isLauncherVisible=$visible forceBubble=$forceBubbleOnLauncher -> bubbleShown=${!visible || forceBubbleOnLauncher}"
                )
                updateBubbleVisibilityForForegroundState()
            }
            ACTION_SELECT_ROUTE -> {
                val routeId = intent.getIntExtra("route_id", 0)
                prefs.edit().putInt(KEY_ACTIVE_ROUTE_ID, routeId).apply()
                val route = navigationEngine.selectPresetRoute(routeId)
                simRouteIndex = 0
                simSubStep = 0.0
                if (route.points.isNotEmpty()) {
                    currentLat = route.points.first().lat
                    currentLon = route.points.first().lon
                }
                spatialEngine.resetLatchedState(50)
                audioManager.resetHistory()
                processSpatialQueryAndUpdateUI()
            }
            ACTION_CUSTOM_DESTINATION -> {
                val destName = intent.getStringExtra("dest_name") ?: "Điểm đến mới"
                val destLat = intent.getDoubleExtra("dest_lat", currentLat + 0.035)
                val destLon = intent.getDoubleExtra("dest_lon", currentLon + 0.045)
                navigationEngine.setCustomDestination(destName, currentLat, currentLon, destLat, destLon)
                simRouteIndex = 0
                simSubStep = 0.0
                processSpatialQueryAndUpdateUI()
            }
            ACTION_DEBUG_INJECT_GPS -> {
                handleDebugInjectedGpsFix(intent)
            }
            ACTION_RESET_SIMULATION -> {
                simRouteIndex = 0
                simSubStep = 0.0
                spatialEngine.resetLatchedState(50)
                audioManager.resetHistory()
                processSpatialQueryAndUpdateUI()
            }
            ACTION_RELOAD_SETTINGS, ACTION_START_SERVICE -> {
                val oldSim = isSimulationMode
                loadSettings()
                updateBubbleVisibilityForForegroundState()
                if (oldSim != isSimulationMode) {
                    applyLocationMode()
                } else {
                    processSpatialQueryAndUpdateUI()
                }
            }
        }
        return START_STICKY
    }

    private fun loadSettings() {
        isSimulationMode = prefs.getBoolean(KEY_IS_SIMULATION, true)
        simSpeedOverrideKmh = prefs.getInt(KEY_SIM_SPEED_OVERRIDE, 0)
        gpsOffsetKmh = prefs.getInt(KEY_GPS_OFFSET_KMH, 0).coerceIn(-5, 5)
        overspeedThresholdKmh = prefs.getInt(KEY_OVERSPEED_THRESHOLD, 3)
        bubbleScalePercent = prefs.getInt(KEY_BUBBLE_SCALE, 100).coerceIn(75, 130)
        isUltraMiniMode = prefs.getBoolean(KEY_ULTRA_MINI, false)
        forceBubbleOnLauncher = prefs.getBoolean(KEY_FORCE_BUBBLE_ON_LAUNCHER, false)
        audioManager.isVoiceEnabled = prefs.getBoolean(KEY_VOICE_ENABLED, true)
        audioManager.soundMode = prefs.getInt(KEY_SOUND_MODE, AudioAlertManager.SOUND_MODE_FULL)
    }

    /**
     * Tự động Bật / Ẩn Bong bóng nổi dựa trên việc Zestech Launcher đang ở Foreground hay bị App khác đè lên!
     */
    fun updateBubbleVisibilityForForegroundState() {
        if (bubbleView == null) {
            createFloatingBubbleWindow()
        }
        val shouldShowBubble = (!isLauncherVisible) || forceBubbleOnLauncher
        bubbleView?.visibility = if (shouldShowBubble) View.VISIBLE else View.GONE
        Log.i(
            TELEMETRY_TAG,
            "BUBBLE_VISIBILITY_STATE: shouldShow=$shouldShowBubble (launcherVisible=$isLauncherVisible, forceOnLauncher=$forceBubbleOnLauncher)"
        )
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun createFloatingBubbleWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Chưa cấp quyền SYSTEM_ALERT_WINDOW cho Bong bóng nổi")
            return
        }
        if (bubbleView != null) return

        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.layout_floating_bubble, null)

        rootBubbleContainer = bubbleView?.findViewById(R.id.rootBubbleContainer)
        speedCircleContainer = bubbleView?.findViewById(R.id.speedCircleContainer)
        tvCurrentSpeed = bubbleView?.findViewById(R.id.tvCurrentSpeed)
        tvSpeedUnit = bubbleView?.findViewById(R.id.tvSpeedUnit)
        signCircleContainer = bubbleView?.findViewById(R.id.signCircleContainer)
        ivSpeedLimitSign = bubbleView?.findViewById(R.id.ivSpeedLimitSign)
        alertExpandPanel = bubbleView?.findViewById(R.id.alertExpandPanel)
        ivUpcomingAlertIcon = bubbleView?.findViewById(R.id.ivUpcomingAlertIcon)
        tvBubbleManeuverBadge = bubbleView?.findViewById(R.id.tvBubbleManeuverBadge)
        alertTextContainer = bubbleView?.findViewById(R.id.alertTextContainer)
        tvUpcomingAlertDistance = bubbleView?.findViewById(R.id.tvUpcomingAlertDistance)
        tvUpcomingAlertName = bubbleView?.findViewById(R.id.tvUpcomingAlertName)
        btnBubbleBackHome = bubbleView?.findViewById(R.id.btnBubbleBackHome)

        setAssetImage(ivSpeedLimitSign, "speed/60.png", isSpeedSign = true)

        btnBubbleBackHome?.setOnClickListener {
            openLauncherHome()
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val savedX = prefs.getInt(KEY_BUBBLE_X, 20)
        val savedY = prefs.getInt(KEY_BUBBLE_Y, 64)

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
            Log.i(TELEMETRY_TAG, "OVERLAY_BUBBLE_CREATED: x=$savedX, y=$savedY")
        } catch (e: Exception) {
            Log.e(TAG, "Không thể thêm Bubble View: ${e.message}")
        }
    }

    private fun openLauncherHome() {
        try {
            val openIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(openIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Không thể mở lại Zestech Launcher: ${e.message}")
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
                    rootBubbleContainer?.alpha = 1.0f
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
                        val screenWidth = resources.displayMetrics.widthPixels
                        val bubbleWidth = bubbleView?.width ?: 220
                        val centerX = layoutParams.x + bubbleWidth / 2
                        val targetX = if (centerX < screenWidth / 2) {
                            16
                        } else {
                            max(16, screenWidth - bubbleWidth - 16)
                        }
                        animateEdgeSnap(layoutParams.x, targetX)
                        prefs.edit()
                            .putInt(KEY_BUBBLE_X, targetX)
                            .putInt(KEY_BUBBLE_Y, layoutParams.y)
                            .apply()
                    } else {
                        if (elapsed >= 500L) {
                            openLauncherHome()
                        } else {
                            isUltraMiniMode = !isUltraMiniMode
                            prefs.edit().putBoolean(KEY_ULTRA_MINI, isUltraMiniMode).apply()
                            processSpatialQueryAndUpdateUI()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun animateEdgeSnap(fromX: Int, toX: Int) {
        if (fromX == toX) return
        try {
            ValueAnimator.ofInt(fromX, toX).apply {
                duration = 180L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    if (bubbleView != null && bubbleView?.isAttachedToWindow == true) {
                        layoutParams.x = anim.animatedValue as Int
                        try {
                            windowManager.updateViewLayout(bubbleView, layoutParams)
                        } catch (_: Exception) {
                        }
                    }
                }
                start()
            }
        } catch (_: Exception) {
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
            lastSpeedSource = "DEMO_ROUTE_GPS"
            spatialEngine.resetLatchedState(50)
            audioManager.resetHistory()
            mainHandler.post(simulationRunnable)
            Log.i(TELEMETRY_TAG, "LOCATION_MODE: DEMO_SIMULATION_ACTIVE")
        } else {
            isDebugGpsInjected = false
            speedCalculator.reset()
            spatialEngine.resetLatchedState(50)
            audioManager.resetHistory()
            currentSpeedKmh = 0
            lastSpeedSource = "REAL_GPS"
            lastLocationCallbackRealtimeMs = SystemClock.elapsedRealtime()
            rebindAndPollLocationListeners()
            mainHandler.postDelayed(gpsWatchdogRunnable, 1000L)
            processSpatialQueryAndUpdateUI()
            Log.i(TELEMETRY_TAG, "LOCATION_MODE: REAL_GPS_ACTIVE")
        }
    }

    @SuppressLint("MissingPermission")
    private fun rebindAndPollLocationListeners() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {
        }

        try {
            val allProviders = locationManager?.allProviders ?: emptyList()
            if (allProviders.contains("fused")) {
                try {
                    locationManager?.requestLocationUpdates("fused", 500L, 0.0f, this)
                } catch (_: Exception) {
                }
            }
            if (allProviders.contains(LocationManager.GPS_PROVIDER) &&
                locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
            ) {
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0.0f, this)
            }
            if (allProviders.contains(LocationManager.PASSIVE_PROVIDER)) {
                try {
                    locationManager?.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 400L, 0.0f, this)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onLocationChanged(location: Location) {
        if (isSimulationMode || isDebugGpsInjected) return
        lastLocationCallbackRealtimeMs = SystemClock.elapsedRealtime()

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
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else 10f,
            hasSpeed = location.hasSpeed(),
            speedMetersPerSecond = if (location.hasSpeed()) location.speed else 0f,
            hasSpeedAccuracy = hasSpeedAcc,
            speedAccuracyMetersPerSecond = speedAccMs,
            hasBearing = location.hasBearing(),
            bearingDegrees = if (location.hasBearing()) location.bearing else 0f
        )

        val calcResult = speedCalculator.processGpsFix(rawFix, gpsOffsetKmh)
        if (calcResult.isAccepted) {
            currentLat = rawFix.lat
            currentLon = rawFix.lon
            currentSpeedKmh = calcResult.speedKmh
            if (calcResult.bearingDeg > 0f) {
                currentBearing = calcResult.bearingDeg
            }
            lastSpeedSource = calcResult.sourceType
            processSpatialQueryAndUpdateUI()
        }
    }

    private fun handleDebugInjectedGpsFix(intent: Intent) {
        isDebugGpsInjected = true
        val lat = intent.getDoubleExtra("lat", currentLat)
        val lon = intent.getDoubleExtra("lon", currentLon)
        val speedKmh = intent.getIntExtra("speed_kmh", 65)
        val bearingDeg = intent.getFloatExtra("bearing_deg", currentBearing)

        currentLat = lat
        currentLon = lon
        currentSpeedKmh = speedKmh
        currentBearing = bearingDeg
        lastSpeedSource = "INJECTED_GPS"
        processSpatialQueryAndUpdateUI()
    }

    private fun stepSimulation() {
        val activeRoute = navigationEngine.activeRoute ?: return
        val pts = activeRoute.points
        if (pts.size < 2) return

        simRouteIndex = simRouteIndex.coerceIn(0, pts.size - 2)
        val p1 = pts[simRouteIndex]
        val nextIdx = (simRouteIndex + 1) % pts.size
        val p2 = pts[nextIdx]

        val segDistM = max(1.0, haversineMeters(p1.lat, p1.lon, p2.lat, p2.lon))
        currentBearing = calculateBearing(p1.lat, p1.lon, p2.lat, p2.lon).toFloat()

        val currentLimit = spatialEngine.latchedSpeedLimit
        currentSpeedKmh = if (simSpeedOverrideKmh > 0) {
            simSpeedOverrideKmh
        } else {
            val wave = (sin(simRouteIndex * 0.28) * 6.0).roundToInt()
            max(38, currentLimit + wave)
        }

        val stepMeters = (currentSpeedKmh / 3.6) * 0.35 * 2.3
        simSubStep += stepMeters / segDistM

        while (simSubStep >= 1.0) {
            simSubStep -= 1.0
            simRouteIndex = (simRouteIndex + 1) % (pts.size - 1)
        }

        val a = pts[simRouteIndex]
        val b = pts[(simRouteIndex + 1) % pts.size]
        currentLat = a.lat + (b.lat - a.lat) * simSubStep
        currentLon = a.lon + (b.lon - a.lon) * simSubStep

        processSpatialQueryAndUpdateUI()
    }

    private fun processSpatialQueryAndUpdateUI() {
        val spatialResult = spatialEngine.queryLocation(
            lat = currentLat,
            lon = currentLon,
            bearingDeg = currentBearing,
            currentSpeedKmh = currentSpeedKmh
        )
        lastSpatialResult = spatialResult

        val navResult = navigationEngine.computeTelemetry(
            carLat = currentLat,
            carLon = currentLon,
            currentSpeedKmh = currentSpeedKmh
        )
        lastNavTelemetry = navResult

        val isOverspeed = (spatialResult.currentSpeedLimit > 0) &&
                (currentSpeedKmh > spatialResult.currentSpeedLimit + overspeedThresholdKmh)

        audioManager.checkAndAnnounceUpcomingAlert(
            alert = spatialResult.upcomingAlert,
            maxLookaheadM = spatialResult.adaptiveLookaheadMeters.toDouble()
        )
        audioManager.checkAndAnnounceSpeedLimitChange(spatialResult.currentSpeedLimit)
        audioManager.checkAndAnnounceOverspeed(
            currentSpeedKmh = currentSpeedKmh,
            speedLimitKmh = spatialResult.currentSpeedLimit,
            thresholdKmh = overspeedThresholdKmh
        )

        updateFloatingBubbleUI(spatialResult, navResult, isOverspeed)

        val bIntent = Intent(ACTION_STATE_BROADCAST).apply {
            setPackage(packageName)
            putExtra("speed_kmh", currentSpeedKmh)
            putExtra("speed_source", lastSpeedSource)
            putExtra("speed_limit", spatialResult.currentSpeedLimit)
            putExtra("zone_name", spatialResult.activeZoneName)
            putExtra("road_name", spatialResult.currentRoadName)
            putExtra("admin_area", spatialResult.currentAdminArea)
            putExtra("lat", currentLat)
            putExtra("lon", currentLon)
            putExtra("bearing", currentBearing)
            putExtra("nearby_count", spatialResult.nearbyPointCount)
            putExtra("adaptive_scan_m", spatialResult.adaptiveLookaheadMeters)
            putExtra("is_overspeed", isOverspeed)
            if (spatialResult.upcomingAlert != null) {
                putExtra("has_alert", true)
                putExtra("alert_id", spatialResult.upcomingAlert.id)
                putExtra("alert_name", spatialResult.upcomingAlert.getTypeName())
                putExtra("alert_dist_m", spatialResult.upcomingAlert.distanceMeters.roundToInt())
                putExtra("alert_asset", spatialResult.upcomingAlert.getAlertAssetPath())
            } else {
                putExtra("has_alert", false)
            }
            // Thông tin dẫn đường Turn-by-Turn
            putExtra("nav_active", navResult.isNavigating)
            putExtra("nav_dest", navResult.destinationName)
            putExtra("nav_symbol", navResult.nextManeuverSymbol)
            putExtra("nav_label", navResult.nextManeuverLabel)
            putExtra("nav_street", navResult.nextStreetName)
            putExtra("nav_instruction", navResult.nextInstruction)
            putExtra("nav_turn_dist_m", navResult.distanceToNextTurnM)
            putExtra("nav_remain_km", navResult.remainingDistanceKm)
            putExtra("nav_eta_min", navResult.etaMinutes)
            putExtra("nav_progress_pct", navResult.progressPercent)
        }
        sendBroadcast(bIntent)
    }

    private fun updateFloatingBubbleUI(
        spatial: SpatialStateResult,
        nav: NavigationTelemetry,
        isOverspeed: Boolean
    ) {
        tvCurrentSpeed?.text = currentSpeedKmh.toString()
        if (isOverspeed) {
            speedCircleContainer?.setBackgroundResource(R.drawable.bg_speedometer_overspeed)
            tvCurrentSpeed?.setTextColor(0xFFFFFFFF.toInt())
        } else {
            speedCircleContainer?.setBackgroundResource(R.drawable.bg_speedometer_normal)
            tvCurrentSpeed?.setTextColor(0xFF38BDF8.toInt())
        }

        val validSignSpeed = if (spatial.currentSpeedLimit in listOf(30, 40, 50, 60, 70, 80, 90, 100, 120)) {
            spatial.currentSpeedLimit
        } else {
            60
        }
        val signAsset = "speed/${validSignSpeed}.png"
        if (signAsset != currentLimitAssetPath) {
            currentLimitAssetPath = signAsset
            setAssetImage(ivSpeedLimitSign, signAsset, isSpeedSign = true)
        }

        val upcoming = spatial.upcomingAlert
        val hasCloseAlert = (upcoming != null && upcoming.distanceMeters <= spatial.adaptiveLookaheadMeters)

        if (isUltraMiniMode) {
            alertExpandPanel?.visibility = View.GONE
        } else {
            alertExpandPanel?.visibility = View.VISIBLE
            tvBubbleManeuverBadge?.text = nav.nextManeuverSymbol

            if (hasCloseAlert && upcoming != null) {
                ivUpcomingAlertIcon?.visibility = View.VISIBLE
                val alertAsset = upcoming.getAlertAssetPath()
                if (alertAsset != currentAlertAssetPath) {
                    currentAlertAssetPath = alertAsset
                    setAssetImage(ivUpcomingAlertIcon, alertAsset, isSpeedSign = (upcoming.type == 1))
                }
                val distM = upcoming.distanceMeters.roundToInt()
                tvUpcomingAlertDistance?.text = "⚠ ${distM}m • ${upcoming.getTypeName()}"
                tvUpcomingAlertDistance?.setTextColor(0xFFFBBF24.toInt())
                tvUpcomingAlertName?.text = "${nav.nextManeuverSymbol} ${nav.distanceToNextTurnM}m • ${nav.nextStreetName}"
                tvUpcomingAlertName?.setTextColor(0xFF38BDF8.toInt())
            } else {
                ivUpcomingAlertIcon?.visibility = View.GONE
                tvUpcomingAlertDistance?.text = "${nav.distanceToNextTurnM}m • ${nav.nextStreetName}"
                tvUpcomingAlertDistance?.setTextColor(0xFF38BDF8.toInt())
                tvUpcomingAlertName?.text = String.format(
                    "%s • Còn %.1fkm (%dp)",
                    spatial.currentRoadName,
                    nav.remainingDistanceKm,
                    nav.etaMinutes
                )
                tvUpcomingAlertName?.setTextColor(0xFFCBD5E1.toInt())
            }
        }
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
        } catch (_: Exception) {
        }
    }

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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zestech Pro Launcher & HUD",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Dịch vụ chạy ngầm cảnh báo tốc độ, dẫn đường & bong bóng nổi Zestech"
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_bubble)
            .setContentTitle("Zestech Pro Launcher")
            .setContentText(statusText)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isServiceRunning = false
        instance = null
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
        releaseHardwareLocks()
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
