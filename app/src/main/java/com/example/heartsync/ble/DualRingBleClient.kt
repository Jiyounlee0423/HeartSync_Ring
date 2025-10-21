// app/src/main/java/com/example/heartsync/ble/DualRingBleClient.kt
package com.example.heartsync.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.util.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext

/** 그래프용: 모노토닉 타임스탬프(sec) + 원시 PPG */
data class RawSample(val tMonoS: Double, val ppg: Float)

/** 내부 연결 상태 로그용 */
sealed interface ConnState {
    data class Connected(val name: String?, val addr: String): ConnState
    data class Reconnecting(val attempt: Int, val reason: String?): ConnState
    data class Disconnected(val reason: String?): ConnState
}

class DualRingBleClient(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val leftMac: String? = null,
    private val rightMac: String? = null,
    private val leftNamePrefix: String? = "R02_",
    private val rightNamePrefix: String? = "R02_",
    private val stallTimeoutSec: Double = 5.0,
) {
    companion object {
        private const val TAG = "DualRingBle"
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val leftFlowMutable  = MutableSharedFlow<RawSample>(
        replay = 0, extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val rightFlowMutable = MutableSharedFlow<RawSample>(
        replay = 0, extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val leftFlow:  SharedFlow<RawSample> = leftFlowMutable.asSharedFlow()
    val rightFlow: SharedFlow<RawSample> = rightFlowMutable.asSharedFlow()

    private val stateFlowMutable = MutableStateFlow<Map<String, ConnState>>(emptyMap())
    val stateFlow: StateFlow<Map<String, ConnState>> = stateFlowMutable

    private var job: Job? = null

    fun start(durationSec: Int? = null) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            supervisorScope {
                val jL = launch {
                    loopOne(hand = "left",  mac = leftMac,  namePrefix = leftNamePrefix,  out = leftFlowMutable)
                }
                val jR = launch {
                    loopOne(hand = "right", mac = rightMac, namePrefix = rightNamePrefix, out = rightFlowMutable)
                }
                if (durationSec != null) {
                    delay(durationSec * 1000L)
                    jL.cancel(); jR.cancel()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    // ---- 단일 손 루프(자동 재연결 + 스톨 감지) ----
    @SuppressLint("MissingPermission")
    private suspend fun loopOne(
        hand: String,
        mac: String?,
        namePrefix: String?,
        out: MutableSharedFlow<RawSample>
    ) {
        var attempt = 0
        val cc = currentCoroutineContext()
        while (cc.isActive) {
            attempt += 1
            var gatt: BluetoothGatt? = null
            var lastPpgMono = 0.0
            try {
                // 1) 장치 찾기
                val device = resolveDevice(mac, namePrefix)
                if (device == null) {
                    setState(hand, ConnState.Reconnecting(attempt, "not found"))
                    delay(1000); continue
                }

                // 2) 연결 + 콜백
                val cb = object : BluetoothGattCallback() {

                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        Log.d(TAG, "[$hand] onConnectionStateChange status=$status newState=$newState")
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            setState(hand, ConnState.Connected(device.name, device.address))
                            g.discoverServices()
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            setState(hand, ConnState.Disconnected("disconnected: $status"))
                            runCatching { g.close() }
                        }
                    }

                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        Log.d(TAG, "[$hand] services discovered status=$status svcs=${g.services?.size ?: 0}")
                        dumpGatt(g)
                        val n = enableNotifications(g)
                        Log.d(TAG, "[$hand] notifications enabled on $n chars")

                        // CCCD 쓰기들이 비동기라 약간 대기 후 ENABLE 전송
                        scope.launch {
                            delay(300)
                            writeEnable(g)
                            Log.d(TAG, "[$hand] ENABLE command sent")
                        }
                    }

                    // (구) 시그니처 – API 33에서 deprecated
                    @Suppress("DEPRECATION")
                    override fun onCharacteristicChanged(
                        g: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic
                    ) {
                        handleNotifyFrom(hand, out, characteristic.uuid, characteristic.value)
                    }

                    // (신) 시그니처 – API 33+
                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray
                    ) {
                        handleNotifyFrom(hand, out, characteristic.uuid, value)
                    }

                    private fun handleNotifyFrom(
                        hand: String,
                        out: MutableSharedFlow<RawSample>,
                        uuid: UUID,
                        value: ByteArray
                    ) {
                        val nowS = SystemClock.elapsedRealtimeNanos() / 1e9
                        val frames = R02Proto.decodeTelinkStream(value)
                        var anyPpg = false
                        for (f in frames) {
                            val pv = f.ppg
                            if (pv != null) {
                                anyPpg = true
                                out.tryEmit(RawSample(nowS, pv.toFloat()))
                            }
                        }
                        if (anyPpg) {
                            lastPpgMono = nowS
                        } else {
                            // 스팸 방지: 낮은 레벨 로그
                            Log.v(TAG, "[$hand] notify from $uuid but no PPG parsed (len=${value.size})")
                        }
                    }
                }

                gatt = if (Build.VERSION.SDK_INT >= 31) {
                    device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(ctx, false, cb)
                }

                // 3) 스톨 감시
                val pollPeriodMs = 500L
                while (currentCoroutineContext().isActive) {
                    delay(pollPeriodMs)
                    val nowS = SystemClock.elapsedRealtimeNanos() / 1e9
                    if (lastPpgMono == 0.0) continue
                    if (nowS - lastPpgMono > stallTimeoutSec) {
                        setState(hand, ConnState.Reconnecting(attempt, "stall ${"%.1f".format(nowS - lastPpgMono)}s"))
                        throw RuntimeException("stall")
                    }
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Throwable) {
                Log.w(TAG, "[$hand] loop error: ${e.message}")
                setState(hand, ConnState.Reconnecting(attempt, e.message))
                delay(1000L * attempt.coerceAtMost(5))
            } finally {
                runCatching {
                    gatt?.let {
                        runCatching { writeDisable(it) }
                        runCatching { it.disconnect() }
                        runCatching { it.close() }
                    }
                }
            }
        }
    }

    private fun setState(hand: String, s: ConnState) {
        stateFlowMutable.value = stateFlowMutable.value.toMutableMap().apply { put(hand, s) }
    }

    // ---- 장치 찾기 (MAC 우선, 아니면 이름 prefix) ----
    @SuppressLint("MissingPermission")
    private fun resolveDevice(mac: String?, namePrefix: String?): BluetoothDevice? {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
        val bt = mgr.adapter ?: return null
        if (mac != null) return runCatching { bt.getRemoteDevice(mac) }.getOrNull()
        if (namePrefix == null) return null
        // 페어링된 기기에서 prefix 우선
        for (d in bt.bondedDevices) {
            val n = d.name ?: ""
            if (n.startsWith(namePrefix)) return d
        }
        return null
    }

    // ---- GATT 덤프(디버그) ----
    private fun dumpGatt(g: BluetoothGatt) {
        val svcs = g.services ?: return
        for (svc in svcs) {
            Log.d(TAG, "svc ${svc.uuid}")
            for (ch in svc.characteristics ?: emptyList()) {
                val p = ch.properties
                val flags = buildList {
                    if ((p and BluetoothGattCharacteristic.PROPERTY_READ) != 0) add("R")
                    if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) add("W")
                    if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) add("WNR")
                    if ((p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) add("N")
                    if ((p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) add("I")
                }.joinToString("|")
                Log.d(TAG, "  ch ${ch.uuid} props=$flags")
            }
        }
    }

    // ---- Notify 활성화(전수 조사: 모든 NOTIFY/INDICATE 캐릭에 구독) ----
    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt): Int {
        var enabled = 0
        val svcs = g.services ?: return 0
        for (svc in svcs) {
            for (ch in svc.characteristics ?: emptyList()) {
                val props = ch.properties
                val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                val canIndicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                if (!canNotify && !canIndicate) continue

                val okSet = g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(CCCD_UUID)
                val okDesc = if (cccd != null) {
                    cccd.value = if (canNotify)
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    g.writeDescriptor(cccd)
                } else false

                Log.d(
                    TAG,
                    "enable ${if (canNotify) "NOTIFY" else "INDICATE"} on ch=${ch.uuid} svc=${svc.uuid} set=$okSet writeCCCD=$okDesc"
                )
                enabled++
            }
        }
        return enabled
    }

    // ---- 명령 전송 (ENABLE/DISABLE) ----
    @SuppressLint("MissingPermission")
    private fun writeEnable(g: BluetoothGatt) = writeCmd(g, R02Proto.RXTX_WRITE, R02Proto.ENABLE)

    @SuppressLint("MissingPermission")
    private fun writeDisable(g: BluetoothGatt) = writeCmd(g, R02Proto.RXTX_WRITE, R02Proto.DISABLE)

    @SuppressLint("MissingPermission")
    private fun writeCmd(g: BluetoothGatt, chUuid: UUID, payload: ByteArray) {
        // 어떤 서비스에 있든 캐릭 UUID로 직접 찾는다
        var target: BluetoothGattCharacteristic? = null
        val svcs = g.services ?: emptyList()
        for (svc in svcs) {
            val chars = svc.characteristics ?: continue
            for (ch in chars) {
                if (ch.uuid == chUuid) { target = ch; break }
            }
            if (target != null) break
        }
        val ch = target ?: run {
            Log.w(TAG, "writeCmd: characteristic $chUuid not found")
            return
        }

        if (Build.VERSION.SDK_INT >= 33) {
            val res = g.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            Log.d(TAG, "writeCmd API33+ $chUuid res=$res")
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = payload
            val res = g.writeCharacteristic(ch)
            Log.d(TAG, "writeCmd legacy $chUuid res=$res")
        }
    }
}
