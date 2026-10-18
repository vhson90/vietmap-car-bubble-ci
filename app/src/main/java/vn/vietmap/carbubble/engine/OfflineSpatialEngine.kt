package vn.vietmap.carbubble.engine

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

/**
 * Dữ liệu của một điểm Biển báo / Camera giải mã từ edogen.bin & Hybrid Cells
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
            4 -> if (speedLimit > 0) "Camera phạt nguội (${speedLimit} km/h)" else "Camera phạt nguội / Đèn đỏ"
            5 -> "Biển cấm vượt"
            6 -> "Hết cấm vượt"
            7 -> "Trạm thu phí ETC"
            8 -> "Đường hầm / Trạm dừng"
            9 -> "Camera giám sát giao thông"
            10 -> "Giao nhau với đường sắt"
            11 -> "Đi chậm / Ngã tư nguy hiểm"
            12 -> "Chốt đèn tín hiệu Hybrid"
            else -> "Biển cảnh báo giao thông"
        }
    }

    fun getVoiceText(distM: Int): String {
        val roundedDist = max(50, ((distM + 25) / 50) * 50)
        return when (type) {
            1 -> "Phía trước ${roundedDist} mét, tốc độ giới hạn ${speedLimit} ki lô mét trên giờ"
            2 -> "Phía trước ${roundedDist} mét, bắt đầu vào khu dân cư"
            3 -> "Phía trước ${roundedDist} mét, ra khỏi khu đông dân cư"
            4 -> if (speedLimit > 0) {
                "Chú ý! Cách ${roundedDist} mét có camera phạt nguội giới hạn ${speedLimit} ki lô mét trên giờ"
            } else {
                "Chú ý! Cách ${roundedDist} mét có camera giám sát phạt nguội"
            }
            5 -> "Phía trước ${roundedDist} mét, khu vực cấm vượt"
            7 -> "Phía trước ${roundedDist} mét, trạm thu phí không dừng E T C"
            9 -> "Phía trước ${roundedDist} mét, có camera giám sát giao thông"
            10 -> "Chú ý! Phía trước ${roundedDist} mét giao nhau với đường sắt"
            12 -> "Phía trước ${roundedDist} mét có chốt đèn tín hiệu giao thông"
            else -> "Chú ý biển báo phía trước cách ${roundedDist} mét"
        }
    }

    fun getAlertAssetPath(): String {
        return when (type) {
            1 -> "speed/${if (speedLimit in listOf(30, 40, 50, 60, 70, 80, 90, 100, 120)) speedLimit else 60}.png"
            2 -> "alerts/residential_area_in.png"
            3 -> "alerts/residential_area_out.png"
            4 -> if (speedLimit > 0) "alerts/speed_limit_camera.png" else "alerts/penalty_camera.png"
            5 -> "alerts/no_passing_in.png"
            6 -> "alerts/no_passing_out.png"
            7 -> "alerts/toll_fee_etc.png"
            9 -> "alerts/traffic_camera.png"
            10 -> "alerts/railway.png"
            12 -> "alerts/penalty_camera.png"
            else -> "alerts/traffic_camera.png"
        }
    }
}

/**
 * Kết quả truy vấn trạng thái tốc độ giới hạn & cảnh báo phía trước tại tọa độ GPS hiện tại
 */
data class SpatialStateResult(
    val currentSpeedLimit: Int,
    val activeZoneName: String,
    val upcomingAlert: EdogPoint?,
    val nearbyPointCount: Int
)

/**
 * Engine truy vấn không gian Offline 100% từ SQLite Database (69,965 điểm toàn quốc) — Phiên bản v2.2:
 * - Tính khoảng cách chiếu dọc trục đường (`alongTrackMeters = dist * cos(relAngle)`) để hiển thị khoảng cách chính xác tới vạch biển báo/camera.
 * - Bộ phát hiện điểm đã đi qua (`passedPointIds` + `minSeenDistMap` CPA Receding Detector):
 *   Ngay khi xe đi ngang qua biển/camera (`alongTrack <= 12m`) hoặc khoảng cách bắt đầu tăng trở lại sau khi tiến vào vùng gần (`dist > minSeenDist + 4.5m`),
 *   lập tức đánh dấu điểm đó (cùng các bản ghi kép trong bán kính 95m) vào `passedPointIds` để xóa ngay khỏi màn hình, không bao giờ bị treo biển cũ.
 */
class OfflineSpatialEngine(private val context: Context) {

    companion object {
        private const val TAG = "OfflineSpatialEngine"
        private const val DB_NAME = "vietmap_offline.db"
        private const val SEARCH_RADIUS_DEG = 0.0055    // ~600m bounding box
        private const val FORWARD_SCAN_MAX_M = 420.0    // Cảnh báo trước tối đa 420m
        private const val LATCH_SIGN_ALONG_M = 38.0     // Chốt biển tốc độ mới khi cách mũi xe <= 38m dọc đường
        private const val PASS_CAMERA_ALONG_M = 12.0    // Xóa camera/biển phụ ngay khi cách mũi xe <= 12m
        private const val MAX_CROSS_TRACK_M = 34.0      // Độ lệch ngang làn đường tối đa 34m
        private const val CLUSTER_DEDUP_RADIUS_M = 95.0 // Bán kính gộp các bản ghi kép tại cùng 1 giao lộ/giá long môn
    }

