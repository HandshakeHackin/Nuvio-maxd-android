package io.github.handshakehackin.nuviomaxd.androidtv.core

import android.graphics.Color
import android.hardware.lights.LightState
import android.hardware.lights.LightsManager
import android.hardware.lights.LightsRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice

/** Android exposes RGB lights on some input devices, independently of TV lighting. */
object ControllerLights {
    private val main=Handler(Looper.getMainLooper())
    private var active=false
    private val tick=object:Runnable{override fun run(){
        if(!active)return
        if(Build.VERSION.SDK_INT>=31){
            if(CoreBridge.settings.optBoolean("controllerLights"))runCatching{Devices.update()}else Devices.close()
        }
        main.postDelayed(this,500)
    }}
    fun start(){active=true;main.removeCallbacks(tick);main.post(tick)}
    fun stop(){active=false;main.removeCallbacks(tick);if(Build.VERSION.SDK_INT>=31)Devices.close()}

    @androidx.annotation.RequiresApi(31)
    private object Devices {
        private val sessions=mutableMapOf<Int,Pair<LightsManager,LightsManager.LightsSession>>()
        private val colors=intArrayOf(0xff26ddd2.toInt(),Color.WHITE,0xffff62ae.toInt(),0xff9b66e8.toInt())
        fun update(){
            val ids=InputDevice.getDeviceIds().toSet()
            sessions.keys.toList().filter{it !in ids}.forEach{sessions.remove(it)?.second?.close()}
            ids.filter{it !in sessions}.forEach{id->runCatching{
                val device=InputDevice.getDevice(id)?:return@runCatching
                if(!device.supportsSource(InputDevice.SOURCE_GAMEPAD))return@runCatching
                val manager=device.lightsManager
                if(manager.lights.any{it.hasRgbControl()})sessions[id]=manager to manager.openSession()
            }}
            val phase=(SystemClock.elapsedRealtime()%24000)/6000f
            val index=phase.toInt();val fraction=phase-index
            fun channel(from:Int,to:Int)=(from+(to-from)*fraction).toInt()
            val a=colors[index];val b=colors[(index+1)%colors.size]
            val color=Color.rgb(channel(Color.red(a),Color.red(b)),channel(Color.green(a),Color.green(b)),channel(Color.blue(a),Color.blue(b)))
            sessions.values.forEach{(manager,session)->runCatching{
                val request=LightsRequest.Builder()
                manager.lights.filter{it.hasRgbControl()}.forEach{request.addLight(it,LightState.Builder().setColor(color).build())}
                session.requestLights(request.build())
            }}
        }
        fun close(){sessions.values.forEach{runCatching{it.second.close()}};sessions.clear()}
    }
}
