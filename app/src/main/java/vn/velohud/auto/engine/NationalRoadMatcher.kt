package vn.velohud.auto.engine

import android.content.Context
import android.util.Log
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater
import kotlin.math.*

/**
 * Kết quả nhận diện Tên đường hiện tại, Phường/Xã, Tỉnh/Thành và Tốc độ mặc định
 * trên mọi con đường không có biển báo toàn quốc (giải mã từ `roadsenz.bin`, `districtsen.bin`, `citiesen.bin`).
 */
data class RoadMatchResult(
    val roadName: String,
    val districtName: String,
    val cityName: String,
    val speedLimitKmh: Int,
    val isOneWay: Boolean,
    val distanceMeters: Double,
    val isNamedRoad: Boolean
) {
    fun getFormattedLocationLabel(): String {
        val adminPart = when {
            districtName.isNotEmpty() && cityName.isNotEmpty() -> "$districtName, $cityName"
            districtName.isNotEmpty() -> districtName
            cityName.isNotEmpty() -> cityName
            else -> ""
        }
        return if (adminPart.isNotEmpty()) {
            "$roadName • $adminPart"
        } else {
            roadName
        }
    }

    fun getCompactBubbleRoadLabel(): String {
        return if (districtName.isNotEmpty()) {
            "$roadName ($districtName)"
        } else {
            roadName
        }
    }
}

private class RoadWay(
    val nameId: Int,
    val distIdx: Int,
    val posSpeed: Int,
    val negSpeed: Int,
    val lats: FloatArray,
    val lons: FloatArray
)

private class RoadGridCell(
    val defaultDistIdx: Int,
    val ways: Array<RoadWay>
)

/**
 * Động cơ Map-Matching Đồ thị Đường bộ Toàn quốc (`velohud_roads_vn.bin` — 34 Tỉnh/Thành, 3,321 Phường/Xã,
 * 1.87 triệu đoạn đường).
 *
 * - Chỉ giải nén theo ô lưới không gian `0.05° × 0.05°` (~5.5km × 5.5km) quanh vị trí xe với bộ đệm LruCache 12 ô.
 * - Phản hồi `< 0.5 ms` mỗi nhịp GPS, tiêu thụ `< 300 KB` bộ nhớ RAM.
 * - Tự động xác định chiều di chuyển thuận (`posSpeed`) hoặc nghịch (`negSpeed`) theo góc hướng lái `bearingDeg`.
 */
class NationalRoadMatcher(private val context: Context) {

    companion object {
        private const val TAG = "NationalRoadMatcher"
        private const val BIN_ASSET_NAME = "velohud_roads_vn.bin"
        private const val COORD_SCALE = 500000.0
        private const val INV_COORD_SCALE = 1.0 / COORD_SCALE
        private const val MAX_SNAP_DIST_M = 38.0
    }

    private var raf: RandomAccessFile? = null
    private var isLoaded: Boolean = false

    private var minLat: Double = 8.0
    private var minLon: Double = 102.0
    private var gridStep: Double = 0.05
    private var gridRows: Int = 320
    private var gridCols: Int = 160

    private val cityNames = HashMap<Int, String>()
    private var districtCityIds = IntArray(0)
    private var districtNames = emptyArray<String>()
    private var roadNames = emptyArray<String>()

    // Ánh xạ cellKey ((row shl 16) or col) -> packed Long ((fileOffset shl 32) or compSize)
    private val cellDirectory = HashMap<Int, Long>(12288)

    private val cellCache = object : LruCache<Int, RoadGridCell>(12) {}

    var lastMatchedRoadName: String = ""
        private set
    var lastMatchedRoadSpeed: Int = 50
        private set