    private var db: SQLiteDatabase? = null

    // Trạng thái chốt tốc độ giới hạn hiện tại trên cung đường
    var latchedSpeedLimit: Int = 60
        private set
    var activeZoneDescription: String = "Đô thị tiêu chuẩn (60 km/h)"
        private set
    private var lastLatchedPointId: Int = -1

    // Bộ nhớ các điểm đã đi qua (Passed Points) & Khoảng cách cực cận (CPA) trong vùng 600m quanh xe
    private val passedPointIds = HashSet<Int>()
    private val minSeenDistMap = HashMap<Int, Double>()

    fun initializeDatabase(): Boolean {
        return try {
            val dbFile = File(context.filesDir, DB_NAME)
            if (!dbFile.exists() || dbFile.length() < 100_000L) {
                Log.i(TAG, "Đang giải nén CSDL Offline vietmap_offline.db (69,965 điểm)...")
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
            true
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi tạo CSDL Offline: ${e.message}", e)
            false
        }
    }

    fun resetLatchedState(defaultLimit: Int = 60) {
        latchedSpeedLimit = defaultLimit
        activeZoneDescription = "Đô thị tiêu chuẩn ($defaultLimit km/h)"
        lastLatchedPointId = -1
        passedPointIds.clear()
        minSeenDistMap.clear()
    }

    /**
     * Truy vấn tốc độ giới hạn hiện tại và biển báo/camera sắp tới dựa trên GPS (lat, lon, bearing)
     */
    fun queryLocation(lat: Double, lon: Double, bearingDeg: Float): SpatialStateResult {
        val database = db ?: return SpatialStateResult(
            currentSpeedLimit = latchedSpeedLimit,
            activeZoneName = activeZoneDescription,
            upcomingAlert = null,
            nearbyPointCount = 0
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

        // Vòng 1: Cập nhật CPA (Closest Point of Approach), chốt biển tốc độ và đánh dấu các điểm đã đi qua
        for (pt in candidates) {
            val bearingToPoint = calculateBearing(lat, lon, pt.lat, pt.lon)
            val relAngle = angleDiff(bearingDeg.toDouble(), bearingToPoint)
            val rad = Math.toRadians(relAngle)

            // Khoảng cách chiếu dọc trục di chuyển của xe (>0: phía trước mũi xe, <=0: ngang hoặc sau mũi xe)
            val alongTrackM = pt.distanceMeters * cos(rad)
            // Khoảng cách lệch ngang làn đường
            val crossTrackM = abs(pt.distanceMeters * sin(rad))
            val isDirectionCompatible = isSignDirectionMatched(bearingDeg.toDouble(), pt.angle, pt.dirFlag)

            if (pt.distanceMeters <= 160.0 && crossTrackM <= MAX_CROSS_TRACK_M + 10.0) {
                val prevMin = minSeenDistMap[pt.id] ?: Double.MAX_VALUE
                if (pt.distanceMeters < prevMin) {
                    minSeenDistMap[pt.id] = pt.distanceMeters
                }
                val currentMin = minSeenDistMap[pt.id] ?: pt.distanceMeters

                // Phát hiện xe đã đi ngang qua hoặc đang rời xa điểm này (CPA Receding):
                // 1) Điểm đã trôi về phía sau góc 82 độ sau khi từng ở gần <= 95m
                // 2) Khoảng cách thực tế đã tăng thêm >= 4.5m so với điểm cực cận (currentMin <= 85m)
                // 3) Khoảng cách chiếu dọc đường <= 12m (xe đã chạm vạch ngang của biển/camera)
                val isRecedingFromClose = (currentMin <= 85.0 && pt.distanceMeters > currentMin + 4.5)
                val isBehindBumper = (relAngle > 82.0 && currentMin <= 95.0)
                val isAtSignLine = (alongTrackM in -25.0..PASS_CAMERA_ALONG_M && crossTrackM <= MAX_CROSS_TRACK_M)

                if (isRecedingFromClose || isBehindBumper || isAtSignLine) {
                    if (isDirectionCompatible && pt.id !in passedPointIds) {
                        latchSpeedLimitIfNeeded(pt)
                    }
                    markPointAndClusterAsPassed(pt, candidates)
                    continue
                }
            }

            // Nếu là biển báo tốc độ / khu dân cư và xe đã tiến vào phạm vi chốt biển (<= 38m dọc đường, phía trước mũi xe)
            if (alongTrackM in 0.0..LATCH_SIGN_ALONG_M &&
                relAngle <= 48.0 &&
                crossTrackM <= MAX_CROSS_TRACK_M &&
                isDirectionCompatible &&
                pt.id !in passedPointIds
            ) {
                if (pt.type in listOf(1, 2, 3)) {
                    latchSpeedLimitIfNeeded(pt)
                    // Khi biển tốc độ đã được chốt lên vòng tròn chính, ẩn ngay khỏi nhãn báo trước để không bị trùng lặp
                    markPointAndClusterAsPassed(pt, candidates)
                    continue
                } else if (pt.type == 4 && pt.speedLimit >= 30) {
                    // Camera phạt nguội có kèm tốc độ giới hạn: chốt tốc độ nhưng vẫn giữ hiển thị camera cho tới khi đi qua (<= 12m)
                    latchSpeedLimitIfNeeded(pt)
                }
            }
        }

        // Vòng 2: Lọc danh sách các biển báo / camera thực sự còn ở phía trước mũi xe (Chưa đi qua)
        for (pt in candidates) {
            if (pt.id in passedPointIds) continue

            val bearingToPoint = calculateBearing(lat, lon, pt.lat, pt.lon)
            val relAngle = angleDiff(bearingDeg.toDouble(), bearingToPoint)
            if (relAngle > 38.0) continue

            val rad = Math.toRadians(relAngle)
            val alongTrackM = pt.distanceMeters * cos(rad)
            val crossTrackM = abs(pt.distanceMeters * sin(rad))
            val isDirectionCompatible = isSignDirectionMatched(bearingDeg.toDouble(), pt.angle, pt.dirFlag)

            // Không hiển thị các điểm đang có khoảng cách tăng dần (đã đi qua điểm cực cận)
            val minSeen = minSeenDistMap[pt.id]
            if (minSeen != null && minSeen <= 90.0 && pt.distanceMeters > minSeen + 4.0) {
                markPointAndClusterAsPassed(pt, candidates)
                continue
            }

            if (alongTrackM in PASS_CAMERA_ALONG_M..FORWARD_SCAN_MAX_M &&
                crossTrackM <= MAX_CROSS_TRACK_M &&
                isDirectionCompatible
            ) {
                // Lưu khoảng cách chiếu dọc trục đường (alongTrackM) để hiển thị chính xác tuyệt đối từ mũi xe tới vạch biển báo
                forwardAlerts.add(pt.copy(distanceMeters = max(10.0, alongTrackM)))
            }
        }

        // Ưu tiên chọn điểm cảnh báo quan trọng gần nhất phía trước
        val bestUpcoming = forwardAlerts.minByOrNull { pt ->
            val priorityWeight = when (pt.type) {
                4 -> 0.85 // Ưu tiên số 1: Camera phạt nguội / tốc độ
                1 -> 0.90 // Ưu tiên số 2: Biển giới hạn tốc độ
                2, 3 -> 0.95 // Khu dân cư
                else -> 1.0
            }
            pt.distanceMeters * priorityWeight
        }

        return SpatialStateResult(
            currentSpeedLimit = latchedSpeedLimit,
            activeZoneName = activeZoneDescription,
            upcomingAlert = bestUpcoming,
            nearbyPointCount = candidates.size
        )
    }

    private fun latchSpeedLimitIfNeeded(pt: EdogPoint) {
        if (pt.id == lastLatchedPointId) return
        when (pt.type) {
            1 -> if (pt.speedLimit >= 30) {
                latchedSpeedLimit = pt.speedLimit
                activeZoneDescription = "Biển giới hạn ${pt.speedLimit} km/h"
                lastLatchedPointId = pt.id
            }
            2 -> {
                if (latchedSpeedLimit > 60) {
                    latchedSpeedLimit = 60
                }
                activeZoneDescription = "Khu đông dân cư (${latchedSpeedLimit} km/h)"
                lastLatchedPointId = pt.id
            }
            3 -> {
                if (latchedSpeedLimit < 80) {
                    latchedSpeedLimit = 80
                }
                activeZoneDescription = "Ngoài khu dân cư (${latchedSpeedLimit} km/h)"
                lastLatchedPointId = pt.id
            }
            4 -> if (pt.speedLimit >= 30) {
                latchedSpeedLimit = pt.speedLimit
                activeZoneDescription = "Khu vực Camera (${pt.speedLimit} km/h)"
                lastLatchedPointId = pt.id
            }
        }
    }

    /**
     * Đánh dấu điểm `pt` và toàn bộ các bản ghi trùng lặp cùng loại trong bán kính 95m tại giao lộ đó là ĐÃ ĐI QUA
     */
    private fun markPointAndClusterAsPassed(pt: EdogPoint, candidates: List<EdogPoint>) {
        passedPointIds.add(pt.id)
        for (other in candidates) {
            if (other.id == pt.id || other.id in passedPointIds) continue
            if (isSameCategoryPoint(pt, other)) {
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
        val camTypes = setOf(4, 9, 12)
        return (a.type in camTypes && b.type in camTypes)
    }

    private fun isSignDirectionMatched(carBearing: Double, signAngle: Int, dirFlag: Int): Boolean {
        if (dirFlag == 0 || signAngle <= 0 || signAngle > 360) return true
        val diffSame = angleDiff(carBearing, signAngle.toDouble())
        val diffOpposite = angleDiff((carBearing + 180.0) % 360.0, signAngle.toDouble())
        return (diffSame <= 45.0) || (diffOpposite <= 45.0)
    }

    fun close() {
        try {
            db?.close()
        } catch (_: Exception) {
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
