package io.github.handshakehackin.nuviomaxd.androidtv.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

class UpdateClient {
    fun check(): AndroidRelease? {
        val connection = open(URI(ReleasePolicy.FEED_URL), feed = true)
        try {
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (bytes.size() + read > ReleasePolicy.MAX_FEED_BYTES) throw UpdateException("The Android TV update feed is too large.")
                    bytes.write(buffer, 0, read)
                }
            }
            return ReleasePolicy.parse(bytes.toString("UTF-8"))
        } finally { connection.disconnect() }
    }

    fun download(release: AndroidRelease, directory: File, progress: (Int) -> Unit): File {
        val connection = open(URI(release.apkUrl), feed = false)
        try {
            val length = connection.contentLengthLong
            if (length >= 0 && length != release.sizeBytes) throw UpdateException("The APK size does not match the release.")
            var lastProgress = 0L
            return connection.inputStream.use { input ->
                ArtifactVerifier.download(input, directory, release) { percentage ->
                    val now = System.nanoTime()
                    if (now - lastProgress > 250_000_000L || percentage == 100) {
                        lastProgress = now
                        progress(percentage)
                    }
                }
            }
        } finally { connection.disconnect() }
    }

    private fun open(initial: URI, feed: Boolean): HttpURLConnection {
        var uri = initial
        repeat(5) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", if (feed) "application/json" else "application/octet-stream")
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                val status = connection.responseCode
                if (status == 200) return connection
                if (!feed && status in setOf(301, 302, 303, 307, 308)) {
                    val next = uri.resolve(connection.getHeaderField("Location") ?: throw UpdateException("The APK redirect is invalid."))
                    if (!ReleasePolicy.acceptsAssetRedirect(next)) throw UpdateException("The APK redirected outside GitHub release storage.")
                    uri = next
                } else {
                    throw UpdateException(if (status == 404 && feed) "The Android TV update feed is not available yet." else "The Android TV update server could not complete the request.")
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        throw UpdateException("The APK server returned too many redirects.")
    }
}
