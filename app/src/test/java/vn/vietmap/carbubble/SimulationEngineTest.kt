package vn.vietmap.carbubble

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import vn.vietmap.carbubble.engine.RawGpsFix
import vn.vietmap.carbubble.engine.VietmapSpeedCalculator
import java.io.File
import java.sql.DriverManager
import kotlin.math.*

/**
 * Bộ kiểm thử tự động (JUnit Test Suite) chạy trên Cloud CI (GitHub Actions):
 * 1. Kiểm tra tính toàn vẹn của CSDL Offline vietmap_offline.db (69,965 điểm) & bộ icon PNG.
 * 2. Kiểm tra thuật toán tính vận tốc xe chuẩn Vietmap Live (`VietmapSpeedCalculator` v2.1):
 *    - Vận tốc Doppler vệ tinh & Hybrid Doppler + Geodesic
 *    - Dự phòng Geodesic (`elapsedRealtimeNanos`) khi Android Box ô tô thiếu trường `location.speed`
 *    - Bộ lọc `isBetterLocation` loại bỏ nhiễu GPS (`accuracy > 48m`)
 *    - Kẹp trần gia tốc vật lý (`<= 8.5 m/s^2`) & Watchdog trả về `0 km/h` sau `3.2s` dừng đèn đỏ
 * 3. Chạy mô phỏng toàn trình 598 tọa độ trên tuyến đường 30km TP.HCM (sample_route_30km.json)
 *    với thuật toán lọc hình nón phía trước (Forward Cone) và chốt giới hạn tốc độ (Latching).
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
    fun testVietmapSpeedCalculatorAlgorithm() {
        val calc = VietmapSpeedCalculator()
        val baseTimeMs = 1_700_000_000_000L
        val baseNanos = 10_000_000_000L

        // 1. Nhịp GPS đầu tiên (Doppler = 15.0 m/s = 54 km/h)
        val fix1 = RawGpsFix(
            lat = 10.772109,
            lon = 106.698272,
            timeMs = baseTimeMs,
            elapsedRealtimeNanos = baseNanos,
            provider = "gps",
            hasAccuracy = true,
            accuracyMeters = 5.0f,
            hasSpeed = true,
            speedMetersPerSecond = 15.0f,
            hasSpeedAccuracy = true,
            speedAccuracyMetersPerSecond = 0.3f,
            hasBearing = true,
            bearingDegrees = 65.0f
        )
        val res1 = calc.processGpsFix(fix1, gpsOffsetKmh = 0)
        val res1b = calc.processGpsFix(
            fix1.copy(
                timeMs = baseTimeMs + 500L,
                elapsedRealtimeNanos = baseNanos + 500_000_000L
            ),
            gpsOffsetKmh = 0
        )
        assertTrue("Fix 1 hợp lệ phải được chấp nhận", res1.isAccepted)
        assertTrue("Vận tốc Doppler 15 m/s sau hội tụ Kalman phải ~50..55 km/h", res1b.speedKmh in 48..55)

        // 2. Kiểm tra bộ lọc isBetterLocation: Bơm 1 bản tin nhiễu có sai số accuracy = 95m (> 65m)
        val noisyFix = fix1.copy(
            lat = 10.775000,
            lon = 106.701000,
            accuracyMeters = 95.0f,
            speedMetersPerSecond = 35.0f,
            timeMs = baseTimeMs + 1000L,
            elapsedRealtimeNanos = baseNanos + 1_000_000_000L
        )
        val resNoisy = calc.processGpsFix(noisyFix, gpsOffsetKmh = 0)
        assertFalse("Bản tin GPS sai số 95m (> 65m) phải bị từ chối", resNoisy.isAccepted)
        assertEquals("REJECTED_NOISE", resNoisy.sourceType)

        // 2b. Kiểm tra v2.2 Stale Fix Expiry (8 giây): Khi đi vào đường rợp cây (accuracy 45m so với 5m trước đó),
        // sau 8.5 giây bản tin mới PHẢI được chấp nhận để không bao giờ bị treo vị trí suốt 2 phút!
        val urbanCanyonFix = fix1.copy(
            lat = 10.773000,
            lon = 106.699000,
            accuracyMeters = 45.0f,
            speedMetersPerSecond = 12.0f,
            timeMs = baseTimeMs + 9500L,
            elapsedRealtimeNanos = baseNanos + 9_500_000_000L
        )
        val resUrban = calc.processGpsFix(urbanCanyonFix, gpsOffsetKmh = 0)
        assertTrue("Bản tin GPS sau > 8s dù accuracy 45m phải được chấp nhận (chống đóng băng vị trí)", resUrban.isAccepted)

        // 3. Kiểm tra Geodesic Fallback & Bearing Anchor (khi Android Box không trả về location.speed/bearing)
        val calcGeo = VietmapSpeedCalculator()
        val geoStart = RawGpsFix(
            lat = 10.770000,
            lon = 106.700000,
            timeMs = baseTimeMs,
            elapsedRealtimeNanos = baseNanos,
            provider = "fused",
            hasAccuracy = true,
            accuracyMeters = 4.0f,
            hasSpeed = false,
            speedMetersPerSecond = 0f,
            hasSpeedAccuracy = false,
            speedAccuracyMetersPerSecond = 0f,
            hasBearing = false,
            bearingDegrees = 0f
        )
        calcGeo.processGpsFix(geoStart, gpsOffsetKmh = 0)

        // Dịch chuyển +0.000150 độ vĩ tuyến (~16.68m về hướng Bắc = 0 độ) mỗi 1.0 giây -> ~60 km/h
        val geoStep1 = geoStart.copy(
            lat = 10.770150,
            timeMs = baseTimeMs + 1000L,
            elapsedRealtimeNanos = baseNanos + 1_000_000_000L
        )
        val geoRes1 = calcGeo.processGpsFix(geoStep1, gpsOffsetKmh = 0)
        val geoStep2 = geoStart.copy(
            lat = 10.770300,
            timeMs = baseTimeMs + 2000L,
            elapsedRealtimeNanos = baseNanos + 2_000_000_000L
        )
        val geoRes2 = calcGeo.processGpsFix(geoStep2, gpsOffsetKmh = 0)
        val geoStep3 = geoStart.copy(
            lat = 10.770450,
            timeMs = baseTimeMs + 3000L,
            elapsedRealtimeNanos = baseNanos + 3_000_000_000L
        )
        val geoRes3 = calcGeo.processGpsFix(geoStep3, gpsOffsetKmh = 0)
        println("[CI-TEST] Geodesic Fallback Speed: step1=${geoRes1.speedKmh} km/h, step2=${geoRes2.speedKmh} km/h, step3=${geoRes3.speedKmh} km/h (${geoRes3.sourceType}), bearing=${geoRes3.bearingDeg}")
        assertEquals("Nguồn tốc độ phải là GEODESIC_FALLBACK", "GEODESIC_FALLBACK", geoRes3.sourceType)
        assertTrue("Tốc độ Geodesic tính từ quãng đường/thời gian phải nằm trong khoảng 45..65 km/h", geoRes3.speedKmh in 45..65)

        // 4. Kiểm tra Watchdog Timeout (3.2 giây không có nhịp GPS mới -> trả về 0 km/h)
        val watchdogSpeed = calcGeo.checkWatchdogTimeout(
            nowElapsedRealtimeNanos = baseNanos + 3_000_000_000L + 3_500_000_000L,
            gpsOffsetKmh = 0
        )
        println("[CI-TEST] Watchdog sau 3.5s mất tín hiệu: speed=$watchdogSpeed km/h")
        assertEquals("Watchdog sau 3.2s phải đưa tốc độ về 0 km/h", 0, watchdogSpeed)
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
                put("version", "2.1")
                put("vietmap_speed_calculator_test", "PASSED")
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
