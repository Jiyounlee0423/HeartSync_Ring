package com.example.heartsync.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow

private val Context.deviceDataStore: DataStore<Preferences> by preferencesDataStore("device_prefs")

class DevicePrefs(private val ctx: Context) {
    private val KEY_LEFT  = stringPreferencesKey("left_mac")
    private val KEY_RIGHT = stringPreferencesKey("right_mac")

    val leftMac: Flow<String?>  = ctx.deviceDataStore.data.map { it[KEY_LEFT] }
    val rightMac: Flow<String?> = ctx.deviceDataStore.data.map { it[KEY_RIGHT] }

    suspend fun setLeft(mac: String)  { ctx.deviceDataStore.edit { it[KEY_LEFT] = mac } }
    suspend fun setRight(mac: String) { ctx.deviceDataStore.edit { it[KEY_RIGHT] = mac } }
    suspend fun clear() { ctx.deviceDataStore.edit { it.remove(KEY_LEFT); it.remove(KEY_RIGHT) } }
}
