package io.github.handshakehackin.nuviomaxd.androidtv.core

import org.json.JSONObject

/** Prevent a late worker response from restoring a previous account/profile. */
class ProfileState {
    @Volatile var context=JSONObject()
        private set
    @Volatile var settings=JSONObject()
        private set
    private var responseSequence=-1L
    private var settingsSequence=-1L
    @Synchronized fun accept(sequence:Long,incoming:JSONObject,settingsResult:JSONObject?=null):Boolean{
        if(sequence>=responseSequence){
            if(incoming.optString("profileKey")!=context.optString("profileKey")){
                settings=JSONObject();settingsSequence=sequence
            }
            context=incoming;responseSequence=sequence
        }
        val current=incoming.optString("profileKey")==context.optString("profileKey")
        if(current&&settingsResult!=null&&sequence>=settingsSequence){settings=settingsResult;settingsSequence=sequence}
        return current
    }
}
