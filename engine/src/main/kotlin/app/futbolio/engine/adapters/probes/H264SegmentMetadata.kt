package app.futbolio.engine.adapters.probes

import java.io.ByteArrayOutputStream

/** Bounded, header-only AVC dimensions. No native decoder, external processes, or extra requests.
 * Unsupported containers/codecs and truncated parameter sets deliberately return unknown.
 */
internal object H264SegmentMetadata {
    data class Dimensions(val width: Int, val height: Int) {
        override fun toString() = "${width}x${height}"
    }

    fun dimensions(segment: ByteArray): Dimensions? = runCatching {
        val limit = minOf(segment.size, 64 * 1024)
        val video = linkedMapOf<Int, ByteArrayOutputStream>()
        val counters = mutableMapOf<Int, Int>()
        for (packet in 0 until limit - 187 step 188) {
            if (segment[packet].toInt() and 255 != 0x47) return null
            fun byte(offset: Int) = segment[packet + offset].toInt() and 255
            if (byte(1) and 0x80 != 0 || byte(3) and 0xc0 != 0) continue
            val control = (byte(3) shr 4) and 3
            if (control == 0 || control == 2) continue
            val pid = ((byte(1) and 31) shl 8) or byte(2)
            var start = 4
            if (control == 3) start += 1 + byte(4)
            if (start >= 188) continue
            val counter = byte(3) and 15
            val previous = counters.put(pid, counter)
            if (previous != null && counter != (previous + 1) % 16) video[pid]?.reset()
            if (byte(1) and 0x40 != 0) {
                // Only video PES payload, never PAT/PMT/audio or arbitrary byte-pattern matches.
                if (start + 9 > 188 || byte(start) != 0 || byte(start + 1) != 0 || byte(start + 2) != 1 ||
                    byte(start + 3) !in 0xe0..0xef) continue
                if (pid !in video && video.size >= 4) continue
                video.getOrPut(pid) { ByteArrayOutputStream() }
                start += 9 + byte(start + 8)
            }
            if (start < 188) video[pid]?.write(segment, packet + start, 188 - start)
        }
        video.values.firstNotNullOfOrNull { output ->
            val bytes = output.toByteArray()
            var index = 0
            var dimensions: Dimensions? = null
            while (index + 4 < bytes.size && dimensions == null) {
                val prefix = when {
                    bytes[index] != 0.toByte() || bytes[index + 1] != 0.toByte() -> 0
                    bytes[index + 2] == 1.toByte() -> 3
                    bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte() -> 4
                    else -> 0
                }
                val header = if (prefix > 0 && index + prefix < bytes.size) bytes[index + prefix].toInt() and 255 else -1
                if (prefix > 0 && header and 0x9f == 7 && header and 0x60 != 0) {
                    val start = index + prefix + 1
                    // SPS is small; cap all parsing work even on malicious provider bytes.
                    var end = start
                    while (end < minOf(start + 4096, bytes.size)) {
                        if (end + 2 < bytes.size && bytes[end] == 0.toByte() && bytes[end + 1] == 0.toByte() &&
                            (bytes[end + 2] == 1.toByte() || end + 3 < bytes.size && bytes[end + 2] == 0.toByte() && bytes[end + 3] == 1.toByte())) break
                        end++
                    }
                    dimensions = runCatching { sps(bytes.copyOfRange(start, end)) }.getOrNull()
                }
                index += if (prefix == 0) 1 else prefix
            }
            dimensions
        }
    }.getOrNull()

    private class Bits(bytes: ByteArray) {
        private val data = ByteArrayOutputStream().also { output ->
            var zeros = 0
            for (byte in bytes) {
                val value = byte.toInt() and 255
                if (zeros >= 2 && value == 3) { zeros = 0; continue }
                output.write(value)
                zeros = if (value == 0) zeros + 1 else 0
            }
        }.toByteArray()
        private var position = 0
        fun bit(): Int {
            require(position < data.size * 8)
            val value = (data[position / 8].toInt() shr (7 - position % 8)) and 1
            position++
            return value
        }
        fun bits(count: Int): Int { var value = 0; repeat(count) { value = (value shl 1) or bit() }; return value }
        fun ue(max: Int = 65535): Int {
            var zeros = 0
            while (bit() == 0) { zeros++; require(zeros <= 16) }
            return ((1 shl zeros) - 1 + bits(zeros)).also { require(it <= max) }
        }
        fun se(): Int = ue().let { if (it % 2 == 0) -it / 2 else (it + 1) / 2 }
    }

    private fun sps(bytes: ByteArray): Dimensions {
        val bits = Bits(bytes)
        val profile = bits.bits(8)
        require(profile in setOf(66, 77, 88, 100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135))
        require(bits.bits(8) and 3 == 0)
        bits.bits(8); bits.ue(31)
        var chroma = 1
        var separateColourPlane = false
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = bits.ue(3)
            if (chroma == 3) separateColourPlane = bits.bit() == 1
            bits.ue(6); bits.ue(6); bits.bit()
            if (bits.bit() == 1) repeat(if (chroma == 3) 12 else 8) { list ->
                if (bits.bit() == 1) {
                    var last = 8
                    var next = 8
                    repeat(if (list < 6) 16 else 64) {
                        if (next != 0) next = (last + bits.se() + 256) and 255
                        if (next != 0) last = next
                    }
                }
            }
        }
        bits.ue(12)
        when (bits.ue(2)) {
            0 -> bits.ue(12)
            1 -> { bits.bit(); bits.se(); bits.se(); repeat(bits.ue(255)) { bits.se() } }
        }
        bits.ue(32); bits.bit()
        val widthMbs = bits.ue(511) + 1
        val heightMapUnits = bits.ue(511) + 1
        val frameOnly = bits.bit()
        if (frameOnly == 0) bits.bit()
        bits.bit()
        var cropX = 0
        var cropY = 0
        if (bits.bit() == 1) { cropX = bits.ue(8192) + bits.ue(8192); cropY = bits.ue(8192) + bits.ue(8192) }
        val arrayType = if (separateColourPlane) 0 else chroma
        val unitX = if (arrayType in 1..2) 2 else 1
        val unitY = (if (arrayType == 1) 2 else 1) * (2 - frameOnly)
        val width = widthMbs * 16 - cropX * unitX
        val height = heightMapUnits * 16 * (2 - frameOnly) - cropY * unitY
        require(width in 16..8192 && height in 16..8192)
        return Dimensions(width, height)
    }
}
