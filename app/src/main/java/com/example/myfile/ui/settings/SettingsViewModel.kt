package com.example.myfile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myfile.MyApp
import com.example.myfile.data.prefs.DownloadSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {
    private val store = MyApp.instance.settingsStore
    private val _settings = MutableStateFlow(DownloadSettings())
    val settings: StateFlow<DownloadSettings> = _settings.asStateFlow()

    init {
        viewModelScope.launch {
            store.settings.collect { _settings.value = it }
        }
    }

    fun updateChunkSize(bytes: Long) {
        viewModelScope.launch { store.update { it.copy(chunkSize = bytes) } }
    }

    fun updateConnections(n: Int) {
        viewModelScope.launch { store.update { it.copy(maxConnections = n) } }
    }

    fun updateRetries(n: Int) {
        viewModelScope.launch { store.update { it.copy(maxRetries = n) } }
    }

    fun updateConnectTimeout(sec: Long) {
        viewModelScope.launch { store.update { it.copy(connectTimeoutSec = sec) } }
    }

    fun updateReadTimeout(sec: Long) {
        viewModelScope.launch { store.update { it.copy(readTimeoutSec = sec) } }
    }

    fun updateSingleConnection(enabled: Boolean) {
        viewModelScope.launch { store.update { it.copy(singleConnectionMode = enabled) } }
    }

    fun updateStreamPulse(enabled: Boolean) {
        viewModelScope.launch { store.update { it.copy(streamPulseMode = enabled) } }
    }

    fun updateRenameToVideoExt(enabled: Boolean) {
        viewModelScope.launch { store.update { it.copy(renameToVideoExt = enabled) } }
    }

    fun updateStreamFakeAvi(enabled: Boolean) {
        viewModelScope.launch { store.update { it.copy(streamFakeAvi = enabled) } }
    }

    fun updateLoadRemoteVideoThumbnails(enabled: Boolean) {
        viewModelScope.launch { store.update { it.copy(loadRemoteVideoThumbnails = enabled) } }
    }

    fun updateShowVideoDuration(enabled: Boolean) {
        viewModelScope.launch { store.update { it.copy(showVideoDuration = enabled) } }
    }

    fun updateAutoCheckUpdate(enabled: Boolean) {
        viewModelScope.launch { store.update { it.copy(autoCheckUpdate = enabled) } }
    }

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    private val _updateInfo = MutableStateFlow<com.example.myfile.core.ota.UpdateInfo?>(null)
    val updateInfo: StateFlow<com.example.myfile.core.ota.UpdateInfo?> = _updateInfo.asStateFlow()

    private val _checkResultMsg = MutableStateFlow<String?>(null)
    val checkResultMsg: StateFlow<String?> = _checkResultMsg.asStateFlow()

    fun checkForUpdates() {
        if (_isCheckingUpdate.value) return
        _isCheckingUpdate.value = true
        _checkResultMsg.value = null
        viewModelScope.launch {
            val res = com.example.myfile.core.ota.OtaManager.checkUpdate()
            _isCheckingUpdate.value = false
            res.onSuccess { info ->
                if (info.hasUpdate) {
                    _updateInfo.value = info
                } else {
                    _checkResultMsg.value = "当前已是最新版本 (v${info.versionName.ifBlank { com.example.myfile.BuildConfig.VERSION_NAME }})"
                }
            }.onFailure { err ->
                _checkResultMsg.value = "检查更新失败: ${err.localizedMessage ?: "网络错误"}"
            }
        }
    }

    fun dismissUpdateDialog() {
        _updateInfo.value = null
    }

    fun clearCheckResultMsg() {
        _checkResultMsg.value = null
    }
}
