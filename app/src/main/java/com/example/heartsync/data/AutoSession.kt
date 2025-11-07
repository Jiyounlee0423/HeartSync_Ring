package com.example.heartsync.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.atomic.AtomicBoolean

data class PpgMetric(
    val ts: Long,
    val side: String,           // "L" | "R"
    val pwtt_ms: Double?,
    val auspr: Double?,
    val hsi: Double?,
    val amp_ratio_norm: Double?,
    val dir_amp: String?        // "L"|"R"|null
)

/**
 * 신호가 들어오면 즉시 저장할 수 있도록
 * - 첫 저장 시 자동으로 세션 생성 (lazy)
 * - 일정 시간(기본 5분) 이상 기록 없으면 새 세션으로 자동 롤오버
 */
object AutoSession {
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    @Volatile private var sid: String? = null
    @Volatile private var lastWriteMs: Long = 0L
    private val creating = AtomicBoolean(false)

    // 유휴 시간 기준(밀리초). 이 시간이 지나면 새 세션 시작
    var idleRolloverMs: Long = 5 * 60 * 1000L

    /** 외부에서 지표가 나오면 이 함수만 호출하면 됨 */
    fun save(m: PpgMetric, device: String = "R02") {
        val uid = auth.currentUser?.uid
        if (uid == null) { Log.e("FS","AutoSession.save: not signed in"); return }

        val now = System.currentTimeMillis()
        val needNew = sid == null || (now - lastWriteMs) > idleRolloverMs
        if (needNew) {
            ensureSession(uid, device) { doSave(uid, m) }
        } else {
            doSave(uid, m)
        }
    }

    private fun ensureSession(uid: String, device: String, after: () -> Unit) {
        if (sid != null) { after(); return }
        if (!creating.compareAndSet(false, true)) { // 이미 생성중
            after(); return
        }
        val newSid = System.currentTimeMillis().toString()
        val meta = mapOf(
            "created_at" to FieldValue.serverTimestamp(),
            "title" to "AutoStream",
            "device" to device,
            "started_by" to "auto"
        )
        db.collection("ppg_events").document(uid)
            .collection("sessions").document(newSid)
            .set(meta)
            .addOnSuccessListener {
                sid = newSid
                Log.d("FS","AutoSession started sid=$newSid")
                creating.set(false)
                after()
            }
            .addOnFailureListener { e ->
                Log.e("FS","AutoSession start fail", e)
                creating.set(false)
            }
    }

    private fun doSave(uid: String, m: PpgMetric) {
        val curSid = sid ?: return
        val col = db.collection("ppg_events").document(uid)
            .collection("sessions").document(curSid)
            .collection("records")

        val data = hashMapOf(
            "ts_client" to m.ts,
            "ts_server" to FieldValue.serverTimestamp(),
            "side" to m.side,
            "pwtt_ms" to m.pwtt_ms,
            "auspr" to m.auspr,
            "hsi" to m.hsi,
            "amp_ratio_norm" to m.amp_ratio_norm,
            "dir_amp" to m.dir_amp
        )
        col.add(data)
            .addOnSuccessListener {
                lastWriteMs = System.currentTimeMillis()
                Log.d("FS","save ok id=${it.id}")
            }
            .addOnFailureListener { e -> Log.e("FS","save fail", e) }
    }

    /** 필요 시 수동 종료 */
    fun endSession() { sid = null; lastWriteMs = 0L }
}
