package vn.zestech.launcher.engine

import android.content.Context
import org.json.JSONObject
import kotlin.math.*

enum class ManeuverType(val symbol: String, val labelVi: String) {
    STRAIGHT("↑", "Đi thẳng"),
    TURN_RIGHT("↱", "Rẽ phải"),
    TURN_LEFT("↰", "Rẽ trái"),
    SLIGHT_RIGHT("↗", "Chếch phải"),
    SLIGHT_LEFT("↖", "Chếch trái"),
    U_TURN("⮌", "Quay đầu"),
    MERGE_HIGHWAY("⤴", "Nhập làn cao tốc"),
    DESTINATION("🏁", "Đích đến")
}

data class NavLatLng(val lat: Double, val lon: Double)

data class ManeuverStep(
    val waypointIndex: Int,
    val lat: Double,
    val lon: Double,
    val type: ManeuverType,
    val streetName: String,
    val instruction: String
)

data class NavRoutePreset(
    val id: Int,
    val title: String,
    val destinationName: String,
    val totalDistanceKm: Double,
    val points: List<NavLatLng>,
    val maneuvers: List<ManeuverStep>
)

data class NavigationTelemetry(
    val isNavigating: Boolean,
    val routeId: Int,
    val destinationName: String,
    val nextManeuverType: ManeuverType,
    val nextManeuverSymbol: String,
    val nextManeuverLabel: String,
    val nextStreetName: String,
    val nextInstruction: String,
    val distanceToNextTurnM: Int,
    val remainingDistanceKm: Double,
    val etaMinutes: Int,
    val closestWaypointIndex: Int,
    val progressPercent: Int
)

/**
 * Engine dẫn đường Turn-by-Turn tích hợp trực tiếp trên Zestech Launcher & Bong Bóng Nổi:
 * - Quản lý lộ trình polyline, các điểm rẽ (ManeuverStep), tính khoảng cách tới ngã rẽ tiếp theo,
 *   quãng đường còn lại (km) và thời gian dự kiến đến nơi (ETA).
 */
class NavigationEngine(private val context: Context) {

    val presets = ArrayList<NavRoutePreset>()
    var activeRoute: NavRoutePreset? = null
        private set
    var isNavigationActive: Boolean = true
        private set

    private var lastReachedManeuverIdx: Int = -1

    init {
        loadPresetRoutes()
        activeRoute = presets.firstOrNull()
    }

