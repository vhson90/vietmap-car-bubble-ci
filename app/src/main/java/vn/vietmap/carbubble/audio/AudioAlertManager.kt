package vn.vietmap.carbubble.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import vn.vietmap.carbubble.engine.EdogPoint
import java.util.Locale
import kotlin.math.*

/**
 * Bộ quản lý âm thanh cảnh báo Tiếng Việt v2.2 (Text-To-Speech + Bíp cảnh báo quá tốc độ):
 * - Đảm bảo MỖI BIỂN BÁO / CAMERA CHỈ ĐỌC ĐÚNG 1 LẦN DUY NHẤT khi xe tiến lại gần:
 *   + Bộ nhớ lịch sử `announcedPointIds` chặn đọc lại khi `bestUpcoming` đảo qua lại giữa 2 điểm gần nhau.
 *   + Bộ lọc cụm không gian `announcedClusters` (bán kính 160m) chặn đọc lặp các bản ghi trùng lặp tại cùng 1 giao lộ.
 *   + Sử dụng `TextToSpeech.QUEUE_FLUSH` thay vì `QUEUE_ADD` để không bao giờ xếp hàng đợi đọc chồng chéo liên tục.
 *   + Khi đi qua biển tốc độ đã được báo trước đó, chỉ phát 1 tiếng bíp nhẹ xác nhận, không đọc lặp lại câu thứ 2.
 */
