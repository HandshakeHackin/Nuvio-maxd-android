package io.github.handshakehackin.nuviomaxd.androidtv

import io.github.handshakehackin.nuviomaxd.androidtv.core.*
import org.json.JSONArray
import org.json.JSONObject

internal object MultiSelection {
    private var profile=""
    val streams=mutableListOf<JSONObject>()
    fun reset(){profile=CoreBridge.profileKey;streams.clear()}
    fun add(value:JSONObject){if(profile!=CoreBridge.profileKey)reset();if(streams.size<4)streams.add(value)}
}

class LiveTvActivity:TvActivity(){
    override fun render(){when(payload.optString("screen")){"channels"->channels();"vod"->vod();"vodCatalog"->vodCatalog();else->sources()}}
    private fun sources(){
        val vod=payload.optString("screen")=="iptv"
        screen(if(vod)"IPTV Library" else "Live TV sources")
        button("Add M3U playlist"){form("M3U playlist",listOf("Source name" to false,"Playlist URL" to false)){values->
            work("live.add",JSONObject().put("source",JSONObject().put("type","m3u").put("name",values[0]).put("url",values[1]))){render()}
        }}
        button("Add Xtream Codes server"){form("Xtream Codes",listOf("Source name" to false,"Server URL" to false,"Username" to false,"Password" to true)){values->
            work("live.add",JSONObject().put("source",JSONObject().put("type","xtream").put("name",values[0]).put("url",values[1]).put("username",values[2]).put("password",values[3]))){render()}
        }}
        if(vod)button("Search saved IPTV catalogs"){form("Search saved catalogs",listOf("Title" to false)){values->work("iptv.search",JSONObject().put("query",values[0])){value->
            val rows=arr(value).objects();choose("Saved IPTV results",rows.map{"${it.optJSONObject("entry")?.optString("name")} · ${it.optString("provider")}"}){index->val hit=rows[index]
                if(hit.optString("kind")=="series")go(LiveTvActivity::class.java,JSONObject().put("screen","vodCatalog").put("source",hit.optString("source")).put("kind","episodes").put("category",hit.optJSONObject("entry")!!.optString("id")))
                else work("iptv.play",JSONObject().put("source",hit.optString("source")).put("kind",hit.optString("kind")).put("entry",hit.optJSONObject("entry"))){play(obj(it))}
            }
        }}}
        else button("Recent channels"){work("live.recent"){value->val rows=arr(value).objects();choose("Recent channels",rows.map{it.optString("name")}){index->playChannel(rows[index].optString("source"),rows[index].optString("id"))}}}
        work("live.sources"){value->arr(value).objects().forEach{source->button(source.optString("name")){
            go(LiveTvActivity::class.java,JSONObject().put("screen",if(vod)"vod" else "channels").put("source",source.optString("id")).put("name",source.optString("name")))
        }}}
    }
    private fun channels(){
        val id=payload.optString("source");screen(payload.optString("name","Live TV"))
        button("Search channels"){form("Channel search",listOf("Name" to false)){values->payload.put("query",values[0]).put("offset",0);render()}}
        button("Reload channels"){payload.put("refresh",true);render()}
        button("Source options"){choose("Source options",listOf("Guide URL","Guide on / off","IPTV movies & shows","Start four-screen selection","Remove source")){action->when(action){
            0->form("XMLTV guide",listOf("Guide URL (empty clears manual URL)" to false)){values->work("live.guide.save",JSONObject().put("source",id).put("url",values[0]).put("enabled",true)){message("Guide URL saved.")}}
            1->choose("Guide",listOf("On","Off")){choice->work("live.guide.save",JSONObject().put("source",id).put("enabled",choice==0)){message("Guide preference saved.")}}
            2->go(LiveTvActivity::class.java,JSONObject(payload.toString()).put("screen","vod"))
            3->{MultiSelection.reset();message("Add four channels using each channel's options. Each stream uses a provider connection. SD channels are recommended.")}
            4->confirm("Remove this source?"){work("live.remove",JSONObject().put("source",id)){finish()}}
        }}}
        if(MultiSelection.streams.size==4)button("Open four-screen TV"){openMulti()}
        work("live.channels",payload){value->val result=obj(value);payload.put("refresh",false)
            val groups=listOf("All","Favorites")+result.optJSONArray("groups")?.strings().orEmpty().filter{it.isNotBlank()}
            button("Group: ${payload.optString("group").ifBlank{"All"}}"){choose("Channel groups",groups){index->payload.put("group",if(index==0)"" else groups[index]).put("offset",0);render()}}
            result.optJSONArray("channels")?.objects()?.forEach{channel->button("${if(channel.optBoolean("favorite"))"★ " else ""}${channel.optString("name")}"){
                choose(channel.optString("name"),listOf("Watch","Now / Next","Favorite / unfavorite","Add to four-screen TV")){action->val args=JSONObject().put("source",id).put("id",channel.optString("id"))
                    when(action){
                        0->playChannel(id,channel.optString("id"))
                        1->work("live.guide",args){rows->message(arr(rows).objects().joinToString("\n\n"){program->java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(program.optLong("start")*1000))+" · "+program.optString("title")}.ifBlank{"No current guide information."})}
                        2->work("live.favorite",args){render()}
                        3->work("live.play",args){stream->MultiSelection.add(obj(stream));if(MultiSelection.streams.size==4)openMulti()else message("Added ${MultiSelection.streams.size} of 4 channels.")}
                    }
                }
            }}
            val offset=payload.optInt("offset")
            if(offset+250<result.optInt("total"))button("More channels ›"){go(LiveTvActivity::class.java,JSONObject(payload.toString()).put("offset",offset+250))}
        }
    }
    private fun openMulti(){go(MultiViewActivity::class.java,JSONObject().put("profileKey",CoreBridge.profileKey).put("streams",JSONArray(MultiSelection.streams)))}
    private fun playChannel(source:String,id:String){work("live.play",JSONObject().put("source",source).put("id",id)){play(obj(it))}}
    private fun vod(){
        screen("IPTV · ${payload.optString("name")}")
        work("iptv.probe",payload){value->val result=obj(value)
            if(result.optBoolean("cached"))text("Showing saved categories while the provider is unavailable.")
            listOf("movies" to "Movies","series" to "Series").forEach{(kind,label)->val categories=result.optJSONArray(kind)?.objects().orEmpty()
                if(categories.isNotEmpty()){
                    button("All $label"){go(LiveTvActivity::class.java,JSONObject(payload.toString()).put("screen","vodCatalog").put("kind",kind).put("category",""))}
                    categories.forEach{category->button("$label · ${category.optString("name")}"){go(LiveTvActivity::class.java,JSONObject(payload.toString()).put("screen","vodCatalog").put("kind",kind).put("category",category.optString("id")))}}
                }
            }
        }
    }
    private fun vodCatalog(){
        val kind=payload.optString("kind","movies");screen("IPTV ${if(kind=="episodes")"Episodes" else kind}")
        button("Search this catalog"){form("IPTV search",listOf("Title" to false)){values->payload.put("query",values[0]).put("offset",0);render()}}
        button("A–Z"){val letters=listOf("All","#")+('A'..'Z').map{it.toString()};choose("Jump to letter",letters){index->payload.put("letter",if(index==0)"" else letters[index]).put("offset",0);render()}}
        work("iptv.catalog",payload){value->val result=obj(value)
            if(result.optBoolean("cached"))text("Showing the saved catalog while the provider is unavailable.")
            result.optJSONArray("entries")?.objects()?.forEach{entry->posterRow(body,(if(kind=="episodes")"S${entry.optInt("season")} E${entry.optInt("episode")} · " else "")+entry.optString("name"),entry.optString("year"),entry.optString("poster")){
                if(kind=="series")go(LiveTvActivity::class.java,JSONObject(payload.toString()).put("kind","episodes").put("category",entry.optString("id")).put("offset",0))
                else work("iptv.play",JSONObject().put("source",payload.optString("source")).put("kind",kind).put("entry",entry)){play(obj(it))}
            }}
            val offset=payload.optInt("offset")
            if(offset+250<result.optInt("total"))button("More titles ›"){go(LiveTvActivity::class.java,JSONObject(payload.toString()).put("offset",offset+250))}
        }
    }
}
