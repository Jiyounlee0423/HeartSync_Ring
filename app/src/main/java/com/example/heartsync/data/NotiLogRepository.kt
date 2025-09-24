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
        val TAG_LOCAL = "$TAG/$ymd"

        // 두 개 루트 모두 리슨
        val sessionRoots = listOf(
            db.collection("ppg_events").document(uid).collection("sessions"),
            db.collection("users").document(uid).collection("sessions")
        )

        // 전역 누적 공간: 문서 경로 → Row
        val rowsByPath = mutableMapOf<String, NotiLogRow>()

        // 세션 목록 리스너, 세션별 레코드 리스너를 관리
        val sessionRegs = mutableListOf<ListenerRegistration>()
        val recordRegsBySession = mutableMapOf<String, ListenerRegistration>() // key = sessionPath

        fun emitNow() {
            val out = rowsByPath.values
                .filter { it.eventType == "ALERT" || it.alertType != null }
                .sortedByDescending { it.epochMs ?: 0L }
            trySend(out)
        }

        // 세션ID → epoch 복원
        fun epochFromSessionIdOrNull(sessionPath: String): Long? {
            val sid = sessionPath.substringAfterLast("/")
            if (sid.length < 18) return null
            val head = sid.substring(0, 18) // S_yyyyMMdd_HHmmss_
            return runCatching {
                LocalDateTime.parse(head, SID_FMT).atZone(KST).toInstant().toEpochMilli()
            }.getOrNull()
        }

        // 레코드 스냅샷을 rowsByPath에 반영 (세션 단위로 덮어쓰기)
        fun updateSessionRows(sessionPath: String, rs: QuerySnapshot?) {
            // 1) 해당 세션의 기존 항목 제거
            val prefix = "$sessionPath/records/"
            val toRemove = rowsByPath.keys.filter { it.startsWith(prefix) }
            toRemove.forEach { rowsByPath.remove(it) }

            // 2) 새 스냅샷 반영
            rs?.documents?.forEach { doc ->
                val pathKey = doc.reference.path
                val row = toRow(doc)?.let { r ->
                    // epoch 보강: 없으면 세션ID에서 복원
                    val e = r.epochMs ?: epochFromSessionIdOrNull(sessionPath)
                    r.copy(epochMs = e)
                }
                if (row != null && (row.eventType == "ALERT" || row.alertType != null)) {
                    rowsByPath[pathKey] = row
                }
            }
            emitNow()
            Log.d(TAG, "updateSessionRows session=$sessionPath size=${rs?.size()}")
        }

        // 세션들을 리슨: 추가/제거만 반영
        fun listenSessions(root: CollectionReference) {
            val q = root
                .orderBy(FieldPath.documentId())
                .startAt("S_${ymd}_")
                .endAt("S_${ymd}_\uf8ff")

            val reg = q.addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG_LOCAL, "sessions listen error: ${root.path}", err)
                    return@addSnapshotListener
                }

                val rootPathPrefix = root.path + "/"
                val sessionPaths = snap?.documents?.map { it.reference.path }?.toSet() ?: emptySet()

                // 🔴 스냅샷이 비었으면: 이 루트의 "해당 날짜" 세션 데이터/리스너를 전부 제거
                if (snap == null || snap.isEmpty) {
                    // 이 루트 + 해당 날짜 prefix 를 가지는 세션들만 골라서 정리
                    val toDrop = recordRegsBySession.keys.filter { sp ->
                        sp.startsWith(rootPathPrefix) && sp.substringAfterLast("/")
                            .startsWith("S_${ymd}_")
                    }
                    toDrop.forEach { sp ->
                        recordRegsBySession.remove(sp)?.remove()
                        val prefix = "$sp/records/"
                        rowsByPath.keys.filter { it.startsWith(prefix) }.forEach { rowsByPath.remove(it) }
                    }
                    emitNow()   // ← 빈 목록이면 빈 목록이 그대로 emit 됨
                    return@addSnapshotListener
                }

                // 기존/추가/삭제 세션 차집합 로직(그대로 유지)
                val existingSessions = recordRegsBySession.keys
                    .filter { it.startsWith(rootPathPrefix) }
                    .toSet()

                val removed = existingSessions - sessionPaths
                removed.forEach { sp ->
                    recordRegsBySession.remove(sp)?.remove()
                    val prefix = "$sp/records/"
                    rowsByPath.keys.filter { it.startsWith(prefix) }.forEach { rowsByPath.remove(it) }
                }
                if (removed.isNotEmpty()) emitNow()

                val added = sessionPaths - existingSessions
                added.forEach { sp ->
                    val recs = db.document(sp).collection("records")
                    val rr = recs.limit(limitPerSession).addSnapshotListener { rs, e ->
                        if (e != null) {
                            Log.w(TAG_LOCAL, "records listen error: $sp", e)
                            return@addSnapshotListener
                        }
                        updateSessionRows(sp, rs)   // 세션 단위로 rowsByPath 갱신 → emitNow() 호출됨
                    }
                    recordRegsBySession[sp] = rr
                }
            }
            sessionRegs.add(reg)
        }

        sessionRoots.forEach { listenSessions(it) }

        awaitClose {
            sessionRegs.forEach { it.remove() }
            recordRegsBySession.values.forEach { it.remove() }
            recordRegsBySession.clear()
            rowsByPath.clear()
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
