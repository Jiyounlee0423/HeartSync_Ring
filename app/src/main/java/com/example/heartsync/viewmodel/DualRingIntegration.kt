package com.example.heartsync.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heartsync.ble.DualRingBleClient
import com.example.heartsync.data.remote.PpgRepository
import com.example.heartsync.service.DualRingProcessorBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DualRingIntegration : ViewModel() {
    private var bridge: DualRingProcessorBridge? = null

    fun start(client: DualRingBleClient) {
        if (bridge != null) return
        PpgRepository.default().setSessionId(makeSessionId())
        bridge = DualRingProcessorBridge(PpgRepository.default(), fsHz = 50).also { it.start(client) }
    }

    fun stop() {
        bridge?.stop()
        bridge = null
    }

    private fun makeSessionId(): String {
        val sdf = SimpleDateFormat("S_yyyyMMdd_HHmmss", Locale.US)
        return sdf.format(Date())
    }
}