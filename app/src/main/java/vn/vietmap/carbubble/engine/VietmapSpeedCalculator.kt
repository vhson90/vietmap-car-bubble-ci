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
 * Kết quả tính toán vận tốc & hướng di chuyển theo chuẩn thuật toán Vietmap Live
 */
data class ComputedSpeedResult(
    val isAccepted: Boolean,
    val speedKmh: Int,
    val rawSpeedKmh: Float,
    val bearingDeg: Float,
    val sourceType: String // "DOPPLER", "GEODESIC_FALLBACK", "STATIONARY", "REJECTED_NOISE"
)

/**
 * Bộ tính toán vận tốc xe hiện tại học hỏi từ kiến trúc vn.vietmap.lib_location_engine (LocationEngineService):
 * 1. Bộ lọc tọa độ tối ưu isBetterLocation (Lma/a$a): Loại bỏ bản tin nhiễu sai số lớn (> 48m) hoặc nhảy trạm phát sóng.
 * 2. Kết hợp vận tốc sóng mang Doppler (location.speed) và vận tốc hình học Geodesic (Δs / Δt) dùng đồng hồ đơn điệu elapsedRealtimeNanos (Lma/g).
 * 3. Giới hạn gia tốc vật lý tối đa của ô tô (<= 8.5 m/s²) để cắt gai nhiễu GPS dưới gầm cầu vượt / cửa hầm.
 * 4. Bộ lọc Kalman 1D / EMA thích ứng (nhạy khi phanh giảm tốc, mượt khi chạy đều) & Cắt nhiễu dừng đèn đỏ (< 2.4 km/h -> 0 km/h).
 * 5. Bộ đếm Watchdog (3.2 giây không có tín hiệu mới tự động hạ tốc độ về 0 km/h).
 */
class VietmapSpeedCalculator {

    companion object {
        private const val TWO_MINUTES_MS = 120_000L
        private const val MAX_ACCEPTABLE_ACCURACY_M = 48.0f
        private const val MAX_PHYSICAL_ACCEL_MS2 = 8.5 // Gia tốc ô tô tối đa ~30.6 km/h mỗi giây
        private const val ZERO_SPEED_DEADBAND_KMH = 2.4
        private const val WATCHDOG_TIMEOUT_NANOS = 3_200_000_000L // 3.2 giây
    }

    private var currentBestFix: RawGpsFix? = null
    private var lastValidSpeedFix: RawGpsFix? = null
    private var filteredSpeedMs: Double = 0.0
    private var kalmanErrorCov: Double = 1.0
    private var lastValidBearing: Float = 0.0f
    private var hasMovingFix: Boolean = false

    fun reset() {
        currentBestFix = null
        lastValidSpeedFix = null
        filteredSpeedMs = 0.0
        kalmanErrorCov = 1.0
        hasMovingFix = false
    }

