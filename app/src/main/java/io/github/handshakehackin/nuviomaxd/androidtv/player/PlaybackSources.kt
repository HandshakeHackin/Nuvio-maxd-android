package io.github.handshakehackin.nuviomaxd.androidtv.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.github.handshakehackin.nuviomaxd.androidtv.BuildConfig
import io.github.handshakehackin.nuviomaxd.androidtv.core.CoreBridge
import io.github.handshakehackin.nuviomaxd.androidtv.core.strings
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

object HeaderScope {
    fun origin(url:String):String?=try{val uri=URI(url);if(uri.scheme !in listOf("http","https")||uri.host.isNullOrBlank()||uri.rawUserInfo!=null)null
        else "${uri.scheme!!.lowercase()}://${uri.host!!.lowercase()}:${if(uri.port!=-1)uri.port else if(uri.scheme=="https")443 else 80}"}catch(_:Exception){null}
    fun headers(value:JSONObject):Map<String,String>{
        val output=linkedMapOf<String,String>()
        value.optJSONArray("request_headers")?.strings()?.forEach{line->val index=line.indexOf(':')
            if(index>0){val key=line.substring(0,index).trim();val text=line.substring(index+1).trim()
                if(key.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+"))&&text.none{it=='\r'||it=='\n'}&&key.lowercase() !in setOf("host","content-length","connection","range"))output[key]=text
            }
        };return output
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object PlaybackSources {
    fun factory(context:Context,value:JSONObject):DataSource.Factory{
        val url=value.optString("url");val origin=HeaderScope.origin(url);val headers=HeaderScope.headers(value)
        val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS)
            .addNetworkInterceptor{chain->
                val request=chain.request();val builder=request.newBuilder()
                if(HeaderScope.origin(request.url.toString())!=origin)headers.keys.filterNot{it.equals("User-Agent",true)}.forEach{builder.removeHeader(it)}
                chain.proceed(builder.build())
            }.build()
        val http=OkHttpDataSource.Factory(client).setUserAgent("NuvioMaxdAndroidTV/${BuildConfig.VERSION_NAME}").setDefaultRequestProperties(headers)
        val ordinary=DefaultDataSource.Factory(context,http)
        return DataSource.Factory{if(url.startsWith("torrent://"))TorrentDataSource(value.optString("profileKey"))else ordinary.createDataSource()}
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class TorrentDataSource(private val profileKey:String):BaseDataSource(true){
    private var handle=0L
    private var offset=0L
    private var remaining=0L
    private var uri:Uri?=null
    private var opened=false
    override fun open(dataSpec:DataSpec):Long{
        transferInitializing(dataSpec)
        uri=dataSpec.uri
        val hash=uri?.host.orEmpty();val index=uri?.lastPathSegment?.toIntOrNull()?:throw IOException("Invalid torrent file.")
        val reader=CoreBridge.nativeOpenTorrent(hash,index,profileKey);handle=reader[0];offset=dataSpec.position
        if(offset>reader[1]){close();throw IOException("Torrent seek is outside the file.")}
        remaining=if(dataSpec.length==C.LENGTH_UNSET.toLong())reader[1]-offset else minOf(dataSpec.length,reader[1]-offset)
        transferStarted(dataSpec);opened=true;return remaining
    }
    override fun read(buffer:ByteArray,offset:Int,length:Int):Int{
        if(length==0)return 0
        if(remaining==0L)return C.RESULT_END_OF_INPUT
        val bytes=CoreBridge.nativeReadTorrent(handle,this.offset,minOf(length.toLong(),remaining,256*1024L).toInt())
        if(bytes.isEmpty())throw IOException("Torrent data ended before the requested range was available.")
        bytes.copyInto(buffer,offset);this.offset+=bytes.size;remaining-=bytes.size;bytesTransferred(bytes.size);return bytes.size
    }
    override fun getUri()=uri
    override fun close(){if(handle!=0L){CoreBridge.nativeCloseTorrent(handle);handle=0};if(opened){opened=false;transferEnded()};uri=null}
}
