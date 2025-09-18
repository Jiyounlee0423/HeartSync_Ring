// app/src/main/java/com/example/heartsync/data/remote/PpgRepository.kt
package com.example.heartsync.data.remote

import android.util.Log
import com.example.heartsync.data.model.PpgEvent
import com.example.heartsync.data.remote.PpgRepository.Companion.instance
import com.google.firebase.firestore.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await
import java.lang.System.err
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.google.firebase.firestore.SetOptions

data class PpgPoint(
    val ts: Long,                 // 기존: 로컬/상대 ms
    val left: Double?,
    val right: Double?,
    val serverTs: Long? = null    // ★ 추가: server_ts epoch ms
)

class PpgRepository(
    private val db: FirebaseFirestore
) {
    @Volatile private var sessionId: String = ""
    @Volatile private var lastWriteMs: Long = 0L

    /** 외부에서 현재 세션 ID 세팅/조회 */
    fun setSessionId(id: String) { sessionId = id; Log.d("PpgRepo", "sessionId set -> $sessionId") }
    fun getSessionId(): String? = sessionId

    private fun recordsCol(userId: String, sessionId: String) =
        db.collection("ppg_events").document(userId)
            .collection("sessions").document(sessionId)
            .collection("records")

    /* ============================================================
     *  A) 하루치 통합 스트림 (문서ID: S_yyyyMMdd_HHmmss 기준)
     * ============================================================ */

    /** 오늘(혹은 지정 날짜)의 세션들을 찾아 records를 합쳐 흘려보냄 */
    fun observeDayPpg(uid: String, date: LocalDate): Flow<List<PpgPoint>> = callbackFlow {
        val dayStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val start = "S_${dayStr}_"
        val end = "S_${dayStr}_\uf8ff"

        val sessionQuery = db.collection("ppg_events")
            .document(uid).collection("sessions")
            .whereGreaterThanOrEqualTo(FieldPath.documentId(), start)
            .whereLessThanOrEqualTo(FieldPath.documentId(), end)

        val recordRegs = mutableListOf<ListenerRegistration>()
        val latestBySession = mutableMapOf<String, List<PpgPoint>>()

        fun pushCombined() {
            // 이제 ts가 절대시간이므로 이것만으로 시간순 정렬됨
            val combined = latestBySession.values.flatten().sortedBy { it.ts }
            trySend(combined)
        }



        val sessionReg = sessionQuery.addSnapshotListener { snap, err ->
            if (err != null) { Log.e("PpgRepository", "session listen error", err); return@addSnapshotListener }

            recordRegs.forEach { it.remove() }
            recordRegs.clear()
            latestBySession.clear()

            snap?.documents?.forEach { sdoc ->
                val base = sessionIdBaseEpochMs(sdoc.id)              // ✅ sdoc
                val reg = sdoc.reference.collection("records")
                    .orderBy("ts_ms")
                    .addSnapshotListener { recSnap, recErr ->
                        if (recErr != null) return@addSnapshotListener
                        val list = recSnap?.documents?.mapNotNull { d ->
                            val rel = (d.getLong("ts_ms") ?: d.getLong("ts") ?: d.getLong("timestamp") ?: d.getLong("idx"))
                                ?: return@mapNotNull null

                            val left  = getDoubleAny(d, "smoothed_left","Smoothed_Left","smooted_left","PPGf_L","ppgf_l","PPG_L")
                            val right = getDoubleAny(d, "smoothed_right","Smoothed_Right","smooted_right","PPGf_R","ppgf_r","PPG_R")
                            val serverTs = d.getTimestamp("server_ts")?.toDate()?.time
                            val absTs = serverTs ?: (base + rel)

                            PpgPoint(ts = absTs, left = left, right = right, serverTs = serverTs)
                        } ?: emptyList()

                        latestBySession[sdoc.id] = list                 // ✅ sdoc
                        trySend(latestBySession.values.flatten().sortedBy { it.ts })
                    }
                recordRegs.add(reg)
            }
        }

        awaitClose {
            sessionReg.remove()
            recordRegs.forEach { it.remove() }
        }
    }.distinctUntilChanged()

    /** 최신 세션 날짜를 자동 감지해서 그 날짜로 observe */
    fun observeLatestDayPpg(uid: String): Flow<List<PpgPoint>> = callbackFlow {
        val root = db.collection("ppg_events").document(uid).collection("sessions")
        var innerReg: ListenerRegistration? = null

        val outerReg = root
            .orderBy(FieldPath.documentId(), Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snap, err ->
                if (err != null) { close(err); return@addSnapshotListener }
                val latestId = snap?.documents?.firstOrNull()?.id
                val dayStr = latestId?.split('_')?.getOrNull(1) ?: return@addSnapshotListener

                innerReg?.remove()
                innerReg = observeDayListener(uid, dayStr) { list -> trySend(list) }
            }

        awaitClose {
            outerReg.remove()
            innerReg?.remove()
        }
    }

    /** 내부 헬퍼: 특정 yyyyMMdd를 실시간으로 합쳐 콜백 */
    private fun observeDayListener(
        uid: String,
        dayStr: String,
        onData: (List<PpgPoint>) -> Unit
    ): ListenerRegistration {
        val start = "S_${dayStr}_"
        val end = "S_${dayStr}_\uf8ff"
        val sessionQuery = db.collection("ppg_events").document(uid).collection("sessions")
            .whereGreaterThanOrEqualTo(FieldPath.documentId(), start)
            .whereLessThanOrEqualTo(FieldPath.documentId(), end)

        val recordRegs = mutableListOf<ListenerRegistration>()
        val latestBySession = mutableMapOf<String, List<PpgPoint>>()

        fun pushCombined() = onData(latestBySession.values.flatten().sortedBy { it.ts })

        val sessionReg = sessionQuery.addSnapshotListener { snap, _ ->
            recordRegs.forEach { it.remove() }
            recordRegs.clear()
            latestBySession.clear()

            snap?.documents?.forEach { sdoc ->
                val base = sessionIdBaseEpochMs(sdoc.id)              // ✅ sdoc
                val reg = sdoc.reference.collection("records")
                    .orderBy("ts_ms")
                    .addSnapshotListener { recSnap, _ ->
                        val list = recSnap?.documents?.mapNotNull { d ->
                            val rel = (d.getLong("ts_ms") ?: d.getLong("ts") ?: d.getLong("timestamp") ?: d.getLong("idx"))
                                ?: return@mapNotNull null

                            val left  = getDoubleAny(d, "smoothed_left","Smoothed_Left","smooted_left","PPGf_L","ppgf_l","PPG_L")
                            val right = getDoubleAny(d, "smoothed_right","Smoothed_Right","smooted_right","PPGf_R","ppgf_r","PPG_R")
                            val serverTs = d.getTimestamp("server_ts")?.toDate()?.time
                            val absTs = serverTs ?: (base + rel)

                            PpgPoint(ts = absTs, left = left, right = right, serverTs = serverTs)
                        } ?: emptyList()

                        latestBySession[sdoc.id] = list                 // ✅ sdoc
                        onData(latestBySession.values.flatten().sortedBy { it.ts })
                    }
                recordRegs.add(reg)
            }
        }

        return sessionReg
    }

    /** 최신 세션 1개 문서ID에서 yyyyMMdd만 추출 (동기 호출이 필요할 때 사용) */
    suspend fun fetchLatestDay(uid: String): String? {
        val qs = db.collection("ppg_events").document(uid)
            .collection("sessions")
            .orderBy(FieldPath.documentId(), Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .await()

        val id = qs.documents.firstOrNull()?.id ?: return null
        val parts = id.split('_')
        return if (parts.size >= 3) parts[1] else null
    }

    /* ============================================================
     *  B) 업로드/최근레코드 유틸
     * ============================================================ */

    /** STAT/ALERT 공통 파서 (필요 시 사용) */
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
    // 부모 세션 문서를 항상 만들어 둔다 (없으면 생성, 있으면 merge)
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

    /** 최근 레코드(서버타임순) 구독 */
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
     *  C) 가벼운 실시간 값 허브 (BLE/Service → UI)
     * ============================================================ */

    // PpgRepository.kt 내부, 클래스 본문에서 단 하나만 존재해야 함
    companion object {
        // --- 기존에 쓰던 값/흐름들 유지 ---
        private const val MIN_INTERVAL_MS = 100L
        private val _smoothedFlow =
            kotlinx.coroutines.flow.MutableSharedFlow<Pair<Float, Float>>(
                replay = 1,
                extraBufferCapacity = 256
            )
        val smoothedFlow: kotlinx.coroutines.flow.SharedFlow<Pair<Float, Float>> = _smoothedFlow

        // 앱 전역에서 쓰는 싱글톤 인스턴스
        val instance: PpgRepository by lazy { PpgRepository(com.google.firebase.firestore.FirebaseFirestore.getInstance()) }

        // BLE/서비스 쪽에서 가벼운 실시간 값 넣을 때 사용
        fun emitSmoothed(left: Number, right: Number) {
            _smoothedFlow.tryEmit(left.toFloat() to right.toFloat())
        }

        // ✅ 정적 위임: BLE가 PpgRepository.trySaveFromLine(line) 형태로 부를 수 있게
        suspend fun trySaveFromLine(line: String): Boolean =
            instance.trySaveFromLine(line)
    }

    fun pushSmoothed(l: Float, r: Float) {
        _smoothedFlow.tryEmit(l to r)
    }

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

    /** 문서에서 후보 키들 중 첫 번째로 존재하는 값을 Double로 파싱 */
    private fun getDoubleAny(doc: DocumentSnapshot, vararg keys: String): Double? {
        for (k in keys) {
            when (val v = doc.get(k)) {
                is Number -> return v.toDouble()
                is String -> v.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }
    // 1) uid + sessionId + line 모두 아는 버전
    suspend fun trySaveFromLine(uid: String, sessionId: String, line: String): Boolean {
        val eventType = when {
            line.startsWith("STAT")  -> "STAT"
            line.startsWith("ALERT") -> "ALERT"
            else -> return false
        }
        val ev = parseStatLikeLine(line, eventType) ?: return false
        uploadRecord(uid, sessionId, ev)
        return true
    }

    // 2) uid/세션ID를 Repo가 기억한 값으로 쓰는 버전
    suspend fun trySaveFromLine(line: String): Boolean {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return false
        val sid = getSessionId() ?: return false
        return trySaveFromLine(uid, sid, line)
    }

    private fun observeOneSessionRecords(
        uid: String,
        sessionId: String,
        onData: (List<PpgPoint>) -> Unit
    ): ListenerRegistration {
        return db.collection("ppg_events").document(uid)
            .collection("sessions").document(sessionId)
            .collection("records")
            .orderBy("ts_ms")
            .addSnapshotListener { recSnap, recErr ->
                if (recErr != null) {
                    Log.e("PpgRepo", "records listen error (direct) for $sessionId", recErr)
                    return@addSnapshotListener
                }
                Log.d("PpgRepo", "records (direct) for $sessionId: count=${recSnap?.size() ?: 0}")

                val list = recSnap?.documents?.mapNotNull { d ->
                    val ts = (d.getLong("ts_ms")
                        ?: d.getLong("ts")
                        ?: d.getLong("timestamp")
                        ?: d.getLong("idx")) ?: return@mapNotNull null

                    val left  = getDoubleAny(d,
                        "smoothed_left","Smoothed_Left","smooted_left","PPGf_L","ppgf_l","PPG_L","ampL")
                    val right = getDoubleAny(d,
                        "smoothed_right","Smoothed_Right","smooted_right","PPGf_R","ppgf_r","PPG_R","ampR")

                    val serverTs = d.getTimestamp("server_ts")?.toDate()?.time   // ★ 추가

                    PpgPoint(ts, left, right, serverTs)                          // ★ 변경
                } ?: emptyList()

                onData(list)
            }
    }
    private fun sessionIdBaseEpochMs(sessionId: String): Long {
        // S_20250918_145449
        val parts = sessionId.split('_')
        if (parts.size < 3) return 0L
        val ymd = parts[1]; val hms = parts[2]
        val dt = java.time.LocalDateTime.of(
            ymd.substring(0,4).toInt(),
            ymd.substring(4,6).toInt(),
            ymd.substring(6,8).toInt(),
            hms.substring(0,2).toInt(),
            hms.substring(2,4).toInt(),
            hms.substring(4,6).toInt()
        )
        return dt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }


}
