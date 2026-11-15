package io.github.handshakehackin.nuviomaxd.androidtv.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Only explicitly selected technical fields are retained; no exception messages or URLs. */
object Diagnostics {
    private var file:File?=null
    private val events=JSONArray()
    fun initialize(context:Context){
        file=File(context.filesDir,"playback-diagnostics.json")
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler{thread,error->
            runCatching{record("crash",JSONObject().put("class",error.javaClass.name).put("frames",JSONArray(error.stackTrace.take(12).map{"${it.className}.${it.methodName}:${it.lineNumber}"})))}
            previous?.uncaughtException(thread,error)
        }
    }
    @Synchronized fun record(kind:String,details:JSONObject=JSONObject()){
        events.put(JSONObject().put("event",kind).put("time",System.currentTimeMillis()).put("details",details))
        while(events.length()>40)events.remove(0)
        runCatching{file?.writeText(events.toString(2))}
    }
    @Synchronized fun report():String=runCatching{file?.takeIf{it.exists()}?.readText()?.take(24000)}.getOrNull()?:"No playback diagnostics recorded yet."
}
