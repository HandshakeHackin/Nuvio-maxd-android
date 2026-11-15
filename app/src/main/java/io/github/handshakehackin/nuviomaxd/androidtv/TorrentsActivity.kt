package io.github.handshakehackin.nuviomaxd.androidtv

import android.content.Intent
import io.github.handshakehackin.nuviomaxd.androidtv.core.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

class TorrentsActivity:TvActivity(){
    override fun render(){if(payload.has("input"))files()else torrents()}
    private fun torrents(){
        screen("Torrents & Web Drop")
        if(!CoreBridge.settings.optBoolean("torrents")){
            text("Peer-to-peer torrents are off. Enabling them lets this TV connect directly to torrent peers.")
            button("Enable peer-to-peer torrents"){work("settings.save",JSONObject().put("settings",JSONObject().put("torrents",true))){render()}};return
        }
        button("Open magnet / info hash / .torrent URL"){form("Open torrent",listOf("Magnet, info hash or torrent URL" to false)){values->go(TorrentsActivity::class.java,JSONObject().put("input",values[0]))}}
        button("Choose a .torrent file from storage"){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="*/*";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("application/x-bittorrent","application/octet-stream"))},42)}
        button("Web Drop · Send from phone / PC"){work("torrents.web.start"){value->
            pair("Web Drop · trusted Wi-Fi only",obj(value).optString("url"),"Confirm the received torrent on this TV before it starts.",2,600,"torrents.web.poll",cancelCommand="torrents.web.stop"){submission->
                confirm("Open ${submission.optString("name","the received torrent") }?"){go(TorrentsActivity::class.java,JSONObject().put("input",submission.optString("input")))}
            }
        }}
        button("Torrent cache & preferences"){go(SettingsActivity::class.java,JSONObject().put("query","torrent"))}
        work("torrents.recent"){value->arr(value).objects().forEach{item->button(item.optString("name",item.optString("hash"))){go(TorrentsActivity::class.java,JSONObject().put("input",item.optString("input",item.optString("hash"))))}}}
    }
    private fun files(){
        screen("Torrent files")
        val original=payload.optJSONObject("playback")
        work("torrents.start",JSONObject().put("input",payload.optString("input")).put("sources",original?.optJSONObject("stream")?.optJSONArray("sources")).put("season",original?.optInt("season")?:0).put("episode",original?.optInt("episode")?:0)){value->val result=obj(value)
            result.optJSONArray("files")?.objects()?.forEach{file->button("${file.optString("name")} · ${file.optLong("size")/1024/1024} MiB"){
                val playback=original?.let{JSONObject(it.toString())}?:JSONObject().put("type","torrent").put("name",file.optString("name")).put("id","torrent:${result.optString("hash")}:${file.optInt("index")}")
                playback.put("url","torrent://${result.optString("hash")}/${file.optInt("index")}").put("torrentHash",result.optString("hash")).put("torrentIndex",file.optInt("index"))
                play(playback)
            }}
        }
    }
    @Deprecated("Android TV document picker callback")override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=42||resultCode!=RESULT_OK)return
        val uri=data?.data?:return;val token=generation
        CoreBridge.workers.submit{
            try{
                val folder=File(filesDir,"nuvio/imports").apply{mkdirs()};val file=File(folder,"${UUID.randomUUID()}.torrent")
                try{contentResolver.openInputStream(uri)!!.use{input->file.outputStream().use{output->val buffer=ByteArray(8192);var count=0
                    while(true){val size=input.read(buffer);if(size<0)break;count+=size;if(count>4*1024*1024)throw CoreException("Torrent metadata exceeds 4 MiB.");output.write(buffer,0,size)}
                }}}catch(error:Exception){file.delete();throw error}
                main.post{if(alive(token))go(TorrentsActivity::class.java,JSONObject().put("input",file.absolutePath))}
            }catch(error:Exception){main.post{if(alive(token))message(if(error is CoreException)error.message.orEmpty()else"Could not read this torrent file.")}}
        }
    }
}
