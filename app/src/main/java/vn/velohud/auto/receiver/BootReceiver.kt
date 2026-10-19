package vn.velohud.auto.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import vn.velohud.auto.service.FloatingBubbleService

/**
 * Tự động khởi chạy Bong bóng tốc độ VeloHUD ở chế độ GPS Thực Tế khi bật khoá điện khởi động Android Box ô tô.
 * Hỗ trợ đầy đủ BOOT_COMPLETED, LOCKED_BOOT_COMPLETED (DirectBoot), QUICKBOOT_POWERON và MY_PACKAGE_REPLACED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == FloatingBubbleService.ACTION_DEBUG_INJECT_GPS) {
            val svcIntent = Intent(context, FloatingBubbleService::class.java).apply {
                this.action = FloatingBubbleService.ACTION_DEBUG_INJECT_GPS
                putExtras(intent)
            }
            try {
                context.startService(svcIntent)
            } catch (_: Exception) {
            }
            return
        }
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            val storageCtx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && context.isDeviceProtectedStorage) {
                context
            } else {
                context
            }
            val prefs = storageCtx.getSharedPreferences(FloatingBubbleService.PREFS_NAME, Context.MODE_PRIVATE)
            val autoBoot = prefs.getBoolean(FloatingBubbleService.KEY_AUTO_START_BOOT, true)

            // Luôn đảm bảo khi khởi động xe là chạy chế độ GPS Thực Tế (không chạy giả lập Debug)
            prefs.edit().putBoolean(FloatingBubbleService.KEY_IS_SIMULATION, false).apply()

            val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }

            if (autoBoot && hasOverlayPermission) {
                Log.i("VeloHudTelemetry", "AUTO_BOOT_TRIGGERED: action=$action -> Starting FloatingBubbleService (Real GPS)")
                val serviceIntent = Intent(context, FloatingBubbleService::class.java).apply {
                    this.action = FloatingBubbleService.ACTION_START_BUBBLE
                    putExtra("is_sim", false)
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e("VeloHudBootReceiver", "Không thể khởi chạy dịch vụ trực tiếp từ nền: ${e.message}")
                }
            }
        }
    }
}
