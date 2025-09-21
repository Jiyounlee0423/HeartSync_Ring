package com.example.heartsync.data

import android.util.Log
import com.example.heartsync.ui.screens.model.NotiLogRow
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.time.Instant

class NotiLogRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    /**
     * 스키마에 맞춘 collectionGroup 쿼리:
     * - whereEqualTo("ownerUid", uid)
     * - whereEqualTo("event", "ALERT")
     * - orderBy("ts_ms")
     */
    fun observeAlertLogs(limit: Long = 200) = callbackFlow<List<NotiLogRow>> {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(emptyList()); close(); return@callbackFlow
        }

        val q = db.collectionGroup("records")
            .whereEqualTo("ownerUid", uid)   // 새/마이그레이션된 데이터 대상
            .whereEqualTo("event", "ALERT")  // 필드명: event
            .orderBy("ts_ms")                // 정렬 필드: ts_ms (숫자)
            .limitToLast(limit)

        val reg: ListenerRegistration = q.addSnapshotListener { snap, err ->
            if (err != null || snap == null) { trySend(emptyList()); return@addSnapshotListener }
            val rows = snap.documents.mapNotNull { d ->
                val iso = d.getString("host_time_iso") ?: return@mapNotNull null
                val ts  = (d.get("ts_ms") as? Number)?.toLong() ?: return@mapNotNull null
                NotiLogRow(
                    id = d.id,
                    timestamp = Instant.ofEpochMilli(ts),
                    hostIso = iso,
                    reasons = (d.get("reasons") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                    ampRatio = (d.get("AmpRatio") as? Number)?.toDouble(),
                    padMs    = (d.get("PAD_ms")   as? Number)?.toDouble(),
                    dSutMs   = (d.get("dSUT_ms")  as? Number)?.toDouble()
                )
            }.sortedByDescending { it.timestamp }

            trySend(rows)
        }
        awaitClose { reg.remove() }
    }
}
