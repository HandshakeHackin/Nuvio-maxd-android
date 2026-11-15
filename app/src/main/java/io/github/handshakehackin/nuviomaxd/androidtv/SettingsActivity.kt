package io.github.handshakehackin.nuviomaxd.androidtv

import android.app.AlertDialog
import android.content.Intent
import android.view.KeyEvent
import io.github.handshakehackin.nuviomaxd.androidtv.core.*
import org.json.JSONObject

class SettingsActivity:TvActivity(){
    override fun render(){if(payload.optString("screen")=="about")about()else settings()}
    private fun settings(){
        screen("Settings")
        val query=payload.optString("query")
        button("Search settings"){form("Search settings",listOf("Name or category" to false)){values->payload.put("query",values[0]);render()}}
        button("Remote button mapping"){remoteMapping()}
        button("Account & services"){go(AccountsActivity::class.java)}
        button("Connected services & trackers"){go(AccountsActivity::class.java,JSONObject().put("screen","services"))}
        button("App updates"){startActivity(Intent(this,UpdateActivity::class.java))}
        button("Subtitle appearance preview"){subtitlePreview()}
        work("settings"){
            Preferences.all.filter{query.isBlank()||it.name.contains(query,true)||it.category.contains(query,true)||it.key.contains(query,true)}.forEach{preference->
                button("${preference.category} · ${preference.name}: ${Preferences.label(preference)}"){
                    choose(preference.name,preference.options.map{it.first}){index->
                        work("settings.save",JSONObject().put("settings",JSONObject().put(preference.key,preference.options[index].second))){render()}
                    }
                }
            }
        }
    }
    private fun remoteMapping(){
        val dialog=AlertDialog.Builder(this).setTitle("Press a remote button")
            .setMessage("Only buttons Android delivers to Max'd can be mapped. The TV may intercept system buttons.")
            .setNegativeButton(android.R.string.cancel,null)
            .setNeutralButton("Reset mappings"){_,_->work("settings.save",JSONObject().put("settings",JSONObject().put("remoteMappings",JSONObject()))){message("Remote mappings reset.")}}.create()
        dialog.setOnKeyListener{_,key,event->
            if(event.action==KeyEvent.ACTION_UP){
                dialog.dismiss()
                val actions=listOf("playPause","seekBack","seekForward","longBack","longForward","audio","subtitles","details","back")
                choose("${KeyEvent.keyCodeToString(key)} action",actions){index->
                    val mappings=CoreBridge.settings.optJSONObject("remoteMappings")?:JSONObject();mappings.put(key.toString(),actions[index])
                    work("settings.save",JSONObject().put("settings",JSONObject().put("remoteMappings",mappings))){message("Remote mapping saved.")}
                }
            };true
        };dialog.show()
    }
    private fun about(){
        screen("About Nuvio Max'd")
        text("Android TV ${BuildConfig.VERSION_NAME} · Build ${BuildConfig.VERSION_CODE}\nUnofficial client · GPL-3.0\nAndroid and PS5 releases are independent.")
        button("What's new"){message(assets.open("release-notes.txt").bufferedReader().use{it.readText()})}
        button("Diagnostics"){
            val report="Android API ${android.os.Build.VERSION.SDK_INT}\nDevice: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nVersion: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nPackage: $packageName\nPlayback engine: ${CoreBridge.settings.optString("engine","media3")}\n\nCodec and display details are available during playback.\nNo passwords, account tokens, authorization headers or stream links are included."
            val fullReport=report+"\n\nRecent playback events:\n"+Diagnostics.report()
            choose("Diagnostics",listOf("View report","Share report")){index->if(index==0)message(fullReport)else startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,fullReport)},"Share diagnostics"))}
        }
        button("Open-source licenses"){
            val names=listOf("GPL-3.0.txt","Apache-2.0.txt","Provider-runtime.txt","Native-dependencies.txt","LGPL-2.1.txt")
            choose("Licenses",listOf("Max'd GPL-3.0","Apache-2.0","JavaScript provider dependencies","Native service dependencies","VLC LGPL-2.1")){index->message(assets.open("licenses/${names[index]}").bufferedReader().use{it.readText()})}
        }
        button("Support Max'd · Ko-fi"){
            qr("Optional support · Ko-fi","https://ko-fi.com/handshakehackin")
        }
        text("Android playback uses Media3 or VLC. PS5 console lights, PS5 decoder/EVO and the console installer are hardware-specific; Android uses its display, audio and APK installation APIs.")
    }
    private fun subtitlePreview(){
        val settings=CoreBridge.settings
        val view=android.widget.TextView(this).apply{
            text="Subtitle preview · العربية · 日本語\nThe next episode is ready."
            textSize=24f*settings.optInt("subtitleSize",100)/100f
            setPadding(dp(32),dp(32),dp(32),dp(32));gravity=android.view.Gravity.CENTER
            setTextColor(when(settings.optString("subtitleColor")){"Yellow"->android.graphics.Color.YELLOW;"Cyan"->android.graphics.Color.CYAN;"Green"->android.graphics.Color.GREEN;else->android.graphics.Color.WHITE})
            setBackgroundColor(if(settings.optBoolean("subtitleBackground"))0xcc000000.toInt()else 0xff243040.toInt())
            typeface=when(settings.optString("subtitleFont")){"Serif"->android.graphics.Typeface.SERIF;"Monospace"->android.graphics.Typeface.MONOSPACE;"Sans"->android.graphics.Typeface.SANS_SERIF;else->android.graphics.Typeface.DEFAULT}
            when(settings.optString("subtitleOutline","Outline")){"Shadow"->setShadowLayer(4f,2f,2f,android.graphics.Color.BLACK);"Outline"->setShadowLayer(3f,0f,0f,android.graphics.Color.BLACK)}
        }
        AlertDialog.Builder(this).setTitle("Subtitle preview · ${settings.optInt("subtitleSize",100)}%")
            .setView(android.widget.ScrollView(this).apply{addView(view)})
            .setMessage("Size, font, colour and background use your subtitle preferences. Embedded ASS styles can override these defaults.")
            .setPositiveButton(android.R.string.ok,null).show()
    }
}
