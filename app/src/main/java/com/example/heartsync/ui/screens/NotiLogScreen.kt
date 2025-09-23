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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotiLogScreen(
    vm: NotiLogViewModel = viewModel(factory = notiLogViewModelFactory())
) {
    val sections by vm.sections.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        val titleSuffix = if (NotiLogViewModel.USE_MOCK) " (Mock)" else ""
        Text("이상 알림 로그$titleSuffix", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        if (sections.isEmpty()) {
            Text("최근 알림이 없습니다.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sections.forEach { section ->
                stickyHeader {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Text(
                            text = section.date,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        )
                    }
                }
                items(section.rows, key = { it.id }) { row ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            val timeStr = row.localTimeStr(java.time.ZoneId.systemDefault())
                            val reasonLine = if (row.reasons.isNotEmpty())
                                row.reasons.joinToString(", ")
                            else "이유 미지정"

                            Text("$timeStr · $reasonLine", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(6.dp))

                            Text(
                                buildString {
                                    append("AmpRatio=")
                                    append(row.ampRatio?.let { "%.2f".format(it) } ?: "-")
                                    append("  ·  PAD=")
                                    append(row.padMs?.let { "%.0f ms".format(it) } ?: "-")
                                    append("  ·  dSUT=")
                                    append(row.dSutMs?.let { "%.0f ms".format(it) } ?: "-")
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
