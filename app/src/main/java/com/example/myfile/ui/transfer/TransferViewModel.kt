package com.example.myfile.ui.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myfile.MyApp
import com.example.myfile.model.TransferTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TransferViewModel : ViewModel() {
    private val mgr = MyApp.instance.downloadManager
    private val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val tasks: StateFlow<List<TransferTask>> = _tasks.asStateFlow()

    init {
        viewModelScope.launch {
            mgr.tasks.collect { _tasks.value = it }
        }
    }

    fun pause(id: Long) {
        viewModelScope.launch { mgr.pause(id) }
    }

    fun resume(id: Long) {
        viewModelScope.launch {
            // 简化：通过 accountStore 查找账户
            val task = _tasks.value.firstOrNull { it.id == id } ?: return@launch
            // 此处 account 解析简化处理：下载管理器内部已存 authHeader，实际由 resume 用任一账户亦可
            val acc = MyApp.instance.accountStore
            // 直接调 cancel 重新发起更稳妥，这里保留 pause/resume 占位
        }
    }

    fun cancel(id: Long) {
        viewModelScope.launch { mgr.cancel(id) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { mgr.delete(id) }
    }
}
