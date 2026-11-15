package io.github.handshakehackin.nuviomaxd.androidtv.player

data class SkipSegment(val kind:String,val start:Double,val end:Double)

class EpisodeFlow {
    var segments=listOf<SkipSegment>()
    var cancelled=false
        private set
    private var suppressed:SkipSegment?=null
    private var lastPosition=-1.0
    private var counting=false
    var remaining=10.0
        private set
    var active:SkipSegment?=null
        private set
    var nextVisible=false
        private set
    fun update(position:Double,duration:Double,elapsed:Double,advancing:Boolean,nextReady:Boolean,autoplay:Boolean,countdown:Int,ended:Boolean=false):Boolean{
        if(position<lastPosition-2){suppressed=null;counting=false;remaining=countdown.coerceIn(5,120).toDouble()}
        lastPosition=position
        active=segments.firstOrNull{it.kind!="Post-credits"&&position>=it.start&&position<it.end&&it!=suppressed}
        val lastScene=segments.filter{it.kind=="Post-credits"}.maxOfOrNull{it.end}?:0.0
        val credit=segments.any{it.kind=="Credits"&&position>=it.start}
        val nearEnd=duration>0&&position>=duration-minOf(45.0,duration*0.05)
        nextVisible=nextReady&&!cancelled&&autoplay&&(ended||((credit||nearEnd)&&position>=lastScene))
        if(nextVisible){if(!counting){remaining=countdown.coerceIn(5,120).toDouble();counting=true}else if(advancing||ended)remaining-=elapsed.coerceIn(0.0,1.0)}
        else{counting=false;remaining=countdown.coerceIn(5,120).toDouble()}
        return nextVisible&&remaining<=0&&!cancelled
    }
    fun skipped(){suppressed=active;active=null}
    fun cancel(){cancelled=true;nextVisible=false;counting=false}
}
