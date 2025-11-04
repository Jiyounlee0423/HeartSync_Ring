package com.example.heartsync.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothGatt
import android.content.Context
import android.os.Build

object BleHardReset {
    /** 연결 중인 GATT 디바이스를 훑어서 R02/HeartSync 계열이면 즉시 disconnect/close */
    fun run(ctx: Context) {
        val bm = ctx.getSystemService(BluetoothManager::class.java) ?: return
        val connected = bm.getConnectedDevices(BluetoothProfile.GATT) ?: return
        for (dev in connected) {
            val name = dev.name ?: ""
            if (name.startsWith("R02") || name.startsWith("HeartSync")) {
                try {
                    val g = if (Build.VERSION.SDK_INT >= 31)
                        dev.connectGatt(ctx, false, object : android.bluetooth.BluetoothGattCallback(){})
                    else
                        dev.connectGatt(ctx, false, object : android.bluetooth.BluetoothGattCallback(){})
                    // 곧바로 종료 (최소한의 세션 확보 후 정리)
                    try { g.disconnect() } catch (_: Exception) {}
                    try { g.close() } catch (_: Exception) {}
                } catch (_: Throwable) { /* skip */ }
            }
        }
    }
}
