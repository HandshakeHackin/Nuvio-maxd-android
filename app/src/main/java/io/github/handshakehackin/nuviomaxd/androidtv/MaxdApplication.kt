package io.github.handshakehackin.nuviomaxd.androidtv

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.handshakehackin.nuviomaxd.androidtv.core.CoreBridge
import io.github.handshakehackin.nuviomaxd.androidtv.core.ControllerLights
import io.github.handshakehackin.nuviomaxd.androidtv.core.Diagnostics
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MaxdApplication:Application(),DefaultLifecycleObserver{
    private val refresh=Executors.newSingleThreadScheduledExecutor()
    private var task:ScheduledFuture<*>?=null
    override fun onCreate(){super<Application>.onCreate();Diagnostics.initialize(this);ProcessLifecycleOwner.get().lifecycle.addObserver(this)}
    override fun onStart(owner:LifecycleOwner){
        ControllerLights.start()
        task?.cancel(false)
        task=refresh.scheduleWithFixedDelay({runCatching{
            CoreBridge.initialize(this)
            CoreBridge.request("app.foreground")
            if(CoreBridge.signedIn)CoreBridge.request("library")
        }},0,60,TimeUnit.SECONDS)
    }
    override fun onStop(owner:LifecycleOwner){
        ControllerLights.stop()
        task?.cancel(true);task=null;CoreBridge.cancel()
        refresh.submit{runCatching{CoreBridge.request("app.background")}}
    }
}
