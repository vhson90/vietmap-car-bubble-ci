package vn.vietmap.carbubble.engine

import kotlin.math.*

/**
 * Đầu vào bản tin GPS thô (Tách biệt khỏi class android.location.Location để hỗ trợ kiểm thử JUnit 100% trên Cloud CI)
 */
data class RawGpsFix(
    val lat: Double,
    val lon: Double,
    val timeMs: Long,
    val elapsedRealtimeNanos: Long,
    val provider: String,
    val hasAccuracy: Boolean,
    val accuracyMeters: Float,
    val hasSpeed: Boolean,
    val speedMetersPerSecond: Float,
    val hasSpeedAccuracy: Boolean = false,
    val speedAccuracyMetersPerSecond: Float = 0f,
    val hasBearing: Boolean = false,
    val bearingDegrees: Float = 0f
)

/**
 * Kết quả tính toán vận tốc & hướng di chuyển theo chuẩn thuật toán Vietmap Live (v2.2)
 */
data class ComputedSpeedResult(
    val isAccepted: Boolean,
    val speedKmh: Int,
    val rawSpeedKmh: Float,
    val bearingDeg: Float,
    val sourceType: String // "DOPPLER", "GEODESIC_FALLBACK", "STATIONARY", "REJECTED_NOISE"
)

/**
 * Bộ tính toán vận tốc & góc hướng lái xe (v2.2) học hỏi từ vn.vietmap.lib_location_engine:
 * 1. Bộ lọc tọa độ tối ưu isBetterLocation (cửa sổ hết hạn ngắn 8 giây chống khóa cứng tọa độ khi đi vào đường rợp cây/nhà cao tầng).
 * 2. Điểm neo góc hướng lái (Bearing Anchor >= 3.2m) giúp cập nhật hướng xe mượt mà ngay cả khi GPS phát tần số cao 500ms (< 2.5m/nhịp).
 * 3. Kết hợp vận tốc sóng mang Doppler (location.speed) và vận tốc hình học Geodesic (Δs / Δt) dùng đồng hồ đơn điệu elapsedRealtimeNanos.
 * 4. Giới hạn gia tốc vật lý tối đa của ô tô (<= 8.5 m/s²) + Bộ lọc Kalman 1D + Watchdog 3.2s tự trả về 0 km/h khi dừng đèn đỏ.
 */
class VietmapSpeedCalculator {

    companion object {
        // Cửa sổ 8 giây: Sau 8 giây, bản tin mới luôn được ưu tiên dù độ chính xác thấp hơn bản tin cũ (Chống kẹt tọa độ 2 phút!)
        private const val STALE_FIX_EXPIRY_MS = 8_000L
        private const val MAX_ACCEPTABLE_ACCURACY_M = 65.0f
        private const val MAX_PHYSICAL_ACCEL_MS2 = 8.5 // Gia tốc ô tô tối đa ~30.6 km/h mỗi giây
        private const val ZERO_SPEED_DEADBAND_KMH = 2.4
        private const val WATCHDOG_TIMEOUT_NANOS = 3_200_000_000L // 3.2 giây
        private const val BEARING_ANCHOR_MIN_DIST_M = 3.2 // Quãng đường tích lũy tối thiểu để cập nhật hướng lái
    }

    private var currentBestFix: RawGpsFix? = null
    private var lastValidSpeedFix: RawGpsFix? = null
    private var filteredSpeedMs: Double = 0.0
    private var kalmanErrorCov: Double = 1.0
    private var lastValidBearing: Float = 0.0f
    private var hasMovingFix: Boolean = false

    // Điểm neo riêng biệt để tính góc hướng lái tích lũy (chống kẹt Bearing khi nhịp GPS 500ms < 2.5m)
    private var bearingAnchorLat: Double = 0.0
    private var bearingAnchorLon: Double = 0.0

    fun reset() {
        currentBestFix = null
        lastValidSpeedFix = null
        filteredSpeedMs = 0.0
        kalmanErrorCov = 1.0
        hasMovingFix = false
        bearingAnchorLat = 0.0
        bearingAnchorLon = 0.0
    }