    /**
     * Xử lý bản tin GPS mới và trả về tốc độ hiện tại (km/h)
     */
    fun processGpsFix(fix: RawGpsFix, gpsOffsetKmh: Int = 0): ComputedSpeedResult {
        // Bước 1: Kiểm tra chất lượng tọa độ theo thuật toán Lma/a$a->a (isBetterLocation) của Vietmap Live
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

        // Bước 2: Cập nhật góc hướng lái (Bearing)
        if (fix.hasBearing && fix.bearingDegrees > 0f && (!fix.hasSpeed || fix.speedMetersPerSecond >= 0.8f)) {
            lastValidBearing = fix.bearingDegrees
        } else if (prevFix != null && distMeters >= 2.5) {
            lastValidBearing = calculateBearing(prevFix.lat, prevFix.lon, fix.lat, fix.lon).toFloat()
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

            // Kiểm tra chéo với quãng đường dịch chuyển nếu dtSec đủ lớn (chống nhiễu Doppler khi xe đang đỗ dưới mái tôn)
            if (prevFix != null && dtSec in 1.2..8.0) {
                val geoSpeedMs = distMeters / dtSec
                val accTolerance = if (fix.hasAccuracy) fix.accuracyMeters.toDouble() else 10.0
                if (distMeters < 1.2 && accTolerance > 12.0 && candidateSpeedMs < 2.2) {
                    candidateSpeedMs = 0.0
                    sourceType = "STATIONARY"
                } else if (candidateSpeedMs > geoSpeedMs * 3.2 + 8.0 && fix.hasAccuracy && fix.accuracyMeters > 20f) {
                    // Nếu Doppler vọt cao bất thường khi tín hiệu GPS yếu, lấy trung bình có trọng số với Geodesic
                    candidateSpeedMs = 0.4 * candidateSpeedMs + 0.6 * geoSpeedMs
                }
            }
        } else if (prevFix != null && dtSec in 0.15..12.0) {
            // Fallback tính từ quãng đường dịch chuyển Δs / Δt (Dành cho Android Box CarPlay không truyền location.speed)
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
            // Khi xe dừng hoặc gần dừng (< 2.34 km/h), hạ nhanh về 0
            filteredSpeedMs = if (filteredSpeedMs < 1.2) 0.0 else filteredSpeedMs * 0.35
            if (filteredSpeedMs == 0.0) {
                hasMovingFix = false
            }
        } else if (!hasMovingFix) {
            // Nhịp di chuyển hợp lệ đầu tiên (khi vừa bật App lúc xe đang chạy hoặc vừa ra khỏi hầm):
            // Khởi tạo trực tiếp để đồng hồ bắt kịp vận tốc thực ngay lập tức mà không bị trễ
            filteredSpeedMs = candidateSpeedMs
            kalmanErrorCov = 1.0
            hasMovingFix = true
        } else {
            if (prevFix != null && dtSec in 0.05..10.0) {
                val maxDeltaV = MAX_PHYSICAL_ACCEL_MS2 * dtSec
                val deltaV = candidateSpeedMs - filteredSpeedMs
                if (deltaV > maxDeltaV) {
                    // Chặn gai nhảy vọt tốc độ ảo (GPS spike)
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
            // Tăng độ nhạy khi xe đang giảm tốc (phanh)
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
     * Bộ đếm Watchdog (Tương tự GPSSignal.loss trong Vietmap Live):
     * Tự động hạ tốc độ về 0 km/h nếu quá 3.2 giây không nhận được bản tin GPS mới (xe dừng hẳn hoặc mất sóng).
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
     * Thuật toán isBetterLocation nguyên bản từ class Lma/a$a->a trong vn.vietmap.lib_location_engine
     */
    fun isBetterLocation(location: RawGpsFix, currentBestLocation: RawGpsFix?): Boolean {
        // Loại bỏ ngay những bản tin sai số ngang quá lớn (> 48m)
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
        val isSignificantlyNewer = timeDelta > TWO_MINUTES_MS
        val isSignificantlyOlder = timeDelta < -TWO_MINUTES_MS
        val isNewer = timeDelta >= -250L // Dung sai 250ms khi Fused và GPS trả bản tin song song

        if (isSignificantlyNewer) return true
        if (isSignificantlyOlder) return false

        // Nếu bản tin trước từ GPS/Fused chính xác cao mà bản tin mới từ Network kém chính xác -> Loại bỏ
        val prevIsSatellite = currentBestLocation.provider.equals("gps", ignoreCase = true) ||
                currentBestLocation.provider.equals("fused", ignoreCase = true)
        val currIsSatellite = location.provider.equals("gps", ignoreCase = true) ||
                location.provider.equals("fused", ignoreCase = true)

        if (prevIsSatellite && location.provider.equals("network", ignoreCase = true) && timeDelta < 5_000L) {
            return false
        }

        // Nếu bản tin mới đến từ vệ tinh (GPS/Fused) và có độ chính xác tốt (<= 25m) -> Luôn chấp nhận
        if (isNewer && currIsSatellite && (!location.hasAccuracy || location.accuracyMeters <= 25.0f)) {
            return true
        }

        val accuracyDelta = (location.accuracyMeters - currentBestLocation.accuracyMeters).toInt()
        val isLessAccurate = accuracyDelta > 0
        val isMoreAccurate = accuracyDelta < 0
        val isSignificantlyLessAccurate = accuracyDelta > 30

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
