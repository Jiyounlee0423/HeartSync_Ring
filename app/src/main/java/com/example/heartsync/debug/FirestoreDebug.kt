// app/src/main/java/com/example/heartsync/debug/FirestoreDebug.kt
package com.example.heartsync.debug

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.tasks.await

object FirestoreDebug {
    private const val TAG = "FirestoreDebug"

    /**
     * A. 현재 로그인 사용자의 ALERT 레코드 "최근 N개" 한 번만 가져와 로그로 출력
     *   - ownerUid, event, ts_ms, host_time_iso 등을 확인
     */
    fun printMyRecentAlertsOnce(
        db: FirebaseFirestore = FirebaseFirestore.getInstance(),
        auth: FirebaseAuth = FirebaseAuth.getInstance(),
        limit: Long = 10L
    ) {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Log.w(TAG, "No currentUser -> cannot query")
            return
        }

        db.collectionGroup("records")
            .whereEqualTo("ownerUid", uid)     // 새로 저장된 레코드에만 존재
            .whereEqualTo("event", "ALERT")
            .orderBy("ts_ms")
            .limitToLast(limit)
            .get()
            .addOnSuccessListener { snap ->
                Log.d(TAG, "----- MyRecentAlerts (count=${snap.size()}) -----")
                snap.documents.forEach { d ->
                    Log.d(TAG, "id=${d.id}, path=${d.reference.path}, data=${d.data}")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "printMyRecentAlertsOnce() failed", e)
            }
    }

    /**
     * B. 내 특정 세션에서 ALERT 레코드 최근 N개 가져와 확인 (ownerUid 없어도 됨)
     *   - 과거 데이터 확인용
     */
    fun printMySessionAlertsOnce(
        sessionId: String,
        db: FirebaseFirestore = FirebaseFirestore.getInstance(),
        auth: FirebaseAuth = FirebaseAuth.getInstance(),
        limit: Long = 10L
    ) {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Log.w(TAG, "No currentUser -> cannot query")
            return
        }

        db.collection("ppg_events")
            .document(uid)
            .collection("sessions")
            .document(sessionId)
            .collection("records")
            .whereEqualTo("event", "ALERT")
            .orderBy("ts_ms")
            .limitToLast(limit)
            .get()
            .addOnSuccessListener { snap ->
                Log.d(TAG, "----- SessionAlerts [$sessionId] (count=${snap.size()}) -----")
                snap.documents.forEach { d ->
                    Log.d(TAG, "id=${d.id}, data=${d.data}")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "printMySessionAlertsOnce() failed", e)
            }
    }

    /**
     * C. 실시간 구독: 내 ALERT 레코드 변경시마다 로그로 출력
     *   - 일시적으로 붙여놓고 동작 확인 후 제거 권장
     */
    fun listenMyAlerts(
        db: FirebaseFirestore = FirebaseFirestore.getInstance(),
        auth: FirebaseAuth = FirebaseAuth.getInstance(),
        limit: Long = 10L
    ): ListenerRegistration? {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Log.w(TAG, "No currentUser -> cannot listen")
            return null
        }

        val q = db.collectionGroup("records")
            .whereEqualTo("ownerUid", uid)
            .whereEqualTo("event", "ALERT")
            .orderBy("ts_ms")
            .limitToLast(limit)

        return q.addSnapshotListener { snap, err ->
            if (err != null) {
                Log.e(TAG, "listenMyAlerts() error", err)
                return@addSnapshotListener
            }
            if (snap == null) {
                Log.w(TAG, "listenMyAlerts() null snapshot")
                return@addSnapshotListener
            }
            Log.d(TAG, "----- listenMyAlerts() update (count=${snap.size()}) -----")
            snap.documents.forEach { d ->
                Log.d(TAG, "id=${d.id}, data=${d.data}")
            }
        }
    }

    /**
     * (옵션) 코루틴 버전: 필요하면 ViewModel/CoroutineScope에서 await()로 호출
     */
    suspend fun printMyRecentAlertsOnceAwait(
        db: FirebaseFirestore = FirebaseFirestore.getInstance(),
        auth: FirebaseAuth = FirebaseAuth.getInstance(),
        limit: Long = 10L
    ) {
        val uid = auth.currentUser?.uid ?: run {
            Log.w(TAG, "No currentUser -> cannot query")
            return
        }
        try {
            val snap = db.collectionGroup("records")
                .whereEqualTo("ownerUid", uid)
                .whereEqualTo("event", "ALERT")
                .orderBy("ts_ms")
                .limitToLast(limit)
                .get()
                .await()

            Log.d(TAG, "----- MyRecentAlertsAwait (count=${snap.size()}) -----")
            for (d in snap.documents) {
                Log.d(TAG, "id=${d.id}, path=${d.reference.path}, data=${d.data}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "printMyRecentAlertsOnceAwait() failed", e)
        }
    }
}
