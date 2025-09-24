package com.example.heartsync.data

import android.util.Log
import com.example.heartsync.ui.screens.model.NotiLogRow
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.*


class NotiLogRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    companion object { private const val TAG = "NotiLogRepository" }

    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun observeAlertsByDate(date: LocalDate, limitPerSession: Long = 1000): Flow<List<NotiLogRow>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(emptyList())
            close(IllegalStateException("Not signed in"))
            return@callbackFlow
        }

        val ymd = date.format(dateFmt)

        // ✅ 두 경로를 모두 리슨 (ppg_events 우선, users 보조)
        val roots = listOf(
            db.collection("ppg_events").document(uid).collection("sessions"),
            db.collection("users").document(uid).collection("sessions")
        )

        // 세션 리스너/레코드 리스너를 전부 관리
        val sessionRegs = mutableListOf<ListenerRegistration>()
        childRegs.forEach { it.remove() }
        childRegs.clear()

        fun listenOnSessions(sessionsRef: CollectionReference) {
            val q = sessionsRef
                .orderBy(FieldPath.documentId())
                .startAt("S_${ymd}_")
                .endAt("S_${ymd}_\uf8ff")

            val reg = q.addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e(TAG, "sessions listen error (${sessionsRef.path})", err)
                    trySend(emptyList())
                    return@addSnapshotListener
                }

                // 기존 레코드 리스너 제거 후 재구성
                childRegs.forEach { it.remove() }
                childRegs.clear()

                if (snap == null || snap.isEmpty) {
                    // 다른 루트가 채워줄 수 있으니 여기선 비우지 않음
                    return@addSnapshotListener
                }

                val allRows = mutableListOf<NotiLogRow>()

                snap.documents.forEach { sessDoc ->
                    val recs = sessDoc.reference.collection("records")

                    // ALERT 전용
                    val qAlert = recs.whereEqualTo("eventType", "ALERT")
                        .limit(limitPerSession)

                    val reg1 = qAlert.addSnapshotListener { rs, e1 ->
                        if (e1 != null) {
                            Log.w(TAG, "records(alert/eventType) listen error", e1)
                            return@addSnapshotListener
                        }
                        val rows1 = rs?.documents?.mapNotNull { toRow(it) } ?: emptyList()

                        // 레거시(event == "ALERT")
                        val qLegacy = recs.limit(200L)
                        val reg2 = qLegacy.addSnapshotListener { rs2, e2 ->
                            if (e2 != null) {
                                Log.w(TAG, "records(legacy/event) listen error", e2)
                                updateAndEmit(allRows, rows1) { snapshot -> trySend(snapshot) }
                                return@addSnapshotListener
                            }
                            val legacy = rs2?.documents
                                ?.mapNotNull { toRow(it) }
                                ?.filter { it.eventType == "ALERT" || (it.alertType != null) }
                                ?: emptyList()

                            updateAndEmit(allRows, rows1 + legacy) { snapshot -> trySend(snapshot) }
                        }
                        childRegs.add(reg2)
                    }
                    childRegs.add(reg1)
                }
            }
            sessionRegs.add(reg)
        }

        // 두 루트 모두 리슨 시작
        roots.forEach { listenOnSessions(it) }

        awaitClose {
            sessionRegs.forEach { it.remove() }
            childRegs.forEach { it.remove() }
            childRegs.clear()
        }
    }

    // === 내부 상태 ===
    private val childRegs = mutableListOf<ListenerRegistration>()
    private val lock = Any()

    private fun updateAndEmit(
        acc: MutableList<NotiLogRow>,
        incoming: List<NotiLogRow>,
        trySendFn: (List<NotiLogRow>) -> Unit
    ) {
        synchronized(lock) {
            val map = (acc + incoming).associateBy { it.id }
            val merged = map.values
                .filter { it.eventType == "ALERT" || (it.alertType != null) }
                .sortedByDescending { it.epochMs ?: 0L }

            acc.clear()
            acc.addAll(merged)
            trySendFn(acc.toList())
        }
    }

    private val SID_FMT = DateTimeFormatter.ofPattern("'S_'yyyyMMdd'_'HHmmss'_'")
    private val KST: ZoneId = ZoneId.of("Asia/Seoul")

    private fun epochFromSessionIdOrNull(doc: DocumentSnapshot): Long? {
        // sessions/{sid}/records/{rid} → sid 파싱
        val sid = doc.reference.parent.parent?.id ?: return null
        // 기대형식: S_yyyyMMdd_HHmmss_XXXX
        // 앞 18글자까지가 패턴 'S_' + 8 + '_' + 6 + '_' = 18
        if (sid.length < 18) return null
        val head = sid.substring(0, 18) // ex) S_20250924_154514_
        return runCatching {
            LocalDateTime.parse(head, SID_FMT).atZone(KST).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun ratioOrNull(a: Double?, b: Double?): Double? {
        if (a == null || b == null) return null
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        if (hi == 0.0) return null
        return lo / hi
    }

    private fun toRow(doc: DocumentSnapshot): NotiLogRow? {
        val eventType = (doc.getString("eventType") ?: doc.getString("event"))?.uppercase()
        val alertType = doc.getString("alert_type") ?: doc.getString("alertType")

        // 시간 계열
        val epochMs: Long? = when {
            doc.contains("epochMs") -> doc.getLong("epochMs")
            doc.contains("server_ts") -> doc.getTimestamp("server_ts")?.toDate()?.time
            else -> epochFromSessionIdOrNull(doc)                     // ⭐ 세션ID로 복원
        }

        // hostIso가 빈 문자열이면 null 취급
        val hostIsoRaw = doc.getString("hostIso") ?: doc.getString("host_time_iso")
        val hostIso = hostIsoRaw?.takeIf { it.isNotBlank() }

        // 숫자 키들
        fun dbl(key: String): Double? =
            (doc.getDouble(key)
                ?: doc.getLong(key)?.toDouble()
                ?: doc.getString(key)?.toDoubleOrNull())

        val ampL = dbl("ampL")
        val ampR = dbl("ampR")
        val ampRatio = dbl("AmpRatio") ?: dbl("ampRatio") ?: ratioOrNull(ampL, ampR) // ⭐ 계산

        val dSutMs = dbl("dSUT_ms") ?: dbl("dSUT")
        ?: run {
            val sutL = dbl("SUTL_ms") ?: dbl("SUTL")
            val sutR = dbl("SUTR_ms") ?: dbl("SUTR")
            if (sutL != null && sutR != null) kotlin.math.abs(sutL - sutR) else null
        }

        val reasons: List<String>? = when (val r = doc.get("reasons")) {
            is List<*> -> r.mapNotNull { it?.toString() }
            else -> doc.getString("reason")?.let { listOf(it) }
        }

        return NotiLogRow(
            id        = doc.id,
            epochMs   = epochMs,
            hostIso   = hostIso,
            eventType = eventType,
            alertType = alertType,
            side      = doc.getString("side"),
            reasons   = reasons,
            ampRatio  = ampRatio,
            padMs     = dbl("PAD_ms") ?: dbl("PAD") ?: dbl("padMs"),
            dSutMs    = dSutMs
        )
    }

}
