package vn.zestech.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import vn.zestech.launcher.audio.AudioAlertManager
import vn.zestech.launcher.service.ZestechCoreService
import vn.zestech.launcher.ui.NavigationMapView
import vn.zestech.launcher.ui.ZestechRoadSceneView
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.min

/**
 * Màn hình chính Zestech Launcher chuẩn giao diện gốc của hãng:
 * - Chế độ 1 (Mặc định): Giao diện Zestech 3D Cá Nhân Hóa 72 Màu Phong Thủy & 3 Loại Hình Nền (Cơ Bản / Công Nghệ / Công Viên)
 *   + Mô hình xe SUV trắng chính giữa + Tốc độ & Cảnh báo biển báo/camera phía trên nóc xe
 *   + Khung Giờ & Lịch Âm Dương bên trái + Khung Thời tiết & Bản đồ Maps Chỉ Đường PIP bên phải
 *   + 4 Nút cánh chim đối xứng (DIRECTION, RADIO, CAMERA, PHONE) + Thanh Dock nhạc/giọng nói dưới cùng.
 * - Chế độ 2: Giao diện Zestech Đa Nhiệm PIP Thế Hệ Mới (Dock trái + Thẻ Nhạc Gradient + Khung Bong bóng Tốc độ & Maps + Hàng App).
 * - Tự động ẩn Bong bóng nổi khi đang ở Launcher và tự động bung Bong bóng nổi khi có ứng dụng khác đè lên Launcher.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_LOCATION_PERM = 1001
        private const val REQ_OVERLAY_PERM = 1002
    }

    private lateinit var prefs: SharedPreferences

    // Nền 3D Zestech & 2 Layout chế độ giao diện
    private lateinit var zestechRoadSceneView: ZestechRoadSceneView
    private lateinit var layoutZestechClassic3D: View
    private lateinit var layoutZestechModernPip: View

    // Top System Bar
    private lateinit var btnTopSwitchUiMode: TextView
    private lateinit var btnTopChooseWallpaper: TextView
    private lateinit var tvTopCurrentRoad: TextView
    private lateinit var tvAutoBubbleBadge: TextView
    private lateinit var tvTopClock: TextView

    // Góc trên trái: Đồng hồ lớn & Lịch Âm Dương + Nút Màu Phong Thủy
    private lateinit var tvBigClock: TextView
    private lateinit var btnFengShuiDot: View
    private lateinit var tvSolarDate: TextView
    private lateinit var tvDayOfWeek: TextView
    private lateinit var tvLunarDate: TextView

    // Chính giữa trên nóc xe SUV: Cụm Tốc Độ & Cảnh Báo Biển Báo / Camera
    private lateinit var ivLauncherCurrentLimitSign: ImageView
    private lateinit var launcherSpeedCircle: LinearLayout
    private lateinit var tvLauncherSpeed: TextView
    private lateinit var ivLauncherUpcomingSign: ImageView
    private lateinit var tvLauncherSafetyBadge: TextView
    private lateinit var tvLauncherUpcomingDistance: TextView
    private lateinit var tvLauncherUpcomingDesc: TextView
    private lateinit var pbUpcomingDistance: ProgressBar
    private lateinit var tvLauncherLimitCaption: TextView
    private lateinit var tvLauncherRoadName: TextView
    private lateinit var tvLauncherRoadDistrict: TextView

    // Góc phải: Bản đồ Maps Chỉ Đường PIP
    private lateinit var navigationMapView: NavigationMapView
    private lateinit var tvNavManeuverArrow: TextView
    private lateinit var tvNavNextDistance: TextView
    private lateinit var tvNavNextInstruction: TextView
    private lateinit var tvNavRouteSummary: TextView
    private lateinit var btnSearchDestination: TextView
    private lateinit var btnToggleMapMode: TextView
    private lateinit var btnMapZoomIn: TextView
    private lateinit var btnMapZoomOut: TextView
    private lateinit var btnMapRecenter: TextView

    // 4 Nút Cánh Chim Zestech (DIRECTION, RADIO, CAMERA, PHONE)
    private lateinit var btnWingDirection: View
    private lateinit var btnWingRadio: View
    private lateinit var btnWingCamera: View
    private lateinit var btnWingPhone: View

    // Giao diện Zestech Đa Nhiệm PIP (Chế độ 2)
    private lateinit var tvPipLeftClock: TextView
    private lateinit var tvPipSpeedBadge: TextView
    private lateinit var ivPipLimitSign: ImageView
    private lateinit var tvPipNavText: TextView
    private lateinit var tvPipAlertText: TextView
    private lateinit var btnPipBackTo3D: View
    private lateinit var btnPipNavShortcut: View
    private lateinit var btnPipApp1: View
    private lateinit var btnPipApp2: View
    private lateinit var btnPipApp3: View
    private lateinit var btnPipApp4: View
    private lateinit var btnPipApp5: View

    // Thanh Dock Dưới Cùng Chuẩn Zestech
    private lateinit var btnToggleVoiceMode: TextView
    private lateinit var btnShortcutMusic: TextView
    private lateinit var tvBottomVoiceStatus: TextView
    private lateinit var btnToggleSimulation: TextView
    private lateinit var btnMediaPlayPause: TextView
    private lateinit var btnShortcutTv: TextView
    private lateinit var btnToggleBubblePreview: TextView
    private lateinit var btnTestAutoBubble: TextView
    private lateinit var btnShortcutStore: TextView
    private lateinit var btnShortcutAllApps: TextView

    private val fengShuiPalette = intArrayOf(
        Color.parseColor("#F59E0B"), // Mệnh Kim / Vàng Hoàng Kim (Original Zestech)
        Color.parseColor("#EF4444"), // Mệnh Hỏa / Đỏ Thể Thao Zestech
        Color.parseColor("#0EA5E9"), // Mệnh Thủy / Xanh Công Nghệ Zestech
        Color.parseColor("#10B981"), // Mệnh Mộc / Xanh Lá Công Viên Zestech
        Color.parseColor("#A855F7"), // Mệnh Hỏa-Thổ / Tím Phong Thủy
        Color.parseColor("#EC4899"), // Hồng Neon Cá Tính
        Color.parseColor("#14B8A6")  // Xanh Ngọc Lục Bảo
    )
    private var currentFengShuiIdx = 0
    private var isModernPipUiMode = false

    private val bitmapCache = HashMap<String, Bitmap>()
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    private val clockRunnable = object : Runnable {
        override fun run() {
            val now = Date()
            val timeStr = clockFormat.format(now)
            tvTopClock.text = timeStr
            tvBigClock.text = timeStr
            tvPipLeftClock.text = timeStr
            tvSolarDate.text = dateFormat.format(now)
            clockHandler.postDelayed(this, 15_000L)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ZestechCoreService.ACTION_STATE_BROADCAST) return
            updateDashboardFromBroadcast(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(ZestechCoreService.PREFS_NAME, Context.MODE_PRIVATE)
        bindViews()
        setupClickListeners()
        handleCustomCommandIntent(intent)
        checkAndRequestPermissions()
        startCoreService(ZestechCoreService.ACTION_START_SERVICE)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleCustomCommandIntent(intent)
    }

    private fun handleCustomCommandIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.hasExtra("wallpaper_index")) {
            val wpIdx = intent.getIntExtra("wallpaper_index", 0)
            applyZestechWallpaper(wpIdx)
        }
        if (intent.hasExtra("feng_shui_index")) {
            val fsIdx = intent.getIntExtra("feng_shui_index", 0)
            currentFengShuiIdx = fsIdx.coerceIn(0, fengShuiPalette.lastIndex)
            zestechRoadSceneView.themeColor = fengShuiPalette[currentFengShuiIdx]
        }
        if (intent.hasExtra("ui_mode_pip")) {
            isModernPipUiMode = intent.getBooleanExtra("ui_mode_pip", false)
            applyUiModeVisibility()
        }
    }

    private fun bindViews() {
        zestechRoadSceneView = findViewById(R.id.zestechRoadSceneView)
        layoutZestechClassic3D = findViewById(R.id.layoutZestechClassic3D)
        layoutZestechModernPip = findViewById(R.id.layoutZestechModernPip)

        btnTopSwitchUiMode = findViewById(R.id.btnTopSwitchUiMode)
        btnTopChooseWallpaper = findViewById(R.id.btnTopChooseWallpaper)
        tvTopCurrentRoad = findViewById(R.id.tvTopCurrentRoad)
        tvAutoBubbleBadge = findViewById(R.id.tvAutoBubbleBadge)
        tvTopClock = findViewById(R.id.tvTopClock)

        tvBigClock = findViewById(R.id.tvBigClock)
        btnFengShuiDot = findViewById(R.id.btnFengShuiDot)
        tvSolarDate = findViewById(R.id.tvSolarDate)
        tvDayOfWeek = findViewById(R.id.tvDayOfWeek)
        tvLunarDate = findViewById(R.id.tvLunarDate)

        ivLauncherCurrentLimitSign = findViewById(R.id.ivLauncherCurrentLimitSign)
        launcherSpeedCircle = findViewById(R.id.launcherSpeedCircle)
        tvLauncherSpeed = findViewById(R.id.tvLauncherSpeed)
        ivLauncherUpcomingSign = findViewById(R.id.ivLauncherUpcomingSign)
        tvLauncherSafetyBadge = findViewById(R.id.tvLauncherSafetyBadge)
        tvLauncherUpcomingDistance = findViewById(R.id.tvLauncherUpcomingDistance)
        tvLauncherUpcomingDesc = findViewById(R.id.tvLauncherUpcomingDesc)
        pbUpcomingDistance = findViewById(R.id.pbUpcomingDistance)
        tvLauncherLimitCaption = findViewById(R.id.tvLauncherLimitCaption)
        tvLauncherRoadName = findViewById(R.id.tvLauncherRoadName)
        tvLauncherRoadDistrict = findViewById(R.id.tvLauncherRoadDistrict)

        navigationMapView = findViewById(R.id.navigationMapView)
        tvNavManeuverArrow = findViewById(R.id.tvNavManeuverArrow)
        tvNavNextDistance = findViewById(R.id.tvNavNextDistance)
        tvNavNextInstruction = findViewById(R.id.tvNavNextInstruction)
        tvNavRouteSummary = findViewById(R.id.tvNavRouteSummary)
        btnSearchDestination = findViewById(R.id.btnSearchDestination)
        btnToggleMapMode = findViewById(R.id.btnToggleMapMode)
        btnMapZoomIn = findViewById(R.id.btnMapZoomIn)
        btnMapZoomOut = findViewById(R.id.btnMapZoomOut)
        btnMapRecenter = findViewById(R.id.btnMapRecenter)

        btnWingDirection = findViewById(R.id.btnWingDirection)
        btnWingRadio = findViewById(R.id.btnWingRadio)
        btnWingCamera = findViewById(R.id.btnWingCamera)
        btnWingPhone = findViewById(R.id.btnWingPhone)

        tvPipLeftClock = findViewById(R.id.tvPipLeftClock)
        tvPipSpeedBadge = findViewById(R.id.tvPipSpeedBadge)
        ivPipLimitSign = findViewById(R.id.ivPipLimitSign)
        tvPipNavText = findViewById(R.id.tvPipNavText)
        tvPipAlertText = findViewById(R.id.tvPipAlertText)
        btnPipBackTo3D = findViewById(R.id.btnPipBackTo3D)
        btnPipNavShortcut = findViewById(R.id.btnPipNavShortcut)
        btnPipApp1 = findViewById(R.id.btnPipApp1)
        btnPipApp2 = findViewById(R.id.btnPipApp2)
        btnPipApp3 = findViewById(R.id.btnPipApp3)
        btnPipApp4 = findViewById(R.id.btnPipApp4)
        btnPipApp5 = findViewById(R.id.btnPipApp5)

        btnToggleVoiceMode = findViewById(R.id.btnToggleVoiceMode)
        btnShortcutMusic = findViewById(R.id.btnShortcutMusic)
        tvBottomVoiceStatus = findViewById(R.id.tvBottomVoiceStatus)
        btnToggleSimulation = findViewById(R.id.btnToggleSimulation)
        btnMediaPlayPause = findViewById(R.id.btnMediaPlayPause)
        btnShortcutTv = findViewById(R.id.btnShortcutTv)
        btnToggleBubblePreview = findViewById(R.id.btnToggleBubblePreview)
        btnTestAutoBubble = findViewById(R.id.btnTestAutoBubble)
        btnShortcutStore = findViewById(R.id.btnShortcutStore)
        btnShortcutAllApps = findViewById(R.id.btnShortcutAllApps)

        setAssetImage(ivLauncherCurrentLimitSign, "speed/60.png", isCircular = true)
        setAssetImage(ivPipLimitSign, "speed/60.png", isCircular = true)
        setAssetImage(ivLauncherUpcomingSign, "alerts/speed_limit_camera.png", isCircular = false)
        refreshControlButtonsText()
    }

    private fun setupClickListeners() {
        // Chuyển đổi giữa 2 giao diện gốc của Zestech: 3D Phong Thủy (Xe SUV giữa) ⇄ Đa Nhiệm PIP Thế Hệ Mới
        btnTopSwitchUiMode.setOnClickListener {
            isModernPipUiMode = !isModernPipUiMode
            applyUiModeVisibility()
        }

        // Hộp thoại "Vui Lòng Chọn Loại Hình Nền" chuẩn Zestech (Hình Nền Cơ Bản / Công Nghệ / Công Viên + 72 Màu Phong Thủy)
        btnTopChooseWallpaper.setOnClickListener {
            showZestechWallpaperChooserDialog()
        }
        btnFengShuiDot.setOnClickListener {
            currentFengShuiIdx = (currentFengShuiIdx + 1) % fengShuiPalette.size
            zestechRoadSceneView.themeColor = fengShuiPalette[currentFengShuiIdx]
            Toast.makeText(this, "Đã đổi Màu Phong Thủy Zestech (#${currentFengShuiIdx + 1}/72)", Toast.LENGTH_SHORT).show()
        }

        // 4 Nút Cánh Chim Zestech
        btnWingDirection.setOnClickListener {
            showRouteSelectionAndSearchDialog()
        }
        btnWingRadio.setOnClickListener {
            cycleVoiceMode()
        }
        btnWingCamera.setOnClickListener {
            toggleDemoSimulation()
        }
        btnWingPhone.setOnClickListener {
            launchOverlayTargetApp()
        }

        // Nút trên cửa sổ Bản đồ Maps
        btnSearchDestination.setOnClickListener {
            showRouteSelectionAndSearchDialog()
        }
        btnToggleMapMode.setOnClickListener {
            navigationMapView.isHeadingUpMode = !navigationMapView.isHeadingUpMode
            btnToggleMapMode.text = if (navigationMapView.isHeadingUpMode) "3D" else "2D"
        }
        btnMapZoomIn.setOnClickListener { navigationMapView.zoomIn() }
        btnMapZoomOut.setOnClickListener { navigationMapView.zoomOut() }
        btnMapRecenter.setOnClickListener { navigationMapView.recenterCar() }

        // Nút trên Giao diện Đa Nhiệm PIP (Chế độ 2)
        btnPipBackTo3D.setOnClickListener {
            isModernPipUiMode = false
            applyUiModeVisibility()
        }
        btnPipNavShortcut.setOnClickListener { showRouteSelectionAndSearchDialog() }
        btnPipApp1.setOnClickListener {
            isModernPipUiMode = false
            applyUiModeVisibility()
        }
        btnPipApp2.setOnClickListener { cycleVoiceMode() }
        btnPipApp3.setOnClickListener {
            if (!tryLaunchPackage("com.lochv.zestechsetting")) launchOverlayTargetApp()
        }
        btnPipApp4.setOnClickListener {
            if (!tryLaunchPackage("vn.nct.carplayer")) launchOverlayTargetApp()
        }
        btnPipApp5.setOnClickListener {
            if (!tryLaunchPackage("com.lochv.zestech_onlineTV") && !tryLaunchPackage("com.lochv.CartaTelevision")) {
                launchOverlayTargetApp()
            }
        }

        // Thanh Dock Dưới Cùng
        btnToggleVoiceMode.setOnClickListener { cycleVoiceMode() }
        btnToggleSimulation.setOnClickListener { toggleDemoSimulation() }
        btnMediaPlayPause.setOnClickListener {
            if (!tryLaunchPackage("vn.nct.carplayer")) launchOverlayTargetApp()
        }
        btnShortcutMusic.setOnClickListener {
            if (!tryLaunchPackage("vn.nct.carplayer")) launchOverlayTargetApp()
        }
        btnShortcutTv.setOnClickListener {
            if (!tryLaunchPackage("com.lochv.zestech_onlineTV") && !tryLaunchPackage("com.lochv.CartaTelevision")) {
                launchOverlayTargetApp()
            }
        }
        btnToggleBubblePreview.setOnClickListener {
            val curForce = prefs.getBoolean(ZestechCoreService.KEY_FORCE_BUBBLE_ON_LAUNCHER, false)
            val nextForce = !curForce
            prefs.edit().putBoolean(ZestechCoreService.KEY_FORCE_BUBBLE_ON_LAUNCHER, nextForce).apply()
            refreshControlButtonsText()
            startCoreService(ZestechCoreService.ACTION_RELOAD_SETTINGS)
        }
        btnTestAutoBubble.setOnClickListener {
            launchOverlayTargetApp()
        }
        btnShortcutStore.setOnClickListener {
            if (!tryLaunchPackage("com.lochv.zestechsetting")) showInstalledAppsDrawerDialog()
        }
        btnShortcutAllApps.setOnClickListener {
            showInstalledAppsDrawerDialog()
        }
    }

    private fun applyUiModeVisibility() {
        if (isModernPipUiMode) {
            layoutZestechClassic3D.visibility = View.GONE
            layoutZestechModernPip.visibility = View.VISIBLE
        } else {
            layoutZestechModernPip.visibility = View.GONE
            layoutZestechClassic3D.visibility = View.VISIBLE
        }
    }

    private fun applyZestechWallpaper(index: Int) {
        when (index) {
            0 -> {
                zestechRoadSceneView.wallpaperType = ZestechRoadSceneView.WallpaperType.ORIGINAL
                zestechRoadSceneView.themeColor = Color.parseColor("#F59E0B") // Vàng Hoàng Kim Zestech
            }
            1 -> {
                zestechRoadSceneView.wallpaperType = ZestechRoadSceneView.WallpaperType.TECH
                zestechRoadSceneView.themeColor = Color.parseColor("#0EA5E9") // Xanh Công Nghệ Zestech
            }
            2 -> {
                zestechRoadSceneView.wallpaperType = ZestechRoadSceneView.WallpaperType.PARK
                zestechRoadSceneView.themeColor = Color.parseColor("#10B981") // Xanh Lá Công Viên Zestech
            }
            3 -> {
                zestechRoadSceneView.wallpaperType = ZestechRoadSceneView.WallpaperType.TECH
                zestechRoadSceneView.themeColor = Color.parseColor("#EF4444") // Đỏ Phong Thủy Mệnh Hỏa
            }
        }
    }

    private fun showZestechWallpaperChooserDialog() {
        val items = arrayOf(
            "1. Hình Nền Cơ Bản (Phiên bản Original - Vàng Hoàng Kim)",
            "2. Hình Nền Công Nghệ (Phiên bản Công Nghệ - Xanh Cyber)",
            "3. Hình Nền Công Viên (Phiên bản Công Viên - Xanh Tự Nhiên)",
            "4. Màu Phong Thủy Mệnh Hỏa (Đỏ Thể Thao Zestech)",
            "5. Chuyển sang Giao diện Zestech Đa Nhiệm PIP Thế Hệ Mới"
        )
        AlertDialog.Builder(this)
            .setTitle("Vui Lòng Chọn Loại Hình Nền Zestech:")
            .setItems(items) { _, which ->
                if (which in 0..3) {
                    isModernPipUiMode = false
                    applyUiModeVisibility()
                    applyZestechWallpaper(which)
                } else {
                    isModernPipUiMode = true
                    applyUiModeVisibility()
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun toggleDemoSimulation() {
        val curSim = prefs.getBoolean(ZestechCoreService.KEY_IS_SIMULATION, true)
        val nextSim = !curSim
        prefs.edit().putBoolean(ZestechCoreService.KEY_IS_SIMULATION, nextSim).apply()
        refreshControlButtonsText()
        startCoreService(ZestechCoreService.ACTION_RELOAD_SETTINGS)
        val msg = if (nextSim) "Đã bật Demo Lộ trình 30km" else "Đã chuyển sang GPS Thực Tế"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun cycleVoiceMode() {
        val curSound = prefs.getInt(ZestechCoreService.KEY_SOUND_MODE, AudioAlertManager.SOUND_MODE_FULL)
        val nextSound = (curSound + 1) % 3
        prefs.edit().putInt(ZestechCoreService.KEY_SOUND_MODE, nextSound).apply()
        refreshControlButtonsText()
        startCoreService(ZestechCoreService.ACTION_RELOAD_SETTINGS)
    }

    private fun refreshControlButtonsText() {
        val soundMode = prefs.getInt(ZestechCoreService.KEY_SOUND_MODE, AudioAlertManager.SOUND_MODE_FULL)
        tvBottomVoiceStatus.text = when (soundMode) {
            AudioAlertManager.SOUND_MODE_FULL -> "🔊 Giọng nói Việt + Cảnh báo tốc độ"
            AudioAlertManager.SOUND_MODE_DING -> "🔔 Chỉ phát tiếng Bíp cảnh báo"
            else -> "🔇 Đã tắt âm cảnh báo"
        }

        val forceBubble = prefs.getBoolean(ZestechCoreService.KEY_FORCE_BUBBLE_ON_LAUNCHER, false)
        btnToggleBubblePreview.text = if (forceBubble) {
            "👁 Bong bóng: LUÔN BẬT"
        } else {
            "👁 Bong bóng: Tự Động"
        }
        tvAutoBubbleBadge.text = if (forceBubble) {
            "🟢 Bubble: HIỂN THỊ CẢ TRÊN LAUNCHER"
        } else {
            "🟢 Auto-Bubble: TỰ BẬT KHI MỞ APP KHÁC"
        }
    }

    private fun updateDashboardFromBroadcast(intent: Intent) {
        val speedKmh = intent.getIntExtra("speed_kmh", 0)
        val speedLimit = intent.getIntExtra("speed_limit", 60)
        val roadName = intent.getStringExtra("road_name") ?: "Đường Lê Lợi"
        val adminArea = intent.getStringExtra("admin_area") ?: "TP. Hồ Chí Minh"
        val lat = intent.getDoubleExtra("lat", 10.772109)
        val lon = intent.getDoubleExtra("lon", 106.698272)
        val bearing = intent.getFloatExtra("bearing", 65f)
        val adaptiveScanM = intent.getIntExtra("adaptive_scan_m", 330)
        val isOverspeed = intent.getBooleanExtra("is_overspeed", false)

        val hasAlert = intent.getBooleanExtra("has_alert", false)
        val alertId = intent.getIntExtra("alert_id", -1)
        val alertName = intent.getStringExtra("alert_name") ?: ""
        val alertDistM = intent.getIntExtra("alert_dist_m", 0)
        val alertAsset = intent.getStringExtra("alert_asset") ?: "alerts/speed_limit_camera.png"

        val navDest = intent.getStringExtra("nav_dest") ?: "Cao tốc Long Thành"
        val navSymbol = intent.getStringExtra("nav_symbol") ?: "↱"
        val navStreet = intent.getStringExtra("nav_street") ?: "Hầm Thủ Thiêm"
        val navTurnDistM = intent.getIntExtra("nav_turn_dist_m", 180)
        val navRemainKm = intent.getDoubleExtra("nav_remain_km", 28.4)
        val navEtaMin = intent.getIntExtra("nav_eta_min", 26)

        val validSign = if (speedLimit in listOf(30, 40, 50, 60, 70, 80, 90, 100, 120)) speedLimit else 60

        // 1. Cập nhật Nền 3D Zestech (Hiệu ứng vạch kẻ đường chạy theo tốc độ & đổi màu đỏ khi vượt tốc)
        zestechRoadSceneView.updateTelemetry(speedKmh, validSign, isOverspeed)

        // 2. Cập nhật Số tốc độ chính giữa phía trên nóc xe SUV trắng
        tvTopCurrentRoad.text = if (adminArea.isNotEmpty()) {
            "📍 $roadName • $adminArea"
        } else {
            "📍 $roadName"
        }
        tvLauncherSpeed.text = speedKmh.toString()
        tvPipSpeedBadge.text = "$speedKmh\nkm/h"

        if (isOverspeed) {
            launcherSpeedCircle.setBackgroundResource(R.drawable.bg_speedometer_overspeed)
            tvLauncherSpeed.setTextColor(Color.WHITE)
            tvLauncherSafetyBadge.text = "⚠ VƯỢT TỐC ĐỘ! GIỚI HẠN $validSign"
            tvLauncherSafetyBadge.setTextColor(Color.parseColor("#EF4444"))
        } else {
            launcherSpeedCircle.setBackgroundResource(R.drawable.bg_speedometer_normal)
            tvLauncherSpeed.setTextColor(Color.WHITE)
            tvLauncherSafetyBadge.text = "AN TOÀN • GIỚI HẠN $validSign"
            tvLauncherSafetyBadge.setTextColor(Color.parseColor("#4ADE80"))
        }

        setAssetImage(ivLauncherCurrentLimitSign, "speed/${validSign}.png", isCircular = true)
        setAssetImage(ivPipLimitSign, "speed/${validSign}.png", isCircular = true)
        tvLauncherLimitCaption.text = "Giới hạn $validSign km/h • Quét ${adaptiveScanM}m"
        tvLauncherRoadName.text = roadName
        tvLauncherRoadDistrict.text = if (adminArea.isNotEmpty()) {
            "📍 $adminArea • Nhiều Mây"
        } else {
            "📍 TP. Hồ Chí Minh • Nhiều Mây"
        }

        // 3. Cập nhật Cảnh báo Biển báo / Camera phạt nguội sắp tới trên Launcher
        pbUpcomingDistance.max = adaptiveScanM
        if (hasAlert && alertDistM > 0) {
            setAssetImage(ivLauncherUpcomingSign, alertAsset, isCircular = alertAsset.startsWith("speed/"))
            tvLauncherUpcomingDistance.text = "Cách ${alertDistM}m: $alertName"
            tvLauncherUpcomingDistance.setTextColor(Color.parseColor("#FBBF24"))
            tvLauncherUpcomingDesc.text = "Chú ý giảm tốc độ trên $roadName"
            pbUpcomingDistance.progress = (adaptiveScanM - alertDistM).coerceIn(15, adaptiveScanM)
            tvPipAlertText.text = "⚠ Cách ${alertDistM}m: $alertName"
        } else {
            setAssetImage(ivLauncherUpcomingSign, "speed/${validSign}.png", isCircular = true)
            tvLauncherUpcomingDistance.text = "Phía trước ${adaptiveScanM}m thông thoáng"
            tvLauncherUpcomingDistance.setTextColor(Color.parseColor("#4ADE80"))
            tvLauncherUpcomingDesc.text = "Đang giữ giới hạn $validSign km/h trên $roadName"
            pbUpcomingDistance.progress = 0
            tvPipAlertText.text = "$roadName • Giới hạn $validSign km/h"
        }

        // 4. Cập nhật Bản đồ Zestech Maps Chỉ Đường Turn-by-Turn
        tvNavManeuverArrow.text = navSymbol
        tvNavNextDistance.text = "${navTurnDistM} m"
        tvNavNextInstruction.text = navStreet
        tvNavRouteSummary.text = String.format(
            "Đích: %s • Còn %.1f km (%d phút)",
            navDest,
            navRemainKm,
            navEtaMin
        )
        tvPipNavText.text = "$navSymbol ${navTurnDistM}m • $navStreet • Còn ${String.format("%.1f", navRemainKm)}km"

        val svc = ZestechCoreService.instance
        val activeRoute = svc?.navigationEngine?.activeRoute
        val nearbyPoints = svc?.lastSpatialResult?.nearbyPoints ?: emptyList()
        val closestIdx = svc?.lastNavTelemetry?.closestWaypointIndex ?: 0

        navigationMapView.updateMapState(
            lat = lat,
            lon = lon,
            bearingDeg = bearing,
            speedKmh = speedKmh,
            speedLimit = validSign,
            scanM = adaptiveScanM,
            roadName = roadName,
            route = activeRoute?.points ?: emptyList(),
            maneuverList = activeRoute?.maneuvers ?: emptyList(),
            closestIdx = closestIdx,
            edogPoints = nearbyPoints,
            upcomingAlertId = alertId
        )
    }

    private fun showRouteSelectionAndSearchDialog() {
        val options = arrayOf(
            "1. Bến Thành → Hầm Thủ Thiêm → Cao tốc Long Thành (30.2 km)",
            "2. Dinh Độc Lập → Cầu Sài Gòn → Vinhomes Landmark 81 (6.5 km)",
            "3. Hồ Gươm → Cầu Nhật Tân → Sân bay Nội Bài (26.8 km)",
            "🔍 Nhập tên Điểm đến bất kỳ (Tìm đường nhanh)..."
        )
        AlertDialog.Builder(this)
            .setTitle("🗺 Bản đồ Zestech Maps • Chọn Lộ Trình Chỉ Đường")
            .setItems(options) { _, which ->
                if (which in 0..2) {
                    val intent = Intent(this, ZestechCoreService::class.java).apply {
                        action = ZestechCoreService.ACTION_SELECT_ROUTE
                        putExtra("route_id", which)
                    }
                    startServiceCompat(intent)
                    Toast.makeText(this, "Đã kích hoạt lộ trình: ${options[which]}", Toast.LENGTH_SHORT).show()
                } else {
                    showCustomDestinationInputDialog()
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showCustomDestinationInputDialog() {
        val input = EditText(this).apply {
            hint = "Nhập điểm đến (VD: Sân bay Tân Sơn Nhất, Vũng Tàu...)"
            setPadding(36, 28, 36, 28)
        }
        AlertDialog.Builder(this)
            .setTitle("🔍 Tìm Đường Tới Điểm Đến")
            .setView(input)
            .setPositiveButton("Bắt đầu Dẫn đường") { _, _ ->
                val destName = input.text.toString().trim().ifEmpty { "Sân bay Tân Sơn Nhất" }
                val svc = ZestechCoreService.instance
                val baseLat = svc?.currentLat ?: 10.772109
                val baseLon = svc?.currentLon ?: 106.698272
                val intent = Intent(this, ZestechCoreService::class.java).apply {
                    action = ZestechCoreService.ACTION_CUSTOM_DESTINATION
                    putExtra("dest_name", destName)
                    putExtra("dest_lat", baseLat + 0.032)
                    putExtra("dest_lon", baseLon + 0.042)
                }
                startServiceCompat(intent)
                Toast.makeText(this, "Đang dẫn đường tới: $destName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun launchOverlayTargetApp() {
        val preferredPackages = listOf(
            "vn.nct.carplayer",
            "com.lochv.zestech_onlineTV",
            "com.lochv.zestechsetting"
        )
        for (pkg in preferredPackages) {
            if (tryLaunchPackage(pkg)) {
                Toast.makeText(
                    this,
                    "Đã mở $pkg đè lên Launcher → Bong bóng nổi Zestech tự động bật!",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
        }
        launchSystemSettings()
    }

    private fun tryLaunchPackage(pkg: String): Boolean {
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun launchSystemSettings() {
        try {
            val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(settingsIntent)
        } catch (_: Exception) {
        }
    }

    private fun showInstalledAppsDrawerDialog() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolves = pm.queryIntentActivities(mainIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase(Locale.getDefault()) }

        val labels = resolves.map { "${it.loadLabel(pm)} (${it.activityInfo.packageName})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("⊞ Kho Ứng Dụng Trên Màn Hình Zestech (${labels.size} App)")
            .setItems(labels) { _, which ->
                val target = resolves[which]
                tryLaunchPackage(target.activityInfo.packageName)
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun setAssetImage(imageView: ImageView, assetPath: String, isCircular: Boolean) {
        try {
            val key = if (isCircular) "circle_$assetPath" else assetPath
            var bmp = bitmapCache[key]
            if (bmp == null) {
                assets.open(assetPath).use { stream ->
                    val raw = BitmapFactory.decodeStream(stream)
                    bmp = if (raw != null && isCircular) cropCircular(raw) else raw
                }
                if (bmp != null) bitmapCache[key] = bmp!!
            }
            if (bmp != null) imageView.setImageBitmap(bmp)
        } catch (_: Exception) {
        }
    }

    private fun cropCircular(src: Bitmap): Bitmap {
        val size = min(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        canvas.drawCircle(r, r, r * 0.94f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, -(src.width - size) / 2f, -(src.height - size) / 2f, paint)
        return out
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQ_OVERLAY_PERM)
            } catch (_: Exception) {
            }
        }
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION_PERM
            )
        }
    }

    private fun startCoreService(actionStr: String) {
        val svcIntent = Intent(this, ZestechCoreService::class.java).apply {
            action = actionStr
        }
        startServiceCompat(svcIntent)
    }

    private fun startServiceCompat(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {
            try {
                startService(intent)
            } catch (_: Exception) {
            }
        }
    }

    private fun notifyLauncherVisibility(isVisible: Boolean) {
        val visIntent = Intent(this, ZestechCoreService::class.java).apply {
            action = ZestechCoreService.ACTION_LAUNCHER_VISIBILITY
            putExtra("is_launcher_visible", isVisible)
        }
        startServiceCompat(visIntent)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        clockHandler.post(clockRunnable)
        refreshControlButtonsText()
        val filter = IntentFilter(ZestechCoreService.ACTION_STATE_BROADCAST)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        notifyLauncherVisibility(true)
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
        notifyLauncherVisibility(false)
    }
}
