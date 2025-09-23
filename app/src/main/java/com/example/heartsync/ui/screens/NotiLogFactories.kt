// app/src/main/java/com/example/heartsync/ui/screens/NotiLogFactories.kt
package com.example.heartsync.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

@Suppress("UNCHECKED_CAST")
fun notiLogViewModelFactory(): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repo = NotiLogRepository(
                db = FirebaseFirestore.getInstance(),
                auth = FirebaseAuth.getInstance()
            )
            return NotiLogViewModel(repo) as T
        }
    }