    private fun loadPresetRoutes() {
        presets.clear()

        // 1. Lộ trình 30km TP.HCM -> Hầm Thủ Thiêm -> Mai Chí Thọ -> Cao tốc Long Thành -> QL51 (từ sample_route_30km.json)
        val hcmPoints = ArrayList<NavLatLng>()
        try {
            val jsonText = context.assets.open("sample_route_30km.json").bufferedReader().use { it.readText() }
            val root = JSONObject(jsonText)
            val coords = root.optJSONArray("points") ?: root.getJSONArray("coordinates")
            for (i in 0 until coords.length()) {
                val pair = coords.getJSONArray(i)
                hcmPoints.add(NavLatLng(pair.getDouble(0), pair.getDouble(1)))
            }
        } catch (_: Exception) {
        }
        if (hcmPoints.size < 10) {
            hcmPoints.clear()
            hcmPoints.add(NavLatLng(10.772109, 106.698272))
            hcmPoints.add(NavLatLng(10.771500, 106.706000))
            hcmPoints.add(NavLatLng(10.776500, 106.725000))
            hcmPoints.add(NavLatLng(10.790500, 106.752500))
            hcmPoints.add(NavLatLng(10.795000, 106.805000))
        }

        val n = hcmPoints.size
        fun ptAt(frac: Double): Pair< Int, NavLatLng> {
            val idx = (frac * (n - 1)).roundToInt().coerceIn(0, n - 1)
            return idx to hcmPoints[idx]
        }

        val (i1, p1) = ptAt(0.06)
        val (i2, p2) = ptAt(0.16)
        val (i3, p3) = ptAt(0.32)
        val (i4, p4) = ptAt(0.55)
        val (i5, p5) = ptAt(0.82)
        val (i6, p6) = ptAt(0.99)

        val hcmManeuvers = listOf(
            ManeuverStep(i1, p1.lat, p1.lon, ManeuverType.SLIGHT_RIGHT, "Hầm vượt sông Sài Gòn (Thủ Thiêm)", "Chếch phải tiến vào Hầm Thủ Thiêm (60 km/h)"),
            ManeuverStep(i2, p2.lat, p2.lon, ManeuverType.STRAIGHT, "Đại lộ Mai Chí Thọ", "Đi thẳng theo Đại lộ Mai Chí Thọ (80 km/h)"),
            ManeuverStep(i3, p3.lat, p3.lon, ManeuverType.MERGE_HIGHWAY, "Nút giao An Phú • Cao tốc Long Thành", "Nhập làn vào Cao tốc TP.HCM - Long Thành (100-120 km/h)"),
            ManeuverStep(i4, p4.lat, p4.lon, ManeuverType.STRAIGHT, "Cầu Long Thành • Cao tốc CT01", "Đi thẳng qua Cầu Long Thành, chú ý camera tốc độ"),
            ManeuverStep(i5, p5.lat, p5.lon, ManeuverType.TURN_RIGHT, "Nút giao Quốc lộ 51", "Rẽ phải xuống nút giao Quốc lộ 51 hướng Long Thành"),
            ManeuverStep(i6, p6.lat, p6.lon, ManeuverType.DESTINATION, "Sân bay Quốc tế Long Thành", "Bạn đã đến đích: Khu vực Sân bay Long Thành")
        )

        presets.add(
            NavRoutePreset(
                id = 0,
                title = "Bến Thành → Cao tốc Long Thành (30 km)",
                destinationName = "Cao tốc Long Thành • QL51",
                totalDistanceKm = computeTotalKm(hcmPoints),
                points = hcmPoints,
                maneuvers = hcmManeuvers
            )
        )

        // 2. Lộ trình Dinh Độc Lập -> Điện Biên Phủ -> Cầu Sài Gòn -> Vinhomes Landmark 81
        val sgLandmarkPts = interpolateWaypoints(
            listOf(
                NavLatLng(10.77699, 106.69530),
                NavLatLng(10.78240, 106.69980),
                NavLatLng(10.79320, 106.70950),
                NavLatLng(10.79910, 106.71860),
                NavLatLng(10.79660, 106.72210),
                NavLatLng(10.79490, 106.72180)
            ),
            stepsPerSegment = 18
        )
        presets.add(
            NavRoutePreset(
                id = 1,
                title = "Dinh Độc Lập → Landmark 81 (6.5 km)",
                destinationName = "Vinhomes Landmark 81",
                totalDistanceKm = computeTotalKm(sgLandmarkPts),
                points = sgLandmarkPts,
                maneuvers = listOf(
                    ManeuverStep(15, sgLandmarkPts[15].lat, sgLandmarkPts[15].lon, ManeuverType.TURN_RIGHT, "Đường Điện Biên Phủ", "Rẽ phải vào đường Điện Biên Phủ"),
                    ManeuverStep(45, sgLandmarkPts[45].lat, sgLandmarkPts[45].lon, ManeuverType.STRAIGHT, "Cầu Sài Gòn", "Đi thẳng lên Cầu Sài Gòn (Chú ý biển 60 km/h)"),
                    ManeuverStep(70, sgLandmarkPts[70].lat, sgLandmarkPts[70].lon, ManeuverType.TURN_RIGHT, "Đường Nguyễn Hữu Cảnh", "Rẽ phải vào nhánh dẫn Vinhomes Central Park"),
                    ManeuverStep(sgLandmarkPts.size - 1, sgLandmarkPts.last().lat, sgLandmarkPts.last().lon, ManeuverType.DESTINATION, "Landmark 81 Tower", "Bạn đã đến Landmark 81")
                )
            )
        )

        // 3. Lộ trình Hà Nội: Hồ Gươm -> Âu Cơ -> Cầu Nhật Tân -> Võ Nguyên Giáp -> Sân bay Nội Bài
        val hnNoiBaiPts = interpolateWaypoints(
            listOf(
                NavLatLng(21.02851, 105.85444),
                NavLatLng(21.04520, 105.84510),
                NavLatLng(21.07650, 105.81800),
                NavLatLng(21.09400, 105.82150),
                NavLatLng(21.15500, 105.80500),
                NavLatLng(21.21870, 105.80410)
            ),
            stepsPerSegment = 24
        )
        presets.add(
            NavRoutePreset(
                id = 2,
                title = "Hồ Gươm → Sân bay Nội Bài (26.8 km)",
                destinationName = "Sân bay Quốc tế Nội Bài",
                totalDistanceKm = computeTotalKm(hnNoiBaiPts),
                points = hnNoiBaiPts,
                maneuvers = listOf(
                    ManeuverStep(20, hnNoiBaiPts[20].lat, hnNoiBaiPts[20].lon, ManeuverType.SLIGHT_LEFT, "Đường Yên Phụ • Âu Cơ", "Đi thẳng theo trục Yên Phụ - Âu Cơ"),
                    ManeuverStep(52, hnNoiBaiPts[52].lat, hnNoiBaiPts[52].lon, ManeuverType.MERGE_HIGHWAY, "Cầu Nhật Tân", "Nhập làn lên Cầu Nhật Tân (80 km/h)"),
                    ManeuverStep(85, hnNoiBaiPts[85].lat, hnNoiBaiPts[85].lon, ManeuverType.STRAIGHT, "Đại lộ Võ Nguyên Giáp", "Đi thẳng Đại lộ Võ Nguyên Giáp (90 km/h)"),
                    ManeuverStep(hnNoiBaiPts.size - 1, hnNoiBaiPts.last().lat, hnNoiBaiPts.last().lon, ManeuverType.DESTINATION, "Nhà ga T1/T2 Nội Bài", "Bạn đã đến Sân bay Quốc tế Nội Bài")
                )
            )
        )
    }

