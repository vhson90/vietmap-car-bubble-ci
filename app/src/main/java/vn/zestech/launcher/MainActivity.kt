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
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Màn hình chính Zestech Pro Launcher (3-Pane Automotive Dashboard):
 * 1. Cụm Trái: HUD Cảnh báo tốc độ trực tiếp trên Launcher + Biển báo giới hạn + Thanh đếm ngược Camera/Biển báo phía trước.
 * 2. Cụm Giữa: Bản đồ Maps Chỉ Đường Turn-by-Turn 3D nhúng trực tiếp + Tìm kiếm điểm đến & Lộ trình.
 * 3. Cụm Phải: Quản lý Bong bóng nổi tự động (tự ẩn khi ở Launcher, tự bung khi có App khác đè lên) + Kho ứng dụng Zestech.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_LOCATION_PERM = 1001
        private const val REQ_OVERLAY_PERM = 1002
    }

    private lateinit var prefs: SharedPreferences

    // Top Bar
    private lateinit var tvCurrentRoadHeader: TextView
    private lateinit var tvBubbleAutoStatus: TextView
    private lateinit var tvLauncherClock: TextView

    // Left Pane: Speed & Traffic Sign Cluster
    private lateinit var tvOverspeedBadge: TextView
    private lateinit var cardLauncherSpeedBox: LinearLayout
    private lateinit var tvLauncherSpeed: TextView
    private lateinit var ivLauncherLimitSign: ImageView
    private lateinit var tvLauncherLimitCaption: TextView
    private lateinit var tvLauncherRoadName: TextView
    private lateinit var tvLauncherAdminArea: TextView
    private lateinit var ivLauncherUpcomingIcon: ImageView
    private lateinit var tvLauncherUpcomingDist: TextView
    private lateinit var tvLauncherUpcomingTitle: TextView
    private lateinit var pbLauncherAlertProgress: ProgressBar
    private lateinit var btnSwitchGpsMode: Button
    private lateinit var btnSwitchSoundMode: Button

    // Center Pane: Embedded Maps Turn-by-Turn Navigation
    private lateinit var navigationMapView: NavigationMapView
    private lateinit var tvNavManeuverSymbol: TextView
    private lateinit var tvNavTurnDist: TextView
    private lateinit var tvNavNextStreet: TextView
    private lateinit var tvNavEtaSummary: TextView
    private lateinit var btnSelectRoute: Button
    private lateinit var btnMapMode: Button
    private lateinit var btnMapZoomIn: Button
    private lateinit var btnMapZoomOut: Button
    private lateinit var btnMapRecenter: Button

    // Right Pane: Auto-Bubble Controls & Zestech Ecosystem Apps
    private lateinit var btnLaunchOverlayTestApp: Button
    private lateinit var btnToggleForceBubble: Button
    private lateinit var btnAppMusic: Button
    private lateinit var btnAppTivi: Button
    private lateinit var btnAppZestechStore: Button
    private lateinit var btnAppDrawer: Button

    private val bitmapCache = HashMap<String, Bitmap>()
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val clockRunnable = object : Runnable {
        override fun run() {
            tvLauncherClock.text = clockFormat.format(Date())
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
        checkAndRequestPermissions()
        startCoreService(ZestechCoreService.ACTION_START_SERVICE)
    }

    private fun bindViews() {
        tvCurrentRoadHeader = findViewById(R.id.tvCurrentRoadHeader)
        tvBubbleAutoStatus = findViewById(R.id.tvBubbleAutoStatus)
        tvLauncherClock = findViewById(R.id.tvLauncherClock)

        tvOverspeedBadge = findViewById(R.id.tvOverspeedBadge)
        cardLauncherSpeedBox = findViewById(R.id.cardLauncherSpeedBox)
        tvLauncherSpeed = findViewById(R.id.tvLauncherSpeed)
        ivLauncherLimitSign = findViewById(R.id.ivLauncherLimitSign)
        tvLauncherLimitCaption = findViewById(R.id.tvLauncherLimitCaption)
        tvLauncherRoadName = findViewById(R.id.tvLauncherRoadName)
        tvLauncherAdminArea = findViewById(R.id.tvLauncherAdminArea)
        ivLauncherUpcomingIcon = findViewById(R.id.ivLauncherUpcomingIcon)
        tvLauncherUpcomingDist = findViewById(R.id.tvLauncherUpcomingDist)
        tvLauncherUpcomingTitle = findViewById(R.id.tvLauncherUpcomingTitle)
        pbLauncherAlertProgress = findViewById(R.id.pbLauncherAlertProgress)
        btnSwitchGpsMode = findViewById(R.id.btnSwitchGpsMode)
        btnSwitchSoundMode = findViewById(R.id.btnSwitchSoundMode)

        navigationMapView = findViewById(R.id.navigationMapView)
        tvNavManeuverSymbol = findViewById(R.id.tvNavManeuverSymbol)
        tvNavTurnDist = findViewById(R.id.tvNavTurnDist)
        tvNavNextStreet = findViewById(R.id.tvNavNextStreet)
        tvNavEtaSummary = findViewById(R.id.tvNavEtaSummary)
        btnSelectRoute = findViewById(R.id.btnSelectRoute)
        btnMapMode = findViewById(R.id.btnMapMode)
        btnMapZoomIn = findViewById(R.id.btnMapZoomIn)
        btnMapZoomOut = findViewById(R.id.btnMapZoomOut)
        btnMapRecenter = findViewById(R.id.btnMapRecenter)

        btnLaunchOverlayTestApp = findViewById(R.id.btnLaunchOverlayTestApp)
        btnToggleForceBubble = findViewById(R.id.btnToggleForceBubble)
        btnAppMusic = findViewById(R.id.btnAppMusic)
        btnAppTivi = findViewById(R.id.btnAppTivi)
        btnAppZestechStore = findViewById(R.id.btnAppZestechStore)
        btnAppDrawer = findViewById(R.id.btnAppDrawer)

        setAssetImage(ivLauncherLimitSign, "speed/60.png", isCircular = true)
        setAssetImage(ivLauncherUpcomingIcon, "alerts/speed_limit_camera.png", isCircular = false)
        refreshControlButtonsText()
    }

    private fun setupClickListeners() {
        btnSwitchGpsMode.setOnClickListener {
            val curSim = prefs.getBoolean(ZestechCoreService.KEY_IS_SIMULATION, true)
            val nextSim = !curSim
            prefs.edit().putBoolean(ZestechCoreService.KEY_IS_SIMULATION, nextSim).apply()
            refreshControlButtonsText()
            startCoreService(ZestechCoreService.ACTION_RELOAD_SETTINGS)
            val msg = if (nextSim) "Đã bật Chế độ Demo Lộ trình 30km" else "Đã chuyển sang thu tín hiệu GPS Thực Tế"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        btnSwitchSoundMode.setOnClickListener {
            val curSound = prefs.getInt(ZestechCoreService.KEY_SOUND_MODE, AudioAlertManager.SOUND_MODE_FULL)
            val nextSound = (curSound + 1) % 3
            prefs.edit().putInt(ZestechCoreService.KEY_SOUND_MODE, nextSound).apply()
            refreshControlButtonsText()
            startCoreService(ZestechCoreService.ACTION_RELOAD_SETTINGS)
        }

        btnSelectRoute.setOnClickListener {
            showRouteSelectionAndSearchDialog()
        }

        btnMapMode.setOnClickListener {
            navigationMapView.isHeadingUpMode = !navigationMapView.isHeadingUpMode
            btnMapMode.text = if (navigationMapView.isHeadingUpMode) "3D" else "2D"
        }

        btnMapZoomIn.setOnClickListener {
            navigationMapView.zoomIn()
        }

        btnMapZoomOut.setOnClickListener {
            navigationMapView.zoomOut()
        }

        btnMapRecenter.setOnClickListener {
            navigationMapView.recenterCar()
        }

        btnLaunchOverlayTestApp.setOnClickListener {
            launchOverlayTargetApp()
        }

        btnToggleForceBubble.setOnClickListener {
            val curForce = prefs.getBoolean(ZestechCoreService.KEY_FORCE_BUBBLE_ON_LAUNCHER, false)
            val nextForce = !curForce
            prefs.edit().putBoolean(ZestechCoreService.KEY_FORCE_BUBBLE_ON_LAUNCHER, nextForce).apply()
            refreshControlButtonsText()
            startCoreService(ZestechCoreService.ACTION_RELOAD_SETTINGS)
        }

        btnAppMusic.setOnClickListener {
            if (!tryLaunchPackage("vn.nct.carplayer")) {
                launchOverlayTargetApp()
            }
        }

        btnAppTivi.setOnClickListener {
            if (!tryLaunchPackage("com.lochv.zestech_onlineTV") && !tryLaunchPackage("com.lochv.CartaTelevision")) {
                Toast.makeText(this, "Đang mở Cài đặt hệ thống để kiểm chứng Bong bóng nổi...", Toast.LENGTH_SHORT).show()
                launchSystemSettings()
            }
        }

        btnAppZestechStore.setOnClickListener {
            if (!tryLaunchPackage("com.lochv.zestechsetting")) {
                launchSystemSettings()
            }
        }

        btnAppDrawer.setOnClickListener {
            showInstalledAppsDrawerDialog()
        }
    }

    private fun refreshControlButtonsText() {
        val isSim = prefs.getBoolean(ZestechCoreService.KEY_IS_SIMULATION, true)
        btnSwitchGpsMode.text = if (isSim) "▶ Demo 30km" else "📡 GPS Thực"
        btnSwitchGpsMode.backgroundTintList = android.content.res.ColorStateList.valueOf(
            Color.parseColor(if (isSim) "#0284C7" else "#10B981")
        )

        val soundMode = prefs.getInt(ZestechCoreService.KEY_SOUND_MODE, AudioAlertManager.SOUND_MODE_FULL)
        btnSwitchSoundMode.text = when (soundMode) {
            AudioAlertManager.SOUND_MODE_FULL -> "🔊 Giọng nói"
            AudioAlertManager.SOUND_MODE_DING -> "🔔 Chỉ Chuông"
            else -> "🔇 Tắt âm"
        }

        val forceBubble = prefs.getBoolean(ZestechCoreService.KEY_FORCE_BUBBLE_ON_LAUNCHER, false)
        btnToggleForceBubble.text = if (forceBubble) {
            "👁 Xem thử Bong bóng trên Launcher: BẬT"
        } else {
            "👁 Xem thử Bong bóng trên Launcher: TẮT"
        }
        tvBubbleAutoStatus.text = if (forceBubble) {
            "🟢 Bubble: LUÔN HIỆN"
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

        // 1. Cập nhật Header & Cụm Tốc Độ
        tvCurrentRoadHeader.text = if (adminArea.isNotEmpty()) {
            "📍 $roadName • $adminArea"
        } else {
            "📍 $roadName"
        }

        tvLauncherSpeed.text = speedKmh.toString()
        if (isOverspeed) {
            cardLauncherSpeedBox.setBackgroundResource(R.drawable.bg_speedometer_overspeed)
            tvLauncherSpeed.setTextColor(Color.WHITE)
            tvOverspeedBadge.text = "⚠ QUÁ TỐC ĐỘ!"
            tvOverspeedBadge.setTextColor(Color.parseColor("#EF4444"))
        } else {
            cardLauncherSpeedBox.setBackgroundResource(R.drawable.bg_speedometer_normal)
            tvLauncherSpeed.setTextColor(Color.parseColor("#38BDF8"))
            tvOverspeedBadge.text = "AN TOÀN"
            tvOverspeedBadge.setTextColor(Color.parseColor("#4ADE80"))
        }

        val validSign = if (speedLimit in listOf(30, 40, 50, 60, 70, 80, 90, 100, 120)) speedLimit else 60
        setAssetImage(ivLauncherLimitSign, "speed/${validSign}.png", isCircular = true)
        tvLauncherLimitCaption.text = "Giới hạn $validSign"

        tvLauncherRoadName.text = roadName
        tvLauncherAdminArea.text = if (adminArea.isNotEmpty()) {
            "$adminArea • Quét ${adaptiveScanM}m"
        } else {
            "Tầm quét thông minh ${adaptiveScanM}m"
        }

        // 2. Cập nhật Cụm Cảnh báo Biển báo / Camera sắp tới (LimitVantocView nâng cấp)
        pbLauncherAlertProgress.max = adaptiveScanM
        if (hasAlert && alertDistM > 0) {
            setAssetImage(ivLauncherUpcomingIcon, alertAsset, isCircular = alertAsset.startsWith("speed/"))
            tvLauncherUpcomingDist.text = "Cách ${alertDistM}m phía trước"
            tvLauncherUpcomingDist.setTextColor(Color.parseColor("#FBBF24"))
            tvLauncherUpcomingTitle.text = alertName
            val progressVal = (adaptiveScanM - alertDistM).coerceIn(15, adaptiveScanM)
            pbLauncherAlertProgress.progress = progressVal
        } else {
            setAssetImage(ivLauncherUpcomingIcon, "speed/${validSign}.png", isCircular = true)
            tvLauncherUpcomingDist.text = "Phía trước ${adaptiveScanM}m thông thoáng"
            tvLauncherUpcomingDist.setTextColor(Color.parseColor("#4ADE80"))
            tvLauncherUpcomingTitle.text = "Đang giữ giới hạn $validSign km/h trên $roadName"
            pbLauncherAlertProgress.progress = 0
        }

        // 3. Cập nhật Banner & Bản đồ Dẫn đường Turn-by-Turn
        tvNavManeuverSymbol.text = navSymbol
        tvNavTurnDist.text = "${navTurnDistM} m"
        tvNavNextStreet.text = navStreet
        tvNavEtaSummary.text = String.format(
            "Đích: %s • Còn %.1f km (%d phút)",
            navDest,
            navRemainKm,
            navEtaMin
        )

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

    /**
     * Hộp thoại Chọn lộ trình có sẵn hoặc Tìm kiếm điểm đến mới cho Bản đồ Chỉ đường Zestech
     */
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
            hint = "Nhập điểm đến (VD: Sân bay Tân Sơn Nhất, Vũng Tàu, Chợ Lớn...)"
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

    /**
     * Mở một ứng dụng khác đè lên Zestech Launcher (ưu tiên NCT Car Player, Zestech Tivi, hoặc Cài đặt Android)
     * để kiểm chứng Bong bóng nổi tự động xuất hiện ngay lập tức!
     */
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
            Toast.makeText(
                this,
                "Đã mở Cài đặt hệ thống đè lên Launcher → Bong bóng nổi tự động bật!",
                Toast.LENGTH_SHORT
            ).show()
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
        // Khi người dùng đang ở màn hình chính Zestech Launcher -> Báo cho Service ẩn Bong bóng nổi
        notifyLauncherVisibility(true)
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
        // Ngay khi có ứng dụng khác đè lên Launcher -> Báo cho Service lập tức bật Bong bóng nổi!
        notifyLauncherVisibility(false)
    }
}
