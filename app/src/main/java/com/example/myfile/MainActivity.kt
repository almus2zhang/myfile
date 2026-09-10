package com.example.myfile

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.example.myfile.ui.nav.AppNavigation
import com.example.myfile.ui.theme.MyfileTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyfileTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val hasPermission = remember { mutableStateOf(checkStoragePermission()) }
                    if (hasPermission.value) {
                        AppNavigation()
                        PendingRenameDialog()
                    } else {
                        PermissionScreen(onGranted = { hasPermission.value = true })
                    }
                }
            }
        }
    }

    private fun checkStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
}

@Composable
private fun PermissionScreen(onGranted: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onGranted() }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("需要「所有文件访问」权限", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "myfile 需要访问本地存储以浏览和管理文件。点击下方按钮授权。",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                launcher.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:com.example.myfile")
                })
            } else {
                onGranted()
            }
        }) { Text("去授权") }
    }
}

/**
 * 启动时检测上次「改名下载/播放」未改回的残留记录，弹窗提示用户。
 * 用户可选择「立即恢复」或「忽略」。
 */
@Composable
private fun PendingRenameDialog() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pendingRenames by MyApp.instance.downloadManager.pendingRenames
        .collectAsState()

    val hasShown = remember { mutableStateOf(false) }

    // 仅在首次检测到残留记录时弹出（用 hasShown 防止重复弹）
    if (pendingRenames.isNotEmpty() && !hasShown.value) {
        val names = pendingRenames.joinToString("\n") { "· ${it.fileName}" }
        AlertDialog(
            onDismissRequest = {
                hasShown.value = true
            },
            title = { Text("检测到未改回的文件") },
            text = {
                Column {
                    Text("上次改名下载/播放时，以下文件因中断未能改回原文件名：")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        names,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    hasShown.value = true
                    // 在 IO 线程执行恢复（涉及网络 MOVE）
                    MyApp.instance.appScope.launch {
                        MyApp.instance.downloadManager.recoverPendingRenames()
                    }
                }) { Text("立即恢复") }
            },
            dismissButton = {
                TextButton(onClick = {
                    hasShown.value = true
                    // 忽略：仅清除本地记录，服务器文件名保持 .avi
                    pendingRenames.forEach { it ->
                        MyApp.instance.downloadManager.dismissPendingRename(it.tmpPath)
                    }
                }) { Text("忽略") }
            }
        )
    }
}
