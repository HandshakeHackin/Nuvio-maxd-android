package io.github.handshakehackin.nuviomaxd.androidtv.core

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.handshakehackin.nuviomaxd.androidtv.R
import io.github.handshakehackin.nuviomaxd.androidtv.dp
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object Artwork {
    private data class Request(val profile:String,val url:String,val server:String,val id:String)
    private val requests=java.util.Collections.synchronizedMap(java.util.WeakHashMap<ImageView,Request>())
    private val worker=ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,ArrayBlockingQueue(64),ThreadPoolExecutor.DiscardOldestPolicy())
    private val main=Handler(Looper.getMainLooper())
    fun load(view:ImageView,url:String="",server:String="",id:String=""){
        val context=view.context.applicationContext;val profile=CoreBridge.profileKey
        requests[view]=Request(profile,url,server,id)
        val token="$profile:$url:$server:$id";view.tag=token
        worker.execute{
            if(view.tag!=token||CoreBridge.profileKey!=profile)return@execute
            try{
                val file=if(server.isNotEmpty()){
                    val result=CoreBridge.objectResult("servers.image",JSONObject().put("profileKey",profile).put("server",server).put("id",id))
                    File(result.getString("path"))
                }else{
                    val uri=URI(url);if(uri.scheme !in listOf("http","https")||uri.host.isNullOrBlank()||uri.rawUserInfo!=null)return@execute
                    val directory=File(context.cacheDir,"posters").apply{mkdirs()}
                    val hash=MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString(""){"%02x".format(it)}
                    val destination=File(directory,hash)
                    if(!destination.exists()){
                        val connection=uri.toURL().openConnection()as HttpURLConnection
                        connection.connectTimeout=8000;connection.readTimeout=8000
                        try{
                            if(connection.responseCode!=200)return@execute
                            val bytes=connection.inputStream.use{input->val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                                while(true){val read=input.read(buffer);if(read<0)break;if(output.size()+read>8*1024*1024)throw java.io.IOException("Artwork too large");output.write(buffer,0,read)};output.toByteArray()}
                            val temporary=File(directory,"$hash.part");temporary.writeBytes(bytes);temporary.renameTo(destination)
                            val files=directory.listFiles().orEmpty().sortedByDescending{it.lastModified()};var total=0L
                            files.forEach{image->total+=image.length();if(total>128L*1024*1024)image.delete()}
                        }finally{connection.disconnect()}
                    };destination
                }
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.absolutePath,bounds)
                var sample=1;while(bounds.outHeight/sample>192||bounds.outWidth/sample>192)sample*=2
                val bitmap=BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply{inSampleSize=sample})?:return@execute
                main.post{if(view.tag==token&&CoreBridge.profileKey==profile&&view.isShown)view.setImageBitmap(bitmap)}
            }catch(_:Exception){/* Missing artwork does not prevent browsing or playback. */}
        }
    }
    fun pause(view:android.view.View){
        if(view is ImageView&&requests.containsKey(view))view.setImageDrawable(null)
        if(view is android.view.ViewGroup)for(index in 0 until view.childCount)pause(view.getChildAt(index))
    }
    fun resume(view:android.view.View){
        if(view is ImageView)requests[view]?.let{if(it.profile==CoreBridge.profileKey)load(view,it.url,it.server,it.id)}
        if(view is android.view.ViewGroup)for(index in 0 until view.childCount)resume(view.getChildAt(index))
    }
}

fun Activity.posterRow(body:LinearLayout,name:String,subtitle:String="",url:String="",server:String="",id:String="",action:()->Unit):LinearLayout{
    val row=LinearLayout(this).apply{
        orientation=LinearLayout.HORIZONTAL;isFocusable=true;isClickable=true;setPadding(dp(12),dp(8),dp(16),dp(8));setBackgroundResource(if(CoreBridge.settings.optBoolean("highContrast"))R.drawable.tv_button_contrast else R.drawable.tv_button)
        isSoundEffectsEnabled=CoreBridge.settings.optBoolean("uiSounds")
        layoutParams=LinearLayout.LayoutParams(-1,dp(120)).apply{bottomMargin=dp(10)}
        setOnClickListener{action()}
    }
    val image=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP;contentDescription=name;setImageResource(R.drawable.nuvio_logo)}
    row.addView(image,LinearLayout.LayoutParams(dp(68),dp(102)).apply{marginEnd=dp(18)})
    row.addView(TextView(this).apply{text=if(subtitle.isBlank())name else "$name\n$subtitle";textSize=21f;setTextColor(Color.WHITE);maxLines=4},LinearLayout.LayoutParams(0,-2,1f))
    body.addView(row)
    if(url.isNotBlank()||server.isNotBlank())Artwork.load(image,url,server,id)
    return row
}
