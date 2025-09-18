package com.example.heartsync.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun StatusCard(
    icon: String,           // "success" or "error"
    title: String,
    buttonText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,                          // ← 실제로 받기
    contentPadding: PaddingValues = PaddingValues(16.dp),   // ← 내부 패딩 조절
    compact: Boolean = false                                // ← 컴팩트 모드(선택)
) {
    val iconSize = if (compact) 20.dp else 32.dp
    val vSpace   = if (compact) 8.dp  else 12.dp

    Surface(
        modifier = modifier,                                 // ← ★ 높이/폭 제어점
        shape = MaterialTheme.shapes.large,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),                   // ← ★ 내부 패딩 적용
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ✅ / ❌ 아이콘
            if (icon == "success") {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(iconSize)
                )
            } else {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    tint = Color(0xFFC62828),
                    modifier = Modifier.size(iconSize)
                )
            }

            Spacer(Modifier.height(vSpace))
            Text(
                title,
                style = if (compact) MaterialTheme.typography.bodyMedium
                else MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(vSpace))

            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(0.7f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                contentPadding = if (compact) PaddingValues(vertical = 6.dp, horizontal = 10.dp)
                else ButtonDefaults.ContentPadding
            ) {
                Text(buttonText)
            }
        }
    }
}
