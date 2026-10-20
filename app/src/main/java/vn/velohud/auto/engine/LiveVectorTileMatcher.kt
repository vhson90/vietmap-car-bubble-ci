package vn.velohud.auto.engine

import android.util.Log
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * Tầng dự phòng trực tuyến (Live MVT Tile Fallback):
 * Khi xe đi vào tuyến đường mới mở chưa có tên trong gói Offline `velohud_roads_vn.bin`
 * và thiết bị có mạng Internet, tự động tải & giải mã XOR 32-byte ô bản đồ Vector Tile `v3/base/14/{x}/{y}`
 * và suy diễn tốc độ mặc định theo chuẩn QCVN 41:2019/BGTVT (50 / 60 / 70 / 80 km/h).
 */
class LiveVectorTileMatcher {

    companion object {
        private const val TAG = "LiveVectorTileMatcher"
        private val XOR_KEY_MVT_BASE = byteArrayOf(
            0xf2.toByte(), 0xf2.toByte(), 0xe4.toByte(), 0x1e.toByte(),
            0x9d.toByte(), 0xaa.toByte(), 0xad.toByte(), 0x9a.toByte(),
            0xe9.toByte(), 0xf7.toByte(), 0x80.toByte(), 0xaa.toByte(),
            0x87.toByte(), 0x1b.toByte(), 0x30.toByte(), 0xa5.toByte(),
            0x94.toByte(), 0xfb.toByte(), 0x63.toByte(), 0x2c.toByte(),
            0x69.toByte(), 0xf8.toByte(), 0x12.toByte(), 0x91.toByte(),
            0x22.toByte(), 0xa3.toByte(), 0x46.toByte(), 0x72.toByte(),
            0xe4.toByte(), 0xb8.toByte(), 0xe5.toByte(), 0x48.toByte()
        )
    }

    data class MvtRoadFeature(
        val name: String,
        val roadClass: String,
        val oneway: Boolean,
        val lats: FloatArray,
        val lons: FloatArray
    )

    private val tileCache = object : LruCache<Int, List<MvtRoadFeature>>(16) {}
    private val pendingTiles = HashSet<Int>()
    private val executor = Executors.newSingleThreadExecutor()

    fun queryFallbackIfAvailable(lat: Double, lon: Double): Pair<String, Int>? {
        val z = 14
        val n = (1 shl z).toDouble()
        val tx = floor((lon + 180.0) / 360.0 * n).toInt()
        val latRad = Math.toRadians(lat)
        val ty = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt()
        val tileKey = (tx shl 16) or ty

        val features = synchronized(this) {
            val cached = tileCache.get(tileKey)
            if (cached == null && !pendingTiles.contains(tileKey)) {
                pendingTiles.add(tileKey)
                executor.execute { fetchAndDecodeTile(tx, ty, tileKey) }
            }
            cached
        } ?: return null

        val cosLat = cos(latRad)
        val mPerDegLat = 110540.0
        val mPerDegLon = 111320.0 * cosLat

        var bestDist = 36.0
        var bestFeature: MvtRoadFeature? = null

        for (f in features) {
            for (i in 0 until f.lats.size - 1) {
                val lat1 = f.lats[i].toDouble()
                val lon1 = f.lons[i].toDouble()
                val lat2 = f.lats[i + 1].toDouble()
                val lon2 = f.lons[i + 1].toDouble()

                val dx = (lon2 - lon1) * mPerDegLon
                val dy = (lat2 - lat1) * mPerDegLat
                val lenSq = dx * dx + dy * dy
                if (lenSq < 0.01) continue

                val px = (lon - lon1) * mPerDegLon
                val py = (lat - lat1) * mPerDegLat
                val t = ((px * dx + py * dy) / lenSq).coerceIn(0.0, 1.0)
                val d = hypot(px - t * dx, py - t * dy)
                if (d < bestDist && f.name.isNotEmpty()) {
                    bestDist = d
                    bestFeature = f
                }
            }
        }

        val matched = bestFeature ?: return null
        val inferredSpeed = when (matched.roadClass) {
            "motorway" -> 100
            "trunk" -> if (matched.oneway) 80 else 70
            "primary" -> if (matched.oneway) 60 else 50
            "secondary" -> if (matched.oneway) 60 else 50
            else -> 50
        }
        return Pair(matched.name, inferredSpeed)
    }

