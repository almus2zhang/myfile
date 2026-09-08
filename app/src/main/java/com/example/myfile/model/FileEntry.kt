package com.example.myfile.model

/**
 * 统一文件条目模型，本地与远程通用
 */
data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String = "",
    val source: FileSource = FileSource.LOCAL
)

enum class FileSource { LOCAL, WEBDAV }
