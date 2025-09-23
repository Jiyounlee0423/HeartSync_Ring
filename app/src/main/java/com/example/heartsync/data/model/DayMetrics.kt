// app/src/main/java/com/example/heartsync/ui/model/DayMetrics.kt
package com.example.heartsync.ui.model

data class DayMetrics(
    val ampRatio: MetricStat?,  // AmpRatio
    val padMs:    MetricStat?,  // PAD_ms
    val dSutMs:   MetricStat?,  // dSUT_ms
)

data class MetricStat(
    val count: Int,
    val avg: Double,
    val min: Double,
    val max: Double
)
