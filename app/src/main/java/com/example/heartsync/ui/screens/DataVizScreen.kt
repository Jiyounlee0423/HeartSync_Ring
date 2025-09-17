// app/src/main/java/com/example/heartsync/ui/screens/DataVizScreen.kt
package com.example.heartsync.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.max
import kotlin.math.min

@Composable
fun DataVizScreen() {
    // TODO: 나중에 실제 그래프/리스트로 교체
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("센서 데이터 시각화", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text("그래프/리스트 영역 (임시)")
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { /* TODO: 더미 버튼 */ }) {
                Text("샘플 데이터를 불러옵니다 (임시)")
            }
        }
    }
}
