package com.example.myfile.model

data class WebDavAccount(
    val id: Long = 0,
    val name: String,
    val url: String,        // 形如 https://example.com/dav 或 http://1.2.3.4:8080
    val username: String,
    val password: String,
    val extraUrls: List<String> = emptyList()   // 备用地址（不同打洞端口，用于轮换绕过限速）
) {
    /** 所有可用地址（主地址 + 备用地址） */
    fun allUrls(): List<String> = listOf(url) + extraUrls
}
