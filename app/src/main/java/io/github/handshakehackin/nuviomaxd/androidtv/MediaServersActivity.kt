package io.github.handshakehackin.nuviomaxd.androidtv

import io.github.handshakehackin.nuviomaxd.androidtv.core.objects
import io.github.handshakehackin.nuviomaxd.androidtv.core.posterRow
import org.json.JSONObject

class MediaServersActivity:TvActivity(){
    override fun render(){if(payload.has("server"))browse()else servers()}
    private fun servers(){
        screen("Plex · Jellyfin · Emby")
        button("Connect Plex with your phone"){work("plex.start"){value->val code=obj(value)
            pair("Plex",code.optString("url"),code.optString("code"),5,code.optInt("expires",600),"plex.poll"){result->
                val items=result.optJSONArray("servers")?.objects().orEmpty()
                choose("Plex servers",items.map{it.optString("name")}){index->work("plex.connect",JSONObject().put("server",items[index].optString("id"))){render()}}
            }
        }}
        listOf("jellyfin" to "Jellyfin","emby" to "Emby").forEach{(kind,name)->button("Connect $name"){
            form(name,listOf("Server URL" to false,"Username" to false,"Password" to true)){values->
                work("servers.add",JSONObject().put("kind",kind).put("url",values[0]).put("username",values[1]).put("password",values[2])){render()}
            }
        }}
        work("servers"){value->arr(value).objects().forEach{server->button("${server.optString("name")} · ${server.optString("kind")}"){
            choose("Media server",listOf("Browse library","Continue watching","Search","Disconnect on this TV")){action->
                val route=JSONObject().put("server",server.optString("id")).put("name",server.optString("name"))
                when(action){
                    0->go(MediaServersActivity::class.java,route.put("type","views"))
                    1->go(MediaServersActivity::class.java,route.put("type","resume"))
                    2->form("Search server",listOf("Title" to false)){values->go(MediaServersActivity::class.java,route.put("type","items").put("query",values[0]))}
                    3->work("servers.remove",route){render()}
                }
            }
        }}}
    }
    private fun browse(){
        screen(payload.optString("name","Media server"))
        work("servers.browse",payload){value->val result=obj(value);val items=result.optJSONArray("Items")?.objects().orEmpty()
            items.forEach{item->val type=item.optString("Type")
                val folder=item.optBoolean("IsFolder")||type in listOf("CollectionFolder","Folder","Series","Season","BoxSet")
                posterRow(body,item.optString("Name","Untitled"),item.optString("Overview").take(140),server=payload.optString("server"),id=item.optString("Id")){
                    if(folder)go(MediaServersActivity::class.java,JSONObject(payload.toString()).put("parent",item.optString("Id")).put("type",if(type=="Series")"episodes" else "items").put("offset",0))
                    else choose(item.optString("Name"),listOf("Play original","Try compatible server stream")){mode->
                        work("servers.play",JSONObject().put("server",payload.optString("server")).put("id",item.optString("Id")).put("name",item.optString("Name")).put("compatible",mode==1)
                            .put("resume",(item.optJSONObject("UserData")?.optDouble("PlaybackPositionTicks")?:0.0)/10_000_000.0).put("duration",item.optDouble("RunTimeTicks",0.0)/10_000_000.0)){play(obj(it))}
                    }
                }
            }
            val offset=payload.optInt("offset")
            if(offset+items.size<result.optInt("TotalRecordCount"))button("More titles ›"){go(MediaServersActivity::class.java,JSONObject(payload.toString()).put("offset",offset+items.size))}
        }
    }
}
