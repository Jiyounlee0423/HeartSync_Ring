package com.example.heartsync.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.util.UUID
import kotlin.math.min

/* ─────────────────────────────  모델  ───────────────────────────── */

data class PpgSample(val tMonoS: Double, val raw: Int, val filt: Float)

sealed interface ConnState {
    val name: String?
    val addr: String
    data class Connecting(override val name: String?, override val addr: String): ConnState
    data class Connected(override val name: String?, override val addr: String): ConnState
    data class Ready(override val name: String?, override val addr: String): ConnState
    data class Reconnecting(override val name: String?, override val addr: String, val attempt: Int): ConnState
    data class Disconnected(override val name: String?, override val addr: String, val reason: Int?): ConnState
}

/* ───────────────────────  UUID (R02 + Nordic NUS) ─────────────────────── */

private val UUID_SVC  = UUID.fromString("6E40FFF0-B5A3-F393-E0A9-E50E24DCCA9E")
private val UUID_RX   = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // write
private val UUID_TX   = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // notify
private val UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

private val UUID_MAIN_SVC   = UUID.fromString("de5bf728-d711-4e47-af26-65e3012a5dc7")
private val UUID_MAIN_RX    = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7")
private val UUID_MAIN_TX    = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")

/* ─────────────────────────  유틸/필터  ───────────────────────── */

private fun Context.hasPerm(p: String) =
    checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

private inline fun <T> withBtConnect(ctx: Context, crossinline block: () -> T?): T? = try {
    if (Build.VERSION.SDK_INT >= 31 && !ctx.hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
        Log.w("BT","BLUETOOTH_CONNECT not granted"); null
    } else block()
} catch (se: SecurityException) { Log.w("BT","SecurityException: ${se.message}"); null }

private class Ema(private val a: Double) {
    private var y = 0.0; private var inited = false
    fun step(x: Double): Double {
        y = if (!inited) { inited = true; x } else { a * x + (1 - a) * y }
        return y
    }
}
private class PpgBP50 {
    private val slow = Ema(2.0/(50.0*1.0+1.0))
    private val fast = Ema(2.0/(50.0*(1.0/8.0)+1.0))
    fun step(x: Double): Double { val tr=slow.step(x); return fast.step(x-tr) }
}

private fun createCmd(hex: String): ByteArray {
    val b = mutableListOf<Int>(); var i=0
    while(i<hex.length){ b += hex.substring(i, min(i+2,hex.length)).toInt(16); i+=2 }
    while(b.size<15) b+=0; b += (b.sum() and 0xFF)
    return b.map{it.toByte()}.toByteArray()
}
private val CMD_BATTERY = createCmd("03")
private val CMD_SET_UNITS_METRIC = createCmd("0a0200")
private val CMD_ENABLE_RAW = createCmd("a104")
private val CMD_DISABLE_RAW = createCmd("a102")

private fun split16(data: ByteArray): List<ByteArray> {
    if (data.size==16) return listOf(data)
    val out=mutableListOf<ByteArray>(); var o=0
    while(o+16<=data.size){ out+=data.copyOfRange(o,o+16); o+=16 }
    return out
}
private fun parsePpg(frame: ByteArray): Int? {
    if (frame.size!=16) return null
    if ((frame[0].toInt() and 0xFF)!=0xA1) return null
    if ((frame[1].toInt() and 0xFF)!=0x02) return null
    val b=frame; return ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
}

/* ─────────────────────  헬퍼: 권한/로그  ───────────────────── */

private fun propStr(p: Int) = buildList {
    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
    if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
    if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
}.joinToString("|")

