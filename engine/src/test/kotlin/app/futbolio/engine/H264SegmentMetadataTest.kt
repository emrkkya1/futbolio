package app.futbolio.engine

import app.futbolio.engine.adapters.probes.H264SegmentMetadata
import kotlin.test.*

class H264SegmentMetadataTest {
    private class Writer {
        private val bits = mutableListOf<Int>()
        fun value(value: Int, size: Int) { for (bit in size - 1 downTo 0) bits += (value shr bit) and 1 }
        fun ue(number: Int) { val code = number + 1; val count = 32 - Integer.numberOfLeadingZeros(code); value(0, count - 1); value(code, count) }
        fun bytes(): ByteArray {
            value(1, 1)
            while (bits.size % 8 != 0) value(0, 1)
            return bits.chunked(8).map { chunk -> chunk.fold(0) { value, bit -> (value shl 1) or bit }.toByte() }.toByteArray()
        }
    }

    private fun sample(width: Int, height: Int, highProfile: Boolean = false, interlaced: Boolean = false, split: Boolean = false): ByteArray {
        val writer = Writer()
        val factor = if (interlaced) 2 else 1
        val codedHeight = ((height + 16 * factor - 1) / (16 * factor)) * (16 * factor)
        writer.value(if (highProfile) 100 else 66, 8); writer.value(0, 8); writer.value(40, 8); writer.ue(0)
        if (highProfile) { writer.ue(1); writer.ue(0); writer.ue(0); writer.value(0, 1); writer.value(0, 1) }
        writer.ue(0); writer.ue(0); writer.ue(0); writer.ue(1); writer.value(0, 1)
        writer.ue(width / 16 - 1); writer.ue(codedHeight / (16 * factor) - 1)
        writer.value(if (interlaced) 0 else 1, 1)
        if (interlaced) writer.value(0, 1)
        writer.value(1, 1); writer.value(1, 1)
        writer.ue(0); writer.ue(0); writer.ue(0); writer.ue((codedHeight - height) / (2 * factor)); writer.value(0, 1)
        val raw = writer.bytes()
        val escaped = mutableListOf<Byte>()
        var zeros = 0
        for (byte in raw) {
            if (zeros >= 2 && byte.toInt() and 255 <= 3) { escaped += 3; zeros = 0 }
            escaped += byte
            zeros = if (byte == 0.toByte()) zeros + 1 else 0
        }
        val pes = byteArrayOf(0, 0, 1, 0xe0.toByte(), 0, 0, 0x80.toByte(), 0, 0)
        val filler = if (split) byteArrayOf(0, 0, 1, 6) + ByteArray(160) { 5 } else byteArrayOf()
        val payload = pes + filler + byteArrayOf(0, 0, 0, 1, 0x67) + escaped.toByteArray()
        return payload.toList().chunked(184).flatMapIndexed { index, chunk ->
            (byteArrayOf(0x47, if (index == 0) 0x41 else 1, 0, (0x10 + index % 16).toByte()) +
                chunk.toByteArray() + ByteArray(184 - chunk.size) { 0xff.toByte() }).toList()
        }.toByteArray()
    }

    @Test fun readsDimensionsWithoutDecodingIncludingCroppingHighProfileAndPacketSplits() {
        for ((width, height) in listOf(1280 to 720, 1920 to 1080, 640 to 360, 960 to 540)) {
            for (high in listOf(false, true)) for (split in listOf(false, true)) {
                assertEquals("${width}x${height}", H264SegmentMetadata.dimensions(sample(width, height, high, split = split)).toString())
            }
        }
        assertEquals("1920x1080", H264SegmentMetadata.dimensions(sample(1920, 1080, interlaced = true)).toString())
    }

    @Test fun missingUnsupportedAndTruncatedHeadersStayUnknown() {
        assertNull(H264SegmentMetadata.dimensions(byteArrayOf()))
        assertNull(H264SegmentMetadata.dimensions(ByteArray(1024)))
        assertNull(H264SegmentMetadata.dimensions(sample(1280, 720).copyOf(20)))
        // Audio PES carrying an SPS-looking pattern is not video evidence.
        val audio = sample(1280, 720).also { it[7] = 0xc0.toByte() }
        assertNull(H264SegmentMetadata.dimensions(audio))
    }

    @Test fun doesNotScanBeyond64KiBOrUseBrokenPacketContinuity() {
        val late = ByteArray(188 * 350).also { for (index in it.indices step 188) it[index] = 0x47 }
        assertNull(H264SegmentMetadata.dimensions(late + sample(1280, 720)))
        val broken = sample(1280, 720, split = true).also { it[191] = 0x17 }
        assertNull(H264SegmentMetadata.dimensions(broken))
    }
}
