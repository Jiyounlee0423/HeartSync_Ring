// app/src/main/java/com/example/heartsync/ui/screens/NotiLogRepository.kt
package com.example.heartsync.ui.screens

import android.util.Log
import com.example.heartsync.ui.screens.model.NotiLogRow
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Instant

class NotiLogRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    companion object { private const val TAG = "NotiLogRepository" }

    /**
     * collectionGroup("records")에서 현재 사용자(ownerUid 또는 uid) + ALERT 만 수집
     * - 스키마 호환: eventType 또는 event 둘 다 지원
     * - hostIso 없는 문서는 server_ts 로 ISO 문자열 생성
     * - AmpRatio, PAD, dSUT 키 혼용 대응 (PAD_ms, dSUT_ms 포함)
     */
    fun observeAlertRows(limit: Long = 500): Flow<List<NotiLogRow>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Log.w(TAG, "observeAlertRows: no auth uid")
            trySend(emptyList())
            close(); return@callbackFlow
        }

        val base = db.collectionGroup("records")

        // ownerUid/uid 어느 필드가 저장되었는지에 따라 둘 다 시도
        // (둘 다 저장했다면 ownerUid 쿼리로 충분)
        val qEventType = base
            .whereEqualTo("ownerUid", uid)
            .whereEqualTo("eventType", "ALERT")
            .limit(limit)

        val qEvent = base
            .whereEqualTo("ownerUid", uid)
            .whereEqualTo("event", "ALERT")
            .limit(limit)

        var cache1: List<NotiLogRow> = emptyList()
        var cache2: List<NotiLogRow> = emptyList()

        fun toRow(id: String, data: Map<String, Any?>?): NotiLogRow? {
            if (data == null) return null

            // hostIso 우선, 없으면 host_time_iso, 그것도 없으면 server_ts 로 보정
            val hostIso = when (val v = data["hostIso"] ?: data["host_time_iso"]) {
                is String -> v
                else -> {
                    val ts = (data["server_ts"] as? com.google.firebase.Timestamp)?.toDate()?.time
                    if (ts != null) Instant.ofEpochMilli(ts).toString() else ""
                }
            }

            val reasons: List<String> = when (val r = data["reasons"]) {
                is List<*> -> r.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                is String  -> r.split(',', ';', '|', '、', '；').map { it.trim() }.filter { it.isNotEmpty() }
                else -> emptyList()
            }

            fun num(name: String): Double? = when (val v = data[name]) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else -> null
            }

            return NotiLogRow(
                id = id,
                hostIso = hostIso,
                reasons = reasons,
                ampRatio = num("AmpRatio"),
                padMs = num("PAD") ?: num("PAD_ms"),
                dSutMs = num("dSUT") ?: num("dSUT_ms")
            )
        }

        fun emitMerged() {
            val merged = (cache1 + cache2)
                .distinctBy { it.id }
                .sortedByDescending { it.instantOrNull() }  // 최신 먼저
            trySend(merged)
        }

        val r1 = qEventType.addSnapshotListener { snap, e ->
            if (e != null) { Log.w(TAG, "qEventType err", e); trySend(emptyList()); return@addSnapshotListener }
            cache1 = snap?.documents.orEmpty().mapNotNull { toRow(it.id, it.data) }
            emitMerged()
        }
        val r2 = qEvent.addSnapshotListener { snap, e ->
            if (e != null) { Log.w(TAG, "qEvent err", e); trySend(emptyList()); return@addSnapshotListener }
            cache2 = snap?.documents.orEmpty().mapNotNull { toRow(it.id, it.data) }
            emitMerged()
        }

        awaitClose { r1.remove(); r2.remove() }
    }
}
