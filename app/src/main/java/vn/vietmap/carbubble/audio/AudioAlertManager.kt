package vn.vietmap.carbubble.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import vn.vietmap.carbubble.engine.EdogPoint
import java.util.Locale

/**
 * Bộ quản lý âm thanh cảnh báo Tiếng Việt (Text-To-Speech + Bíp cảnh báo quá tốc độ)
 * Tối ưu hóa cho Android Car Box (phát trên luồng STREAM_MUSIC / STREAM_NOTIFICATION để không cắt nhạc đột ngột).
 */
class AudioAlertManager(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "AudioAlertManager"
        private const val OVERSPEED_VOICE_COOLDOWN_MS = 14_000L // Nhắc giọng nói quá tốc độ mỗi 14 giây
        private const val OVERSPEED_BEEP_COOLDOWN_MS = 3_500L   // Tiếng bíp cảnh báo mỗi 3.5 giây
    }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var toneGenerator: ToneGenerator? = null

    var isVoiceEnabled: Boolean = true

    private var lastAnnouncedPointId: Int = -1
    private var lastOverspeedVoiceTime: Long = 0L
    private var lastOverspeedBeepTime: Long = 0L
    private var lastAnnouncedSpeedLimit: Int = -1

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
        } catch (e: Exception) {
            Log.e(TAG, "Không thể khởi tạo AudioAlertManager: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val viLocale = Locale("vi", "VN")
            val res = tts?.setLanguage(viLocale)
            isTtsReady = (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED)
            tts?.setSpeechRate(1.08f)
            Log.i(TAG, "Khởi tạo TTS Tiếng Việt thành công: isTtsReady=$isTtsReady")
        } else {
            Log.w(TAG, "TTS không khả dụng trên thiết bị này, sử dụng ToneGenerator thay thế.")
        }
    }

    /**
     * Phát âm báo khi phát hiện Biển báo / Camera mới ở phía trước (trong tầm 350m)
     */
    fun checkAndAnnounceUpcomingAlert(alert: EdogPoint?) {
        if (!isVoiceEnabled || alert == null) return
        if (alert.distanceMeters > 350.0) return

        // Mỗi biển báo/camera chỉ đọc 1 lần khi xe tiến vào vùng < 350m
        if (alert.id != lastAnnouncedPointId) {
            lastAnnouncedPointId = alert.id
            playBeep(isWarning = (alert.type == 4))
            speak(alert.getVoiceText(alert.distanceMeters.toInt()))
        }
    }

    /**
     * Phát âm báo khi tốc độ giới hạn của đoạn đường vừa thay đổi
     */
    fun checkAndAnnounceSpeedLimitChange(newLimit: Int) {
        if (!isVoiceEnabled) return
        if (lastAnnouncedSpeedLimit != -1 && lastAnnouncedSpeedLimit != newLimit) {
            playBeep(isWarning = false)
            speak("Tốc độ giới hạn hiện tại ${newLimit} ki lô mét trên giờ")
        }
        lastAnnouncedSpeedLimit = newLimit
    }

    /**
     * Cảnh báo khi xe chạy vượt quá tốc độ giới hạn cho phép
     */
    fun checkAndAnnounceOverspeed(currentSpeedKmh: Int, speedLimitKmh: Int, thresholdKmh: Int) {
        if (!isVoiceEnabled || speedLimitKmh <= 0) return

        if (currentSpeedKmh > speedLimitKmh + thresholdKmh) {
            val now = System.currentTimeMillis()
            if (now - lastOverspeedBeepTime >= OVERSPEED_BEEP_COOLDOWN_MS) {
                lastOverspeedBeepTime = now
                playBeep(isWarning = true)
            }
            if (now - lastOverspeedVoiceTime >= OVERSPEED_VOICE_COOLDOWN_MS) {
                lastOverspeedVoiceTime = now
                speak("Chú ý! Bạn đang chạy quá tốc độ giới hạn ${speedLimitKmh} ki lô mét trên giờ")
            }
        }
    }

    private fun playBeep(isWarning: Boolean) {
        try {
            val tone = if (isWarning) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_ACK
            toneGenerator?.startTone(tone, if (isWarning) 280 else 160)
        } catch (_: Exception) {
        }
    }

    fun speak(text: String) {
        if (!isVoiceEnabled || !isTtsReady) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "vm_alert_${System.currentTimeMillis()}")
            } else {
                @Suppress("DEPRECATION")
                tts?.speak(text, TextToSpeech.QUEUE_ADD, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi phát giọng nói TTS: ${e.message}")
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            toneGenerator?.release()
        } catch (_: Exception) {
        }
    }
}
