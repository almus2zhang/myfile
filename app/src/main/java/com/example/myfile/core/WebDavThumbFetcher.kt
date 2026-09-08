package com.example.myfile.core

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.example.myfile.model.FileEntry
import com.example.myfile.model.WebDavAccount
import okio.buffer
import okio.source

data class WebDavThumbRequest(
    val account: WebDavAccount,
    val entry: FileEntry
)

class WebDavThumbFetcher(
    private val data: WebDavThumbRequest,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val ext = data.entry.name.substringAfterLast('.', "").lowercase()
        val file = when {
            ext == "apk" -> ThumbnailManager.getOrFetchWebDavApkThumb(
                options.context,
                data.account,
                data.entry
            )
            FileOpener.isVideo(data.entry.name) -> ThumbnailManager.getOrFetchWebDavVideoThumb(
                options.context,
                data.account,
                data.entry
            )
            else -> ThumbnailManager.getOrFetchWebDavImageThumb(
                options.context,
                data.account,
                data.entry
            )
        } ?: return null

        val source = file.source().buffer()
        return SourceResult(
            source = ImageSource(source = source, context = options.context),
            mimeType = if (file.name.endsWith(".png")) "image/png" else "image/jpeg",
            dataSource = DataSource.DISK
        )
    }

    class Factory : Fetcher.Factory<WebDavThumbRequest> {
        override fun create(data: WebDavThumbRequest, options: Options, imageLoader: ImageLoader): Fetcher {
            return WebDavThumbFetcher(data, options)
        }
    }
}
