package io.github.handshakehackin.nuviomaxd.androidtv.update

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ArtifactVerifierTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "test APK bytes".toByteArray()
    private fun release() = AndroidRelease(2, "0.2.0", 26, "", MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }, bytes.size.toLong(), "")

    @Test fun onlyAFullyVerifiedDownloadIsPublishedAsAnApk() {
        val directory = temporary.newFolder()
        val file = ArtifactVerifier.download(bytes.inputStream(), directory, release())
        assertTrue(file.name.endsWith(".apk"))
        assertArrayEquals(bytes, file.readBytes())
        ArtifactVerifier.verify(file, release())
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun corruptTruncatedAndOversizedDownloadsLeaveNoInstallableFiles() {
        listOf(bytes + byteArrayOf(0), bytes.dropLast(1).toByteArray(), ByteArray(bytes.size) { 42 }).forEach { data ->
            val directory = temporary.newFolder()
            assertThrows(UpdateException::class.java) { ArtifactVerifier.download(data.inputStream(), directory, release()) }
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun anApkChangedAfterDownloadFailsThePreInstallCheck() {
        val file = ArtifactVerifier.download(bytes.inputStream(), temporary.newFolder(), release())
        file.writeBytes(ByteArray(bytes.size) { 42 })
        assertThrows(UpdateException::class.java) { ArtifactVerifier.verify(file, release()) }
    }

    @Test fun leavingDuringADownloadCleansUpPartialFiles() {
        val directory = temporary.newFolder()
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { ArtifactVerifier.download(bytes.inputStream(), directory, release()) }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { Thread.interrupted() }
    }
}
