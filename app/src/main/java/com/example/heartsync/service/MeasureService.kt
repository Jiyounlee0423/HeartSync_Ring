// app/src/main/java/com/example/heartsync/service/MeasureService.kt
package com.example.heartsync.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.heartsync.data.remote.PpgEvent
import com.example.heartsync.data.remote.PpgRepository
import com.example.heartsync.ble.PpgBleClient
import com.example.heartsync.data.model.BleDevice
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
// ★ 불필요: kotlinx.coroutines.flow.collect import 제거
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout


class MeasureService : Service() {

    companion object {
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_DEVICE_ADDR = "extra_device_addr"
        const val NOTI_ID = 1001
        const val NOTI_CHANNEL_ID = "measuresvc"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var client: PpgBleClient
    private lateinit var repo: PpgRepository

    // 로그인 필수 (anonymous 금지)
    private var userId: String = ""
    private var sessionId: String = ""

    // --- 그래프용 EMA 상태 (서비스 멤버) ---
    private var emaL: Double? = null
    private var emaR: Double? = null
    private val EMA_ALPHA = 0.2  // 0.1~0.3 사이에서 조절

    private fun emaUpdate(prev: Double?, x: Double): Double =
        if (prev == null) x else EMA_ALPHA * x + (1 - EMA_ALPHA) * prev


    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this, NOTI_CHANNEL_ID, "HeartSync Measure")
        startForeground(NOTI_ID, NotificationCompat.Builder(this, NOTI_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("측정 중")
            .setContentText("로그인 중…")
            .setOngoing(true)
            .build())

        scope.launch {
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                auth.signInAnonymously().await()
            }
            userId = FirebaseAuth.getInstance().currentUser!!.uid

            val sid = newSessionId()
            PpgRepository.instance.setSessionId(sid)
            sessionId = sid
            Log.d("MeasureService","session ready -> $sid / uid=$userId")
            // ★ 여기! 세션 메타 1회 초기화 후 BLE 시작
            // (이 블록을 startBle() 호출 "바로 전"에 둡니다)
            launch(Dispatchers.IO) {
                try {
                    PpgRepository.instance.initSessionOnce(userId, sessionId)
                    Log.d("MeasureService", "session init OK")
                } catch (t: Throwable) {
                    Log.e("MeasureService", "session init FAIL", t)
                }
                withContext(Dispatchers.Main) {
                    startBle()   // 이제 BLE 시작
                }
            }
        }
    }
    // 바로 아래에 private 함수로 추가
    private suspend fun firestoreWarmupWrite() {
        val db = FirebaseFirestore.getInstance()
        val uid = FirebaseAuth.getInstance().currentUser!!.uid
        val test = db.collection("ppg_events").document(uid)
            .collection("debug").document()
        try {
            withTimeout(3_000) {
                test.set(mapOf("ping" to System.currentTimeMillis())).await()
            }
            Log.d("MeasureService","firestore warmup OK (${test.id})")
        } catch (t: Throwable) {
            Log.e("MeasureService","firestore warmup FAIL", t)
        }
    }

