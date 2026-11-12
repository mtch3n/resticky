package dev.resticky.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import dev.resticky.BLE_RX
import dev.resticky.BLE_SERVICE
import dev.resticky.BLE_TX
import dev.resticky.Checkin
import dev.resticky.FRAME_CHECKIN
import dev.resticky.FRAME_AGENDA
import dev.resticky.FRAME_CHUNK
import dev.resticky.FRAME_DISPLAY
import dev.resticky.FRAME_HUB_TOKEN
import dev.resticky.FRAME_LISTS
import dev.resticky.HubToken
import dev.resticky.FRAME_TODO_SET
import dev.resticky.TodoSet
import dev.resticky.FRAME_PHOTO
import dev.resticky.FRAME_OTA_STATUS
import dev.resticky.OtaStatus
import dev.resticky.FRAME_TIME
import dev.resticky.FRAME_WEATHER
import dev.resticky.FRAME_WEATHER_SET
import dev.resticky.FRAME_WIFI_ADD
import dev.resticky.FRAME_WIFI_FORGET
import dev.resticky.TimeInfo
import dev.resticky.WeatherChange
import dev.resticky.WifiAdd
import dev.resticky.WifiForget
import dev.resticky.frame
import dev.resticky.utcOffsetMin
import dev.resticky.Frame
import dev.resticky.FrameReader
import dev.resticky.Hub
import dev.resticky.ackFrame
import dev.resticky.hubJson
import dev.resticky.parseChunk
import dev.resticky.chunkAudioOk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

private const val MTU = 515
private const val RETRY_MS = 3_000L
private const val WRITE_RETRY_MS = 200L
private const val MAX_WRITE_RETRIES = 10
private const val DRAIN_MS = 15_000L
private const val PHOTO_DRAIN_MS = 60_000L
private val SERVICE = UUID.fromString(BLE_SERVICE)
private val RX = UUID.fromString(BLE_RX)
private val TX = UUID.fromString(BLE_TX)
private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

enum class LinkStatus { Off, Connecting, Pairing, Ready }

data class BleState(
    val status: LinkStatus = LinkStatus.Off,
    val address: String? = null,
    val passkeyWanted: Boolean = false,
    val mtu: Int = 0,
    val phy2m: Boolean = false,
    val lastChunk: String? = null,
)

