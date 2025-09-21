package com.example.heartsync.ui.screens.model

import java.time.*
import java.time.format.DateTimeFormatter

data class NotiLogRow(
    val id: String,
    val timestamp: Instant,
    val hostIso: String,
    val reasons: List<String>,
    val ampRatio: Double?,
    val padMs: Double?,
    val dSutMs: Double?
) {
    fun localDate(zone: ZoneId): LocalDate =
        LocalDateTime.ofInstant(timestamp, zone).toLocalDate()

    fun localTimeStr(zone: ZoneId): String =
        LocalDateTime.ofInstant(timestamp, zone)
            .toLocalTime()
            .format(DateTimeFormatter.ofPattern("HH:mm"))
}

/** 날짜 헤더는 ViewModel에서 문자열로 포맷해서 넣는다(간단 대응). */
data class NotiLogSection(
    val date: String,            // e.g., "2025-09-21 (일)"
    val rows: List<NotiLogRow>
)
