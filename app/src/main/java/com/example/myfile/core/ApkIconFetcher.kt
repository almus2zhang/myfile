package com.example.myfile.core

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import java.io.File

class ApkIconFetcher(
    private val apkFile: File,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val thumbFile = ThumbnailManager.getOrFetchLocalApkThumb(options.context, apkFile) ?: return@withContext null
        val source = thumbFile.source().buffer()
        SourceResult(
            source = ImageSource(source = source, context = options.context),
            mimeType = "image/png",
            dataSource = DataSource.DISK
        )
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
