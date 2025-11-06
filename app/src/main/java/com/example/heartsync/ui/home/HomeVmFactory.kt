// app/src/main/java/com/example/heartsync/ui/home/HomeVmFactory.kt
package com.example.heartsync.ui.home

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.heartsync.viewmodel.DualRingViewModel

class HomeVmFactory(private val app: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DualRingViewModel::class.java)) {
            return DualRingViewModel(app) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
