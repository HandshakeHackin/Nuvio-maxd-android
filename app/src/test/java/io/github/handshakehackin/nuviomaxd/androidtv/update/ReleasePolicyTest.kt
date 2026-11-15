package io.github.handshakehackin.nuviomaxd.androidtv.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class ReleasePolicyTest {
    private fun feed(version: String = "0.2.0", code: Long = 2): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("platform", "android-tv")
        put("applicationId", ReleasePolicy.APPLICATION_ID)
        put("channel", "stable")
        put("latest", JSONObject().apply {
            put("versionCode", code)
            put("versionName", version)
            put("minSdk", 26)
            put("apkUrl", "https://github.com/${ReleasePolicy.REPOSITORY}/releases/download/android-tv-v$version/NuvioMaxd-AndroidTV-$version.apk")
            put("sha256", "a".repeat(64))
            put("sizeBytes", 1024)
            put("notes", "Android TV improvements")
        })
    }

    private fun rejected(json: JSONObject) {
        assertThrows(UpdateException::class.java) { ReleasePolicy.parse(json.toString()) }
    }

    @Test fun emptyChannelDoesNotAdvertiseAnUnpublishedApk() {
        val json = feed().put("latest", JSONObject.NULL)
        assertNull(ReleasePolicy.parse(json.toString()))
    }

    @Test fun aPs5ReleaseCannotBecomeAnAndroidUpdate() {
        rejected(feed().put("platform", "ps5"))
        rejected(feed().put("applicationId", "PPSA99288"))
        rejected(feed().apply { getJSONObject("latest").put("apkUrl", "https://github.com/HandshakeHackin/Nuvio-maxd-releases/releases/download/v1.0.0.11-native/PPSA99288.zip") })
    }

    @Test fun channelAndSchemaMustMatch() {
        rejected(feed().put("channel", "beta"))
        rejected(feed().put("schemaVersion", 2))
        rejected(feed().apply { remove("latest") })
    }

    @Test fun monotonicVersionCodesControlUpgrades() {
        val release = ReleasePolicy.parse(feed("0.9.0", 10).toString())!!
        assertTrue(ReleasePolicy.isNewer(release, 9))
        assertFalse(ReleasePolicy.isNewer(release, 10))
        assertFalse(ReleasePolicy.isNewer(release, 11))
        // A display version that looks newer never permits a numeric downgrade.
        assertFalse(ReleasePolicy.isNewer(ReleasePolicy.parse(feed("99.0.0", 1).toString())!!, 10))
    }

    @Test fun onlyTheExactAndroidRepositoryAndAssetAreAccepted() {
        val good = feed().getJSONObject("latest").getString("apkUrl")
        listOf(
            good.replace("https:", "http:"),
            good.replace("github.com", "github.com.evil.invalid"),
            good.replace("github.com", "user:password@github.com"),
            good.replace("github.com", "github.com:443"),
            good.replace("Nuvio-maxd-android", "Nuvio-maxd-releases"),
            good.replace("android-tv-v0.2.0", "v0.2.0"),
            good.replace("AndroidTV-0.2.0.apk", "AndroidTV-0.1.0.apk"),
            "$good?override=true", "$good#fragment",
            good.replace("/releases/", "/other/../releases/"),
        ).forEach { url -> rejected(feed().apply { getJSONObject("latest").put("apkUrl", url) }) }
    }

    @Test fun malformedOrUnboundedArtifactMetadataIsRejected() {
        listOf("versionCode" to 0, "versionCode" to 2.5, "versionCode" to "2", "minSdk" to 25,
            "sizeBytes" to 0, "sizeBytes" to ReleasePolicy.MAX_APK_BYTES + 1,
            "sha256" to "bad", "versionName" to "../../release"
        ).forEach { (key, value) -> rejected(feed().apply { getJSONObject("latest").put(key, value) }) }
        assertThrows(UpdateException::class.java) { ReleasePolicy.parse(" ".repeat(ReleasePolicy.MAX_FEED_BYTES + 1)) }
    }

    @Test fun downloadRedirectsStayOnHttpsGithubAssetStorage() {
        assertTrue(ReleasePolicy.acceptsAssetRedirect(URI("https://release-assets.githubusercontent.com/asset?signature=example")))
        listOf("http://release-assets.githubusercontent.com/asset", "https://evil.invalid/asset",
            "https://release-assets.githubusercontent.com.evil.invalid/asset", "https://raw.githubusercontent.com/asset",
            "https://user@objects.githubusercontent.com/asset", "https://objects.githubusercontent.com:444/asset"
        ).forEach { assertFalse(ReleasePolicy.acceptsAssetRedirect(URI(it))) }
    }
}
