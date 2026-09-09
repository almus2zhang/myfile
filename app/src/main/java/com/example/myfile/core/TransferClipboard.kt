package com.example.myfile.core

import com.example.myfile.model.FileEntry
import com.example.myfile.model.WebDavAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ClipboardOp {
    COPY,
    CUT
}

data class ClipboardEntry(
    val entry: FileEntry,
    val account: WebDavAccount? = null // null 表示本地文件，非 null 表示来自该 WebDAV 账户
)

/**
 * 全局文件剪贴板：支持本地与远程跨源多选复制、剪切与粘贴移动
 */
object TransferClipboard {
    private val _items = MutableStateFlow<List<ClipboardEntry>>(emptyList())
    val items: StateFlow<List<ClipboardEntry>> = _items.asStateFlow()

    private val _op = MutableStateFlow(ClipboardOp.COPY)
    val op: StateFlow<ClipboardOp> = _op.asStateFlow()

    fun copy(newItems: List<ClipboardEntry>) {
        _op.value = ClipboardOp.COPY
        _items.value = newItems
    }

    fun cut(newItems: List<ClipboardEntry>) {
        _op.value = ClipboardOp.CUT
        _items.value = newItems
    }

    fun clear() {
        _items.value = emptyList()
        _op.value = ClipboardOp.COPY
    }

    val isCut: Boolean get() = _op.value == ClipboardOp.CUT
    val count: Int get() = _items.value.size
    val hasItems: Boolean get() = _items.value.isNotEmpty()
}
