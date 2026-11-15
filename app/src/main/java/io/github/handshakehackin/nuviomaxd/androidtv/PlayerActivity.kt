package io.github.handshakehackin.nuviomaxd.androidtv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.media3.common.*
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import io.github.handshakehackin.nuviomaxd.androidtv.core.*
import io.github.handshakehackin.nuviomaxd.androidtv.player.*
import org.json.JSONObject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.security.MessageDigest

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity:Activity(){
    private val main=Handler(Looper.getMainLooper())
    private lateinit var root:FrameLayout
    private lateinit var video:FrameLayout
    private lateinit var overlay:LinearLayout
    private lateinit var title:TextView
    private lateinit var information:TextView
    private lateinit var timeline:PlaybackTimeline
    private lateinit var prompts:LinearLayout
    private lateinit var skip:Button
    private lateinit var next:Button
    private lateinit var cancelNext:Button
    private lateinit var playControl:Button
    private var item=JSONObject()
    private var settings=JSONObject()
    private var exo:ExoPlayer?=null
    private var exoView:PlayerView?=null
    private var libVlc:LibVLC?=null
    private var vlc:MediaPlayer?=null
    private var vlcLayout:VLCVideoLayout?=null
    private var positionMs=0L
    private var playWhenReady=true
    private var active=false
    private var initialized=false
    private var ended=false
    private var vlcBuffer=0f
    private var lastTick=0L
    private var lastSave=0L
    private var lastReport=0L
    private var lastInteraction=0L
    private var timingsRequested=false
    private var nextLoading=false
    private var nextItem:JSONObject?=null
    private var nextPreparedAt=-60_001L
    private var completionSent=false
    private var transitioning=false
    private var externalSubtitle:JSONObject?=null
    private var subtitlesOff=false
    private var subtitleManual=false
    private var automaticSubtitleAttempted=false
    private var manualAudioLanguage:String?=null
    private var manualAudioTrack:Format?=null
    private var manualSubtitleTrack:Format?=null
    private var vlcAudioId:Int?=null
    private var vlcSubtitleId:Int?=null
    private var engineStartedAt=0L
    private var lastChapterCheck=0L
    private val flow=EpisodeFlow()
    private val tick=object:Runnable{override fun run(){update();if(active)main.postDelayed(this,500)}}

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        item=Routes.read(this)
        item.optJSONObject("meta")?.optString("name")?.takeIf{it.isNotBlank()}?.let{item.put("name",it)}
        if(item.optString("url").isEmpty())item.put("url",intent.getStringExtra("url").orEmpty()).put("type","direct")
        val url=item.optString("url")
        if(!validStream(url)&&!url.matches(Regex("torrent://[a-fA-F0-9]{40,64}/[0-9]+"))){finish();return}
        if(item.optString("id").isBlank())item.put("id","direct:"+MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString(""){"%02x".format(it)})
        positionMs=savedInstanceState?.getLong("position")?:((item.optDouble("resume",0.0))*1000).toLong()
        playWhenReady=savedInstanceState?.getBoolean("playing")?:true
        subtitlesOff=savedInstanceState?.getBoolean("subtitlesOff")?:false
        subtitleManual=savedInstanceState?.getBoolean("subtitleManual")?:false
        manualAudioLanguage=savedInstanceState?.getString("audioLanguage")
        savedInstanceState?.getString("externalSubtitle")?.let{runCatching{externalSubtitle=JSONObject(it)}}
        fun restoreChoice(key:String):Format?=savedInstanceState?.getString(key)?.let{value->runCatching{val fields=JSONObject(value);Format.Builder().setId(fields.optString("id").ifBlank{null}).setLanguage(fields.optString("language").ifBlank{null}).setLabel(fields.optString("label").ifBlank{null}).build()}.getOrNull()}
        manualAudioTrack=restoreChoice("audioChoice");manualSubtitleTrack=restoreChoice("subtitleChoice")
        if(savedInstanceState?.containsKey("vlcAudio")==true)vlcAudioId=savedInstanceState.getInt("vlcAudio")
        if(savedInstanceState?.containsKey("vlcSubtitle")==true)vlcSubtitleId=savedInstanceState.getInt("vlcSubtitle")
        buildUi()
        CoreBridge.workers.submit{
            try{
                CoreBridge.initialize(applicationContext)
                CoreBridge.request("app.foreground")
                val expected=item.optString("profileKey",CoreBridge.profileKey)
                if(expected!=CoreBridge.profileKey)throw CoreException("The active profile changed. Open this title again from its profile.")
                item.put("profileKey",CoreBridge.profileKey)
                if(positionMs==0L){
                    val local=if(item.optString("type") in listOf("movie","series")){
                        val rows=CoreBridge.arrayResult("library",JSONObject().put("sync",false))
                        rows.objects().firstOrNull{it.optString("_id")==item.optString("id")}?.optJSONObject("state")
                    }else CoreBridge.objectResult("resume.get",JSONObject().put("id",item.optString("id")))
                    val matchingVideo=local?.optString("video_id").orEmpty()
                    if(local!=null&&(matchingVideo.isBlank()||matchingVideo==item.optString("videoId"))&&!local.optBoolean("nuvioCompleted"))positionMs=local.optLong("timeOffset").coerceAtLeast(0)
                }
                main.post{if(!isDestroyed&&!isFinishing){initialized=true;settings=JSONObject(CoreBridge.settings.toString());if(active)startEngine()}}
            }catch(error:Exception){main.post{if(!isDestroyed)showError(if(error is CoreException)error.message.orEmpty()else"Playback could not start.")}}
        }
    }
    private fun buildUi(){
        root=FrameLayout(this).apply{setBackgroundColor(Color.BLACK);isFocusableInTouchMode=true}
        video=FrameLayout(this);root.addView(video,FrameLayout.LayoutParams(-1,-1))
        title=TextView(this).apply{text=item.optString("name","Nuvio Max'd");textSize=22f;setTextColor(Color.WHITE);setBackgroundColor(0xaa000000.toInt());setPadding(dp(24),dp(16),dp(24),dp(16))}
        root.addView(title,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        overlay=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(12));setBackgroundColor(0xdd101421.toInt())}
        information=TextView(this).apply{textSize=16f;setTextColor(Color.WHITE)};overlay.addView(information)
        timeline=PlaybackTimeline(this).apply{seek={seekAbsolute(it)}};overlay.addView(timeline,LinearLayout.LayoutParams(-1,dp(32)))
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        fun control(label:String,action:()->Unit){val button=tvButton(row,label){lastInteraction=SystemClock.elapsedRealtime();action()};button.textSize=18f;button.setPadding(dp(8),dp(4),dp(8),dp(4));button.layoutParams=LinearLayout.LayoutParams(dp(144),dp(60)).apply{marginEnd=dp(8)};if(label=="Play / Pause")playControl=button}
        control("− Seek"){seek(-settings.optInt("seekSeconds",10))};control("Play / Pause"){toggle()};control("Seek +"){seek(settings.optInt("seekSeconds",10))}
        control("Audio"){audioTracks()};control("Subtitles"){subtitleTracks()};control("Chapters"){chapters()};control("Details"){details()};control("A/V settings"){avSettings()};control("Close"){finish()}
        overlay.addView(HorizontalScrollView(this).apply{addView(row);isHorizontalScrollBarEnabled=false})
        root.addView(overlay,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        prompts=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(dp(24),0,dp(24),0)}
        skip=tvButton(prompts,"Skip"){flow.active?.let{seekAbsolute((it.end*1000).toLong());flow.skipped()}}
        next=tvButton(prompts,"Next episode"){launchNext()}
        cancelNext=tvButton(prompts,"Cancel autoplay"){flow.cancel();nextItem=null}
        listOf(skip,next,cancelNext).forEach{it.textSize=18f;it.setPadding(dp(8),dp(4),dp(8),dp(4));it.layoutParams=LinearLayout.LayoutParams(dp(210),dp(60)).apply{marginEnd=dp(8)};it.visibility=View.GONE}
        root.addView(prompts,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{bottomMargin=dp(120)})
        setContentView(root);playControl.requestFocus();lastInteraction=SystemClock.elapsedRealtime()
    }
    override fun onStart(){super.onStart();active=true;if(initialized){CoreBridge.workers.submit{runCatching{CoreBridge.request("app.foreground")}.onSuccess{main.post{if(active&&!isDestroyed){settings=JSONObject(CoreBridge.settings.toString());startEngine()}}}}}}
    private fun startEngine(){
        if(exo!=null||vlc!=null)return
        if(item.optString("profileKey")!=CoreBridge.profileKey){showError("The active profile changed. Reopen this title from its profile.");return}
        if(item.optString("url").startsWith("torrent://")&&!CoreBridge.settings.optBoolean("torrents")){showError("Peer-to-peer torrents are disabled. Enable them in Settings before opening this file.");return}
        ended=false;engineStartedAt=SystemClock.elapsedRealtime();video.removeAllViews();window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val needsAndroidMix=!settings.optBoolean("surround",true)||settings.optInt("nightMode")>0
        val useVlc=settings.optString("engine","media3")=="vlc"&&!item.optString("url").startsWith("torrent://")&&!needsAndroidMix
        try{if(useVlc)startVlc()else startMedia3()}catch(error:Exception){showError(if(error is CoreException)error.message.orEmpty()else"This playback engine could not start. Try the other engine in Settings.");return}
        lastTick=SystemClock.elapsedRealtime();main.removeCallbacks(tick);main.post(tick);serverReport("start")
    }
    private fun startMedia3(){
        val selector=DefaultTrackSelector(this).apply{setParameters(buildUponParameters().setPreferredAudioLanguage(manualAudioLanguage?:settings.optString("audioLanguage","eng"))
            .setPreferredTextLanguage(settings.optString("subtitleLanguage","eng")).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,subtitlesOff||(!subtitleManual&&settings.optString("subtitleLanguage")=="off")))}
        val seconds=settings.optInt("readAhead",60).coerceIn(30,300)
        val buffering=DefaultLoadControl.Builder().setBufferDurationsMs(seconds*1000,seconds*1000,1500,4000).setTargetBufferBytes(64*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build()
        val player=ExoPlayer.Builder(this,MaxdRenderers(this,settings)).setTrackSelector(selector).setLoadControl(buffering)
            .setAudioAttributes(AudioAttributes.DEFAULT,true).setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(DefaultMediaSourceFactory(PlaybackSources.factory(this,item))).build()
        exo=player
        var audioRestored=false;var subtitlesRestored=false
        player.addAnalyticsListener(object:AnalyticsListener{
            override fun onDroppedVideoFrames(eventTime:AnalyticsListener.EventTime,droppedFrames:Int,elapsedMs:Long){Diagnostics.record("droppedFrames",JSONObject().put("frames",droppedFrames).put("elapsedMs",elapsedMs))}
        })
        exoView=PlayerView(this).apply{useController=false;this.player=player;setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)}
        video.addView(exoView,FrameLayout.LayoutParams(-1,-1));applySubtitleStyle()
        player.addListener(object:Player.Listener{
            override fun onPlayerError(error:PlaybackException){Diagnostics.record("media3Error",JSONObject().put("code",error.errorCodeName));showError("The stream could not be played by Media3. Check the source or try VLC.")}
            override fun onTracksChanged(tracks:Tracks){
                fun restore(type:Int,selected:Format?):Boolean{
                    if(selected==null)return true
                    for(group in tracks.groups.filter{it.type==type})for(index in 0 until group.length){
                        val format=group.getTrackFormat(index)
                        if(group.isTrackSupported(index)&&format.id==selected.id&&format.language==selected.language&&format.label==selected.label){
                            player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).addOverride(TrackSelectionOverride(group.mediaTrackGroup,listOf(index))).build();return true
                        }
                    };return false
                }
                if(!audioRestored){audioRestored=true;audioRestored=restore(C.TRACK_TYPE_AUDIO,manualAudioTrack)}
                if(!subtitlesRestored){subtitlesRestored=true;subtitlesRestored=subtitlesOff||restore(C.TRACK_TYPE_TEXT,manualSubtitleTrack)}
                if(tracks.groups.isNotEmpty()&&tracks.groups.none{it.type==C.TRACK_TYPE_TEXT&&it.isSupported})automaticSubtitles()
            }
            override fun onPlaybackStateChanged(playbackState:Int){if(active&&playbackState==Player.STATE_ENDED)ended=true}
            override fun onRenderedFirstFrame(){DisplayPolicy.apply(this@PlayerActivity,settings,player.videoFormat?.frameRate?:0f,exoView)
                Diagnostics.record("firstFrame",JSONObject().put("engine","media3").put("startupMs",SystemClock.elapsedRealtime()-engineStartedAt).put("width",player.videoFormat?.width).put("height",player.videoFormat?.height))
                val hdr=player.videoFormat?.colorInfo?.colorTransfer in listOf(C.COLOR_TRANSFER_ST2084,C.COLOR_TRANSFER_HLG)
                if(hdr&&settings.optString("hdr")=="require"&&!DisplayPolicy.hdrSupported(this@PlayerActivity)){player.pause();showError("This source requires an HDR-capable display. Select Automatic or SDR output in Settings.")}
            }
        })
        val builder=MediaItem.Builder().setUri(item.optString("url"))
        externalSubtitle?.let{subtitle->
            val url=subtitle.optString("url");val extension=url.substringBefore('?').substringAfterLast('.').lowercase()
            val mime=when(extension){"vtt"->MimeTypes.TEXT_VTT;"ass","ssa"->MimeTypes.TEXT_SSA;"ttml","xml"->MimeTypes.APPLICATION_TTML;else->MimeTypes.APPLICATION_SUBRIP}
            builder.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(Uri.parse(url)).setMimeType(mime).setLanguage(subtitle.optString("lang")).setLabel(subtitle.optString("name","Addon subtitle")).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
        }
        player.setMediaItem(builder.build());player.seekTo(positionMs);player.playWhenReady=playWhenReady;player.prepare()
    }
    private fun startVlc(){
        val headers=HeaderScope.headers(item)
        if(headers.keys.any{!it.equals("User-Agent",true)&&!it.equals("Referer",true)}){
            throw CoreException("Use Media3 for this authenticated stream.")
        }
        val color=when(settings.optString("subtitleColor","White")){"Yellow"->16776960;"Cyan"->65535;"Green"->65280;else->16777215}
        val options=arrayListOf("--network-caching=${settings.optInt("readAhead",60)*1000}","--no-video-title-show","--freetype-color=$color",
            "--freetype-rel-fontsize=${(16*100/settings.optInt("subtitleSize",100)).coerceIn(6,32)}",
            "--freetype-background-opacity=${if(settings.optBoolean("subtitleBackground"))160 else 0}")
        options+="--freetype-font=${when(settings.optString("subtitleFont")){"Serif"->"serif";"Monospace"->"monospace";else->"sans-serif"}}"
        options+="--freetype-outline-thickness=${if(settings.optString("subtitleOutline","Outline")=="Outline")2 else 0}"
        options+="--freetype-shadow-opacity=${if(settings.optString("subtitleOutline")=="Shadow")160 else 0}"
        if(settings.optString("audioMode")=="pcm")options+="--no-audiotrack-passthrough"
        if(settings.optString("audioLanguage").isNotBlank())options+="--audio-language=${settings.optString("audioLanguage") }"
        options+="--sub-language=${if(subtitlesOff)"none" else settings.optString("subtitleLanguage","eng") }"
        libVlc=LibVLC(this,options)
        vlcLayout=VLCVideoLayout(this);video.addView(vlcLayout,FrameLayout.LayoutParams(-1,-1))
        val player=MediaPlayer(libVlc!!);vlc=player
        player.attachViews(vlcLayout!!,null,true,false)
        val gain=settings.optInt("gain",100).coerceIn(50,300)
        player.setAudioDigitalOutputEnabled(settings.optString("audioMode","auto")!="pcm"&&gain==100)
        val equalizer=MediaPlayer.Equalizer.create()
        equalizer.setPreAmp((20*kotlin.math.log10(gain/100.0)).toFloat());player.setEqualizer(equalizer)
        var initialSeek=true
        player.setEventListener{event->if(!active)return@setEventListener
            when(event.type){
                MediaPlayer.Event.Buffering->vlcBuffer=event.buffering
                MediaPlayer.Event.Playing->{vlcBuffer=100f;if(initialSeek){player.setTime(positionMs);if(!playWhenReady)player.pause();vlcAudioId?.let{player.setAudioTrack(it)};vlcSubtitleId?.let{player.setSpuTrack(it)};Diagnostics.record("vlcPlaying",JSONObject().put("startupMs",SystemClock.elapsedRealtime()-engineStartedAt));initialSeek=false};player.setVolume(100);player.audioDelay=settings.optLong("audioDelay")*1000;player.spuDelay=settings.optLong("subtitleDelay")*1000
                    DisplayPolicy.apply(this,settings,player.currentVideoTrack?.let{if(it.frameRateDen>0)it.frameRateNum.toFloat()/it.frameRateDen else 0f}?:0f)
                    if(player.spuTracks.orEmpty().none{it.id>=0})automaticSubtitles()}
                MediaPlayer.Event.EndReached->ended=true
                MediaPlayer.Event.EncounteredError->{Diagnostics.record("vlcError");showError("VLC could not play this stream. Try another source or Media3.")}
            }
        }
        val media=Media(libVlc!!,Uri.parse(item.optString("url")))
        media.setHWDecoderEnabled(true,false)
        headers.forEach{(key,value)->when{key.equals("User-Agent",true)->media.addOption(":http-user-agent=$value");key.equals("Referer",true)->media.addOption(":http-referrer=$value")}}
        externalSubtitle?.let{media.addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle,4,it.optString("url")))}
        player.media=media;media.release();player.play()
    }
    private fun applySubtitleStyle(){
        val color=when(settings.optString("subtitleColor","White")){"Yellow"->Color.YELLOW;"Cyan"->Color.CYAN;"Green"->Color.GREEN;else->Color.WHITE}
        val font=when(settings.optString("subtitleFont")){"Sans"->Typeface.SANS_SERIF;"Serif"->Typeface.SERIF;"Monospace"->Typeface.MONOSPACE;else->Typeface.DEFAULT}
        val edge=when(settings.optString("subtitleOutline","Outline")){"Shadow"->CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW;"None"->CaptionStyleCompat.EDGE_TYPE_NONE;else->CaptionStyleCompat.EDGE_TYPE_OUTLINE}
        exoView?.subtitleView?.setStyle(CaptionStyleCompat(color,if(settings.optBoolean("subtitleBackground"))0x99000000.toInt()else Color.TRANSPARENT,Color.TRANSPARENT,edge,Color.BLACK,font))
        exoView?.subtitleView?.setFractionalTextSize(0.0533f*settings.optInt("subtitleSize",100)/100f)
    }
    private fun position()=exo?.currentPosition?:vlc?.time?:positionMs
    private fun duration():Long=(exo?.duration?:vlc?.length?:0L).coerceAtLeast(0)
    private fun playing()=exo?.isPlaying?:((vlc?.isPlaying==true)&&vlcBuffer>=99f)
    private fun toggle(){exo?.let{it.playWhenReady=!it.playWhenReady};vlc?.let{if(it.isPlaying)it.pause()else it.play()};showControls()}
    private fun seek(seconds:Int)=seekAbsolute(position()+seconds*1000L)
    private fun seekAbsolute(value:Long){val target=if(duration()>0)value.coerceIn(0,duration()-1)else value.coerceAtLeast(0);ended=false;exo?.seekTo(target);vlc?.setTime(target);showControls()}
    private fun showControls(){lastInteraction=SystemClock.elapsedRealtime();overlay.visibility=View.VISIBLE;title.visibility=View.VISIBLE;if(overlay.findFocus()==null)playControl.requestFocus()}
    private fun update(){
        if(!active||isDestroyed)return
        val now=SystemClock.elapsedRealtime();val elapsed=((now-lastTick)/1000.0).coerceIn(0.0,1.0);lastTick=now
        val position=position();val duration=duration()
        if(now-lastChapterCheck>3000){lastChapterCheck=now;val player=vlc
            timeline.chapters=if(player!=null&&player.title>=0&&duration>0)player.getChapters(player.title).orEmpty().map{it.timeOffset to it.name.orEmpty()}.filter{it.first in 0 until duration}.distinctBy{it.first}.sortedBy{it.first}.takeIf{it.size>=2}.orEmpty()else emptyList()
        }
        timeline.position=position;timeline.duration=duration;timeline.seekStep=settings.optInt("seekSeconds",10)*1000L;timeline.invalidate()
        information.text="${clock(position)} / ${if(duration>0)clock(duration)else "Live"} · ${if(exo!=null)"Media3" else "VLC"}${if(exo?.playbackState==Player.STATE_BUFFERING||(vlc!=null&&vlcBuffer in 0f..98f))" · Buffering" else ""}"
        timeline.chapters.lastOrNull{it.first<=position}?.second?.takeIf{it.isNotBlank()}?.let{information.append(" · $it")}
        if(now-lastInteraction>6000&&overlay.visibility==View.VISIBLE){overlay.visibility=View.GONE;title.visibility=View.GONE;root.requestFocus()}
        val episode=item.optString("type")=="series"
        if(duration>0&&!timingsRequested&&item.optString("type") in listOf("movie","series")){timingsRequested=true;requestTimings(duration/1000.0)}
        if(episode&&settings.optBoolean("autoplay",true)&&duration>0&&duration-position<120_000&&!nextLoading&&!flow.cancelled&&now-nextPreparedAt>60_000)prepareNext()
        val launch=flow.update(position/1000.0,duration/1000.0,elapsed,playing(),nextItem!=null,settings.optBoolean("autoplay",true),settings.optInt("countdown",10),ended)
        skip.visibility=if(flow.active!=null)View.VISIBLE else View.GONE;skip.text="Skip ${flow.active?.kind.orEmpty()}"
        next.visibility=if(flow.nextVisible)View.VISIBLE else View.GONE;cancelNext.visibility=next.visibility;next.text="Next episode · ${flow.remaining.toInt().coerceAtLeast(0)} s"
        if(launch){launchNext();return}
        if(position>0&&now-lastSave>=30_000){lastSave=now;saveProgress(position,duration,ended)}
        if(item.optString("type")=="media-server"&&now-lastReport>=10_000){lastReport=now;serverReport("progress")}
    }
    private fun requestTimings(duration:Double){
        val args=JSONObject(item.toString()).put("duration",duration)
        CoreBridge.workers.submit{runCatching{CoreBridge.arrayResult("timings",args)}.onSuccess{rows->main.post{if(active)flow.segments=rows.objects().map{SkipSegment(it.optString("kind"),it.optDouble("start"),it.optDouble("end"))}}}}
    }
    private fun prepareNext(){
        nextLoading=true;nextItem=null;nextPreparedAt=SystemClock.elapsedRealtime();val args=JSONObject(item.toString())
        CoreBridge.workers.submit{
            try{
                val next=CoreBridge.request("next",args)as?JSONObject?:return@submit
                val request=JSONObject(args.toString()).put("videoId",next.optString("id")).put("season",next.optInt("season")).put("episode",next.optInt("episode")).put("refresh",true)
                val streams=CoreBridge.objectResult("streams",request).optJSONArray("streams")?:return@submit
                val match=CoreBridge.objectResult("streams.match",JSONObject().put("streams",streams).put("previous",item.optJSONObject("stream")?:JSONObject())).optInt("index",-1)
                if(match<0)return@submit
                val stream=streams.getJSONObject(match)
                val resolved=CoreBridge.objectResult("streams.resolve",JSONObject().put("stream",stream).put("season",next.optInt("season")).put("episode",next.optInt("episode")))
                if(resolved.optString("url").isBlank()){
                    val hash=resolved.optString("info_hash")
                    if(hash.isBlank()||!settings.optBoolean("torrents"))return@submit
                    // The shared torrent engine owns one active torrent. Start
                    // the next swarm only after releasing this episode's reader.
                    resolved.put("deferredTorrent",hash)
                }
                val playback=JSONObject(item.toString()).put("videoId",next.optString("id")).put("season",next.optInt("season")).put("episode",next.optInt("episode")).put("stream",stream).put("resume",0)
                resolved.keys().forEach{playback.put(it,resolved.get(it))}
                main.post{if(active&&!flow.cancelled){nextItem=playback;nextPreparedAt=SystemClock.elapsedRealtime()}}
            }catch(_:Exception){/* A missing next stream leaves the current episode playing. */}
            finally{main.post{nextLoading=false}}
        }
    }
    private fun launchNext(){val value=nextItem?:return;if(item.optString("profileKey")!=CoreBridge.profileKey)return
        nextItem=null;flow.cancel();transitioning=true;positionMs=position();saveProgress(positionMs,duration(),true)
        val torrent=value.optString("deferredTorrent")
        if(torrent.isNotBlank()){
            active=false;main.removeCallbacks(tick);releaseEngine()
            Routes.open(this,TorrentsActivity::class.java,JSONObject().put("input",torrent).put("playback",value).put("auto",true))
        }else Routes.open(this,PlayerActivity::class.java,value)
        finish()
    }
    private fun saveProgress(position:Long,duration:Long,finished:Boolean=false){
        val type=item.optString("type");if(type in listOf("trailer","tv","media-server"))return
        val completed=finished||(duration>0&&position>=duration*0.95)
        val state=JSONObject().put("timeOffset",if(completed)0 else position).put("duration",duration).put("lastWatchedMs",System.currentTimeMillis())
            .put("video_id",item.optString("videoId",item.optString("id"))).put("nuvioSeason",item.optInt("season",-1)).put("nuvioEpisode",item.optInt("episode",-1)).put("nuvioCompleted",completed)
        val key=item.optString("profileKey");val id=item.optString("id")
        CoreBridge.workers.submit{runCatching{
            if(type in listOf("movie","series")){
                val meta=item.optJSONObject("meta")?:JSONObject()
                val row=JSONObject().put("_id",id).put("type",type).put("name",item.optString("name")).put("poster",meta.optString("poster")).put("background",meta.optString("background")).put("state",state)
                CoreBridge.request("library.put",JSONObject().put("profileKey",key).put("item",row))
            }else CoreBridge.request("resume.put",JSONObject().put("profileKey",key).put("id",id).put("state",state))
        }}
        if(completed&&!completionSent&&type in listOf("movie","series")){completionSent=true
            val args=JSONObject().put("profileKey",key).put("id",id).put("videoId",item.optString("videoId",id)).put("name",item.optString("name")).put("season",item.optInt("season",-1)).put("episode",item.optInt("episode",-1)).put("watched",true)
            CoreBridge.workers.submit{runCatching{CoreBridge.request("watched.set",args)}}
        }
    }
    private fun serverReport(event:String){if(item.optString("type")!="media-server")return
        val args=JSONObject().put("profileKey",item.optString("profileKey")).put("playback",JSONObject(item.toString())).put("position",position()/1000.0).put("paused",!playing()).put("event",event)
        CoreBridge.workers.submit{runCatching{CoreBridge.request("servers.report",args)}}
    }
    private fun choose(title:String,labels:List<String>,action:(Int)->Unit){if(labels.isEmpty()){message("No tracks are available.");return};AlertDialog.Builder(this).setTitle(title).setItems(labels.toTypedArray()){_,index->action(index)}.setNegativeButton(android.R.string.cancel,null).show()}
    private fun message(value:String){if(!isFinishing)AlertDialog.Builder(this).setMessage(value).setPositiveButton(android.R.string.ok,null).show()}
    private fun audioTracks(){
        val player=exo
        if(player!=null){val tracks=player.currentTracks.groups.filter{it.type==C.TRACK_TYPE_AUDIO}.flatMap{group->(0 until group.length).filter{group.isTrackSupported(it)}.map{group to it}}
            choose("Audio tracks",tracks.map{(group,index)->val format=group.getTrackFormat(index);"${format.label?:format.language?:"Audio"} · ${format.channelCount} ch · ${format.codecs.orEmpty()}"}){selected->val(group,index)=tracks[selected]
                manualAudioLanguage=group.getTrackFormat(index).language
                manualAudioTrack=group.getTrackFormat(index)
                player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,false).clearOverridesOfType(C.TRACK_TYPE_AUDIO).addOverride(TrackSelectionOverride(group.mediaTrackGroup,listOf(index))).build()
            }
        }else{val tracks=vlc?.audioTracks.orEmpty();choose("Audio tracks",tracks.map{it.name}){vlcAudioId=tracks[it].id;vlc?.setAudioTrack(tracks[it].id)}}
    }
    private fun subtitleTracks(){choose("Subtitles",listOf("Embedded tracks / Off","Addon subtitles")){choice->if(choice==0)embeddedSubtitles()else addonSubtitles()}}
    private fun embeddedSubtitles(){
        val player=exo
        if(player!=null){val tracks=player.currentTracks.groups.filter{it.type==C.TRACK_TYPE_TEXT}.flatMap{group->(0 until group.length).filter{group.isTrackSupported(it)}.map{group to it}}
            choose("Subtitle tracks",listOf("Off")+tracks.map{(group,index)->val format=group.getTrackFormat(index);format.label?:format.language?:"Subtitle"}){selected->
                subtitleManual=true;subtitlesOff=selected==0;val builder=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,subtitlesOff).clearOverridesOfType(C.TRACK_TYPE_TEXT)
                if(selected>0){val(group,index)=tracks[selected-1];manualSubtitleTrack=group.getTrackFormat(index);builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup,listOf(index)))}else manualSubtitleTrack=null
                player.trackSelectionParameters=builder.build()
            }
        }else{val tracks=vlc?.spuTracks.orEmpty();choose("Subtitle tracks",listOf("Off")+tracks.map{it.name}){index->subtitleManual=true;subtitlesOff=index==0;vlcSubtitleId=if(index==0)-1 else tracks[index-1].id;vlc?.setSpuTrack(vlcSubtitleId!!)}}
    }
    private fun addonSubtitles(){
        if(item.optString("type") !in listOf("movie","series")){message("Addon subtitles are available for catalog movies and episodes.");return}
        information.text="Loading subtitle addons…"
        CoreBridge.workers.submit{try{val tracks=CoreBridge.arrayResult("subtitles",JSONObject(item.toString())).objects();main.post{if(active)choose("Addon subtitles",tracks.map{"${it.optString("lang")} · ${it.optString("name")} · ${it.optString("addon")}"}){index->
            externalSubtitle=tracks[index];subtitlesOff=false;subtitleManual=true;manualSubtitleTrack=null
            if(vlc!=null)vlc?.addSlave(IMedia.Slave.Type.Subtitle,Uri.parse(tracks[index].optString("url")),true)else restartEngine()
        }}}catch(_:Exception){main.post{if(active)message("Subtitle addons are unavailable.")}}}
    }
    private fun automaticSubtitles(){
        if(automaticSubtitleAttempted||subtitleManual||externalSubtitle!=null||subtitlesOff||settings.optString("subtitleLanguage","eng")=="off"||item.optString("type") !in listOf("movie","series"))return
        automaticSubtitleAttempted=true
        val preferred=settings.optString("subtitleLanguage","eng")
        fun language(value:String)=when(value.lowercase().substringBefore('-')){"en","eng"->"eng";"es","spa"->"spa";"fr","fra","fre"->"fra";"de","deu","ger"->"deu";"ja","jpn"->"jpn";else->value.lowercase()}
        CoreBridge.workers.submit{runCatching{CoreBridge.arrayResult("subtitles",JSONObject(item.toString())).objects().firstOrNull{language(it.optString("lang"))==language(preferred)}}.onSuccess{track->
            if(track!=null)main.post{if(active&&!subtitleManual&&externalSubtitle==null){externalSubtitle=track;if(vlc!=null)vlc?.addSlave(IMedia.Slave.Type.Subtitle,Uri.parse(track.optString("url")),true)else restartEngine()}}
        }}
    }
    private fun chapters(){
        val player=vlc
        if(player!=null){val chapters=player.getChapters(player.title).orEmpty();choose("Chapters",chapters.map{it.name}){seekAbsolute(chapters[it].timeOffset)}}
        else message("Use VLC for embedded chapter navigation on compatible files. You can change the playback engine in A/V settings.")
    }
    private fun details(){
        val video=exo?.videoFormat;val audio=exo?.audioFormat;val nativeVideo=vlc?.currentVideoTrack
        val description=if(video!=null)"Video ${video.width}×${video.height} · ${video.codecs.orEmpty()} · ${video.frameRate} fps\nColor transfer ${video.colorInfo?.colorTransfer?:"unknown"}\nAudio ${audio?.codecs.orEmpty()} · ${audio?.channelCount?:0} channels\nBuffered ${exo?.totalBufferedDuration?:0} ms"
            else if(nativeVideo!=null)"Video ${nativeVideo.width}×${nativeVideo.height} · ${nativeVideo.codec}\nVLC track ${nativeVideo.description.orEmpty()}"else "Waiting for stream information."
        message("${if(exo!=null)"Media3" else "VLC"}\n$description\n${DisplayPolicy.description(this)}\nHDR mode: ${settings.optString("hdr","auto")}\nSource format and display capabilities do not prove the TV's current HDMI or receiver signal.")
    }
    private fun avSettings(){
        val entries=Preferences.all.filter{it.category in listOf("Audio","Subtitles","Video","Playback")}
        choose("Playback settings",entries.map{"${it.name}: ${settings.opt(it.key)?:it.default}"}){index->val preference=entries[index]
            choose(preference.name,preference.options.map{it.first}){choice->
                settings.put(preference.key,preference.options[choice].second)
                if(preference.key=="audioLanguage"){manualAudioTrack=null;manualAudioLanguage=null;vlcAudioId=null}
                if(preference.key=="subtitleLanguage"){manualSubtitleTrack=null;vlcSubtitleId=null;subtitleManual=false;subtitlesOff=false;externalSubtitle=null;automaticSubtitleAttempted=false}
                val args=JSONObject().put("profileKey",item.optString("profileKey")).put("settings",JSONObject().put(preference.key,preference.options[choice].second))
                CoreBridge.workers.submit{runCatching{CoreBridge.request("settings.save",args)}}
                restartEngine()
            }
        }
    }
    private fun restartEngine(){positionMs=position();playWhenReady=exo?.playWhenReady?:vlc?.isPlaying?:true;releaseEngine();startEngine()}
    private fun showError(value:String){showControls();information.text=value}
    override fun dispatchKeyEvent(event:KeyEvent):Boolean{
        if(event.action!=KeyEvent.ACTION_DOWN)return super.dispatchKeyEvent(event)
        val mapped=settings.optJSONObject("remoteMappings")?.optString(event.keyCode.toString()).orEmpty()
        val action=if(mapped.isNotBlank())mapped else when(event.keyCode){
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE->"playPause"
            KeyEvent.KEYCODE_MEDIA_PLAY->"play";KeyEvent.KEYCODE_MEDIA_PAUSE->"pause"
            KeyEvent.KEYCODE_BUTTON_A->if(overlay.visibility!=View.VISIBLE||timeline.hasFocus())"playPause"else ""
            KeyEvent.KEYCODE_MEDIA_REWIND->"seekBack";KeyEvent.KEYCODE_MEDIA_FAST_FORWARD->"seekForward"
            KeyEvent.KEYCODE_BUTTON_L1,KeyEvent.KEYCODE_PAGE_UP->"longBack";KeyEvent.KEYCODE_BUTTON_R1,KeyEvent.KEYCODE_PAGE_DOWN->"longForward"
            KeyEvent.KEYCODE_MENU,KeyEvent.KEYCODE_BUTTON_Y->"details";KeyEvent.KEYCODE_CAPTIONS->"subtitles"
            KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_BUTTON_B->"back";else->""
        }
        when(action){
            "playPause"->{if(event.repeatCount==0)toggle();return true}
            "play"->{exo?.play();vlc?.play();showControls();return true};"pause"->{exo?.pause();vlc?.pause();showControls();return true}
            "seekBack"->{seek(-settings.optInt("seekSeconds",10));return true};"seekForward"->{seek(settings.optInt("seekSeconds",10));return true}
            "longBack"->{seek(-settings.optInt("longSeekSeconds",60));return true};"longForward"->{seek(settings.optInt("longSeekSeconds",60));return true}
            "audio"->{if(event.repeatCount==0)audioTracks();return true};"subtitles"->{if(event.repeatCount==0)subtitleTracks();return true};"details"->{if(event.repeatCount==0)details();return true}
            "back"->{if(overlay.visibility==View.VISIBLE){overlay.visibility=View.GONE;title.visibility=View.GONE;root.requestFocus()}else finish();return true}
        }
        if(overlay.visibility!=View.VISIBLE){when(event.keyCode){
            KeyEvent.KEYCODE_DPAD_LEFT->{seek(-settings.optInt("seekSeconds",10));return true};KeyEvent.KEYCODE_DPAD_RIGHT->{seek(settings.optInt("seekSeconds",10));return true}
            KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER->{toggle();return true}
            KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN->{showControls();if(skip.visibility==View.VISIBLE)skip.requestFocus()else overlay.requestFocus(View.FOCUS_DOWN);return true}
        }}else lastInteraction=SystemClock.elapsedRealtime()
        return super.dispatchKeyEvent(event)
    }
    private fun clock(ms:Long):String{val seconds=(ms/1000).coerceAtLeast(0);return if(seconds>=3600)"%d:%02d:%02d".format(seconds/3600,(seconds/60)%60,seconds%60)else"%d:%02d".format(seconds/60,seconds%60)}
    override fun onSaveInstanceState(outState:Bundle){
        outState.putLong("position",position());outState.putBoolean("playing",exo?.playWhenReady?:vlc?.isPlaying?:playWhenReady);outState.putBoolean("subtitlesOff",subtitlesOff);outState.putBoolean("subtitleManual",subtitleManual);outState.putString("audioLanguage",manualAudioLanguage)
        externalSubtitle?.let{outState.putString("externalSubtitle",it.toString())}
        fun saveChoice(key:String,format:Format?){format?.let{outState.putString(key,JSONObject().put("id",it.id).put("language",it.language).put("label",it.label).toString())}}
        saveChoice("audioChoice",manualAudioTrack);saveChoice("subtitleChoice",manualSubtitleTrack)
        vlcAudioId?.let{outState.putInt("vlcAudio",it)};vlcSubtitleId?.let{outState.putInt("vlcSubtitle",it)}
        super.onSaveInstanceState(outState)
    }
    private fun releaseEngine(){
        CoreBridge.nativeCancelTorrentReads();exoView?.player=null;exo?.release();exo=null;exoView=null
        vlc?.setEventListener(null);vlc?.detachViews();vlc?.release();vlc=null;libVlc?.release();libVlc=null;vlcLayout=null;video.removeAllViews()
    }
    override fun onStop(){
        if(!::video.isInitialized){super.onStop();return}
        val position=position();val duration=duration();playWhenReady=exo?.playWhenReady?:vlc?.isPlaying?:playWhenReady;positionMs=position
        active=false;main.removeCallbacks(tick);if(!transitioning)saveProgress(position,duration,ended);serverReport("stop");releaseEngine()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);super.onStop()
    }
}
