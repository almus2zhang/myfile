package com.example.myfile.model

data class WebDavAccount(
    val id: Long = 0,
    val name: String,
    val url: String,                            // 原始配置地址（如动态解析网址或重定向网址，始终记忆保留，不被覆盖）
    val username: String,
    val password: String,
    val extraUrls: List<String> = emptyList(),  // 备用地址（不同打洞端口，用于轮换绕过限速）
    val isDynamic: Boolean = false,             // 类别：是否为动态解析 / 重定向网址
    val resolvedUrl: String = "",               // 最终解析出的真实连接地址（缓存并用于日常连接）
    val renameToVideoExt: Boolean = true,       // 改名下载（加速下载）
    val streamFakeAvi: Boolean = false,         // 视频播放伪装 .avi 后缀加速
    val isEncrypted: Boolean = false,           // 是否加密保护
    val encryptPassword: String = ""            // 解锁密码（若为空则使用 WebDav 密码）
) {
    /** 实际用于连接的有效地址：若为动态类别且有解析出的地址，则优先使用解析地址；否则使用原始配置地址 */
    fun connectionUrl(): String = if (isDynamic && resolvedUrl.isNotBlank()) resolvedUrl else url

    /** 所有可用地址（主连接地址 + 备用端口地址） */
    fun allUrls(): List<String> = listOf(connectionUrl()) + extraUrls

    /** 有效解锁密码 */
    fun unlockPassword(): String = encryptPassword.ifBlank { password }
}
