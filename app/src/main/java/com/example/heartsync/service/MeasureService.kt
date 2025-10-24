// app/src/main/java/com/example/heartsync/service/MeasureService.kt
package com.example.heartsync.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.heartsync.ble.PpgBleClient
import com.example.heartsync.data.model.BleDevice
import com.example.heartsync.data.remote.PpgRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.ZoneOffset

class MeasureService : Service() {

    companion object {
        // ── Intent extras (단일 연결 모드) ──
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_DEVICE_ADDR = "extra_device_addr"

        // ── Foreground 알림 ──
        const val NOTI_ID = 1001
        const val NOTI_CHANNEL_ID = "measuresvc"

        // ── 외부 액션 ──
        /** 모든 BLE 연결/스트림을 즉시 중단하고 서비스를 끝낸다 */
        const val ACTION_RESET_ALL = "measureservice.action.RESET_ALL"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var client: PpgBleClient
    private lateinit var repo: PpgRepository

    // 로그인 필수
    private var userId: String = ""
    private var sessionId: String = ""

    override fun onCreate() {
        super.onCreate()

        NotificationHelper.ensureChannel(this, NOTI_CHANNEL_ID, "HeartSync Measure")
        startForeground(
            NOTI_ID,
            NotificationCompat.Builder(this, NOTI_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("측정 중")
                .setContentText("로그인 중…")
                .setOngoing(true)
                .build()
        )

        repo = PpgRepository.instance

        // (선택) 크래시/미처리 예외에도 안전 종료 시도하려면:
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            Log.e("MeasureService", "Uncaught exception", e)
            runCatching { if (::client.isInitialized) client.safeDisconnect() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 0) 초기화 액션 우선 처리
        if (intent?.action == ACTION_RESET_ALL) {
            Log.d("MeasureService", "ACTION_RESET_ALL")
            runCatching { if (::client.isInitialized) client.safeDisconnect() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // 1) 연결 파라미터
        val devName = intent?.getStringExtra(EXTRA_DEVICE_NAME)
        val devAddr = intent?.getStringExtra(EXTRA_DEVICE_ADDR)
        if (devAddr.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 2) 본 작업
        scope.launch {
            // 로그인 확인
            val cur = FirebaseAuth.getInstance().currentUser
            if (cur == null) {
                Log.e("MeasureService", "로그인 필요")
                stopSelf()
                return@launch
            }
            userId = cur.uid

            // Firestore 워밍업 + 세션 준비
            withContext(Dispatchers.IO) { firestoreWarmupWrite() }
            withContext(Dispatchers.IO) { maybeRotateSessionIfNeeded() }
            Log.d("MeasureService", "SESSION READY uid=$userId sid=$sessionId")

            // BLE 시작
            client = PpgBleClient(
                ctx = this@MeasureService,
                onLine = { line ->
                    Log.d("MeasureService", "LINE IN => ${line.take(160)}")
                    scope.launch(Dispatchers.IO) {
                        maybeRotateSessionIfNeeded()
                        val ok = PpgRepository.trySaveFromLine(line)
                        Log.d("MeasureService", "SAVE RESULT => $ok")
                    }
                },
                onError = { e -> Log.e("MeasureService","BLE error: $e") },
                filterByService = true
            )
            client.connect(BleDevice(devName, devAddr))
            Log.d("MeasureService", "CONNECT TRY name=$devName addr=$devAddr")

            // 상태 알림 & 재시도
            client.connectionState.collect { st ->
                Log.d("MeasureService", "BLE STATE => $st")

                if (st is PpgBleClient.ConnectionState.Disconnected ||
                    st is PpgBleClient.ConnectionState.Failed) {
                    kotlinx.coroutines.delay(1200)
                    withContext(Dispatchers.Main) {
                        Log.d("MeasureService", "RETRY connect addr=$devAddr")
                        client.connect(BleDevice(devName, devAddr))
                    }
                }

                val text = when (st) {
                    is PpgBleClient.ConnectionState.Connected  -> "연결됨: ${st.device.address}"
                    is PpgBleClient.ConnectionState.Connecting -> "연결 중… ($devName / $devAddr)"
                    is PpgBleClient.ConnectionState.Failed     -> "실패: ${st.reason} ($devAddr)"
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 최근 앱 목록에서 스와이프 종료되는 경우에도 안전 종료 시도
        runCatching { if (::client.isInitialized) client.safeDisconnect() }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        runCatching { if (::client.isInitialized) client.safeDisconnect() }
        MeasureStatusBus.setMeasuring(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────
    // 헬퍼
    // ─────────────────────

    private suspend fun firestoreWarmupWrite() {
        val db = FirebaseFirestore.getInstance()
        val uid = FirebaseAuth.getInstance().currentUser!!.uid
        db.collection("ppg_events").document(uid)
            .collection("debug").document()
            .set(mapOf("ping" to System.currentTimeMillis()))
            .addOnSuccessListener { Log.d("MeasureService","firestore warmup OK") }
            .addOnFailureListener { Log.w("MeasureService","firestore warmup err", it) }
    }

    // 공통 권장 포맷: S_yyyyMMdd_HHmmss_<6자리랜덤>
    private fun newSessionId(): String {
        val now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
        val day = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
        val hms = now.format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"))
        val suffix = java.util.UUID.randomUUID().toString().take(6)
        return "S_${day}_${hms}_${suffix}"
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
        PpgRepository.instance.putSessionMetaFireAndForget(uid, sid)
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

    @Suppress("unused")
    private fun utcIsoNow(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()
}
