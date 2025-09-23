// app/src/main/java/com/example/heartsync/ui/screens/NotiLogScreen.kt
package com.example.heartsync.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.heartsync.ui.screens.model.NotiLogSection

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotiLogScreen(
    vm: NotiLogViewModel = viewModel(factory = notiLogViewModelFactory())
) {
    val sections by vm.sections.collectAsState()
    val selDate by vm.selectedDate.collectAsState()

    var datePickerOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "이상 알림 로그 (${selDate})",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = { datePickerOpen = true }) {
                Text("날짜 선택")
            }
        }
        Spacer(Modifier.height(12.dp))

        if (sections.isEmpty()) {
            Text("선택한 날짜에 알림이 없습니다.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sections.forEach { section ->
                    stickyHeader {
                        Surface(color = MaterialTheme.colorScheme.surface) {
                            Text(
                                text = section.date,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            )
                        }
                    }
                    items(section.rows, key = { it.id }) { row ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                val timeStr = row.localTimeStr(java.time.ZoneId.systemDefault())
                                val reasons = if (row.reasons.isNotEmpty())
                                    row.reasons.joinToString(", ")
                                else "이유 미지정"

                                Text("$timeStr · $reasons", style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "AmpRatio=${row.ampRatio?.let { "%.2f".format(it) } ?: "-"}  ·  " +
                                            "PAD=${row.padMs?.let { "%.0f ms".format(it) } ?: "-"}  ·  " +
                                            "dSUT=${row.dSutMs?.let { "%.0f ms".format(it) } ?: "-"}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (datePickerOpen) {
        DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    // 선택값 적용
                    // m3 DatePicker는 millis -> LocalDate 변환 필요
                    // 아래 helper 참고
                    // 이 블록 안에서 selectedMillis 읽어서 vm.setDate(...)
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { datePickerOpen = false }) { Text("취소") } }
        ) {
            val state = rememberDatePickerState()
            DatePicker(state = state)
            // 확인 버튼에서:
            // state.selectedDateMillis?.let { vm.setDate(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()) }
        }
    }
}