    fun selectPresetRoute(presetId: Int): NavRoutePreset {
        val found = presets.find { it.id == presetId } ?: presets.first()
        activeRoute = found
        isNavigationActive = true
        lastReachedManeuverIdx = -1
        return found
    }

    fun setCustomDestination(destName: String, startLat: Double, startLon: Double, destLat: Double, destLon: Double): NavRoutePreset {
        val mid1Lat = startLat + (destLat - startLat) * 0.35 + 0.003
        val mid1Lon = startLon + (destLon - startLon) * 0.30 - 0.002
        val mid2Lat = startLat + (destLat - startLat) * 0.72 - 0.002
        val mid2Lon = startLon + (destLon - startLon) * 0.75 + 0.003

        val pts = interpolateWaypoints(
            listOf(
                NavLatLng(startLat, startLon),
                NavLatLng(mid1Lat, mid1Lon),
                NavLatLng(mid2Lat, mid2Lon),
                NavLatLng(destLat, destLon)
            ),
            stepsPerSegment = 25
        )
        val totalKm = max(0.5, computeTotalKm(pts))
        val customRoute = NavRoutePreset(
            id = 99,
            title = "Lộ trình tới $destName (${String.format("%.1f", totalKm)} km)",
            destinationName = destName,
            totalDistanceKm = totalKm,
            points = pts,
            maneuvers = listOf(
                ManeuverStep(22, pts[22].lat, pts[22].lon, ManeuverType.TURN_RIGHT, "Trục đường chính hướng $destName", "Rẽ phải vào trục đường chính"),
                ManeuverStep(48, pts[48].lat, pts[48].lon, ManeuverType.SLIGHT_LEFT, "Đường dẫn trung tâm", "Chếch trái tiếp tục theo lộ trình dẫn đường"),
                ManeuverStep(pts.size - 1, pts.last().lat, pts.last().lon, ManeuverType.DESTINATION, destName, "Bạn đã đến $destName")
            )
        )
        activeRoute = customRoute
        isNavigationActive = true
        lastReachedManeuverIdx = -1
        return customRoute
    }

    fun toggleNavigation(enabled: Boolean) {
        isNavigationActive = enabled
    }

