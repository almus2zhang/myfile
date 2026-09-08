package com.example.myfile.ui.webdav

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myfile.model.WebDavAccount
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    var isDynamic by remember { mutableStateOf(initial?.isDynamic ?: false) }
    var resolvedUrl by remember { mutableStateOf(initial?.resolvedUrl ?: "") }
    var extraPorts by remember { mutableStateOf(initial?.extraUrls?.joinToString(",") { extractPort(it) } ?: "") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加账户" else "编辑账户") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace("\r", "").replace("\n", "") },
                    label = { Text("名称") },
                    singleLine = true,
                    maxLines = 1
                )

                // 类别选择：普通固定地址 vs 动态解析/重定向
                Text("账户类别", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !isDynamic,
                        onClick = { isDynamic = false },
                        label = { Text("普通固定地址") },
                        leadingIcon = if (!isDynamic) {
                            { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }
                        } else null
                    )
                    FilterChip(
                        selected = isDynamic,
                        onClick = { isDynamic = true },
                        label = { Text("动态解析/重定向") },
                        leadingIcon = if (isDynamic) {
                            { Icon(Icons.Filled.SyncAlt, null, Modifier.size(16.dp)) }
                        } else null
                    )
                }

                if (isDynamic) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "💡 动态类别始终保存上方原始地址。日常使用解析后的端点连接；当 IP 或端口变动导致断开时，自动重新获取最新真实地址，无需手动修改配置。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            if (resolvedUrl.isNotBlank()) {
                                Text(
                                    "当前已解析连接端点: $resolvedUrl",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        // 强制单行：自动去掉所有换行符和回车符，防止多行隐藏
                        url = it.replace("\r", "").replace("\n", "").replace("\t", "")
                        testResult = null
                    },
                    label = { Text(if (isDynamic) "动态网址 / 重定向入口" else "WebDAV 地址") },
                    placeholder = {
                        Text(if (isDynamic) "https://web22.114.nasnas.site:11466" else "http://192.168.1.100:5005/video")
                    },
                    singleLine = true,
                    maxLines = 1,
                    supportingText = {
                        if (!isDynamic) {
                            Text("群晖需带共享文件夹名，例如 http://域名:5005/video", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it.replace("\r", "").replace("\n", "") },
                    label = { Text("用户名") },
                    singleLine = true,
                    maxLines = 1
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it.replace("\r", "").replace("\n", "") },
                    label = { Text("密码") },
                    singleLine = true,
                    maxLines = 1
                )
                OutlinedTextField(
                    value = extraPorts,
                    onValueChange = { extraPorts = it.replace("\r", "").replace("\n", "") },
                    label = { Text("备用端口（逗号分隔，如 24438,24439）") },
                    singleLine = true,
                    maxLines = 1,
                    supportingText = {
                        Text("多个打洞端口轮换下载，绕过运营商流量额度限速", style = MaterialTheme.typography.bodySmall)
                    }
                )

                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = !testing && url.isNotBlank() && user.isNotBlank(),
                        onClick = {
                            val cleanUrl = cleanWebDavUrl(url)
                            url = cleanUrl
                            val targetForValidation = if (cleanUrl.startsWith("302:", ignoreCase = true) || cleanUrl.startsWith("301:", ignoreCase = true)) {
                                cleanUrl.substring(4)
                            } else cleanUrl
                            val errorMsg = try {
                                targetForValidation.toHttpUrl()
                                null
                            } catch (e: Exception) {
                                e.message ?: "格式无法解析"
                            }
                            if (errorMsg != null) {
                                testResult = "✗ URL 格式错误: $errorMsg"
                                return@OutlinedButton
                            }
                            testing = true
                            testResult = null
                            val probe = WebDavAccount(
                                id = initial?.id ?: 0,
                                name = name.trim().ifBlank { "probe" },
                                url = cleanUrl,
                                username = user.trim(),
                                password = pass,
                                isDynamic = isDynamic,
                                resolvedUrl = resolvedUrl
                            )
                            kotlinx.coroutines.GlobalScope.launch {
                                val res = com.example.myfile.MyApp.instance.webDavRepository.testConnection(probe)
                                val detectedEndpoint = res.resolvedUrl
                                if (res.ok) {
                                    if (detectedEndpoint.isNotBlank()) {
                                        resolvedUrl = detectedEndpoint
                                    }
                                    val isRedirected = detectedEndpoint.isNotBlank() && detectedEndpoint.trimEnd('/') != cleanUrl.trimEnd('/')
                                    if (!isDynamic && isRedirected) {
                                        isDynamic = true
                                        testResult = "✓ 连接成功！探测到重定向/动态端点，已自动设为「动态解析」类别 (端点: $detectedEndpoint)"
                                    } else if (isDynamic) {
                                        testResult = "✓ 连接成功！(已解析连接端点: $detectedEndpoint)"
                                    } else {
                                        testResult = "✓ 连接成功: ${res.message}"
                                    }
                                } else {
                                    testResult = "✗ ${res.message}"
                                }
                                // 始终保留原始输入 cleanUrl，绝不将其覆盖为解析后的临时 IP
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
                    val baseForExtras = if (isDynamic && resolvedUrl.isNotBlank()) resolvedUrl else cleanUrl
                    val extras = buildExtraUrls(baseForExtras, extraPorts)
                    onSave(
                        WebDavAccount(
                            id = initial?.id ?: 0,
                            name = name.trim(),
                            url = cleanUrl,
                            username = user.trim(),
                            password = pass,
                            extraUrls = extras,
                            isDynamic = isDynamic,
                            resolvedUrl = resolvedUrl
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 清洗并规范化 WebDAV URL，纠正全角字符、不可见字符、空格、重复协议等常见输入错误 */
fun cleanWebDavUrl(raw: String): String {
    if (raw.isBlank()) return ""
    var s = raw
    // 1. 移除常见不可见字符、零宽字符、BOM与不换行空格
    s = s.replace(Regex("""[\u200B\u200C\u200D\uFEFF\u00A0\u2028\u2029\u200E\u200F]"""), "")
    // 2. 全角转半角 (全角冒号, 全角句号/点, 全角斜杠, 全角数字, 全角字母)
    val sb = StringBuilder()
    for (c in s) {
        when {
            c == '：' -> sb.append(':')
            c == '。' || c == '．' -> sb.append('.')
            c == '／' -> sb.append('/')
            c in '０'..'９' -> sb.append((c - '０' + '0'.code).toChar())
            c in 'ａ'..'ｚ' -> sb.append((c - 'ａ' + 'a'.code).toChar())
            c in 'Ａ'..'Ｚ' -> sb.append((c - 'Ａ' + 'A'.code).toChar())
            else -> sb.append(c)
        }
    }
    s = sb.toString()
    // 3. 移除所有空白字符（包括空格、制表符、换行符）
    s = s.replace(Regex("""\s+"""), "")
    // 3.1 识别并提取 302: / 301: 前缀
    val is302 = s.startsWith("302:", ignoreCase = true) || s.startsWith("301:", ignoreCase = true)
    val prefix = if (is302) s.substring(0, 4) else ""
    if (is302) {
        s = s.substring(4)
    }
    // 4. 清理末尾意外跟随的 http(s) 片段，如 "httphttp:" 或 "http://"
    s = s.replace(Regex("""(https?)+:?/*$""", RegexOption.IGNORE_CASE), "")
    s = s.replace(Regex("""(?<=\d|/)(https?://*)+$""", RegexOption.IGNORE_CASE), "")
    // 5. 处理多重重复的前缀，例如 http://http:// 或 https://http:// 等
    while (s.startsWith("http://http://", ignoreCase = true) ||
        s.startsWith("http://https://", ignoreCase = true) ||
        s.startsWith("https://http://", ignoreCase = true) ||
        s.startsWith("https://https://", ignoreCase = true)
    ) {
        s = s.substring(s.indexOf("://") + 3)
    }
    // 6. 如果没有 http:// 或 https:// 前缀，自动补齐 http://
    if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
        s = "http://$s"
    }
    return (prefix + s).trim()
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
