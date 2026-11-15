# Android TV 0.2.0 development port

- Import the PS5 1.0.0.11 portable service core with Android NDK/JNI bindings.
- Add Nuvio pairing, profile/PIN selection, addon catalogs/search/details, provider scripts/settings, library/resume/watched sync, tracker sources and connected debrid services.
- Add M3U/Xtream Live TV, XMLTV/short EPG, favorites/recents, four-screen previews, cached IPTV movie/series catalogs, and Plex/Jellyfin/Emby.
- Add Media3/VLC playback, language/track selection, addon subtitles, styling/delays, chapters through VLC, audio gain/night mode/downmix, display/HDR preferences, trailer playback, skip timings and cancellable next-episode preparation/autoplay.
- Add opt-in native torrent streaming, file import and phone/PC Web Drop, plus profile-specific caches and lifecycle cleanup.
- Add searchable settings, remote mapping, accessibility preferences, optional supported gamepad RGB lights, changelog, support QR and redacted playback diagnostics.
- Retain the independent Android update feed, APK signature/checksum validation and user-confirmed installer. PS5 releases do not update this app.

This development build requires physical Android TV acceptance testing. Shared-service/bridge fixtures and Android build checks do not establish live-account compatibility, HDR/receiver output, codec support or four-screen capacity on a particular TV. See [feature coverage](FEATURE_PARITY.md) and [testing](TESTING.md). PS5-specific console lighting, EVO and FFPFSC installation have Android platform counterparts rather than PS5 binaries in the APK.

Development APKs use a separate package/signing identity and cannot upgrade through the stable release channel.
