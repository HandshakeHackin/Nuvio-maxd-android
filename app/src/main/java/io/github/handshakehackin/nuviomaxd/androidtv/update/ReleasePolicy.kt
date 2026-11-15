package io.github.handshakehackin.nuviomaxd.androidtv.update

import org.json.JSONObject
import java.net.URI

class UpdateException(message: String) : Exception(message)

data class AndroidRelease(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
)

object ReleasePolicy {
    const val APPLICATION_ID = "io.github.handshakehackin.nuviomaxd.androidtv"
    const val REPOSITORY = "HandshakeHackin/Nuvio-maxd-android"
    const val FEED_URL = "https://raw.githubusercontent.com/$REPOSITORY/main/updates/android-tv/stable.json"
    const val MAX_APK_BYTES = 256L * 1024 * 1024
    const val MAX_FEED_BYTES = 64 * 1024

    fun parse(text: String): AndroidRelease? {
        try {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_FEED_BYTES)
            val root = JSONObject(text)
            require(root.get("schemaVersion") == 1)
            require(root.getString("platform") == "android-tv")
            require(root.getString("applicationId") == APPLICATION_ID)
            require(root.getString("channel") == "stable")
            require(root.has("latest"))
            if (root.isNull("latest")) return null
            val latest = root.getJSONObject("latest")
            val code = integer(latest, "versionCode")
            val sdk = integer(latest, "minSdk")
            val size = integer(latest, "sizeBytes")
            val version = latest.getString("versionName")
            val hash = latest.getString("sha256")
            val url = latest.getString("apkUrl")
            val notes = latest.optString("notes", "")
            require(code in 1..2_100_000_000L)
            require(sdk in 26..1000)
            require(size in 1..MAX_APK_BYTES)
            require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
            require(hash.matches(Regex("[a-fA-F0-9]{64}")))
            require(notes.length <= 8000)
            val uri = URI(url)
            require(uri.scheme == "https" && uri.host == "github.com")
            require(uri.port == -1 && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            require(uri.rawPath == "/$REPOSITORY/releases/download/android-tv-v$version/NuvioMaxd-AndroidTV-$version.apk")
            return AndroidRelease(code, version, sdk.toInt(), url, hash.lowercase(), size, notes)
        } catch (_: Exception) {
            throw UpdateException("The feed is not a valid Android TV stable release.")
        }
    }

    private fun integer(json: JSONObject, key: String): Long {
        val value = json.get(key)
        require(value is Int || value is Long)
        return (value as Number).toLong()
    }

    fun isNewer(release: AndroidRelease, installedVersionCode: Long) = release.versionCode > installedVersionCode

    fun acceptsAssetRedirect(uri: URI): Boolean {
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1 || uri.rawFragment != null) return false
        return uri.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }
}
