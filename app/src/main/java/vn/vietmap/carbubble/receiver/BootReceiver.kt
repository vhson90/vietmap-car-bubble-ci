package vn.vietmap.carbubble.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import vn.vietmap.carbubble.service.FloatingBubbleService

/**
 * Tự động khởi chạy Bong bóng tốc độ khi bật khoá điện khởi động Android Box ô tô
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs = context.getSharedPreferences(FloatingBubbleService.PREFS_NAME, Context.MODE_PRIVATE)
            val autoBoot = prefs.getBoolean(FloatingBubbleService.KEY_AUTO_START_BOOT, true)

            val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }

            if (autoBoot && hasOverlayPermission) {
                Log.i("BootReceiver", "Khởi động tự động VietmapCarBubble khi nổ máy xe...")
                val serviceIntent = Intent(context, FloatingBubbleService::class.java).apply {
                    this.action = FloatingBubbleService.ACTION_START_BUBBLE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
