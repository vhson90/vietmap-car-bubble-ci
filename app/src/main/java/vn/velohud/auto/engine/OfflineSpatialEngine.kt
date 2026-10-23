package vn.velohud.auto.engine

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

/**
 * Dữ liệu của một điểm Biển báo / Camera giải mã từ CSDL Không gian Offline VeloHUD
 */
data class EdogPoint(
    val id: Int,
    val lat: Double,
    val lon: Double,
    val type: Int,
    val speedLimit: Int,
    val dirFlag: Int,
    val angle: Int,
    val distanceMeters: Double = 0.0
) {
    fun getTypeName(): String {
        return when (type) {
            1 -> "Biển giới hạn ${speedLimit} km/h"
            2 -> "Vào khu đông dân cư"
            3 -> "Ra khỏi khu dân cư"
            4 -> "Khu vực giảm tốc / Chú ý"
            5 -> "Biển cấm vượt"
            6 -> "Hết cấm vượt"
            7 -> if (speedLimit >= 80) "Điểm kiểm tra tốc độ (${speedLimit} km/h)" else "Giao nhau với đường sắt"
            8 -> "Trạm thu phí ETC"
            9 -> "Đường hầm"
            10 -> if (speedLimit > 0) "Camera phạt nguội (${speedLimit} km/h)" else "Camera phạt nguội"
            11 -> if (speedLimit > 0) "Camera giao thông (${speedLimit} km/h)" else "Camera giám sát / Đèn đỏ"
            12 -> "Camera AI giám sát"
            else -> "Biển cảnh báo giao thông"
        }
    }

    /**
     * Trả về đường dẫn file âm thanh MP3 giọng người thật thu âm phòng thu trong `assets/sounds/`
     */
    fun getVoiceMp3AssetPaths(): List<String> {
        val validSpeed = if (speedLimit in listOf(30, 40, 50, 60, 70, 80, 90, 100, 120)) speedLimit else 0
        return when (type) {
            1 -> if (validSpeed > 0) listOf("sounds/next_speed_${validSpeed}.mp3") else listOf("sounds/kiem_tra_toc_do.mp3")
            2 -> listOf("sounds/vao_kdc.mp3")
            3 -> listOf("sounds/ra_kdc.mp3")
            4 -> listOf("sounds/kiem_tra_toc_do.mp3")
            5 -> listOf("sounds/vao_cam_vuot.mp3")
            6 -> listOf("sounds/ra_cam_vuot.mp3")
            7 -> if (speedLimit >= 80) listOf("sounds/kiem_tra_toc_do.mp3") else listOf("sounds/giao_duong_sat.mp3")
            8 -> listOf("sounds/tram_thu_phi.mp3")
            9 -> listOf("sounds/vao_duong_ham.mp3")
            10 -> if (validSpeed > 0) {
                listOf("sounds/camera_speed.mp3", "sounds/current_speed_${validSpeed}.mp3")
            } else {
                listOf("sounds/camera_speed.mp3")
            }
            11 -> if (validSpeed > 0) {
                listOf("sounds/camera_traffic.mp3", "sounds/current_speed_${validSpeed}.mp3")
            } else {
                listOf("sounds/camera_traffic.mp3")
            }
            12 -> listOf("sounds/camera_ai.mp3")
            else -> listOf("sounds/camera.mp3")
        }
    }

    fun getVoiceText(distM: Int): String {
        val roundedDist = max(50, ((distM + 25) / 50) * 50)
        return when (type) {
            1 -> "Phía trước ${roundedDist} mét, tốc độ giới hạn ${speedLimit} ki lô mét trên giờ"
            2 -> "Phía trước ${roundedDist} mét, bắt đầu vào khu dân cư"
            3 -> "Phía trước ${roundedDist} mét, ra khỏi khu đông dân cư"
            4 -> "Phía trước ${roundedDist} mét, chú ý giảm tốc độ"
            5 -> "Phía trước ${roundedDist} mét, khu vực cấm vượt"
            6 -> "Phía trước ${roundedDist} mét, hết khu vực cấm vượt"
            7 -> if (speedLimit >= 80) {
                "Phía trước ${roundedDist} mét, điểm kiểm tra tốc độ ${speedLimit} ki lô mét trên giờ"
            } else {
                "Chú ý! Phía trước ${roundedDist} mét giao nhau với đường sắt"
            }
            8 -> "Phía trước ${roundedDist} mét, trạm thu phí không dừng E T C"
            9 -> "Phía trước ${roundedDist} mét, chuẩn bị vào đường hầm"
            10 -> if (speedLimit > 0) {
                "Chú ý! Cách ${roundedDist} mét có camera phạt nguội giới hạn ${speedLimit} ki lô mét trên giờ"
            } else {
                "Chú ý! Cách ${roundedDist} mét có camera giám sát phạt nguội"
            }
            11 -> "Phía trước ${roundedDist} mét, có camera giám sát giao thông"
            12 -> "Phía trước ${roundedDist} mét có camera A I giám sát giao thông"
            else -> "Chú ý biển báo phía trước cách ${roundedDist} mét"
        }
    }

    fun getAlertAssetPath(): String {
        return when (type) {
            1 -> "speed/${if (speedLimit in listOf(30, 40, 50, 60, 70, 80, 90, 100, 120)) speedLimit else 60}.png"
            2 -> "alerts/residential_area_in.png"
            3 -> "alerts/residential_area_out.png"
            4 -> "alerts/speed_limit_checkpoint.png"
            5 -> "alerts/no_passing_in.png"
            6 -> "alerts/no_passing_out.png"
            7 -> if (speedLimit >= 80) "alerts/speed_limit_checkpoint.png" else "alerts/railway.png"
            8 -> "alerts/toll_fee_etc.png"
            9 -> "alerts/tunnel.png"
            10 -> if (speedLimit > 0) "alerts/speed_limit_camera.png" else "alerts/penalty_camera.png"
            11 -> "alerts/traffic_camera.png"
            12 -> "alerts/penalty_camera.png"
            else -> "alerts/traffic_camera.png"
        }
    }
}

