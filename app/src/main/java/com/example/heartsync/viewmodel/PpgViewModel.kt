// app/src/main/java/com/example/heartsync/viewmodel/PpgViewModel.kt
package com.example.heartsync.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heartsync.data.remote.PpgEvent
import com.example.heartsync.data.remote.PpgRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Firestore 업로드/조회 전용 ViewModel
 * - 이벤트 타입은 반드시 com.example.heartsync.data.remote.PpgEvent 로 통일
 */
class PpgViewModel(
    private val repo: PpgRepository = PpgRepository.instance
) : ViewModel() {

    private val tag = "PpgViewModel"

    /** 현재 세션ID (예: 2025-09-18T12-34-56.789Z_abcd1234) */
    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    /** 업로드/최근기록 UI 표시에 사용할 이벤트 목록 */
    private val _events = MutableStateFlow<List<PpgEvent>>(emptyList())
    val events: StateFlow<List<PpgEvent>> = _events.asStateFlow()

    /** 새 세션 시작 (세션ID 생성 + Repo에도 전달) */
    fun startNewSession(): String {
        val id = newSessionId()
        _sessionId.value = id
        repo.setSessionId(id)
        Log.d(tag, "startNewSession: $id")
        return id
    }

    /** 외부에서 세션을 이어받아 사용하고 싶을 때 */
    fun attachSession(id: String) {
        _sessionId.value = id
        repo.setSessionId(id)
        Log.d(tag, "attachSession: $id")
    }

    /** 세션 해제 */
    fun clearSession() {
        _sessionId.value = null
        repo.setSessionId("")
        Log.d(tag, "clearSession")
    }

    /** remote.PpgEvent 업로드 */
    fun saveEvent(ev: PpgEvent) {
        viewModelScope.launch {
            val auth = FirebaseAuth.getInstance()
            val uid = auth.currentUser?.uid ?: run {
                Log.e(tag, "anonymous sign-in failed (no user)")
                return@launch
            }
            val sid = _sessionId.value ?: startNewSession()

            try {
                repo.uploadRecord(uid, sid, ev) // suspend
                Log.d(tag, "upload OK: uid=$uid / session=$sid")
                // 로컬 리스트에도 추가(간단 미러링)
                _events.update { it + ev }
            } catch (t: Throwable) {
                Log.e(tag, "upload failed", t)
            }
        }
    }

    /** Firestore 최근 N개 구독해서 events 갱신 (서버 타임순) */
    fun observeRecent(limit: Long = 200) {
        viewModelScope.launch {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
            val sid = _sessionId.value ?: return@launch

            repo.observeRecent(uid, sid, limit).collect { list ->
                _events.value = list
            }
        }
    }

    /** 세션ID 생성: ISO-UTC 의 시간 콜론을 '-'로 치환 + 짧은 UUID suffix */
    private fun newSessionId(): String {
        val isoUtc = OffsetDateTime.now(ZoneOffset.UTC).toString().replace(":", "-")
        val suffix = UUID.randomUUID().toString().take(8)
        return "${isoUtc}_$suffix"
    }
}
