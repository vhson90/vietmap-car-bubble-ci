package vn.zestech.launcher.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import vn.zestech.launcher.engine.EdogPoint
import vn.zestech.launcher.engine.ManeuverStep
import vn.zestech.launcher.engine.NavLatLng
import kotlin.math.*

/**
 * Bản đồ dẫn đường 3D Automotive Dark Theme nhúng trực tiếp vào màn hình chính Zestech Launcher:
 * - Vẽ lộ trình Turn-by-Turn phát sáng (Neon Cyan Route Polyline + Directional Chevrons).
 * - Vẽ trực tiếp các điểm Biển giới hạn tốc độ (30..120 km/h), Camera phạt nguội, Khu dân cư quanh xe theo tọa độ GPS thực.
 * - Vẽ nón quét Radar biến thiên (240m/330m/450m) và biểu tượng xe 3D xoay theo góc lái (Heading-Up hoặc North-Up).
 * - Hỗ trợ cử chỉ chạm kéo (Pan), phóng to/thu nhỏ (Zoom) và tự động căn tâm xe.
 */
class NavigationMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var carLat: Double = 10.772109
    private var carLon: Double = 106.698272
    private var carBearing: Float = 65f
    private var carSpeedKmh: Int = 0
    private var currentSpeedLimit: Int = 60
    private var adaptiveScanMeters: Int = 330
    private var currentRoadName: String = "Đường Lê Lợi"

    private var routePoints: List<NavLatLng> = emptyList()
    private var maneuvers: List<ManeuverStep> = emptyList()
    private var closestWaypointIdx: Int = 0
    private var nearbyEdogPoints: List<EdogPoint> = emptyList()
    private var activeAlertId: Int = -1

    var isHeadingUpMode: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    // Tỷ lệ phóng bản đồ (mét trên mỗi pixel): mặc định ~2.6m/px (hiển thị ~1.2km chiều dọc)
    private var metersPerPixel: Float = 2.6f
    private var panOffsetX: Float = 0f
    private var panOffsetY: Float = 0f
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#080E1A")
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#132038")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    private val secondaryRoadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#16243E")
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val routeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#440EA5E9")
        strokeWidth = 26f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val routeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        strokeWidth = 11f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val passedRoutePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val radarFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F38BDF8")
        style = Paint.Style.FILL
    }
    private val radarStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6638BDF8")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val signWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val signRedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF4444")
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val cameraBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B")
        style = Paint.Style.FILL
    }
    private val signTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        textSize = 21f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC0F172A")
        style = Paint.Style.FILL
    }
    private val labelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5538BDF8")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        textSize = 20f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val carHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3338BDF8")
        style = Paint.Style.FILL
    }
    private val carBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.FILL
    }
    private val carStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
    }

    private val routePathPassed = Path()
    private val routePathAhead = Path()
    private val carArrowPath = Path()
    private val radarRect = RectF()

    fun updateMapState(
        lat: Double,
        lon: Double,
        bearingDeg: Float,
        speedKmh: Int,
        speedLimit: Int,
        scanM: Int,
        roadName: String,
        route: List<NavLatLng>,
        maneuverList: List<ManeuverStep>,
        closestIdx: Int,
        edogPoints: List<EdogPoint>,
        upcomingAlertId: Int
    ) {
        carLat = lat
        carLon = lon
        carBearing = bearingDeg
        carSpeedKmh = speedKmh
        currentSpeedLimit = speedLimit
        adaptiveScanMeters = scanM
        currentRoadName = roadName
        routePoints = route
        maneuvers = maneuverList
        closestWaypointIdx = closestIdx
        nearbyEdogPoints = edogPoints
        activeAlertId = upcomingAlertId
        invalidate()
    }

    fun zoomIn() {
        metersPerPixel = (metersPerPixel * 0.75f).coerceIn(0.8f, 12.0f)
        invalidate()
    }

    fun zoomOut() {
        metersPerPixel = (metersPerPixel * 1.35f).coerceIn(0.8f, 12.0f)
        invalidate()
    }

    fun recenterCar() {
        panOffsetX = 0f
        panOffsetY = 0f
        invalidate()
    }

    private fun latLonToScreen(targetLat: Double, targetLon: Double, cx: Float, cy: Float): PointF {
        val dNorthM = (targetLat - carLat) * 111_320.0
        val dEastM = (targetLon - carLon) * 111_320.0 * cos(Math.toRadians(carLat))

        val pxEast = (dEastM / metersPerPixel).toFloat()
        val pxNorth = (dNorthM / metersPerPixel).toFloat()

        return if (isHeadingUpMode) {
            val rad = Math.toRadians(carBearing.toDouble())
            val cosB = cos(rad).toFloat()
            val sinB = sin(rad).toFloat()
            // Xoay sao cho hướng di chuyển của xe luôn hướng thẳng lên đỉnh màn hình (0 độ màn hình)
            val rx = pxEast * cosB - pxNorth * sinB
            val ry = -(pxEast * sinB + pxNorth * cosB)
            PointF(cx + rx + panOffsetX, cy + ry + panOffsetY)
        } else {
            PointF(cx + pxEast + panOffsetX, cy - pxNorth + panOffsetY)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 10f || h <= 10f) return

        // Tâm xe nằm ở vị trí 68% chiều cao khung bản đồ (để nhường không gian nhìn xa phía trước mũi xe)
        val cx = w * 0.50f
        val cy = if (isHeadingUpMode) h * 0.68f else h * 0.55f

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 1. Vẽ lưới tọa độ radar không gian
        val gridSpacingPx = (200f / metersPerPixel).coerceIn(48f, 160f)
        var gx = (cx + panOffsetX) % gridSpacingPx
        while (gx < w) {
            canvas.drawLine(gx, 0f, gx, h, gridPaint)
            gx += gridSpacingPx
        }
        var gy = (cy + panOffsetY) % gridSpacingPx
        while (gy < h) {
            canvas.drawLine(0f, gy, w, gy, gridPaint)
            gy += gridSpacingPx
        }

        // Vẽ các vòng tròn cự ly Radar quanh xe (150m, 300m, 450m)
        val carScreenX = cx + panOffsetX
        val carScreenY = cy + panOffsetY
        for (ringM in listOf(150, 300, 450)) {
            val rPx = ringM / metersPerPixel
            canvas.drawCircle(carScreenX, carScreenY, rPx, gridPaint)
        }

        // 2. Vẽ nón quét biển báo biến thiên (Adaptive Lookahead Radar Cone) phía trước mũi xe
        val scanRadiusPx = (adaptiveScanMeters / metersPerPixel).coerceIn(60f, max(w, h))
        radarRect.set(
            carScreenX - scanRadiusPx,
            carScreenY - scanRadiusPx,
            carScreenX + scanRadiusPx,
            carScreenY + scanRadiusPx
        )
        val sweepStartAngle = if (isHeadingUpMode) -90f - 36f else (carBearing - 90f - 36f)
        canvas.drawArc(radarRect, sweepStartAngle, 72f, true, radarFillPaint)
        canvas.drawArc(radarRect, sweepStartAngle, 72f, true, radarStrokePaint)

        // 3. Vẽ lộ trình dẫn đường Turn-by-Turn (Route Polyline)
        if (routePoints.size >= 2) {
            routePathPassed.reset()
            routePathAhead.reset()

            for (i in routePoints.indices) {
                val pt = latLonToScreen(routePoints[i].lat, routePoints[i].lon, cx, cy)
                if (i <= closestWaypointIdx) {
                    if (i == 0) routePathPassed.moveTo(pt.x, pt.y) else routePathPassed.lineTo(pt.x, pt.y)
                }
                if (i >= closestWaypointIdx) {
                    if (i == closestWaypointIdx) routePathAhead.moveTo(pt.x, pt.y) else routePathAhead.lineTo(pt.x, pt.y)
                }
            }
            canvas.drawPath(routePathPassed, passedRoutePaint)
            canvas.drawPath(routePathAhead, routeGlowPaint)
            canvas.drawPath(routePathAhead, routeLinePaint)

            // Vẽ các điểm rẽ (Maneuver Pins) trên lộ trình
            for (m in maneuvers) {
                if (m.waypointIndex >= closestWaypointIdx) {
                    val mp = latLonToScreen(m.lat, m.lon, cx, cy)
                    if (mp.x in -60f..(w + 60f) && mp.y in -60f..(h + 60f)) {
                        canvas.drawCircle(mp.x, mp.y, 16f, labelBgPaint)
                        canvas.drawCircle(mp.x, mp.y, 16f, routeLinePaint)
                        canvas.drawText(m.type.symbol, mp.x, mp.y + 7f, labelTextPaint)
                    }
                }
            }
        }

        // 4. Vẽ các điểm Biển báo tốc độ & Camera phạt nguội thực tế từ OfflineSpatialEngine
        for (edog in nearbyEdogPoints) {
            val sp = latLonToScreen(edog.lat, edog.lon, cx, cy)
            if (sp.x !in 20f..(w - 20f) || sp.y !in 20f..(h - 20f)) continue

            val isHighlighted = (edog.id == activeAlertId)
            val radius = if (isHighlighted) 24f else 19f

            if (isHighlighted) {
                // Vòng sáng cảnh báo cho biển/camera sắp tới
                val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#66EF4444")
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(sp.x, sp.y, radius + 11f, pulsePaint)
            }

            if (edog.type in listOf(10, 11, 12)) {
                // Điểm Camera phạt nguội / giao thông
                canvas.drawCircle(sp.x, sp.y, radius, cameraBgPaint)
                canvas.drawCircle(sp.x, sp.y, radius, carStrokePaint)
                val label = if (edog.speedLimit > 0) "📷${edog.speedLimit}" else "📷"
                signTextPaint.textSize = if (edog.speedLimit > 0) 15f else 18f
                canvas.drawText(label, sp.x, sp.y + 6f, signTextPaint)
            } else {
                // Biển báo tốc độ chuẩn vòng tròn đỏ nền trắng
                canvas.drawCircle(sp.x, sp.y, radius, signWhitePaint)
                canvas.drawCircle(sp.x, sp.y, radius, signRedBorderPaint)
                val txt = when {
                    edog.speedLimit > 0 -> edog.speedLimit.toString()
                    edog.type == 2 -> "KDC"
                    edog.type == 3 -> "R.KDC"
                    else -> "!"
                }
                signTextPaint.textSize = if (txt.length >= 3) 14f else 18f
                canvas.drawText(txt, sp.x, sp.y + 6f, signTextPaint)
            }
        }

        // 5. Vẽ biểu tượng Xe 3D Navigation Puck tại vị trí GPS của xe
        canvas.save()
        canvas.translate(carScreenX, carScreenY)
        if (!isHeadingUpMode) {
            canvas.rotate(carBearing)
        }
        canvas.drawCircle(0f, 0f, 34f, carHaloPaint)

        carArrowPath.reset()
        carArrowPath.moveTo(0f, -24f)
        carArrowPath.lineTo(18f, 20f)
        carArrowPath.lineTo(0f, 11f)
        carArrowPath.lineTo(-18f, 20f)
        carArrowPath.close()

        canvas.drawPath(carArrowPath, carBodyPaint)
        canvas.drawPath(carArrowPath, carStrokePaint)
        canvas.restore()

        // 6. Nhãn tên đường hiện tại dưới chân xe
        if (currentRoadName.isNotEmpty()) {
            val pillW = (labelTextPaint.measureText(currentRoadName) + 36f).coerceAtLeast(140f)
            val pillRect = RectF(
                carScreenX - pillW / 2f,
                carScreenY + 40f,
                carScreenX + pillW / 2f,
                carScreenY + 74f
            )
            canvas.drawRoundRect(pillRect, 16f, 16f, labelBgPaint)
            canvas.drawRoundRect(pillRect, 16f, 16f, labelStrokePaint)
            canvas.drawText(currentRoadName, carScreenX, carScreenY + 63f, labelTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                if (abs(dx) > 3f || abs(dy) > 3f) {
                    panOffsetX += dx
                    panOffsetY += dy
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