/**
 * Kết quả truy vấn trạng thái tốc độ giới hạn, tên đường hiện tại & cảnh báo phía trước tại tọa độ GPS hiện tại
 */
data class SpatialStateResult(
    val currentSpeedLimit: Int,
    val activeZoneName: String,
    val upcomingAlert: EdogPoint?,
    val nearbyPointCount: Int,
    val adaptiveLookaheadMeters: Int = 330,
    val currentRoadName: String = "Đường nội đô",
    val currentAdminArea: String = ""
)

/**
 * Engine truy vấn không gian Offline 100% từ SQLite Database (69,965 điểm toàn quốc) — Phiên bản VeloHUD v3.0:
 * - Cự ly cảnh báo biến thiên theo vận tốc (`warningAlertDistance`):
 *   + Đi chậm trong phố (< 40 km/h): Quét 240m phía trước (tránh báo sớm biển báo cách 2–3 ngã tư).
 *   + Đi đường trục đô thị (40..65 km/h): Quét 330m phía trước.
 *   + Đi quốc lộ / cao tốc (> 65 km/h): Tự động nới tầm quét lên 450m phía trước.
 * - Tính khoảng cách chiếu dọc trục đường (`alongTrackMeters = dist * cos(relAngle)`).
 * - Cơ chế `cleanWhenOut()` + `minSeenDistMap` CPA Receding Detector:
 *   Xóa biển/camera ngay lập tức khi xe đi ngang qua trụ biển (`alongTrack <= 10m`) hoặc góc lệch > 80° hoặc khoảng cách bắt đầu tăng trở lại.
 */
class OfflineSpatialEngine(private val context: Context) {

