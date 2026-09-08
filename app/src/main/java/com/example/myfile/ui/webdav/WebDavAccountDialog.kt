package com.example.myfile.ui.webdav

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myfile.model.WebDavAccount
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
@Composable
fun WebDavAccountDialog(
    initial: WebDavAccount? = null,
    onDismiss: () -> Unit,
    onSave: (WebDavAccount) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var user by remember { mutableStateOf(initial?.username ?: "") }
    var pass by remember { mutableStateOf(initial?.password ?: "") }
    var extraPorts by remember { mutableStateOf(initial?.extraUrls?.joinToString(",") { extractPort(it) } ?: "") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加账户" else "编辑账户") },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; testResult = null },
                    label = { Text("地址") },
                    placeholder = { Text("http://192.168.1.100:5005/video") },
                    singleLine = true,
                    supportingText = {
                        Text("群晖需带共享文件夹名，例如 http://域名:5005/video", style = MaterialTheme.typography.bodySmall)
                    }
                )
                OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("用户名") }, singleLine = true)
                OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("密码") }, singleLine = true)
                OutlinedTextField(
                    value = extraPorts,
                    onValueChange = { extraPorts = it },
                    label = { Text("备用端口（逗号分隔，如 24438,24439）") },
                    singleLine = true,
                    supportingText = {
                        Text("多个打洞端口轮换下载，绕过运营商流量额度限速", style = MaterialTheme.typography.bodySmall)
                    }
                )

                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = !testing && url.isNotBlank() && user.isNotBlank(),
                        onClick = {
                            val cleanUrl = cleanWebDavUrl(url)
                            if (cleanUrl.toHttpUrlOrNull() == null) {
                                testResult = "✗ URL 格式不正确，请检查地址或端口"
                                return@OutlinedButton
                            }
                            url = cleanUrl
                            testing = true
                            testResult = null
                            val probe = WebDavAccount(initial?.id ?: 0, name.trim().ifBlank { "probe" }, cleanUrl, user.trim(), pass)
                            kotlinx.coroutines.GlobalScope.launch {
                                val (ok, msg) = com.example.myfile.MyApp.instance.webDavRepository.testConnection(probe)
                                testResult = if (ok) "✓ 连接成功：$msg" else "✗ $msg"
                                testing = false
                            }
                        }
                    ) { Text(if (testing) "测试中..." else "测试连接") }
                    Spacer(Modifier.width(12.dp))
                    testResult?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && url.isNotBlank(),
                onClick = {
                    val cleanUrl = cleanWebDavUrl(url)
                    val extras = buildExtraUrls(cleanUrl, extraPorts)
                    onSave(WebDavAccount(initial?.id ?: 0, name.trim(), cleanUrl, user.trim(), pass, extras))
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 清洗并规范化 WebDAV URL，纠正重复协议、空格、缺少协议等常见输入错误 */
fun cleanWebDavUrl(raw: String): String {
    var s = raw.trim()
    if (s.isBlank()) return ""
    // 替换中文全角冒号与斜杠
    s = s.replace('：', ':').replace('／', '/')
    // 移除末尾意外跟随的 http(s) 片段，如 " httphttp:" 或 " http://"
    s = s.replace(Regex("""\s+(https?)+:?/*$""", RegexOption.IGNORE_CASE), "")
    // 移除数字或路径尾部意外黏附的协议头（如 1.2.3.4:5http:// 或 1.2.3.4:5http）
    s = s.replace(Regex("""(?<=\d|/)(https?://*)+$""", RegexOption.IGNORE_CASE), "")
    // 处理多重重复的前缀，例如 http://http:// 或 https://http:// 等
    while (s.startsWith("http://http://", ignoreCase = true) ||
        s.startsWith("http://https://", ignoreCase = true) ||
        s.startsWith("https://http://", ignoreCase = true) ||
        s.startsWith("https://https://", ignoreCase = true)
    ) {
        s = s.substring(s.indexOf("://") + 3)
    }
    // 如果没有 http:// 或 https:// 前缀，自动补齐 http://
    if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
        s = "http://$s"
    }
    return s.trim()
}

/** 从 URL 提取端口号（用于回显） */
private fun extractPort(url: String): String = try {
    val uri = java.net.URI(url)
    if (uri.port > 0) uri.port.toString() else ""
} catch (e: Exception) { "" }

/** 根据主 URL 和逗号分隔的端口列表，构建备用 URL 列表 */
private fun buildExtraUrls(baseUrl: String, portsText: String): List<String> {
    if (portsText.isBlank()) return emptyList()
    return portsText.split(',', '，')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { portStr ->
            val port = portStr.toIntOrNull() ?: return@mapNotNull null
            try {
                val uri = java.net.URI(baseUrl)
                java.net.URI(uri.scheme, uri.userInfo, uri.host, port, uri.path, uri.query, uri.fragment).toString()
            } catch (e: Exception) { null }
        }
}
