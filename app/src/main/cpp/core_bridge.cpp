#include "core_bridge.hpp"
#include "nuvio_account.h"
#include "providers.h"
#include "connected_preparer.h"
#include "live_tv.h"
#include "live_guide.h"
#include "iptv_library.h"
#include "media_server.h"
#include "plex.h"
#include "tracking.h"
#include "trailers.h"
#include "episode_playback.hpp"
#include "stream_json.hpp"
#include "stream_display.hpp"
#include "stream_result_cache.hpp"
#include "series_updates.hpp"
#include "torrent/engine.h"
#include <algorithm>
#include <future>
#include <filesystem>
#include <map>
#include <mutex>
#include <stdexcept>
#include <sys/stat.h>

namespace nuvio_android {
namespace {
struct Failure : std::runtime_error { using std::runtime_error::runtime_error; };
std::mutex requests;
uint64_t response_sequence=0;
std::atomic<bool> cancelled{false};
std::atomic<uint64_t> profile_revision{0};
std::string data_dir, assets_dir;
std::vector<Addon> addons;
std::string addon_profile;
sx::StreamResultCache source_cache;
sx::SeriesUpdates series_updates;
std::map<std::string,live_tv::Result> channel_cache;
std::map<std::string,double> channel_times;
std::map<std::string,std::shared_ptr<const std::string>> guide_documents;
std::map<std::string,double> guide_times;
std::shared_ptr<debrid::PreparedLinks> prepared;
bool backgrounded=false;
plex::Pair plex_pair;
std::vector<plex::Server> plex_servers;
debrid::DeviceCode torbox_pair;
json library_cache = json::array();
std::string active_context,active_path,operation_context,operation_path;
bool scoped_operation=false;

void require(bool condition, const std::string& error) { if(!condition) throw Failure(error); }
template<class Predicate> void erase_rows(json& rows, Predicate predicate) {
    rows.erase(std::remove_if(rows.begin(),rows.end(),predicate),rows.end());
}
json api(const ApiResult& value) { require(value.ok,value.error.empty()?"The service request failed.":value.error); return value.result; }
std::string profile_storage() {
    if(!nuvio_account_saved())return data_dir+"/guest";
    if(nuvio_account_user_id().empty())return data_dir+"/pending/"+std::to_string(fnv1a(nuvio_profile_key()));
    return nuvio_profile_path();
}
std::string profile_dir() {
    auto path=profile_storage();
    if(path.empty()) path=data_dir+"/guest";
    require(make_dirs(path),"Profile storage is unavailable.");
    return path;
}
json state() {
    json result; load_json(profile_dir()+"/android-state.json",result);
    if(!result.is_object()) result=json::object();
    for(auto name:{"addons","liveSources","servers","library","savedTitles","recentTorrents","recentChannels","favoriteChannels"})
        if(!jobj(result,name).is_array()) result[name]=json::array();
    if(!jobj(result,"settings").is_object()) result["settings"]=json::object();
    if(!jobj(result,"providerPreferences").is_object()) result["providerPreferences"]=json::object();
    return result;
}
void persist(const json& value) {
    require(!scoped_operation||(operation_context==nuvio_profile_key()&&operation_path==profile_storage()),"The active account or profile changed. Reload this screen.");
    require(save_json(profile_dir()+"/android-state.json",value),"Could not save profile settings.");
}
std::string identity(const std::string& text) { return std::to_string(fnv1a(text)); }
void changed_profile() {
    active_context=nuvio_profile_key();
    active_path=profile_storage();
    ++profile_revision;
    plex_pair={};plex_servers.clear();torbox_pair={};
    addons.clear(); addon_profile.clear(); source_cache.clear(); channel_cache.clear(); channel_times.clear();
    series_updates.clear();
    library_cache=json::array(); providers::clear_script_cache(); bt::Engine::get().stop_web_drop();
    guide_documents.clear();guide_times.clear();
    if(prepared) prepared->shutdown(); prepared=std::make_shared<debrid::PreparedLinks>();
    const auto settings=jobj(state(),"settings");
    bt::Engine::get().configure(profile_dir()+"/torrent-cache",int64_t(jnum(settings,"torrentCacheGiB",1))*1024*1024*1024);
    bt::Engine::get().set_enabled(jbool(settings,"torrents",false));
}
json context() { return {{"saved",nuvio_account_saved()},{"pending",nuvio_account_saved()&&nuvio_account_user_id().empty()},{"profileKey",nuvio_profile_key()},{"profilePath",profile_dir()}}; }
json public_source(const json& value) {
    return {{"id",jstr(value,"id")},{"name",jstr(value,"name")},{"type",jstr(value,"type")},{"guide",!jstr(value,"guideUrl").empty()}};
}
void load_addons(bool sync=false) {
    auto local=state();
    const auto key=nuvio_profile_key();
    if(sync && nuvio_account_saved()) {
        const auto remote=api(api_addon_collection(key));
        auto rows=json::array();
        for(const auto& entry:local["addons"]) if(!jbool(entry,"fromAccount")) rows.push_back(entry);
        if(jobj(remote,"addons").is_array()) for(auto entry:remote["addons"]) {
            entry["fromAccount"]=true;
            auto existing=std::find_if(rows.begin(),rows.end(),[&](const auto& row){return jstr(row,"transportUrl")==jstr(entry,"transportUrl");});
            if(existing==rows.end()) rows.push_back(entry);
        }
        local["addons"]=rows; persist(local);
    }
    if(local["addons"].empty() && !nuvio_account_saved()) {
        for(const auto* url:{kCinemetaUrl,kOpenSubtitlesUrl}) {
            Addon addon;std::string error;
            if(fetch_addon(url,addon,error,&cancelled)) local["addons"].push_back({{"transportUrl",url},{"manifest",addon.manifest}});
        }
        persist(local);
    }
    addons.clear();
    for(const auto& entry:local["addons"]) {
        if(!jbool(entry,"enabled",true)) continue;
        Addon addon;
        if(!parse_addon(jobj(entry,"manifest"),jstr(entry,"transportUrl"),addon)) continue;
        addon.from_account=jbool(entry,"fromAccount");
        const auto& prefs=jobj(jobj(local,"providerPreferences"),addon.transport_url.c_str());
        for(auto& provider:addon.providers) {
            const auto& config=jobj(prefs,provider.id.c_str());
            provider.enabled=jbool(config,"enabled",false);
            if(jobj(config,"settings").is_object()) provider.settings=config["settings"];
        }
        addons.push_back(std::move(addon));
    }
    addon_profile=profile_dir();
}
void ensure_addons() { if(addon_profile!=profile_dir() || addons.empty()) load_addons(); }
json describe_addons() {
    auto local=state(); auto rows=json::array();
    for(size_t i=0;i<local["addons"].size();++i) {
        const auto& entry=local["addons"][i];Addon addon;
        if(!parse_addon(jobj(entry,"manifest"),jstr(entry,"transportUrl"),addon)) continue;
        auto scripts=json::array();
        for(const auto& provider:addon.providers) {
            const auto& pref=jobj(jobj(jobj(local,"providerPreferences"),addon.transport_url.c_str()),provider.id.c_str());
            scripts.push_back({{"id",provider.id},{"name",provider.name},{"enabled",jbool(pref,"enabled")}});
        }
        rows.push_back({{"index",i},{"name",addon.name},{"description",addon.description},{"url",addon.transport_url},
            {"fromAccount",jbool(entry,"fromAccount")},{"enabled",jbool(entry,"enabled",true)},
            {"plugin",addon.provider_repository},{"providers",scripts}});
    }
    return rows;
}
json catalogs() {
    ensure_addons();auto rows=json::array();
    for(size_t i=0;i<addons.size();++i) for(const auto& catalog:addons[i].catalogs) {
        auto extras=json::array(); for(const auto& extra:catalog.extras) extras.push_back({{"name",extra.name},{"required",extra.required},{"options",extra.options}});
        rows.push_back({{"addon",i},{"id",catalog.id},{"type",catalog.type},{"name",catalog.name},
            {"source",addons[i].name},{"extras",extras},{"home",catalog.on_home()}});
    }
    return rows;
}
Meta metadata(const std::string& type,const std::string& id,json* raw=nullptr) {
    ensure_addons(); json data;std::string error;
    for(const auto& addon:addons) if(addon.supports("meta",type,id)) {
        if(fetch_json(addon.resource_url("meta",type,id),data,error,20,&cancelled) && jobj(data,"meta").is_object()) {
            if(raw)*raw=data["meta"];return parse_meta(data["meta"]);
        }
        if(cancelled) throw Failure("Cancelled");
    }
    Addon fallback; require(fetch_addon(kCinemetaUrl,fallback,error,&cancelled),error);
    require(fetch_json(fallback.resource_url("meta",type,id),data,error,20,&cancelled) && jobj(data,"meta").is_object(),error.empty()?"Metadata is unavailable.":error);
    if(raw)*raw=data["meta"];return parse_meta(data["meta"]);
}
::Stream stream_value(const json& value) { return stream_from_json(value); }
json stream_json(const ::Stream& stream) {
    auto value=stream_to_json(stream);
    value["resolution"]=sx::stream_display::resolution_of(stream);
    value["sizeLabel"]=sx::stream_display::size_of(stream);
    value["language"]=sx::stream_display::language_of(stream);
    return value;
}
json library(bool sync) {
    auto local=state();
    if(nuvio_account_saved() && sync) {
        auto response=api_library_get(nuvio_profile_key());
        if(response.ok && response.result.is_array()) local["library"]=response.result;
    }
    for(const auto& saved:local["savedTitles"]) {
        auto found=std::find_if(local["library"].begin(),local["library"].end(),[&](const auto& row){return jstr(row,"_id")==jstr(saved,"_id");});
        if(found==local["library"].end())local["library"].push_back(saved);
        else (*found)["removed"]=jbool(saved,"removed");
    }
    persist(local);
    library_cache=local["library"];
    return library_cache;
}
json source(const std::string& id) {
    const auto local=state();
    for(const auto& item:local["liveSources"]) if(jstr(item,"id")==id) return item;
    throw Failure("This live TV source is no longer available.");
}
live_tv::Result& channels(const std::string& id,bool refresh=false) {
    const auto value=source(id);auto found=channel_cache.find(id);
    if(found!=channel_cache.end() && !refresh && now_seconds()-channel_times[id]<300) return found->second;
    auto result=jstr(value,"type")=="xtream"
        ?live_tv::fetch_xtream(jstr(value,"url"),jstr(value,"username"),jstr(value,"password"),&cancelled)
        :live_tv::fetch(jstr(value,"url"),&cancelled);
    if(!result.error.empty() && result.channels.empty()) throw Failure(iptv::failure_message(result.error,0,false));
    channel_times[id]=now_seconds();return channel_cache.insert_or_assign(id,std::move(result)).first->second;
}
iptv::Account iptv_account(const std::string& id) {
    const auto accounts=iptv::accounts(state()["liveSources"]);
    for(const auto& account:accounts) if(account.id==id) return account;
    throw Failure("Choose a source with Xtream movies or series.");
}
iptv::Kind iptv_kind(const std::string& value) { return value=="series"?iptv::Kind::series:value=="episodes"?iptv::Kind::episodes:iptv::Kind::movies; }
json entry_json(const iptv::Entry& entry) { return {{"id",entry.id},{"name",entry.name},{"category",entry.category},{"poster",entry.poster},
    {"extension",entry.extension},{"year",entry.year},{"season",entry.season},{"episode",entry.episode}}; }
iptv::Entry iptv_entry(const json& value) {
    return {jstr(value,"id"),jstr(value,"name"),jstr(value,"category"),jstr(value,"poster"),jstr(value,"extension"),jstr(value,"year"),int(jnum(value,"season")),int(jnum(value,"episode"))};
}
media_server::Connection connection(const std::string& id) {
    const auto local=state();
    for(const auto& value:local["servers"]) if(jstr(value,"id")==id) {
        auto result=media_server::Connection::restore(value);require(result.valid(),"Reconnect this media server.");return result;
    }
    throw Failure("This media server is no longer connected.");
}
void save_connection(const media_server::Connection& connection) {
    require(connection.valid(),"Media server sign-in is incomplete.");auto local=state();
    auto& list=local["servers"];erase_rows(list,[&](const auto& item){return jstr(item,"id")==connection.id;});
    list.push_back(connection.saved());persist(local);
}
json server_list() {
    const auto local=state();
    auto rows=json::array();for(const auto& item:local["servers"]) rows.push_back({{"id",jstr(item,"id")},{"kind",jstr(item,"kind")},
        {"name",jstr(item,"server_name",jstr(item,"kind"))},{"user",jstr(item,"user_name")}});return rows;
}
json timing_segments(const json& args) {
    using namespace sx::episode_playback;
    const auto settings=jobj(state(),"settings");const auto duration=jnum(args,"duration");
    const auto id=jstr(args,"id"); const int season=int(jnum(args,"season",1)),episode=int(jnum(args,"episode",1));
    std::vector<std::string> ids{id,jstr(args,"imdb_id")};
    std::vector<Segment> intro,anime,registered;json response;std::string error;
    if(jbool(settings,"introSkip",true)) {
        const auto url=timing_url(ids,season,episode,jstr(args,"type")=="movie");
        if(!url.empty() && fetch_json(url,response,error,10,&cancelled)) intro=parse_segments(response);
    }
    AnimeIds mapped;
    if(jbool(settings,"animeSkip",true) && jstr(args,"type")=="series") {
        auto input=anime_source(ids);
        const auto url=input?arm_ids_url(*input):arm_imdb_url(ids);
        if(!url.empty() && fetch_json(url,response,error,10,&cancelled)) {
            mapped=input?parse_arm_ids(response,episode):pick_arm_season(response,season,episode);
        }
        if(mapped.mal>0 && fetch_json(aniskip_url(mapped.mal,mapped.episode,duration),response,error,10,&cancelled)) anime=parse_aniskip(response,duration);
        debrid::Settings services;debrid::load(profile_dir()+"/connected-services.json",services);
        if(mapped.anilist>0 && !services.animeskip.key.empty()) {
            auto post=[&](const std::string& body) {
                const auto h=http_provider_request(animeskip_url,&body,"application/json",{"X-Client-ID: "+services.animeskip.key},&cancelled);
                return json::parse(h.body,nullptr,false);
            };
            const auto shows=parse_animeskip_shows(post(animeskip_shows_body(mapped.anilist)));
            for(const auto& show:shows) {
                registered=parse_animeskip(post(animeskip_episodes_body(show)),mapped.episode,duration);
                if(!registered.empty()) break;
            }
        }
    }
    auto rows=json::array();for(const auto& segment:merge_segments({anime,registered,intro},duration)) rows.push_back({
        {"kind",segment.kind==Kind::intro?"Intro":segment.kind==Kind::recap?"Recap":segment.kind==Kind::credits?"Credits":"Post-credits"},
        {"start",segment.start},{"end",segment.end}});return rows;
}
json dispatch(const std::string& command,const json& args) {
    if(command=="init") {
        data_dir=jstr(args,"dataDir");assets_dir=jstr(args,"assetsDir");
        require(!data_dir.empty() && !assets_dir.empty(),"Application storage is unavailable.");umask(0077);make_dirs(data_dir);
        http_init(jstr(args,"caBundle"));
        require(nuvio_account_init(assets_dir+"/account-config.json",data_dir),"Nuvio account configuration is missing.");
        require(providers::initialize(assets_dir+"/provider-runtime.js"),"The JavaScript provider runtime is missing.");
        providers::configure(assets_dir+"/provider-applications.json");changed_profile();return context();
    }
    require(!data_dir.empty(),"The app is still starting.");
    if(command=="status") return context();
    if(command=="app.foreground") {if(backgrounded){prepared=std::make_shared<debrid::PreparedLinks>();backgrounded=false;}bt::Engine::get().set_enabled(jbool(jobj(state(),"settings"),"torrents"));return {};}
    if(command=="app.background") {backgrounded=true;if(prepared)prepared->shutdown();bt::Engine::get().stop_web_drop();bt::Engine::get().set_enabled(false);return {};}
    if(args.contains("profileKey")) require(jstr(args,"profileKey")==nuvio_profile_key(),"The active profile changed. Reload this screen.");
    const auto key=nuvio_profile_key();
    if(command=="account.pair") {
        const auto pair=link_create();require(pair.ok,pair.error);
        return {{"code",pair.code},{"displayCode",pair.display_code},{"url",pair.link},{"verificationUrl",pair.verification_uri},{"interval",pair.poll_interval},{"expires",pair.expires_in}};
    }
    if(command=="account.poll") {
        const auto result=link_read(jstr(args,"code"));
        if(!result.ok && result.error.empty()) return {{"pending",true}};
        api(result);changed_profile();return context();
    }
    if(command=="account.cancel") { nuvio_pairing_cancel();return {}; }
    if(command=="account.logout") { api(api_logout(key));changed_profile();return context(); }
    if(command=="profiles") return api(api_profiles_get(key));
    if(command=="profiles.select") {const auto value=api(api_profile_select(key,int(jnum(args,"index")),jstr(args,"pin")));changed_profile();return value;}
    if(command=="profiles.create") return api(api_profile_create(key,jstr(args,"name")));
    if(command=="settings") return state()["settings"];
    if(command=="resume.get") return jobj(jobj(state(),"resume"),jstr(args,"id").c_str());
    if(command=="resume.put") {auto local=state();if(!jobj(local,"resume").is_object())local["resume"]=json::object();local["resume"][jstr(args,"id")]=jobj(args,"state");persist(local);return {};}
    if(command=="settings.save") {
        auto local=state();require(jobj(args,"settings").is_object(),"Invalid settings.");
        local["settings"].merge_patch(args["settings"]);persist(local);
        bt::Engine::get().set_enabled(jbool(local["settings"],"torrents"));
        bt::Engine::get().set_cache_bytes(int64_t(std::clamp(jnum(local["settings"],"torrentCacheGiB",1),1.0,6.0))*1024*1024*1024);
        return local["settings"];
    }
    if(command=="addons") { load_addons(jbool(args,"sync"));return describe_addons(); }
    if(command=="addons.add") {
        auto url=normalize_addon_url(jstr(args,"url"));Addon addon;std::string error;
        require(fetch_addon(url,addon,error,&cancelled),error);
        if(nuvio_account_saved()) api(api_profile_source_update(key,url,addon.provider_repository,false));
        auto local=state();erase_rows(local["addons"],[&](const auto& row){return jstr(row,"transportUrl")==url;});
        local["addons"].push_back({{"transportUrl",url},{"manifest",addon.manifest},{"fromAccount",nuvio_account_saved()}});persist(local);load_addons();return describe_addons();
    }
    if(command=="addons.remove" || command=="addons.toggle" || command=="providers.save" || command=="providers.layout") {
        auto local=state();const int index=int(jnum(args,"index",-1));
        require(index>=0 && size_t(index)<local["addons"].size(),"This addon is no longer available.");
        auto& entry=local["addons"][index];const auto url=jstr(entry,"transportUrl");Addon addon;
        require(parse_addon(jobj(entry,"manifest"),url,addon),"This addon manifest is invalid.");
        if(command=="addons.remove") {
            if(jbool(entry,"fromAccount")) api(api_profile_source_update(key,url,addon.provider_repository,true));
            local["addons"].erase(local["addons"].begin()+index);
        } else if(command=="addons.toggle") entry["enabled"]=!jbool(entry,"enabled",true);
        else {
            const auto id=jstr(args,"provider");auto found=std::find_if(addon.providers.begin(),addon.providers.end(),[&](const auto& p){return p.id==id;});
            require(found!=addon.providers.end(),"This JavaScript provider is no longer available.");
            auto& preferences=local["providerPreferences"][url][id];
            if(command=="providers.layout") {
                found->settings=jobj(preferences,"settings").is_object()?preferences["settings"]:json::object();std::string error;
                auto layout=providers::settings_layout(addon,*found,error,&cancelled);require(error.empty(),error);
                return {{"layout",layout},{"values",found->settings}};
            }
            preferences["enabled"]=jbool(args,"enabled",jbool(preferences,"enabled"));
            if(jobj(args,"settings").is_object()) preferences["settings"]=args["settings"];
        }
        persist(local);load_addons();source_cache.clear();return describe_addons();
    }
    if(command=="catalogs") return catalogs();
    if(command=="catalog") {
        ensure_addons();int index=int(jnum(args,"addon",-1));require(index>=0 && size_t(index)<addons.size(),"Reload the addon catalogs.");
        std::string extra;const auto query=jstr(args,"query"),genre=jstr(args,"genre");
        if(!query.empty()) extra="search="+url_encode(query);
        if(!genre.empty()) extra+=(extra.empty()?"":"&")+std::string("genre=")+url_encode(genre);
        const auto offset=int(jnum(args,"offset"));if(offset>0) extra+=(extra.empty()?"":"&")+std::string("skip=")+std::to_string(offset);
        json value;std::string error;require(fetch_json(addons[index].resource_url("catalog",jstr(args,"type"),jstr(args,"id"),extra),value,error,25,&cancelled),error);
        return jobj(value,"metas").is_array()?value["metas"]:json::array();
    }
    if(command=="search") {
        ensure_addons();auto rows=json::array();std::set<std::string> seen;
        const auto query=jstr(args,"query");require(!query.empty()&&query.size()<=256,"Enter a title to search.");
        for(const auto& addon:addons) for(const auto& catalog:addon.catalogs) if(catalog.has_extra("search")&&!catalog.requires_other_than("search")) {
            json response;std::string error;
            if(fetch_json(addon.resource_url("catalog",catalog.type,catalog.id,"search="+url_encode(query)),response,error,15,&cancelled) && jobj(response,"metas").is_array())
                for(const auto& item:response["metas"]) if(seen.insert(jstr(item,"type")+":"+jstr(item,"id")).second && rows.size()<500) rows.push_back(item);
            if(cancelled) throw Failure("Cancelled");
        }
        return rows;
    }
    if(command=="detail") {json raw;metadata(jstr(args,"type"),jstr(args,"id"),&raw);return raw;}
    if(command=="series.badges") {
        auto rows=library(false);const auto watched=nuvio_account_saved()?api(api_watched_get(key)):jobj(state(),"watched");std::set<std::string> completed;
        if(jobj(watched,"entries").is_array())for(const auto& entry:watched["entries"]){const auto id=jstr(entry,"content_id");completed.insert(id+(jobj(entry,"season").is_number()?":"+std::to_string(int(jnum(entry,"season")))+":"+std::to_string(int(jnum(entry,"episode"))):""));}
        else if(watched.is_object())for(auto it=watched.begin();it!=watched.end();++it)if(it.value().is_boolean()&&it.value().get<bool>())completed.insert(it.key());
        if(completed.empty()||(nuvio_account_saved()&&!jbool(watched,"synced")))return json::object();
        json badges=json::object();int fetched=0;for(const auto& row:rows)if(jstr(row,"type")=="series"&&!jbool(row,"removed")) {
            const auto id=jstr(row,"_id");if(series_updates.due(id,now_seconds())&&fetched++<12){try{const auto meta=metadata("series",id);series_updates.put(id,meta.videos,now_seconds());}catch(const Failure&){series_updates.put(id,{},now_seconds(),false);}}
            const auto label=series_updates.badge(id,completed,now_epoch_ms(),true,jstr(jobj(row,"state"),"video_id"));if(!label.empty())badges[id]=label;
            if(cancelled)throw Failure("Cancelled");
        }return badges;
    }
    if(command=="streams") {
        ensure_addons();const auto type=jstr(args,"type"),id=jstr(args,"videoId",jstr(args,"id"));
        Meta meta=jobj(args,"meta").is_object()?parse_meta(args["meta"]):metadata(type,jstr(args,"id"));
        const auto cache_key=key+":"+type+":"+id;
        if(!jbool(args,"refresh")) {
            auto cached=source_cache.get(cache_key,now_seconds());if(!cached.empty()) {
                debrid::Settings services;debrid::load(profile_dir()+"/connected-services.json",services);
                const bool torrents=jbool(jobj(state(),"settings"),"torrents");
                auto values=json::array();for(const auto& stream:cached)if(!stream.torrent()||torrents||services.can_resolve(stream)){auto value=stream_json(stream);value["debridResolvable"]=services.can_resolve(stream);values.push_back(value);}return {{"streams",values},{"cached",true},{"errors",json::array()}};
            }
        }
        std::vector<Addon> sources;for(const auto& addon:addons) for(auto item:providers::stream_sources(addon,type,id)) sources.push_back(std::move(item));
        std::vector<::Stream> streams;auto errors=json::array();
        for(size_t base=0;base<sources.size();base+=4) {
            std::vector<std::future<providers::Result>> jobs;
            for(size_t i=base;i<std::min(base+4,sources.size());++i) jobs.push_back(std::async(std::launch::async,[&,i]{return providers::fetch(sources[i],type,id,meta,&cancelled);}));
            for(auto& job:jobs) {auto result=job.get();if(!result.error.empty()) errors.push_back(result.error);streams.insert(streams.end(),result.streams.begin(),result.streams.end());}
            if(cancelled) throw Failure("Cancelled");
        }
        source_cache.put(cache_key,streams,now_seconds());
        debrid::Settings services;debrid::load(profile_dir()+"/connected-services.json",services);
        if(services.enabled&&services.instant_limit>0&&prepared){
            prepared->configure(services);const auto links=prepared;const auto scope=links->open_scope(),epoch=links->epoch();
            const int season=int(jnum(args,"season")),episode=int(jnum(args,"episode"));
            auto candidates=debrid::preparation_candidates(streams,services,0,"",services.instant_limit);
            std::thread([links,scope,epoch,season,episode,candidates=std::move(candidates)]{
                std::vector<std::future<debrid::Result>> jobs;
                for(const auto& stream:candidates)jobs.push_back(std::async(std::launch::async,[=]{return links->prepare(stream,season,episode,scope,epoch);}));
                for(auto& job:jobs){try{job.get();}catch(const std::exception&){}}
                links->close_scope(scope);
            }).detach();
        }
        const bool torrents=jbool(jobj(state(),"settings"),"torrents");auto values=json::array();for(const auto& stream:streams)if(!stream.torrent()||torrents||services.can_resolve(stream)){auto value=stream_json(stream);value["debridResolvable"]=services.can_resolve(stream);values.push_back(value);}
        return {{"streams",values},{"cached",false},{"errors",errors}};
    }
    if(command=="streams.resolve") {
        auto stream=stream_value(jobj(args,"stream"));debrid::Settings services;debrid::load(profile_dir()+"/connected-services.json",services);
        if(!stream.direct_http()) {
            if(stream.torrent()&&!services.can_resolve(stream)) { require(jbool(jobj(state(),"settings"),"torrents"),"Enable peer-to-peer torrents or connect a cached-file service first.");return stream_json(stream); }
            if(!prepared)prepared=std::make_shared<debrid::PreparedLinks>();prepared->configure(services);
            const std::shared_ptr<std::atomic<bool>> cancel_scope(&cancelled,[](std::atomic<bool>*){});
            auto result=prepared->resolve(stream,int(jnum(args,"season")),int(jnum(args,"episode")),prepared->epoch(),cancel_scope);
            require(result.ok,result.error);stream=result.stream;
        }
        return stream_json(stream);
    }
    if(command=="subtitles") {
        ensure_addons();auto rows=json::array();const auto type=jstr(args,"type"),id=jstr(args,"videoId");
        for(const auto& addon:addons) if(addon.supports("subtitles",type,id)) {
            json response;std::string error;
            if(fetch_json(addon.resource_url("subtitles",type,id),response,error,15,&cancelled))
                for(const auto& track:parse_subtitles(response,addon.name)) rows.push_back({{"id",track.id},{"url",track.url},{"lang",track.lang},{"name",track.label},{"addon",track.addon}});
        }
        return rows;
    }
    if(command=="library") return library(jbool(args,"sync",true));
    if(command=="library.save") {
        auto item=jobj(args,"item");require(item.is_object()&&!jstr(item,"_id").empty(),"Missing library title.");
        auto local=state();auto& saved=local["savedTitles"];
        erase_rows(saved,[&](const auto& row){return jstr(row,"_id")==jstr(item,"_id");});
        item.erase("state");item["temp"]=false;saved.push_back(item);persist(local);return library(false);
    }
    if(command=="library.put") {
        const auto item=jobj(args,"item");require(item.is_object()&&!jstr(item,"_id").empty(),"Missing library title.");
        auto local=state();auto& rows=local["library"];auto found=std::find_if(rows.begin(),rows.end(),[&](const auto& row){return jstr(row,"_id")==jstr(item,"_id");});
        json merged=item;
        if(found==rows.end()) {if(!merged.contains("removed")){merged["removed"]=true;merged["temp"]=true;}rows.push_back(merged);}
        else {merged=*found;merged.merge_patch(item);*found=merged;}persist(local);
        if(nuvio_account_saved()) {auto result=api_library_put(key,json::array({merged}));return {{"synced",result.ok},{"notice",result.error}};}
        return {{"synced",false}};
    }
    if(command=="watched") return nuvio_account_saved()?api(api_watched_get(key)):jobj(state(),"watched");
    if(command=="watched.set") {
        if(nuvio_account_saved()) return api(api_watched_update(key,jstr(args,"videoId"),jstr(args,"name"),jbool(args,"watched"),jstr(args,"id"),int(jnum(args,"season",-1)),int(jnum(args,"episode",-1))));
        auto local=state();if(!jobj(local,"watched").is_object())local["watched"]=json::object();local["watched"][jstr(args,"videoId")]=jbool(args,"watched");persist(local);return {};
    }
    if(command=="tracking") return api(api_tracking(key,jstr(args,"action","status"),jobj(args,"arguments"),&cancelled));
    if(command=="services") {
        debrid::Settings value;debrid::load(profile_dir()+"/connected-services.json",value);
        return {{"enabled",value.enabled},{"preferred",value.preferred},{"instantLimit",value.instant_limit},
            {"torbox",!value.torbox.key.empty()},{"premiumize",!value.premiumize.key.empty()},{"animeSkip",!value.animeskip.key.empty()}};
    }
    if(command=="services.options") {
        debrid::Settings value;debrid::load(profile_dir()+"/connected-services.json",value);
        if(args.contains("enabled"))value.enabled=jbool(args,"enabled");
        if(args.contains("instantLimit"))value.instant_limit=std::clamp(int(jnum(args,"instantLimit")),0,5);
        if(args.contains("preferred")){const auto provider=jstr(args,"preferred");require(provider.empty()||provider=="torbox"||provider=="premiumize","Choose a supported service.");value.preferred=provider;}
        require(debrid::save(profile_dir()+"/connected-services.json",value),"Could not save service preferences.");if(prepared)prepared->configure(value);return {};
    }
    if(command=="streams.promote") {if(prepared)prepared->promote(stream_value(jobj(args,"stream")),int(jnum(args,"season")),int(jnum(args,"episode")));return {};}
    if(command=="services.save" || command=="services.import" || command=="services.torbox.poll") {
        debrid::Settings value;debrid::load(profile_dir()+"/connected-services.json",value);
        if(command=="services.import") {
            const auto result=api(api_connected_services_pull(key));require(debrid::import_snapshot(value,result),"No supported synced service connection was found.");
        } else if(command=="services.torbox.poll") {
            auto result=debrid::Client().poll_torbox(torbox_pair.device_code,&cancelled);
            if(result.state==debrid::DeviceToken::pending)return {{"pending",true}};
            require(result.state==debrid::DeviceToken::authorized,result.error);value.torbox.key=result.key;value.enabled=true;
        } else {
            const auto provider=jstr(args,"provider");const auto credential=jstr(args,"key");
            require(provider=="torbox"||provider=="premiumize"||provider=="animeskip","Unknown connected service.");
            if(!credential.empty() && provider!="animeskip") require(debrid::Client().validate(provider,credential,&cancelled).empty(),"The service could not verify this API key.");
            auto& target=provider=="torbox"?value.torbox:provider=="premiumize"?value.premiumize:value.animeskip;
            target.key=credential;target.sync_owner.clear();value.enabled=jbool(args,"enabled",true);
            if(args.contains("instantLimit"))value.instant_limit=std::clamp(int(jnum(args,"instantLimit")),0,5);
            if(args.contains("preferred"))value.preferred=jstr(args,"preferred");
        }
        require(debrid::save(profile_dir()+"/connected-services.json",value),"Could not save service settings.");
        if(prepared)prepared->configure(value);return {{"saved",true}};
    }
    if(command=="services.torbox.start") {
        torbox_pair=debrid::Client().start_torbox(&cancelled);require(torbox_pair.ok,torbox_pair.error);
        return {{"code",torbox_pair.user_code},{"url",torbox_pair.link.empty()?torbox_pair.url:torbox_pair.link},{"interval",torbox_pair.interval},{"expires",torbox_pair.lifetime}};
    }
    if(command=="trailers") {
        auto meta=parse_meta(jobj(args,"meta"));const auto settings=jobj(state(),"settings");
        auto extra=providers::trailer_videos(meta,&cancelled,trailers::preferred_language(jstr(settings,"audioLanguage","eng")));
        auto rows=json::array();for(const auto& item:trailers::candidates(meta,extra))rows.push_back({{"name",item.name},{"youtubeId",item.youtube_id},{"url",item.url}});return rows;
    }
    if(command=="trailers.resolve") {
        const auto& value=jobj(args,"trailer");auto result=trailers::resolve({jstr(value,"name"),jstr(value,"youtubeId"),jstr(value,"url")},&cancelled);
        require(result.error.empty()&&!result.url.empty(),result.error.empty()?"This trailer is unavailable.":result.error);
        return {{"url",result.url},{"request_headers",result.headers},{"name",jstr(value,"name")}};
    }
    if(command=="timings") return timing_segments(args);
    if(command=="next") {
        const auto meta=parse_meta(jobj(args,"meta"));const auto next=sx::episode_playback::next_video(meta.videos,jstr(args,"videoId"),now_epoch_ms());
        if(!next)return nullptr;return {{"id",next->id},{"name",next->title},{"season",next->season},{"episode",next->episode}};
    }
    if(command=="streams.match") {
        std::vector<::Stream> streams;if(jobj(args,"streams").is_array())for(const auto& item:args["streams"])streams.push_back(stream_value(item));
        return {{"index",sx::episode_playback::select_stream(streams,stream_value(jobj(args,"previous")))}};
    }
    if(command=="live.sources") {const auto local=state();auto rows=json::array();for(const auto& item:local["liveSources"])rows.push_back(public_source(item));return rows;}
    if(command=="live.add") {
        auto value=jobj(args,"source");require(value.is_object(),"Enter live TV source details.");
        require(jstr(value,"type")=="m3u"||jstr(value,"type")=="xtream","Choose M3U or Xtream Codes.");
        require(live_tv::http_url(jstr(value,"url")),"Enter an HTTP or HTTPS provider address.");
        value["id"]=identity(value.dump());if(jstr(value,"name").empty())value["name"]="Live TV source";
        auto local=state();require(local["liveSources"].size()<16,"Remove a source before adding another.");local["liveSources"].push_back(value);persist(local);return public_source(value);
    }
    if(command=="live.remove" || command=="live.guide.save") {
        auto local=state();auto& list=local["liveSources"];const auto id=jstr(args,"source");
        if(command=="live.remove") {erase_rows(list,[&](const auto& row){return jstr(row,"id")==id;});iptv::remove_cache(profile_dir()+"/iptv",id);}
        else for(auto& item:list)if(jstr(item,"id")==id){if(args.contains("url")){const auto url=jstr(args,"url");require(url.empty()||live_tv::http_url(url),"Enter an HTTP or HTTPS guide URL.");item["guideUrl"]=url;}item["guideEnabled"]=jbool(args,"enabled",true);}
        channel_cache.erase(id);guide_documents.erase(id);guide_times.erase(id);persist(local);return {};
    }
    if(command=="live.channels") {
        const auto source_id=jstr(args,"source");auto& result=channels(source_id,jbool(args,"refresh"));
        const auto group=jstr(args,"group"),query=lower(jstr(args,"query"));
        const auto local=state();std::set<std::string> favorites;for(const auto& id:local["favoriteChannels"])if(id.is_string())favorites.insert(id.get<std::string>());
        auto groups=std::set<std::string>();auto rows=json::array();size_t count=0;const auto offset=size_t(std::max(0.0,jnum(args,"offset")));
        for(const auto& channel:result.channels) {
            groups.insert(channel.group);for(const auto& name:channel.groups)groups.insert(name);
            const auto favorite_key=source_id+":"+channel.id;
            if(!group.empty() && group!="Favorites" && !channel.in_group(group))continue;
            if(group=="Favorites"&&!favorites.count(favorite_key))continue;
            if(!query.empty()&&lower(channel.name).find(query)==std::string::npos)continue;
            if(count++<offset||rows.size()>=250)continue;
            rows.push_back({{"id",channel.id},{"name",channel.name},{"group",channel.group},{"logo",channel.logo},{"favorite",favorites.count(favorite_key)>0}});
        }
        return {{"channels",rows},{"groups",groups},{"total",count}};
    }
    if(command=="live.play" || command=="live.favorite") {
        const auto source_id=jstr(args,"source"),id=jstr(args,"id");
        auto& result=channels(source_id);auto found=std::find_if(result.channels.begin(),result.channels.end(),[&](const auto& channel){return channel.id==id;});require(found!=result.channels.end(),"This channel is no longer available.");
        auto local=state();const auto favorite_key=source_id+":"+id;
        if(command=="live.favorite") {
            auto& favorites=local["favoriteChannels"];auto old=std::find(favorites.begin(),favorites.end(),favorite_key);
            if(old==favorites.end())favorites.push_back(favorite_key);else favorites.erase(old);persist(local);return {};
        }
        auto& recent=local["recentChannels"];erase_rows(recent,[&](const auto& value){return jstr(value,"source")==source_id&&jstr(value,"id")==id;});
        recent.insert(recent.begin(),json{{"source",source_id},{"id",id},{"name",found->name}});if(recent.size()>50)recent.erase(recent.begin()+50,recent.end());persist(local);
        return {{"url",found->url},{"request_headers",found->headers},{"name",found->name},{"id",id},{"type","tv"},{"source",source_id},{"epgId",found->epg_id}};
    }
    if(command=="live.recent")return state()["recentChannels"];
    if(command=="live.guide") {
        const auto source_id=jstr(args,"source");const auto value=source(source_id);require(jbool(value,"guideEnabled",true),"The guide is disabled for this source.");
        auto& snapshot=channels(source_id);const auto id=jstr(args,"id");
        auto found=std::find_if(snapshot.channels.begin(),snapshot.channels.end(),[&](const auto& channel){return channel.id==id;});require(found!=snapshot.channels.end(),"This channel is unavailable.");
        auto url=jstr(value,"guideUrl",snapshot.guide_url);live_guide::Result guide;
        const auto now=now_epoch_ms()/1000;
        if(!url.empty()) {
            const std::set<std::string> wanted{found->epg_id.empty()?id:found->epg_id};
            if(guide_documents.count(source_id)&&now_seconds()-guide_times[source_id]<300)guide=live_guide::parse_xmltv(*guide_documents[source_id],wanted,now,&cancelled);
            else {guide=live_guide::fetch_xmltv(url,wanted,now,&cancelled);if(guide.document){guide_documents[source_id]=guide.document;guide_times[source_id]=now_seconds();}}
        }
        else if(jstr(value,"type")=="xtream")guide=live_guide::fetch_xtream(jstr(value,"url"),jstr(value,"username"),jstr(value,"password"),id,now,&cancelled);
        else throw Failure("Add an XMLTV guide URL in this source's options.");
        require(guide.error.empty(),guide.error);auto rows=json::array();for(const auto& [channel,listing]:guide.channels)for(const auto& program:listing)rows.push_back({{"title",program.title},{"start",program.start},{"end",program.end}});return rows;
    }
    if(command=="iptv.probe") {
        auto local=state();const auto account=iptv_account(jstr(args,"source"));auto result=iptv::probe(account,&cancelled);
        if(!jobj(local,"iptvCapabilities").is_object())local["iptvCapabilities"]=json::object();
        iptv::apply_capabilities(local["iptvCapabilities"],account.id,result);persist(local);
        const bool cached=!result.movies_ok&&!result.series_ok;
        const auto movie_rows=cached?iptv::categories(local["iptvCapabilities"],account.id,iptv::Kind::movies):result.movies;
        const auto series_rows=cached?iptv::categories(local["iptvCapabilities"],account.id,iptv::Kind::series):result.series;
        auto movies=json::array(),series=json::array();for(const auto& row:movie_rows)movies.push_back({{"id",row.id},{"name",row.name}});for(const auto& row:series_rows)series.push_back({{"id",row.id},{"name",row.name}});
        require(result.movies_ok||result.series_ok||!movies.empty()||!series.empty(),result.error.empty()?"This provider has no IPTV movies or series.":result.error);
        return {{"movies",movies},{"series",series},{"cached",cached}};
    }
    if(command=="iptv.search") {
        const auto result=iptv::search_saved(profile_dir()+"/iptv",iptv::accounts(state()["liveSources"]),jstr(args,"query"),&cancelled);
        auto rows=json::array();for(const auto& hit:result.hits)rows.push_back({{"source",hit.source},{"provider",hit.provider},{"kind",iptv::kind_name(hit.kind)},{"entry",entry_json(hit.entry)}});return rows;
    }
    if(command=="iptv.catalog") {
        const auto account=iptv_account(jstr(args,"source"));const auto kind=iptv_kind(jstr(args,"kind"));const auto category=jstr(args,"category");
        auto catalog=iptv::fetch(account,kind,category,&cancelled);bool cached=false;
        if(catalog.error.empty())iptv::save_cache(profile_dir()+"/iptv",account.id,kind,category,catalog,&cancelled);
        else {const auto error=catalog.error;cached=iptv::load_cache(profile_dir()+"/iptv",account.id,kind,category,catalog,&cancelled);require(cached,error);}
        const auto query=iptv::search_key(jstr(args,"query")),letter=jstr(args,"letter");const auto offset=size_t(std::max(0.0,jnum(args,"offset")));
        auto rows=json::array();size_t count=0;for(const auto& entry:catalog.entries) {
            if(!query.empty()&&iptv::search_key(entry.name).find(query)==std::string::npos)continue;
            if(!letter.empty()&&iptv::initial(entry.name)!=letter)continue;
            if(count++<offset||rows.size()>=250)continue;rows.push_back(entry_json(entry));
        }
        return {{"entries",rows},{"total",count},{"cached",cached}};
    }
    if(command=="iptv.play") {
        const auto account=iptv_account(jstr(args,"source"));const auto entry=iptv_entry(jobj(args,"entry"));const auto kind=iptv_kind(jstr(args,"kind"));
        const auto url=iptv::playback_url(account,kind,entry);require(!url.empty(),"This IPTV entry is not playable.");
        return {{"url",url},{"name",entry.name},{"id",iptv::identity(account.id,kind,entry.id)},{"type",kind==iptv::Kind::movies?"iptv-movie":"iptv-series"},{"season",entry.season},{"episode",entry.episode}};
    }
    if(command=="servers")return server_list();
    if(command=="servers.add") {
        media_server::Connection connection;connection.kind=jstr(args,"kind");require(connection.kind=="jellyfin"||connection.kind=="emby","Choose Jellyfin or Emby.");
        connection.base=jstr(args,"url");connection.id=identity(connection.kind+connection.base+jstr(args,"username"));connection.device="MaxdAndroidTV"+identity(data_dir);
        const auto result=media_server::authenticate(connection,jstr(args,"username"),jstr(args,"password"),&cancelled);require(result.ok(),result.error);save_connection(connection);return server_list();
    }
    if(command=="servers.remove") {auto local=state();erase_rows(local["servers"],[&](const auto& item){return jstr(item,"id")==jstr(args,"server");});persist(local);return server_list();}
    if(command=="plex.start") {plex_pair=plex::Client().start(&cancelled);require(plex_pair.error.empty(),plex_pair.error);return {{"url",plex_pair.url},{"code",plex_pair.code},{"expires",plex_pair.expires}};}
    if(command=="plex.poll") {
        plex_pair=plex::Client().poll(plex_pair,&cancelled);require(plex_pair.error.empty(),plex_pair.error);if(plex_pair.pending)return {{"pending",true}};
        const auto servers=plex::Client().servers(plex_pair,&cancelled);require(servers.error.empty(),servers.error);plex_servers=servers.items;
        auto rows=json::array();for(const auto& item:plex_servers)rows.push_back({{"id",item.id},{"name",item.name}});return {{"servers",rows}};
    }
    if(command=="plex.connect") {
        const auto id=jstr(args,"server");auto found=std::find_if(plex_servers.begin(),plex_servers.end(),[&](const auto& item){return item.id==id;});require(found!=plex_servers.end(),"Select an available Plex server.");
        media_server::Connection connection;connection.kind="plex";connection.id="plex"+identity(id);connection.device=plex_pair.device;
        auto result=plex::Client().connect(connection,plex_pair,*found,&cancelled);require(result.ok(),result.error);save_connection(connection);return server_list();
    }
    if(command=="servers.browse") {
        auto server=connection(jstr(args,"server"));const auto result=media_server::browse(server,jstr(args,"parent"),jstr(args,"type","views"),jstr(args,"query"),size_t(std::max(0.0,jnum(args,"offset"))),&cancelled);require(result.ok(),result.error);save_connection(server);return result.data;
    }
    if(command=="servers.image") {
        auto server=connection(jstr(args,"server"));const auto directory=profile_dir()+"/artwork";make_dirs(directory);
        const auto path=directory+"/"+identity(server.id+":"+jstr(args,"id"))+".image";
        if(!file_exists(path)){const auto response=media_server::image(server,jstr(args,"id"),&cancelled);require(response.ok()&&response.body.size()<=8*1024*1024,"Artwork is unavailable.");require(write_file(path,response.body),"Could not save artwork.");save_connection(server);
            std::vector<std::filesystem::directory_entry> entries;std::error_code error;uint64_t total=0;
            for(const auto& entry:std::filesystem::directory_iterator(directory,error))if(entry.is_regular_file(error)){entries.push_back(entry);total+=entry.file_size(error);}
            std::sort(entries.begin(),entries.end(),[&](const auto& a,const auto& b){return a.last_write_time(error)<b.last_write_time(error);});
            for(const auto& entry:entries)if(total>128u*1024*1024&&entry.path()!=path){total-=entry.file_size(error);std::filesystem::remove(entry.path(),error);}
        }
        return {{"path",path}};
    }
    if(command=="servers.play") {
        auto server=connection(jstr(args,"server"));const auto result=media_server::playback(server,jstr(args,"id"),jstr(args,"source"),jbool(args,"compatible"),&cancelled);require(result.error.empty(),result.error);save_connection(server);
        return {{"url",result.url},{"request_headers",result.headers},{"resume",std::clamp(jnum(args,"resume",result.resume),0.0,1e8)},{"duration",std::clamp(jnum(args,"duration",result.duration),0.0,1e8)},{"server",jstr(args,"server")},{"serverItem",jstr(args,"id")},{"mediaSource",result.source},{"session",result.session},{"method",result.method},{"type","media-server"},{"name",jstr(args,"name")}};
    }
    if(command=="servers.report") {
        const auto& value=jobj(args,"playback");media_server::Playback playback;playback.url=jstr(value,"url");playback.source=jstr(value,"mediaSource");playback.session=jstr(value,"session");playback.method=jstr(value,"method");
        auto server=connection(jstr(value,"server"));auto result=media_server::report(server,playback,jstr(value,"serverItem"),jnum(args,"position"),jbool(args,"paused"),jstr(args,"event"),&cancelled);require(result.ok(),result.error);save_connection(server);return {};
    }
    if(command=="torrents.start") {
        require(jbool(jobj(state(),"settings"),"torrents"),"Enable peer-to-peer torrents in Settings first.");
        auto input=jstr(args,"input");bt::MagnetInfo magnet;bt::TorrentMeta torrent;std::string error,hash;
        if(bt::Engine::parse_magnet(input,magnet,&error)) {require(bt::Engine::get().start_magnet(input,&magnet,&error),error);hash=magnet.info_hash_hex;
            if(jobj(args,"sources").is_array()){for(const auto& value:args["sources"])if(value.is_string()){auto tracker=value.get<std::string>();if(starts_with(tracker,"tracker:"))tracker=tracker.substr(8);if(starts_with(tracker,"udp://")||live_tv::http_url(tracker))magnet.trackers.push_back(tracker);}bt::Engine::get().start_with_webseeds(hash,magnet.trackers,magnet.webseeds);}}
        else if(live_tv::http_url(input)) {auto response=http_get(input,30,&cancelled);require(response.ok()&&response.body.size()<=4*1024*1024,"Could not download bounded torrent metadata.");require(bt::Engine::get().load_torrent_buffer(response.body,torrent,&error),error);hash=torrent.info_hash_hex;}
        else {require(starts_with(input,data_dir+"/") && input.find("..") == std::string::npos && ends_with(lower(input),".torrent"),"Choose a .torrent file with Android's file picker.");require(bt::Engine::get().load_torrent_file(input,torrent,&error),error);hash=torrent.info_hash_hex;}
        std::vector<bt::FileInfo> files;require(bt::Engine::get().wait_metadata(hash,files,&cancelled,60,&error),error);
        auto rows=json::array();for(size_t i=0;i<files.size();++i)rows.push_back({{"index",i},{"name",files[i].path},{"size",files[i].size}});
        auto local=state();auto& recents=local["recentTorrents"];erase_rows(recents,[&](const auto& value){return jstr(value,"hash")==hash;});
        recents.insert(recents.begin(),json{{"hash",hash},{"name",magnet.display_name.empty()?torrent.name:magnet.display_name},{"input",input}});if(recents.size()>50)recents.erase(recents.begin()+50,recents.end());persist(local);
        return {{"hash",hash},{"files",rows},{"suggested",bt::Engine::guess_file(files,int(jnum(args,"season")),int(jnum(args,"episode")))}};
    }
    if(command=="torrents.recent")return state()["recentTorrents"];
    if(command=="torrents.stats") {const auto value=bt::Engine::get().stats(jstr(args,"hash"));return {{"found",value.found},{"metadata",value.has_metadata},{"peers",value.peers},{"knownPeers",value.known_peers},{"rate",value.download_rate},{"progress",value.file_progress}};}
    if(command=="torrents.web.start") {require(jbool(jobj(state(),"settings"),"torrents"),"Enable torrents first.");const auto url=bt::Engine::get().start_web_drop();require(!url.empty(),"Web Drop could not start on this network.");return {{"url",url}};}
    if(command=="torrents.web.stop") {bt::Engine::get().stop_web_drop();return {};}
    if(command=="torrents.web.poll") {bt::WebDropSubmission value;if(!bt::Engine::get().take_web_submission(&value))return {{"pending",true}};return {{"kind",value.kind},{"input",value.value},{"name",value.title}};}
    throw Failure("This app command is unavailable.");
}
}

json call(const std::string& command,const json& arguments) {
    std::lock_guard<std::mutex> lock(requests);cancelled=false;
    operation_context=nuvio_profile_key();operation_path=profile_storage();scoped_operation=command!="init";
    const auto reconcile=[](){if(!data_dir.empty()&&(active_context!=nuvio_profile_key()||active_path!=profile_storage()))changed_profile();};
    const auto failure=[&](const std::string& message){
        json result{{"ok",false},{"error",message}};
        try{reconcile();if(!data_dir.empty()){result["context"]=context();result["sequence"]=++response_sequence;}}catch(const std::exception&){}
        return result;
    };
    try {
        const auto value=dispatch(command,arguments.is_object()?arguments:json::object());
        const bool changes_context=command=="init"||command=="account.poll"||command=="account.logout"||command=="profiles.select";
        require(changes_context||(operation_context==nuvio_profile_key()&&operation_path==profile_storage()),"The active account or profile changed. Reload this screen.");
        reconcile();
        return {{"ok",true},{"result",value},{"context",context()},{"sequence",++response_sequence}};
    } catch(const Failure& error) {return failure(error.what());}
    catch(const std::exception&) {return failure("The request could not be completed. Retry or refresh this screen.");}
}
void cancel() {cancelled=true;}
void shutdown() {cancel();if(prepared)prepared->shutdown();bt::Engine::get().shutdown();}

struct TorrentReader {
    std::shared_ptr<bt::Torrent> torrent;int reader=-1,index=-1;int64_t size=0;uint64_t revision=0;std::atomic<bool> abort{false};
    ~TorrentReader(){abort=true;if(torrent)bt::Engine::get().close_reader(torrent,reader);}
};
std::shared_ptr<TorrentReader> open_torrent(const std::string& hash,int index,const std::string& profile_key) {
    std::lock_guard<std::mutex> lock(requests);cancelled=false;
    require(profile_key==nuvio_profile_key(),"The active profile changed.");
    require(jbool(jobj(state(),"settings"),"torrents"),"Peer-to-peer torrents are disabled.");
    if(!bt::Engine::get().stats(hash).found){bt::Engine::get().start(hash,{});std::vector<bt::FileInfo> files;std::string error;if(!bt::Engine::get().wait_metadata(hash,files,&cancelled,60,&error))throw std::runtime_error(error);}
    auto value=std::make_shared<TorrentReader>();value->index=index;value->revision=profile_revision.load();std::string error;
    value->torrent=bt::Engine::get().open_reader(hash,index,&value->reader,&value->size,&error);
    if(!value->torrent)throw std::runtime_error(error);return value;
}
int64_t torrent_size(const std::shared_ptr<TorrentReader>& reader) {return reader->size;}
int read_torrent(const std::shared_ptr<TorrentReader>& reader,int64_t offset,uint8_t* data,int length) {
    if(reader->revision!=profile_revision.load())return -1;
    bt::Engine::get().seek_reader(reader->torrent,reader->reader,reader->index,offset);
    return bt::Engine::get().read(reader->torrent,reader->reader,reader->index,offset,data,length,&reader->abort);
}
void cancel_torrent(const std::shared_ptr<TorrentReader>& reader) {reader->abort=true;}
}
