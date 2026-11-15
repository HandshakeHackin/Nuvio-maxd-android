package io.github.handshakehackin.nuviomaxd.androidtv

import io.github.handshakehackin.nuviomaxd.androidtv.core.*
import org.json.JSONObject

class AddonsActivity:TvActivity(){
    override fun render(){if(payload.has("addon"))providers(payload.getJSONObject("addon"))else addons()}
    private fun addons(){
        screen("Addons & JavaScript providers")
        button("Add addon / provider manifest URL"){form("Add a raw manifest.json URL",listOf("Manifest URL" to false)){values->work("addons.add",JSONObject().put("url",values[0])){payload.put("sync",false);render()}}}
        button("Reload from Nuvio"){payload.put("sync",true);render()}
        work("addons",JSONObject().put("sync",payload.optBoolean("sync",true)&&CoreBridge.signedIn)){value->
            arr(value).objects().forEach{addon->button("${if(addon.optBoolean("enabled",true))"✓" else "○"} ${addon.optString("name")}"){
                val options=mutableListOf("Enable / disable on this TV","Remove repository")
                if(addon.optBoolean("plugin"))options+="Manage JavaScript providers"
                choose(addon.optString("name"),options){action->val args=JSONObject().put("index",addon.optInt("index"))
                    when(action){
                        0->work("addons.toggle",args){payload.put("sync",false);render()}
                        1->confirm("Remove this repository? Inherited profile sources remain read-only."){work("addons.remove",args){payload.put("sync",false);render()}}
                        2->go(AddonsActivity::class.java,JSONObject().put("addon",addon))
                    }
                }
            }}
        }
    }
    private fun providers(addon:JSONObject){
        screen(addon.optString("name"))
        text("Providers start disabled. Enable individual scrapers. Some providers require settings or unsupported APIs.")
        addon.optJSONArray("providers")?.objects()?.forEach{provider->button("${if(provider.optBoolean("enabled"))"✓" else "○"} ${provider.optString("name")}"){
            choose(provider.optString("name"),listOf("Enable / disable","Provider settings")){action->
                val args=JSONObject().put("index",addon.optInt("index")).put("provider",provider.optString("id"))
                if(action==0)work("providers.save",args.put("enabled",!provider.optBoolean("enabled"))){provider.put("enabled",!provider.optBoolean("enabled"));render()}
                else work("providers.layout",args){value->val data=obj(value);val fields=data.optJSONArray("layout")?.objects().orEmpty()
                    if(fields.isEmpty()){message("This provider does not expose configurable settings.");return@work}
                    choose("Provider settings",fields.map{it.optString("label",it.optString("key",it.optString("id")))}){index->val field=fields[index];val key=field.optString("key",field.optString("id"))
                        fun save(value:Any){val settings=data.optJSONObject("values")?:JSONObject();settings.put(key,value)
                            work("providers.save",args.put("enabled",provider.optBoolean("enabled")).put("settings",settings)){message("Provider settings saved.")}}
                        val options=field.optJSONArray("options")
                        when{
                            field.optString("type") in listOf("boolean","switch","toggle")->choose(field.optString("label",key),listOf("Off","On")){save(it==1)}
                            options!=null&&options.length()>0->{
                                val values=(0 until options.length()).map{options.get(it)}
                                choose(field.optString("label",key),values.map{if(it is JSONObject)it.optString("label",it.optString("value"))else it.toString()}){choice->
                                    val value=values[choice];save(if(value is JSONObject)value.opt("value")?:value.optString("label")else value)
                                }
                            }
                            else->form(field.optString("label",key),listOf(key to (field.optBoolean("secret")||field.optString("type")=="password"))){values->
                                if(field.optString("type") in listOf("number","integer")){
                                    val number=values[0].toDoubleOrNull();if(number==null)message("Enter a number.")else save(number)
                                }else save(values[0])
                            }
                        }
                    }
                }
            }
        }}
    }
}
