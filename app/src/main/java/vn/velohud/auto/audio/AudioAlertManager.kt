package vn.velohud.auto.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.util.Log
import vn.velohud.auto.engine.EdogPoint
import kotlin.math.*

/**
 * Bộ quản lý âm thanh cảnh báo Tiếng Việt VeloHUD v3.0 (Studio MP3 Offline + Chuông Ting + markRead 1-Shot):
 * - Thay thế hoàn toàn sự phụ thuộc vào Google TTS bằng bộ 38 file âm thanh MP3 giọng người thật thu âm phòng thu
 *   (`assets/sounds/*.mp3`) phát trực tiếp qua `MediaPlayer`, đảm bảo 100% đầu Android Box ô tô phát giọng nói Tiếng Việt
 *   rõ nét ngay cả khi không có mạng và không cài gói TTS hệ thống.
 * - Hỗ trợ 3 chế độ âm thanh chuẩn thực tế:
 *   + `SOUND_MODE_FULL (0)`: Giọng nói Phòng thu MP3 + Chuông Ting (`only_ding.mp3` / `over_speed_ding.mp3`)
 *   + `SOUND_MODE_DING (1)`: Chỉ phát tiếng Chuông Ting nhẹ nhàng
 *   + `SOUND_MODE_MUTE (2)`: Tắt âm hoàn toàn
 * - Cơ chế `markRead(id)` + Khử trùng lặp cụm không gian 160m: Đảm bảo MỖI BIỂN BÁO / CAMERA CHỈ ĐỌC ĐÚNG 1 LẦN DUY NHẤT.
 */
class AudioAlertManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioAlertManager"
        private const val TELEMETRY_TAG = "VeloHudTelemetry"

        const val SOUND_MODE_FULL = 0
        const val SOUND_MODE_DING = 1
        const val SOUND_MODE_MUTE = 2

        private const val OVERSPEED_VOICE_COOLDOWN_MS = 15_000L // Nhắc giọng nói quá tốc độ tối đa mỗi 15 giây
        private const val OVERSPEED_BEEP_COOLDOWN_MS = 4_500L   // Tiếng chuông quá tốc độ mỗi 4.5 giây
        private const val UPCOMING_VOICE_COOLDOWN_MS = 4_200L   // Khoảng nghỉ tối thiểu giữa 2 câu báo biển khác loại
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

    private var mediaPlayer: MediaPlayer? = null
    private val clipQueue = ArrayDeque<String>()
    private var isPlayingQueue = false
    private var toneGenerator: ToneGenerator? = null

    var isVoiceEnabled: Boolean = true
    var soundMode: Int = SOUND_MODE_FULL

    // Bộ nhớ markRead(id) chống báo lặp
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
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
        } catch (e: Exception) {
            Log.e(TAG, "Không thể khởi tạo ToneGenerator dự phòng: ${e.message}")
        }
    }

    fun resetHistory() {
        announcedPointIds.clear()
        announcedClusters.clear()
        lastAnnouncedUpcomingLimit = -1
        stopCurrentPlayback()
    }

    /**
     * Phát âm báo ĐÚNG 1 LẦN DUY NHẤT (`markRead`) khi phát hiện Biển báo / Camera mới ở phía trước
     */
    fun checkAndAnnounceUpcomingAlert(alert: EdogPoint?, maxLookaheadM: Double = 350.0) {
        if (!isVoiceEnabled || soundMode == SOUND_MODE_MUTE || alert == null) return
        if (alert.distanceMeters !in 25.0..maxLookaheadM) return

        // Dọn dẹp các điểm trong lịch sử mà xe đã bỏ lại phía sau quá 2.0 km
        val iter = announcedClusters.iterator()
        while (iter.hasNext()) {
            val old = iter.next()
            if (haversineMeters(alert.lat, alert.lon, old.lat, old.lon) > HISTORY_RETENTION_DIST_M) {
                announcedPointIds.remove(old.id)
                iter.remove()
            }
        }

        // 1. Nếu ID điểm này đã được markRead -> Tuyệt đối không đọc lại!
        if (alert.id in announcedPointIds) return

        // 2. Kiểm tra khử trùng lặp cụm không gian (160m):
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
        if (now - lastUpcomingVoiceTimeMs < UPCOMING_VOICE_COOLDOWN_MS) return

        // Đánh dấu markRead(alert.id)
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

        if (soundMode == SOUND_MODE_DING) {
            playMp3Sequence(listOf("sounds/only_ding.mp3"), reason = "UPCOMING_DING_ID_${alert.id}")
        } else {
            val clips = mutableListOf("sounds/only_ding.mp3")
            clips.addAll(alert.getVoiceMp3AssetPaths())
            playMp3Sequence(clips, reason = "UPCOMING_VOICE_ID_${alert.id}")
        }
    }

    /**
     * Phát âm báo khi tốc độ giới hạn của đoạn đường vừa thay đổi:
     * Nếu biển tốc độ này đã được đọc báo trước đó (trong vòng 45 giây), chỉ phát 1 tiếng chuông nhẹ xác nhận để không đọc 2 lần.
     */
    fun checkAndAnnounceSpeedLimitChange(newLimit: Int) {
        if (!isVoiceEnabled || soundMode == SOUND_MODE_MUTE) {
            lastAnnouncedSpeedLimit = newLimit
            return
        }
        if (lastAnnouncedSpeedLimit != -1 && lastAnnouncedSpeedLimit != newLimit) {
            val now = System.currentTimeMillis()
            val alreadyAnnouncedAhead = (newLimit == lastAnnouncedUpcomingLimit) &&
                    (now - lastAnnouncedUpcomingLimitTimeMs <= 45_000L)

            val validSpeed = if (newLimit in listOf(30, 40, 50, 60, 70, 80, 90, 100, 120)) newLimit else 0
            if (soundMode == SOUND_MODE_DING || alreadyAnnouncedAhead || validSpeed == 0) {
                playMp3Sequence(listOf("sounds/only_ding.mp3"), reason = "LIMIT_CHANGE_DING_$newLimit")
            } else if (now - lastUpcomingVoiceTimeMs >= 3_500L) {
                lastUpcomingVoiceTimeMs = now
                playMp3Sequence(
                    listOf("sounds/only_ding.mp3", "sounds/current_speed_${validSpeed}.mp3"),
                    reason = "LIMIT_CHANGE_VOICE_$newLimit"
                )
            }
        }
        lastAnnouncedSpeedLimit = newLimit
    }

    /**
     * Cảnh báo khi xe chạy vượt quá tốc độ giới hạn cho phép bằng file MP3 `over_speed_ding.mp3` & `over_speed_limit.mp3`
     */
    fun checkAndAnnounceOverspeed(currentSpeedKmh: Int, speedLimitKmh: Int, thresholdKmh: Int) {
        if (!isVoiceEnabled || soundMode == SOUND_MODE_MUTE || speedLimitKmh <= 0) return

        if (currentSpeedKmh > speedLimitKmh + thresholdKmh) {
            val now = System.currentTimeMillis()
            if (soundMode == SOUND_MODE_FULL &&
                now - lastOverspeedVoiceTime >= OVERSPEED_VOICE_COOLDOWN_MS &&
                now - lastUpcomingVoiceTimeMs >= 4_000L
            ) {
                lastOverspeedVoiceTime = now
                lastOverspeedBeepTime = now
                playMp3Sequence(
                    listOf("sounds/over_speed_ding.mp3", "sounds/over_speed_limit.mp3"),
                    reason = "OVERSPEED_VOICE_${currentSpeedKmh}_OVER_${speedLimitKmh}"
                )
            } else if (now - lastOverspeedBeepTime >= OVERSPEED_BEEP_COOLDOWN_MS && !isPlayingQueue) {
                lastOverspeedBeepTime = now
                playMp3Sequence(
                    listOf("sounds/over_speed_ding.mp3"),
                    reason = "OVERSPEED_DING_${currentSpeedKmh}_OVER_${speedLimitKmh}"
                )
            }
        }
    }

    /**
     * Phát tuần tự danh sách file MP3 phòng thu từ `assets/sounds/` (Flush hàng đợi cũ để không bao giờ đọc chồng chéo)
     */
    @Synchronized
    private fun playMp3Sequence(assetPaths: List<String>, reason: String) {
        stopCurrentPlayback()
        clipQueue.addAll(assetPaths)
        Log.i(TELEMETRY_TAG, "STUDIO_MP3_PLAYED: reason=$reason clips=${assetPaths.joinToString(",")}")
        playNextInQueue()
    }

    @Synchronized
    private fun playNextInQueue() {
        val nextAsset = clipQueue.removeFirstOrNull()
        if (nextAsset == null) {
            isPlayingQueue = false
            return
        }
        isPlayingQueue = true
        try {
            val afd = context.assets.openFd(nextAsset)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setOnCompletionListener { player ->
                try {
                    player.release()
                } catch (_: Exception) {
                }
                if (mediaPlayer === player) {
                    mediaPlayer = null
                }
                playNextInQueue()
            }
            mp.setOnErrorListener { player, _, _ ->
                try {
                    player.release()
                } catch (_: Exception) {
                }
                if (mediaPlayer === player) {
                    mediaPlayer = null
                }
                playFallbackTone()
                playNextInQueue()
                true
            }
            mp.prepare()
            mp.start()
            mediaPlayer = mp
        } catch (e: Exception) {
            Log.w(TAG, "Không thể phát file MP3 $nextAsset, dùng ToneGenerator dự phòng: ${e.message}")
            playFallbackTone()
            playNextInQueue()
        }
    }

    @Synchronized
    private fun stopCurrentPlayback() {
        clipQueue.clear()
        isPlayingQueue = false
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.stop()
                }
                mp.release()
            }
        } catch (_: Exception) {
        } finally {
            mediaPlayer = null
        }
    }

    private fun playFallbackTone() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        } catch (_: Exception) {
        }
    }

    private fun isSameAlertCategory(typeA: Int, limitA: Int, typeB: Int, limitB: Int): Boolean {
        if (typeA == typeB) {
            return typeA != 1 || limitA == limitB
        }
        val camTypes = setOf(4, 9, 12)
        return (typeA in camTypes && typeB in camTypes)
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
        stopCurrentPlayback()
        try {
            toneGenerator?.release()
        } catch (_: Exception) {
        }
    }
}
