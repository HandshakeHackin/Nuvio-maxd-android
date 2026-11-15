package io.github.handshakehackin.nuviomaxd.androidtv.core

data class Preference(val key: String, val name: String, val category: String, val options: List<Pair<String, Any>>, val default: Any)

object Preferences {
    private fun choices(vararg values: String) = values.map { it to it }
    private fun numbers(vararg values: Int) = values.map { it.toString() to it }
    private val toggles = listOf("Off" to false, "On" to true)
    val all = listOf(
        Preference("engine", "Playback engine", "Video", listOf("Android Media3" to "media3", "VLC" to "vlc"), "media3"),
        Preference("hdr", "HDR output", "Video", listOf("Automatic" to "auto", "Convert to SDR when supported" to "sdr", "Require HDR display" to "require"), "auto"),
        Preference("resolution", "Display resolution", "Video", listOf("Follow TV" to "system", "1080p" to "1080", "1440p" to "1440", "2160p" to "2160"), "system"),
        Preference("refresh", "Display refresh", "Video", listOf("Follow TV" to "system", "Match video" to "match", "Prefer 60 Hz" to "60", "Prefer 120 Hz" to "120"), "match"),
        Preference("readAhead", "Read-ahead seconds", "Playback", numbers(30,60,120,300), 60),
        Preference("audioMode", "Audio output", "Audio", listOf("Automatic / passthrough" to "auto", "Decoded PCM" to "pcm"), "auto"),
        Preference("surround", "Surround audio", "Audio", toggles, true),
        Preference("gain", "Volume / boost percent", "Audio", numbers(50,75,100,125,150,200,250,300), 100),
        Preference("nightMode", "Night mode", "Audio", listOf("Off" to 0,"Gentle" to 1,"Strong" to 2), 0),
        Preference("lfeMix", "LFE downmix percent", "Audio", numbers(0,25,50,75,100), 50),
        Preference("audioDelay", "Audio delay (milliseconds)", "Audio", numbers(-2000,-1000,-500,-250,0,250,500,1000,2000), 0),
        Preference("audioLanguage", "Preferred audio language", "Audio", choices("eng","spa","fra","deu","ita","por","jpn","kor","zho","ara","hin","rus"), "eng"),
        Preference("subtitleLanguage", "Preferred subtitle language", "Subtitles", choices("off","eng","spa","fra","deu","ita","por","jpn","kor","zho","ara","hin","rus"), "eng"),
        Preference("subtitleDelay", "Subtitle delay (milliseconds)", "Subtitles", numbers(-10000,-5000,-2000,-1000,-500,0,500,1000,2000,5000,10000), 0),
        Preference("subtitleSize", "Subtitle size percent", "Subtitles", numbers(75,100,125,150,200), 100),
        Preference("subtitleColor", "Subtitle color", "Subtitles", choices("White","Yellow","Cyan","Green"), "White"),
        Preference("subtitleFont", "Subtitle font", "Subtitles", choices("Default","Sans","Serif","Monospace"), "Default"),
        Preference("subtitleOutline", "Subtitle outline / shadow", "Subtitles", choices("Outline","Shadow","None"), "Outline"),
        Preference("subtitleBackground", "Subtitle background", "Subtitles", toggles, false),
        Preference("seekSeconds", "D-pad seek seconds", "Playback", numbers(5,10,15,30,60), 10),
        Preference("longSeekSeconds", "Shoulder / page seek seconds", "Playback", numbers(30,60,120,300), 60),
        Preference("introSkip", "IntroDB skip intro / recap / credits", "Playback", toggles, true),
        Preference("animeSkip", "AniSkip / Anime-Skip timings", "Playback", toggles, true),
        Preference("autoplay", "Next-episode autoplay", "Playback", toggles, true),
        Preference("countdown", "Next-episode countdown seconds", "Playback", numbers(5,10,15,30,60,120), 10),
        Preference("torrents", "Peer-to-peer torrents", "Addons", toggles, false),
        Preference("torrentCacheGiB", "Torrent rolling cache GiB", "Addons", numbers(1,2,4,6), 1),
        Preference("reducedMotion", "Reduced motion", "Appearance", toggles, false),
        Preference("highContrast", "High contrast", "Appearance", toggles, false),
        Preference("uiSounds", "Interface sounds", "Appearance", toggles, false),
        Preference("controllerLights", "RGB gamepad light fade (supported Android 12+ devices)", "Appearance", toggles, false),
    )
    fun value(preference: Preference): Any = CoreBridge.settings.opt(preference.key)?.takeUnless { it == org.json.JSONObject.NULL } ?: preference.default
    fun label(preference: Preference) = preference.options.firstOrNull { it.second.toString() == value(preference).toString() }?.first ?: value(preference).toString()
}