@SuppressLint("MissingPermission")
class BleHub(private val context: Context, private val hub: Hub, private val scope: CoroutineScope, private val sticky: () -> String?) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val main = Handler(Looper.getMainLooper())
    private val reader = FrameReader()
    private var gatt: BluetoothGatt? = null
    private var running = false
    private var frameStart = 0L
    private var pairing: BluetoothDevice? = null

    val state = MutableStateFlow(BleState(address = sticky()))
    private val outbox = ArrayDeque<ByteArray>()
    val otaStatus = MutableStateFlow<OtaStatus?>(null)
    val ready: Boolean get() = state.value.status == LinkStatus.Ready
    private var writing = false
    private var inFlight: ByteArray? = null
    private var retries = 0
    private val sent = mutableMapOf<Int, String>()
    private var watchers = emptyList<Job>()
    @Volatile private var linked: String? = null
    @Volatile private var photo = false

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            if (device.address != gatt?.device?.address) return
            when (intent.action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    pairing = device
                    state.update { it.copy(status = LinkStatus.Pairing, passkeyWanted = true) }
                    log("INFO ble pairing request variant=${intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)}")
                    if (isOrderedBroadcast) abortBroadcast()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_BONDED -> {
                        log("INFO ble bonded address=${device.address}")
                        state.update { it.copy(passkeyWanted = false) }
                        pairing = null
                        gatt?.requestMtu(MTU)
                    }
                    BluetoothDevice.BOND_NONE -> {
                        log("WARN ble pairing failed address=${device.address}")
                        state.update { it.copy(passkeyWanted = false) }
                        pairing = null
                        gatt?.disconnect()
                    }
                }
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("INFO ble connected address=${g.device.address} bond=${g.device.bondState}")
                if (g.device.bondState == BluetoothDevice.BOND_BONDED) g.requestMtu(MTU)
                else {
                    state.update { it.copy(status = LinkStatus.Pairing) }
                    g.device.createBond()
                }
                return
            }
            log("INFO ble disconnected status=$status")
            reader.clear()
            resetOutbox()
            g.close()
            if (gatt == g) gatt = null
            state.update { it.copy(status = LinkStatus.Off, passkeyWanted = false, mtu = 0, phy2m = false) }
            retry()
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            state.update { it.copy(mtu = mtu) }
            g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
            g.discoverServices()
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            log("INFO ble phy tx=$txPhy rx=$rxPhy")
            state.update { it.copy(phy2m = txPhy == BluetoothDevice.PHY_LE_2M && rxPhy == BluetoothDevice.PHY_LE_2M) }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val tx = g.getService(SERVICE)?.getCharacteristic(TX)
            if (tx == null) {
                log("WARN ble no resticky service")
                g.disconnect()
                return
            }
            g.setCharacteristicNotification(tx, true)
            g.writeDescriptor(tx.getDescriptor(CCCD), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("WARN ble subscribe failed status=$status")
                g.disconnect()
                return
            }
            log("INFO ble ready mtu=${state.value.mtu}")
            state.update { it.copy(status = LinkStatus.Ready) }
            hub.token?.let { write(frame(FRAME_HUB_TOKEN, hubJson.encodeToString(HubToken(it)).encodeToByteArray())) }
            push()
            scope.launch { pushLists() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val retry = synchronized(outbox) {
                writing = false
                val piece = inFlight
                inFlight = null
                when {
                    status == BluetoothGatt.GATT_SUCCESS -> {
                        retries = 0
                        false
                    }
                    piece != null && retries < MAX_WRITE_RETRIES -> {
                        retries++
                        outbox.addFirst(piece)
                        true
                    }
                    else -> null
                }
            }
            if (status != BluetoothGatt.GATT_SUCCESS) log("WARN ble write failed status=$status retry=$retry")
            when (retry) {
                true -> main.postDelayed(::pump, WRITE_RETRY_MS)
                false -> pump()
                null -> {
                    log("WARN ble write gave up, closing the link")
                    resetOutbox()
                    g.disconnect()
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (!reader.pending) frameStart = System.nanoTime()
            val frames = reader.feed(value)
            if (frames.isEmpty()) return
            val took = (System.nanoTime() - frameStart) / 1_000_000
            frames.forEach { handle(g, it, took) }
            if (reader.pending) frameStart = System.nanoTime()
        }
    }

    private fun handle(g: BluetoothGatt, frame: Frame, tookMs: Long) {
        when (frame.type) {
            FRAME_CHECKIN -> runCatching { hubJson.decodeFromString<Checkin>(frame.payload.decodeToString()) }
                .onSuccess {
                    linked = it.device
                    scope.launch {
                        hub.checkin(it, "ble")
                        pushLists()
                    }
                    push()
                }
                .onFailure { log("WARN ble bad checkin ${it.message}") }
            FRAME_OTA_STATUS -> runCatching { hubJson.decodeFromString<OtaStatus>(frame.payload.decodeToString()) }
                .onSuccess {
                    otaStatus.value = it
                    log("INFO ble ota_status state=${it.state} percent=${it.percent}")
                }
                .onFailure { log("WARN ble bad ota_status ${it.message}") }
            FRAME_WEATHER_SET -> runCatching { hubJson.decodeFromString<WeatherChange>(frame.payload.decodeToString()) }
                .onSuccess { scope.launch { if (!hub.weather.change(it)) log("WARN ble bad weather_set") else log("INFO ble weather_set mode=${it.mode} units=${it.units}") } }
                .onFailure { log("WARN ble bad weather_set ${it.message}") }
            FRAME_TODO_SET -> runCatching { hubJson.decodeFromString<TodoSet>(frame.payload.decodeToString()) }
                .onSuccess { set ->
                    val device = linked ?: return log("WARN ble todo_set before checkin")
                    scope.launch {
                        if (hub.setTodo(device, set) == null) log("WARN ble todo_set no such to-do id=${set.id}")
                        pushLists()
                    }
                }
                .onFailure { log("WARN ble bad todo_set ${it.message}") }
            FRAME_CHUNK -> {
                val chunk = parseChunk(frame.payload) ?: return log("WARN ble bad chunk frame bytes=${frame.payload.size}")
                if (!chunkAudioOk(chunk.audio)) return log("WARN ble bad chunk audio, no ack ${chunk.sessionId}/${chunk.seq} bytes=${chunk.audio.size}")
                scope.launch {
                    runCatching { hub.receiveChunk(chunk.device, chunk.sessionId, chunk.seq.toInt(), chunk.final, chunk.startMs, chunk.durationMs, chunk.audio, "ble") }
                        .onFailure { return@launch log("WARN ble chunk not stored, no ack ${chunk.sessionId}/${chunk.seq}: ${it.message}") }
                    write(ackFrame(chunk.session, chunk.seq))
                    val kbs = if (tookMs > 0) frame.payload.size / 1.024 / tookMs else 0.0
                    val line = "${chunk.sessionId}/${chunk.seq}: ${frame.payload.size} bytes in $tookMs ms (${"%.1f".format(kbs)} KB/s)"
                    log("INFO ble chunk $line")
                    state.update { it.copy(lastChunk = line) }
                }
            }
        }
    }

    private fun write(bytes: ByteArray) {
        val piece = (state.value.mtu - 3).coerceIn(20, 512)
        synchronized(outbox) { bytes.toList().chunked(piece).forEach { outbox.addLast(it.toByteArray()) } }
        pump()
    }

    private fun pump() {
        synchronized(outbox) {
            if (writing) return
            val g = gatt ?: return
            val rx = g.getService(SERVICE)?.getCharacteristic(RX) ?: return
            val next = outbox.firstOrNull() ?: return
            val result = g.writeCharacteristic(rx, next, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            if (result == BluetoothStatusCodes.SUCCESS) {
                inFlight = outbox.removeFirst()
                writing = true
            } else {
                main.postDelayed(::pump, 50)
            }
        }
    }

    private fun resetOutbox() = synchronized(outbox) {
        outbox.clear()
        inFlight = null
        retries = 0
        writing = false
        sent.clear()
        linked = null
    }

    private fun sendIfChanged(type: Int, json: String) {
        if (synchronized(outbox) { sent.put(type, json) == json }) return
        log("INFO ble send type=$type bytes=${json.length}")
        write(frame(type, json.encodeToByteArray()))
    }

    @Volatile var updating = false
        set(value) {
            field = value
            if (value) gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            if (!value) {
                push()
                scope.launch { pushLists() }
            }
        }

    private val paused: Boolean get() = state.value.status != LinkStatus.Ready || updating || photo

    fun push() {
        if (paused) return
        sendIfChanged(FRAME_TIME, hubJson.encodeToString(TimeInfo(utcOffsetMin())))
        sendIfChanged(FRAME_WEATHER, hubJson.encodeToString(hub.weather.report.value))
        sendIfChanged(FRAME_AGENDA, hubJson.encodeToString(hub.agendaNow()))
        sendIfChanged(FRAME_DISPLAY, hubJson.encodeToString(hub.display()))
    }

    private suspend fun pushLists() {
        val device = linked ?: return
        if (paused) return
        val json = runCatching { hub.lists(device) }.getOrElse { return log("WARN ble lists ${it.message}") }
        if (!paused) sendIfChanged(FRAME_LISTS, json)
    }

    fun send(bytes: ByteArray): Boolean {
        if (!ready) return false
        write(bytes)
        return true
    }

    suspend fun sendDrained(bytes: ByteArray, timeoutMs: Long = DRAIN_MS): Boolean {
        if (!send(bytes)) return false
        return drained(timeoutMs)
    }

    private suspend fun drained(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (synchronized(outbox) { outbox.isNotEmpty() || writing }) {
            if (!ready || System.currentTimeMillis() > end) return false
            delay(5)
        }
        return ready
    }

    fun sendPhoto(frame: ByteArray): Boolean {
        if (state.value.status != LinkStatus.Ready || updating) return false
        photo = true
        write(frame(FRAME_PHOTO, frame))
        log("INFO ble photo sent bytes=${frame.size}")
        scope.launch {
            drained(PHOTO_DRAIN_MS)
            photo = false
            push()
            pushLists()
        }
        return true
    }

    fun addWifi(ssid: String, password: String): Boolean {
        if (state.value.status != LinkStatus.Ready) return false
        write(frame(FRAME_WIFI_ADD, hubJson.encodeToString(WifiAdd(ssid, password)).encodeToByteArray()))
        log("INFO ble wifi_add sent")
        return true
    }

    fun forgetWifi(ssid: String): Boolean {
        if (state.value.status != LinkStatus.Ready) return false
        write(frame(FRAME_WIFI_FORGET, hubJson.encodeToString(WifiForget(ssid)).encodeToByteArray()))
        log("INFO ble wifi_forget sent")
        return true
    }

    private fun connect(device: BluetoothDevice, auto: Boolean) {
        state.update { it.copy(status = LinkStatus.Connecting) }
        gatt = device.connectGatt(context, auto, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun retry() {
        if (running) main.postDelayed(::link, RETRY_MS)
    }

    private fun link() {
        val address = sticky()
        state.update { it.copy(address = address) }
        if (!running || gatt != null || address == null) return
        val device = adapter.getRemoteDevice(address)
        connect(device, device.bondState == BluetoothDevice.BOND_BONDED)
    }

    fun start() {
        if (running) return
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return log("WARN ble off: no Bluetooth permission")
        }
        running = true
        watchers = listOf(
            scope.launch { hub.weather.report.collect { push() } },
            scope.launch { hub.agenda.collect { push() } },
            scope.launch { hub.clockFace.collect { push() } },
            scope.launch { hub.weatherStyle.collect { push() } },
            scope.launch { hub.hourFormat.collect { push() } },
            scope.launch { hub.sound.collect { push() } },
            scope.launch { hub.buzzHz.collect { push() } },
            scope.launch { hub.night.collect { push() } },
            scope.launch { hub.home.collect { push() } },
            scope.launch { hub.changes.collect { pushLists() } },
        )
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
        }
        context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
        link()
    }

    fun stop() {
        if (!running) return
        running = false
        watchers.forEach { it.cancel() }
        main.removeCallbacksAndMessages(null)
        context.unregisterReceiver(bondReceiver)
        gatt?.close()
        gatt = null
        reader.clear()
        resetOutbox()
        state.update { it.copy(status = LinkStatus.Off, passkeyWanted = false) }
    }

    fun enterPasskey(code: String) {
        val device = pairing ?: return log("WARN ble passkey but no pairing request")
        log("INFO ble passkey entered")
        device.setPin(code.toByteArray())
    }

    fun relink() {
        gatt?.close()
        gatt = null
        reader.clear()
        resetOutbox()
        state.update { it.copy(status = LinkStatus.Off, passkeyWanted = false) }
        link()
    }
}
