package vn.zestech.launcher.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Vẽ chuẩn giao diện gốc Zestech Launcher (Phiên bản Original / Công Nghệ / Công Viên + 72 Màu Phong Thủy):
 * - Bầu trời + Dãy tòa nhà thành phố phát sáng (City Skyline)
 * - Mặt đường cao tốc 3D chạy về đường chân trời với hiệu ứng chuyển động theo tốc độ xe
 * - Hàng cây xanh hai bên đường (khi chọn Hình Nền Công Viên)
 * - Mô hình xe SUV Zestech màu trắng nhìn từ phía sau ở chính giữa làn đường (kèm biển số 30H-985.21 & đèn hậu LED)
 * - Hiệu ứng cảnh báo mặt đường đỏ khi xe vượt quá tốc độ giới hạn
 */
class ZestechRoadSceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class WallpaperType {
        ORIGINAL,   // Hình Nền Cơ Bản (Vàng Hoàng Kim Zestech)
        TECH,       // Hình Nền Công Nghệ (Xanh Neon Cyber)
        PARK        // Hình Nền Công Viên (Xanh Lá Tự Nhiên)
    }

    var wallpaperType: WallpaperType = WallpaperType.ORIGINAL
        set(value) {
            field = value
            invalidate()
        }

    // Màu phong thủy chủ đạo (có thể chọn từ 72 màu phong thủy)
    var themeColor: Int = Color.parseColor("#F59E0B")
        set(value) {
            field = value
            invalidate()
        }

    var currentSpeedKmh: Int = 0
    var currentLimitKmh: Int = 60
    var isOverspeed: Boolean = false
    private var animPhase: Float = 0f

    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buildingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val windowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val carPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Chiều cao tương đối của các tòa nhà skyline
    private val skylineHeights = floatArrayOf(
        0.22f, 0.30f, 0.18f, 0.38f, 0.27f, 0.44f, 0.33f, 0.25f, 0.48f,
        0.36f, 0.56f, 0.42f, 0.29f, 0.51f, 0.35f, 0.24f, 0.39f, 0.31f,
        0.45f, 0.28f, 0.34f, 0.20f, 0.26f
    )

    fun updateTelemetry(speedKmh: Int, limitKmh: Int, overspeed: Boolean) {
        currentSpeedKmh = speedKmh
        currentLimitKmh = limitKmh
        isOverspeed = overspeed
        val step = (speedKmh.coerceAtLeast(15)) * 0.008f
        animPhase = (animPhase + step) % 1.0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val horizonY = h * 0.55f
        val centerX = w * 0.5f

        val activeAccent = when {
            isOverspeed -> Color.parseColor("#EF4444")
            wallpaperType == WallpaperType.TECH -> themeColor
            wallpaperType == WallpaperType.PARK -> Color.parseColor("#10B981")
            else -> themeColor
        }

        // 1. Vẽ bầu trời & hào quang chân trời
        val skyTop = when (wallpaperType) {
            WallpaperType.ORIGINAL -> Color.parseColor("#141008")
            WallpaperType.TECH -> Color.parseColor("#040B1E")
            WallpaperType.PARK -> Color.parseColor("#06281E")
        }
        val skyMid = blendColors(skyTop, activeAccent, 0.42f)
        val skyHorizon = blendColors(activeAccent, Color.WHITE, 0.28f)

        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, horizonY,
            intArrayOf(skyTop, skyMid, skyHorizon),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, horizonY, skyPaint)

        // Hào quang tỏa từ tâm chân trời (giống ảnh gốc Zestech)
        glowPaint.shader = RadialGradient(
            centerX, horizonY, w * 0.52f,
            intArrayOf(
                Color.argb(170, Color.red(activeAccent), Color.green(activeAccent), Color.blue(activeAccent)),
                Color.argb(65, Color.red(activeAccent), Color.green(activeAccent), Color.blue(activeAccent)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, glowPaint)

        // 2. Vẽ dãy tòa nhà thành phố (City Skyline)
        drawCitySkyline(canvas, w, h, horizonY, activeAccent)

        // 3. Nếu là chủ đề Công Viên -> vẽ hàng cây xanh hai bên chân trời
        if (wallpaperType == WallpaperType.PARK) {
            drawParkTrees(canvas, w, horizonY)
        }

        // 4. Vẽ mặt đường cao tốc 3D phối cảnh
        drawPerspectiveRoad(canvas, w, h, centerX, horizonY, activeAccent)

        // 5. Vẽ chiếc xe SUV màu trắng Zestech ở chính giữa
        drawZestechSuvCar(canvas, centerX, h * 0.695f, w, h, activeAccent)
    }

    private fun drawCitySkyline(canvas: Canvas, w: Float, h: Float, horizonY: Float, accent: Int) {
        val count = skylineHeights.size
        val bWidth = w / count

        // Lớp tòa nhà mờ phía xa
        buildingPaint.shader = null
        buildingPaint.color = blendColors(Color.BLACK, accent, 0.35f)
        buildingPaint.alpha = 155
        for (i in 0 until count) {
            val bh = horizonY * skylineHeights[(i + 5) % count] * 0.95f
            val left = i * bWidth - bWidth * 0.2f
            val right = left + bWidth * 0.95f
            canvas.drawRect(left, horizonY - bh, right, horizonY, buildingPaint)
        }

        // Lớp tòa nhà chính phía trước
        val frontColorTop = blendColors(Color.parseColor("#0F172A"), accent, 0.45f)
        val frontColorBottom = blendColors(Color.parseColor("#1E293B"), accent, 0.72f)
        buildingPaint.shader = LinearGradient(
            0f, horizonY * 0.25f, 0f, horizonY,
            frontColorTop, frontColorBottom, Shader.TileMode.CLAMP
        )
        windowPaint.color = blendColors(accent, Color.WHITE, 0.55f)

        for (i in 0 until count) {
            val bh = horizonY * skylineHeights[i] * 0.82f
            val left = i * bWidth + 2f
            val right = (i + 1) * bWidth - 2f
            val top = horizonY - bh
            canvas.drawRect(left, top, right, horizonY, buildingPaint)

            // Vẽ vạch sáng cửa sổ tòa nhà kiểu Zestech
            windowPaint.alpha = 110
            var wy = top + 8f
            while (wy < horizonY - 10f) {
                canvas.drawRect(left + 5f, wy, right - 5f, wy + 2.5f, windowPaint)
                wy += 9f
            }
        }
    }

    private fun drawParkTrees(canvas: Canvas, w: Float, horizonY: Float) {
        val treePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radii = floatArrayOf(42f, 34f, 48f, 30f, 38f, 28f)
        for (side in intArrayOf(-1, 1)) {
            for (i in radii.indices) {
                val distFromEdge = i * (w * 0.06f) + 28f
                val cx = if (side < 0) distFromEdge else w - distFromEdge
                val cy = horizonY - radii[i] * 0.55f
                treePaint.color = if (i % 2 == 0) Color.parseColor("#15803D") else Color.parseColor("#22C55E")
                canvas.drawCircle(cx, cy, radii[i], treePaint)
                treePaint.color = Color.parseColor("#4ADE80")
                treePaint.alpha = 90
                canvas.drawCircle(cx - side * 6f, cy - 6f, radii[i] * 0.65f, treePaint)
            }
        }
    }

    private fun drawPerspectiveRoad(
        canvas: Canvas,
        w: Float,
        h: Float,
        centerX: Float,
        horizonY: Float,
        accent: Int
    ) {
        val roadTopHalf = w * 0.055f
        val roadBottomHalf = w * 0.68f

        // Nền mặt đường hai bên
        val groundDark = blendColors(Color.parseColor("#090D16"), accent, 0.25f)
        val groundBright = blendColors(Color.parseColor("#111827"), accent, 0.52f)
        roadPaint.shader = LinearGradient(
            0f, horizonY, 0f, h,
            intArrayOf(groundBright, groundDark),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, horizonY, w, h, roadPaint)

        // Lòng đường cao tốc chính
        val roadPath = Path().apply {
            moveTo(centerX - roadTopHalf, horizonY)
            lineTo(centerX + roadTopHalf, horizonY)
            lineTo(centerX + roadBottomHalf, h)
            lineTo(centerX - roadBottomHalf, h)
            close()
        }
        val asphaltTop = blendColors(Color.parseColor("#1E293B"), accent, 0.48f)
        val asphaltBottom = blendColors(Color.parseColor("#0B0F19"), accent, 0.18f)
        roadPaint.shader = LinearGradient(
            0f, horizonY, 0f, h,
            asphaltTop, asphaltBottom, Shader.TileMode.CLAMP
        )
        canvas.drawPath(roadPath, roadPaint)

        // Các tia làn đường hội tụ về tâm chân trời
        lanePaint.style = Paint.Style.STROKE
        val laneRatios = floatArrayOf(-1.0f, -0.62f, -0.28f, 0.28f, 0.62f, 1.0f)
        for (ratio in laneRatios) {
            val startX = centerX + roadTopHalf * ratio
            val endX = centerX + roadBottomHalf * ratio
            val isMainBorder = kotlin.math.abs(ratio) == 0.28f || kotlin.math.abs(ratio) == 1.0f
            lanePaint.strokeWidth = if (isMainBorder) 3.2f else 1.6f
            lanePaint.color = if (isMainBorder) {
                blendColors(accent, Color.WHITE, 0.65f)
            } else {
                blendColors(accent, Color.WHITE, 0.35f)
            }
            lanePaint.alpha = if (isMainBorder) 210 else 115
            canvas.drawLine(startX, horizonY, endX, h, lanePaint)
        }

        // Vạch đứt phân làn ở giữa chuyển động theo tốc độ xe
        lanePaint.color = Color.WHITE
        lanePaint.alpha = 210
        val segments = 6
        for (i in 0 until segments) {
            val t1 = ((i + animPhase) / segments).coerceIn(0f, 1f)
            val t2 = ((i + animPhase + 0.45f) / segments).coerceIn(0f, 1f)
            if (t2 <= t1) continue
            val y1 = horizonY + (h - horizonY) * (t1 * t1)
            val y2 = horizonY + (h - horizonY) * (t2 * t2)
            lanePaint.strokeWidth = 2f + 5f * t2
            canvas.drawLine(centerX, y1, centerX, y2, lanePaint)
        }

        // Lưới ngang phối cảnh hai bên lề đường (đặc trưng giao diện Công Nghệ & Cơ Bản của Zestech)
        lanePaint.strokeWidth = 1.2f
        lanePaint.color = blendColors(accent, Color.WHITE, 0.4f)
        lanePaint.alpha = 70
        for (i in 1..7) {
            val t = i / 7f
            val y = horizonY + (h - horizonY) * (t * t)
            canvas.drawLine(0f, y, w, y, lanePaint)
        }
    }

    private fun drawZestechSuvCar(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        accent: Int
    ) {
        val carW = (w * 0.135f).coerceIn(130f, 190f)
        val carH = carW * 0.76f
        val left = cx - carW * 0.5f
        val right = cx + carW * 0.5f
        val top = cy - carH * 0.5f
        val bottom = cy + carH * 0.5f

        // 1. Bóng đổ dưới gầm xe
        carPaint.shader = RadialGradient(
            cx, bottom + 6f, carW * 0.62f,
            intArrayOf(Color.argb(210, 0, 0, 0), Color.TRANSPARENT),
            floatArrayOf(0.2f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(RectF(left - 18f, bottom - 14f, right + 18f, bottom + 18f), carPaint)
        carPaint.shader = null

        // 2. Hai lốp xe sau
        val tireW = carW * 0.19f
        val tireH = carH * 0.30f
        carPaint.color = Color.parseColor("#0F172A")
        canvas.drawRoundRect(
            RectF(left + 4f, bottom - tireH, left + 4f + tireW, bottom + 4f),
            6f, 6f, carPaint
        )
        canvas.drawRoundRect(
            RectF(right - 4f - tireW, bottom - tireH, right - 4f, bottom + 4f),
            6f, 6f, carPaint
        )

        // 3. Thân xe SUV màu trắng ngọc trai (vuốt thon lên nóc)
        val bodyPath = Path().apply {
            moveTo(left + carW * 0.16f, top + 6f)
            quadTo(cx, top - 4f, right - carW * 0.16f, top + 6f)
            lineTo(right - carW * 0.06f, cy - 2f)
            lineTo(right, bottom - tireH * 0.65f)
            lineTo(left, bottom - tireH * 0.65f)
            lineTo(left + carW * 0.06f, cy - 2f)
            close()
        }
        carPaint.shader = LinearGradient(
            cx, top, cx, bottom,
            intArrayOf(Color.WHITE, Color.parseColor("#E2E8F0"), Color.parseColor("#CBD5E1")),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(bodyPath, carPaint)
        carPaint.shader = null

        // Viền thân xe
        carPaint.style = Paint.Style.STROKE
        carPaint.strokeWidth = 1.6f
        carPaint.color = Color.parseColor("#94A3B8")
        canvas.drawPath(bodyPath, carPaint)
        carPaint.style = Paint.Style.FILL

        // 4. Kính chắn gió sau màu đen bóng
        val glassPath = Path().apply {
            moveTo(left + carW * 0.21f, top + 12f)
            lineTo(right - carW * 0.21f, top + 12f)
            lineTo(right - carW * 0.13f, cy - 6f)
            lineTo(left + carW * 0.13f, cy - 6f)
            close()
        }
        carPaint.shader = LinearGradient(
            cx, top + 12f, cx, cy - 6f,
            Color.parseColor("#0F172A"), Color.parseColor("#334155"),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(glassPath, carPaint)
        carPaint.shader = null

        // 5. Cụm đèn hậu LED đỏ hai bên (phát sáng khi phanh/vượt tốc)
        val lampW = carW * 0.25f
        val lampH = carH * 0.11f
        val lampY = cy - 2f
        carPaint.color = Color.parseColor("#EF4444")
        canvas.drawRoundRect(RectF(left + 6f, lampY, left + 6f + lampW, lampY + lampH), 5f, 5f, carPaint)
        canvas.drawRoundRect(RectF(right - 6f - lampW, lampY, right - 6f, lampY + lampH), 5f, 5f, carPaint)

        // Lõi đèn hậu sáng trắng-cam
        carPaint.color = Color.parseColor("#FCA5A5")
        canvas.drawRoundRect(RectF(left + 10f, lampY + 3f, left + 4f + lampW * 0.7f, lampY + lampH - 3f), 3f, 3f, carPaint)
        canvas.drawRoundRect(RectF(right - 4f - lampW * 0.7f, lampY + 3f, right - 10f, lampY + lampH - 3f), 3f, 3f, carPaint)

        // 6. Cản sau thể thao & ống xả kép
        val bumperTop = bottom - tireH * 0.85f
        val bumperBottom = bottom - tireH * 0.30f
        carPaint.color = Color.parseColor("#1E293B")
        canvas.drawRoundRect(RectF(left + 8f, bumperTop, right - 8f, bumperBottom), 6f, 6f, carPaint)

        // Tấm ốp gầm bạc giữa cản sau
        carPaint.color = Color.parseColor("#94A3B8")
        canvas.drawRoundRect(RectF(cx - carW * 0.24f, bumperTop + 4f, cx + carW * 0.24f, bumperBottom - 2f), 4f, 4f, carPaint)

        // 7. Biển số xe chính giữa cốp sau
        val plateW = carW * 0.34f
        val plateH = carH * 0.11f
        val plateRect = RectF(cx - plateW * 0.5f, cy + 5f, cx + plateW * 0.5f, cy + 5f + plateH)
        carPaint.color = Color.WHITE
        canvas.drawRoundRect(plateRect, 3f, 3f, carPaint)
        carPaint.style = Paint.Style.STROKE
        carPaint.strokeWidth = 1f
        carPaint.color = Color.parseColor("#334155")
        canvas.drawRoundRect(plateRect, 3f, 3f, carPaint)
        carPaint.style = Paint.Style.FILL

        textPaint.color = Color.parseColor("#0F172A")
        textPaint.textSize = (plateH * 0.68f).coerceAtLeast(8f)
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("30H-985.21", cx, plateRect.centerY() + textPaint.textSize * 0.35f, textPaint)
    }

    private fun blendColors(c1: Int, c2: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        val ir = 1f - r
        val red = (Color.red(c1) * ir + Color.red(c2) * r).toInt().coerceIn(0, 255)
        val green = (Color.green(c1) * ir + Color.green(c2) * r).toInt().coerceIn(0, 255)
        val blue = (Color.blue(c1) * ir + Color.blue(c2) * r).toInt().coerceIn(0, 255)
        return Color.rgb(red, green, blue)
    }
}
