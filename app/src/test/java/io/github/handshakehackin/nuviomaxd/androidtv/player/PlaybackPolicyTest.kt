package io.github.handshakehackin.nuviomaxd.androidtv.player

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest{
    @Test fun headerOriginsIncludeSchemeHostAndEffectivePort(){
        assertEquals(HeaderScope.origin("https://example.com/video"),HeaderScope.origin("https://example.com:443/other"))
        assertNotEquals(HeaderScope.origin("https://example.com/video"),HeaderScope.origin("http://example.com/video"))
        assertNotEquals(HeaderScope.origin("https://example.com/video"),HeaderScope.origin("https://example.com:8443/video"))
        assertNull(HeaderScope.origin("https://name:password@example.com/video"));assertNull(HeaderScope.origin("file:///etc/passwd"))
    }
    @Test fun addonHeadersCannotInjectRequestsOrOverrideTransportFraming(){
        val value=JSONObject().put("request_headers",JSONArray(listOf("Authorization: Bearer private","Host: evil.invalid","Range: bytes=0-","X-Test: value\r\nOther: bad","Referer: https://origin.invalid/")))
        val headers=HeaderScope.headers(value)
        assertEquals(2,headers.size);assertEquals("Bearer private",headers["Authorization"]);assertFalse(headers.containsKey("Host"));assertFalse(headers.containsKey("X-Test"))
    }
    @Test fun pcmIdentityAndClippingPreserveSixteenBitExtremes(){
        for(sample in listOf(-32768,-12345,-1,0,1,12345,32767))assertEquals(sample.toShort(),PcmMix.sample(sample/32768f,1f,0))
        assertEquals(32767.toShort(),PcmMix.sample(1f,3f,0));assertEquals((-32768).toShort(),PcmMix.sample(-1f,3f,0))
    }
    @Test fun surroundDownmixKeepsCenterSymmetricAndLfeOptional(){
        val center=PcmMix.stereo(floatArrayOf(0f,0f,1f,0f,0f,0f),0f)
        assertEquals(center.first,center.second,0.0001f)
        val lfeOff=PcmMix.stereo(floatArrayOf(0f,0f,0f,1f,0f,0f),0f)
        val lfeOn=PcmMix.stereo(floatArrayOf(0f,0f,0f,1f,0f,0f),0.5f)
        assertEquals(0f,lfeOff.first,0f);assertEquals(0.5f,lfeOn.first,0.0001f)
    }
    @Test fun nightModeReducesLoudPeaksWithoutChangingPolarity(){
        val normal=PcmMix.sample(0.9f,1f,0).toInt();val night=PcmMix.sample(0.9f,1f,2).toInt()
        assertTrue(night in 1 until normal);assertTrue(PcmMix.sample(-0.9f,1f,2)<0)
    }
}
