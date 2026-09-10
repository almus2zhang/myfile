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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.shape.RoundedCornerShape
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
    var passwordVisible by remember { mutableStateOf(false) }
    var renameToVideoExt by remember { mutableStateOf(initial?.renameToVideoExt ?: true) }
    var streamFakeAvi by remember { mutableStateOf(initial?.streamFakeAvi ?: false) }
    var isEncrypted by remember { mutableStateOf(initial?.isEncrypted ?: false) }
    var encryptPassword by remember { mutableStateOf(initial?.encryptPassword ?: "") }
    var encryptPasswordVisible by remember { mutableStateOf(false) }
    var rememberLastPath by remember { mutableStateOf(initial?.rememberLastPath ?: true) }
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
                SettingsSectionTitle(
                    icon = Icons.Filled.Cloud,
                    title = "基本信息"
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace("\r", "").replace("\n", "") },
                    label = { Text("名称") },
                    singleLine = true,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                // 类别选择：普通固定地址 vs 动态解析/重定向
                Text(
                    "账户类别",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, top = 4.dp)
                )
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
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
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
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it.replace("\r", "").replace("\n", "") },
                    label = { Text("密码") },
                    singleLine = true,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                            )
                        }
                    }
                )
                OutlinedTextField(
                    value = extraPorts,
                    onValueChange = { extraPorts = it.replace("\r", "").replace("\n", "") },
                    label = { Text("备用端口（逗号分隔，如 24438,24439）") },
                    singleLine = true,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    supportingText = {
                        Text("多个打洞端口轮换下载，绕过运营商流量额度限速", style = MaterialTheme.typography.bodySmall)
                    }
                )

                // ============ 高级选项分组 ============
                SettingsSectionTitle(
                    icon = Icons.Filled.Tune,
                    title = "高级选项"
                )
                SettingsCard {
                    // 记住上次路径
                    OptionSwitch(
                        title = "记住上次浏览位置",
                        subtitle = "下次切换到此配置时，自动回到上次浏览的目录",
                        checked = rememberLastPath,
                        onCheckedChange = { rememberLastPath = it }
                    )
                }

                // ============ 加速选项分组 ============
                SettingsSectionTitle(
                    icon = Icons.Filled.Bolt,
                    title = "加速选项"
                )
                SettingsCard {
                    OptionSwitch(
                        title = "改名下载（加速下载）",
                        subtitle = "点击 APK 或大于 5M 文件时采用改名多线程加速下载，绕过运营商限速",
                        checked = renameToVideoExt,
                        onCheckedChange = { renameToVideoExt = it }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    OptionSwitch(
                        title = "视频播放伪装 .avi 加速",
                        subtitle = "在线播放视频时临时改名为 .avi，加速流媒体加载与播放",
                        checked = streamFakeAvi,
                        onCheckedChange = { streamFakeAvi = it }
                    )
                }

                // ============ 安全分组 ============
                SettingsSectionTitle(
                    icon = Icons.Filled.Shield,
                    title = "安全"
                )
                SettingsCard {
                    OptionSwitch(
                        title = "配置加密保护",
                        subtitle = "切换到该配置时需要输入密码或指纹解锁",
                        checked = isEncrypted,
                        onCheckedChange = { isEncrypted = it }
                    )
                    if (isEncrypted) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = encryptPassword,
                            onValueChange = { encryptPassword = it.replace("\r", "").replace("\n", "") },
                            label = { Text("独立解锁密码") },
                            placeholder = { Text("留空则默认使用上方 WebDAV 密码") },
                            supportingText = { Text("切换到该配置时验证此密码或指纹（留空使用 WebDAV 密码）", style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            visualTransformation = if (encryptPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { encryptPasswordVisible = !encryptPasswordVisible }) {
                                    Icon(
                                        imageVector = if (encryptPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                        contentDescription = if (encryptPasswordVisible) "隐藏密码" else "显示密码"
                                    )
                                }
                            }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

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
                                resolvedUrl = resolvedUrl,
                                renameToVideoExt = renameToVideoExt,
                                streamFakeAvi = streamFakeAvi,
                                isEncrypted = isEncrypted,
                                encryptPassword = encryptPassword,
                                rememberLastPath = rememberLastPath
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
                            resolvedUrl = resolvedUrl,
                            renameToVideoExt = renameToVideoExt,
                            streamFakeAvi = streamFakeAvi,
                            isEncrypted = isEncrypted,
                            encryptPassword = encryptPassword,
                            rememberLastPath = rememberLastPath
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 分组标题（带图标） */
@Composable
private fun SettingsSectionTitle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 6.dp, start = 2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** 分组卡片容器 */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            content = content
        )
    }
}

/** 统一的开关选项行 */
@Composable
private fun OptionSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = MaterialTheme.typography.bodySmall.lineHeight
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
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
