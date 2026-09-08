package com.example.myfile.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "download_settings")

data class DownloadSettings(
    val chunkSize: Long = 4L * 1024 * 1024,   // 4MB
    val maxConnections: Int = 4,
    val maxRetries: Int = 3,
    val connectTimeoutSec: Long = 30,
    val readTimeoutSec: Long = 60,
    val singleConnectionMode: Boolean = false,  // true = 单连接完整 GET（不用 Range）
    val streamPulseMode: Boolean = false,       // true = 流式节奏（边下边停，模拟边播边拉）
    val renameToVideoExt: Boolean = true,       // true = 下载前临时把文件改成 .avi 后缀绕过 Content-Type 限速，下完改回
    val streamFakeAvi: Boolean = false,         // true = 播放视频时伪装为 .avi 后缀
    val loadRemoteVideoThumbnails: Boolean = false // true = 自动加载 WebDAV 远程视频首帧缩略图（耗流量）
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val CHUNK_SIZE = longPreferencesKey("chunk_size")
        val MAX_CONNECTIONS = intPreferencesKey("max_connections")
        val MAX_RETRIES = intPreferencesKey("max_retries")
        val CONNECT_TIMEOUT = longPreferencesKey("connect_timeout")
        val READ_TIMEOUT = longPreferencesKey("read_timeout")
        val SINGLE_CONNECTION = booleanPreferencesKey("single_connection")
        val STREAM_PULSE = booleanPreferencesKey("stream_pulse")
        val RENAME_TO_VIDEO_EXT = booleanPreferencesKey("rename_to_video_ext")
        val STREAM_FAKE_AVI = booleanPreferencesKey("stream_fake_avi")
        val LOAD_REMOTE_VIDEO_THUMBNAILS = booleanPreferencesKey("load_remote_video_thumbnails")
    }

    val settings: Flow<DownloadSettings> = context.settingsStore.data.map { p ->
        DownloadSettings(
            chunkSize = p[Keys.CHUNK_SIZE] ?: (4L * 1024 * 1024),
            maxConnections = p[Keys.MAX_CONNECTIONS] ?: 4,
            maxRetries = p[Keys.MAX_RETRIES] ?: 3,
            connectTimeoutSec = p[Keys.CONNECT_TIMEOUT] ?: 30,
            readTimeoutSec = p[Keys.READ_TIMEOUT] ?: 60,
            singleConnectionMode = p[Keys.SINGLE_CONNECTION] ?: false,
            streamPulseMode = p[Keys.STREAM_PULSE] ?: false,
            renameToVideoExt = p[Keys.RENAME_TO_VIDEO_EXT] ?: true,
            streamFakeAvi = p[Keys.STREAM_FAKE_AVI] ?: false,
            loadRemoteVideoThumbnails = p[Keys.LOAD_REMOTE_VIDEO_THUMBNAILS] ?: false
        )
    }

    suspend fun update(transform: (DownloadSettings) -> DownloadSettings) {
        context.settingsStore.edit { p ->
            val current = DownloadSettings(
                chunkSize = p[Keys.CHUNK_SIZE] ?: (4L * 1024 * 1024),
                maxConnections = p[Keys.MAX_CONNECTIONS] ?: 4,
                maxRetries = p[Keys.MAX_RETRIES] ?: 3,
                connectTimeoutSec = p[Keys.CONNECT_TIMEOUT] ?: 30,
                readTimeoutSec = p[Keys.READ_TIMEOUT] ?: 60,
                singleConnectionMode = p[Keys.SINGLE_CONNECTION] ?: false,
                streamPulseMode = p[Keys.STREAM_PULSE] ?: false,
                renameToVideoExt = p[Keys.RENAME_TO_VIDEO_EXT] ?: true,
                streamFakeAvi = p[Keys.STREAM_FAKE_AVI] ?: false,
                loadRemoteVideoThumbnails = p[Keys.LOAD_REMOTE_VIDEO_THUMBNAILS] ?: false
            )
            val updated = transform(current)
            p[Keys.CHUNK_SIZE] = updated.chunkSize
            p[Keys.MAX_CONNECTIONS] = updated.maxConnections
            p[Keys.MAX_RETRIES] = updated.maxRetries
            p[Keys.CONNECT_TIMEOUT] = updated.connectTimeoutSec
            p[Keys.READ_TIMEOUT] = updated.readTimeoutSec
            p[Keys.SINGLE_CONNECTION] = updated.singleConnectionMode
            p[Keys.STREAM_PULSE] = updated.streamPulseMode
            p[Keys.RENAME_TO_VIDEO_EXT] = updated.renameToVideoExt
            p[Keys.STREAM_FAKE_AVI] = updated.streamFakeAvi
            p[Keys.LOAD_REMOTE_VIDEO_THUMBNAILS] = updated.loadRemoteVideoThumbnails
        }
    }
}
