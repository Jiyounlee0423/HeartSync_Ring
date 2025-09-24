package com.example.heartsync.ui.screens.model

import java.time.*
import java.time.format.DateTimeFormatter

data class NotiLogRow(
    val id: String,
    val epochMs: Long? = null,
    val hostIso: String? = null,

    val eventType: String? = null,   // "ALERT" 등
    val alertType: String? = null,   // ex) "asymmetry"

    val side: String? = null,        // "left" | "right" | "uncertain"
    val reasons: List<String>? = null,

    val ampRatio: Double? = null,
    val padMs: Double? = null,
    val dSutMs: Double? = null
)

private val KST: ZoneId = ZoneId.of("Asia/Seoul")
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

fun NotiLogRow.instantOrNull(): Instant? =
    epochMs?.let { Instant.ofEpochMilli(it) }
        ?: runCatching { Instant.parse(hostIso) }.getOrNull()

fun NotiLogRow.localDate(zone: ZoneId = KST): LocalDate =
    (instantOrNull() ?: Instant.EPOCH).atZone(zone).toLocalDate()

fun NotiLogRow.localTimeStr(zone: ZoneId = KST): String =
    (instantOrNull() ?: Instant.EPOCH).atZone(zone).toLocalTime().format(TIME_FMT)

private fun translateReason(reason: String): String = when (reason) {
    "AmpRatio low" -> "진폭 비율 낮음"
    "PAD high"     -> "맥파 지연(PAD) 증가"
    "dSUT high"    -> "상승시간 차이(dSUT) 증가"
    else           -> reason // 미정의 키는 원문 그대로
}
