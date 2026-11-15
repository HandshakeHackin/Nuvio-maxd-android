package io.github.handshakehackin.nuviomaxd.androidtv.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.KeyEvent
import android.view.View

class PlaybackTimeline(context:Context):View(context){
    var duration=0L
    var position=0L
    var chapters=listOf<Pair<Long,String>>()
    var seekStep=10000L
    var seek:(Long)->Unit={}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    init{isFocusable=true;minimumHeight=(32*resources.displayMetrics.density).toInt();contentDescription="Playback timeline. Left and right seek."}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        val mid=height/2f;val thickness=if(hasFocus())6f else 3f
        paint.color=Color.GRAY;canvas.drawRect(0f,mid-thickness,width.toFloat(),mid+thickness,paint)
        if(duration<=0)return
        paint.color=0xff26ddd2.toInt();canvas.drawRect(0f,mid-thickness,width*position.coerceIn(0,duration).toFloat()/duration,mid+thickness,paint)
        paint.color=Color.WHITE
        chapters.forEach{val x=width*it.first.toFloat()/duration;canvas.drawRect(x-1,mid-10,x+1,mid+10,paint)}
    }
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean{
        if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT||keyCode==KeyEvent.KEYCODE_DPAD_RIGHT){seek((position+if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT)-seekStep else seekStep).coerceIn(0,duration.coerceAtLeast(1)-1));return true}
        return super.onKeyDown(keyCode,event)
    }
}
