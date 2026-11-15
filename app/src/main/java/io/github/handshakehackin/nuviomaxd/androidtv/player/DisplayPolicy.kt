package io.github.handshakehackin.nuviomaxd.androidtv.player

import android.app.Activity
import android.os.Build
import android.view.SurfaceView
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.round

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object DisplayPolicy {
    @Suppress("DEPRECATION")
    fun apply(activity:Activity,settings:JSONObject,fps:Float,view:PlayerView?=null){
        val display=activity.windowManager.defaultDisplay
        val current=display.mode
        val desiredHeight=settings.optString("resolution","system").toIntOrNull()
        val candidates=display.supportedModes.filter{if(desiredHeight==null)it.physicalWidth==current.physicalWidth&&it.physicalHeight==current.physicalHeight else it.physicalHeight==desiredHeight||it.physicalWidth==desiredHeight}
        val refresh=settings.optString("refresh","match")
        val selected=when(refresh){
            "60","120"->candidates.minByOrNull{abs(it.refreshRate-refresh.toFloat())}
            "match"->if(fps>0)candidates.minByOrNull{mode->val multiple=mode.refreshRate/fps;abs(multiple-round(multiple))*100+abs(mode.refreshRate-current.refreshRate)*0.001f}else null
            else->if(desiredHeight!=null)candidates.minByOrNull{abs(it.refreshRate-current.refreshRate)}else null
        }
        if(selected!=null)activity.window.attributes=activity.window.attributes.apply{preferredDisplayModeId=selected.modeId}
        if(Build.VERSION.SDK_INT>=30&&refresh=="match"&&fps>0){
            val surface=(view?.videoSurfaceView as? SurfaceView)?.holder?.surface
            runCatching{surface?.setFrameRate(fps,android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)}
        }
    }
    @Suppress("DEPRECATION")fun hdrSupported(activity:Activity)=activity.windowManager.defaultDisplay.hdrCapabilities.supportedHdrTypes.isNotEmpty()
    @Suppress("DEPRECATION")fun description(activity:Activity):String{
        val mode=activity.windowManager.defaultDisplay.mode
        return "Display ${mode.physicalWidth}×${mode.physicalHeight} · ${"%.2f".format(mode.refreshRate)} Hz\nHDR display capability: ${if(hdrSupported(activity))"Yes" else "No"}"
    }
}