    fun initialize(): Boolean {
        return try {
            val outFile = File(context.filesDir, BIN_ASSET_NAME)
            val assetLen = try {
                context.assets.openFd(BIN_ASSET_NAME).use { it.length }
            } catch (_: Exception) {
                -1L
            }

            if (!outFile.exists() || (assetLen > 0 && outFile.length() != assetLen) || outFile.length() < 1_000_000L) {
                Log.i(TAG, "Đang sao chép CSDL Đồ thị Đường Toàn quốc $BIN_ASSET_NAME...")
                context.assets.open(BIN_ASSET_NAME).use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                        }
                        output.flush()
                    }
                }
            }

            val file = RandomAccessFile(outFile, "r")
            raf = file

            val hdrBytes = ByteArray(32)
            file.readFully(hdrBytes)
            val hdr = ByteBuffer.wrap(hdrBytes).order(ByteOrder.LITTLE_ENDIAN)
            val magicBytes = ByteArray(8)
            hdr.get(magicBytes)
            val magic = String(magicBytes, StandardCharsets.US_ASCII)
            if (magic != "VROADS01") {
                Log.e(TAG, "Sai định dạng header: $magic")
                return false
            }

            val version = hdr.int
            minLat = hdr.int / 10000.0
            minLon = hdr.int / 10000.0
            gridStep = hdr.int / 10000.0
            gridRows = hdr.short.toInt() and 0xFFFF
            gridCols = hdr.short.toInt() and 0xFFFF
            val numCells = hdr.int

            val tblHdrBytes = ByteArray(8)
            file.readFully(tblHdrBytes)
            val tblHdr = ByteBuffer.wrap(tblHdrBytes).order(ByteOrder.LITTLE_ENDIAN)
            val tblCompSize = tblHdr.int
            val tblUncompSize = tblHdr.int

            val tblCompBytes = ByteArray(tblCompSize)
            file.readFully(tblCompBytes)
            val tblRaw = inflateZlib(tblCompBytes, tblUncompSize)
            val tbuf = ByteBuffer.wrap(tblRaw).order(ByteOrder.LITTLE_ENDIAN)

            val numCities = tbuf.short.toInt() and 0xFFFF
            cityNames.clear()
            for (i in 0 until numCities) {
                val cid = tbuf.short.toInt() and 0xFFFF
                val clen = tbuf.get().toInt() and 0xFF
                val sb = ByteArray(clen)
                tbuf.get(sb)
                cityNames[cid] = String(sb, StandardCharsets.UTF_8)
            }

            val numDists = tbuf.short.toInt() and 0xFFFF
            districtCityIds = IntArray(numDists)
            districtNames = Array(numDists) { "" }
            for (i in 0 until numDists) {
                val dIdx = tbuf.short.toInt() and 0xFFFF
                val cid = tbuf.short.toInt() and 0xFFFF
                val dlen = tbuf.get().toInt() and 0xFF
                val sb = ByteArray(dlen)
                tbuf.get(sb)
                if (dIdx in 0 until numDists) {
                    districtCityIds[dIdx] = cid
                    districtNames[dIdx] = String(sb, StandardCharsets.UTF_8)
                }
            }

            val numRoadNames = tbuf.short.toInt() and 0xFFFF
            roadNames = Array(numRoadNames) { "" }
            for (i in 0 until numRoadNames) {
                val nlen = tbuf.get().toInt() and 0xFF
                val sb = ByteArray(nlen)
                tbuf.get(sb)
                roadNames[i] = String(sb, StandardCharsets.UTF_8)
            }

            val dirBytes = ByteArray(numCells * 12)
            file.readFully(dirBytes)
            val dbuf = ByteBuffer.wrap(dirBytes).order(ByteOrder.LITTLE_ENDIAN)
            cellDirectory.clear()
            for (i in 0 until numCells) {
                val ckey = dbuf.int
                val fOff = dbuf.int.toLong() and 0xFFFFFFFFL
                val cSz = dbuf.int.toLong() and 0xFFFFFFFFL
                cellDirectory[ckey] = (fOff shl 32) or cSz
            }

            isLoaded = true
            Log.i(
                TAG,
                "Khởi tạo thành công NationalRoadMatcher v$version: ${cityNames.size} Tỉnh/Thành, " +
                        "${districtNames.size} Phường/Xã, ${roadNames.size} Tên đường, $numCells Ô lưới."
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi tạo NationalRoadMatcher: ${e.message}", e)
            isLoaded = false
            false
        }
    }

    fun resetState() {
        lastMatchedRoadName = ""
        lastMatchedRoadSpeed = 50
    }

    @Synchronized
    private fun loadCell(r: Int, c: Int): RoadGridCell? {
        val ckey = (r shl 16) or c
        cellCache.get(ckey)?.let { return it }

        val packed = cellDirectory[ckey] ?: return null
        val fileOffset = packed ushr 32
        val compSize = (packed and 0xFFFFFFFFL).toInt()
        val file = raf ?: return null

        return try {
            val compBytes = ByteArray(compSize)
            file.seek(fileOffset)
            file.readFully(compBytes)
            val raw = inflateZlibDynamic(compBytes)
            val cbuf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

            val defDistIdx = cbuf.short.toInt() and 0xFFFF
            val numWays = cbuf.int
            val cLat0 = minLat + r * gridStep
            val cLon0 = minLon + c * gridStep

            val ways = Array(numWays) {
                val nameId = cbuf.short.toInt() and 0xFFFF
                val distIdx = cbuf.short.toInt() and 0xFFFF
                val posSpd = cbuf.get().toInt() and 0xFF
                val negSpd = cbuf.get().toInt() and 0xFF
                val nPts = cbuf.short.toInt() and 0xFFFF
                val lats = FloatArray(nPts)
                val lons = FloatArray(nPts)
                for (p in 0 until nPts) {
                    val qlat = cbuf.short.toInt()
                    val qlon = cbuf.short.toInt()
                    lats[p] = (cLat0 + qlat * INV_COORD_SCALE).toFloat()
                    lons[p] = (cLon0 + qlon * INV_COORD_SCALE).toFloat()
                }
                RoadWay(nameId, distIdx, posSpd, negSpd, lats, lons)
            }

            val cell = RoadGridCell(defDistIdx, ways)
            cellCache.put(ckey, cell)
            cell
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi đọc ô lưới ($r, $c): ${e.message}")
            null
        }
    }

    /**
     * Đối chiếu tọa độ GPS `(lat, lon)` và góc hướng di chuyển `bearingDeg` lên đồ thị đường bộ toàn quốc.
     */
    fun matchRoad(lat: Double, lon: Double, bearingDeg: Float): RoadMatchResult {
        if (!isLoaded) {
            return RoadMatchResult(
                roadName = "Đường nội đô",
                districtName = "",
                cityName = "",
                speedLimitKmh = 50,
                isOneWay = false,
                distanceMeters = 0.0,
                isNamedRoad = false
            )
        }

        val r = floor((lat - minLat) / gridStep).toInt()
        val c = floor((lon - minLon) / gridStep).toInt()
        if (r !in 0 until gridRows || c !in 0 until gridCols) {
            return RoadMatchResult(
                roadName = "Đường nội bộ",
                districtName = "",
                cityName = "",
                speedLimitKmh = 50,
                isOneWay = false,
                distanceMeters = 0.0,
                isNamedRoad = false
            )
        }

        val cell = loadCell(r, c)
        if (cell == null) {
            return RoadMatchResult(
                roadName = "Đường dân sinh",
                districtName = "",
                cityName = "",
                speedLimitKmh = 50,
                isOneWay = false,
                distanceMeters = 0.0,
                isNamedRoad = false
            )
        }

        val cosLat = cos(Math.toRadians(lat))
        val mPerDegLat = 110540.0
        val mPerDegLon = 111320.0 * cosLat

        var bestScore = Double.MAX_VALUE
        var bestName = ""
        var bestDistIdx = cell.defaultDistIdx
        var bestSpeed = 50
        var bestOneWay = false
        var bestDistM = Double.MAX_VALUE
        var foundMatch = false

        val ways = cell.ways
        val hasValidBearing = !bearingDeg.isNaN() && bearingDeg >= 0f

        for (i in ways.indices) {
            val w = ways[i]
            val lats = w.lats
            val lons = w.lons
            val nPts = lats.size
            for (k in 0 until nPts - 1) {
                val lat1 = lats[k].toDouble()
                val lon1 = lons[k].toDouble()
                val lat2 = lats[k + 1].toDouble()
                val lon2 = lons[k + 1].toDouble()

                val minSegLat = if (lat1 < lat2) lat1 else lat2
                val maxSegLat = if (lat1 > lat2) lat1 else lat2
                if (lat < minSegLat - 0.00045 || lat > maxSegLat + 0.00045) continue

                val minSegLon = if (lon1 < lon2) lon1 else lon2
                val maxSegLon = if (lon1 > lon2) lon1 else lon2
                if (lon < minSegLon - 0.00045 || lon > maxSegLon + 0.00045) continue

                val dx = (lon2 - lon1) * mPerDegLon
                val dy = (lat2 - lat1) * mPerDegLat
                val segLenSq = dx * dx + dy * dy
                if (segLenSq < 0.01) continue

                val px = (lon - lon1) * mPerDegLon
                val py = (lat - lat1) * mPerDegLat
                val t = ((px * dx + py * dy) / segLenSq).coerceIn(0.0, 1.0)
                val distM = hypot(px - t * dx, py - t * dy)
                if (distM > MAX_SNAP_DIST_M) continue

                val segBrg = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
                val revBrg = (segBrg + 180.0) % 360.0

                var headingDiff = 0.0
                var matchedSpd = 50
                var isOneWay = false

                if (hasValidBearing) {
                    val b = bearingDeg.toDouble()
                    val diffPos = angleDiff(b, segBrg)
                    val diffNeg = angleDiff(b, revBrg)

                    val posOk = w.posSpeed > 0 && diffPos <= 52.0
                    val negOk = w.negSpeed > 0 && diffNeg <= 52.0

                    if (posOk && (!negOk || diffPos <= diffNeg)) {
                        headingDiff = diffPos
                        matchedSpd = w.posSpeed
                        isOneWay = (w.negSpeed == 0)
                    } else if (negOk) {
                        headingDiff = diffNeg
                        matchedSpd = w.negSpeed
                        isOneWay = (w.posSpeed == 0)
                    } else if (w.posSpeed > 0 && diffPos <= 65.0) {
                        headingDiff = diffPos
                        matchedSpd = w.posSpeed
                        isOneWay = (w.negSpeed == 0)
                    } else if (w.negSpeed > 0 && diffNeg <= 65.0) {
                        headingDiff = diffNeg
                        matchedSpd = w.negSpeed
                        isOneWay = (w.posSpeed == 0)
                    } else {
                        continue
                    }
                } else {
                    matchedSpd = max(w.posSpeed, w.negSpeed).let { if (it > 0) it else 50 }
                    isOneWay = (w.posSpeed == 0 || w.negSpeed == 0)
                }

                val rname = if (w.nameId in roadNames.indices) roadNames[w.nameId] else ""
                val hysteresisBonus = if (rname.isNotEmpty() && rname == lastMatchedRoadName) -4.0 else 0.0
                val namedBonus = if (rname.isNotEmpty()) -4.5 else 0.0
                val score = distM + 0.32 * headingDiff + hysteresisBonus + namedBonus

                if (score < bestScore) {
                    bestScore = score
                    bestName = rname
                    bestDistIdx = if (w.distIdx > 0) w.distIdx else cell.defaultDistIdx
                    bestSpeed = matchedSpd
                    bestOneWay = isOneWay
                    bestDistM = distM
                    foundMatch = true
                }
            }
        }

        val dName = if (bestDistIdx in districtNames.indices) districtNames[bestDistIdx] else ""
        val cId = if (bestDistIdx in districtCityIds.indices) districtCityIds[bestDistIdx] else 0
        val cName = cityNames[cId] ?: ""

        if (foundMatch) {
            val resolvedName = when {
                bestName.isNotEmpty() -> {
                    lastMatchedRoadName = bestName
                    bestName
                }
                lastMatchedRoadName.isNotEmpty() && bestDistM <= 18.0 -> lastMatchedRoadName
                else -> "Đường nhánh / Nội bộ"
            }
            val validSpeed = normalizeValidSpeedLimit(bestSpeed)
            lastMatchedRoadSpeed = validSpeed
            return RoadMatchResult(
                roadName = resolvedName,
                districtName = dName,
                cityName = cName,
                speedLimitKmh = validSpeed,
                isOneWay = bestOneWay,
                distanceMeters = bestDistM,
                isNamedRoad = bestName.isNotEmpty()
            )
        }

        // Fallback: Đường hẻm / đường nội bộ 2 chiều không tên trong ô lưới -> 50 km/h
        lastMatchedRoadSpeed = 50
        return RoadMatchResult(
            roadName = "Đường nội bộ",
            districtName = dName,
            cityName = cName,
            speedLimitKmh = 50,
            isOneWay = false,
            distanceMeters = 0.0,
            isNamedRoad = false
        )
    }

    private fun normalizeValidSpeedLimit(spd: Int): Int {
        return when {
            spd <= 0 -> 50
            spd in listOf(30, 40, 50, 60, 70, 80, 90, 100, 120) -> spd
            spd < 35 -> 30
            spd < 45 -> 40
            spd < 55 -> 50
            spd < 65 -> 60
            spd < 75 -> 70
            spd < 85 -> 80
            spd < 95 -> 90
            spd < 110 -> 100
            else -> 120
        }
    }

    private fun inflateZlib(comp: ByteArray, expectedSize: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(comp)
        val out = ByteArray(expectedSize)
        var off = 0
        while (!inflater.finished() && off < expectedSize) {
            val count = inflater.inflate(out, off, expectedSize - off)
            if (count <= 0 && inflater.needsInput()) break
            off += count
        }
        inflater.end()
        return out
    }

    private fun inflateZlibDynamic(comp: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(comp)
        val bos = ByteArrayOutputStream(comp.size * 2)
        val buf = ByteArray(16 * 1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buf)
            if (count > 0) {
                bos.write(buf, 0, count)
            } else if (inflater.needsInput() || inflater.needsDictionary()) {
                break
            }
        }
        inflater.end()
        return bos.toByteArray()
    }

    private fun angleDiff(a: Double, b: Double): Double {
        var d = abs(a - b) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }

    fun close() {
        try {
            raf?.close()
        } catch (_: Exception) {
        }
    }
}
