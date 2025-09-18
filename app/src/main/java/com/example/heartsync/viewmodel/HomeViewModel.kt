package com.example.heartsync.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.heartsync.data.remote.PpgPoint
import com.example.heartsync.data.remote.PpgRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.example.heartsync.viewmodel.HomeViewModel
import com.example.heartsync.viewmodel.HomeVmFactory

class HomeViewModel(
    private val repo: PpgRepository
) : ViewModel() {

    private val _today = MutableStateFlow<List<PpgPoint>>(emptyList())
    val today: StateFlow<List<PpgPoint>> = _today

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

    init {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        _isLoggedIn.value = uid != null

        if (uid != null) {
            viewModelScope.launch {
                // ✅ “오늘 날짜” 고정 대신, 최신 세션 날짜 자동 감지
                repo.observeLatestDayPpg(uid).collectLatest { list ->
                    _today.value = list
                }
            }
        }
    }
}

class HomeVmFactory(
    private val repo: PpgRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HomeViewModel(repo) as T
    }
}
