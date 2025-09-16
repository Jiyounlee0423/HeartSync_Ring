// app/src/main/java/com/example/heartsync/service/MeasureService.kt
package com.example.heartsync.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.heartsync.data.model.PpgEvent
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

    override fun onCreate() {
        super.onCreate()

        val app = com.google.firebase.FirebaseApp.getInstance()
        val opt = app.options
        Log.d("FB", "proj=${opt.projectId}, appId=${opt.applicationId}, dbUrl=${opt.databaseUrl}")

        // 포그라운드 알림 채널 보장
        NotificationHelper.ensureChannel(this, NOTI_CHANNEL_ID, "HeartSync Measure")

        // 초기 알림
        val noti = NotificationCompat.Builder(this, NOTI_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("측정 중")
            .setContentText("BLE 연결 및 수집 준비")
            .setOngoing(true)
            .build()
        startForeground(NOTI_ID, noti)

        // Firestore Repo / Auth 초기화
        repo = PpgRepository(FirebaseFirestore.getInstance())

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Log.e("MeasureService", "User not logged in, stopping service")
            stopSelf()
            return
        }
        userId = user.uid

        // 세션 ID 생성 (UTC ISO + 8자리 UUID)
        sessionId = newSessionId()

        MeasureStatusBus.setMeasuring(true)
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
                scope.launch(Dispatchers.IO) { handleLine(line) }
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

    private fun newSessionId(): String {
        val iso = OffsetDateTime.now(ZoneOffset.UTC).toString().replace(":", "-")
        val suffix = UUID.randomUUID().toString().take(8)
        return "${iso}_$suffix"
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