//    private fun parseSmoothed(line: String): Pair<Float, Float>? {
//        fun num(key: String): Float? =
//            Regex("""\b${key}=([-\d.]+)""").find(line)?.groupValues?.getOrNull(1)?.toFloatOrNull()
//
//        val l = num("PPGf_L") ?: num("smoothedL")
//        val r = num("PPGf_R") ?: num("smoothedR")
//        return if (l != null && r != null) l to r else null
//    }
    private fun startBle() {
        Log.d("MeasureService","startBle() called")
        client = PpgBleClient(
            ctx = this,
            onLine = { line ->
                Log.d("MeasureService", "onLine(raw)=${line.take(160)}")
                scope.launch(Dispatchers.IO) {
                    val ok = try {
                        PpgRepository.trySaveFromLine(line)
                    } catch (t: Throwable) {
                        Log.e("MeasureService", "trySaveFromLine threw", t)
                        false
                    }
                    Log.d("MeasureService", "trySaveFromLine -> $ok")
                    if (!ok) Log.w("MeasureService", "save skipped for line: ${line.take(160)}")
                }
            },
            onError = { e -> Log.e("MeasureService", "BLE error: $e") },
            filterByService = true
        )

    }

    // (A) 라인 파서 교체/추가
    private fun parseSmoothed(line: String): Pair<Float, Float>? {
        fun pick(key: String): Float? =
            Regex("""\b${key}=([-\d.]+)""").find(line)
                ?.groupValues?.getOrNull(1)
                ?.toFloatOrNull()

        // 1순위: PPGf_L/PPGf_R  |  2순위(혹시 있을 때): smoothedL/smoothedR
        val l = pick("PPGf_L") ?: pick("smoothedL")
        val r = pick("PPGf_R") ?: pick("smoothedR")
        return if (l != null && r != null) l to r else null
    }



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val devName = intent?.getStringExtra(EXTRA_DEVICE_NAME)
        val devAddr = intent?.getStringExtra(EXTRA_DEVICE_ADDR)

        if (devAddr.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        client = PpgBleClient(
            ctx = this,
            onLine = { line ->
                scope.launch(Dispatchers.IO) {
                    // 한 번의 호출로: 파싱 → (absTs 계산) emit → Firestore 저장
                    com.example.heartsync.data.remote.PpgRepository.trySaveFromLine(line)
                    // handleLine(line) 에서 추가 저장/업로드를 하고 있었다면 중복 저장 방지를 위해 정리
                }
            },
            onError = { e ->
                Log.e("MeasureService", "BLE error: $e")
            },
            filterByService = true
        )

        // 연결 시도
        client.connect(BleDevice(devName, devAddr))

        // 연결 상태에 따라 알림 텍스트 업데이트
        scope.launch {
            client.connectionState.collect { st ->
                val connected = st is PpgBleClient.ConnectionState.Connected
                MeasureStatusBus.setConnected(connected)

                // ★ 삭제: requestMtu/enableNotify/writeCommand 호출 (PpgBleClient 내부에서 수행)
                val text = when (st) {
                    is PpgBleClient.ConnectionState.Connected  -> "연결됨: ${st.device.address}"
                    is PpgBleClient.ConnectionState.Connecting -> "연결 중…"
                    is PpgBleClient.ConnectionState.Failed     -> "실패: ${st.reason}"
                    else -> "대기"
                }
                val n = NotificationCompat.Builder(this@MeasureService, NOTI_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("측정 중")
                    .setContentText(text)
                    .setOngoing(true)
                    .build()
                startForeground(NOTI_ID, n)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        runCatching { client.disconnect() }
        MeasureStatusBus.setMeasuring(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------
    // 헬퍼
    // -------------------------------

    // 공통 권장 포맷: S_yyyyMMdd_HHmmss_<6자리랜덤>
    fun newSessionId(): String {
        val now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
        val day = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
        val hms = now.format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"))
        val suffix = java.util.UUID.randomUUID().toString().take(6)
        return "S_${day}_${hms}_${suffix}"
    }

    private fun utcIsoNow(): String =
        OffsetDateTime.now(ZoneOffset.UTC).toString()

    // CSV 한 줄 → Firestore 업로드
    private suspend fun handleLine(line: String) {
        Log.d("MSVC", "[handle] got line='$line'")
        val ev = parseWireLineToEvent(line)
        if (ev == null) {
            Log.w("MSVC", "[handle] parse -> null  (업로드 스킵)")
            return
        }
        Log.d("MSVC", "[handle] parsed event=${ev.event} ts=${ev.ts_ms}")

        // --- (A) 그래프용 값 추출 → EMA 스무딩 → Repo emit ---
        val lSrc: Double? = ev.ampL ?: ev.BPM_L   // 왼쪽 채널 원천값
        val rSrc: Double? = ev.ampR ?: ev.BPM_R   // 오른쪽 채널 원천값

        if (lSrc != null && rSrc != null) {
            emaL = emaUpdate(emaL, lSrc)
            emaR = emaUpdate(emaR, rSrc)
            val outL = emaL!!
            val outR = emaR!!
            Log.d("MSVC", "graph emit L=$outL R=$outR (src ampL=$lSrc ampR=$rSrc)")
            PpgRepository.emitSmoothed(
                System.currentTimeMillis(), // 그래프/Firestore 기록용 timestamp
                outL,
                outR
            )
        } else {
            Log.w("MSVC", "no L/R source (ampL/ampR/BPM_L/BPM_R 없음)")
        }

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            Log.e("MSVC", "[handle] no uid (업로드 스킵)")
            return
        }

        Log.d("MSVC", "[handle] call uploadRecord uid=$uid session=$sessionId")
        try {
            val docId = repo.uploadRecord(uid, sessionId, ev)
            Log.d("MSVC", "[handle] firestore OK: $docId")
        } catch (t: Throwable) {
            Log.e("MSVC", "[handle] firestore fail", t)
        }
    }



    // KV 라인 → PpgEvent
    private fun parseWireLineToEvent(line: String): PpgEvent? {
        val raw = line.trim()
        if (raw.isEmpty() || raw.startsWith("#")) return null

        // 첫 토큰은 STAT | ALERT
        val sp = raw.split(Regex("\\s+"))
        if (sp.isEmpty()) return null
        val kind = sp[0].uppercase()
        if (kind != "STAT" && kind != "ALERT") {
            Log.w("PARSE", "unknown kind: ${sp[0]}")
            return null
        }

        // 나머지는 key=value 형태
        val kv = mutableMapOf<String, String>()
        for (i in 1 until sp.size) {
            val token = sp[i]
            val eq = token.indexOf('=')
            if (eq <= 0) continue
            kv[token.substring(0, eq)] = token.substring(eq+1)
        }

        if (kv.isEmpty()) {
            Log.w("PARSE", "no kv parsed: $raw")
            return null
        }

        fun D(k: String) = kv[k]?.toDoubleOrNull()
        fun L(k: String) = kv[k]?.toLongOrNull()
        fun I(k: String) = kv[k]?.toIntOrNull()
        fun sideNorm(s: String?): String? = when (s?.lowercase()) {
            "left","right","balanced","balance","uncertain" -> s.lowercase()
            else -> null
        }
        fun reasonsList(s: String?): List<String>? =
            s?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.ifEmpty { null }

        //val hostIso = OffsetDateTime.now(ZoneOffset.UTC).toString()

        return PpgEvent(
            event = kind,
            host_time_iso = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString(),
            ts_ms = L("ts") ?: 0L,
            alert_type = kv["type"],
            reasons = reasonsList(kv["reasons"]),
            AmpRatio = D("AmpRatio"),
            PAD_ms = D("PAD"),
            dSUT_ms = D("dSUT"),
            ampL = D("ampL"),
            ampR = D("ampR"),
            SUTL_ms = D("SUTL"),
            SUTR_ms = D("SUTR"),
            BPM_L = D("BPM_L"),
            BPM_R = D("BPM_R"),
            PQIL = I("PQIL"),
            PQIR = I("PQIR"),
            side = sideNorm(kv["side"]),
            // 이 스트림엔 smoothed_left/right 배열은 안 옴
            smoothed_left = null,
            smoothed_right = null
        )
    }


    private fun parseReasons(s: String?): List<String>? {
        if (s.isNullOrBlank()) return null
        val list = s.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        return if (list.isEmpty()) null else list
    }
}
