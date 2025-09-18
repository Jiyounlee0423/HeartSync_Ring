// app/src/main/java/com/example/heartsync/data/remote/PpgRepository.kt
package com.example.heartsync.data.remote

import android.util.Log
import com.example.heartsync.data.model.PpgEvent
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 그래프용 포인트 */
data class PpgPoint(
    /** x축 기준 (절대 ms). server_ts가 있으면 그것을, 없으면 세션ID+상대ts로 계산 */
    val ts: Long,
    val left: Double?,
    val right: Double?,
    /** Firestore server_ts(epoch ms). 없으면 null */
    val serverTs: Long? = null
)

class PpgRepository(
    private val db: FirebaseFirestore
) {
    @Volatile private var sessionId: String = ""
    // PpgRepository 클래스 본문에 (companion 밖)
    suspend fun trySaveFromLinePublic(line: String) = trySaveFromLineInternal(line)

    /** 측정 서비스 등에서 세션 ID 전달 */
    fun setSessionId(id: String) { sessionId = id; Log.d("PpgRepo", "sessionId set -> $sessionId") }
    fun getSessionId(): String? = sessionId

    private fun recordsCol(userId: String, sessionId: String) =
        db.collection("ppg_events").document(userId)
            .collection("sessions").document(sessionId)
            .collection("records")

    /* ============================================================
     *  A) 하루치 통합 스트림 (문서ID: S_yyyyMMdd_HHmmss 기반)
     * ============================================================ */

    /** 지정 날짜(yyyyMMdd)의 모든 세션을 합쳐서 시간순으로 흘려보냄 */
    fun observeDayPpg(uid: String, date: LocalDate): Flow<List<PpgPoint>> = callbackFlow {
        val dayStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val start = "S_${dayStr}_"
        val end   = "S_${dayStr}_\uf8ff"

        val sessionQuery = db.collection("ppg_events")
            .document(uid).collection("sessions")
            .whereGreaterThanOrEqualTo(FieldPath.documentId(), start)
            .whereLessThanOrEqualTo(FieldPath.documentId(), end)

        val recordRegs = mutableListOf<ListenerRegistration>()
        val latestBySession = mutableMapOf<String, List<PpgPoint>>()

        fun pushCombined() {
            trySend(latestBySession.values.flatten().sortedBy { it.ts })
        }

        val sessionReg = sessionQuery.addSnapshotListener { snap, err ->
            if (err != null) {
                Log.e("PpgRepo", "session listen error", err)
                return@addSnapshotListener
            }

            // 세션 라인업 바뀌면 records 리스너 재구성
            recordRegs.forEach { it.remove() }
            recordRegs.clear()
            latestBySession.clear()

            snap?.documents?.forEach { sdoc ->
                // 세션 ID로 절대시간 베이스 계산
                val base = sessionIdBaseEpochMs(sdoc.id)
                val reg = sdoc.reference.collection("records")
                    .orderBy("ts_ms")
                    .addSnapshotListener { recSnap, recErr ->
                        if (recErr != null) return@addSnapshotListener
                        val list = recSnap?.documents?.mapNotNull { d ->
                            val rel = (d.getLong("ts_ms")
                                ?: d.getLong("ts")
                                ?: d.getLong("timestamp")
                                ?: d.getLong("idx")) ?: return@mapNotNull null

                            val left  = getDoubleAny(d, "smoothed_left","Smoothed_Left","smooted_left","PPGf_L","ppgf_l","PPG_L")
                            val right = getDoubleAny(d, "smoothed_right","Smoothed_Right","smooted_right","PPGf_R","ppgf_r","PPG_R")
                            val serverTs = d.getTimestamp("server_ts")?.toDate()?.time
                            val absTs = serverTs ?: (base + rel)

                            PpgPoint(ts = absTs, left = left, right = right, serverTs = serverTs)
                        } ?: emptyList()

                        latestBySession[sdoc.id] = list
                        pushCombined()
                    }
                recordRegs.add(reg)
            }
        }

        awaitClose {
            sessionReg.remove()
            recordRegs.forEach { it.remove() }
        }
    }.distinctUntilChanged()

    /** 최신 세션의 날짜를 자동 감지해서 observe */
    fun observeLatestDayPpg(uid: String): Flow<List<PpgPoint>> = callbackFlow {
        val root = db.collection("ppg_events").document(uid).collection("sessions")
        var innerReg: ListenerRegistration? = null

        val outerReg = root
            .orderBy(FieldPath.documentId(), Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snap, err ->
                if (err != null) { close(err); return@addSnapshotListener }
                val latestId = snap?.documents?.firstOrNull()?.id ?: return@addSnapshotListener
                val dayStr = latestId.split('_').getOrNull(1) ?: return@addSnapshotListener

                // 날짜 바뀌면 재-구독
                innerReg?.remove()
                innerReg = observeDayListener(uid, dayStr) { list -> trySend(list) }
            }

        awaitClose {
            outerReg.remove()
            innerReg?.remove()
        }
    }

    /** 내부: yyyyMMdd로 observe (ListenerRegistration 버전) */
    private fun observeDayListener(
        uid: String,
        dayStr: String,
        onData: (List<PpgPoint>) -> Unit
    ): ListenerRegistration {
        val start = "S_${dayStr}_"
        val end   = "S_${dayStr}_\uf8ff"
        val sessionQuery = db.collection("ppg_events").document(uid).collection("sessions")
            .whereGreaterThanOrEqualTo(FieldPath.documentId(), start)
            .whereLessThanOrEqualTo(FieldPath.documentId(), end)

        val recordRegs = mutableListOf<ListenerRegistration>()
        val latestBySession = mutableMapOf<String, List<PpgPoint>>()

        fun pushCombined() = onData(latestBySession.values.flatten().sortedBy { it.ts })

        return sessionQuery.addSnapshotListener { snap, _ ->
            recordRegs.forEach { it.remove() }
            recordRegs.clear()
            latestBySession.clear()

            snap?.documents?.forEach { sdoc ->
                val base = sessionIdBaseEpochMs(sdoc.id)
                val reg = sdoc.reference.collection("records")
                    .orderBy("ts_ms")
                    .addSnapshotListener { recSnap, _ ->
                        val list = recSnap?.documents?.mapNotNull { d ->
                            val rel = (d.getLong("ts_ms")
                                ?: d.getLong("ts")
                                ?: d.getLong("timestamp")
                                ?: d.getLong("idx")) ?: return@mapNotNull null

                            val left  = getDoubleAny(d, "smoothed_left","Smoothed_Left","smooted_left","PPGf_L","ppgf_l","PPG_L")
                            val right = getDoubleAny(d, "smoothed_right","Smoothed_Right","smooted_right","PPGf_R","ppgf_r","PPG_R")
                            val serverTs = d.getTimestamp("server_ts")?.toDate()?.time
                            val absTs = serverTs ?: (base + rel)

                            PpgPoint(ts = absTs, left = left, right = right, serverTs = serverTs)
                        } ?: emptyList()

                        latestBySession[sdoc.id] = list
                        pushCombined()
                    }
                recordRegs.add(reg)
            }
        }
    }

    /** 최신 세션 1개 문서ID에서 yyyyMMdd만 추출 (필요할 때 사용) */
    suspend fun fetchLatestDay(uid: String): String? {
        val qs = db.collection("ppg_events").document(uid)
            .collection("sessions")
            .orderBy(FieldPath.documentId(), Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .await()

        val id = qs.documents.firstOrNull()?.id ?: return null
        return id.split('_').getOrNull(1)
    }

    /* ============================================================
     *  B) 업로드/최근레코드 유틸
     * ============================================================ */

    /** STAT/ALERT 공통 파서 */
    private fun parseStatLikeLine(line: String, eventType: String): PpgEvent? {
        fun <T> num(key: String, conv: (String) -> T): T? =
            Regex("""\b$key=([-\d.]+)""").find(line)?.groupValues?.getOrNull(1)?.let(conv)
        fun numAny(vararg keys: String): Double? {
            for (k in keys) num(k) { it.toDouble() }?.let { return it }
            return null
        }
        val ts = num("ts") { it.toLong() } ?: return null
        val side = Regex("""\bside=([A-Za-z_]+)""").find(line)?.groupValues?.getOrNull(1)

        val ppgfL = numAny("PPGf_L", "PPG_L", "PPG_left")
        val ppgfR = numAny("PPGf_R", "PPG_R", "PPG_right")

        return PpgEvent(
            event = eventType,
            host_time_iso = "",
            ts_ms = ts,
            alert_type = if (eventType == "ALERT") "device" else null,
            reasons = null,
            AmpRatio = num("AmpRatio") { it.toDouble() },
            PAD_ms   = num("PAD")      { it.toDouble() },
            dSUT_ms  = num("dSUT")     { it.toDouble() },
            ampL = num("ampL") { it.toDouble() },
            ampR = num("ampR") { it.toDouble() },
            SUTL_ms = num("SUTL") { it.toDouble() },
            SUTR_ms = num("SUTR") { it.toDouble() },
            BPM_L = num("BPM_L") { it.toDouble() },
            BPM_R = num("BPM_R") { it.toDouble() },
            PQIL = num("PQIL") { it.toDouble() }?.toInt(),
            PQIR = num("PQIR") { it.toDouble() }?.toInt(),
            side = side,
            smoothed_left  = ppgfL,
            smoothed_right = ppgfR,
        )
    }

    /** 세션 메타 문서 존재 보장(없으면 생성/있으면 merge) */
    private suspend fun ensureSessionDoc(uid: String, sessionId: String) {
        val day = sessionId.substring(2, 10) // "S_20250918_150444" -> "20250918"
        val meta = mapOf(
            "created_at" to FieldValue.serverTimestamp(),
            "day" to day,
            "id" to sessionId
        )
        db.collection("ppg_events").document(uid)
            .collection("sessions").document(sessionId)
            .set(meta, SetOptions.merge())
            .await()
    }

    /** 레코드 업로드(서버 타임 스탬프 포함) */
    suspend fun uploadRecord(userId: String, sessionId: String, ev: PpgEvent): String {
        ensureSessionDoc(userId, sessionId)

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
            Log.d("PpgRepo", "write try: $path  event=${ev.event} ts_ms=${ev.ts_ms}")
            val ref = recordsCol(userId, sessionId).document()
            ref.set(map).await()
            val snap = ref.get(Source.SERVER).await()
            Log.d("PpgRepo", "server read ok: exists=${snap.exists()}")
            Log.d("PpgRepo", "write OK: $path/${ref.id}")
            ref.id
        } catch (t: Throwable) {
            Log.e("PpgRepo", "write FAIL: $path", t)
            throw t
        }
    }

    /** 최근 레코드(서버시간순) 구독 */
    fun observeRecent(
        userId: String,
        sessionId: String,
        limit: Long = 200
    ) = callbackFlow<List<PpgEvent>> {
        val qs = recordsCol(userId, sessionId)
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
                } catch (_: Exception) { null }
            }
            trySend(items)
        }
        awaitClose { reg.remove() }
    }

    /* ============================================================
     *  C) 실시간 값 허브 (BLE/Service → UI)
     * ============================================================ */

    companion object {
        private val _smoothedFlow =
            MutableSharedFlow<Pair<Float, Float>>(replay = 1, extraBufferCapacity = 256)
        val smoothedFlow: SharedFlow<Pair<Float, Float>> = _smoothedFlow

        /** 앱 전역 싱글톤 */
        val instance: PpgRepository by lazy { PpgRepository(FirebaseFirestore.getInstance()) }

        /** 서비스/BLE → UI로 즉시 전달 */
        fun emitSmoothed(left: Number, right: Number) {
            _smoothedFlow.tryEmit(left.toFloat() to right.toFloat())
        }

        /** 외부 노출 함수는 딱 1개 (모호성 제거) */
        suspend fun trySaveFromLine(line: String): Boolean =
            instance.trySaveFromLineInternal(line)
    }

    /** 내부 저장로직(UID/세션ID는 내부에서 확보) */
    private suspend fun trySaveFromLineInternal(line: String): Boolean {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
        val sid = getSessionId() ?: return false

        val eventType = when {
            line.startsWith("ALERT") -> "ALERT"
            line.startsWith("STAT")  -> "STAT"
            else -> return false
        }
        val ev = parseStatLikeLine(line, eventType) ?: return false

        // 실시간 그래프 즉시 반영
        if (ev.smoothed_left != null && ev.smoothed_right != null) {
            emitSmoothed(ev.smoothed_left!!, ev.smoothed_right!!)
        }

        uploadRecord(uid, sid, ev)
        return true
    }

    /** 옵션: Firestore에서 smoothed만 따라 읽어 UI로 흘리기 */
    fun observeSmoothedFromFirestore(
        uid: String,
        sessionId: String,
        limit: Long = 512L
    ): Flow<Pair<Float, Float>> = callbackFlow {
        val ref = db.collection("ppg_events").document(uid)
            .collection("sessions").document(sessionId)
            .collection("records")
            .orderBy("ts_ms", Query.Direction.DESCENDING)
            .limit(limit)

        val reg = ref.addSnapshotListener { snap, err ->
            if (err != null) { close(err); return@addSnapshotListener }
            if (snap == null) return@addSnapshotListener

            for (d in snap.documents.asReversed()) {
                fun num(key: String): Double? =
                    d.getDouble(key) ?: (d.get(key) as? Number)?.toDouble()
                val l = num("smoothed_left")
                val r = num("smoothed_right")
                if (l != null && r != null) trySend(l.toFloat() to r.toFloat())
            }
        }
        awaitClose { reg.remove() }
    }

    /* ============================================================
     *  D) 공통 유틸
     * ============================================================ */

    private fun readNumberFlexible(d: DocumentSnapshot, key: String): Double? {
        d.getDouble(key)?.let { return it }
        val arr = d.get(key) as? List<*>
        val first = arr?.firstOrNull() as? Number
        return first?.toDouble()
    }

    /** 여러 키 중 첫 번째로 발견되는 숫자값(Double) */
    private fun getDoubleAny(doc: DocumentSnapshot, vararg keys: String): Double? {
        for (k in keys) {
            when (val v = doc.get(k)) {
                is Number -> return v.toDouble()
                is String -> v.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    /** 세션ID "S_yyyyMMdd_HHmmss" → 그 시각의 epoch ms(로컬 타임존) */
    private fun sessionIdBaseEpochMs(sessionId: String): Long {
        return try {
            // Case A: S_yyyyMMdd_HHmmss(_suffix)
            val m1 = Regex("""^S_(\d{8})_(\d{6})""").find(sessionId)
            if (m1 != null) {
                val (ymd, hms) = m1.destructured
                val dt = java.time.LocalDateTime.of(
                    ymd.substring(0, 4).toInt(),
                    ymd.substring(4, 6).toInt(),
                    ymd.substring(6, 8).toInt(),
                    hms.substring(0, 2).toInt(),
                    hms.substring(2, 4).toInt(),
                    hms.substring(4, 6).toInt()
                )
                return dt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }

            // Case B: ISO-8601(UTC)에서 시간 콜론만 '-'로 바꿔 저장된 형태
            // 예: 2025-09-18T12-34-56.789Z_abcd1234
            val m2 = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2})-(\d{2})-(\d{2})""").find(sessionId)
            if (m2 != null) {
                val (Y, M, D, h, m, s) = m2.destructured
                val dt = java.time.LocalDateTime.of(Y.toInt(), M.toInt(), D.toInt(), h.toInt(), m.toInt(), s.toInt())
                // 위 형식은 UTC 기준으로 만든 것이므로 UTC로 간주
                return dt.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            }

            0L // 형식 미일치 시 fallback
        } catch (_: Exception) {
            0L
        }
    }
}
