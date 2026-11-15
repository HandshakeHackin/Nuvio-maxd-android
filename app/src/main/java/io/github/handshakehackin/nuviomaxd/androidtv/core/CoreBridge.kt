package io.github.handshakehackin.nuviomaxd.androidtv.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.Executors
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class CoreException(message: String) : Exception(message)

object CoreBridge {
    private var initialized = false
    val workers = Executors.newFixedThreadPool(4)
    private val state=ProfileState()
    val context:JSONObject get()=state.context
    val settings:JSONObject get()=state.settings
    val profileKey: String get() = context.optString("profileKey")
    val signedIn: Boolean get() = context.optBoolean("saved")

    init { System.loadLibrary("nuvio_core") }

    @Synchronized fun initialize(application: Context) {
        if (initialized) return
        val app = application.applicationContext
        val assetsDirectory = File(app.filesDir, "core").apply { mkdirs() }
        for (name in app.assets.list("core").orEmpty()) {
            val destination = File(assetsDirectory, name)
            val bytes = app.assets.open("core/$name").use { it.readBytes() }
            if (!destination.exists() || !destination.readBytes().contentEquals(bytes)) {
                val temporary = File(assetsDirectory, "$name.tmp")
                temporary.writeBytes(bytes)
                check(temporary.renameTo(destination)) { "Could not prepare the app resources." }
            }
        }
        // Native HTTPS uses the TV's trust store, including current Android root updates.
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val certificates = trust.trustManagers.filterIsInstance<X509TrustManager>().flatMap { it.acceptedIssuers.toList() }
        val caBundle = File(assetsDirectory, "android-ca.pem")
        caBundle.bufferedWriter().use { output ->
            for (certificate in certificates) {
                output.appendLine("-----BEGIN CERTIFICATE-----")
                output.appendLine(Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(certificate.encoded))
                output.appendLine("-----END CERTIFICATE-----")
            }
        }
        request("init", JSONObject().put("dataDir", File(app.filesDir, "nuvio").absolutePath)
            .put("assetsDir", assetsDirectory.absolutePath).put("caBundle", caBundle.absolutePath), false)
        initialized = true
        refreshSettings()
    }

    fun request(command: String, arguments: JSONObject = JSONObject(), scoped: Boolean = true): Any? {
        val payload = JSONObject(arguments.toString())
        if (scoped && !payload.has("profileKey")) payload.put("profileKey", profileKey)
        val envelope = JSONObject(nativeCall(command, payload.toString().toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8))
        val result = envelope.opt("result").takeUnless { it == JSONObject.NULL }
        envelope.optJSONObject("context")?.let { current ->
            val changed=current.optString("profileKey")!=profileKey||current.optString("profilePath")!=context.optString("profilePath")
            val settingsResult=if(command=="settings"||command=="settings.save")result as? JSONObject else null
            if(!state.accept(envelope.optLong("sequence"),current,settingsResult))throw CoreException("The active profile changed. Reload this screen.")
            if(changed&&command!="init"&&command!="settings"&&command!="settings.save")runCatching{refreshSettings()}
        }
        if (!envelope.optBoolean("ok")) throw CoreException(envelope.optString("error", "The request failed."))
        return result
    }

    fun objectResult(command: String, arguments: JSONObject = JSONObject()) = request(command, arguments) as? JSONObject ?: JSONObject()
    fun arrayResult(command: String, arguments: JSONObject = JSONObject()) = request(command, arguments) as? JSONArray ?: JSONArray()
    fun refreshSettings() = objectResult("settings")
    fun saveSetting(key: String, value: Any) = objectResult("settings.save", JSONObject().put("settings", JSONObject().put(key, value)))
    fun cancel() = nativeCancel()

    private external fun nativeCall(command: String, arguments: ByteArray): ByteArray
    private external fun nativeCancel()
    external fun nativeOpenTorrent(hash: String, index: Int, profileKey: String): LongArray
    external fun nativeReadTorrent(handle: Long, position: Long, length: Int): ByteArray
    external fun nativeCloseTorrent(handle: Long)
    external fun nativeCancelTorrentReads()
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { opt(it) as? String }
