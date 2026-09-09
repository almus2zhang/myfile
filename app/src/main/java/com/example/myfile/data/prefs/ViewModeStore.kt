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

    private fun loadWebDavMode(): ViewMode {
        val name = prefs.getString("webdav_view_mode", ViewMode.DETAILS_WITH_MEDIA.name)
        return try {
            ViewMode.valueOf(name ?: ViewMode.DETAILS_WITH_MEDIA.name)
        } catch (_: Exception) {
            ViewMode.DETAILS_WITH_MEDIA
        }
    }

    fun setWebDavViewMode(mode: ViewMode) {
        prefs.edit().putString("webdav_view_mode", mode.name).apply()
        _webDavViewMode.value = mode
    }
}
