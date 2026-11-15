package io.github.handshakehackin.nuviomaxd.androidtv.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProfileStateTest{
    private fun profile(key:String)=JSONObject().put("profileKey",key)
    @Test fun lateWorkerCannotRestoreAnotherProfile(){
        val state=ProfileState();state.accept(1,profile("account-a:1"));state.accept(3,profile("account-a:2"))
        assertFalse(state.accept(2,profile("account-a:1"),JSONObject().put("engine","vlc")))
        assertEquals("account-a:2",state.context.getString("profileKey"));assertEquals(0,state.settings.length())
    }
    @Test fun lateGuestResponseCannotReplaceASignedInAccount(){
        val state=ProfileState();state.accept(1,profile(""));state.accept(3,profile("account-b:1"))
        assertFalse(state.accept(2,profile("")));assertEquals("account-b:1",state.context.getString("profileKey"))
    }
    @Test fun oldSettingsReadCannotUndoANewerSave(){
        val state=ProfileState();state.accept(1,profile("a"));state.accept(3,profile("a"),JSONObject().put("gain",150))
        assertTrue(state.accept(2,profile("a"),JSONObject().put("gain",100)))
        assertEquals(150,state.settings.getInt("gain"))
    }
    @Test fun profileSwitchClearsOldPreferencesUntilNewPreferencesArrive(){
        val state=ProfileState();state.accept(1,profile("a"),JSONObject().put("torrents",true));state.accept(2,profile("b"))
        assertEquals(0,state.settings.length());state.accept(3,profile("b"),JSONObject().put("torrents",false))
        assertFalse(state.settings.getBoolean("torrents"))
    }
}