private fun canWrite(ch: BluetoothGattCharacteristic?): Boolean {
    ch ?: return false
    val p = ch.properties
    return (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 ||
            (p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
}

/* ─────────────────────  GATT 클라이언트 (한쪽 링)  ───────────────────── */

@SuppressLint("MissingPermission")
class R02GattClient(
    private val ctx: Context,
    private val device: BluetoothDevice,
    private val scope: CoroutineScope,
    private val tag: String,
    private val autoReconnect: Boolean = true,
    private val maxAttempts: Int = 5
){
    private var gatt: BluetoothGatt? = null
    private val bp = PpgBP50()
    private var attempt = 0
    private var readyOnce = false

    private var writeChNus:  BluetoothGattCharacteristic? = null
    private var writeChMain: BluetoothGattCharacteristic? = null

    private val _conn = MutableStateFlow<ConnState>(
        ConnState.Disconnected(device.name, device.address, null)
    )
    val conn: StateFlow<ConnState> = _conn

    private val _ppg = MutableSharedFlow<PpgSample>(
        replay=0, extraBufferCapacity=4096, onBufferOverflow=BufferOverflow.DROP_OLDEST
    )
    val ppg: SharedFlow<PpgSample> = _ppg

    fun connect() {
        _conn.value = ConnState.Connecting(device.name, device.address)
        val transport = if (Build.VERSION.SDK_INT>=23) BluetoothDevice.TRANSPORT_LE else 0
        withBtConnect(ctx){ gatt = device.connectGatt(ctx,false,gattCb,transport); gatt }
    }

    fun disconnect() {
        attempt = 0; readyOnce=false
        withBtConnect(ctx){ gatt?.disconnect(); true }
        withBtConnect(ctx){ gatt?.close(); true }
        gatt = null
        _conn.value = ConnState.Disconnected(device.name, device.address, null)
    }

    /** CCCD enable (notify→indic fallback) */
    @Suppress("DEPRECATION")
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        try {
            if (!g.setCharacteristicNotification(ch, true)) {
                Log.w("R02-$tag", "setCharacteristicNotification=false for ${ch.uuid}")
                return false
            }
        } catch (se: SecurityException) {
            Log.w("R02-$tag", "setCharacteristicNotification SecurityException: ${se.message}")
            return false
        }
        val cccd = try { ch.getDescriptor(UUID_CCCD) } catch (se: SecurityException) {
            Log.w("R02-$tag","getDescriptor SecurityException: ${se.message}"); null
        } ?: run { Log.w("R02-$tag","CCCD not found for ${ch.uuid}"); return false }

        fun writeCccd(value: ByteArray): Boolean = try {
            if (Build.VERSION.SDK_INT>=33) {
                g.writeDescriptor(cccd, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                cccd.value = value; g.writeDescriptor(cccd)
            }
        } catch (_: SecurityException) { false }

        if (writeCccd(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) return true
        val indic = writeCccd(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
        if (!indic) Log.w("R02-$tag","CCCD write failed for ${ch.uuid} (notif/indic 모두 실패)")
        return indic
    }

    private fun resolveWriteChars(g: BluetoothGatt) {
        writeChNus  = withBtConnect(ctx){ g.getService(UUID_SVC)?.getCharacteristic(UUID_RX) }
        writeChMain = withBtConnect(ctx){ g.getService(UUID_MAIN_SVC)?.getCharacteristic(UUID_MAIN_RX) }
    }

    /** write: noRsp→response fallback */
    @Suppress("DEPRECATION")
    private fun writeNoRsp(data: ByteArray) {
        val g = gatt ?: return
        if (writeChNus==null && writeChMain==null) resolveWriteChars(g)

        fun tryWrite(ch: BluetoothGattCharacteristic?, preferNoRsp: Boolean): Boolean {
            ch ?: return false
            val props = ch.properties
            val supportNoRsp = (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            val supportWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0

            val candidates = buildList {
                if (preferNoRsp && supportNoRsp) add(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                if (supportWrite) add(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                if (!preferNoRsp && supportNoRsp) add(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            }

            for (t in candidates) {
                val ok = try {
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeCharacteristic(ch, data, t) ==
                                android.bluetooth.BluetoothStatusCodes.SUCCESS
                    } else {
                        ch.writeType = t; ch.value = data; g.writeCharacteristic(ch)
                    }
                } catch (se: SecurityException) {
                    Log.w("R02-$tag","write SecurityException ${se.message}")
                    false
                }
                if (ok) return true
            }
            Log.w("R02-$tag","write fail ch=${ch.uuid} props=${propStr(props)}")
            return false
        }

        val ok = tryWrite(writeChNus,  true) || tryWrite(writeChMain, true)
        if (!ok) Log.w("R02-$tag","writeNoRsp failed on both RX chars")
    }

    private fun sendInit() {
        scope.launch {
            writeNoRsp(CMD_BATTERY);          delay(80)
            writeNoRsp(CMD_SET_UNITS_METRIC); delay(80)
            writeNoRsp(CMD_ENABLE_RAW)
        }
    }

    private fun kickRawUntilReady() {
        scope.launch {
            var tries = 0
            while (!readyOnce && tries < 8) {
                writeNoRsp(CMD_BATTERY); delay(80)
                writeNoRsp(CMD_SET_UNITS_METRIC); delay(80)
                writeNoRsp(CMD_ENABLE_RAW)
                tries++; delay(700)
            }
        }
    }

    private fun scheduleReconnect() {
        if (!autoReconnect || attempt>=maxAttempts) return
        attempt += 1
        _conn.value = ConnState.Reconnecting(device.name, device.address, attempt)
        scope.launch {
            delay(600L*attempt)
            connect()
        }
    }

    /* ──────────────── 콜백 ──────────────── */
    private val gattCb = object: BluetoothGattCallback(){
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState==BluetoothProfile.STATE_CONNECTED){
                _conn.value = ConnState.Connected(g.device.name, g.device.address)
                withBtConnect(ctx){ g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); true }
                if (Build.VERSION.SDK_INT>=26){
                    withBtConnect(ctx){
                        g.setPreferredPhy(BluetoothDevice.PHY_LE_2M, BluetoothDevice.PHY_LE_2M, BluetoothDevice.PHY_OPTION_NO_PREFERRED); true
                    }
                }
                withBtConnect(ctx){ g.requestMtu(247); true }
                withBtConnect(ctx){ g.discoverServices(); true }
            } else if (newState==BluetoothProfile.STATE_DISCONNECTED){
                readyOnce=false
                _conn.value = ConnState.Disconnected(g.device.name, g.device.address, status)
                scheduleReconnect()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w("R02-$tag","servicesDiscovered status=$status → rediscover")
                withBtConnect(ctx){ g.discoverServices(); true }
                return
            }
            resolveWriteChars(g)
            val nusTx  = withBtConnect(ctx){ g.getService(UUID_SVC)?.getCharacteristic(UUID_TX) }
            val mainTx = withBtConnect(ctx){ g.getService(UUID_MAIN_SVC)?.getCharacteristic(UUID_MAIN_TX) }
            val nusRx  = writeChNus
            val mainRx = writeChMain

            nusTx?.let  { Log.i("R02-$tag","NUS TX props=${propStr(it.properties)}") }
            mainTx?.let { Log.i("R02-$tag","MAIN TX props=${propStr(it.properties)}") }
            nusRx?.let  { Log.i("R02-$tag","NUS RX props=${propStr(it.properties)}") }
            mainRx?.let { Log.i("R02-$tag","MAIN RX props=${propStr(it.properties)}") }

            val nusOk  = nusTx?.let  { enableNotify(g, it) }  ?: false
            val mainOk = mainTx?.let { enableNotify(g, it) } ?: false
            Log.i("R02-$tag", "notify enabled: NUS=$nusOk, MAIN=$mainOk")

            val writeOk = canWrite(nusRx) || canWrite(mainRx)
            if ((nusOk || mainOk) && writeOk) {
                scope.launch { delay(150); sendInit(); kickRawUntilReady() }
            } else {
                Log.w("R02-$tag","gate not open → notify=${nusOk||mainOk}, writeOk=$writeOk (재시도)")
                scope.launch {
                    delay(350)
                    withBtConnect(ctx){ g.discoverServices(); true }
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, s: Int) {
            scope.launch { delay(120); sendInit(); kickRawUntilReady() }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val isNus  = ch.uuid == UUID_TX
            val isMain = ch.uuid == UUID_MAIN_TX
            if (!isNus && !isMain) return
            val frames = split16(ch.value ?: return)
            for (f in frames) {
                val raw = parsePpg(f) ?: continue
                val t = SystemClock.elapsedRealtimeNanos() / 1e9
                val filt = bp.step(raw.toDouble()).toFloat()
                _ppg.tryEmit(PpgSample(t, raw, filt))
                if (!readyOnce) {
                    readyOnce = true; attempt = 0
                    _conn.value = ConnState.Ready(g.device.name, g.device.address)
                }
            }
        }
    }
}

/* ─────────────────────  듀얼 관리 (좌/우)  ───────────────────── */

class DualRingBleClient(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val leftMac: String? = null,
    private val rightMac: String? = null,
    private val leftNamePrefix: String? = null,
    private val rightNamePrefix: String? = null,
    private val stallTimeoutSec: Double = 5.0
) {
    private var leftClient: R02GattClient? = null
    private var rightClient: R02GattClient? = null

    private val _stateL = MutableStateFlow<ConnState?>(null)
    private val _stateR = MutableStateFlow<ConnState?>(null)

    val connStates: StateFlow<Map<String, ConnState>> =
        combine(_stateL, _stateR) { l, r ->
            buildMap {
                l?.let { put("left", it) }
                r?.let { put("right", it) }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val _ppgL = MutableSharedFlow<PpgSample>(extraBufferCapacity=4096, onBufferOverflow=BufferOverflow.DROP_OLDEST)
    private val _ppgR = MutableSharedFlow<PpgSample>(extraBufferCapacity=4096, onBufferOverflow=BufferOverflow.DROP_OLDEST)
    val ppgL: SharedFlow<PpgSample> = _ppgL
    val ppgR: SharedFlow<PpgSample> = _ppgR

    @Volatile private var connecting = false
    @Volatile private var lastLeft: String? = null
    @Volatile private var lastRight: String? = null

    fun start() {
        val lAddr = leftMac; val rAddr = rightMac
        if (lAddr==null || rAddr==null) {
            Log.w("DualRing","start() called without both MACs")
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val lDev = try { adapter.getRemoteDevice(lAddr) } catch (_:Throwable){ null }
        val rDev = try { adapter.getRemoteDevice(rAddr) } catch (_:Throwable){ null }
        if (lDev==null || rDev==null) {
            Log.w("DualRing","Invalid MAC(s): L=$lAddr R=$rAddr"); return
        }
        connectBoth(lDev, rDev)
    }

    fun stop() { disconnect() }

    fun startWith(left: String, right: String) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val lDev = try { adapter.getRemoteDevice(left) } catch (_:Throwable){ null }
        val rDev = try { adapter.getRemoteDevice(right) } catch (_:Throwable){ null }
        if (lDev==null || rDev==null) {
            Log.w("DualRing","startWith invalid MACs"); return
        }
        connectBoth(lDev, rDev)
    }

    suspend fun resetAll() { disconnect() }

    private fun connectBoth(left: BluetoothDevice, right: BluetoothDevice) {
        val curL = left.address; val curR = right.address
        if (connecting && curL==lastLeft && curR==lastRight) {
            Log.w("DualRing","connectBoth ignored (duplicate call)")
            return
        }
        connecting = true; lastLeft = curL; lastRight = curR

        disconnect()
        leftClient = R02GattClient(ctx, left, scope, "L").also { c ->
            scope.launch { c.conn.collect { _stateL.value = it } }
            scope.launch { c.ppg.collect { _ppgL.tryEmit(it) } }
            c.connect()
        }
        rightClient = R02GattClient(ctx, right, scope, "R").also { c ->
            scope.launch { c.conn.collect { _stateR.value = it } }
            scope.launch { c.ppg.collect { _ppgR.tryEmit(it) } }
            c.connect()
        }

        scope.launch { delay(3000); connecting = false }
    }

    private fun disconnect() {
        leftClient?.apply { disconnect() }
        rightClient?.apply { disconnect() }
        leftClient = null; rightClient = null
        _stateL.value?.let { _stateL.value = ConnState.Disconnected(it.name, it.addr, null) }
        _stateR.value?.let { _stateR.value = ConnState.Disconnected(it.name, it.addr, null) }
    }
}
