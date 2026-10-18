package vn.vietmap.carbubble

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import vn.vietmap.carbubble.service.FloatingBubbleService

/**
 * Màn hình điều khiển trung tâm (Dashboard v2.0) hỗ trợ tùy chỉnh kích thước bong bóng siêu gọn,
 * độ trong suốt tự làm mờ khi đường thông thoáng, và chế độ tối giản 2 vòng tròn.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_LOCATION_PERM = 1001
        private const val REQ_OVERLAY_PERM = 1002
    }

    private lateinit var prefs: SharedPreferences

    // UI Controls
    private lateinit var tvStatusBanner: TextView
    private lateinit var btnToggleBubble: Button
    private lateinit var btnGrantPermissions: Button
    private lateinit var rgLocationMode: RadioGroup
    private lateinit var rbRealGps: RadioButton
    private lateinit var rbSimRoute: RadioButton

    // Live Telemetry Preview Card
    private lateinit var tvLiveSpeed: TextView
    private lateinit var ivLiveLimitSign: ImageView
    private lateinit var tvLiveZoneName: TextView
    private lateinit var tvLiveUpcomingAlert: TextView
    private lateinit var tvLiveCoords: TextView

    // Compact Bubble Customization Controls
    private lateinit var sbBubbleScale: SeekBar
    private lateinit var tvBubbleScaleLabel: TextView
    private lateinit var sbIdleAlpha: SeekBar
    private lateinit var tvIdleAlphaLabel: TextView
    private lateinit var swUltraMiniMode: Switch

    // Settings Sliders & Switches
    private lateinit var sbSimSpeedOverride: SeekBar
    private lateinit var tvSimSpeedOverrideLabel: TextView
    private lateinit var sbOverspeedThreshold: SeekBar
    private lateinit var tvOverspeedThresholdLabel: TextView
    private lateinit var swVoiceAlert: Switch
    private lateinit var swAutoBoot: Switch
    private lateinit var btnResetSimRoute: Button

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != FloatingBubbleService.ACTION_STATE_BROADCAST) return
            val speedKmh = intent.getIntExtra("speed_kmh", 0)
            val speedLimit = intent.getIntExtra("speed_limit", 60)
            val zoneName = intent.getStringExtra("zone_name") ?: "Đô thị tiêu chuẩn"
            val lat = intent.getDoubleExtra("lat", 0.0)
            val lon = intent.getDoubleExtra("lon", 0.0)
            val bearing = intent.getFloatExtra("bearing", 0f)
            val nearbyCount = intent.getIntExtra("nearby_count", 0)
            val hasAlert = intent.getBooleanExtra("has_alert", false)

            tvLiveSpeed.text = "$speedKmh km/h"
            if (speedKmh > speedLimit + prefs.getInt(FloatingBubbleService.KEY_OVERSPEED_THRESHOLD, 3)) {
                tvLiveSpeed.setTextColor(0xFFEF4444.toInt())
            } else {
                tvLiveSpeed.setTextColor(0xFF38BDF8.toInt())
            }
            tvLiveZoneName.text = zoneName
            tvLiveCoords.text = String.format(
                "GPS: %.5f, %.5f • Hướng: %.0f° • Trạm quanh xe: %d",
                lat, lon, bearing, nearbyCount
            )

            val validSign = if (speedLimit in listOf(30, 40, 50, 60, 70, 80, 90, 100, 120)) speedLimit else 60
            try {
                assets.open("speed/${validSign}.png").use { stream ->
                    ivLiveLimitSign.setImageBitmap(BitmapFactory.decodeStream(stream))
                }
            } catch (_: Exception) {
            }

            if (hasAlert) {
                val alertName = intent.getStringExtra("alert_name") ?: ""
                val alertDist = intent.getIntExtra("alert_dist_m", 0)
                tvLiveUpcomingAlert.text = "⚠️ Cách ${alertDist}m: $alertName"
                tvLiveUpcomingAlert.setTextColor(0xFFFBBF24.toInt())
            } else {
                tvLiveUpcomingAlert.text = "✅ Phía trước thông thoáng (Bong bóng tự thu gọn 2 vòng tròn)"
                tvLiveUpcomingAlert.setTextColor(0xFF34D399.toInt())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(FloatingBubbleService.PREFS_NAME, Context.MODE_PRIVATE)
        bindViews()
        setupListeners()
        handleAutomationIntent(intent)
        syncUIFromPrefs()
        updatePermissionAndServiceStatus()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutomationIntent(intent)
        syncUIFromPrefs()
        updatePermissionAndServiceStatus()
    }

    private fun handleAutomationIntent(intent: Intent?) {
        if (intent == null) return
        val editor = prefs.edit()
        var changed = false

        if (intent.hasExtra("is_sim")) {
            editor.putBoolean(FloatingBubbleService.KEY_IS_SIMULATION, intent.getBooleanExtra("is_sim", true))
            changed = true
        }
        if (intent.hasExtra("sim_speed")) {
            editor.putInt(FloatingBubbleService.KEY_SIM_SPEED_OVERRIDE, intent.getIntExtra("sim_speed", 0))
            changed = true
        }
        if (intent.hasExtra("bubble_scale")) {
            editor.putInt(FloatingBubbleService.KEY_BUBBLE_SCALE, intent.getIntExtra("bubble_scale", 100))
            changed = true
        }
        if (intent.hasExtra("idle_alpha")) {
            editor.putInt(FloatingBubbleService.KEY_IDLE_ALPHA, intent.getIntExtra("idle_alpha", 78))
            changed = true
        }
        if (intent.hasExtra("ultra_mini")) {
            editor.putBoolean(FloatingBubbleService.KEY_ULTRA_MINI, intent.getBooleanExtra("ultra_mini", false))
            changed = true
        }
        if (intent.hasExtra("bubble_x") && intent.hasExtra("bubble_y")) {
            editor.putInt(FloatingBubbleService.KEY_BUBBLE_X, intent.getIntExtra("bubble_x", 16))
            editor.putInt(FloatingBubbleService.KEY_BUBBLE_Y, intent.getIntExtra("bubble_y", 105))
            changed = true
        }
        if (changed) {
            editor.apply()
        }

        if (intent.getBooleanExtra("auto_start_bubble", false) && hasOverlayPermission()) {
            val svcIntent = Intent(this, FloatingBubbleService::class.java).apply {
                action = FloatingBubbleService.ACTION_START_BUBBLE
                putExtras(intent)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
            btnToggleBubble.postDelayed({ updatePermissionAndServiceStatus() }, 300L)
        } else if ((changed || intent.hasExtra("sim_index")) && FloatingBubbleService.isServiceRunning) {
            val svcIntent = Intent(this, FloatingBubbleService::class.java).apply {
                action = FloatingBubbleService.ACTION_RELOAD_SETTINGS
                putExtras(intent)
            }
            startService(svcIntent)
        }
    }

    private fun bindViews() {
        tvStatusBanner = findViewById(R.id.tvStatusBanner)
        btnToggleBubble = findViewById(R.id.btnToggleBubble)
        btnGrantPermissions = findViewById(R.id.btnGrantPermissions)
        rgLocationMode = findViewById(R.id.rgLocationMode)
        rbRealGps = findViewById(R.id.rbRealGps)
        rbSimRoute = findViewById(R.id.rbSimRoute)

        tvLiveSpeed = findViewById(R.id.tvLiveSpeed)
        ivLiveLimitSign = findViewById(R.id.ivLiveLimitSign)
        tvLiveZoneName = findViewById(R.id.tvLiveZoneName)
        tvLiveUpcomingAlert = findViewById(R.id.tvLiveUpcomingAlert)
        tvLiveCoords = findViewById(R.id.tvLiveCoords)

        sbBubbleScale = findViewById(R.id.sbBubbleScale)
        tvBubbleScaleLabel = findViewById(R.id.tvBubbleScaleLabel)
        sbIdleAlpha = findViewById(R.id.sbIdleAlpha)
        tvIdleAlphaLabel = findViewById(R.id.tvIdleAlphaLabel)
        swUltraMiniMode = findViewById(R.id.swUltraMiniMode)

        sbSimSpeedOverride = findViewById(R.id.sbSimSpeedOverride)
        tvSimSpeedOverrideLabel = findViewById(R.id.tvSimSpeedOverrideLabel)
        sbOverspeedThreshold = findViewById(R.id.sbOverspeedThreshold)
        tvOverspeedThresholdLabel = findViewById(R.id.tvOverspeedThresholdLabel)
        swVoiceAlert = findViewById(R.id.swVoiceAlert)
        swAutoBoot = findViewById(R.id.swAutoBoot)
        btnResetSimRoute = findViewById(R.id.btnResetSimRoute)

        try {
            assets.open("speed/60.png").use { stream ->
                ivLiveLimitSign.setImageBitmap(BitmapFactory.decodeStream(stream))
            }
        } catch (_: Exception) {
        }
    }

    private fun setupListeners() {
        btnGrantPermissions.setOnClickListener {
            requestAllNeededPermissions()
        }

        btnToggleBubble.setOnClickListener {
            if (!hasOverlayPermission() || !hasLocationPermission()) {
                requestAllNeededPermissions()
                return@setOnClickListener
            }

            if (FloatingBubbleService.isServiceRunning) {
                val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
                    action = FloatingBubbleService.ACTION_STOP_BUBBLE
                }
                startService(stopIntent)
                btnToggleBubble.postDelayed({ updatePermissionAndServiceStatus() }, 250L)
            } else {
                startBubbleService()
                btnToggleBubble.postDelayed({ updatePermissionAndServiceStatus() }, 250L)
            }
        }

        sbBubbleScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val scalePct = 70 + progress // 70%..130%
                tvBubbleScaleLabel.text = "Kích thước Bong bóng: $scalePct% (Gọn gàng trên màn hình xe)"
                if (fromUser) {
                    prefs.edit().putInt(FloatingBubbleService.KEY_BUBBLE_SCALE, scalePct).apply()
                    notifyServiceReloadSettings()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbIdleAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val alphaPct = 50 + progress // 50%..100%
                tvIdleAlphaLabel.text = "Độ rõ nét khi đường thông thoáng (Tự làm mờ): $alphaPct%"
                if (fromUser) {
                    prefs.edit().putInt(FloatingBubbleService.KEY_IDLE_ALPHA, alphaPct).apply()
                    notifyServiceReloadSettings()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        swUltraMiniMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(FloatingBubbleService.KEY_ULTRA_MINI, isChecked).apply()
            notifyServiceReloadSettings()
        }

        rgLocationMode.setOnCheckedChangeListener { _, checkedId ->
            val isSim = (checkedId == R.id.rbSimRoute)
            prefs.edit().putBoolean(FloatingBubbleService.KEY_IS_SIMULATION, isSim).apply()
            notifyServiceReloadSettings()
        }

        sbSimSpeedOverride.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress == 0) {
                    tvSimSpeedOverrideLabel.text = "Tốc độ xe mô phỏng: Tự động theo tuyến đường"
                } else {
                    tvSimSpeedOverrideLabel.text = "Tốc độ xe mô phỏng (Ép thủ công để test báo đỏ): $progress km/h"
                }
                if (fromUser) {
                    prefs.edit().putInt(FloatingBubbleService.KEY_SIM_SPEED_OVERRIDE, progress).apply()
                    notifyServiceReloadSettings()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbOverspeedThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvOverspeedThresholdLabel.text = "Ngưỡng báo động vượt tốc độ: +$progress km/h"
                if (fromUser) {
                    prefs.edit().putInt(FloatingBubbleService.KEY_OVERSPEED_THRESHOLD, progress).apply()
                    notifyServiceReloadSettings()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        swVoiceAlert.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(FloatingBubbleService.KEY_VOICE_ENABLED, isChecked).apply()
            notifyServiceReloadSettings()
        }

        swAutoBoot.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(FloatingBubbleService.KEY_AUTO_START_BOOT, isChecked).apply()
        }

        btnResetSimRoute.setOnClickListener {
            if (FloatingBubbleService.isServiceRunning) {
                val intent = Intent(this, FloatingBubbleService::class.java).apply {
                    action = FloatingBubbleService.ACTION_RESET_SIMULATION
                }
                startService(intent)
                Toast.makeText(this, "Đã đưa xe về điểm xuất phát (0 km)!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun syncUIFromPrefs() {
        val isSim = prefs.getBoolean(FloatingBubbleService.KEY_IS_SIMULATION, false)
        if (isSim) rbSimRoute.isChecked = true else rbRealGps.isChecked = true

        val scalePct = prefs.getInt(FloatingBubbleService.KEY_BUBBLE_SCALE, 100).coerceIn(70, 130)
        sbBubbleScale.progress = scalePct - 70
        tvBubbleScaleLabel.text = "Kích thước Bong bóng: $scalePct% (Gọn gàng trên màn hình xe)"

        val alphaPct = prefs.getInt(FloatingBubbleService.KEY_IDLE_ALPHA, 78).coerceIn(50, 100)
        sbIdleAlpha.progress = alphaPct - 50
        tvIdleAlphaLabel.text = "Độ rõ nét khi đường thông thoáng (Tự làm mờ): $alphaPct%"

        swUltraMiniMode.isChecked = prefs.getBoolean(FloatingBubbleService.KEY_ULTRA_MINI, false)

        val simOverride = prefs.getInt(FloatingBubbleService.KEY_SIM_SPEED_OVERRIDE, 0)
        sbSimSpeedOverride.progress = simOverride

        val threshold = prefs.getInt(FloatingBubbleService.KEY_OVERSPEED_THRESHOLD, 3)
        sbOverspeedThreshold.progress = threshold

        swVoiceAlert.isChecked = prefs.getBoolean(FloatingBubbleService.KEY_VOICE_ENABLED, true)
        swAutoBoot.isChecked = prefs.getBoolean(FloatingBubbleService.KEY_AUTO_START_BOOT, true)
    }

    private fun startBubbleService() {
        val serviceIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = FloatingBubbleService.ACTION_START_BUBBLE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(
            this,
            "Đã bật Bong bóng Siêu Gọn v2.0! Bạn có thể mở Google Maps ngay bây giờ.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun notifyServiceReloadSettings() {
        if (FloatingBubbleService.isServiceRunning) {
            val intent = Intent(this, FloatingBubbleService::class.java).apply {
                action = FloatingBubbleService.ACTION_RELOAD_SETTINGS
            }
            startService(intent)
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAllNeededPermissions() {
        if (!hasLocationPermission()) {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQ_LOCATION_PERM)
            return
        }

        if (!hasOverlayPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Toast.makeText(
                this,
                "Vui lòng bật quyền 'Hiển thị trên ứng dụng khác' cho Vietmap Car Bubble",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_OVERLAY_PERM)
        }
    }

    private fun updatePermissionAndServiceStatus() {
        val overlayOk = hasOverlayPermission()
        val gpsOk = hasLocationPermission()

        if (!overlayOk || !gpsOk) {
            btnGrantPermissions.visibility = android.view.View.VISIBLE
            tvStatusBanner.text = "⚠️ Cần cấp quyền: ${if (!gpsOk) "[Vị trí GPS] " else ""}${if (!overlayOk) "[Vẽ bong bóng nổi]" else ""}"
            tvStatusBanner.setTextColor(0xFFFBBF24.toInt())
        } else {
            btnGrantPermissions.visibility = android.view.View.GONE
            if (FloatingBubbleService.isServiceRunning) {
                tvStatusBanner.text = "🟢 MINI HUD v2.0 ĐANG CHẠY • CSDL OFFLINE: 69,965 ĐIỂM TOÀN QUỐC"
                tvStatusBanner.setTextColor(0xFF34D399.toInt())
                btnToggleBubble.text = "⏹ TẮT BONG BÓNG TỐC ĐỘ"
                btnToggleBubble.setBackgroundColor(0xFFDC2626.toInt())
            } else {
                tvStatusBanner.text = "⚪ ĐÃ SẴN SÀNG • CSDL OFFLINE: 69,965 ĐIỂM TOÀN QUỐC"
                tvStatusBanner.setTextColor(0xFF94A3B8.toInt())
                btnToggleBubble.text = "🚀 BẬT BONG BÓNG TỐC ĐỘ NỔI"
                btnToggleBubble.setBackgroundColor(0xFF0284C7.toInt())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionAndServiceStatus()
        val filter = IntentFilter(FloatingBubbleService.ACTION_STATE_BROADCAST)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionAndServiceStatus()
        if (hasLocationPermission() && !hasOverlayPermission()) {
            requestAllNeededPermissions()
        }
    }
}
