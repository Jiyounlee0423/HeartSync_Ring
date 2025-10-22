// app/src/main/java/com/example/heartsync/ble/DualRingBleClient.kt
package com.example.heartsync.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlin.coroutines.cancellation.CancellationException
import java.util.UUID
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

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
                    delay(1000)
                    continue
                }

                // 2) 콜백
                val cb = object: BluetoothGattCallback() {

                    // --- 내부 상태(이 GATT 인스턴스 한정) ---
                    private val cccdQueue: ArrayDeque<BluetoothGattDescriptor> = ArrayDeque()
                    private var enableSent = AtomicBoolean(false)

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
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            // MTU 먼저
                            val ok = g.requestMtu(247)
                            Log.d(TAG, "[$hand] requestMtu(247) ok=$ok")
                        } else {
                            setState(hand, ConnState.Reconnecting(attempt, "svc discover failed $status"))
                            runCatching { g.disconnect() }
                        }
                    }

                    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                        Log.d(TAG, "[$hand] onMtuChanged mtu=$mtu status=$status")
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        // 필요한 두 개만 CCCD 활성화
                        enableNeededCccds(g)
                    }

                    override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                        Log.d(TAG, "[$hand] onDescriptorWrite ${descriptor.characteristic.uuid} status=$status")
                        writeNextCccd(g)
                    }

                    override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                        val t = SystemClock.elapsedRealtimeNanos() / 1e9
                        val data = ch.value ?: return
                        // Telink 스트림 해석 (PPG만 추출)
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
                        var count = 0
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
                                    count++
                                } else {
                                    Log.d(TAG, "[$hand] ${ch.uuid} has no CCCD")
                                }
                            }
                        }
                        if (count == 0) {
                            Log.w(TAG, "[$hand] No target CCCDs found, try ENABLE anyway")
                            sendEnableWithDelay(g)
                        } else {
                            writeNextCccd(g)
                        }
                    }

                    private fun writeNextCccd(g: BluetoothGatt) {
                        val next: BluetoothGattDescriptor? = if (cccdQueue.isEmpty()) null else cccdQueue.removeFirst()
                        if (next == null) {
                            sendEnableWithDelay(g)
                            return
                        }
                        val ok = g.writeDescriptor(next)
                        Log.d(TAG, "[$hand] writeDescriptor(CCCD) -> $ok (ch=${next.characteristic.uuid})")
                        if (!ok) {
                            // 실패 시 다음으로 넘어감(막힘 방지)
                            writeNextCccd(g)
                        }
                    }

                    private fun sendEnableWithDelay(g: BluetoothGatt) {
                        if (enableSent.getAndSet(true)) return
                        scope.launch {
                            delay(200)
                            writeEnable(g)
                            delay(150)
                            writeEnable(g) // 일부 기기 첫 패킷 드롭
                            Log.d(TAG, "[$hand] ENABLE x2 sent")
                        }
                    }
                }

                // 3) 연결 시작
                gatt = if (Build.VERSION.SDK_INT >= 31) {
                    device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(ctx, false, cb)
                }

                // 4) 스톨 감시
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
                    gatt?.disconnect()
                    gatt?.close()
                }
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
    private fun writeEnable(g: BluetoothGatt) = writeCmd(g, R02Proto.RXTX_WRITE, R02Proto.ENABLE)

    @SuppressLint("MissingPermission")
    private fun writeDisable(g: BluetoothGatt) = writeCmd(g, R02Proto.RXTX_WRITE, R02Proto.DISABLE)

    @SuppressLint("MissingPermission")
    private fun writeCmd(g: BluetoothGatt, chUuid: UUID, payload: ByteArray) {
        // 어떤 서비스에 있든 캐릭 UUID로 직접 찾는다
        var target: BluetoothGattCharacteristic? = null
        val svcs = g.services ?: emptyList()
        outer@ for (svc in svcs) {
            val chars = svc.characteristics ?: continue
            for (ch in chars) {
                if (ch.uuid == chUuid) { target = ch; break@outer }
            }
        }
        val ch = target ?: run {
            Log.w(TAG, "writeCmd: characteristic $chUuid not found")
            return
        }

        if (Build.VERSION.SDK_INT >= 33) {
            val res = g.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            Log.d(TAG, "writeCmd API33+ NO_RESPONSE $chUuid res=$res")
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ch.value = payload
            val res = g.writeCharacteristic(ch)
            Log.d(TAG, "writeCmd legacy NO_RESPONSE $chUuid res=$res")
        }
    }

    // ---- 장치 탐색 ----
    @SuppressLint("MissingPermission")
    private suspend fun resolveDevice(mac: String?, namePrefix: String?): BluetoothDevice? {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) return null

        // 1) MAC 이 있으면 바로 사용
        if (!mac.isNullOrBlank()) {
            return runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
        }

        // 2) 스캔으로 이름 프리픽스 매칭
        val scanner = adapter.bluetoothLeScanner ?: return null
        val res = CompletableDeferred<BluetoothDevice?>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device?.name ?: result.scanRecord?.deviceName ?: ""
                if (namePrefix.isNullOrBlank() || name.startsWith(namePrefix!!)) {
                    res.complete(result.device)
                }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (r in results) onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r)
            }
            override fun onScanFailed(errorCode: Int) {
                res.complete(null)
            }
        }
        scanner.startScan(null, ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        val device = withTimeoutOrNull(8000L) { res.await() }
        runCatching { scanner.stopScan(cb) }
        return device
    }
}
