package io.github.handshakehackin.nuviomaxd.androidtv.update

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object ArtifactVerifier {
    fun download(input: InputStream, directory: File, release: AndroidRelease, progress: (Int) -> Unit = {}): File {
        if (!directory.exists() && !directory.mkdirs()) throw UpdateException("Update storage is unavailable.")
        val partial = File.createTempFile("download-", ".part", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            val buffer = ByteArray(64 * 1024)
            partial.outputStream().use { output ->
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    if (count > release.sizeBytes || count > ReleasePolicy.MAX_APK_BYTES) {
                        throw UpdateException("The APK size does not match the release.")
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    progress((count * 100 / release.sizeBytes).toInt())
                }
                output.fd.sync()
            }
            if (count != release.sizeBytes || hex(digest.digest()) != release.sha256) {
                throw UpdateException("The APK failed checksum or size verification.")
            }
            val verified = File(directory, "android-tv-${release.versionCode}-${release.sha256.take(12)}.apk")
            if (!partial.renameTo(verified)) throw UpdateException("The verified APK could not be saved.")
            return verified
        } finally {
            partial.delete()
        }
    }

    fun verify(file: File, release: AndroidRelease) {
        if (!file.isFile || file.length() != release.sizeBytes) throw UpdateException("The downloaded APK has changed. Download it again.")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        if (hex(digest.digest()) != release.sha256) throw UpdateException("The downloaded APK has changed. Download it again.")
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
