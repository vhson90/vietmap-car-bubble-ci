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
        val roundedDist = ((distM + 25) / 50) * 50
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
 * Engine truy vấn không gian Offline 100% từ SQLite Database (69,957 điểm toàn quốc).
 * Thuật toán lọc hình nón phía trước (Forward Cone) + Khoảng cách vuông góc (Cross-track <= 32m)
 * + Chốt trạng thái tốc độ giới hạn (Latching Speed Limit) khi xe chạy qua biển báo.
 */
class OfflineSpatialEngine(private val context: Context) {

    companion object {
        private const val TAG = "OfflineSpatialEngine"
        private const val DB_NAME = "vietmap_offline.db"
        private const val SEARCH_RADIUS_DEG = 0.0055 // ~600m bounding box
        private const val FORWARD_SCAN_MAX_M = 450.0 // Cảnh báo trước tối đa 450m
        private const val LATCH_PASS_DIST_M = 65.0   // Khoảng cách chốt biển tốc độ khi vừa đi qua
        private const val MAX_CROSS_TRACK_M = 35.0   // Độ lệch làn đường tối đa 35m
    }

    private var db: SQLiteDatabase? = null

    // Trạng thái chốt tốc độ giới hạn hiện tại trên cung đường
    var latchedSpeedLimit: Int = 60
        private set
    var activeZoneDescription: String = "Đô thị tiêu chuẩn (60 km/h)"
        private set
    private var lastLatchedPointId: Int = -1

    fun initializeDatabase(): Boolean {
        return try {
            val dbFile = File(context.filesDir, DB_NAME)
            // Sao chép từ assets nếu chưa tồn tại hoặc kích thước thay đổi
            if (!dbFile.exists() || dbFile.length() < 100_000L) {
                Log.i(TAG, "Đang giải nén CSDL Offline vietmap_offline.db (69,957 điểm)...")
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

        val candidates = mutableListOf<EdogPoint>()

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
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi truy vấn SQLite: ${e.message}")
        }

        val forwardAlerts = mutableListOf<EdogPoint>()

        for (pt in candidates) {
            val bearingToPoint = calculateBearing(lat, lon, pt.lat, pt.lon)
            val relAngle = angleDiff(bearingDeg.toDouble(), bearingToPoint)

            // Kiểm tra độ lệch ngang so với trục di chuyển của xe (Cross-track distance)
            val crossTrackM = abs(pt.distanceMeters * sin(Math.toRadians(relAngle)))

            // Kiểm tra hướng của biển báo (angle trong edogen.bin thường là hướng nhìn vào biển hoặc hướng chiều đường)
            val isDirectionCompatible = isSignDirectionMatched(bearingDeg.toDouble(), pt.angle, pt.dirFlag)

            // 1. Nếu xe đang đi tới rất gần (< 65m) và nằm đúng làn đường -> Chốt tốc độ giới hạn mới!
            if (pt.distanceMeters <= LATCH_PASS_DIST_M && crossTrackM <= MAX_CROSS_TRACK_M && isDirectionCompatible) {
                if (pt.id != lastLatchedPointId) {
                    when (pt.type) {
                        1 -> if (pt.speedLimit >= 30) {
                            latchedSpeedLimit = pt.speedLimit
                            activeZoneDescription = "Biển giới hạn ${pt.speedLimit} km/h"
                            lastLatchedPointId = pt.id
                        }
                        2 -> {
                            // Vào khu dân cư -> Nếu tốc độ đang > 60 thì hạ xuống 60 km/h
                            if (latchedSpeedLimit > 60) {
                                latchedSpeedLimit = 60
                            }
                            activeZoneDescription = "Khu đông dân cư (${latchedSpeedLimit} km/h)"
                            lastLatchedPointId = pt.id
                        }
                        3 -> {
                            // Ra khỏi khu dân cư -> Nâng lên 80 km/h nếu chưa có biển cắm riêng
                            if (latchedSpeedLimit < 80) {
                                latchedSpeedLimit = 80
                            }
                            activeZoneDescription = "Ngoài khu dân cư (${latchedSpeedLimit} km/h)"
                            lastLatchedPointId = pt.id
                        }
                        4 -> if (pt.speedLimit >= 30) {
                            // Camera tốc độ có kèm giới hạn tốc độ của đoạn đường
                            latchedSpeedLimit = pt.speedLimit
                            activeZoneDescription = "Khu vực Camera (${pt.speedLimit} km/h)"
                            lastLatchedPointId = pt.id
                        }
                    }
                }
            }

            // 2. Lọc các điểm nằm trong hình nón phía trước đầu xe (Góc lệch <= 42 độ, khoảng cách 15m..450m)
            if (pt.distanceMeters in 12.0..FORWARD_SCAN_MAX_M &&
                relAngle <= 42.0 &&
                crossTrackM <= MAX_CROSS_TRACK_M &&
                isDirectionCompatible
            ) {
                forwardAlerts.add(pt)
            }
        }

        // Ưu tiên chọn điểm cảnh báo quan trọng gần nhất phía trước
        // (Ưu tiên Camera phạt nguội & Biển tốc độ hơn các biển phụ nếu cùng khoảng cách)
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

    private fun isSignDirectionMatched(carBearing: Double, signAngle: Int, dirFlag: Int): Boolean {
        if (dirFlag == 0 || signAngle <= 0 || signAngle > 360) return true
        // Trong dữ liệu eDog Vietmap, góc biển báo có thể lưu theo hướng xe chạy (cùng pha)
        // hoặc hướng mặt biển nhìn ngược chiều xe (lệch 180 độ).
        val diffSame = angleDiff(carBearing, signAngle.toDouble())
        val diffOpposite = angleDiff((carBearing + 180.0) % 360.0, signAngle.toDouble())
        return (diffSame <= 50.0) || (diffOpposite <= 50.0)
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
