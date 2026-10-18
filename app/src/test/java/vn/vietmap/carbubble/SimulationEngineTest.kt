package vn.vietmap.carbubble

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import kotlin.math.*

/**
 * Bộ kiểm thử tự động (JUnit Test Suite) chạy trên Cloud CI (GitHub Actions):
 * 1. Kiểm tra tính toàn vẹn của CSDL Offline vietmap_offline.db (69,965 điểm) & bộ icon PNG.
 * 2. Chạy mô phỏng toàn trình 598 tọa độ trên tuyến đường 30km TP.HCM (sample_route_30km.json)
 *    với thuật toán lọc hình nón phía trước (Forward Cone) và chốt giới hạn tốc độ (Latching).
 * 3. Xuất báo cáo kết quả JSON (build/reports/simulation_ci_report.json).
 */
class SimulationEngineTest {

    @Test
    fun testDatabaseAndAssetsIntegrity() {
        val assetsDir = File("src/main/assets")
        assertTrue("Thư mục assets phải tồn tại", assetsDir.exists())

        val dbFile = File(assetsDir, "vietmap_offline.db")
        assertTrue("File vietmap_offline.db phải tồn tại", dbFile.exists())
        assertTrue("Dung lượng vietmap_offline.db phải > 5MB", dbFile.length() > 5_000_000L)

        // Kiểm tra bộ icon tốc độ
        val speedSigns = listOf(30, 40, 50, 60, 70, 80, 90, 100, 120)
        for (s in speedSigns) {
            val icon = File(assetsDir, "speed/$s.png")
            assertTrue("Thiếu icon biển tốc độ speed/$s.png", icon.exists() && icon.length() > 100)
        }

        // Kiểm tra kết nối SQLite và tổng số điểm
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT COUNT(*) FROM edog_points")
                assertTrue(rs.next())
                val count = rs.getInt(1)
                println("[CI-TEST] Tổng số điểm trong vietmap_offline.db: $count")
                assertTrue("Số lượng điểm toàn quốc phải >= 69,900", count >= 69900)
            }
        }
    }

    @Test
    fun test30KmHcmcRouteSimulationAndAlerts() {
        val assetsDir = File("src/main/assets")
        val dbFile = File(assetsDir, "vietmap_offline.db")
        val routeFile = File(assetsDir, "sample_route_30km.json")
        assertTrue(routeFile.exists())

        val routeJson = JSONObject(routeFile.readText(Charsets.UTF_8))
        val coords = routeJson.optJSONArray("points") ?: routeJson.getJSONArray("coordinates")
        assertTrue("Tuyến đường 30km phải có trên 500 tọa độ", coords.length() > 500)

        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            val sql = """
                SELECT id, lat, lon, type, speed_limit, dir_flag, angle
                FROM edog_points
                WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
            """.trimIndent()
            val pstmt = conn.prepareStatement(sql)

            var latchedLimit = 60
            var lastLatchedId = -1
            val speedTransitions = mutableListOf<String>()
            val detectedCameras = linkedSetOf<String>()
            var totalQueryNanoseconds = 0L

            for (i in 0 until coords.length() - 1) {
                val p1 = coords.getJSONArray(i)
                val p2 = coords.getJSONArray(i + 1)
                val lat = p1.getDouble(0)
                val lon = p1.getDouble(1)
                val nextLat = p2.getDouble(0)
                val nextLon = p2.getDouble(1)
                val bearing = calculateBearing(lat, lon, nextLat, nextLon)

                val t0 = System.nanoTime()
                pstmt.setDouble(1, lat - 0.0055)
                pstmt.setDouble(2, lat + 0.0055)
                pstmt.setDouble(3, lon - 0.0055)
                pstmt.setDouble(4, lon + 0.0055)

                pstmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val id = rs.getInt(1)
                        val pLat = rs.getDouble(2)
                        val pLon = rs.getDouble(3)
                        val type = rs.getInt(4)
                        val speedLimit = rs.getInt(5)
                        val dirFlag = rs.getInt(6)
                        val angle = rs.getInt(7)

                        val distM = haversineMeters(lat, lon, pLat, pLon)
                        val ptBearing = calculateBearing(lat, lon, pLat, pLon)
                        val relAngle = angleDiff(bearing, ptBearing)
                        val crossTrackM = abs(distM * sin(Math.toRadians(relAngle)))
                        val dirOk = isSignDirectionMatched(bearing, angle, dirFlag)

                        if (distM <= 65.0 && crossTrackM <= 35.0 && dirOk && id != lastLatchedId) {
                            if (type == 1 && speedLimit >= 30 && speedLimit != latchedLimit) {
                                speedTransitions.add("Waypoint #$i ($lat, $lon): $latchedLimit -> $speedLimit km/h (Sign ID=$id)")
                                latchedLimit = speedLimit
                                lastLatchedId = id
                            }
                        }

                        if (distM in 12.0..450.0 && relAngle <= 42.0 && crossTrackM <= 35.0 && dirOk) {
                            if (type == 4 || type == 9 || type == 12) {
                                detectedCameras.add("ID=$id (Type=$type, Limit=$speedLimit) tại ($pLat, $pLon)")
                            }
                        }
                    }
                }
                totalQueryNanoseconds += (System.nanoTime() - t0)
            }

            val avgQueryMs = (totalQueryNanoseconds / coords.length()) / 1_000_000.0
            println("[CI-TEST] Số lần chuyển biển tốc độ giới hạn dọc 30km: ${speedTransitions.size}")
            speedTransitions.forEach { println("   -> $it") }
            println("[CI-TEST] Số trạm Camera phát hiện dọc tuyến 30km: ${detectedCameras.size}")
            println(String.format("[CI-TEST] Thời gian truy vấn SQLite trung bình: %.3f ms/nhịp GPS", avgQueryMs))

            assertTrue("Phải phát hiện ít nhất 5 lần chuyển đổi tốc độ giới hạn trên tuyến 30km", speedTransitions.size >= 5)
            assertTrue("Phải phát hiện ít nhất 10 camera giám sát/phạt nguội trên tuyến 30km", detectedCameras.size >= 10)
            assertTrue("Thời gian truy vấn SQLite trung bình phải < 15ms", avgQueryMs < 15.0)

            // Lưu báo cáo JSON để upload lên GitHub Actions Artifact
            val reportDir = File("build/reports")
            reportDir.mkdirs()
            val reportJson = JSONObject().apply {
                put("route_waypoints", coords.length())
                put("speed_transitions_count", speedTransitions.size)
                put("speed_transitions", speedTransitions)
                put("detected_cameras_count", detectedCameras.size)
                put("avg_sqlite_query_ms", avgQueryMs)
                put("status", "PASSED")
            }
            File(reportDir, "simulation_ci_report.json").writeText(reportJson.toString(2), Charsets.UTF_8)
        }
    }

    private fun isSignDirectionMatched(carBearing: Double, signAngle: Int, dirFlag: Int): Boolean {
        if (dirFlag == 0 || signAngle <= 0 || signAngle > 360) return true
        val diffSame = angleDiff(carBearing, signAngle.toDouble())
        val diffOpposite = angleDiff((carBearing + 180.0) % 360.0, signAngle.toDouble())
        return (diffSame <= 50.0) || (diffOpposite <= 50.0)
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
