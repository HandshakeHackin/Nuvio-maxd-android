package io.github.handshakehackin.nuviomaxd.androidtv.player

import org.junit.Assert.*
import org.junit.Test

class EpisodeFlowTest{
    @Test fun autoplayWaitsUntilAfterPostCreditScenes(){
        val flow=EpisodeFlow();flow.segments=listOf(SkipSegment("Credits",900.0,940.0),SkipSegment("Post-credits",940.0,980.0))
        repeat(20){assertFalse(flow.update(930.0,1000.0,1.0,true,true,true,5))};assertFalse(flow.nextVisible)
        assertFalse(flow.update(981.0,1000.0,1.0,true,true,true,5));assertTrue(flow.nextVisible)
        var launch=false;repeat(5){launch=flow.update(986.0,1000.0,1.0,true,true,true,5)};assertTrue(launch)
    }
    @Test fun pauseAndBufferingHoldTheCountdown(){
        val flow=EpisodeFlow();flow.update(990.0,1000.0,0.5,true,true,true,10)
        repeat(50){assertFalse(flow.update(990.0,1000.0,1.0,false,true,true,10))};assertEquals(10.0,flow.remaining,0.001)
    }
    @Test fun missingNextStreamNeverStartsACountdown(){
        val flow=EpisodeFlow();repeat(20){assertFalse(flow.update(999.0,1000.0,1.0,true,false,true,5))};assertFalse(flow.nextVisible)
    }
    @Test fun cancelRemainsEffectiveAfterRewindAndEndOfFile(){
        val flow=EpisodeFlow();flow.update(990.0,1000.0,1.0,true,true,true,5);flow.cancel()
        flow.update(100.0,1000.0,1.0,true,true,true,5)
        repeat(20){assertFalse(flow.update(1000.0,1000.0,1.0,false,true,true,5,true))};assertFalse(flow.nextVisible)
    }
    @Test fun skippedIntroCanBeOfferedAgainAfterSeekingBack(){
        val flow=EpisodeFlow();flow.segments=listOf(SkipSegment("Intro",20.0,80.0))
        flow.update(25.0,1000.0,0.5,true,false,false,10);assertNotNull(flow.active);flow.skipped()
        flow.update(26.0,1000.0,0.5,true,false,false,10);assertNull(flow.active)
        flow.update(10.0,1000.0,0.5,true,false,false,10);flow.update(30.0,1000.0,0.5,true,false,false,10);assertNotNull(flow.active)
    }
}
