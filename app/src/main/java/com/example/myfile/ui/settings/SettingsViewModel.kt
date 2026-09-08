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
}
