package app.scanwatch

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Withings WPP framing.
 *
 * Frame: u8 0x01 | u16 cmd | u16 payload length | payload, all big-endian.
 * Payload: a sequence of TLVs, each u16 type | u16 length | data.
 * The top two bits of cmd are the channel: 0 = phone request / watch reply,
 * 1 = watch-initiated request, 2 = notification. The id is cmd & 0x3FFF.
 */
object Cmd {
    const val ERROR = 256
    const val PROBE = 257
    const val INITIAL_CONNECT = 273
    const val SYNC_OK = 277
    const val CHALLENGE = 296
    const val SYNC_REQUEST = 321
    const val BATTERY = 1284
    const val WAM_VASISTAS_GET = 1286
    const val BODY_VASISTAS_GET = 2344
    const val VASISTAS_GET = 2424
}

object Obj {
    const val NULL = 256
    const val CMD_ERROR = 272
    const val PROBE_REPLY = 257
    const val PROBE_CHALLENGE = 290
    const val PROBE_CHALLENGE_RESPONSE = 291
    const val APP_PROBE = 298
    const val BATTERY_STATUS = 1284
    const val WAM_VASISTAS_GET = 1286
    const val VASISTAS_TYPE = 1301
    const val VASISTAS_HEAD = 1537
    const val VASISTAS_DURATION = 1538
    const val VASISTAS_AWAKE = 1539
    const val VASISTAS_SLEEP = 1543
    const val VASISTAS_METCAL_EARNED = 1546
    const val VERSION = 2401
    const val HEARTRATE = 2345
    const val SPO2 = 2453
    const val TEMPERATURE = 2502
    const val HRV = 2520
    const val RESP_RATE = 2521
    const val ACTIVITY_TYPE = 2522
}

class Tlv(val type: Int, val data: ByteArray) {
    fun buf(): ByteBuffer = ByteBuffer.wrap(data)
}

class Frame(val rawCmd: Int, val tlvs: List<Tlv> = emptyList()) {
    val cmd get() = rawCmd and 0x3FFF
    val channel get() = rawCmd ushr 14

    fun first(type: Int): Tlv? = tlvs.firstOrNull { it.type == type }
    fun has(type: Int) = tlvs.any { it.type == type }

    fun encode(): ByteArray {
        val payload = ByteArrayOutputStream()
        for (t in tlvs) {
            payload.write(t.type ushr 8); payload.write(t.type)
            payload.write(t.data.size ushr 8); payload.write(t.data.size)
            payload.write(t.data)
        }
        val p = payload.toByteArray()
        return ByteBuffer.allocate(5 + p.size)
            .put(1).putShort(rawCmd.toShort()).putShort(p.size.toShort()).put(p).array()
    }

    override fun toString() = "Frame(cmd=$cmd ch=$channel tlvs=${tlvs.map { it.type }})"

    companion object {
        fun decode(bytes: ByteArray): Frame {
            val b = ByteBuffer.wrap(bytes)
            b.get()
            val cmd = b.short.toInt() and 0xFFFF
            val len = b.short.toInt() and 0xFFFF
            val end = 5 + len
            val tlvs = ArrayList<Tlv>()
            while (b.position() + 4 <= end) {
                val type = b.short.toInt() and 0xFFFF
                val l = b.short.toInt() and 0xFFFF
                val d = ByteArray(minOf(l, end - b.position()))
                b.get(d)
                tlvs.add(Tlv(type, d))
            }
            return Frame(cmd, tlvs)
        }
    }
}

/** Reassembles WPP frames that arrive split across several BLE notifications. */
class FrameAssembler(private val onFrame: (Frame) -> Unit) {
    private var buf = ByteArray(0)

    @Synchronized
    fun feed(chunk: ByteArray) {
        if (buf.isEmpty() && (chunk.isEmpty() || chunk[0] != 1.toByte())) return
        buf += chunk
        while (buf.size >= 5) {
            val need = 5 + (((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF))
            if (buf.size < need) return
            val frame = buf.copyOfRange(0, need)
            buf = buf.copyOfRange(need, buf.size)
            if (buf.isNotEmpty() && buf[0] != 1.toByte()) buf = ByteArray(0)
            onFrame(Frame.decode(frame))
        }
    }
}

/** WPP strings and byte arrays are a u8 length followed by the bytes. */
fun lenPrefixed(bytes: ByteArray): ByteArray = byteArrayOf(bytes.size.toByte()) + bytes

fun ByteBuffer.readLenPrefixed(): ByteArray {
    val n = get().toInt() and 0xFF
    return ByteArray(n).also { get(it) }
}

fun u8(b: ByteBuffer) = b.get().toInt() and 0xFF
fun u16(b: ByteBuffer) = b.short.toInt() and 0xFFFF
fun u32(b: ByteBuffer) = b.int.toLong() and 0xFFFFFFFFL
