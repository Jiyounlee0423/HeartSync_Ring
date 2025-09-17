// app/src/main/java/com/example/HeartSync/data/remote/PpgRepository.kt
package com.example.heartsync.data.remote

import android.util.Log
import com.example.heartsync.data.model.PpgEvent
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.tasks.await
import com.google.firebase.auth.FirebaseAuth


class PpgRepository(
    private val db: FirebaseFirestore
) {
    @Volatile private var sessionId: String = ""

    /** MeasureService 등에서 1회 세션ID 세팅 */
    fun setSessionId(id: String) {
        sessionId = id
        Log.d("PpgRepo", "sessionId set -> $sessionId")
    }

    /** 필요시 현재 세션 조회 */
    fun getSessionId(): String = sessionId

    private fun col(userId: String, sessionId: String) =
        db.collection("ppg_events")
            .document(userId)
            .collection("sessions")
            .document(sessionId)
            .collection("records")

    // 클래스 멤버에 추가 (쓰로틀 상태)
    @Volatile private var lastWriteMs: Long = 0L

    /** STAT/ALERT 라인 공통 처리 */
    suspend fun trySaveFromLine(line: String) {
        // 1) 이벤트 타입 추출 (맨 앞 토큰)
        val eventType = line.substringBefore(' ').trim()
        if (eventType != "STAT" && eventType != "ALERT") return

        Log.d("PpgRepo", "trySaveFromLine IN: ${eventType} ${line.take(100)}")

        // 2) 파싱
        val ev = parseStatLikeLine(line, eventType) ?: run {
            Log.w("PpgRepo", "parse fail ($eventType)")
            return
        }

        // 3) uid/세션 확보
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            Log.e("PpgRepo", "uid null -> skip")
            return
        }
        val sid = if (sessionId.isNotBlank()) sessionId
        else "S_${System.currentTimeMillis()}".also {
            sessionId = it
            Log.d("PpgRepo", "sessionId (auto) -> $sessionId")
        }

        // 4) 쓰로틀: ALERT는 즉시 저장, STAT는 최소 간격 유지
        val now = System.currentTimeMillis()
        val shouldWrite = (eventType == "ALERT") || (now - lastWriteMs >= MIN_INTERVAL_MS)
        if (!shouldWrite) return
        lastWriteMs = now

        Log.d("PpgRepo", "upload start: $eventType uid=$uid sid=$sid ts=${ev.ts_ms}")
        uploadRecord(uid, sid, ev)
    }

    /** STAT/ALERT 공통 포맷 파싱 */
    private fun parseStatLikeLine(line: String, eventType: String): PpgEvent? {
        fun <T> num(key: String, conv: (String)->T): T? =
            Regex("""\b$key=([-\d.]+)""").find(line)?.groupValues?.getOrNull(1)?.let(conv)

        // 여러 키 중 하나라도 있으면 우선 사용
        fun numAny(vararg keys: String): Double? {
            for (k in keys) {
                val v = num(k) { it.toDouble() }
                if (v != null) return v
            }
            return null
        }

        val ts   = num("ts") { it.toLong() } ?: return null
        val side = Regex("""\bside=([A-Za-z_]+)""").find(line)?.groupValues?.getOrNull(1)

        // ✅ 아두이노 전송 키 대응
        val ppgfL = numAny("PPGf_L", "PPG_L", "PPG_left")
        val ppgfR = numAny("PPGf_R", "PPG_R", "PPG_right")

        return PpgEvent(
            event = eventType,              // ★ STAT 또는 ALERT 로 들어감
            host_time_iso = "",             // 필요시 ISO 시간 넣어도 됨
            ts_ms = ts,

            alert_type = if (eventType == "ALERT") "device" else null, // 필요시 조정
            reasons = null,

            AmpRatio = num("AmpRatio"){ it.toDouble() },
            PAD_ms   = num("PAD"){ it.toDouble() },
            dSUT_ms  = num("dSUT"){ it.toDouble() },

            ampL = num("ampL"){ it.toDouble() },
            ampR = num("ampR"){ it.toDouble() },

            SUTL_ms = num("SUTL"){ it.toDouble() },
            SUTR_ms = num("SUTR"){ it.toDouble() },

            BPM_L = num("BPM_L"){ it.toDouble() },
            BPM_R = num("BPM_R"){ it.toDouble() },

            PQIL = num("PQIL"){ it.toDouble() }?.toInt(),
            PQIR = num("PQIR"){ it.toDouble() }?.toInt(),

            side = side,

            smoothed_left  = ppgfL,
            smoothed_right = ppgfR,
        )
    }

    suspend fun uploadRecord(userId: String, sessionId: String, ev: PpgEvent): String {

        val path = "ppg_events/$userId/sessions/$sessionId/records"

        val map = hashMapOf(
            "event" to ev.event,
            "host_time_iso" to ev.host_time_iso,
            "ts_ms" to ev.ts_ms,
            "alert_type" to ev.alert_type,
            "reasons" to ev.reasons,
            "AmpRatio" to ev.AmpRatio,
            "PAD_ms" to ev.PAD_ms,
            "dSUT_ms" to ev.dSUT_ms,
            "ampL" to ev.ampL,
            "ampR" to ev.ampR,
            "SUTL_ms" to ev.SUTL_ms,
            "SUTR_ms" to ev.SUTR_ms,
            "BPM_L" to ev.BPM_L,
            "BPM_R" to ev.BPM_R,
            "PQIL" to ev.PQIL,
            "PQIR" to ev.PQIR,
            "side" to ev.side,
            "smoothed_left" to ev.smoothed_left,
            "smoothed_right" to ev.smoothed_right,
            "server_ts" to FieldValue.serverTimestamp()
        )

        return try {
            // 디버깅용 간단 요약 로그
            android.util.Log.d("PpgRepo",
                "write try: $path  event=${ev.event} ts_ms=${ev.ts_ms} " +
                        "AmpRatio=${ev.AmpRatio} leftAmp=${ev.ampL} rightAmp=${ev.ampR}"
            )

            val ref = col(userId, sessionId).document()
            ref.set(map).await()

            // 서버에서 강제 읽기 (오프라인/퍼미션 문제면 여기서 예외)
            val snap = ref.get(Source.SERVER).await()
            Log.d("PpgRepo", "server read ok: exists=${snap.exists()}")

            android.util.Log.d("PpgRepo", "write OK: $path/${ref.id}")
            ref.id
        } catch (t: Throwable) {
            android.util.Log.e("PpgRepo", "write FAIL: $path", t)
            throw t
        }
    }

    /**
     * 세션 레코드 실시간 구독 (UI에서 바로 씀)
     * orderBy는 server_ts(서버시간) 기준, 최신 N개
     */
    private fun readNumberFlexible(d: com.google.firebase.firestore.DocumentSnapshot, key: String): Double? {
        d.getDouble(key)?.let { return it }                // 숫자로 저장된 경우
        val arr = d.get(key) as? List<*>                  // 예전 문서: 배열 첫 원소
        val first = arr?.firstOrNull() as? Number
        return first?.toDouble()
    }
    fun observeRecent(
        userId: String,
        sessionId: String,
        limit: Long = 200
    ) = callbackFlow<List<PpgEvent>> {
        val qs = col(userId, sessionId)
            .orderBy("server_ts")
            .limitToLast(limit)

        val reg = qs.addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            val items = snap.documents.mapNotNull { d ->
                try {
                    PpgEvent(
                        event = d.getString("event") ?: "STAT",
                        host_time_iso = d.getString("host_time_iso") ?: "",
                        ts_ms = (d.getLong("ts_ms") ?: 0L),

                        alert_type = d.getString("alert_type"),
                        reasons = (d.get("reasons") as? List<*>)?.mapNotNull { it as? String },

                        AmpRatio = d.getDouble("AmpRatio"),
                        PAD_ms = d.getDouble("PAD_ms"),
                        dSUT_ms = d.getDouble("dSUT_ms"),

                        ampL = d.getDouble("ampL"),
                        ampR = d.getDouble("ampR"),

                        SUTL_ms = d.getDouble("SUTL_ms"),
                        SUTR_ms = d.getDouble("SUTR_ms"),

                        BPM_L = d.getDouble("BPM_L"),
                        BPM_R = d.getDouble("BPM_R"),

                        PQIL = (d.getLong("PQIL") ?: d.getDouble("PQIL")?.toLong())?.toInt(),
                        PQIR = (d.getLong("PQIR") ?: d.getDouble("PQIR")?.toLong())?.toInt(),

                        side = d.getString("side"),

                        smoothed_left  = readNumberFlexible(d, "smoothed_left"),
                        smoothed_right = readNumberFlexible(d, "smoothed_right"),
                    )
                } catch (_: Exception) {
                    null
                }
            }
            trySend(items)
        }
        awaitClose { reg.remove() }
    }

    companion object {
        /**
         * 실시간 스무딩 샘플 스트림 (좌/우 한 점씩)
         * - 최신 샘플을 가볍게 흘려보내기 위한 허브
         * - UI: PpgRepository.smoothedFlow.collect { (l, r) -> ... }
         * - Service/BLE: PpgRepository.emitSmoothed(l, r)
         */
        private const val MIN_INTERVAL_MS = 200L   // ≈ 5Hz. 필요에 맞게 100~500ms로 조정

        private val _smoothedFlow =
            MutableSharedFlow<Pair<Double, Double>>(replay = 0, extraBufferCapacity = 128)

        val smoothedFlow: SharedFlow<Pair<Double, Double>> = _smoothedFlow.asSharedFlow()

        // 싱글톤 인스턴스
        val instance: PpgRepository by lazy { PpgRepository(FirebaseFirestore.getInstance()) }

        /**
         * 생산자(서비스/블루투스)에서 호출: 스무딩된 좌/우 값을 방출
         * Number를 받도록 해서 Float/Double 둘 다 허용
         */
        fun emitSmoothed(left: Number, right: Number) {
            _smoothedFlow.tryEmit(left.toDouble() to right.toDouble())
        }
    }

}
