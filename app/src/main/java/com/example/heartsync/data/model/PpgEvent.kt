// app/src/main/java/com/example/HeartSync/data/model/PpgEvent.kt
package com.example.heartsync.data.model

/**
 * 앱 내부 표준 모델 (Firestore 직렬화용으로 map 변환해서 사용)
 */
data class PpgEvent(
    val event: String,                 // "STAT" | "ALERT"
    val host_time_iso: String,         // ISO8601
    val ts_ms: Long,                   // ESP32 부팅 이후 ms

    val alert_type: String? = null,    // e.g., "asymmetry"
    val reasons: List<String>? = null, // ["AmpRatio low","PAD high",...]

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

    val side: String? = null,          // "left" | "right" | "balance"

    val smoothed_left: Double? = null,
    val smoothed_right: Double? = null,
)
