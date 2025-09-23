// app/src/main/java/com/example/heartsync/data/NotiLogRepository.kt
package com.example.heartsync.data

import android.util.Log
import com.example.heartsync.ui.screens.model.NotiLogRow
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class NotiLogRepository(
    private val db: FirebaseFirestore
) {
    companion object { private const val TAG = "NotiLogRepository" }

    /**
     * 지정한 날짜(yyyyMMdd)의 세션들을 찾고, 각 세션의 records를 리슨하면서
     * event == "ALERT"(또는 eventType == "ALERT")인 문서만 NotiLogRow로 변환해 합칩니다.
     * collectionGroup 인덱스/권한 이슈를 우회합니다.
     */
    fun observeAlertRowsForDate(uid: String, date: LocalDate): Flow<List<NotiLogRow>> = callbackFlow {
        val ymd = date.format(DateTimeFormatter.BASIC_ISO_DATE) // yyyyMMdd
        val sessionsCol = db.collection("ppg_events").document(uid).collection("sessions")

        // 1) day 필드로 우선 리슨
        val qByDay = sessionsCol.whereEqualTo("day", ymd)

        // 2) 보조: 문서 ID prefix로 폴백 (S_yyyyMMdd_…)
        val idPrefix = "S_${ymd}"
        val qByIdPrefix = sessionsCol
            .whereGreaterThanOrEqualTo(FieldPath.documentId(), idPrefix)
            .whereLessThan(FieldPath.documentId(), idPrefix + "\uf8ff")
            .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING)

        val recordRegs: MutableMap<String, ListenerRegistration> = mutableMapOf()
        val rowsBySession: MutableMap<String, List<NotiLogRow>> = mutableMapOf()

        // 로컬 헬퍼: Firestore 문서를 NotiLogRow로 변환 (ALERT 외는 null)
        fun toRow(id: String, data: Map<String, Any?>): NotiLogRow? {
            val event = (data["event"] as? String) ?: (data["eventType"] as? String)
            if (!"ALERT".equals(event, ignoreCase = true)) return null

            val hostIso: String =
                (data["hostIso"] as? String)
                    ?: (data["host_time_iso"] as? String)
                    ?: ((data["server_ts"] as? Timestamp)
                        ?.toDate()
                        ?.time
                        ?.let { Instant.ofEpochMilli(it).toString() }
                        ?: "")

            val reasons: List<String> = when (val r = data["reasons"]) {
                is List<*> -> r.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                is String  -> r.split(',', ';', '|', '、', '；').map { it.trim() }.filter { it.isNotEmpty() }
                else       -> emptyList()
            }

            fun num(name: String): Double? = when (val v = data[name]) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else      -> null
            }

            return NotiLogRow(
                id = id,
                hostIso = hostIso,
                reasons = reasons,
                ampRatio = num("AmpRatio"),
                padMs    = num("PAD") ?: num("PAD_ms"),
                dSutMs   = num("dSUT") ?: num("dSUT_ms")
            )
        }

        fun emitMerged() {
            val merged = rowsBySession.values
                .flatten()
                .distinctBy { it.id }
                .sortedByDescending { it.instantOrNull() }
            Log.d(TAG, "emitMerged(date=$ymd) count=${merged.size}")
            trySend(merged)
        }

        fun clearRecordListeners(keep: Set<String>) {
            (recordRegs.keys - keep).forEach { sid ->
                recordRegs.remove(sid)?.remove()
                rowsBySession.remove(sid)
            }
        }

        fun attachRecordListener(sid: String) {
            if (recordRegs.containsKey(sid)) return
            val reg = sessionsCol.document(sid).collection("records")
                .addSnapshotListener { recSnap, recErr ->
                    if (recErr != null) {
                        Log.w(TAG, "records($sid) err", recErr)
                        rowsBySession[sid] = emptyList()
                        emitMerged()
                        return@addSnapshotListener
                    }
                    val rows: List<NotiLogRow> = recSnap?.documents.orEmpty().mapNotNull { d ->
                        d.data?.let { toRow(d.id, it) }
                    }
                    rowsBySession[sid] = rows
                    emitMerged()
                }
            recordRegs[sid] = reg
        }

        var usingDay = true
        var sessionReg: ListenerRegistration? = null

        fun startListening(q: Query) {
            sessionReg?.remove()
            sessionReg = q.addSnapshotListener { ss, se ->
                if (se != null) {
                    Log.w(TAG, "session listen err usingDay=$usingDay", se)
                    // day 쿼리 실패/빈 결과 → prefix 쿼리로 폴백
                    if (usingDay) {
                        usingDay = false
                        startListening(qByIdPrefix)
                    } else {
                        trySend(emptyList())
                    }
                    return@addSnapshotListener
                }

                val ids: Set<String> = ss?.documents?.map { it.id }?.toSet().orEmpty()
                clearRecordListeners(ids)
                ids.forEach(::attachRecordListener)

                // day 쿼리 결과가 0이면 즉시 prefix로 폴백
                if (usingDay && ids.isEmpty()) {
                    usingDay = false
                    startListening(qByIdPrefix)
                }
            }
        }

        startListening(qByDay)

        awaitClose {
            sessionReg?.remove()
            recordRegs.values.forEach { it.remove() }
            recordRegs.clear()
            rowsBySession.clear()
        }
    }
}
