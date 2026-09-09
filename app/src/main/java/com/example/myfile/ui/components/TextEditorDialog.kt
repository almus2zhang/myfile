package com.example.myfile.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * 内置文本浏览与编辑器：
 * - 支持流式渐进式加载（实时显示已下载字节数与前置内容，防大文件卡死）
 * - 支持浏览模式与编辑模式切换
 * - 支持自动换行切换
 * - 行号与内容同步滚动
 * - 保存到 WebDAV / 本地文件
 * - 退出未保存二次确认
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorDialog(
    fileName: String,
    filePath: String,
    onLoad: suspend (onProgress: (loadedBytes: Long, totalBytes: Long, partialText: String) -> Unit) -> String,
    onSave: (suspend (newText: String) -> Boolean)?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var textValue by remember { mutableStateOf(TextFieldValue("")) }
    var originalText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var loadedBytes by remember { mutableLongStateOf(0L) }
    var totalBytes by remember { mutableLongStateOf(0L) }
    var isWordWrap by remember { mutableStateOf(true) }
    var isReadOnly by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }

    val isModified = remember(textValue.text, originalText) {
        textValue.text != originalText
    }

    // 启动流式读取
    fun startLoading() {
        isLoading = true
        errorMessage = null
        scope.launch {
            try {
                val full = onLoad { loaded, total, partial ->
                    loadedBytes = loaded
                    totalBytes = total
                    if (isLoading) {
                        textValue = TextFieldValue(partial)
                    }
                }
                textValue = TextFieldValue(full)
                originalText = full
                isLoading = false
            } catch (e: Exception) {
                errorMessage = e.message ?: "读取文件失败"
                isLoading = false
            }
        }
    }

    LaunchedEffect(filePath) {
        startLoading()
    }

    // 保存逻辑
    fun performSave(onSuccess: () -> Unit = {}) {
        if (onSave == null || isSaving) return
        isSaving = true
        scope.launch {
            try {
                val success = onSave(textValue.text)
                if (success) {
                    originalText = textValue.text
                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                    onSuccess()
                } else {
                    Toast.makeText(context, "保存失败，请检查网络或权限", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isSaving = false
            }
        }
    }

    Dialog(
        onDismissRequest = {
            if (isModified) {
                showExitConfirm = true
            } else {
                onDismiss()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部工具栏
                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = fileName,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isModified) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "*",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            }
                            val lineCount = remember(textValue.text) {
                                if (textValue.text.isEmpty()) 0 else textValue.text.count { it == '\n' } + 1
                            }
                            Text(
                                text = "${formatSize(textValue.text.toByteArray().size.toLong())} · $lineCount 行 · UTF-8",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (isModified) {
                                showExitConfirm = true
                            } else {
                                onDismiss()
                            }
                        }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        // 自动换行切换
                        IconButton(onClick = { isWordWrap = !isWordWrap }) {
                            Icon(
                                imageVector = if (isWordWrap) Icons.Filled.WrapText else Icons.Filled.Notes,
                                contentDescription = if (isWordWrap) "取消自动换行" else "自动换行",
                                tint = if (isWordWrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // 只读 / 编辑切换
                        IconButton(onClick = { isReadOnly = !isReadOnly }) {
                            Icon(
                                imageVector = if (isReadOnly) Icons.Filled.Visibility else Icons.Filled.Edit,
                                contentDescription = if (isReadOnly) "切换到编辑" else "切换到浏览",
                                tint = if (isReadOnly) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                            )
                        }

                        // 保存按钮
                        if (onSave != null) {
                            if (isSaving) {
                                Box(
                                    modifier = Modifier.size(48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = { performSave() },
                                    enabled = isModified
                                ) {
                                    Icon(
                                        Icons.Filled.Save,
                                        contentDescription = "保存",
                                        tint = if (isModified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // 流式加载进度条
                if (isLoading) {
                    if (totalBytes > 0) {
                        val progress = (loadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "流式读取中: ${formatSize(loadedBytes)}${if (totalBytes > 0) " / ${formatSize(totalBytes)}" else ""}...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // 错误提示区
                if (errorMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = errorMessage ?: "加载错误",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { startLoading() }) {
                                Text("重试")
                            }
                        }
                    }
                } else {
                    // 编辑区主体
                    val verticalScrollState = rememberScrollState()
                    val horizontalScrollState = rememberScrollState()

                    val lines = remember(textValue.text) {
                        textValue.text.split('\n')
                    }
                    val lineCount = lines.size.coerceAtLeast(1)

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        // 左侧行号栏（与内容垂直同步滚动）
                        Column(
                            modifier = Modifier
                                .width(42.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .verticalScroll(verticalScrollState)
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            for (i in 1..lineCount) {
                                Text(
                                    text = "$i",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        lineHeight = 20.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        textAlign = TextAlign.End
                                    ),
                                    maxLines = 1
                                )
                            }
                        }

                        // 分割线
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        )

                        // 右侧编辑器区域
                        val editorModifier = if (isWordWrap) {
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(verticalScrollState)
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                        } else {
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(verticalScrollState)
                                .horizontalScroll(horizontalScrollState)
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                        }

                        Box(modifier = editorModifier) {
                            BasicTextField(
                                value = textValue,
                                onValueChange = { if (!isReadOnly) textValue = it },
                                readOnly = isReadOnly,
                                textStyle = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // 底部状态条
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isReadOnly) "浏览模式 (只读)" else "编辑模式",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isReadOnly) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                            )
                            if (isModified) {
                                Text(
                                    text = "已修改 (未保存)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 退出未保存二次确认弹窗
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("保存修改？") },
            text = { Text("文件 \"$fileName\" 已修改，是否在退出前保存？") },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    performSave(onSuccess = onDismiss)
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showExitConfirm = false
                        onDismiss()
                    }) {
                        Text("放弃修改", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { showExitConfirm = false }) {
                        Text("取消")
                    }
                }
            }
        )
    }
}
