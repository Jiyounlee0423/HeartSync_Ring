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

/** 그래프용: 모노토닉 타임스탬프(sec) + 원시 PPG */
data class RawSample(val tMonoS: Double, val ppg: Float)

/** 내부 연결 상태 로그용 */
sealed interface ConnState {
    data class Connected(val name: String?, val addr: String): ConnState
    data class Reconnecting(
        val attempt: Int,
        val reason: String?,
        val name: String?,          // 재연결 대상 이름
        val addr: String?           // 재연결 대상 주소
    ): ConnState
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
    val leftFlow:  SharedFlow<RawSample> = leftFlowMutable
    val rightFlow: SharedFlow<RawSample> = rightFlowMutable

    private val stateFlowMutable = MutableStateFlow<Map<String, ConnState>>(emptyMap())
    val connStates: StateFlow<Map<String, ConnState>> = stateFlowMutable

    private var job: Job? = null

    /** 현재 연결된 GATT 인스턴스(손별) — 안전 분리를 위해 보관 */
    private val liveGatt = ConcurrentHashMap<String, BluetoothGatt?>() // key: "left"/"right"

    /** === 신규 헬퍼: 반대 손 / 현재 주소 조회 === */
    private fun otherHand(hand: String) = if (hand == "left") "right" else "left"
    private fun currentAddrOf(hand: String): String? = liveGatt[hand]?.device?.address

    fun start(durationSec: Int? = null) {
        // === 1차 가드: 좌/우 MAC이 동일하게 지정된 경우 즉시 중단
        if (!leftMac.isNullOrBlank() && !rightMac.isNullOrBlank() &&
            leftMac.equals(rightMac, ignoreCase = true)
        ) {
            Log.e(TAG, "❌ 동일 MAC이 좌/우에 지정됨: $leftMac — 시작을 취소합니다.")
            setState("left",  ConnState.Disconnected("duplicate_mac"))
            setState("right", ConnState.Disconnected("duplicate_mac"))
            return
        }

        stop()
        job = scope.launch(Dispatchers.IO) {
            supervisorScope {
                val jL = launch { loopOne(hand = "left",  mac = leftMac,  namePrefix = leftNamePrefix,  out = leftFlowMutable) }
                val jR = launch { loopOne(hand = "right", mac = rightMac, namePrefix = rightNamePrefix, out = rightFlowMutable) }
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
        scope.launch { resetAll() } // 코루틴만 끊으면 잔여 notify가 남을 수 있어 즉시 안전 분리 시도
    }

    /** 외부 공개: 두 센서 모두 안전 분리 (CCCD off → DISABLE → disconnect/close) */
    @SuppressLint("MissingPermission")
    suspend fun resetAll() = withContext(Dispatchers.IO) {
        // ★★★ 동시성 안전을 위해 스냅샷을 순회
        val snapshot = liveGatt.toMap()
        for ((hand, g) in snapshot) {
            if (g != null) {
                runCatching { writeDisable(g) }
                runCatching { disableAllCccd(g) }
                runCatching { g.disconnect() }
                runCatching { g.close() }
            }
            // ★★★ null put 대신 remove
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
                val exclude = currentAddrOf(otherHand(hand))
                    ?: if (hand == "left") rightMac else leftMac

                val device = resolveDevice(mac, namePrefix, excludeAddr = exclude)
                if (device == null) {
                    setState(hand, ConnState.Reconnecting(attempt, "not found", null, null))
                    delay(1000); continue
                }

                // 2) 콜백
                val cb = object: BluetoothGattCallback() {

                    // --- 내부 상태(이 GATT 인스턴스 한정) ---
                    private val cccdQueue: ArrayDeque<BluetoothGattDescriptor> = ArrayDeque()
                    private val enableSent = AtomicBoolean(false)

                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        Log.d(TAG, "[$hand] onConnectionStateChange status=$status newState=$newState")
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            setState(hand, ConnState.Connected(device.name, device.address))

                            // === 연결 직후 2차 가드: 반대쪽과 동일 MAC이면 즉시 분리
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
                            val ok = g.requestMtu(247)
                            Log.d(TAG, "[$hand] requestMtu(247) ok=$ok")
                        } else {
                            setState(hand, ConnState.Reconnecting(attempt, "svc discover failed $status", device.name, device.address))
                            runCatching { g.disconnect() }
                        }
                    }

                    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                        Log.d(TAG, "[$hand] onMtuChanged mtu=$mtu status=$status")
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        enableNeededCccds(g)
                    }

                    override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                        writeNextCccd(g)
                    }

                    override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                        val t = SystemClock.elapsedRealtimeNanos() / 1e9
                        val data = ch.value ?: return
                        // Telink 스트림 해석 (PPG만 추출) — R02Proto는 프로젝트에 이미 존재한다고 가정
                        try {
                            val frames = R02Proto.decodeTelinkStream(data)
                            for (f in frames) {
                                val p = f.ppg ?: continue
                                lastPpgMono = t
                                out.tryEmit(RawSample(t, p.toFloat()))
                            }
                        } catch (_: Throwable) { /* ignore */ }
                    }