    private fun fetchAndDecodeTile(tx: Int, ty: Int, tileKey: Int) {
        try {
            val url = URL("https://tile.vietmap.live/production/v3/base/14/$tx/$ty")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "Dart/3.5 (dart:io)")
            }
            if (conn.responseCode == 200) {
                val raw = conn.inputStream.use { it.readBytes() }
                for (i in raw.indices) {
                    raw[i] = (raw[i].toInt() xor XOR_KEY_MVT_BASE[i and 31].toInt()).toByte()
                }
                val parsed = parseMvtNamedRoads(raw, tx, ty)
                synchronized(this) {
                    tileCache.put(tileKey, parsed)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Bỏ qua tải MVT tile 14/$tx/$ty: ${e.message}")
        } finally {
            synchronized(this) {
                pendingTiles.remove(tileKey)
            }
        }
    }

    private fun parseMvtNamedRoads(buf: ByteArray, tx: Int, ty: Int): List<MvtRoadFeature> {
        val out = ArrayList<MvtRoadFeature>()
        var pos = 0
        val ptr = IntArray(1)
        while (pos < buf.size) {
            ptr[0] = pos
            val tag = readVarint(buf, ptr)
            pos = ptr[0]
            val fn = tag ushr 3
            val wt = tag and 7
            when (wt) {
                2 -> {
                    val len = readVarint(buf, ptr)
                    pos = ptr[0]
                    if (fn == 3 && pos + len <= buf.size) {
                        parseLayer(buf, pos, pos + len, tx, ty, out)
                    }
                    pos += len
                }
                0 -> {
                    readVarint(buf, ptr)
                    pos = ptr[0]
                }
                5 -> pos += 4
                1 -> pos += 8
                else -> break
            }
        }
        return out
    }

