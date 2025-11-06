@file:Suppress("DEPRECATION", "unused")

// app/src/main/java/com/example/heartsync/ble/DualRingBleClient.kt
package com.example.heartsync.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import android.bluetooth.BluetoothStatusCodes // ✅ API33 상태코드 비교용

/** 그래프용: 모노토닉 타임스탬프(sec) + 원시 PPG */
data class RawSample(val tMonoS: Double, val ppg: Float)

/** 내부 연결 상태 로그용 */
sealed interface ConnState {
    data class Connected(val name: String?, val addr: String): ConnState
    data class Reconnecting(
        val attempt: Int,
        val reason: String?,
        val name: String?,
        val addr: String?
    ): ConnState
    data class Connecting(val addr: String): ConnState
    data class Discovering(val addr: String): ConnState
    data class Subscribing(val addr: String): ConnState
    data class Ready(val name: String?, val addr: String): ConnState
    data class Disconnected(val reason: String? = null): ConnState
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

    /** API 33+ 호환: descriptor와 value를 함께 큐잉하기 위한 자료형 (클래스 스코프) */
    private data class PendingCCCD(val desc: BluetoothGattDescriptor, val bytes: ByteArray)

    private val leftFlowMutable  = MutableSharedFlow<RawSample>(replay = 0, extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val rightFlowMutable = MutableSharedFlow<RawSample>(replay = 0, extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val leftFlow:  SharedFlow<RawSample> = leftFlowMutable
    val rightFlow: SharedFlow<RawSample> = rightFlowMutable

    private val stateFlowMutable = MutableStateFlow<Map<String, ConnState>>(emptyMap())
    val connStates: StateFlow<Map<String, ConnState>> = stateFlowMutable

    private var job: Job? = null

    /** 현재 연결된 GATT 인스턴스(손별) — 안전 분리를 위해 보관 */
    private val liveGatt = ConcurrentHashMap<String, BluetoothGatt?>() // key: "left"/"right"

    private fun otherHand(hand: String) = if (hand == "left") "right" else "left"
    private fun currentAddrOf(hand: String): String? = liveGatt[hand]?.device?.address

    /** 외부 시작 API — 좌/우 동시 루프 구동 */
    fun start(durationSec: Int? = null) {
        if (!leftMac.isNullOrBlank() && !rightMac.isNullOrBlank() && leftMac.equals(rightMac, ignoreCase = true)) {
            Log.e(TAG, "❌ 동일 MAC이 좌/우에 지정됨: $leftMac — 시작 취소")
            setState("left",  ConnState.Disconnected("duplicate_mac"))
            setState("right", ConnState.Disconnected("duplicate_mac"))
            return
        }
        stop()
        job = scope.launch(Dispatchers.IO) {
            supervisorScope {
                val jL = launch { loopOne("left",  leftMac,  leftNamePrefix,  leftFlowMutable) }
                val jR = launch { loopOne("right", rightMac, rightNamePrefix, rightFlowMutable) }
                if (durationSec != null) {
                    delay(durationSec * 1000L)
                    jL.cancel(); jR.cancel()
                }
            }
        }
    }

    /** 외부 중지 API — 코루틴 중단 + 안전 분리 */
    fun stop() {
        job?.cancel()
        job = null
        scope.launch { resetAll() }
    }

    /** 두 센서 모두 안전 분리 */
    @SuppressLint("MissingPermission")
    suspend fun resetAll() = withContext(Dispatchers.IO) {
        val snapshot = liveGatt.toMap()
        for ((hand, g) in snapshot) {
            if (g != null) {
                runCatching { writeDisable(g) }
                runCatching { disableAllCccd(g) }
                runCatching { g.disconnect() }
                runCatching { g.close() }
            }
            liveGatt.remove(hand)
            setState(hand, ConnState.Disconnected("user_reset"))
        }
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
                // 1) 장치 찾기 — 반대쪽에 이미 연결/지정된 주소는 제외
                val exclude = currentAddrOf(otherHand(hand)) ?: if (hand == "left") rightMac else leftMac
                val device = resolveDevice(mac, namePrefix, excludeAddr = exclude)
                if (device == null) {
                    setState(hand, ConnState.Reconnecting(attempt, "not found", null, null))
                    delay(1000); continue
                }

                // 2) 콜백
                val cb = object: BluetoothGattCallback() {

                    private val cccdQueue: ArrayDeque<PendingCCCD> = ArrayDeque()
                    private val enableSent = AtomicBoolean(false)

                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        Log.d(TAG, "[$hand] onConnectionStateChange status=$status newState=$newState")
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            setState(hand, ConnState.Connected(device.name, device.address))

                            val otherAddr = currentAddrOf(otherHand(hand))
                            if (otherAddr != null && otherAddr.equals(device.address, ignoreCase = true)) {
                                Log.w(TAG, "[$hand] 동일 MAC이 다른 손에 이미 연결됨 → 즉시 분리")
                                setState(hand, ConnState.Reconnecting(attempt, "duplicate_mac", device.name, device.address))
                                runCatching { g.disconnect() }
                                return
                            }
                            g.discoverServices()
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            setState(hand, ConnState.Reconnecting(attempt, "disconnected: $status", device.name, device.address))
                            runCatching { g.close() }
                        }
                    }

                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        Log.d(TAG, "[$hand] services discovered status=$status")
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            if (Build.VERSION.SDK_INT >= 21) {
                                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                                g.requestMtu(247)
                            }
                        } else {
                            setState(hand, ConnState.Reconnecting(attempt, "svc discover failed $status", device.name, device.address))
                            runCatching { g.disconnect() }
                        }
                    }

                    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                        Log.d(TAG, "[$hand] onMtuChanged mtu=$mtu status=$status")
                        if (Build.VERSION.SDK_INT >= 21) {
                            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        }
                        enableNeededCccds(g)
                    }

                    override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                        writeNextCccdLocal(g)
                    }

                    override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                        val t = SystemClock.elapsedRealtimeNanos() / 1e9
                        val data = ch.value ?: return
                        try {
                            val frames = R02Proto.decodeTelinkStream(data)
                            var gotPpg = false
                            for (f in frames) {
                                val p = f.ppg ?: continue
                                gotPpg = true
                                lastPpgMono = t
                                out.tryEmit(RawSample(t, p.toFloat()))
                            }
                            if (gotPpg && stateFlowMutable.value[hand] !is ConnState.Ready) {
                                setState(hand, ConnState.Ready(device.name, device.address))
                            }
                        } catch (_: Throwable) { /* ignore */ }
                    }

                    /** CCCD 활성화 대상 찾아 큐에 넣기 (API33+ 호환) */
                    private fun enableNeededCccds(g: BluetoothGatt) {
                        cccdQueue.clear()
                        val targets = setOf(R02Proto.RXTX_NOTIFY, R02Proto.MAIN_NOTIFY)
                        val services = g.services ?: return
                        for (svc in services) {
                            for (ch in svc.characteristics ?: emptyList()) {
                                if (ch.uuid !in targets) continue
                                val props = ch.properties
                                val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                                val canIndicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                                if (!canNotify && !canIndicate) continue

                                g.setCharacteristicNotification(ch, true)
                                val cccd = ch.getDescriptor(CCCD_UUID) ?: continue
                                val bytes = if (canNotify)
                                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                else
                                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE

                                cccdQueue.addLast(PendingCCCD(cccd, bytes))
                                Log.d(TAG, "[$hand] queue CCCD for ${ch.uuid}")
                            }
                        }
                        writeNextCccdLocal(g)
                    }

                    /** API 33+와 하위 버전 모두에서 동작하는 CCCD 쓰기 */
                    private fun writeNextCccdLocal(g: BluetoothGatt) {
                        val item = if (cccdQueue.isEmpty()) null else cccdQueue.removeFirst()
                        if (item == null) {
                            if (enableSent.compareAndSet(false, true)) {
                                scope.launch {
                                    delay(200); writeEnable(g)
                                    delay(150); writeEnable(g) // 첫 패킷 드롭 방지
                                }
                            }
                            return
                        }

                        // 1.5초 내 무패킷이면 ENABLE 재시도
                        scope.launch {
                            delay(1500)
                            if (lastPpgMono == 0.0) writeEnable(g)
                        }

                        // ✅ API33은 Int status 반환 → SUCCESS 비교로 Boolean화
                        val ok: Boolean = if (Build.VERSION.SDK_INT >= 33) {
                            val status = g.writeDescriptor(item.desc, item.bytes)
                            status == BluetoothStatusCodes.SUCCESS
                        } else {
                            item.desc.value = item.bytes
                            g.writeDescriptor(item.desc)
                        }
                        if (!ok) {
                            writeNextCccdLocal(g) // 실패 시 다음으로(막힘 방지)
                        }
                    }
                }

                // 3) 연결 시작
                gatt = if (Build.VERSION.SDK_INT >= 31) {
                    device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(ctx, false, cb)
                }
                if (gatt == null) {
                    setState(
                        hand,
                        ConnState.Reconnecting(
                            attempt = attempt,
                            reason = "connectGatt returned null",
                            name = device.name,
                            addr = device.address
                        )
                    )
                    delay(800)
                    continue
                }
                liveGatt[hand] = gatt

                // 4) 스톨 감시
                val pollPeriodMs = 500L
                while (currentCoroutineContext().isActive) {
                    delay(pollPeriodMs)
                    val nowS = SystemClock.elapsedRealtimeNanos() / 1e9
                    if (lastPpgMono == 0.0) continue
                    if (nowS - lastPpgMono > stallTimeoutSec) {
                        setState(
                            hand,
                            ConnState.Reconnecting(
                                attempt = attempt,
                                reason = "stall ${"%.1f".format(nowS - lastPpgMono)}s",
                                name = device.name,
                                addr = device.address
                            )
                        )
                        throw RuntimeException("stall")
                    }
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Throwable) {
                Log.w(TAG, "[$hand] loop error: ${e.message}")
                setState(hand, ConnState.Reconnecting(attempt, e.message, null, null))
                delay(1000L * attempt.coerceAtMost(5))
            } finally {
                runCatching { gatt?.disconnect(); gatt?.close() }
                liveGatt.remove(hand)
            }
        }
    }

    private fun setState(hand: String, st: ConnState) {
        val cur = stateFlowMutable.value.toMutableMap()
        cur[hand] = st
        stateFlowMutable.value = cur
    }

    // ---- 명령 전송 (ENABLE/DISABLE) ----
    @SuppressLint("MissingPermission")
    private fun writeEnable(g: BluetoothGatt) {
        writeCmdByUuid(g, R02Proto.RXTX_WRITE, R02Proto.ENABLE)
    }

    @SuppressLint("MissingPermission")
    private fun writeDisable(g: BluetoothGatt) {
        writeCmdByUuid(g, R02Proto.RXTX_WRITE, R02Proto.DISABLE)
    }

    @SuppressLint("MissingPermission")
    private fun writeCmdByUuid(g: BluetoothGatt, chUuid: UUID, payload: ByteArray) {
        var target: BluetoothGattCharacteristic? = null
        val svcs = g.services ?: emptyList()
        outer@ for (svc in svcs) {
            val chars = svc.characteristics ?: continue
            for (ch in chars) {
                if (ch.uuid == chUuid) { target = ch; break@outer }
            }
        }
        val ch = target ?: run { Log.w(TAG, "writeCmd: characteristic $chUuid not found"); return }

        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ch.value = payload
            g.writeCharacteristic(ch)
        }
    }

    // ---- 장치 탐색 (반대쪽 주소 제외 지원) ----
    @SuppressLint("MissingPermission")
    private suspend fun resolveDevice(
        mac: String?,
        namePrefix: String?,
        excludeAddr: String?
    ): BluetoothDevice? {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) return null

        // 1) MAC 우선 (제외 주소면 null)
        if (!mac.isNullOrBlank()) {
            if (!excludeAddr.isNullOrBlank() && mac.equals(excludeAddr, ignoreCase = true)) {
                Log.w(TAG, "resolveDevice: excluded mac=$mac"); return null
            }
            return runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
        }

        // 2) 스캔 매칭
        val scanner = adapter.bluetoothLeScanner ?: return null
        val res = CompletableDeferred<BluetoothDevice?>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val name = dev.name ?: result.scanRecord?.deviceName ?: ""
                val addr = dev.address
                if (!excludeAddr.isNullOrBlank() && excludeAddr.equals(addr, ignoreCase = true)) return
                if (namePrefix.isNullOrBlank() || name.startsWith(namePrefix)) {
                    res.complete(dev)
                }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (r in results) onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r)
            }
            override fun onScanFailed(errorCode: Int) { res.complete(null) }
        }
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            cb
        )
        val device = withTimeoutOrNull(8000L) { res.await() }
        runCatching { scanner.stopScan(cb) }
        return device
    }

    // ===== 안전 분리용 헬퍼 =====
    @SuppressLint("MissingPermission")
    private fun disableAllCccd(g: BluetoothGatt) {
        val targets = setOf(R02Proto.RXTX_NOTIFY, R02Proto.MAIN_NOTIFY)
        val svcs = g.services ?: return
        for (svc in svcs) {
            for (ch in svc.characteristics ?: emptyList()) {
                if (ch.uuid !in targets) continue
                runCatching { g.setCharacteristicNotification(ch, false) }
                val cccd = ch.getDescriptor(CCCD_UUID) ?: continue
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                } else {
                    cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }
        }
    }
}
