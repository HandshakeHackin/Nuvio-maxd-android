#include "core_bridge.hpp"
#include <jni.h>
#include <map>
#include <mutex>
#include <atomic>

namespace {
std::mutex readers_mutex;
std::map<jlong,std::shared_ptr<nuvio_android::TorrentReader>> readers;
std::atomic<jlong> next_reader{1};
std::string string_value(JNIEnv* env,jstring value) {
    const auto* text=env->GetStringUTFChars(value,nullptr);
    std::string result=text?text:"";if(text)env->ReleaseStringUTFChars(value,text);return result;
}
std::shared_ptr<nuvio_android::TorrentReader> reader(jlong id) {
    std::lock_guard<std::mutex> lock(readers_mutex);const auto found=readers.find(id);
    if(found==readers.end())throw std::runtime_error("Torrent reader is closed");return found->second;
}
void io_error(JNIEnv* env) {env->ThrowNew(env->FindClass("java/io/IOException"),"Torrent reading was interrupted or the torrent is unavailable.");}
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_handshakehackin_nuviomaxd_androidtv_core_CoreBridge_nativeCall(JNIEnv* env,jobject,jstring command,jbyteArray arguments) {
    std::string bytes(size_t(env->GetArrayLength(arguments)),0);
    env->GetByteArrayRegion(arguments,0,jsize(bytes.size()),reinterpret_cast<jbyte*>(bytes.data()));
    std::string result;
    try {result=nuvio_android::call(string_value(env,command),json::parse(bytes)).dump();}
    catch(const std::exception&) {result="{\"ok\":false,\"error\":\"Invalid app request.\"}";}
    auto output=env->NewByteArray(jsize(result.size()));
    if(output)env->SetByteArrayRegion(output,0,jsize(result.size()),reinterpret_cast<const jbyte*>(result.data()));return output;
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_handshakehackin_nuviomaxd_androidtv_core_CoreBridge_nativeCancel(JNIEnv*,jobject) {nuvio_android::cancel();}
extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_handshakehackin_nuviomaxd_androidtv_core_CoreBridge_nativeOpenTorrent(JNIEnv* env,jobject,jstring hash,jint index) {
    try {
        auto value=nuvio_android::open_torrent(string_value(env,hash),index);const auto id=next_reader++;
        const jlong values[]={id,nuvio_android::torrent_size(value)};
        {std::lock_guard<std::mutex> lock(readers_mutex);readers[id]=std::move(value);}
        auto output=env->NewLongArray(2);if(output)env->SetLongArrayRegion(output,0,2,values);return output;
    }catch(const std::exception&){io_error(env);return nullptr;}
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_handshakehackin_nuviomaxd_androidtv_core_CoreBridge_nativeReadTorrent(JNIEnv* env,jobject,jlong handle,jlong position,jint length) {
    try {
        if(length<1||length>1024*1024||position<0)throw std::runtime_error("Invalid read");
        const auto value=reader(handle);std::vector<uint8_t> data(size_t(length),0);
        const int size=nuvio_android::read_torrent(value,position,data.data(),length);
        if(size<0)throw std::runtime_error("Interrupted");
        auto output=env->NewByteArray(size);if(output&&size)env->SetByteArrayRegion(output,0,size,reinterpret_cast<const jbyte*>(data.data()));return output;
    }catch(const std::exception&){io_error(env);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_handshakehackin_nuviomaxd_androidtv_core_CoreBridge_nativeCloseTorrent(JNIEnv*,jobject,jlong handle) {
    std::shared_ptr<nuvio_android::TorrentReader> value;
    {std::lock_guard<std::mutex> lock(readers_mutex);auto found=readers.find(handle);if(found!=readers.end()){value=found->second;readers.erase(found);}}
    if(value)nuvio_android::cancel_torrent(value);
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_handshakehackin_nuviomaxd_androidtv_core_CoreBridge_nativeCancelTorrentReads(JNIEnv*,jobject) {
    std::lock_guard<std::mutex> lock(readers_mutex);for(const auto& [id,value]:readers)nuvio_android::cancel_torrent(value);
}