    private fun parseLayer(
        buf: ByteArray,
        start: Int,
        end: Int,
        tx: Int,
        ty: Int,
        out: MutableList<MvtRoadFeature>
    ) {
        var pos = start
        val ptr = IntArray(1)
        var layerName = ""
        val keys = ArrayList<String>()
        val values = ArrayList<String>()
        val featRanges = ArrayList<IntArray>()
        var extent = 4096

        while (pos < end) {
            ptr[0] = pos
            val tag = readVarint(buf, ptr)
            pos = ptr[0]
            val fn = tag ushr 3
            val wt = tag and 7
            when (wt) {
                2 -> {
                    val len = readVarint(buf, ptr)
                    pos = ptr[0]
                    if (pos + len > end) break
                    when (fn) {
                        1 -> {
                            layerName = String(buf, pos, len, StandardCharsets.UTF_8)
                            if (layerName != "transportation_name") return
                        }
                        2 -> featRanges.add(intArrayOf(pos, pos + len))
                        3 -> keys.add(String(buf, pos, len, StandardCharsets.UTF_8))
                        4 -> values.add(parseValueString(buf, pos, pos + len))
                    }
                    pos += len
                }
                0 -> {
                    val v = readVarint(buf, ptr)
                    pos = ptr[0]
                    if (fn == 5) extent = v
                }
                5 -> pos += 4
                1 -> pos += 8
                else -> break
            }
        }

        val n = (1 shl 14).toDouble()
        for (range in featRanges) {
            var fpos = range[0]
            val fend = range[1]
            val tags = ArrayList<Int>()
            val geom = ArrayList<Int>()
            while (fpos < fend) {
                ptr[0] = fpos
                val ftag = readVarint(buf, ptr)
                fpos = ptr[0]
                val ffn = ftag ushr 3
                val fwt = ftag and 7
                when (fwt) {
                    2 -> {
                        val flen = readVarint(buf, ptr)
                        fpos = ptr[0]
                        val subEnd = fpos + flen
                        if (ffn == 2) {
                            while (fpos < subEnd) {
                                ptr[0] = fpos
                                tags.add(readVarint(buf, ptr))
                                fpos = ptr[0]
                            }
                        } else if (ffn == 4) {
                            while (fpos < subEnd) {
                                ptr[0] = fpos
                                geom.add(readVarint(buf, ptr))
                                fpos = ptr[0]
                            }
                        } else {
                            fpos = subEnd
                        }
                    }
                    0 -> {
                        readVarint(buf, ptr)
                        fpos = ptr[0]
                    }
                    5 -> fpos += 4
                    1 -> fpos += 8
                    else -> break
                }
            }

            var rname = ""
            var rclass = "tertiary"
            var oneway = false
            var idx = 0
            while (idx + 1 < tags.size) {
                val kIdx = tags[idx]
                val vIdx = tags[idx + 1]
                if (kIdx in keys.indices && vIdx in values.indices) {
                    when (keys[kIdx]) {
                        "name" -> rname = values[vIdx]
                        "class" -> rclass = values[vIdx]
                        "oneway" -> oneway = (values[vIdx] == "1")
                    }
                }
                idx += 2
            }
            if (rname.isEmpty()) continue

            var cx = 0
            var cy = 0
            var gIdx = 0
            val lats = ArrayList<Float>()
            val lons = ArrayList<Float>()
            while (gIdx < geom.size) {
                val cmdHdr = geom[gIdx++]
                val cmd = cmdHdr and 7
                val cnt = cmdHdr ushr 3
                if (cmd == 1 || cmd == 2) {
                    for (c in 0 until cnt) {
                        if (gIdx + 1 >= geom.size) break
                        val dx = zigzagDecode(geom[gIdx++])
                        val dy = zigzagDecode(geom[gIdx++])
                        cx += dx
                        cy += dy
                        val lon = ((tx + cx.toDouble() / extent) / n) * 360.0 - 180.0
                        val latRad = atan(sinh(PI * (1.0 - 2.0 * (ty + cy.toDouble() / extent) / n)))
                        val lat = Math.toDegrees(latRad)
                        lats.add(lat.toFloat())
                        lons.add(lon.toFloat())
                    }
                }
            }
            if (lats.size >= 2) {
                out.add(
                    MvtRoadFeature(
                        name = rname,
                        roadClass = rclass,
                        oneway = oneway,
                        lats = lats.toFloatArray(),
                        lons = lons.toFloatArray()
                    )
                )
            }
        }
    }

    private fun parseValueString(buf: ByteArray, start: Int, end: Int): String {
        var pos = start
        val ptr = IntArray(1)
        while (pos < end) {
            ptr[0] = pos
            val tag = readVarint(buf, ptr)
            pos = ptr[0]
            val fn = tag ushr 3
            val wt = tag and 7
            when (wt) {
                2 -> {
                    val len = readVarint(buf, ptr)
                    pos = ptr[0]
                    if (fn == 1 && pos + len <= end) {
                        return String(buf, pos, len, StandardCharsets.UTF_8)
                    }
                    pos += len
                }
                0 -> {
                    val v = readVarint(buf, ptr)
                    pos = ptr[0]
                    return v.toString()
                }
                5 -> pos += 4
                1 -> pos += 8
                else -> break
            }
        }
        return ""
    }

    private fun readVarint(buf: ByteArray, ptr: IntArray): Int {
        var result = 0
        var shift = 0
        var pos = ptr[0]
        while (pos < buf.size) {
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        ptr[0] = pos
        return result
    }

    private fun zigzagDecode(n: Int): Int = (n ushr 1) xor -(n and 1)
}
