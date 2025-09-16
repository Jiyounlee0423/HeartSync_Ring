// app/src/main/java/com/example/HeartSync/data/remote/PpgRepository.kt
package com.example.heartsync.data.remote

import android.util.Log
import com.example.heartsync.data.model.PpgEvent
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.tasks.await

class PpgRepository(
    private val db: FirebaseFirestore
) {

    private fun col(userId: String, sessionId: String) =
        db.collection("ppg_events")
            .document(userId)
            .collection("sessions")
            .document(sessionId)
            .collection("records")

    suspend fun uploadRecord(userId: String, sessionId: String, ev: PpgEvent): String {

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
            // 디버깅용 간단 요약 로그
            android.util.Log.d("PpgRepo",
                "write try: $path  event=${ev.event} ts_ms=${ev.ts_ms} " +
                        "AmpRatio=${ev.AmpRatio} leftAmp=${ev.ampL} rightAmp=${ev.ampR}"
            )

            val ref = col(userId, sessionId).document()
            ref.set(map).await()

            // 서버에서 강제 읽기 (오프라인/퍼미션 문제면 여기서 예외)
            val snap = ref.get(Source.SERVER).await()
            Log.d("PpgRepo", "server read ok: exists=${snap.exists()}")

            android.util.Log.d("PpgRepo", "write OK: $path/${ref.id}")
            ref.id
        } catch (t: Throwable) {
            android.util.Log.e("PpgRepo", "write FAIL: $path", t)
            throw t
        }
    }

    /**
     * 세션 레코드 실시간 구독 (UI에서 바로 씀)
     * orderBy는 server_ts(서버시간) 기준, 최신 N개
     */
    fun observeRecent(
        userId: String,
        sessionId: String,
        limit: Long = 200
    ) = callbackFlow<List<PpgEvent>> {
        val qs = col(userId, sessionId)
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

                        smoothed_left = (d.get("smoothed_left") as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() },
                        smoothed_right = (d.get("smoothed_right") as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() },
                    )
                } catch (_: Exception) {
                    null
                }
            }
            trySend(items)
        }
        awaitClose { reg.remove() }
    }

    companion object {
        /**
         * 실시간 스무딩 샘플 스트림 (좌/우 한 점씩)
         * - 최신 샘플을 가볍게 흘려보내기 위한 허브
         * - UI: PpgRepository.smoothedFlow.collect { (l, r) -> ... }
         * - Service/BLE: PpgRepository.emitSmoothed(l, r)
         */
        private val _smoothedFlow =
            MutableSharedFlow<Pair<Double, Double>>(replay = 0, extraBufferCapacity = 128)

        val smoothedFlow: SharedFlow<Pair<Double, Double>> = _smoothedFlow.asSharedFlow()

        /**
         * 생산자(서비스/블루투스)에서 호출: 스무딩된 좌/우 값을 방출
         * Number를 받도록 해서 Float/Double 둘 다 허용
         */
        fun emitSmoothed(left: Number, right: Number) {
            _smoothedFlow.tryEmit(left.toDouble() to right.toDouble())
        }
    }

}
