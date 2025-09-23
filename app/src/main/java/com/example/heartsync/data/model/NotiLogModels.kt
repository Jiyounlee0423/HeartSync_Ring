// app/src/main/java/com/example/heartsync/ui/screens/model/NotiLogModels.kt
package com.example.heartsync.ui.screens.model

import java.time.*
import java.time.format.DateTimeFormatter

data class NotiLogRow(
    val id: String,
    val hostIso: String,              // e.g., "2025-09-23T09:12:00Z" or local ISO
    val reasons: List<String>,
    val ampRatio: Double?,
    val padMs: Double?,
    val dSutMs: Double?,
) {
    fun instantOrNull(): Instant? {
        return try {
            // timezone 정보가 없으면 system zone으로 간주
            val ldt = LocalDateTime.parse(hostIso)
            ldt.atZone(ZoneId.systemDefault()).toInstant()
        } catch (_: Throwable) {
            null
        }
    }

    fun localDate(zone: ZoneId): LocalDate {
        val ins = instantOrNull() ?: Instant.now()
        return ins.atZone(zone).toLocalDate()
    }

    fun localTimeStr(zone: ZoneId): String {
        val ins = instantOrNull() ?: Instant.now()
        val lt = ins.atZone(zone).toLocalTime()
        return lt.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    }
}

data class NotiLogSection(
    val date: String,                 // "yyyy-MM-dd"
    val rows: List<NotiLogRow>
)
