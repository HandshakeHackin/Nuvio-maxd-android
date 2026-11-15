package io.github.handshakehackin.nuviomaxd.androidtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.handshakehackin.nuviomaxd.androidtv.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Future

internal object Routes {
    fun open(activity: Activity, target: Class<out Activity>, payload: JSONObject) {
        val directory = File(activity.cacheDir,"routes").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        File(directory,"$id.json").writeText(payload.toString())
        directory.listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(64).forEach { it.delete() }
        activity.startActivity(Intent(activity,target).putExtra("route",id))
        if(CoreBridge.settings.optBoolean("reducedMotion"))activity.overridePendingTransition(0,0)
    }
    fun read(activity: Activity): JSONObject {
        val id=activity.intent.getStringExtra("route").orEmpty()
        if(!id.matches(Regex("[a-f0-9-]{36}")))return JSONObject()
        return runCatching { JSONObject(File(activity.cacheDir,"routes/$id.json").readText()) }.getOrDefault(JSONObject())
    }
}

abstract class TvActivity : Activity() {
    protected val main=Handler(Looper.getMainLooper())
    protected lateinit var body: LinearLayout
    protected lateinit var status: TextView
    protected var payload=JSONObject()
    protected var ready=false
    protected var generation=0
    private var renderedProfile=""
    private val jobs=mutableListOf<Future<*>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        payload=Routes.read(this)
        screen("Nuvio Max'd")
        status.text="Opening your profile…"
        jobs += CoreBridge.workers.submit {
            try { CoreBridge.initialize(applicationContext);CoreBridge.request("app.foreground");main.post { if(!isDestroyed&&!isFinishing){ready=true;renderedProfile=CoreBridge.profileKey;render()} } }
            catch(_:Throwable){main.post{if(!isDestroyed)status.text="Max'd could not start. Check that this APK supports your TV and reinstall the complete APK."}}
        }
    }
    protected abstract fun render()
    override fun onStart(){super.onStart();if(ready&&renderedProfile!=CoreBridge.profileKey&&javaClass!=MainActivity::class.java)finish()}
    override fun onResume(){super.onResume();if(ready&&javaClass==MainActivity::class.java)renderedProfile=CoreBridge.profileKey;if(ready&&::body.isInitialized)Artwork.resume(body)}
    override fun onStop(){if(::body.isInitialized)Artwork.pause(body);super.onStop()}
    protected fun screen(title: String, back: Boolean=true) {
        generation++
        body=tvScreen(title);status=tvText(body,"")
        if(back && javaClass!=MainActivity::class.java)button("‹ Back"){finish()}
    }
    protected fun button(label: String, action:()->Unit)=tvButton(body,label){action()}
    protected fun text(value: String)=tvText(body,value)
    protected fun go(target: Class<out Activity>, args: JSONObject=JSONObject()) {
        CoreBridge.cancel();Routes.open(this,target,args)
    }
    protected fun play(value: JSONObject) {
        value.put("profileKey",CoreBridge.profileKey)
        go(PlayerActivity::class.java,value)
        if(CoreBridge.settings.optBoolean("reducedMotion"))overridePendingTransition(0,0)
    }
    protected fun message(value: String){if(!isFinishing)AlertDialog.Builder(this).setMessage(value).setPositiveButton(android.R.string.ok,null).show()}
    protected fun choose(title: String, labels: List<String>, action:(Int)->Unit){
        if(labels.isEmpty()){message("Nothing is available here yet.");return}
        AlertDialog.Builder(this).setTitle(title).setItems(labels.toTypedArray()){_,index->action(index)}.setNegativeButton(android.R.string.cancel,null).show()
    }
    protected fun confirm(title: String, action:()->Unit){AlertDialog.Builder(this).setMessage(title).setPositiveButton(android.R.string.ok){_,_->action()}.setNegativeButton(android.R.string.cancel,null).show()}
    protected fun qr(title:String,url:String){
        val bits=QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,320,320)
        val pixels=IntArray(320*320){index->if(bits[index%320,index/320])Color.BLACK else Color.WHITE}
        val bitmap=Bitmap.createBitmap(pixels,320,320,Bitmap.Config.ARGB_8888)
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(16),dp(24),dp(16))}
        layout.addView(ImageView(this).apply{setImageBitmap(bitmap)},LinearLayout.LayoutParams(dp(220),dp(220)))
        layout.addView(TextView(this).apply{text=url;textSize=18f;setTextIsSelectable(true)})
        AlertDialog.Builder(this).setTitle(title).setView(layout).setPositiveButton(android.R.string.ok,null).show()
    }
    protected fun form(title: String, fields: List<Pair<String,Boolean>>, submit:(List<String>)->Unit){
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(8))}
        val inputs=fields.map{(label,secret)->EditText(this).apply{
            hint=label;isSingleLine=true
            inputType=InputType.TYPE_CLASS_TEXT or if(secret)InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_NORMAL
            importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO;layout.addView(this)
        }}
        val dialog=AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply{addView(layout)})
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{dialog.dismiss();submit(inputs.map{it.text.toString().trim()})}}
        dialog.show();inputs.firstOrNull()?.requestFocus()
    }
    protected fun work(command: String,args: JSONObject=JSONObject(),done:(Any?)->Unit){
        workInternal(command,args,true,done)
    }
    protected fun quietly(command: String,args: JSONObject=JSONObject(),done:(Any?)->Unit){
        workInternal(command,args,false,done)
    }
    private fun workInternal(command: String,args: JSONObject,visible:Boolean,done:(Any?)->Unit){
        val token=generation;val request=JSONObject(args.toString())
        if(!request.has("profileKey"))request.put("profileKey",CoreBridge.profileKey)
        if(visible)status.text="Loading…"
        jobs+=CoreBridge.workers.submit{
            try {val result=CoreBridge.request(command,request);main.post{if(alive(token)){if(visible)status.text="";done(result)}}}
            catch(error:Exception){main.post{if(visible&&alive(token))status.text=if(error is CoreException)error.message else "The request failed. Check your connection and retry."}}
        }
    }
    protected fun alive(token: Int)=token==generation&&!isDestroyed&&!isFinishing
    protected fun obj(value: Any?)=value as? JSONObject?:JSONObject()
    protected fun arr(value: Any?)=value as? JSONArray?:JSONArray()
    protected fun pair(title:String,url:String,code:String,interval:Int=5,expires:Int=600,command:String,args:JSONObject=JSONObject(),cancelCommand:String?=null,done:(JSONObject)->Unit){
        val pollingArgs=JSONObject(args.toString()).put("profileKey",CoreBridge.profileKey)
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(8))}
        val label=TextView(this).apply{text="$url\n\n$code";textSize=20f;setTextIsSelectable(true)};layout.addView(label)
        runCatching{
            val bits=QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,320,320)
            val bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888)
            for(y in 0 until 320)for(x in 0 until 320)bitmap.setPixel(x,y,if(bits[x,y])Color.BLACK else Color.WHITE)
            layout.addView(ImageView(this).apply{setImageBitmap(bitmap)},LinearLayout.LayoutParams(dp(220),dp(220)))
        }
        val dialog=AlertDialog.Builder(this).setTitle(title).setView(layout).setNegativeButton(android.R.string.cancel,null).create()
        val token=generation;val deadline=android.os.SystemClock.elapsedRealtime()+expires.coerceIn(1,3600)*1000L;var active=true
        lateinit var tick:Runnable
        tick=Runnable{
            if(!active||!alive(token))return@Runnable
            if(android.os.SystemClock.elapsedRealtime()>=deadline){label.text="Code expired. Close this screen and request a new code.";return@Runnable}
            CoreBridge.workers.submit{
                try{val result=CoreBridge.objectResult(command,pollingArgs);main.post{
                    if(active&&alive(token)){
                        if(result.optBoolean("pending"))main.postDelayed(tick,interval.coerceIn(2,60)*1000L)
                        else{active=false;dialog.dismiss();done(result)}
                    }
                }}catch(error:Exception){main.post{if(active&&alive(token))label.text=if(error is CoreException)error.message else "Pairing failed. Please try again."}}
            }
        }
        dialog.setOnDismissListener{active=false;main.removeCallbacks(tick);if(cancelCommand!=null)CoreBridge.workers.submit{runCatching{CoreBridge.request(cancelCommand,JSONObject().put("profileKey",pollingArgs.optString("profileKey")))}}}
        dialog.show();main.postDelayed(tick,interval.coerceIn(2,60)*1000L)
    }
    override fun onDestroy(){generation++;jobs.forEach{it.cancel(true)};super.onDestroy()}
    override fun finish(){super.finish();if(CoreBridge.settings.optBoolean("reducedMotion"))overridePendingTransition(0,0)}
}
