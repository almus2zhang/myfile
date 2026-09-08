package com.example.myfile.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.myfile.core.AppCandidate

/**
 * 自定义「打开方式」对话框：列出能打开该文件的候选应用。
 * 每个应用一行，右侧「仅此一次」/「始终」按钮。
 * 对齐 CX 浏览器行为：选择由 myfile 自己记录（通过回调返回）。
 */
@Composable
fun OpenWithDialog(
    title: String,
    candidates: List<AppCandidate>,
    onDismiss: () -> Unit,
    onSelect: (AppCandidate, always: Boolean) -> Unit,
    onSystemChooser: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (candidates.isEmpty()) {
                Text("没有找到可打开此文件的应用", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                ) {
                    items(candidates) { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(c, false) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 应用图标
                            val bmp = c.icon?.let {
                                runCatching { it.toBitmap(40, 40).asImageBitmap() }.getOrNull()
                            }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp)
                                )
                            } else {
                                Box(Modifier.size(36.dp))
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    c.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            // 「始终」按钮
                            TextButton(onClick = { onSelect(c, true) }) {
                                Text("始终")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            if (onSystemChooser != null) {
                TextButton(onClick = onSystemChooser) {
                    Text("系统选择器…")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
