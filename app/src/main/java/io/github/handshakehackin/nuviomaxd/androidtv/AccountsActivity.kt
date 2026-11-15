package io.github.handshakehackin.nuviomaxd.androidtv

import io.github.handshakehackin.nuviomaxd.androidtv.core.*
import org.json.JSONObject

class AccountsActivity:TvActivity(){
    override fun render(){when(payload.optString("screen")){"services"->services();"trackers"->trackers();else->account()}}
    private fun account(){
        screen("Account & profiles")
        if(!CoreBridge.signedIn){
            text("Sign in with your phone to load your Nuvio account, profiles, addons and watch progress.")
            button("Link this Android TV"){work("account.pair"){value->val code=obj(value)
                pair("Nuvio sign-in",code.optString("url"),code.optString("displayCode"),code.optInt("interval",3),code.optInt("expires",600),
                    "account.poll",JSONObject().put("code",code.optString("code")),"account.cancel"){work("settings"){render()}}
            }}
            button("Continue as Guest"){finish()};return
        }
        button("Refresh profiles"){render()}
        if(CoreBridge.context.optBoolean("pending"))text("Your login is saved. Reconnect and refresh profiles to verify the account before using its saved settings and services.")
        button("Create a profile"){form("Create Nuvio profile",listOf("Profile name" to false)){values->work("profiles.create",JSONObject().put("name",values[0])){render()}}}
        button("Sign out"){confirm("Sign out on this Android TV?"){work("account.logout"){work("settings"){finish()}}}}
        work("profiles"){value->val result=obj(value);text(result.optString("userEmail"))
            result.optJSONArray("profiles")?.objects()?.forEach{profile->button(profile.optString("name","Profile ${profile.optInt("profile_index")}")){
                val select:(String)->Unit={pin->work("profiles.select",JSONObject().put("index",profile.optInt("profile_index")).put("pin",pin)){work("settings"){finish()}}}
                if(profile.optBoolean("pin_enabled"))form("Profile PIN",listOf("PIN" to true)){values->select(values[0])}else select("")
            }}
        }
    }
    private fun services(){
        screen("Connected services")
        button("Trakt · SIMKL · MDBList"){go(AccountsActivity::class.java,JSONObject().put("screen","trackers"))}
        button("Import supported connections from Nuvio Sync"){work("services.import"){render()}}
        button("Pair TorBox with your phone"){work("services.torbox.start"){value->val code=obj(value)
            pair("TorBox",code.optString("url"),code.optString("code"),code.optInt("interval",5),code.optInt("expires",600),"services.torbox.poll"){render()}
        }}
        work("services"){value->val state=obj(value)
            button("Background link preparation: ${state.optInt("instantLimit",2)}"){
                choose("Prepare original links in advance",listOf("Off","1 source","2 sources","3 sources","5 sources")){index->val limit=listOf(0,1,2,3,5)[index]
                    work("services.options",JSONObject().put("instantLimit",limit)){render()}
                }
            }
            button("Preferred service: ${state.optString("preferred").ifBlank{"Automatic"}}"){
                choose("Preferred connected service",listOf("Automatic","TorBox","Premiumize")){index->work("services.options",JSONObject().put("preferred",listOf("","torbox","premiumize")[index])){render()}}
            }
            listOf("torbox" to "TorBox","premiumize" to "Premiumize","animeskip" to "Anime-Skip client ID").forEach{(provider,name)->
                val connected=state.optBoolean(if(provider=="animeskip")"animeSkip" else provider)
                button("$name · ${if(connected)"Connected" else "Not connected"}"){
                    choose(name,listOf("Connect / enter key","Disconnect on this TV")){action->
                        if(action==1)work("services.save",JSONObject().put("provider",provider).put("key","")){render()}
                        else form(name,listOf((if(provider=="animeskip")"Client ID" else "API key") to true)){values->
                            work("services.save",JSONObject().put("provider",provider).put("key",values[0])){render()}
                        }
                    }
                }
            }
        }
    }
    private fun tracking(action:String,args:JSONObject=JSONObject(),done:(Any?)->Unit){work("tracking",JSONObject().put("action",action).put("arguments",args),done)}
    private fun trackers(){
        screen("Tracking accounts")
        if(!CoreBridge.signedIn){text("Sign in and select a Nuvio profile before connecting trackers.");return}
        tracking("status"){value->val state=obj(value)
            listOf("library" to "Library source","progress" to "Watch progress source").forEach{(field,label)->button("$label · ${state.optString(field,"nuvio")}"){
                val options=listOf("nuvio","trakt","simkl","mdblist");choose(label,options){index->tracking("source",JSONObject().put("field",field).put("value",options[index])){render()}}
            }}
            listOf("trakt","simkl","mdblist").forEach{provider->
                val connected=state.optJSONObject("accounts")?.optJSONObject(provider)?.optBoolean("connected")?:false
                button("${provider.uppercase()} · ${if(connected)"Connected" else "Not connected"}"){
                    choose(provider.uppercase(),listOf("Connect","Import Nuvio connection","Disconnect","Configure public client ID")){action->
                        val args=JSONObject().put("provider",provider)
                        when(action){
                            0->if(provider=="mdblist")form("MDBList",listOf("API key" to true)){values->tracking("key",args.put("api_key",values[0])){render()}}
                            else tracking("device_start",args){valueCode->val code=obj(valueCode)
                                pair(provider.uppercase(),code.optString("url"),code.optString("code"),code.optInt("interval",5),code.optInt("expires_in",600),"tracking",
                                    JSONObject().put("action","device_poll").put("arguments",args)){render()}
                            }
                            1->tracking("import_sync",args){render()}
                            2->tracking("disconnect",args){render()}
                            3->form("Public application client ID",listOf("Client ID" to false)){values->tracking("configure",args.put("client_id",values[0])){render()}}
                        }
                    }
                }
            }
        }
    }
}