    /**
     * Xử lý bản tin GPS mới và trả về tốc độ hiện tại (km/h) cùng góc hướng lái chính xác
     */
    fun processGpsFix(fix: RawGpsFix, gpsOffsetKmh: Int = 0): ComputedSpeedResult {
        // Bước 1: Kiểm tra chất lượng tọa độ
        if (!isBetterLocation(fix, currentBestFix)) {
            val currentKmh = applyOffsetAndDeadband(filteredSpeedMs * 3.6, gpsOffsetKmh)
            return ComputedSpeedResult(
                isAccepted = false,
                speedKmh = currentKmh,
                rawSpeedKmh = (filteredSpeedMs * 3.6).toFloat(),
                bearingDeg = lastValidBearing,
                sourceType = "REJECTED_NOISE"
            )
        }
        currentBestFix = fix

        val prevFix = lastValidSpeedFix
        var dtSec = 0.0
        var distMeters = 0.0

        if (prevFix != null) {
            dtSec = if (fix.elapsedRealtimeNanos > prevFix.elapsedRealtimeNanos && prevFix.elapsedRealtimeNanos > 0L) {
                (fix.elapsedRealtimeNanos - prevFix.elapsedRealtimeNanos) / 1_000_000_000.0
            } else {
                (fix.timeMs - prevFix.timeMs) / 1000.0
            }
            distMeters = haversineMeters(prevFix.lat, prevFix.lon, fix.lat, fix.lon)
        }

        // Bước 2: Cập nhật góc hướng lái (Bearing) với cơ chế Điểm Neo Tích Lũy (Bearing Anchor)
        if (bearingAnchorLat == 0.0 && bearingAnchorLon == 0.0) {
            bearingAnchorLat = fix.lat
            bearingAnchorLon = fix.lon
        }
        val distFromAnchor = haversineMeters(bearingAnchorLat, bearingAnchorLon, fix.lat, fix.lon)

        if (fix.hasBearing && fix.bearingDegrees > 0f && (!fix.hasSpeed || fix.speedMetersPerSecond >= 1.1f)) {
            lastValidBearing = fix.bearingDegrees
            if (distFromAnchor >= BEARING_ANCHOR_MIN_DIST_M) {
                bearingAnchorLat = fix.lat
                bearingAnchorLon = fix.lon
            }
        } else if (distFromAnchor >= BEARING_ANCHOR_MIN_DIST_M) {
            lastValidBearing = calculateBearing(bearingAnchorLat, bearingAnchorLon, fix.lat, fix.lon).toFloat()
            bearingAnchorLat = fix.lat
            bearingAnchorLon = fix.lon
        }

        // Bước 3: Xác định vận tốc thô (m/s) kết hợp Doppler & Geodesic Fallback (cho Box CarPlay khuyết speed)
        var candidateSpeedMs = 0.0
        var sourceType = "STATIONARY"

        val hasReliableDoppler = fix.hasSpeed &&
                fix.speedMetersPerSecond >= 0f &&
                (!fix.hasSpeedAccuracy || fix.speedAccuracyMetersPerSecond <= 6.5f)

        if (hasReliableDoppler && fix.speedMetersPerSecond > 0.3f) {
            candidateSpeedMs = fix.speedMetersPerSecond.toDouble()
            sourceType = "DOPPLER"

            if (prevFix != null && dtSec in 1.2..8.0) {
                val geoSpeedMs = distMeters / dtSec
                val accTolerance = if (fix.hasAccuracy) fix.accuracyMeters.toDouble() else 10.0
                if (distMeters < 1.2 && accTolerance > 12.0 && candidateSpeedMs < 2.2) {
                    candidateSpeedMs = 0.0
                    sourceType = "STATIONARY"
                } else if (candidateSpeedMs > geoSpeedMs * 3.2 + 8.0 && fix.hasAccuracy && fix.accuracyMeters > 20f) {
                    candidateSpeedMs = 0.4 * candidateSpeedMs + 0.6 * geoSpeedMs
                }
            }
        } else if (prevFix != null && dtSec in 0.15..12.0) {
            // Fallback tính từ quãng đường dịch chuyển Δs / Δt
            val noiseFloorMeters = if (fix.hasAccuracy) max(1.8, fix.accuracyMeters * 0.22) else 2.2
            if (distMeters >= noiseFloorMeters) {
                candidateSpeedMs = distMeters / dtSec
                sourceType = "GEODESIC_FALLBACK"
            } else {
                candidateSpeedMs = 0.0
                sourceType = "STATIONARY"
            }
        }

        // Bước 4 & 5: Khử gai gia tốc vật lý + Bộ lọc Kalman 1D
        if (candidateSpeedMs <= 0.65) {
            filteredSpeedMs = if (filteredSpeedMs < 1.2) 0.0 else filteredSpeedMs * 0.35
            if (filteredSpeedMs == 0.0) {
                hasMovingFix = false
            }
        } else if (!hasMovingFix) {
            filteredSpeedMs = candidateSpeedMs
            kalmanErrorCov = 1.0
            hasMovingFix = true
        } else {
            if (prevFix != null && dtSec in 0.05..10.0) {
                val maxDeltaV = MAX_PHYSICAL_ACCEL_MS2 * dtSec
                val deltaV = candidateSpeedMs - filteredSpeedMs
                if (deltaV > maxDeltaV) {
                    candidateSpeedMs = filteredSpeedMs + maxDeltaV
                }
            }

            val processNoiseQ = 0.45
            val measurementNoiseR = if (fix.hasSpeedAccuracy && fix.speedAccuracyMetersPerSecond > 0f) {
                (fix.speedAccuracyMetersPerSecond.toDouble()).pow(2.0).coerceIn(0.2, 6.0)
            } else if (sourceType == "DOPPLER") {
                0.55
            } else {
                1.10
            }

            val predCov = kalmanErrorCov + processNoiseQ
            val kalmanGain = predCov / (predCov + measurementNoiseR)
            val effectiveGain = if (candidateSpeedMs < filteredSpeedMs) max(kalmanGain, 0.78) else max(kalmanGain, 0.68)
            filteredSpeedMs += effectiveGain * (candidateSpeedMs - filteredSpeedMs)
            kalmanErrorCov = (1.0 - effectiveGain) * predCov
        }

        lastValidSpeedFix = fix
        val finalKmh = applyOffsetAndDeadband(filteredSpeedMs * 3.6, gpsOffsetKmh)

        return ComputedSpeedResult(
            isAccepted = true,
            speedKmh = finalKmh,
            rawSpeedKmh = (candidateSpeedMs * 3.6).toFloat(),
            bearingDeg = lastValidBearing,
            sourceType = sourceType
        )
    }

