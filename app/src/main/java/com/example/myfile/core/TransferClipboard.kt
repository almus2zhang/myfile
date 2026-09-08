package com.example.myfile.core

import com.example.myfile.model.FileEntry
import com.example.myfile.model.WebDavAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ClipboardEntry(
    val entry: FileEntry,
    val account: WebDavAccount? = null // null 表示本地文件，非 null 表示来自该 WebDAV 账户
)

/**
 * 全局文件剪贴板：支持本地与远程跨源多选复制与粘贴
 */
object TransferClipboard {
    private val _items = MutableStateFlow<List<ClipboardEntry>>(emptyList())
    val items: StateFlow<List<ClipboardEntry>> = _items.asStateFlow()

    fun copy(newItems: List<ClipboardEntry>) {
        _items.value = newItems
    }

    fun clear() {
        _items.value = emptyList()
    }

    val count: Int get() = _items.value.size
    val hasItems: Boolean get() = _items.value.isNotEmpty()
}
