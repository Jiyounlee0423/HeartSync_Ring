// app/src/main/java/com/example/heartsync/data/remote/PpgRepository.kt
package com.example.heartsync.data.remote

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/* =========================
 *  그래프 포인트 (로컬 정의)
 *  이미 프로젝트에 PpgPoint가 있으면 아래 정의를 제거하고
 *  올바른 패키지로 import 하세요.
 * ========================= */
data class PpgPoint(
    val time: Long,
    val left: Double,
    val right: Double,
    val serverTime: Long? = null
)

/* =========================
 *  Firestore 레코드 파싱용 DTO
 * ========================= */
data class PpgRecord(
    val ts: Long? = null,
    val smoothed_left: Double? = null,
    val smoothed_right: Double? = null,
    val event: String? = null,       // "STAT" | "ALERT"
    val side: String? = null,        // "left" | "right" | "balanced" | "uncertain"
    val alert_type: String? = null,  // "FLOW_IMBALANCE" 등
    val reasons: List<String> = emptyList()
)

/* Firestore 문서 -> PpgRecord */
private fun docToRecord(data: Map<String, Any?>): PpgRecord {
    fun asDouble(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
    fun asLong(v: Any?): Long? = when (v) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }
    fun asString(v: Any?): String? = v as? String
    fun asReasons(v: Any?): List<String> = when (v) {
        is List<*> -> v.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        is String -> v.split(',', '；', '、', '|', ';').map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    return PpgRecord(
        ts = asLong(data["ts"]) ?: asLong(data["timestamp"]) ?: asLong(data["time"]),
        smoothed_left  = asDouble(data["smoothed_left"]) ?: asDouble(data["PPGf_L"]),
        smoothed_right = asDouble(data["smoothed_right"]) ?: asDouble(data["PPGf_R"]),
        event       = asString(data["event"]) ?: asString(data["Event"]) ?: asString(data["EVENT"]),
        side        = asString(data["side"])  ?: asString(data["Side"])  ?: asString(data["SIDE"]),
        alert_type  = asString(data["alert_type"]) ?: asString(data["alertType"]) ?: asString(data["AlertType"]),
        reasons     = asReasons(data["reasons"] ?: data["Reasons"] ?: data["reason"])
    )
}

/* =========================
 *  업로드/관측에 쓰는 이벤트 DTO
 *  (프로젝트에 이미 있다면 import 하세요)
 * ========================= */
data class PpgEvent(
    val event: String,
    val host_time_iso: String = "",
    val ts_ms: Long,
    val alert_type: String? = null,
    val reasons: List<String>? = null,
    val AmpRatio: Double? = null,
    val PAD_ms: Double? = null,
    val dSUT_ms: Double? = null,
    val ampL: Double? = null,
    val ampR: Double? = null,
    val SUTL_ms: Double? = null,
    val SUTR_ms: Double? = null,
    val BPM_L: Double? = null,
    val BPM_R: Double? = null,
    val PQIL: Int? = null,
    val PQIR: Int? = null,
    val side: String? = null,
    val smoothed_left: Double? = null,
    val smoothed_right: Double? = null
)

class PpgRepository(
    private val db: FirebaseFirestore
) {
    @Volatile private var sessionId: String = ""

    /* ===== 팝업용 ALERT 스트림 ===== */
    data class UiAlert(
        val side: String,           // "left" | "right"
        val alertType: String?,
        val reasons: List<String>,
        val ts: Long?
    )
    private val _alerts = MutableSharedFlow<UiAlert>(replay = 0, extraBufferCapacity = 64)
    val alerts: SharedFlow<UiAlert> = _alerts

    private val lastAlertAtBySide = mutableMapOf<String, Long>()
    private val alertMinIntervalMs = 2500L

    private fun maybeEmitAlert(event: String?, side: String?, alertType: String?, reasons: List<String>?, ts: Long?) {
        val isAlert = event?.equals("ALERT", true) == true
        val sideNorm = when {
            side.equals("left", true) -> "left"
            side.equals("right", true) -> "right"
            else -> null
        }
        if (!isAlert || sideNorm == null) return

        val now = System.currentTimeMillis()
        val last = lastAlertAtBySide[sideNorm] ?: 0L
        if (now - last < alertMinIntervalMs) return
        lastAlertAtBySide[sideNorm] = now

        _alerts.tryEmit(UiAlert(sideNorm, alertType, reasons ?: emptyList(), ts))
    }

    /* ===== 세션 관리 ===== */
    fun setSessionId(id: String) { sessionId = id; Log.d("PpgRepo", "sessionId set -> $sessionId") }
    fun getSessionId(): String? = sessionId

    private fun recordsCol(userId: String, sessionId: String) =
        db.collection("ppg_events").document(userId)
            .collection("sessions").document(sessionId)
            .collection("records")

    /* ============================================================
     *  A) 하루치 통합 스트림
     * ============================================================ */
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
            trySend(latestBySession.values.flatten().sortedBy { it.time })
        }

        val sessionReg = sessionQuery.addSnapshotListener { snap, err ->
            if (err != null) {
                Log.e("PpgRepo", "session listen error", err)
                return@addSnapshotListener
            }
            recordRegs.forEach { it.remove() }
            recordRegs.clear()
            latestBySession.clear()

            snap?.documents?.forEach { sdoc ->
                val base = sessionIdBaseEpochMs(sdoc.id)
                val reg = sdoc.reference.collection("records")
                    .orderBy("ts_ms")
                    .addSnapshotListener { recSnap, recErr ->
                        if (recErr != null) return@addSnapshotListener
                        val list = recSnap?.documents?.mapNotNull { d ->
                            // ALERT 감지
                            val evStr = d.getString("event")
                            val sideStr = d.getString("side")
                            val alertType = d.getString("alert_type")
                            val reasons = when (val r = d.get("reasons")) {
                                is List<*> -> r.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                                is String -> r.split(',', '；', '、', '|', ';').map { it.trim() }.filter { it.isNotEmpty() }
                                else -> emptyList()
                            }
                            val relForTs = (d.getLong("ts_ms")
                                ?: d.getLong("ts")
                                ?: d.getLong("timestamp")
                                ?: d.getLong("idx"))
                            val absForTs = d.getTimestamp("server_ts")?.toDate()?.time ?: (relForTs?.let { base + it })
                            maybeEmitAlert(evStr, sideStr, alertType, reasons, absForTs)

                            // 그래프 포인트
                            val left  = getDoubleAny(d, "smoothed_left","Smoothed_Left","smooted_left","PPGf_L","ppgf_l","PPG_L")
                            val right = getDoubleAny(d, "smoothed_right","Smoothed_Right","smooted_right","PPGf_R","ppgf_r","PPG_R")
                            val serverTs = d.getTimestamp("server_ts")?.toDate()?.time
                            val absTs = serverTs ?: (relForTs?.let { base + it }) ?: return@mapNotNull null
                            if (left == null || right == null) return@mapNotNull null

                            // 이름 인자 사용하지 않음 (시그니처 차이 방지)
                            PpgPoint(absTs, left, right, serverTs)
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

    /* ============================================================
     *  B) 라인 저장 / 실시간 값 전달
     * ============================================================ */
    companion object {
        private val _smoothedFlow =
            MutableSharedFlow<Pair<Float, Float>>(replay = 1, extraBufferCapacity = 256)
        val smoothedFlow: SharedFlow<Pair<Float, Float>> = _smoothedFlow

        /** 앱 전역 싱글톤 */
        val instance: PpgRepository by lazy { PpgRepository(FirebaseFirestore.getInstance()) }

        fun emitSmoothed(left: Number, right: Number) {
            _smoothedFlow.tryEmit(left.toFloat() to right.toFloat())
        }

        suspend fun trySaveFromLine(line: String): Boolean =
            instance.trySaveFromLineInternal(line)
    }

    private suspend fun trySaveFromLineInternal(line: String): Boolean {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
        val sid = getSessionId() ?: return false

        val eventType = when {
            line.startsWith("ALERT") -> "ALERT"
            line.startsWith("STAT")  -> "STAT"
            else -> return false
        }
        val ev = parseStatLikeLine(line, eventType) ?: return false

        if (ev.smoothed_left != null && ev.smoothed_right != null) {
            emitSmoothed(ev.smoothed_left!!, ev.smoothed_right!!)
        }
        maybeEmitAlert(ev.event, ev.side, ev.alert_type, ev.reasons, ev.ts_ms)

        uploadRecord(uid, sid, ev)
        return true
    }

    /* ============================================================
     *  C) 업로드 & 최근 관측(선택)
     * ============================================================ */
    suspend fun uploadRecord(userId: String, sessionId: String, ev: PpgEvent): String {
        ensureSessionDoc(userId, sessionId)
        val ref = recordsCol(userId, sessionId).document()
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
        ref.set(map).await()
        return ref.id
    }

    fun observeRecent(
        userId: String,
        sessionId: String,
        limit: Long = 200
    ): Flow<List<PpgEvent>> = callbackFlow {
        val qs = recordsCol(userId, sessionId)
            .orderBy("server_ts")
            .limitToLast(limit)

        val reg = qs.addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            val items = snap.documents.mapNotNull { d ->
                try {
                    val ev = PpgEvent(
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
                    maybeEmitAlert(ev.event, ev.side, ev.alert_type, ev.reasons, ev.ts_ms)
                    ev
                } catch (_: Exception) { null }
            }
            trySend(items)
        }
        awaitClose { reg.remove() }
    }

    /* ============================================================
     *  D) 유틸
     * ============================================================ */
    private fun readNumberFlexible(d: DocumentSnapshot, key: String): Double? {
        d.getDouble(key)?.let { return it }
        val arr = d.get(key) as? List<*>
        val first = arr?.firstOrNull() as? Number
        return first?.toDouble()
    }

    private fun getDoubleAny(doc: DocumentSnapshot, vararg keys: String): Double? {
        for (k in keys) {
            when (val v = doc.get(k)) {
                is Number -> return v.toDouble()
                is String -> v.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private suspend fun ensureSessionDoc(uid: String, sessionId: String) {
        val day = sessionId.substring(2, 10) // "S_yyyyMMdd_HHmmss_..." -> yyyyMMdd
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

    /** 세션ID 파싱: S_yyyyMMdd_HHmmss or yyyy-MM-ddTHH-mm-ss... */
    private fun sessionIdBaseEpochMs(sessionId: String): Long {
        return try {
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
            val m2 = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2})-(\d{2})-(\d{2})""").find(sessionId)
            if (m2 != null) {
                val (Y, M, D, h, m, s) = m2.destructured
                val dt = java.time.LocalDateTime.of(Y.toInt(), M.toInt(), D.toInt(), h.toInt(), m.toInt(), s.toInt())
                return dt.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            }
            0L
        } catch (_: Exception) { 0L }
    }

    /** STAT / ALERT 라인 공통 파서 */
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
            smoothed_right = ppgfR
        )
    }
}