    /**
     * Bộ đếm Watchdog: Tự động hạ tốc độ về 0 km/h nếu quá 3.2 giây không nhận được bản tin GPS mới
     */
    fun checkWatchdogTimeout(nowElapsedRealtimeNanos: Long, gpsOffsetKmh: Int = 0): Int {
        val lastFix = lastValidSpeedFix ?: return 0
        if (lastFix.elapsedRealtimeNanos > 0L && nowElapsedRealtimeNanos > lastFix.elapsedRealtimeNanos) {
            val elapsedNanos = nowElapsedRealtimeNanos - lastFix.elapsedRealtimeNanos
            if (elapsedNanos >= WATCHDOG_TIMEOUT_NANOS) {
                filteredSpeedMs = 0.0
                hasMovingFix = false
                return 0
            }
        }
        return applyOffsetAndDeadband(filteredSpeedMs * 3.6, gpsOffsetKmh)
    }

    /**
     * Thuật toán isBetterLocation cải tiến v2.2:
     * - Loại bỏ bản tin sai số ngang quá lớn (> 65m).
     * - Cửa sổ hết hạn ngắn (8 giây) để khi xe đi từ chỗ thoáng (sai số 2m) vào phố hẹp (sai số 35-50m) không bao giờ bị khóa cứng tọa độ!
     */
    fun isBetterLocation(location: RawGpsFix, currentBestLocation: RawGpsFix?): Boolean {
        if (location.hasAccuracy && location.accuracyMeters > MAX_ACCEPTABLE_ACCURACY_M) {
            return false
        }
        if (currentBestLocation == null) {
            return true
        }

        val timeDelta = if (location.elapsedRealtimeNanos > 0L && currentBestLocation.elapsedRealtimeNanos > 0L) {
            (location.elapsedRealtimeNanos - currentBestLocation.elapsedRealtimeNanos) / 1_000_000L
        } else {
            location.timeMs - currentBestLocation.timeMs
        }
        val isSignificantlyNewer = timeDelta > STALE_FIX_EXPIRY_MS
        val isSignificantlyOlder = timeDelta < -STALE_FIX_EXPIRY_MS
        val isNewer = timeDelta >= -250L

        if (isSignificantlyNewer) return true
        if (isSignificantlyOlder) return false

        val prevIsSatellite = currentBestLocation.provider.equals("gps", ignoreCase = true) ||
                currentBestLocation.provider.equals("fused", ignoreCase = true)
        val currIsSatellite = location.provider.equals("gps", ignoreCase = true) ||
                location.provider.equals("fused", ignoreCase = true)

        if (prevIsSatellite && location.provider.equals("network", ignoreCase = true) && timeDelta < 4_000L) {
            return false
        }

        // Mọi bản tin từ GPS/Fused mới hơn và có sai số <= 45m luôn được chấp nhận ngay lập tức
        if (isNewer && currIsSatellite && (!location.hasAccuracy || location.accuracyMeters <= 45.0f)) {
            return true
        }

        val accuracyDelta = (location.accuracyMeters - currentBestLocation.accuracyMeters).toInt()
        val isLessAccurate = accuracyDelta > 0
        val isMoreAccurate = accuracyDelta < 0
        val isSignificantlyLessAccurate = accuracyDelta > 35

        val isSameOrCompatibleProvider = (prevIsSatellite && currIsSatellite) ||
                location.provider.equals(currentBestLocation.provider, ignoreCase = true)

        if (isMoreAccurate) return true
        if (isNewer && !isLessAccurate) return true
        if (isNewer && !isSignificantlyLessAccurate && isSameOrCompatibleProvider) return true

        return false
    }

    private fun applyOffsetAndDeadband(speedKmhRaw: Double, gpsOffsetKmh: Int): Int {
        if (speedKmhRaw < ZERO_SPEED_DEADBAND_KMH) return 0
        return max(0, (speedKmhRaw + gpsOffsetKmh).roundToInt())
    }

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
