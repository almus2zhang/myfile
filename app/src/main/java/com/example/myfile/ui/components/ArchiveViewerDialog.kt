package com.example.myfile.ui.components

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myfile.core.ArchiveEntryItem
import com.example.myfile.core.ArchiveHelper
import com.example.myfile.core.FileOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通用压缩包浏览器与解压对话框
 * 支持 ZIP, RAR, 7Z, TAR, GZ/TGZ, BZ2, XZ 等格式
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveViewerDialog(
    archiveFile: File,
    title: String,
    defaultExtractDir: File? = null,
    onOpenExtractedDir: ((File) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var allEntries by remember { mutableStateOf<List<ArchiveEntryItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var currentPath by remember { mutableStateOf("") } // 相对包内路径，例如 "" 或 "subdir/"

    val archiveType = remember(archiveFile) {
        ArchiveHelper.getArchiveType(archiveFile.name)
    }

    // 提取单个文件并打开状态
    var openingItem by remember { mutableStateOf<ArchiveEntryItem?>(null) }

    // 全部解压状态
    var showExtractConfirm by remember { mutableStateOf(false) }
    var isExtracting by remember { mutableStateOf(false) }
    var extractProgress by remember { mutableFloatStateOf(0f) }
    var extractCurrentName by remember { mutableStateOf("") }
    var extractDoneCount by remember { mutableIntStateOf(0) }
    var extractTotalCount by remember { mutableIntStateOf(0) }

    // 默认解压目标目录：Downloads/myfile/{archiveNameWithoutExtension}
    val targetExtractDir = remember(archiveFile, defaultExtractDir) {
        defaultExtractDir ?: run {
            val baseName = archiveFile.name
                .removeSuffix(".tar.gz").removeSuffix(".tar.bz2").removeSuffix(".tar.xz")
                .removeSuffix(".tgz").removeSuffix(".tbz2")
                .substringBeforeLast('.', archiveFile.nameWithoutExtension)
                .ifBlank { "extracted_archive" }
            val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            File(downloads, "myfile/$baseName")
        }
    }

    // 加载压缩包内容
    LaunchedEffect(archiveFile) {
        isLoading = true
        loadError = null
        withContext(Dispatchers.IO) {
            try {
                if (!archiveFile.exists() || !archiveFile.canRead()) {
                    loadError = "压缩包文件不存在或无法读取"
                } else {
                    val entries = ArchiveHelper.parseAllEntries(archiveFile)
                    allEntries = entries
                }
            } catch (e: Exception) {
                loadError = "解析压缩包失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // 目录返回逻辑
    fun navigateUp() {
        if (currentPath.isEmpty()) {
            onDismiss()
        } else {
            val trimmed = currentPath.trimEnd('/')
            val parent = trimmed.substringBeforeLast('/', "")
            currentPath = if (parent.isEmpty()) "" else "$parent/"
        }
    }

    // 拦截系统返回键
    BackHandler(enabled = true) {
        if (isExtracting) {
            Toast.makeText(context, "正在解压中，请稍候...", Toast.LENGTH_SHORT).show()
        } else {
            navigateUp()
        }
    }

    // 当前目录展示项
    val currentItems = remember(allEntries, currentPath) {
        ArchiveHelper.listDirectory(allEntries, currentPath)
    }

    // 面包屑分段
    val pathSegments = remember(currentPath) {
        val list = mutableListOf<Pair<String, String>>()
        list.add("根目录" to "")
        if (currentPath.isNotEmpty()) {
            val parts = currentPath.trimEnd('/').split('/')
            var acc = ""
            for (p in parts) {
                acc = if (acc.isEmpty()) "$p/" else "$acc$p/"
                list.add(p to acc)
            }
        }
        list
    }

    Dialog(
        onDismissRequest = {
            if (!isExtracting) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                // 顶部工具栏
                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                archiveType?.let { at ->
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = at.displayName,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                            val totalFiles = allEntries.count { !it.isDirectory }
                            val totalDirs = allEntries.count { it.isDirectory }
                            Text(
                                text = if (isLoading) "正在解析..." else "$totalFiles 个文件" + if (totalDirs > 0) "，${totalDirs} 个目录" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigateUp() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    actions = {
                        if (!isLoading && loadError == null && allEntries.isNotEmpty()) {
                            FilledTonalButton(
                                onClick = { showExtractConfirm = true },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Unarchive,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("全部解压", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // 面包屑路径栏
                val breadcrumbScrollState = rememberScrollState()
                LaunchedEffect(currentPath) {
                    breadcrumbScrollState.animateScrollTo(breadcrumbScrollState.maxValue)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .horizontalScroll(breadcrumbScrollState)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderZip,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))

                    pathSegments.forEachIndexed { index, (name, path) ->
                        val isLast = index == pathSegments.lastIndex
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                            color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(enabled = !isLast) { currentPath = path }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        if (!isLast) {
                            Text(
                                text = "/",
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // 内容区
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        isLoading -> {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(40.dp))
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "正在读取压缩包结构...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        loadError != null -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(54.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = loadError ?: "未知错误",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(onClick = { onDismiss() }) {
                                    Text("关闭")
                                }
                            }
                        }

                        currentItems.isEmpty() -> {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(54.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "空文件夹",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        else -> {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(currentItems, key = { it.entryPath }) { item ->
                                    ArchiveItemRow(
                                        item = item,
                                        isOpening = openingItem?.entryPath == item.entryPath,
                                        onClick = {
                                            if (item.isDirectory) {
                                                currentPath = item.entryPath
                                            } else {
                                                // 点击文件：解压到缓存并打开
                                                openingItem = item
                                                scope.launch {
                                                    try {
                                                        val cacheDir = File(context.cacheDir, "archive_temp_view")
                                                        val tempFile = File(cacheDir, item.name)
                                                        val ok = ArchiveHelper.extractEntry(archiveFile, item.entryPath, tempFile)
                                                        if (ok && tempFile.exists()) {
                                                            FileOpener.open(context, tempFile)
                                                        } else {
                                                            Toast.makeText(context, "提取文件失败", Toast.LENGTH_SHORT).show()
                                                        }
                                                    } catch (e: Exception) {
                                                        Toast.makeText(context, "打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                                    } finally {
                                                        openingItem = null
                                                    }
                                                }
                                            }
                                        }
                                    )
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                        modifier = Modifier.padding(start = 68.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 确认解压弹窗
    if (showExtractConfirm) {
        AlertDialog(
            onDismissRequest = { showExtractConfirm = false },
            icon = { Icon(Icons.Filled.Unarchive, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("全部解压") },
            text = {
                Column {
                    Text("将压缩包内所有文件解压至：")
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = targetExtractDir.absolutePath,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExtractConfirm = false
                        isExtracting = true
                        extractProgress = 0f
                        extractDoneCount = 0
                        extractTotalCount = 0
                        scope.launch {
                            val result = ArchiveHelper.extractAll(
                                archiveFile = archiveFile,
                                destDir = targetExtractDir,
                                onProgress = { done, total, curName ->
                                    extractDoneCount = done
                                    extractTotalCount = total
                                    extractProgress = if (total > 0) done.toFloat() / total else 0f
                                    extractCurrentName = curName
                                }
                            )
                            isExtracting = false
                            if (result.isSuccess) {
                                Toast.makeText(
                                    context,
                                    "解压完成，共解压 ${result.getOrNull()} 个文件",
                                    Toast.LENGTH_LONG
                                ).show()
                                onOpenExtractedDir?.invoke(targetExtractDir)
                            } else {
                                Toast.makeText(
                                    context,
                                    "解压失败: ${result.exceptionOrNull()?.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                ) {
                    Text("开始解压")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExtractConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 解压进度弹窗
    if (isExtracting) {
        AlertDialog(
            onDismissRequest = { /* 禁止背景关闭 */ },
            icon = { CircularProgressIndicator(modifier = Modifier.size(36.dp)) },
            title = { Text("正在解压中...") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { extractProgress },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "$extractDoneCount / $extractTotalCount",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${(extractProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (extractCurrentName.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = extractCurrentName,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }
}

/**
 * 兼容旧命名 ZipViewerDialog
 */
@Composable
fun ZipViewerDialog(
    zipFile: File,
    title: String,
    defaultExtractDir: File? = null,
    onOpenExtractedDir: ((File) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    ArchiveViewerDialog(
        archiveFile = zipFile,
        title = title,
        defaultExtractDir = defaultExtractDir,
        onOpenExtractedDir = onOpenExtractedDir,
        onDismiss = onDismiss
    )
}

/**
 * 压缩包内部单项列表 Item
 */
@Composable
private fun ArchiveItemRow(
    item: ArchiveEntryItem,
    isOpening: Boolean,
    onClick: () -> Unit
) {
    val visualType = remember(item.isDirectory, item.name) {
        resolveVisualType(item.isDirectory, item.name)
    }

    val dateStr = remember(item.time) {
        if (item.time > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.time))
        } else ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isOpening, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (item.isDirectory) visualType.tintColor.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isOpening) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    imageVector = visualType.icon,
                    contentDescription = null,
                    tint = visualType.tintColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // 详情
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (item.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.isDirectory) {
                    Text(
                        text = "文件夹",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    if (item.size >= 0L) {
                        Text(
                            text = formatSize(item.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (item.compressedSize > 0 && item.compressedSize < item.size) {
                            val ratio = ((item.compressedSize.toDouble() / item.size) * 100).toInt()
                            Text(
                                text = " (压缩率 $ratio%)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    } else if (item.compressedSize > 0L) {
                        Text(
                            text = formatSize(item.compressedSize),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (dateStr.isNotEmpty()) {
                    Text(
                        text = "  ·  $dateStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        if (item.isDirectory) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
