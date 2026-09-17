package com.example.myfile.ui.components

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    onLoad: suspend (charset: String?, onProgress: (loadedBytes: Long, totalBytes: Long) -> Unit) -> com.example.myfile.core.TextFileHelper.TextLoadResult,
    onSave: (suspend (newText: String, charset: String) -> Boolean)?,
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
    var isReadOnly by remember { mutableStateOf(true) } // 默认浏览模式，防大文件弹软键盘卡顿
    var isTruncated by remember { mutableStateOf(false) }
    var isHexPreview by remember { mutableStateOf(false) }
    var currentCharsetName by remember { mutableStateOf("UTF-8") }
    var showEncodingMenu by remember { mutableStateOf(false) }
    var pendingEncodingSwitch by remember { mutableStateOf<String?>(null) }
    var showExitConfirm by remember { mutableStateOf(false) }

    val isModified = remember(textValue.text, originalText) {
        textValue.text != originalText
    }

    // 启动流式读取
    fun startLoading(charset: String? = null) {
        isLoading = true
        errorMessage = null
        scope.launch {
            try {
                val result = onLoad(charset) { loaded, total ->
                    loadedBytes = loaded
                    totalBytes = total
                }
                textValue = TextFieldValue(result.content)
                originalText = result.content
                currentCharsetName = result.charsetName
                isHexPreview = result.isBinary
                isTruncated = result.isTruncated
                if (result.isTruncated || result.isBinary) {
                    isReadOnly = true
                }
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
        if (isHexPreview) {
            Toast.makeText(context, "二进制文件仅支持十六进制预览，禁止保存以防损坏文件", Toast.LENGTH_SHORT).show()
            return
        }
        if (isTruncated) {
            Toast.makeText(context, "文件已截断显示，禁止保存以防丢失数据", Toast.LENGTH_SHORT).show()
            return
        }
        isSaving = true
        scope.launch {
            try {
                val success = onSave(textValue.text, currentCharsetName)
                if (success) {
                    originalText = textValue.text
                    Toast.makeText(context, "已保存 ($currentCharsetName)", Toast.LENGTH_SHORT).show()
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

    val handleBack = {
        if (showExitConfirm) {
            showExitConfirm = false
        } else if (pendingEncodingSwitch != null) {
            pendingEncodingSwitch = null
        } else if (showEncodingMenu) {
            showEncodingMenu = false
        } else if (isModified) {
            showExitConfirm = true
        } else {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = handleBack,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true
        )
    ) {
        BackHandler(onBack = handleBack)

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
                            val displaySize = if (totalBytes > 0) totalBytes else textValue.text.length.toLong()
                            val subTitle = if (isHexPreview) {
                                "${formatSize(displaySize)} · 十六进制 Hex 预览 (只读)"
                            } else {
                                "${formatSize(displaySize)} · $lineCount 行 · $currentCharsetName" + if (isTruncated) " (已截断)" else ""
                            }
                            Text(
                                text = subTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isHexPreview || isTruncated) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = handleBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        // 编码选择切换（仅在文本模式下可用）
                        if (!isHexPreview) {
                            Box {
                                Surface(
                                    onClick = { showEncodingMenu = true },
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = currentCharsetName,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Spacer(Modifier.width(2.dp))
                                        Icon(
                                            Icons.Filled.ArrowDropDown,
                                            contentDescription = "选择编码",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = showEncodingMenu,
                                    onDismissRequest = { showEncodingMenu = false }
                                ) {
                                    com.example.myfile.core.TextFileHelper.COMMON_ENCODINGS.forEach { enc ->
                                        val isSelected = enc.name.equals(currentCharsetName, ignoreCase = true)
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = enc.displayName,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                            },
                                            leadingIcon = if (isSelected) {
                                                {
                                                    Icon(
                                                        Icons.Filled.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            } else null,
                                            onClick = {
                                                showEncodingMenu = false
                                                if (!isSelected) {
                                                    if (isModified) {
                                                        pendingEncodingSwitch = enc.name
                                                    } else {
                                                        currentCharsetName = enc.name
                                                        startLoading(enc.name)
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // 自动换行切换
                        IconButton(onClick = { isWordWrap = !isWordWrap }) {
                            Icon(
                                imageVector = if (isWordWrap) Icons.Filled.WrapText else Icons.Filled.Notes,
                                contentDescription = if (isWordWrap) "取消自动换行" else "自动换行",
                                tint = if (isWordWrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // 只读 / 编辑切换
                        IconButton(onClick = {
                            if (isHexPreview) {
                                Toast.makeText(context, "二进制文件仅支持十六进制预览，禁止编辑以防损坏文件", Toast.LENGTH_SHORT).show()
                            } else if (isTruncated) {
                                Toast.makeText(context, "文件过大已截断，仅支持浏览，禁止编辑以防损坏原文件", Toast.LENGTH_SHORT).show()
                            } else {
                                isReadOnly = !isReadOnly
                            }
                        }) {
                            Icon(
                                imageVector = if (isReadOnly) Icons.Filled.Edit else Icons.Filled.Visibility,
                                contentDescription = if (isReadOnly) "切换到编辑" else "切换到浏览",
                                tint = if (isReadOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
                                val canSave = isModified && !isTruncated
                                IconButton(
                                    onClick = { performSave() },
                                    enabled = canSave
                                ) {
                                    Icon(
                                        Icons.Filled.Save,
                                        contentDescription = "保存",
                                        tint = if (canSave) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
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

                    val lineCount = remember(textValue.text) {
                        if (textValue.text.isEmpty()) 0 else textValue.text.count { it == '\n' } + 1
                    }

                    // 单个 Text 高效渲染行号，避免千万个 Compose 节点导致卡死
                    val lineNumbersText = remember(lineCount) {
                        val limit = minOf(lineCount, 3000)
                        buildString(limit * 6) {
                            for (i in 1..limit) {
                                append(i).append('\n')
                            }
                            if (lineCount > 3000) append("...")
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        // 左侧行号栏（与内容垂直同步滚动）
                        Box(
                            modifier = Modifier
                                .width(if (lineCount >= 1000) 48.dp else 38.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .verticalScroll(verticalScrollState)
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            Text(
                                text = lineNumbersText,
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    textAlign = TextAlign.End
                                )
                            )
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
                            if (isReadOnly) {
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(
                                        text = textValue.text,
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            lineHeight = 20.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            } else {
                                BasicTextField(
                                    value = textValue,
                                    onValueChange = { textValue = it },
                                    readOnly = false,
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
                                text = if (isHexPreview) {
                                    "二进制文件十六进制预览 (只读，禁止编辑与保存)"
                                } else if (isTruncated) {
                                    "已截断显示前 256KB · $currentCharsetName (只读，禁止保存)"
                                } else if (isReadOnly) {
                                    "浏览模式 · $currentCharsetName (只读，轻触右上角铅笔可编辑)"
                                } else {
                                    "编辑模式 · $currentCharsetName"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isHexPreview || isTruncated) MaterialTheme.colorScheme.error
                                       else if (isReadOnly) MaterialTheme.colorScheme.onSurfaceVariant
                                       else MaterialTheme.colorScheme.primary
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

    // 切换编码未保存二次确认弹窗
    pendingEncodingSwitch?.let { targetCharset ->
        AlertDialog(
            onDismissRequest = { pendingEncodingSwitch = null },
            title = { Text("切换编码重新加载") },
            text = { Text("当前内容已做修改，以「$targetCharset」重新加载将放弃未保存的修改。\n\n是否确认重新加载？") },
            confirmButton = {
                Button(onClick = {
                    val target = targetCharset
                    pendingEncodingSwitch = null
                    currentCharsetName = target
                    startLoading(target)
                }) {
                    Text("放弃修改并重新加载")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingEncodingSwitch = null }) {
                    Text("取消")
                }
            }
        )
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
