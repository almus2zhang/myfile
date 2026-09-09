package com.example.myfile.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.example.myfile.model.ViewMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ViewModeStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("view_mode_prefs", Context.MODE_PRIVATE)

    private val _webDavViewMode = MutableStateFlow(loadWebDavMode())
    val webDavViewMode: StateFlow<ViewMode> = _webDavViewMode.asStateFlow()

    private val _localViewMode = MutableStateFlow(loadLocalMode())
    val localViewMode: StateFlow<ViewMode> = _localViewMode.asStateFlow()

    private val _showThumbnailsAndDuration = MutableStateFlow(loadShowThumbnailsAndDuration())
    val showThumbnailsAndDuration: StateFlow<Boolean> = _showThumbnailsAndDuration.asStateFlow()

    private fun loadWebDavMode(): ViewMode {
        val name = prefs.getString("webdav_view_mode", ViewMode.DETAILS.name)
        return try {
            ViewMode.valueOf(name ?: ViewMode.DETAILS.name)
        } catch (_: Exception) {
            ViewMode.DETAILS
        }
    }

    fun setWebDavViewMode(mode: ViewMode) {
        prefs.edit().putString("webdav_view_mode", mode.name).apply()
        _webDavViewMode.value = mode
    }

    private fun loadLocalMode(): ViewMode {
        val name = prefs.getString("local_view_mode", ViewMode.DETAILS.name)
        return try {
            ViewMode.valueOf(name ?: ViewMode.DETAILS.name)
        } catch (_: Exception) {
            ViewMode.DETAILS
        }
    }

    fun setLocalViewMode(mode: ViewMode) {
        prefs.edit().putString("local_view_mode", mode.name).apply()
        _localViewMode.value = mode
    }

    private fun loadShowThumbnailsAndDuration(): Boolean {
        return prefs.getBoolean("show_thumbnails_and_duration", true)
    }

    fun setShowThumbnailsAndDuration(show: Boolean) {
        prefs.edit().putBoolean("show_thumbnails_and_duration", show).apply()
        _showThumbnailsAndDuration.value = show
    }
}
