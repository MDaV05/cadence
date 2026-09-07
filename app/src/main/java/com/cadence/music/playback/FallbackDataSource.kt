package com.cadence.music.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Media3 DataSource wrapper that catches connection/IO failures on a primary server stream
 * and immediately attempts the request against an alternate / secondary server URL.
 */
class FallbackDataSource(
    private val upstream: DataSource,
    private val resolveFallback: (Uri) -> Uri?,
    private val onFallbackUsed: ((from: Uri, to: Uri) -> Unit)? = null,
) : DataSource {

    private var activeSource: DataSource = upstream

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        activeSource = upstream
        return try {
            upstream.open(dataSpec)
        } catch (e: IOException) {
            runCatching { upstream.close() }
            val fallbackUri = resolveFallback(dataSpec.uri)
            if (fallbackUri != null && fallbackUri != dataSpec.uri) {
                val fallbackSpec = dataSpec.buildUpon().setUri(fallbackUri).build()
                val bytes = upstream.open(fallbackSpec)
                onFallbackUsed?.invoke(dataSpec.uri, fallbackUri)
                bytes
            } else {
                throw e
            }
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return activeSource.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = activeSource.uri

    override fun getResponseHeaders(): Map<String, List<String>> = activeSource.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        activeSource.close()
    }

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val resolveFallback: (Uri) -> Uri?,
        private val onFallbackUsed: ((from: Uri, to: Uri) -> Unit)? = null,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            FallbackDataSource(upstreamFactory.createDataSource(), resolveFallback, onFallbackUsed)
    }
}
