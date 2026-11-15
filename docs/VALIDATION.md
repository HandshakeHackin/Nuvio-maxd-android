# Android TV 0.2.0 development validation

Recorded 2026-11-15. PS5 service-source baseline: `441054bbbeff490df1c0e03ac18ed295f72aff65` (1.0.0.11). Android integration source is on `android-tv/bootstrap`; see [PR #1 and its checks](https://github.com/HandshakeHackin/Nuvio-maxd-android/pull/1).

| Check | Observed result |
| --- | --- |
| Imported shared-service regressions | All 13 suites pass: live TV, live guide, IPTV library, episode/timings, connected services, tracking, media servers, Plex, instant playback, series updates, trailers, account/watch sync and providers |
| Android JVM regressions | 26 tests pass: updater/integrity, profile/settings ordering, verified-storage transitions, transport headers, PCM/downmix/night mode and autoplay/skip flow |
| Android dispatcher and torrent-reader fixture | 54 calls pass, including actual owned web-seed bytes across piece boundaries/EOF, disabled-P2P rejection and stale-profile rejection |
| Android account-boundary fixture | 18 calls pass against local HTTPS: pair, revoke a session, preserve Guest data, isolate an unverified login, preserve verified account settings and reject stale requests |
| Debug checks | `testDebugUnitTest lintDebug assembleDebug` passes for ARM64, ARMv7 and x86-64 |
| Release checks | `lintRelease assembleRelease` passes for all three ABIs; release APK remains unsigned in this workspace |
| APK inspection | Development package/version/API/launcher checked; APK v2 signature verified; all expected core/VLC libraries present; 16 KiB APK alignment checked and 64-bit native LOAD segments use 16 KiB alignment |
| GitHub clean build | Full native dependency/service/bridge/Android pipeline passed for the main port commit; final follow-up status is linked from PR #1 |
| AOSP Android API 26 x86-64 VM, 1280×720 | Guest launch → local addon catalog → title details → source selection → Media3 H.264/AAC 320×180 test video; actual first-frame callback and screenshot observed, HTTP range seeking (206), pause and a 52.794-second resume position saved |

The VM used a generated colour-pattern video and sine wave served by a local fixture. No personal Nuvio, tracker, debrid, IPTV or media-server account was used. The API 35 VM initialized the app/core, but its system UI became unresponsive under software emulation; it is not used as playback acceptance evidence. The API 26 phone-system VM used a landscape TV display profile and explicit app launch; it does not establish TV launcher, remote, HDMI, audio receiver or decoder-capacity behavior on real hardware.

Physical Android TV testing remains required for the [acceptance checklist](TESTING.md): live-account interoperability, codecs/ASS/PGS across files, HDR/SDR output, surround/passthrough and sync, display switching, four-screen capacity, real-peer DHT/UPnP, LAN Web Drop, supported controller lights and a signed N-to-N+1 installation. The stable feed remains `latest: null`; no stable Android release is published by this work.
