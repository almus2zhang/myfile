package com.example.myfile

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.myfile.core.download.DownloadManager
import com.example.myfile.data.db.AppDatabase
import com.example.myfile.data.local.LocalFileRepository
import com.example.myfile.data.prefs.DefaultAppStore
import com.example.myfile.data.prefs.DownloadSettings
import com.example.myfile.data.prefs.SettingsStore
import com.example.myfile.data.webdav.AccountStore
import com.example.myfile.data.webdav.WebDavClient
import com.example.myfile.data.webdav.WebDavRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

class MyApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(com.example.myfile.core.ApkIconFetcher.Factory())
                add(com.example.myfile.core.ApkIconFetcher.StringFactory())
                add(com.example.myfile.core.WebDavThumbFetcher.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(250L * 1024 * 1024) // 250 MB 缓存
                    .build()
            }
            .okHttpClient(okHttpClient)
            .crossfade(true)
            .build()
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var okHttpClient: OkHttpClient
        private set

    lateinit var db: AppDatabase
        private set

    lateinit var localRepo: LocalFileRepository
        private set

    lateinit var accountStore: AccountStore
        private set

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var defaultAppStore: DefaultAppStore
        private set

    lateinit var folderSortStore: com.example.myfile.data.prefs.FolderSortStore
        private set

    lateinit var webDavRepository: WebDavRepository
        private set

    lateinit var downloadManager: DownloadManager
        private set

    private val _currentSettings = MutableStateFlow(DownloadSettings())
    val currentSettings: kotlinx.coroutines.flow.StateFlow<DownloadSettings> = _currentSettings.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        instance = this

        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            override fun checkClientTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
        })
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS").apply {
            init(null, trustAll, java.security.SecureRandom())
        }

        okHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll[0] as javax.net.ssl.X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            // 短连接池：空闲 3 秒即关闭，避免长连接被运营商持续限速。
            // 每次下载尽量用新连接（新连接可能"抽到"未限速的路径），
            // 匹配其他客户端"随机时快时慢"的行为。
            .connectionPool(ConnectionPool(0, 3, TimeUnit.SECONDS))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // 强制 HTTP/1.1：Apache mod_dav + 部分 WebDAV 服务端在 HTTP/2 下
            // PROPFIND/OPTIONS 行为异常（会返回 404），与 RaiDrive/rclone 行为不一致。
            .protocols(listOf(Protocol.HTTP_1_1))
            // 关键：禁用 OkHttp 自动重定向跟随。PROPFIND 重定向时 OkHttp 会把方法
            // 改成 GET，导致服务端把 PROPFIND 当普通 GET 处理返回 404。
            .followRedirects(false)
            .followSslRedirects(false)
            // 挂载全局网络传输监视器，实时监控网速、请求来源与已传数据
            .addNetworkInterceptor(com.example.myfile.core.TrafficMonitor.interceptor)
            .build()

        db = AppDatabase.get(this)
        localRepo = LocalFileRepository(this)
        accountStore = AccountStore(this)
        settingsStore = SettingsStore(this)
        defaultAppStore = DefaultAppStore(this)
        folderSortStore = com.example.myfile.data.prefs.FolderSortStore(this)

        appScope.launch {
            settingsStore.settings.collect { _currentSettings.value = it }
        }

        webDavRepository = WebDavRepository(
            clientFactory = { account ->
                WebDavClient(okHttpClient, account.connectionUrl(), account.username, account.password)
            },
            accountStore = accountStore
        )

        downloadManager = DownloadManager(
            context = this,
            db = db,
            clientProvider = { account ->
                WebDavClient(okHttpClient, account.connectionUrl(), account.username, account.password)
            },
            settingsProvider = { _currentSettings.value }
        )
    }

    companion object {
        lateinit var instance: MyApp
            private set
    }
}
