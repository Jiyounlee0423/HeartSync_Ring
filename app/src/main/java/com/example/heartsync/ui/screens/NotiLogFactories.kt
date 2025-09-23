package com.example.heartsync.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.heartsync.data.NotiLogRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

@Suppress("UNCHECKED_CAST")
fun notiLogViewModelFactory(): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            // Repository는 db만 주입
            val db = FirebaseFirestore.getInstance()
            val repo = NotiLogRepository(db)

            // ViewModel에 uid도 함께 주입
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""

            return NotiLogViewModel(
                repo = repo,
                uid = uid
            ) as T
        }
    }

