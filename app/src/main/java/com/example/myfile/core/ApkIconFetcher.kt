package com.example.myfile.core

import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ApkIconFetcher(
    private val apkFile: File,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() <= 0) return@withContext null
        try {
            val context = options.context
            val pm = context.packageManager
            val pi = pm.getPackageArchiveInfo(apkFile.absolutePath, 0) ?: return@withContext null
            val appInfo = pi.applicationInfo ?: return@withContext null
            appInfo.sourceDir = apkFile.absolutePath
            appInfo.publicSourceDir = apkFile.absolutePath
            val drawable = appInfo.loadIcon(pm) ?: return@withContext null
            DrawableResult(
                drawable = drawable,
                isSampled = false,
                dataSource = DataSource.DISK
            )
        } catch (_: Exception) {
            null
        }
    }

    class Factory : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.name.endsWith(".apk", ignoreCase = true)) {
                return ApkIconFetcher(data, options)
            }
            return null
        }
    }

    class StringFactory : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.endsWith(".apk", ignoreCase = true) && (data.startsWith("/") || data.startsWith("file://"))) {
                val path = data.removePrefix("file://")
                val f = File(path)
                return ApkIconFetcher(f, options)
            }
            return null
        }
    }
}
