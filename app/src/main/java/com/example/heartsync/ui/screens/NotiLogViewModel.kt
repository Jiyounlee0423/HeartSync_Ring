package com.example.heartsync.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heartsync.data.NotiLogRepository
import com.example.heartsync.ui.screens.model.NotiLogRow
import com.example.heartsync.ui.screens.model.NotiLogSection
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class NotiLogViewModel(
    private val repo: NotiLogRepository,
    private val zone: ZoneId = ZoneId.systemDefault()
) : ViewModel() {

    // 모의 데이터(콘솔 잠김 등일 때 UI 확인용)
    companion object {
        const val USE_MOCK: Boolean = true   // ← 필요 시 false로 바꾸면 Firestore로 전환
    }

    private val df = DateTimeFormatter.ofPattern("yyyy-MM-dd (E)")

    private val _rows = MutableStateFlow<List<NotiLogRow>>(emptyList())

    val sections: StateFlow<List<NotiLogSection>> =
        _rows.map { rows ->
            rows.groupBy { it.localDate(zone) }
                .toSortedMap(compareByDescending { it })
                .map { (date, list) ->
                    NotiLogSection(
                        date = date.format(df), // LocalDate → String
                        rows = list.sortedByDescending { it.timestamp }
                    )
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (USE_MOCK) {
            // 모의 데이터 주입
            viewModelScope.launch {
                _rows.value = NotiLogMockData.sampleRows(zone)
            }
        } else {
            // Firestore
            viewModelScope.launch {
                repo.observeAlertLogs(limit = 300).collect { _rows.value = it }
            }
        }
    }
}
// ===== Mock 데이터 유틸 =====
// ===== Mock 데이터 유틸 =====
private object NotiLogMockData {

    // ── 임계값(프로젝트 기준에 맞춰 조정 가능) ─────────────────────────────
    private const val AMP_RATIO_LOW_TH   = 0.85      // AmpRatio < 0.85  → AMP_LOW
    private const val PAD_HIGH_TH_MS     = 200.0     // PAD > 200 ms      → PAD_HIGH
    private const val DSUT_HIGH_TH_MS    = 20.0      // |ΔSUT| > 20 ms    → DSUT_HIGH
    private const val SIDE_IMB_STRICT_AR = 0.80      // AmpRatio < 0.80   → SIDE_IMBALANCE(강)

    // 타이틀(요청한 문구 그대로)
    private const val T_AMP_LOW        = "좌·우 진폭 비율 저하 (AmpRatio Low)"
    private const val T_PAD_HIGH       = "양팔 Foot 지연 증가 (PAD High)"
    private const val T_DSUT_HIGH      = "수축기 상승시간 차 증가 (ΔSUT High)"
    private const val T_SIDE_IMBALANCE = "양팔 신호 불균형 감지"
    private const val T_UNKNOWN        = "이상 징후 감지"

    private fun parseLocalToInstant(localIso: String, zone: java.time.ZoneId): java.time.Instant {
        val ldt = java.time.LocalDateTime.parse(
            localIso,
            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
        )
        return ldt.atZone(zone).toInstant()
    }

    /** 값들로부터 표시용 reason 타이틀을 자동 생성 */
    private fun buildReasons(
        ampRatio: Double?,
        padMs: Double?,
        dSutMs: Double?
    ): List<String> {
        val out = mutableListOf<String>()

        if (ampRatio != null && ampRatio < AMP_RATIO_LOW_TH) out += T_AMP_LOW
        if (padMs != null && padMs > PAD_HIGH_TH_MS) out += T_PAD_HIGH
        if (dSutMs != null && kotlin.math.abs(dSutMs) > DSUT_HIGH_TH_MS) out += T_DSUT_HIGH

        // 강한 불균형 or 경고 2개 이상이면 종합 플래그 추가
        val strongImb = (ampRatio != null && ampRatio < SIDE_IMB_STRICT_AR)
        if (strongImb || out.size >= 2) out += T_SIDE_IMBALANCE

        if (out.isEmpty()) out += T_UNKNOWN
        return out.distinct()
    }

    fun sampleRows(zone: java.time.ZoneId): List<com.example.heartsync.ui.screens.model.NotiLogRow> =
        listOf(
            com.example.heartsync.ui.screens.model.NotiLogRow(
                id = "r1",
                timestamp = parseLocalToInstant("2025-09-21T09:12:00", zone),
                hostIso = "2025-09-21T09:12:00",
                reasons = buildReasons(ampRatio = 0.82, padMs = 232.0, dSutMs = 20.0),
                ampRatio = 0.82, padMs = 232.0, dSutMs = 20.0
            ),
            com.example.heartsync.ui.screens.model.NotiLogRow(
                id = "r2",
                timestamp = parseLocalToInstant("2025-09-21T09:07:00", zone),
                hostIso = "2025-09-21T09:07:00",
                reasons = buildReasons(ampRatio = 0.95, padMs = 180.0, dSutMs = -15.0),
                ampRatio = 0.95, padMs = 180.0, dSutMs = -15.0
            ),
            com.example.heartsync.ui.screens.model.NotiLogRow(
                id = "r3",
                timestamp = parseLocalToInstant("2025-09-20T22:14:00", zone),
                hostIso = "2025-09-20T22:14:00",
                reasons = buildReasons(ampRatio = 0.75, padMs = 250.0, dSutMs = 30.0),
                ampRatio = 0.75, padMs = 250.0, dSutMs = 30.0
            )
        )
}

