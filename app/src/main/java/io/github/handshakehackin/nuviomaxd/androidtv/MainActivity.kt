package io.github.handshakehackin.nuviomaxd.androidtv

import android.content.Intent
import io.github.handshakehackin.nuviomaxd.androidtv.core.CoreBridge
import org.json.JSONObject
import java.net.URI

class MainActivity : TvActivity() {
    private var checkedChangelog=false
    override fun onResume(){super.onResume();if(ready)render()}
    override fun render(){
        screen("Nuvio Max'd · Android TV",false)
        text("${BuildConfig.VERSION_NAME} · ${if(CoreBridge.signedIn)"Nuvio account" else "Guest profile"}")
        button("Browse catalogs"){go(BrowserActivity::class.java)}
        button("Search movies & series"){form("Search",listOf("Title" to false)){values->go(BrowserActivity::class.java,JSONObject().put("screen","search").put("query",values[0]))}}
        button("Library & Continue Watching"){go(BrowserActivity::class.java,JSONObject().put("screen","library"))}
        button("Addons & JavaScript providers"){go(AddonsActivity::class.java)}
        button("Live TV"){go(LiveTvActivity::class.java)}
        button("IPTV movies & series"){go(LiveTvActivity::class.java,JSONObject().put("screen","iptv"))}
        button("Plex · Jellyfin · Emby"){go(MediaServersActivity::class.java)}
        if(CoreBridge.settings.optBoolean("torrents"))button("Torrents & Web Drop"){go(TorrentsActivity::class.java)}
        button(if(CoreBridge.signedIn)"Account & profiles" else "Sign in to Nuvio"){go(AccountsActivity::class.java)}
        button("Connected services & trackers"){go(AccountsActivity::class.java,JSONObject().put("screen","services"))}
        button("Play a link"){form("Play an HTTP(S) video link",listOf("Video URL" to false)){values->
            if(validStream(values[0]))play(JSONObject().put("url",values[0]).put("name","Direct video").put("type","direct"))else message("Enter a valid HTTP or HTTPS video link.")
        }}
        button("Settings"){go(SettingsActivity::class.java)}
        button("App updates"){startActivity(Intent(this,UpdateActivity::class.java))}
        button("About · What's new · Diagnostics"){go(SettingsActivity::class.java,JSONObject().put("screen","about"))}
        if(!checkedChangelog){
            checkedChangelog=true
            val seen=getSharedPreferences("app-news",MODE_PRIVATE)
            if(seen.getInt("version",0)!=BuildConfig.VERSION_CODE){
                seen.edit().putInt("version",BuildConfig.VERSION_CODE).apply()
                message(assets.open("release-notes.txt").bufferedReader().use{it.readText()})
            }
        }
    }
}

internal fun validStream(value: String): Boolean = try {
    val uri = URI(value)
    uri.scheme in listOf("https","http") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
} catch (_: Exception) { false }
