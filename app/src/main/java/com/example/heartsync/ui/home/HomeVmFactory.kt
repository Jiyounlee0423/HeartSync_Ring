// app/src/main/java/com/example/heartsync/ui/home/HomeVmFactory.kt
package com.example.heartsync.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.heartsync.data.remote.PpgRepository
import com.example.heartsync.ui.screens.HomeViewModel

class HomeVmFactory(private val repo: PpgRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HomeViewModel(repo) as T
    }
}
