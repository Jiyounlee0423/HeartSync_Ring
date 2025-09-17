// DataBizViewModel.kt
package com.example.heartsync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.tasks.await

data class PpgPoint(
    val t: Long,      // ms
    val left: Double, // smooted_left
    val right: Double // smooted_right
)

class DataBizViewModel(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) : ViewModel() {

    private val _points = MutableStateFlow<List<PpgPoint>>(emptyList())
    val points: StateFlow<List<PpgPoint>> = _points

    private var sessionListReg: ListenerRegistration? = null
    private val recordRegs = mutableMapOf<String, ListenerRegistration>() // key: sessionId
    private val buffer = mutableListOf<PpgPoint>()

    /**
     * 같은 날짜(Asia/Seoul)의 모든 세션을 찾아 각 세션의 records를 실시간 구독해서 합친다.
     *
     * @param deviceId Firestore 경로의 디바이스 문서ID (예: "fpb8XE0z2ifrQJGV4liKw31grQR2")
     * @param date 날짜(기본=오늘, Asia/Seoul)
     * @param tsField records의 Timestamp 필드명 (예: "timestamp" or "ts")
     * @param leftField "smooted_left" 필드명
     * @param rightField "smooted_right" 필드명
     */
    fun startListenSameDateSessions(
        deviceId: String,
        date: LocalDate = LocalDate.now(ZoneId.of("Asia/Seoul")),
        tsField: String = "timestamp",
        leftField: String = "smooted_left",
        rightField: String = "smooted_right",
    ) {
        stopAll()

        val ymd = date.format(DateTimeFormatter.BASIC_ISO_DATE) // YYYYMMDD
        // 세션 문서ID가 "S_YYYYMMDD_HHMMSS" 형식이므로 prefix 범위 쿼리
        val startPrefix = "S_${ymd}_"
        val endPrefix   = "S_${ymd}_\uf8ff" // 문자열 prefix 상한

        val sessionsCol = db.collection("ppg_events")
            .document(deviceId)
            .collection("sessions")

        // 문서ID prefix 범위 + 정렬
        sessionListReg = sessionsCol
            .whereGreaterThanOrEqualTo(FieldPath.documentId(), startPrefix)
            .whereLessThanOrEqualTo(FieldPath.documentId(), endPrefix)
            .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) return@addSnapshotListener

                // 현재 등록된 리스너와 비교하여 추가/제거
                val sessionIds = snap?.documents?.map { it.id } ?: emptyList()

                // 1) 제거해야 할 세션 리스너 해제
                val toRemove = recordRegs.keys - sessionIds.toSet()
                toRemove.forEach { sid ->
                    recordRegs.remove(sid)?.remove()
                }

                // 2) 새로 추가할 세션에 리스너 등록
                val toAdd = sessionIds - recordRegs.keys
                toAdd.forEach { sid ->
                    val reg = sessionsCol.document(sid)
                        .collection("records")
                        .orderBy(tsField, Query.Direction.ASCENDING)
                        .addSnapshotListener { rsnap, rerr ->
                            if (rerr != null) return@addSnapshotListener

                            // 해당 세션의 포인트만 추출
                            val newPts = rsnap?.documents?.mapNotNull { d ->
                                val ts = d.getTimestamp(tsField) ?: d.getDate(tsField)?.let { Timestamp(it) }
                                val l = d.getDouble(leftField) ?: d.getLong(leftField)?.toDouble()
                                val r = d.getDouble(rightField) ?: d.getLong(rightField)?.toDouble()
                                if (ts == null || l == null || r == null) return@mapNotNull null
                                PpgPoint(ts.toDate().time, l, r)
                            } ?: emptyList()

                            // 버퍼를 재구성: 모든 세션의 스냅샷 콜백이 섞여오므로
                            viewModelScope.launch {
                                // 세션별로 따로 들고 있다가 합치는 방법도 가능하지만,
                                // 간단히 전체를 다시 긁어 모아 정렬해서 반영
                                rebuildAllPoints(
                                    sessionsCol = sessionsCol,
                                    sessionIds = (recordRegs.keys + sid).toList(),
                                    tsField = tsField,
                                    leftField = leftField,
                                    rightField = rightField
                                )
                            }
                        }
                    recordRegs[sid] = reg
                }

                // 첫 로딩 시점에도 전체 재구성
                viewModelScope.launch {
                    rebuildAllPoints(
                        sessionsCol = sessionsCol,
                        sessionIds = sessionIds,
                        tsField = tsField,
                        leftField = leftField,
                        rightField = rightField
                    )
                }
            }
    }

    private suspend fun rebuildAllPoints(
        sessionsCol: com.google.firebase.firestore.CollectionReference,
        sessionIds: List<String>,
        tsField: String,
        leftField: String,
        rightField: String
    ) {
        // 합본 만들기 (읽기 비용을 고려하면, 각 세션 콜백에서 증분 병합하는 로직으로 바꿀 수 있음)
        val all = mutableListOf<PpgPoint>()
        sessionIds.forEach { sid ->
            val shot = sessionsCol.document(sid)
                .collection("records")
                .orderBy(tsField, Query.Direction.ASCENDING)
                .get()
                .await()
            shot.documents.forEach { d ->
                val ts = d.getTimestamp(tsField) ?: d.getDate(tsField)?.let { Timestamp(it) }
                val l = d.getDouble(leftField) ?: d.getLong(leftField)?.toDouble()
                val r = d.getDouble(rightField) ?: d.getLong(rightField)?.toDouble()
                if (ts != null && l != null && r != null) {
                    all += PpgPoint(ts.toDate().time, l, r)
                }
            }
        }
        all.sortBy { it.t }
        buffer.clear()
        buffer.addAll(all)
        _points.emit(buffer.toList())
    }

    fun stopAll() {
        sessionListReg?.remove()
        sessionListReg = null
        recordRegs.values.forEach { it.remove() }
        recordRegs.clear()
        buffer.clear()
        viewModelScope.launch { _points.emit(emptyList()) }
    }

    override fun onCleared() {
        super.onCleared()
        stopAll()
    }
}
