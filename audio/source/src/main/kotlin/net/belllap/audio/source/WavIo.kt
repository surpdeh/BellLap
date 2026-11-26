package net.belllap.audio.source

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavData(val sampleRate: Int, val samples: ShortArray)

/** Minimal RIFF/WAVE reader and writer for mono 16-bit PCM. Other formats are rejected, not guessed at. */
object WavIo {
    private const val HEADER_BYTES = 44
    private const val CHUNK_HEADER_BYTES = 8
    private const val RIFF_SIZE_FIELD_BYTES = 8
    private const val WAVE_OFFSET = 8
    private const val FIRST_CHUNK_OFFSET = 12
    private const val FMT_CHANNELS = 2
    private const val FMT_RATE = 4
    private const val FMT_BITS = 14
    private const val TAG_BYTES = 4
    private const val FMT_CHUNK_BYTES = 16
    private const val PCM_FORMAT = 1
    private const val BITS = 16
    private const val BYTES_PER_SAMPLE = 2

    fun write(file: File, sampleRate: Int, samples: ShortArray) {
        file.parentFile?.mkdirs()
        val dataBytes = samples.size * BYTES_PER_SAMPLE
        val buf = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(HEADER_BYTES - RIFF_SIZE_FIELD_BYTES + dataBytes).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(FMT_CHUNK_BYTES).putShort(PCM_FORMAT.toShort()).putShort(1)
        buf.putInt(sampleRate).putInt(sampleRate * BYTES_PER_SAMPLE).putShort(BYTES_PER_SAMPLE.toShort())
        buf.putShort(BITS.toShort())
        buf.put("data".toByteArray()).putInt(dataBytes)
        samples.forEach { buf.putShort(it) }
        file.writeBytes(buf.array())
    }

    fun read(file: File): WavData = parse(file.readBytes())

    fun parse(bytes: ByteArray): WavData {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= HEADER_BYTES && tag(buf, 0) == "RIFF" && tag(buf, WAVE_OFFSET) == "WAVE") {
            "not a RIFF/WAVE file"
        }
        var pos = FIRST_CHUNK_OFFSET
        var sampleRate = 0
        while (pos + CHUNK_HEADER_BYTES <= bytes.size) {
            val id = tag(buf, pos)
            val size = buf.getInt(pos + TAG_BYTES)
            val body = pos + CHUNK_HEADER_BYTES
            when (id) {
                "fmt " -> {
                    val format = buf.getShort(body).toInt()
                    val channels = buf.getShort(body + FMT_CHANNELS).toInt()
                    sampleRate = buf.getInt(body + FMT_RATE)
                    val bits = buf.getShort(body + FMT_BITS).toInt()
                    require(format == PCM_FORMAT && channels == 1 && bits == BITS) {
                        "only mono 16-bit PCM is supported (format=$format channels=$channels bits=$bits)"
                    }
                }
                "data" -> {
                    require(sampleRate > 0) { "data chunk before fmt chunk" }
                    val count = minOf(size, bytes.size - body) / BYTES_PER_SAMPLE
                    return WavData(sampleRate, ShortArray(count) { buf.getShort(body + it * BYTES_PER_SAMPLE) })
                }
            }
            pos = body + size + (size and 1)
        }
        throw IllegalArgumentException("no data chunk")
    }

    private fun tag(buf: ByteBuffer, at: Int) = String(ByteArray(TAG_BYTES) { buf.get(at + it) }, Charsets.US_ASCII)
}
