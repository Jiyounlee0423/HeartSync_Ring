package com.example.heartsync.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heartsync.data.remote.PpgRepository
import com.example.heartsync.ui.model.MetricStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.util.Locale
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.example.heartsync.ui.model.DayMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job


/**
 * 날짜별 요약 집계 전용 ViewModel
 * - observeDayRecords로 들어오는 대량 레코드를 '러닝 통계'로 요약해 O(1) 메모리로 처리
 * - event == STAT/ALERT 필터링은 Repository에서 client-side로 처리됨
 */
class DataBizViewModel(
    private val repo: PpgRepository = PpgRepository.default()
) : ViewModel() {

    private var fullJob: Job? = null

    private companion object {
        private const val TAG = "DataViz"
    }
    // 로딩/카운트 상태 (클래스 필드로)
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastFullCount = MutableStateFlow(0)
    val lastFullCount: StateFlow<Int> = _lastFullCount.asStateFlow()


    // UI에서 상태표시 용
    private val _debugStatus = MutableStateFlow("idle")
    val debugStatus: StateFlow<String> = _debugStatus.asStateFlow()

    private val _dayMetrics = MutableStateFlow<DayMetrics?>(null)
    val dayMetrics: StateFlow<DayMetrics?> = _dayMetrics.asStateFlow()

    private var metricsStopper: (() -> Unit)? = null

    /**
     * 버튼/사용자 트리거로 호출: 페이지네이션으로 끝까지 읽어 "요약"을 계산
     * - 실시간 리스너와 별개로 동작 (리스너는 그대로 두고, 필요 시 이걸로 정확 요약)
     */
    // 기존 함수 교체
    fun refreshDaySummaryPaged(
        uid: String,
        date: LocalDate,
        zone: ZoneId = ZoneId.of("Asia/Seoul"),
        pageSize: Long = 3000L,
        force: Boolean = false              // ← 추가
    ) {
        if (_isLoading.value && !force) return
        if (force) fullJob?.cancel()        // ← 강제 시 이전 작업 취소

        val TAG = "DataViz"
        fullJob = viewModelScope.launch(Dispatchers.Default) {
            _isLoading.value = true
            try {
                val amps = RunningStat(); val pads = RunningStat(); val dSuts = RunningStat()
                var cnt = 0

                repo.foldDayRecordsPaged(
                    uid = uid,
                    date = date,
                    zone = zone,
                    pageSize = pageSize,
                    eventsFilter = setOf("STAT","ALERT")
                ) { m ->
                    cnt++
                    getNum(m, "AmpRatio","ampratio","Amp_Ratio","ARatio")?.let { amps.add(it) }
                    getNum(m, "PAD_ms","PAD","pad","padMs")?.let { pads.add(it) }
                    getNum(m, "dSUT_ms","dSUT","dSut","dSutMs","delta_sut_ms","DeltaSUT","metrics.dSUT_ms")
                        ?.let { dSuts.add(it) }
                }

                withContext(Dispatchers.Main) {
                    _lastFullCount.value = cnt
                    _dayMetrics.value = DayMetrics(
                        ampRatio = amps.toMetricStat(),
                        padMs    = pads.toMetricStat(),
                        dSutMs   = dSuts.toMetricStat()
                    )
                }
            } catch (_: CancellationException) {
                // force 취소 시 조용히 무시
            } catch (t: Throwable) {
                Log.e(TAG, "refreshDaySummaryPaged ERROR", t)
                withContext(Dispatchers.Main) { _lastFullCount.value = 0 }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 날짜별 요약 리슨 시작
     * - 필드명은 기본값을 주고, 실제 문서가 다르면 getNum() alias에서 보정
     */
    fun startListenDayMetrics(
        uid: String,
        date: LocalDate,
        ampField: String = "AmpRatio",
        padField: String = "PAD_ms",
        dSutField: String = "dSUT_ms",
        zone: ZoneId = ZoneId.of("Asia/Seoul")
    ) {
        metricsStopper?.invoke()
        metricsStopper = null

        val job = viewModelScope.launch {
            _debugStatus.value = "listening($uid @ $date)"
            val prefix = "S_${date.format(DateTimeFormatter.BASIC_ISO_DATE)}"
            Log.d(TAG, "startListenDayMetrics uid=$uid date=$date prefix=$prefix")

            try {
                repo.observeDayRecords(uid, date, zone)
                    // 백프레셔/프리즈 방지
                    .buffer(capacity = Channel.BUFFERED)
                    .conflate()
                    .sample(250L) // UI 업데이트 최대 4Hz
                    .collectLatest { rows ->
                        // --- 진단: 어떤 키들이 얼마나 나오는지 카운트 ---
                        val keyCount = mutableMapOf<String, Int>()
                        val dsutHitKeys = mutableMapOf<String, Int>()  // dSUT가 잡힌 실제 키 이름
                        val peekN = rows.take(300) // 너무 많으면 300개만 본다
                        peekN.forEach { m ->
                            // 키 카운트(1depth + nested 1단계)
                            m.keys.forEach { k -> keyCount[k] = (keyCount[k] ?: 0) + 1 }
                            m.values.filterIsInstance<Map<*, *>>().forEach { sub ->
                                sub.keys.filterIsInstance<String>().forEach { k2 ->
                                    keyCount["(nested).$k2"] = (keyCount["(nested).$k2"] ?: 0) + 1
                                }
                            }
                            // dSUT 추출이 어디서 성공하는지 체크
                            val found = getNum(
                                m,
                                "dSUT_ms", "dSUT", "dSut", "dSutMs", "delta_sut_ms", "DeltaSUT", "metrics.dSUT_ms"
                            )
                            if (found != null) {
                                // 어떤 키 이름으로 맞았는지 대충 추정(정확 매칭 우선)
                                listOf("dSUT_ms","dSUT","dSut","dSutMs","delta_sut_ms","DeltaSUT","metrics.dSUT_ms").forEach { k ->
                                    if ((m[k] != null) ||
                                        (k.contains(".") && k.split('.').let { p -> (m[p.first()] as? Map<*, *>)?.get(p.last()) != null })
                                    ) {
                                        dsutHitKeys[k] = (dsutHitKeys[k] ?: 0) + 1
                                    }
                                }
                            }
                        }
                        if (rows.isNotEmpty()) {
                            Log.d("DataViz", "keys(top): " +
                                    keyCount.entries.sortedByDescending { it.value }.take(10)
                                        .joinToString { "${it.key}:${it.value}" }
                            )
                            Log.d("DataViz", "dSUT hit-by-key: " +
                                    dsutHitKeys.entries.sortedByDescending { it.value }.joinToString()
                            )
                        }
                        Log.d(TAG, "collect rows size=${rows.size}")

                        // 이벤트 분포(검증용)
                        val cntStat = rows.count { ((it["event"] ?: it["eventType"])?.toString() ?: "").uppercase() == "STAT" }
                        val cntAlert = rows.count { ((it["event"] ?: it["eventType"])?.toString() ?: "").uppercase() == "ALERT" }
                        Log.d(TAG, "event dist: STAT=$cntStat, ALERT=$cntAlert")

                        // 러닝 통계 누적 (리스트로 전개하지 않음)
                        val amps = RunningStat()
                        val pads = RunningStat()
                        val dSuts = RunningStat()

                        rows.asSequence().forEach { m ->
                            getNum(m, "AmpRatio", "ampratio", "Amp_Ratio", "ARatio")?.let { amps.add(it) }
                            getNum(m, "PAD_ms", "PAD", "pad", "padMs")?.let { pads.add(it) }
                            getNum(
                                m,
                                "dSUT_ms", "dSUT", "dSut", "dSutMs", "delta_sut_ms", "DeltaSUT", "metrics.dSUT_ms"
                            )?.let { dSuts.add(it) }
                        }

                        _dayMetrics.value = DayMetrics(
                            ampRatio = amps.toMetricStat(),
                            padMs    = pads.toMetricStat(),
                            dSutMs   = dSuts.toMetricStat()
                        )
                    }
            } catch (ce: CancellationException) {
                // 정상 취소
            } catch (t: Throwable) {
                Log.e(TAG, "startListenDayMetrics ERROR", t)
                _debugStatus.value = "error: ${t.message}"
                _dayMetrics.value = DayMetrics(
                    ampRatio = MetricStat(0,0.0,0.0,0.0),
                    padMs    = MetricStat(0,0.0,0.0,0.0),
                    dSutMs   = MetricStat(0,0.0,0.0,0.0),
                )
            }
        }
        metricsStopper = { job.cancel() }
    }

    fun stopAll() {
        metricsStopper?.invoke()
        metricsStopper = null
    }
}

/* ======================= 유틸들 (이 파일 안에 포함) ======================= */

/** 여러 이름으로 섞여있는 숫자 필드 안전 추출 */
// 기존 getNum(...) 을 아래로 교체
private fun getNum(
    map: Map<String, Any?>,
    primary: String,
    vararg aliases: String
): Double? {
    // 0) dot-path 지원: "metrics.dSUT_ms" 형태 지원
    fun getByPath(path: String, m: Map<String, Any?>): Any? {
        var cur: Any? = m
        path.split('.').forEach { seg ->
            cur = when (cur) {
                is Map<*, *> -> (cur as Map<*, *>)[seg]
                else -> return null
            }
        }
        return cur
    }

    // 1) primary/alias 그대로 시도 (숫자/문자열)
    fun asDouble(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
    (asDouble(map[primary]) ?: asDouble(getByPath(primary, map)))?.let { return it }
    for (a in aliases) {
        (asDouble(map[a]) ?: asDouble(getByPath(a, map)))?.let { return it }
    }

    // 2) 대소문자 무시 + underscore/dash 제거 + 유사키 매칭
    //    예: "dSUT_ms", "d_sut_ms", "DeltaSUT" -> "dsutms"/"deltasut"
    val norm = { s: String -> s.lowercase().filter { it.isLetterOrDigit() } }
    val want = buildList {
        add(norm(primary))
        aliases.forEach { add(norm(it)) }
    }.toSet()

    // map 의 모든 키를 스캔
    for ((k, v) in map) {
        val nk = norm(k)
        if (nk in want) return asDouble(v)
        // nested map까지 한 번 더 들어가 봄
        if (v is Map<*, *>) {
            for ((k2, v2) in v) {
                if (k2 is String && norm(k2) in want) return asDouble(v2)
            }
        }
    }
    return null
}

/** O(1) 메모리 요약 통계 */
private data class RunningStat(
    var n: Int = 0,
    var mean: Double = 0.0,
    var min: Double = Double.POSITIVE_INFINITY,
    var max: Double = Double.NEGATIVE_INFINITY
) {
    fun add(x: Double) {
        n += 1
        val d = x - mean
        mean += d / n
        if (x < min) min = x
        if (x > max) max = x
    }
}

private fun RunningStat.toMetricStat(): MetricStat =
    if (n == 0) MetricStat(0, 0.0, 0.0, 0.0) else MetricStat(n, mean, min, max)