    companion object {
        private const val TAG = "OfflineSpatialEngine"
        private const val DB_NAME = "velohud_offline.db"
        private const val SEARCH_RADIUS_DEG = 0.0055    // ~600m bounding box
        private const val LATCH_SIGN_ALONG_M = 15.0     // Chốt biển tốc độ mới khi xe thực sự tiến sát biển (<= 15m dọc đường)
        private const val PASS_CAMERA_ALONG_M = 10.0    // cleanWhenOut: Xóa camera/biển phụ ngay khi cách mũi xe <= 10m
        private const val MAX_CROSS_TRACK_M = 34.0      // Độ lệch ngang làn đường tối đa 34m
        private const val CLUSTER_DEDUP_RADIUS_M = 35.0 // Bán kính gộp các bản ghi kép tại cùng 1 trụ/giá long môn

        /**
         * Tính cự ly quét cảnh báo phía trước biến thiên theo vận tốc thực tế (`warningAlertDistance`)
         */
        fun computeAdaptiveLookaheadMeters(currentSpeedKmh: Int): Double {
            return when {
                currentSpeedKmh in 1..39 -> 240.0  // Nội đô đông đúc: tránh bắt nhầm biển cách 2-3 ngã tư
                currentSpeedKmh in 40..65 -> 330.0 // Đường trục đô thị tiêu chuẩn
                currentSpeedKmh > 65 -> 450.0      // Quốc lộ / Cao tốc: báo sớm từ 450m
                else -> 330.0                      // Khi xe đang dừng (0 km/h) hoặc mới khởi động
            }
        }
    }

    private var db: SQLiteDatabase? = null
    private val nationalRoadMatcher = NationalRoadMatcher(context)
    private val liveTileMatcher = LiveVectorTileMatcher()

    // Trạng thái chốt tốc độ giới hạn & Tên đường hiện tại trên cung đường
    var latchedSpeedLimit: Int = 50
        private set
    var activeZoneDescription: String = "Đường nội đô (50 km/h)"
        private set
    var currentRoadName: String = "Đường nội đô"
        private set
    var currentAdminArea: String = ""
        private set

    private var lastLatchedPointId: Int = -1
    private var hasActiveSignLatch: Boolean = false
    private var signLatchedRoadName: String = ""
    private var signLatchedBaseRoadSpeed: Int = 50
    private var signLatchedAtLat: Double = 0.0
    private var signLatchedAtLon: Double = 0.0
    private var signLatchedBearing: Double = 0.0

    // Trạng thái vùng Khu đông dân cư (TYPE = 2: true, TYPE = 3: false, null: tự động theo đồ thị đường)
    private var inResidentialZone: Boolean? = null
    private var residentialSignLat: Double = 0.0
    private var residentialSignLon: Double = 0.0

    // Bộ nhớ các điểm đã đi qua (cleanWhenOut / Passed Points) & Khoảng cách cực cận (CPA)
    private val passedPointIds = HashSet<Int>()
    private val minSeenDistMap = HashMap<Int, Double>()