                    // ===== 내부 도우미 =====

                    private fun enableNeededCccds(g: BluetoothGatt) {
                        cccdQueue.clear()
                        val targets = setOf(R02Proto.RXTX_NOTIFY, R02Proto.MAIN_NOTIFY)
                        val svcs = g.services ?: return
                        for (svc in svcs) {
                            for (ch in svc.characteristics ?: emptyList()) {
                                if (ch.uuid !in targets) continue
                                val p = ch.properties
                                val canNotify = (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                                val canIndicate = (p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                                if (!canNotify && !canIndicate) continue

                                val okSet = g.setCharacteristicNotification(ch, true)
                                val cccd = ch.getDescriptor(CCCD_UUID)
                                if (cccd != null) {
                                    cccd.value = if (canNotify)
                                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    else
                                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                    cccdQueue.addLast(cccd)
                                    Log.d(TAG, "[$hand] queue CCCD for ${ch.uuid} (okSet=$okSet)")
                                } else {
                                    Log.d(TAG, "[$hand] ${ch.uuid} has no CCCD")
                                }
                            }
                        }
                        writeNextCccd(g)
                    }

                    private fun writeNextCccd(g: BluetoothGatt) {
                        val next: BluetoothGattDescriptor? =
                            if (cccdQueue.isEmpty()) null else cccdQueue.removeFirst()
                        if (next == null) {
                            if (enableSent.compareAndSet(false, true)) {
                                scope.launch {
                                    delay(200); writeEnable(g)
                                    delay(150); writeEnable(g) // 일부 기기 첫 패킷 드롭 방지
                                }
                            }
                            return
                        }
                        val ok = g.writeDescriptor(next)
                        if (!ok) {
                            // 실패 시 다음으로 넘어감(막힘 방지)
                            writeNextCccd(g)
                        }
                    }
                }

                // 3) 연결 시작
                gatt = if (Build.VERSION.SDK_INT >= 31) {
                    device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(ctx, false, cb)
                }
                // ★★★ 추가: null 가드
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

                // ★★★ gatt가 null이 아닐 때만 보관
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
                setState(
                    hand,
                    ConnState.Reconnecting(
                        attempt = attempt,
                        reason = e.message,
                        name = null,
                        addr = null
                    )
                )
                delay(1000L * attempt.coerceAtMost(5))
            } finally {
                runCatching { gatt?.disconnect(); gatt?.close() }
                // ★★★ ConcurrentHashMap은 null value 불가 → remove 사용
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
    private fun writeEnable(g: BluetoothGatt) =
        writeCmd(g, R02Proto.RXTX_WRITE, R02Proto.ENABLE)

    @SuppressLint("MissingPermission")
    private fun writeDisable(g: BluetoothGatt) =
        writeCmd(g, R02Proto.RXTX_WRITE, R02Proto.DISABLE)

    @SuppressLint("MissingPermission")
    private fun writeCmd(g: BluetoothGatt, chUuid: UUID, payload: ByteArray) {
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
            val res = g.writeCharacteristic(
                ch, payload,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
            Log.d(TAG, "writeCmd API33+ NO_RESPONSE $chUuid res=$res")
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ch.value = payload
            val res = g.writeCharacteristic(ch)
            Log.d(TAG, "writeCmd legacy NO_RESPONSE $chUuid res=$res")
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

        // 1) MAC 이 있으면 바로 사용 (단, 제외 주소와 같으면 null)
        if (!mac.isNullOrBlank()) {
            if (!excludeAddr.isNullOrBlank() && mac.equals(excludeAddr, ignoreCase = true)) {
                Log.w(TAG, "resolveDevice: excluded mac=$mac")
                return null
            }
            return runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
        }

        // 2) 스캔으로 이름 프리픽스 매칭 (제외 주소는 건너뜀)
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
                    runCatching { g.writeDescriptor(cccd, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) }
                } else {
                    @Suppress("DEPRECATION")
                    runCatching {
                        cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(cccd)
                    }
                }
            }
        }
    }
}


