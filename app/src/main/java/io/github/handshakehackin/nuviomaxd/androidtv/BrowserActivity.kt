package io.github.handshakehackin.nuviomaxd.androidtv

import io.github.handshakehackin.nuviomaxd.androidtv.core.*
import org.json.JSONArray
import org.json.JSONObject

class BrowserActivity : TvActivity() {
    private val titleLabels=mutableMapOf<String,Pair<android.widget.TextView,String>>()
    override fun render(){titleLabels.clear();when(payload.optString("screen")){
        "catalog"->catalog();"search"->search();"detail"->detail();"episodes"->episodes();"streams"->streams();"library"->library();else->catalogs()
    }}
    private fun navigate(value:JSONObject){go(BrowserActivity::class.java,value)}
    private fun catalogs(){
        screen("Browse catalogs")
        work("catalogs"){value->val rows=arr(value).objects()
            if(rows.isEmpty())text("Add an addon that supplies movie or series catalogs.")
            rows.forEach{catalog->button("${catalog.optString("name")} · ${catalog.optString("source")}"){navigate(JSONObject(catalog.toString()).put("screen","catalog"))}}
        }
    }
    private fun catalog(){
        screen(payload.optString("name","Catalog"))
        val genre=payload.optString("genre");val offset=payload.optInt("offset")
        val genres=payload.optJSONArray("extras")?.objects()?.firstOrNull{it.optString("name")=="genre"}?.optJSONArray("options")?.strings().orEmpty()
        if(genres.isNotEmpty())button("Genre: ${genre.ifBlank{"All"}}"){choose("Genre",listOf("All")+genres){index->payload.put("genre",if(index==0)"" else genres[index-1]).put("offset",0);render()}}
        work("catalog",payload){value->val rows=arr(value).objects();titleRows(rows)
            if(rows.isNotEmpty())button("More titles ›"){navigate(JSONObject(payload.toString()).put("offset",offset+rows.size))}
        }
    }
    private fun search(){screen("Search · ${payload.optString("query")}");work("search",payload){titleRows(arr(it).objects())}}
    private fun titleRows(rows:List<JSONObject>){
        if(rows.isEmpty()){text("No titles found.");return}
        rows.forEach{item->
            val id=item.optString("id",item.optString("_id"))
            val offset=item.optJSONObject("state")?.optLong("timeOffset")?:0L
            val extra=listOf(item.optString("releaseInfo",item.optString("year")),if(offset>0)"Resume ${offset/60000} min" else "").filter{it.isNotBlank()}.joinToString(" · ")
            val row=posterRow(body,item.optString("name","Untitled"),extra,item.optString("poster")){
                navigate(JSONObject().put("screen","detail").put("type",item.optString("type","movie")).put("id",item.optString("id",item.optString("_id"))))
            }
            val label=row.getChildAt(1)as android.widget.TextView;titleLabels[id]=label to label.text.toString()
        }
    }
    private fun detail(){
        screen("Title details")
        val id=payload.optString("id");val type=payload.optString("type","movie")
        work("detail",payload){value->val meta=obj(value)
            if(!meta.has("id"))meta.put("id",id);if(!meta.has("type"))meta.put("type",type)
            text(meta.optString("name","Untitled"));text(meta.optString("description"))
            text(listOf(meta.optString("releaseInfo"),meta.optString("runtime"),meta.optString("imdbRating")).filter{it.isNotBlank()}.joinToString(" · "))
            button("Save / remove on this TV"){
                work("library",JSONObject().put("sync",false)){valueRows->
                    val old=arr(valueRows).objects().firstOrNull{it.optString("_id")==id}
                    val item=old?:JSONObject().put("_id",id).put("type",type).put("name",meta.optString("name")).put("poster",meta.optString("poster"))
                    item.put("removed",old!=null&&!old.optBoolean("removed")).put("temp",false)
                    work("library.save",JSONObject().put("item",item)){message(if(item.optBoolean("removed"))"Removed from this TV's saved titles." else "Saved on this TV. Cloud Library membership is managed in Nuvio.")}
                }
            }
            button("Watch trailer"){work("trailers",JSONObject().put("meta",meta)){candidates->
                val trailers=arr(candidates).objects();choose("Trailers",trailers.map{it.optString("name","Trailer")}){index->
                    work("trailers.resolve",JSONObject().put("trailer",trailers[index])){play(obj(it).put("type","trailer"))}
                }
            }}
            val videos=meta.optJSONArray("videos")?.objects().orEmpty()
            if(type=="series"&&videos.isNotEmpty()){
                videos.map{it.optInt("season")}.distinct().sorted().forEach{season->button("Season $season"){
                    navigate(JSONObject(payload.toString()).put("screen","episodes").put("meta",meta).put("season",season))
                }}
            }else button("Choose a stream"){navigate(JSONObject().put("screen","streams").put("id",id).put("videoId",id).put("type",type).put("meta",meta).put("season",-1).put("episode",-1))}
            if(type!="series")watchedButton(id,id,meta.optString("name"),-1,-1)
        }
    }
    private fun episodes(){
        val meta=payload.optJSONObject("meta")?:JSONObject();val season=payload.optInt("season")
        screen("${meta.optString("name")} · Season $season")
        work("library",JSONObject().put("sync",false)){value->
            val progress=arr(value).objects().firstOrNull{it.optString("_id")==payload.optString("id")}?.optJSONObject("state")
            meta.optJSONArray("videos")?.objects().orEmpty().filter{it.optInt("season")==season}.sortedBy{it.optInt("episode")}.forEach{episode->
                val selected=progress?.optString("video_id")==episode.optString("id")
                val offset=if(selected)progress?.optLong("timeOffset")?:0 else 0
                val subtitle=listOf(episode.optString("overview",episode.optString("description")),episode.optString("released"),if(offset>0)"Resume ${offset/60000} min" else "").filter{it.isNotBlank()}.joinToString(" · ")
                posterRow(body,"E${episode.optInt("episode")} · ${episode.optString("title",episode.optString("name"))}",subtitle,episode.optString("thumbnail")){
                    navigate(JSONObject(payload.toString()).put("screen","streams").put("videoId",episode.optString("id")).put("episode",episode.optInt("episode")))
                }
                val total=progress?.optLong("duration")?:0
                if(offset>0&&total>0)body.addView(android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;this.progress=(offset*100/total).toInt().coerceIn(0,100)},android.widget.LinearLayout.LayoutParams(-1,dp(6)))
            }
        }
    }
    private fun watchedButton(id:String,videoId:String,name:String,season:Int,episode:Int){
        button("Mark watched / unwatched"){choose("Watched history",listOf("Mark watched","Mark unwatched")){choice->
            work("watched.set",JSONObject().put("id",id).put("videoId",videoId).put("name",name).put("season",season).put("episode",episode).put("watched",choice==0)){message("Watched history saved.")}
        }}
    }
    private fun streams(){
        val meta=payload.optJSONObject("meta")?:JSONObject();val season=payload.optInt("season",-1);val episode=payload.optInt("episode",-1)
        screen(meta.optString("name","Streams")+if(episode>0)" · S$season E$episode" else "")
        button("Reload sources"){payload.put("refresh",true);render()}
        watchedButton(payload.optString("id"),payload.optString("videoId"),meta.optString("name"),season,episode)
        work("streams",payload){response->val result=obj(response);val sources=result.optJSONArray("streams")?.objects().orEmpty()
            if(result.optBoolean("cached"))text("Recent sources · Reload checks availability again.")
            if(sources.isEmpty())text("No sources were returned. Check enabled addons and providers.")
            if((result.optJSONArray("errors")?.length()?:0)>0)button("Provider availability details"){message(result.optJSONArray("errors")!!.strings().distinct().joinToString("\n\n"))}
            val filter=payload.optString("filter")
            if(sources.isNotEmpty())button("Filter: ${filter.ifBlank{"All"}}"){val options=listOf("All")+sources.map{it.optString("addon")}.filter{it.isNotBlank()}.distinct()+listOf("4K","1080p","720p")
                choose("Source filter",options){index->payload.put("filter",if(index==0)"" else options[index]);payload.put("refresh",false);render()}
            }
            sources.filter{filter.isBlank()||it.optString("addon")==filter||it.optString("resolution")==filter}.forEach{stream->
                val badges=listOf(stream.optString("resolution"),stream.optString("language"),stream.optString("sizeLabel")).filter{it.isNotBlank()}.joinToString(" · ")
                button("${stream.optString("name",stream.optString("addon","Stream"))} · $badges\n${stream.optString("description").take(180)}"){
                    val playback=JSONObject(payload.toString()).put("name",meta.optString("name")).put("stream",stream)
                    work("streams.resolve",JSONObject().put("stream",stream).put("season",season).put("episode",episode)){value->val resolved=obj(value)
                        if(resolved.optString("info_hash").isNotBlank()&&resolved.optString("url").isBlank()){
                            go(TorrentsActivity::class.java,JSONObject().put("input",resolved.optString("info_hash")).put("playback",playback))
                        }else{resolved.keys().forEach{playback.put(it,resolved.get(it))};play(playback)}
                    }
                }
            }
        }
    }
    private fun library(){
        screen("Library & Continue Watching")
        val mode=payload.optString("mode","All");val query=payload.optString("query");val sort=payload.optString("sort","Name")
        button("Search Library"){form("Search saved titles",listOf("Title" to false)){values->payload.put("query",values[0]);render()}}
        button("Filter: $mode"){val options=listOf("All","Movies","Series","Continue Watching");choose("Library filter",options){payload.put("mode",options[it]);render()}}
        button("Sort: $sort"){val options=listOf("Name","Recently watched");choose("Library sort",options){payload.put("sort",options[it]);render()}}
        button("Refresh new-episode badges"){quietly("series.badges"){applyBadges(obj(it))}}
        if(query.isNotBlank())button("Clear search"){payload.put("query","");render()}
        work("library"){value->var rows=arr(value).objects().filter{item->
            val resume=item.optJSONObject("state")?.optLong("timeOffset")?:0L
            val matches=when(mode){"Movies"->item.optString("type")=="movie"&&!item.optBoolean("removed");"Series"->item.optString("type")=="series"&&!item.optBoolean("removed");"Continue Watching"->resume>0;else->!item.optBoolean("removed")}
            matches&&item.optString("name").contains(query,true)
        }
            rows=if(sort=="Recently watched")rows.sortedByDescending{it.optJSONObject("state")?.optLong("lastWatchedMs")?:0L}else rows.sortedBy{it.optString("name").lowercase()}
            val offset=payload.optInt("offset");titleRows(rows.drop(offset).take(250))
            if(offset+250<rows.size)button("More saved titles ›"){navigate(JSONObject(payload.toString()).put("offset",offset+250))}
            quietly("series.badges"){applyBadges(obj(it))}
        }
    }
    private fun applyBadges(badges:JSONObject){
        titleLabels.forEach{(id,pair)->pair.first.text=pair.second+badges.optString(id).let{if(it.isBlank())"" else "\n$it"}}
    }
}
