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
    private fun sameProfile(left:JSONObject,right:JSONObject)=left.optString("profileKey")==right.optString("profileKey")&&left.optString("profilePath")==right.optString("profilePath")
    @Synchronized fun accept(sequence:Long,incoming:JSONObject,settingsResult:JSONObject?=null):Boolean{
        if(sequence>=responseSequence){
            if(!sameProfile(incoming,context)){
                settings=JSONObject();settingsSequence=sequence
            }
            context=incoming;responseSequence=sequence
        }
        val current=sameProfile(incoming,context)
        if(current&&settingsResult!=null&&sequence>=settingsSequence){settings=settingsResult;settingsSequence=sequence}
        return current
    }
}
