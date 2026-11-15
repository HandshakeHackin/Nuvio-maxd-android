package io.github.handshakehackin.nuviomaxd.androidtv.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

object ApkInstaller {
    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, release: AndroidRelease) {
        ArtifactVerifier.verify(file, release)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw UpdateException("The download is not a valid APK.")
        if (archive.packageName != ReleasePolicy.APPLICATION_ID || archive.packageName != context.packageName) {
            throw UpdateException("The APK belongs to a different app.")
        }
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        if (archiveCode != release.versionCode || archive.versionName != release.versionName || archiveCode <= installedCode) {
            throw UpdateException("The APK version does not match a newer Android TV release.")
        }
        if (archive.applicationInfo?.minSdkVersion != release.minSdk || release.minSdk > Build.VERSION.SDK_INT) {
            throw UpdateException("This APK does not support this Android version.")
        }
        val installedSigners = signers(installed)
        if (installedSigners.isEmpty() || installedSigners != signers(archive)) {
            throw UpdateException("The APK signing certificate does not match this installation.")
        }
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.sorted()
    }

    fun intent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("Android TV update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
