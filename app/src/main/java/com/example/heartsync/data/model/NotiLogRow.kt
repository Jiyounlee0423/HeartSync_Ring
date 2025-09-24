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