class AudioAlertManager(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "AudioAlertManager"
        private const val OVERSPEED_VOICE_COOLDOWN_MS = 15_000L // Nhắc giọng nói quá tốc độ tối đa mỗi 15 giây
        private const val OVERSPEED_BEEP_COOLDOWN_MS = 4_000L   // Tiếng bíp cảnh báo quá tốc độ mỗi 4 giây
        private const val UPCOMING_VOICE_COOLDOWN_MS = 4_500L   // Khoảng nghỉ tối thiểu giữa 2 câu báo biển khác loại
        private const val CLUSTER_DEDUP_RADIUS_M = 160.0        // Không đọc lại biển/camera cùng loại trong bán kính 160m
        private const val HISTORY_RETENTION_DIST_M = 2000.0     // Giữ lịch sử điểm đã báo cho tới khi xe đi xa > 2km
    }

    private data class AnnouncedCluster(
        val id: Int,
        val lat: Double,
        val lon: Double,
        val type: Int,
        val speedLimit: Int,
        val timestampMs: Long
    )

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var toneGenerator: ToneGenerator? = null

    var isVoiceEnabled: Boolean = true

    private val announcedPointIds = HashSet<Int>()
    private val announcedClusters = ArrayList<AnnouncedCluster>()

    private var lastUpcomingVoiceTimeMs: Long = 0L
    private var lastAnnouncedUpcomingLimit: Int = -1
    private var lastAnnouncedUpcomingLimitTimeMs: Long = 0L

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
            Log.i(TAG, "Khởi tạo TTS Tiếng Việt v2.2 thành công: isTtsReady=$isTtsReady")
        } else {
            Log.w(TAG, "TTS không khả dụng trên thiết bị này, sử dụng ToneGenerator thay thế.")
        }
    }

    fun resetHistory() {
        announcedPointIds.clear()
        announcedClusters.clear()
        lastAnnouncedUpcomingLimit = -1
    }

    /**
     * Phát âm báo ĐÚNG 1 LẦN DUY NHẤT khi phát hiện Biển báo / Camera mới ở phía trước (trong tầm 35m..350m)
     */
    fun checkAndAnnounceUpcomingAlert(alert: EdogPoint?) {
        if (!isVoiceEnabled || alert == null) return
        if (alert.distanceMeters !in 30.0..350.0) return

        // Dọn dẹp các điểm trong lịch sử mà xe đã bỏ lại phía sau quá 2.0 km
        val iter = announcedClusters.iterator()
        while (iter.hasNext()) {
            val old = iter.next()
            if (haversineMeters(alert.lat, alert.lon, old.lat, old.lon) > HISTORY_RETENTION_DIST_M) {
                announcedPointIds.remove(old.id)
                iter.remove()
            }
        }

        // 1. Nếu ID điểm này đã từng được đọc -> Tuyệt đối không đọc lại!
        if (alert.id in announcedPointIds) return

        // 2. Kiểm tra khử trùng lặp cụm không gian (160m):
        // Nếu trong bán kính 160m quanh điểm này đã từng đọc 1 biển/camera cùng loại -> Đánh dấu đã đọc & Bỏ qua!
        for (cluster in announcedClusters) {
            if (isSameAlertCategory(alert.type, alert.speedLimit, cluster.type, cluster.speedLimit)) {
                val distToCluster = haversineMeters(alert.lat, alert.lon, cluster.lat, cluster.lon)
                if (distToCluster <= CLUSTER_DEDUP_RADIUS_M) {
                    announcedPointIds.add(alert.id)
                    return
                }
            }
        }

        val now = System.currentTimeMillis()
        // Nếu vừa mới đọc 1 biển báo khác cách đây chưa tới 4.5 giây, đợi nhịp tiếp theo để không ngắt lời câu trước
        if (now - lastUpcomingVoiceTimeMs < UPCOMING_VOICE_COOLDOWN_MS) return

        // Ghi nhận điểm này vào lịch sử ĐÃ ĐỌC 1 LẦN
        announcedPointIds.add(alert.id)
        announcedClusters.add(
            AnnouncedCluster(
                id = alert.id,
                lat = alert.lat,
                lon = alert.lon,
                type = alert.type,
                speedLimit = alert.speedLimit,
                timestampMs = now
            )
        )
        lastUpcomingVoiceTimeMs = now

        if (alert.speedLimit >= 30 && (alert.type == 1 || alert.type == 4)) {
            lastAnnouncedUpcomingLimit = alert.speedLimit
            lastAnnouncedUpcomingLimitTimeMs = now
        }

        playBeep(isWarning = (alert.type == 4))
        speakFlush(alert.getVoiceText(alert.distanceMeters.toInt()))
    }

    /**
     * Phát âm báo khi tốc độ giới hạn của đoạn đường vừa thay đổi:
     * Nếu biển tốc độ này đã được đọc báo trước đó (trong vòng 45 giây), chỉ phát 1 tiếng bíp nhẹ xác nhận để không đọc 2 lần.
     */
    fun checkAndAnnounceSpeedLimitChange(newLimit: Int) {
        if (!isVoiceEnabled) return
        if (lastAnnouncedSpeedLimit != -1 && lastAnnouncedSpeedLimit != newLimit) {
            val now = System.currentTimeMillis()
            val alreadyAnnouncedAhead = (newLimit == lastAnnouncedUpcomingLimit) &&
                    (now - lastAnnouncedUpcomingLimitTimeMs <= 45_000L)

            playBeep(isWarning = false)
            if (!alreadyAnnouncedAhead && now - lastUpcomingVoiceTimeMs >= 3_500L) {
                lastUpcomingVoiceTimeMs = now
                speakFlush("Tốc độ giới hạn hiện tại ${newLimit} ki lô mét trên giờ")
            }
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
            if (now - lastOverspeedVoiceTime >= OVERSPEED_VOICE_COOLDOWN_MS &&
                now - lastUpcomingVoiceTimeMs >= 4_000L
            ) {
                lastOverspeedVoiceTime = now
                speakFlush("Chú ý! Bạn đang chạy quá tốc độ giới hạn ${speedLimitKmh} ki lô mét trên giờ")
            }
        }
    }

    private fun isSameAlertCategory(typeA: Int, limitA: Int, typeB: Int, limitB: Int): Boolean {
        if (typeA == typeB) {
            return typeA != 1 || limitA == limitB
        }
        val camTypes = setOf(4, 9, 12)
        return (typeA in camTypes && typeB in camTypes)
    }

    private fun playBeep(isWarning: Boolean) {
        try {
            val tone = if (isWarning) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_ACK
            toneGenerator?.startTone(tone, if (isWarning) 240 else 140)
        } catch (_: Exception) {
        }
    }

    private fun speakFlush(text: String) {
        if (!isVoiceEnabled || !isTtsReady) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Sử dụng QUEUE_FLUSH thay vì QUEUE_ADD để tuyệt đối không xếp hàng đọc lặp liên hồi
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vm_alert_${System.currentTimeMillis()}")
            } else {
                @Suppress("DEPRECATION")
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi phát giọng nói TTS: ${e.message}")
        }
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        return 2.0 * r * asin(sqrt(a))
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
