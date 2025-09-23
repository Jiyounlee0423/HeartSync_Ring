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
//            val auth = FirebaseAuth.getInstance()
//            if (auth.currentUser == null) {
//                auth.signInAnonymously().await()
//            }
//            userId = FirebaseAuth.getInstance().currentUser!!.uid
//
//            val sid = newSessionId()
//            PpgRepository.instance.setSessionId(sid)
//            sessionId = sid
//            Log.d("MeasureService","session ready -> $sid / uid=$userId")
//            // ★ 여기! 세션 메타 1회 초기화 후 BLE 시작
//            // (이 블록을 startBle() 호출 "바로 전"에 둡니다)
//            launch(Dispatchers.IO) {
//                try {
//                    PpgRepository.instance.initSessionOnce(userId, sessionId)
//                    Log.d("MeasureService", "session init OK")
//                } catch (t: Throwable) {
//                    Log.e("MeasureService", "session init FAIL", t)
//                }
//                withContext(Dispatchers.Main) {
//                    startBle()   // 이제 BLE 시작
//                }
//            }
        }
    }
    // 바로 아래에 private 함수로 추가
    private suspend fun firestoreWarmupWrite() {
        val db = FirebaseFirestore.getInstance()
        val uid = FirebaseAuth.getInstance().currentUser!!.uid
        db.collection("ppg_events").document(uid)
            .collection("debug").document()
            .set(mapOf("ping" to System.currentTimeMillis()))
            .addOnSuccessListener { Log.d("MeasureService","firestore warmup OK") }
            .addOnFailureListener { Log.w("MeasureService","firestore warmup err", it) }
        // ❌ withTimeout/await 제거 (네트워크 지연으로 타임아웃 나던 부분)
    }

    private fun initSessionOnce_fireAndForget(uid: String, sessionId: String) {
        FirebaseFirestore.getInstance()
            .collection("ppg_events").document(uid)
            .collection("sessions").document(sessionId)
            .set(
                mapOf(
                    "created_at" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    "day" to sessionId.substring(2, 10),
                    "id"  to sessionId
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
            .addOnSuccessListener {
                Log.d("MeasureService","SESSION META OK => /ppg_events/$uid/sessions/$sessionId")
            }
            .addOnFailureListener { e ->
                Log.w("MeasureService","SESSION META ERR", e)
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
            ctx = this@MeasureService,
            onLine = { line ->
                Log.d("MeasureService", "LINE IN => ${line.take(160)}") // ★ 반드시 찍힘
                scope.launch(Dispatchers.IO) {
                    maybeRotateSessionIfNeeded()
                    val ok = PpgRepository.trySaveFromLine(line)
                    Log.d("MeasureService", "SAVE RESULT => $ok")        // ★ 저장 시도 결과
                    if (!ok) Log.w("MeasureService","save skipped: ${line.take(160)}")
                }
            },
            onError = { e -> Log.e("MeasureService","BLE error: $e") },
            filterByService = false

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
            stopSelf(); return START_NOT_STICKY
        }
        scope.launch {


            // 1) 로그인 보장
            val auth = FirebaseAuth.getInstance()
            val cur = auth.currentUser
            if (cur == null /* || cur.isAnonymous */) {
                Log.e("MeasureService","로그인 필요"); stopSelf(); return@launch
            }
            userId = cur.uid

            // 2) (선택) 워밍업 쓰기로 네트워크/오프라인큐 준비
            withContext(Dispatchers.IO) { firestoreWarmupWrite() }

            // 3) 세션 회전/생성 + 세션 메타 초기화 "완료될 때까지 기다림"
            withContext(Dispatchers.IO) { maybeRotateSessionIfNeeded() }
            val sid = sessionId
            Log.d("MeasureService","SESSION READY uid=$userId sid=$sid")

            // onStartCommand() 안, SESSION READY 직후
            val db = FirebaseFirestore.getInstance()
            val testRef = db.collection("ppg_events").document(userId)
                .collection("sessions").document(sessionId)
                .collection("records").document()

            testRef.set(
                mapOf(
                    "event" to "STAT",
                    "ts_ms" to System.currentTimeMillis() % 1000,
                    "smoothed_left" to 1.23,
                    "smoothed_right" to 4.56,
                    "server_ts" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )
            ).addOnSuccessListener {
                Log.d("MeasureService", "TEST WRITE OK => ${testRef.path}")
            }.addOnFailureListener { e ->
                Log.e("MeasureService", "TEST WRITE FAIL", e)
            }

            // 4) 세션 준비가 끝난 뒤에만 BLE 시작
            client = PpgBleClient(
                ctx = this@MeasureService,
                onLine = { line ->
                    Log.d("MeasureService", "LINE IN => ${line.take(160)}") // ★ 반드시 찍힘
                    scope.launch(Dispatchers.IO) {
                        maybeRotateSessionIfNeeded()
                        val ok = PpgRepository.trySaveFromLine(line)
                        Log.d("MeasureService", "SAVE RESULT => $ok")        // ★ 저장 시도 결과
                        if (!ok) Log.w("MeasureService","save skipped: ${line.take(160)}")
                    }
                },
                onError = { e -> Log.e("MeasureService","BLE error: $e") },
                filterByService = true
            )
            client.connect(BleDevice(devName, devAddr))

            Log.d("MeasureService", "CONNECT TRY name=$devName addr=$devAddr")

            // 5) 상태 알림
            client.connectionState.collect { st ->
                Log.d("MeasureService", "BLE STATE => $st") // ★ 상태 덤프
                if (st is PpgBleClient.ConnectionState.Disconnected ||
                    st is PpgBleClient.ConnectionState.Failed) {
                    // 짧게 재시도 (필요 시 backoff)
                    kotlinx.coroutines.delay(1200)
                    withContext(Dispatchers.Main) {
                        Log.d("MeasureService", "RETRY connect addr=$devAddr")
                        client.connect(BleDevice(devName, devAddr))
                    }
                }
                val text = when (st) {
                    is PpgBleClient.ConnectionState.Connected  -> "연결됨: ${st.device.address}"
                    is PpgBleClient.ConnectionState.Connecting -> "연결 중…"
                    is PpgBleClient.ConnectionState.Failed     -> "실패: ${st.reason}"
                    else -> "대기"
                }
                when (st) {
                is PpgBleClient.ConnectionState.Failed -> {
                    Log.e("MeasureService", "BLE FAIL reason=${st.reason}")
                }
                    else -> {
                    }

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

    private fun dayFromSessionId(id: String): String? =
        Regex("""^S_(\d{8})_""").find(id)?.groupValues?.getOrNull(1)

    private fun todaySeoul(): String =
        java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))

    private suspend fun startNewSessionAndInit() {
        val sid = newSessionId()
        PpgRepository.instance.setSessionId(sid)
        sessionId = sid
        val uid = FirebaseAuth.getInstance().currentUser!!.uid

        // ✅ 부모 세션 문서 먼저 생성 (BLE 시작/레코드 쓰기 전에)
        PpgRepository.instance.putSessionMetaFireAndForget(uid, sid)
        // 또는 규칙상 필요하면: withTimeout(30_000) { PpgRepository.instance.initSessionOnce(uid, sid) }

        Log.d("MeasureService","SESSION READY uid=$uid sid=$sid")
    }

    private suspend fun maybeRotateSessionIfNeeded() {
        val cur = sessionId
        val curDay = dayFromSessionId(cur)
        val today = todaySeoul()
        if (cur.isBlank() || curDay != today) {
            Log.d("MeasureService", "rotate session: cur=$cur (day=$curDay) -> today=$today")
            startNewSessionAndInit()
        }
    }
}