    fun initializeDatabase(): Boolean {
        return try {
            val dbFile = File(context.filesDir, DB_NAME)
            if (!dbFile.exists() || dbFile.length() < 100_000L) {
                Log.i(TAG, "Đang giải nén CSDL Offline $DB_NAME (69,965 điểm)...")
                context.assets.open(DB_NAME).use { input ->
                    FileOutputStream(dbFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }
                Log.i(TAG, "Hoàn tất sao chép CSDL Offline (${dbFile.length() / 1024} KB)")
            }
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val roadOk = nationalRoadMatcher.initialize()
            Log.i(TAG, "Trạng thái khởi tạo NationalRoadMatcher Toàn Quốc: $roadOk")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi tạo CSDL Offline: ${e.message}", e)
            false
        }
    }

    fun resetLatchedState(defaultLimit: Int = 50) {
        latchedSpeedLimit = defaultLimit
        activeZoneDescription = "Đường nội đô ($defaultLimit km/h)"
        currentRoadName = "Đường nội đô"
        currentAdminArea = ""
        lastLatchedPointId = -1
        hasActiveSignLatch = false
        signLatchedRoadName = ""
        inResidentialZone = null
        passedPointIds.clear()
        minSeenDistMap.clear()
        nationalRoadMatcher.resetState()
    }

    /**
     * Truy vấn tốc độ giới hạn hiện tại và biển báo/camera sắp tới dựa trên GPS (lat, lon, bearing, currentSpeedKmh)
     */
    fun queryLocation(
        lat: Double,
        lon: Double,
        bearingDeg: Float,
        currentSpeedKmh: Int = 50
    ): SpatialStateResult {
        val adaptiveMaxScanM = computeAdaptiveLookaheadMeters(currentSpeedKmh)

        // Tầng 1 & 3: Nhận diện Tên đường, Phường/Xã, Tỉnh/Thành & Tốc độ mặc định của đoạn đường (50/60/70/80/90/120 km/h)
        var roadMatch = nationalRoadMatcher.matchRoad(lat, lon, bearingDeg)
        if (!roadMatch.isNamedRoad) {
            val mvtFallback = liveTileMatcher.queryFallbackIfAvailable(lat, lon)
            if (mvtFallback != null) {
                roadMatch = roadMatch.copy(
                    roadName = mvtFallback.first,
                    speedLimitKmh = mvtFallback.second,
                    isNamedRoad = true
                )
            }
        }

        currentRoadName = roadMatch.roadName
        currentAdminArea = when {
            roadMatch.districtName.isNotEmpty() && roadMatch.cityName.isNotEmpty() ->
                "${roadMatch.districtName}, ${roadMatch.cityName}"
            roadMatch.districtName.isNotEmpty() -> roadMatch.districtName
            roadMatch.cityName.isNotEmpty() -> roadMatch.cityName
            else -> ""
        }
        val roadDefaultSpeed = roadMatch.speedLimitKmh

        // Kiểm tra xem xe đã rẽ sang đường mới hoặc đổi phân đoạn tốc độ mới chưa để nhả biển chốt cũ (TYPE = 1, 10)
        if (hasActiveSignLatch) {
            val distFromSignM = haversineMeters(lat, lon, signLatchedAtLat, signLatchedAtLon)
            // Đồng bộ tốc độ nền của đoạn đường ngay sau biển báo (trong 85m đầu tiên sau biển)
            if (distFromSignM in 15.0..85.0 && roadDefaultSpeed == latchedSpeedLimit) {
                signLatchedBaseRoadSpeed = roadDefaultSpeed
            }
            val turnAngle = angleDiff(bearingDeg.toDouble(), signLatchedBearing)
            val switchedNamedRoad = roadMatch.isNamedRoad &&
                    signLatchedRoadName.isNotEmpty() &&
                    roadMatch.roadName != signLatchedRoadName
            val madeSharpTurn = distFromSignM > 45.0 && turnAngle >= 48.0
            val roadClassSpeedChanged = distFromSignM > 85.0 && roadDefaultSpeed != signLatchedBaseRoadSpeed
            val signExpiredByDistance = distFromSignM > 1500.0

            if (switchedNamedRoad || madeSharpTurn || roadClassSpeedChanged || signExpiredByDistance) {
                hasActiveSignLatch = false
            }
        }

        // Kiểm tra thời hạn hiệu lực vùng đệm biển Khu dân cư (TYPE = 2 / TYPE = 3)
        if (inResidentialZone != null) {
            val distFromResM = haversineMeters(lat, lon, residentialSignLat, residentialSignLon)
            if (inResidentialZone == true) {
                if (distFromResM > 1800.0 || (distFromResM > 300.0 && roadDefaultSpeed >= 90)) {
                    inResidentialZone = null
                }
            } else if (inResidentialZone == false) {
                if (distFromResM > 1200.0 || (distFromResM > 250.0 && roadDefaultSpeed in 30..60)) {
                    inResidentialZone = null
                }
            }
        }

        applyEffectiveSpeedAndZone(roadMatch)

        val database = db ?: return SpatialStateResult(
            currentSpeedLimit = latchedSpeedLimit,
            activeZoneName = activeZoneDescription,
            upcomingAlert = null,
            nearbyPointCount = 0,
            adaptiveLookaheadMeters = adaptiveMaxScanM.roundToInt(),
            currentRoadName = currentRoadName,
            currentAdminArea = currentAdminArea
        )

        val minLat = lat - SEARCH_RADIUS_DEG
        val maxLat = lat + SEARCH_RADIUS_DEG
        val minLon = lon - SEARCH_RADIUS_DEG
        val maxLon = lon + SEARCH_RADIUS_DEG

        val candidates = ArrayList<EdogPoint>()
        val candidateIds = HashSet<Int>()

        val sql = """
            SELECT id, lat, lon, type, speed_limit, dir_flag, angle
            FROM edog_points
            WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
        """.trimIndent()

        try {
            database.rawQuery(
                sql,
                arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getInt(0)
                    val pLat = cursor.getDouble(1)
                    val pLon = cursor.getDouble(2)
                    val type = cursor.getInt(3)
                    val speedLimit = cursor.getInt(4)
                    val dirFlag = cursor.getInt(5)
                    val angle = cursor.getInt(6)

                    // Bỏ qua 23,342 điểm giảm tốc chung (TYPE = 4, speed_limit = 0) để tránh báo giả liên tục tại mọi ngã tư,
                    // và bỏ qua cảnh báo đường sắt (TYPE = 7, speed_limit < 80) khi đang chạy trên đường cao tốc (>= 90 km/h)
                    if (type == 4 && speedLimit == 0) continue
                    if (type == 7 && speedLimit < 80 && roadDefaultSpeed >= 90) continue

                    val distM = haversineMeters(lat, lon, pLat, pLon)
                    candidates.add(
                        EdogPoint(
                            id = id,
                            lat = pLat,
                            lon = pLon,
                            type = type,
                            speedLimit = speedLimit,
                            dirFlag = dirFlag,
                            angle = angle,
                            distanceMeters = distM
                        )
                    )
                    candidateIds.add(id)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi truy vấn SQLite: ${e.message}")
        }

        // Tự động dọn dẹp bộ nhớ cho các điểm đã nằm ngoài vùng bán kính 600m quanh xe
        passedPointIds.retainAll(candidateIds)
        minSeenDistMap.keys.retainAll(candidateIds)

        val forwardAlerts = ArrayList<EdogPoint>()

        // Vòng 1: Cập nhật CPA (Closest Point of Approach), chốt biển tốc độ và thực thi cleanWhenOut()
        for (pt in candidates) {
            val bearingToPoint = calculateBearing(lat, lon, pt.lat, pt.lon)
            val relAngle = angleDiff(bearingDeg.toDouble(), bearingToPoint)
            val rad = Math.toRadians(relAngle)

            // Khoảng cách chiếu dọc trục di chuyển của xe (>0: phía trước mũi xe, <=0: ngang hoặc sau mũi xe)
            val alongTrackM = pt.distanceMeters * cos(rad)
            // Khoảng cách lệch ngang làn đường
            val crossTrackM = abs(pt.distanceMeters * sin(rad))
            val isDirectionCompatible = isSignDirectionMatched(bearingDeg.toDouble(), pt.angle, pt.dirFlag)

            if (!isDirectionCompatible) continue

            if (pt.distanceMeters <= 160.0 && crossTrackM <= MAX_CROSS_TRACK_M + 10.0) {
                if (relAngle <= 75.0 || minSeenDistMap.containsKey(pt.id)) {
                    val prevMin = minSeenDistMap[pt.id] ?: Double.MAX_VALUE
                    if (pt.distanceMeters < prevMin) {
                        minSeenDistMap[pt.id] = pt.distanceMeters
                    }
                }
                val currentMin = minSeenDistMap[pt.id]

                // Cơ chế cleanWhenOut(): Xóa ngay lập tức khi xe đi ngang qua hoặc vừa vượt qua điểm đặt biển
                val isRecedingFromClose = (currentMin != null && currentMin <= 85.0 && pt.distanceMeters > currentMin + 3.8)
                val isBehindBumper = (currentMin != null && currentMin <= 95.0 && relAngle > 80.0)
                val isAtSignLine = (alongTrackM in -20.0..PASS_CAMERA_ALONG_M && crossTrackM <= MAX_CROSS_TRACK_M)

                if (isRecedingFromClose || isBehindBumper || isAtSignLine) {
                    if (pt.id !in passedPointIds) {
                        latchSpeedLimitIfNeeded(pt, lat, lon, bearingDeg.toDouble(), roadMatch)
                    }
                    cleanWhenOut(pt, candidates, bearingDeg.toDouble())
                    continue
                }
            }

            // Nếu là biển báo tốc độ / khu dân cư và xe đã tiến sát phạm vi chốt biển (<= 15m dọc đường, phía trước mũi xe)
            if (alongTrackM in 0.0..LATCH_SIGN_ALONG_M &&
                relAngle <= 48.0 &&
                crossTrackM <= MAX_CROSS_TRACK_M &&
                isDirectionCompatible &&
                pt.id !in passedPointIds
            ) {
                if (pt.type in listOf(1, 2, 3)) {
                    latchSpeedLimitIfNeeded(pt, lat, lon, bearingDeg.toDouble(), roadMatch)
                    cleanWhenOut(pt, candidates, bearingDeg.toDouble())
                    continue
                } else if (pt.type == 10 && pt.speedLimit >= 30) {
                    latchSpeedLimitIfNeeded(pt, lat, lon, bearingDeg.toDouble(), roadMatch)
                }
            }
        }

        // Vòng 2: Lọc danh sách các biển báo / camera thực sự còn ở phía trước mũi xe trong tầm warningAlertDistance
        for (pt in candidates) {
            if (pt.id in passedPointIds) continue

            val bearingToPoint = calculateBearing(lat, lon, pt.lat, pt.lon)
            val relAngle = angleDiff(bearingDeg.toDouble(), bearingToPoint)
            if (relAngle > 38.0) continue

            val rad = Math.toRadians(relAngle)
            val alongTrackM = pt.distanceMeters * cos(rad)
            val crossTrackM = abs(pt.distanceMeters * sin(rad))
            val isDirectionCompatible = isSignDirectionMatched(bearingDeg.toDouble(), pt.angle, pt.dirFlag)

            val minSeen = minSeenDistMap[pt.id]
            if (minSeen != null && minSeen <= 90.0 && pt.distanceMeters > minSeen + 3.8) {
                cleanWhenOut(pt, candidates, bearingDeg.toDouble())
                continue
            }

            if (alongTrackM in PASS_CAMERA_ALONG_M..adaptiveMaxScanM &&
                crossTrackM <= MAX_CROSS_TRACK_M &&
                isDirectionCompatible
            ) {
                forwardAlerts.add(pt.copy(distanceMeters = max(10.0, alongTrackM)))
            }
        }

        // Ưu tiên chọn điểm cảnh báo quan trọng gần nhất phía trước
        val bestUpcoming = forwardAlerts.minByOrNull { pt ->
            val priorityWeight = when {
                pt.type == 10 -> 0.55 // Ưu tiên số 1: Camera phạt nguội / bắn tốc độ
                pt.type == 1 && pt.speedLimit != latchedSpeedLimit -> 0.65 // Ưu tiên số 2: Biển ĐỔI giới hạn tốc độ mới
                pt.type in listOf(2, 3) -> 0.75 // Biển Khu dân cư / Hết KDC
                pt.type in listOf(11, 12) -> 0.85 // Camera giám sát giao thông / Đèn đỏ / Camera AI
                pt.type in listOf(8, 9) -> 0.95 // Trạm thu phí ETC / Đường hầm
                pt.type == 1 && pt.speedLimit == latchedSpeedLimit -> 1.60 // Biển nhắc lại cùng tốc độ hiện tại
                else -> 1.10
            }
            pt.distanceMeters * priorityWeight
        }

        return SpatialStateResult(
            currentSpeedLimit = latchedSpeedLimit,
            activeZoneName = activeZoneDescription,
            upcomingAlert = bestUpcoming,
            nearbyPointCount = candidates.size,
            adaptiveLookaheadMeters = adaptiveMaxScanM.roundToInt(),
            currentRoadName = currentRoadName,
            currentAdminArea = currentAdminArea
        )
    }

    /**
     * Tính toán tốc độ giới hạn hiệu lực (`latchedSpeedLimit`) từ Đồ thị Đường bộ Toàn quốc (`NationalRoadMatcher`),
     * kết hợp trạng thái biển báo tốc độ (`hasActiveSignLatch`) và trạng thái Khu đông dân cư (`inResidentialZone`).
     */
    private fun applyEffectiveSpeedAndZone(roadMatch: RoadMatchResult) {
        val roadDefaultSpeed = roadMatch.speedLimitKmh
        if (!hasActiveSignLatch) {
            latchedSpeedLimit = when (inResidentialZone) {
                true -> {
                    // Trong khu đông dân cư: Nếu bản đồ đường đã có tốc độ nội thị (30..60 km/h) -> lấy chính xác từng đoạn (50 hoặc 60).
                    // Nếu vừa qua chân biển mà đoạn bản đồ chưa kịp chuyển (vẫn > 60) -> Đường đôi/1 chiều: 60 km/h, Đường 2 chiều: 50 km/h.
                    if (roadDefaultSpeed in 30..60) {
                        roadDefaultSpeed
                    } else {
                        if (roadMatch.isOneWay) 60 else 50
                    }
                }
                false -> {
                    // Ngoài khu đông dân cư: Nếu bản đồ đường đã >= 70 km/h -> lấy chính xác từng đoạn (70/80/90/120).
                    // Nếu vừa qua chân biển Hết KDC mà bản đồ chưa kịp chuyển (vẫn <= 60) -> Đường đôi/1 chiều: 90 km/h, Đường 2 chiều: 80 km/h.
                    if (roadDefaultSpeed >= 70) {
                        roadDefaultSpeed
                    } else {
                        if (roadMatch.isOneWay) 90 else 80
                    }
                }
                null -> roadDefaultSpeed
            }

            activeZoneDescription = if (currentAdminArea.isNotEmpty()) {
                "$currentRoadName • $currentAdminArea (${latchedSpeedLimit} km/h)"
            } else {
                "$currentRoadName (${latchedSpeedLimit} km/h)"
            }
        } else {
            activeZoneDescription = if (currentAdminArea.isNotEmpty()) {
                "$currentRoadName • $currentAdminArea (Biển ${latchedSpeedLimit} km/h)"
            } else {
                "$currentRoadName (Biển ${latchedSpeedLimit} km/h)"
            }
        }
    }

    private fun latchSpeedLimitIfNeeded(
        pt: EdogPoint,
        carLat: Double,
        carLon: Double,
        carBearing: Double,
        roadMatch: RoadMatchResult
    ) {
        if (pt.id == lastLatchedPointId) return
        val roadDefaultSpeed = roadMatch.speedLimitKmh
        when (pt.type) {
            1 -> if (pt.speedLimit >= 30) {
                latchedSpeedLimit = pt.speedLimit
                hasActiveSignLatch = true
                signLatchedRoadName = currentRoadName
                signLatchedBaseRoadSpeed = roadDefaultSpeed
                signLatchedAtLat = carLat
                signLatchedAtLon = carLon
                signLatchedBearing = carBearing
                lastLatchedPointId = pt.id
                applyEffectiveSpeedAndZone(roadMatch)
            }
            2 -> {
                // Vào khu đông dân cư: Giải phóng mọi khóa biển tốc độ cũ, kích hoạt chế độ bám sát tốc độ 50/60 từng đoạn đường
                hasActiveSignLatch = false
                inResidentialZone = true
                residentialSignLat = carLat
                residentialSignLon = carLon
                lastLatchedPointId = pt.id
                applyEffectiveSpeedAndZone(roadMatch)
            }
            3 -> {
                // Ra khỏi khu đông dân cư: Giải phóng khóa biển cũ, chuyển sang tốc độ ngoài khu dân cư (70/80/90 km/h)
                hasActiveSignLatch = false
                inResidentialZone = false
                residentialSignLat = carLat
                residentialSignLon = carLon
                lastLatchedPointId = pt.id
                applyEffectiveSpeedAndZone(roadMatch)
            }
            10 -> if (pt.speedLimit >= 30) {
                latchedSpeedLimit = pt.speedLimit
                hasActiveSignLatch = true
                signLatchedRoadName = currentRoadName
                signLatchedBaseRoadSpeed = roadDefaultSpeed
                signLatchedAtLat = carLat
                signLatchedAtLon = carLon
                signLatchedBearing = carBearing
                lastLatchedPointId = pt.id
                applyEffectiveSpeedAndZone(roadMatch)
            }
        }
    }

    /**
     * Cơ chế `cleanWhenOut`: Đánh dấu điểm `pt` và toàn bộ các bản ghi trùng lặp cùng loại, cùng hướng
     * trong bán kính 35m tại trụ/giá long môn đó là ĐÃ ĐI QUA để xóa ngay khỏi giao diện.
     */
    private fun cleanWhenOut(pt: EdogPoint, candidates: List<EdogPoint>, carBearing: Double) {
        passedPointIds.add(pt.id)
        for (other in candidates) {
            if (other.id == pt.id || other.id in passedPointIds) continue
            if (isSameCategoryPoint(pt, other) && isSignDirectionMatched(carBearing, other.angle, other.dirFlag)) {
                val d = haversineMeters(pt.lat, pt.lon, other.lat, other.lon)
                if (d <= CLUSTER_DEDUP_RADIUS_M) {
                    passedPointIds.add(other.id)
                }
            }
        }
    }

    private fun isSameCategoryPoint(a: EdogPoint, b: EdogPoint): Boolean {
        if (a.type == b.type) {
            return a.type != 1 || a.speedLimit == b.speedLimit
        }
        val camTypes = setOf(10, 11, 12)
        return (a.type in camTypes && b.type in camTypes)
    }

    private fun isSignDirectionMatched(carBearing: Double, signAngle: Int, dirFlag: Int): Boolean {
        if (dirFlag == 0 || signAngle <= 0 || signAngle > 360) return true
        // Chỉ khớp các biển báo / camera cùng chiều di chuyển của xe (bearingTol <= 48°),
        // tuyệt đối không bắt nhầm biển báo / camera của làn ngược chiều bên kia con lươn!
        val diffSame = angleDiff(carBearing, signAngle.toDouble())
        return diffSame <= 48.0
    }

    fun close() {
        try {
            db?.close()
        } catch (_: Exception) {
        }
        nationalRoadMatcher.close()
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

    private fun angleDiff(a: Double, b: Double): Double {
        var d = abs(a - b) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }
}
