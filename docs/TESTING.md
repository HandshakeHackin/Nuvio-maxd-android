# Android TV validation

The port shares service code with PS5; Android UI, JNI, playback, lifecycle and update installation require their own checks. Do not infer physical-device results from a successful build or host fixture.

## Repeatable build and host fixtures

Use the SDK/JDK prerequisites in [README](../README.md). Host suites also need a C++20 compiler, `pkg-config`, OpenSSL/libcurl/Expat development libraries and zlib. On Ubuntu these are `g++ pkg-config libssl-dev libcurl4-openssl-dev libexpat1-dev zlib1g-dev`.

```sh
python3 tools/native_dependencies.py
./gradlew testDebugUnitTest lintDebug assembleDebug
python3 tools/test_shared_core.py
"$ANDROID_HOME/cmake/3.22.1/bin/cmake" -S app/src/main/cpp -B build/host-core -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$ANDROID_HOME/cmake/3.22.1/bin/ninja" -DCMAKE_BUILD_TYPE=Release
"$ANDROID_HOME/cmake/3.22.1/bin/cmake" --build build/host-core --parallel 2
python3 tools/test_android_bridge.py
python3 tools/test_android_account_boundary.py
```

The service runner writes individual logs and `results.json` under `build/shared-tests/`. `--suites media_servers plex` reruns selected suites while preserving previous recorded results. CI always runs the full set. Host dispatcher builds disable public default torrent trackers, DHT and UPnP; the torrent fixture uses only its own localhost web seed and verifies bytes across piece boundaries and at EOF. This does not test a real swarm. The account-boundary fixture pairs against a local HTTPS backend, revokes its refresh token and checks that account data cannot be returned or persisted as Guest; errors must publish the updated context and reject stale-profile requests.

JVM tests cover updater rejection/validation, stale account/profile/settings responses, header origin isolation/injection, PCM identity/downmix/night mode, post-credit protection and paused/cancelled autoplay. Service suites cover live TV, XMLTV/EPG, IPTV cache/outages, episode/timing parsers, debrid clients, tracker contracts, media servers, Plex, prepared links, series updates, trailers, accounts/watch sync and JavaScript providers.

## TV acceptance checklist

Before a stable release, record device model, Android version and app build, then use content/accounts you control:

- Launch from the TV home screen; navigate every menu with D-pad/Back and a gamepad. Check focus visibility, text clipping, long lists, settings search, contrast, reduced motion, sounds and delivered remote-button mapping.
- Pair Nuvio; cancel/expire pairing; restart; switch PIN-protected profiles; create a profile; sign out. Confirm no sources, service credentials, artwork, resume, torrent or settings from another profile appear.
- Load account addons and a standard manifest. Browse/search/genre/page, view details/episode thumbnails, select/filter/reload sources and configure boolean/select/password provider options. Confirm an unavailable provider leaves usable navigation.
- Check Library/Continue Watching, watched/unwatched and new-episode badges with known released/future episodes. Resume on another device using the same progress source; disconnect/reconnect to verify queued progress.
- Exercise optional Trakt/SIMKL/MDBList sources independently from library membership, TorBox/Premiumize and explicit Nuvio import. Cancel pairing and ensure credentials stay out of displayed/shared diagnostics.
- Play owned HTTP/HLS/DASH and authenticated media through Media3. Check redirected segments cannot receive origin credentials. Exercise VLC's supported HTTP headers and failure handling.
- Check pause/seek/reopen, exact manual audio/subtitle choices and Off, automatic addon fallback, external SRT/VTT/ASS, Unicode/PGS, style/size preview, subtitle/audio delay, gain/night mode and LFE/stereo downmix. Use VLC to check chapters, markers and current label.
- Check actual 1080p/4K modes, frame-rate matching, HDR auto/SDR request/required-display behavior, receiver PCM/surround/passthrough and unsupported codec errors. Record TV/receiver signal readings separately from source badges.
- Check known intro/recap/credits/post-credit timings, missing timings, next-source matching, slow resolution, torrent next episode, paused/buffering countdown and cancellation.
- Add owned M3U/Xtream sources; search/filter/page, favorites/recents, XMLTV/manual guide and Xtream Now/Next. Check guide Off/On, cached IPTV categories/titles during outage, A–Z and per-profile VOD resume.
- Test four SD live channels only where the provider permits four concurrent connections. Check focus/audio selection, a failed cell, background/reopen and decoder/resource limits.
- Pair Plex and connect Jellyfin/Emby; check reverse-proxy URLs, auth artwork, folders/series/resume/search/pagination, original/compatible streams and progress/stop reports.
- Opt into torrents; import an owned metadata file/magnet; seek, change files, inspect recents/cache size, cancel and disable. Check Web Drop on a trusted LAN, confirmation, dismissal/expiry and listener shutdown on background/profile change.
- Check changelog-on-version-change, optional support QR, sanitized diagnostics and supported input-device light fade/restoration.
- On two APKs signed with the same **Android release key**, exercise update check/download/checksum/signature/version/size rejection, unknown-source permission, installer confirmation and retained data. Confirm PS5 release/feed changes cannot trigger an Android update. Debug packages intentionally cannot use the stable channel.

Use [feature coverage](FEATURE_PARITY.md) to record limits alongside results. No stable Android release is published while the stable feed has `latest: null`.