    fun computeTelemetry(carLat: Double, carLon: Double, currentSpeedKmh: Int): NavigationTelemetry {
        val route = activeRoute ?: presets.first()
        val pts = route.points
        if (!isNavigationActive || pts.isEmpty()) {
            return NavigationTelemetry(
                isNavigating = false,
                routeId = route.id,
                destinationName = route.destinationName,
                nextManeuverType = ManeuverType.STRAIGHT,
                nextManeuverSymbol = "↑",
                nextManeuverLabel = "Dẫn đường tắt",
                nextStreetName = route.destinationName,
                nextInstruction = "Chạm chọn lộ trình để bắt đầu dẫn đường",
                distanceToNextTurnM = 0,
                remainingDistanceKm = 0.0,
                etaMinutes = 0,
                closestWaypointIndex = 0,
                progressPercent = 0
            )
        }

        // Tìm điểm gần xe nhất trên lộ trình
        var closestIdx = 0
        var minDist = Double.MAX_VALUE
        for (i in pts.indices) {
            val d = haversineMeters(carLat, carLon, pts[i].lat, pts[i].lon)
            if (d < minDist) {
                minDist = d
                closestIdx = i
            }
        }

        // Tìm điểm rẽ tiếp theo phía trước
        val nextManeuver = route.maneuvers.firstOrNull { it.waypointIndex > closestIdx + 1 }
            ?: route.maneuvers.last()

        val distToTurnM = computePathMetersBetween(pts, closestIdx, nextManeuver.waypointIndex, carLat, carLon)
            .roundToInt()
            .coerceAtLeast(10)

        val remainingMeters = computePathMetersBetween(pts, closestIdx, pts.size - 1, carLat, carLon)
        val remainingKm = (remainingMeters / 1000.0).coerceAtLeast(0.1)

        val effectiveSpeedKmh = if (currentSpeedKmh >= 20) currentSpeedKmh.toDouble() else 48.0
        val etaMin = ((remainingKm / effectiveSpeedKmh) * 60.0).roundToInt().coerceAtLeast(1)
        val progressPct = ((closestIdx.toDouble() / max(1, pts.size - 1)) * 100.0).roundToInt().coerceIn(0, 100)

        return NavigationTelemetry(
            isNavigating = true,
            routeId = route.id,
            destinationName = route.destinationName,
            nextManeuverType = nextManeuver.type,
            nextManeuverSymbol = nextManeuver.type.symbol,
            nextManeuverLabel = nextManeuver.type.labelVi,
            nextStreetName = nextManeuver.streetName,
            nextInstruction = nextManeuver.instruction,
            distanceToNextTurnM = distToTurnM,
            remainingDistanceKm = remainingKm,
            etaMinutes = etaMin,
            closestWaypointIndex = closestIdx,
            progressPercent = progressPct
        )
    }

    private fun computePathMetersBetween(
        pts: List<NavLatLng>,
        fromIdx: Int,
        toIdx: Int,
        carLat: Double,
        carLon: Double
    ): Double {
        if (pts.isEmpty() || toIdx <= fromIdx) {
            val target = pts.getOrNull(toIdx.coerceIn(0, pts.size - 1)) ?: return 0.0
            return haversineMeters(carLat, carLon, target.lat, target.lon)
        }
        var sum = haversineMeters(carLat, carLon, pts[fromIdx].lat, pts[fromIdx].lon)
        for (i in fromIdx until min(toIdx, pts.size - 1)) {
            sum += haversineMeters(pts[i].lat, pts[i].lon, pts[i + 1].lat, pts[i + 1].lon)
        }
        return sum
    }

    private fun computeTotalKm(pts: List<NavLatLng>): Double {
        var sumM = 0.0
        for (i in 0 until pts.size - 1) {
            sumM += haversineMeters(pts[i].lat, pts[i].lon, pts[i + 1].lat, pts[i + 1].lon)
        }
        return sumM / 1000.0
    }

    private fun interpolateWaypoints(anchors: List<NavLatLng>, stepsPerSegment: Int): List<NavLatLng> {
        val out = ArrayList<NavLatLng>()
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i + 1]
            for (s in 0 until stepsPerSegment) {
                val t = s.toDouble() / stepsPerSegment
                out.add(NavLatLng(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t))
            }
        }
        out.add(anchors.last())
        return out
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        return 2.0 * r * asin(sqrt(a))
    }
}
