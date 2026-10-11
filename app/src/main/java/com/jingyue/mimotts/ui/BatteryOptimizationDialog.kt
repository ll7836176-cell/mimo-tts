package com.jingyue.mimotts.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun BatteryOptimizationDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("允许后台运行") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "阅读器朗读时，MiMo TTS 需要在后台保持运行。当前系统电池管理可能会在你打开其他应用后结束 MiMo 服务，导致没有声音。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "建议允许 MiMo TTS 忽略电池优化；在华为系统中如果看到“后台运行”或“电池管理”，请选择允许/不限制。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text("去设置")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("以后再说")
            }
        },
    )
}
