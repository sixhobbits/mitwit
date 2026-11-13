package app.scanwatch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class WatchException(msg: String) : Exception(msg)

/**
 * Talks WPP to a ScanWatch over BLE. Blocking API: call from a worker thread.
 *
 * Link setup mirrors the Withings app: the service whose UUID contains "5749-5448"
 * ("WITH"), the characteristic whose UUID has the index digit at position 7 (4 when
 * bonded, 3 when not), MTU 512, notifications on that same characteristic, and writes
 * without response in MTU-3 chunks.
 */
@SuppressLint("MissingPermission")
class WatchClient(private val context: Context, private val log: (String) -> Unit) {

    private sealed class Ev {
        data class State(val connected: Boolean, val status: Int) : Ev()
        data class Services(val status: Int) : Ev()
        data class Mtu(val mtu: Int, val status: Int) : Ev()
        data class DescWrite(val status: Int) : Ev()
        data class Write(val status: Int) : Ev()
    }

    private val events = LinkedBlockingQueue<Ev>()
    private val frames = LinkedBlockingQueue<Frame>()
    private val assembler = FrameAssembler { frames.put(it) }

    private var gatt: BluetoothGatt? = null
    private var ch: BluetoothGattCharacteristic? = null
    private var mtu = 23

    var deviceName = ""
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            events.put(Ev.State(newState == BluetoothProfile.STATE_CONNECTED, status))
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            events.put(Ev.Services(status))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            events.put(Ev.Mtu(mtu, status))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            events.put(Ev.DescWrite(status))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            events.put(Ev.Write(status))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == ch?.uuid) assembler.feed(value)
        }
    }

    private inline fun <reified T : Ev> await(what: String, timeoutMs: Long = 15000): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw WatchException("Timed out waiting for $what")
            when (val e = events.poll(left, TimeUnit.MILLISECONDS)) {
                is T -> return e
                is Ev.State -> if (!e.connected) throw WatchException("Watch disconnected (status ${e.status}) while waiting for $what")
                else -> {}
            }
        }
    }

    fun connect(address: String, advKey: String) {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            ?: throw WatchException("No Bluetooth adapter")
        if (!adapter.isEnabled) throw WatchException("Bluetooth is off")
        val device = scan(adapter, address.uppercase(), advKey)
        val bonded = device.bondState == BluetoothDevice.BOND_BONDED
        deviceName = address
        log("Connecting to ${device.address} (${if (bonded) "bonded" else "not bonded"})")
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        gatt = g
        val st = await<Ev.State>("connection", 30000)
        if (!st.connected) throw WatchException("Connection failed (status ${st.status})")

        if (!g.discoverServices()) throw WatchException("discoverServices failed")
        await<Ev.Services>("service discovery")
        val service = g.services.firstOrNull { it.uuid.toString().contains("5749-5448", ignoreCase = true) }
            ?: throw WatchException("Withings service not found; services: ${g.services.map { it.uuid }}")
        // Bonded (encrypted) links use characteristic 4, unbonded ones characteristic 3.
        val index = if (bonded) '4' else '3'
        val c = service.characteristics.firstOrNull { it.uuid.toString()[7] == index }
            ?: throw WatchException("Characteristic $index not found; have: ${service.characteristics.map { it.uuid }}")
        ch = c
        log("Using ${c.uuid}")

        g.requestMtu(512)
        mtu = await<Ev.Mtu>("MTU").mtu
        log("MTU $mtu")

        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            ?: throw WatchException("No notification descriptor")
        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        await<Ev.DescWrite>("enabling notifications")
    }

    /**
     * The watch advertises from a random address, so it is recognised by the identity in
     * its 0xFD77 service data: a 2-byte salt and a 2-byte hash of the advertise key
     * (the Withings app sets it to the same value as the association secret).
     */
    private fun scan(adapter: android.bluetooth.BluetoothAdapter, address: String, advKey: String): BluetoothDevice {
        val found = LinkedBlockingQueue<BluetoothDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val data = result.scanRecord?.getServiceData(FD77) ?: ByteArray(0)
                if (result.device.address == address || identityMatches(data, advKey.toByteArray())) {
                    found.offer(result.device)
                }
            }
        }
        val filters = listOf(
            ScanFilter.Builder().setServiceData(FD77, ByteArray(0)).build(),
            ScanFilter.Builder().setDeviceAddress(address).build(),
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        log("Looking for the watch…")
        adapter.bluetoothLeScanner.startScan(filters, settings, cb)
        try {
            return found.poll(SCAN_SECONDS, TimeUnit.SECONDS)
                ?: throw WatchException("Watch not found nearby within $SCAN_SECONDS s")
        } finally {
            adapter.bluetoothLeScanner.stopScan(cb)
        }
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    fun send(frame: Frame) {
        val g = gatt ?: throw WatchException("Not connected")
        val c = ch ?: throw WatchException("Not connected")
        val bytes = frame.encode()
        val chunk = mtu - 3
        var off = 0
        while (off < bytes.size) {
            val part = bytes.copyOfRange(off, minOf(off + chunk, bytes.size))
            val rc = g.writeCharacteristic(c, part, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            if (rc != BluetoothGatt.GATT_SUCCESS) throw WatchException("Write failed ($rc)")
            await<Ev.Write>("write")
            off += chunk
        }
    }

    /** Next frame for [cmd]; answers watch-initiated sync requests and skips unrelated frames. */
    private fun nextFrame(cmd: Int, timeoutMs: Long = 20000): Frame {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw WatchException("Timed out waiting for reply to $cmd")
            val f = frames.poll(left, TimeUnit.MILLISECONDS) ?: continue
            if (f.channel == 1 && f.cmd == Cmd.SYNC_REQUEST) {
                send(Frame((1 shl 14) or Cmd.SYNC_REQUEST))
                continue
            }
            if (f.channel != 0) continue
            if (f.cmd == cmd) return f
            if (f.cmd == Cmd.ERROR) {
                val e = f.first(Obj.CMD_ERROR)?.buf()
                if (e != null && u16(e) == cmd) throw WatchException("Watch rejected command $cmd (error ${e.int})")
            }
        }
    }

    /** Sends a request and returns the single reply frame. */
    fun request(frame: Frame): Frame {
        send(frame)
        return nextFrame(frame.cmd)
    }

    /** Sends a request whose reply is a list of frames terminated by a Null TLV. */
    fun requestList(frame: Frame): List<Frame> {
        send(frame)
        val out = ArrayList<Frame>()
        while (true) {
            val f = nextFrame(frame.cmd)
            out.add(f)
            if (f.has(Obj.NULL)) return out
        }
    }

    /**
     * Probe and mutual challenge. The watch proves the phone knows the association
     * secret with SHA1(challenge || mac || secret). We also send the watch a challenge,
     * as the Withings app does, but don't need to check its answer.
     */
    fun authenticate(secret: String) {
        val appProbe = ByteBuffer.allocate(6).put(1).put(1).putInt(202611020).array()
        send(Frame(Cmd.PROBE, listOf(Tlv(Obj.APP_PROBE, appProbe), Tlv(2344, byteArrayOf(0x00, 0x25)))))

        val challengeFrame = nextFrame(Cmd.CHALLENGE)
        val cb = challengeFrame.first(Obj.PROBE_CHALLENGE)?.buf()
            ?: throw WatchException("Watch sent no challenge")
        val mac = cb.readLenPrefixed()
        val challenge = cb.readLenPrefixed()
        val answer = MessageDigest.getInstance("SHA1").digest(challenge + mac + secret.toByteArray())

        val ours = ByteArray(16).also { SecureRandom().nextBytes(it) }
        send(Frame(Cmd.CHALLENGE, listOf(
            Tlv(Obj.PROBE_CHALLENGE_RESPONSE, lenPrefixed(answer)),
            Tlv(Obj.PROBE_CHALLENGE, lenPrefixed(mac) + lenPrefixed(ours)),
        )))

        val reply = nextFrame(Cmd.PROBE)
        val pr = reply.first(Obj.PROBE_REPLY)?.buf() ?: throw WatchException("Authentication failed: no probe reply")
        pr.int
        deviceName = String(pr.readLenPrefixed())
        log("Authenticated with $deviceName")

        request(Frame(Cmd.INITIAL_CONNECT))
    }

    companion object {
        const val SCAN_SECONDS = 60L
        val FD77: android.os.ParcelUuid = android.os.ParcelUuid.fromString("0000FD77-0000-1000-8000-00805F9B34FB")
    }
}

/**
 * FD77 service data: 2-byte header (version, model), then entries with a 1-byte header
 * (type in the high nibble, length in the low nibble). Type 3 = identity {salt[2], hash[2]},
 * where hash = SHA1(key XOR repeated salt) folded by XOR into 2 bytes.
 */
internal fun identityMatches(data: ByteArray, key: ByteArray): Boolean {
    var i = 2
    while (i < data.size) {
        val type = (data[i].toInt() ushr 4) and 0xF
        val len = data[i].toInt() and 0xF
        if (i + 1 + len > data.size) return false
        if (type == 3 && len == 4) {
            val salt = data.copyOfRange(i + 1, i + 3)
            val want = data.copyOfRange(i + 3, i + 5)
            val mixed = ByteArray(key.size) { (key[it].toInt() xor salt[it % salt.size].toInt()).toByte() }
            val d = MessageDigest.getInstance("SHA1").digest(mixed)
            val out = d.copyOf(want.size)
            for (j in want.size until d.size) out[j % want.size] = (out[j % want.size].toInt() xor d[j].toInt()).toByte()
            return out.contentEquals(want)
        }
        i += 1 + len
    }
    return false
}
