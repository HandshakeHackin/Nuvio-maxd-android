# Nuvio Max'd — Android TV

An Android TV / Google TV port of [Nuvio Max'd](https://github.com/HandshakeHackin/Nuvio-maxd-releases), with its own APKs and update channel.

## Android TV service port

Version 0.2.0 brings the portable PS5 1.0.0.11 C++ services to Android through the NDK/JNI. It adds Nuvio phone pairing and profiles, addon catalogs and JavaScript providers, library/watch progress, Trakt/SIMKL/MDBList, TorBox/Premiumize, Live TV and guides, IPTV movies/series, Plex/Jellyfin/Emby, trailers, skip timings, next-episode autoplay and optional torrents/Web Drop. Android TV controls and playback use native Android views, Media3 and VLC. Android 8.0 / API 26 or newer is required; ARM64, ARMv7 and x86-64 APK libraries are included.

**This is a development port, not a claim of verified parity on every TV.** Shared-service regression fixtures, Android bridge integration, unit tests, lint and APK builds cover the implementation. Account/provider availability and physical TV playback still require acceptance testing. See the [feature comparison](docs/FEATURE_PARITY.md) and [validation guide](docs/TESTING.md). Phone/tablet layouts are outside this first stage.

Development APKs are available as artifacts of successful [Android TV checks](https://github.com/HandshakeHackin/Nuvio-maxd-android/actions/workflows/android-ci.yml). Signed public APKs will appear under [Releases](https://github.com/HandshakeHackin/Nuvio-maxd-android/releases). The stable feed stays empty until a signed release is published.

## Build and test

On Linux, install JDK 17, Python 3.12+, Perl, Make and the Android SDK with `platforms;android-35`, `build-tools;35.0.0`, `ndk;27.2.12479018` and `cmake;3.22.1`. Set `ANDROID_HOME` to the SDK directory. The dependency helper downloads checksum-pinned sources and cross-compiles static OpenSSL/curl for each ABI. The first build takes longer; `.native-deps` is a local cache.

```sh
python3 tools/native_dependencies.py
./gradlew testDebugUnitTest lintDebug assembleDebug
```

To build only one ABI, use matching selections in both commands, for example `python3 tools/native_dependencies.py --abis arm64-v8a` and `./gradlew -PnuvioAbis=arm64-v8a assembleDebug`. [CI](.github/workflows/android-ci.yml) also runs the imported service regression suites and the Android dispatcher/stream-reader fixtures. Host test prerequisites and commands are in [TESTING.md](docs/TESTING.md).

The development APK is `app/build/outputs/apk/debug/app-debug.apk`. It installs as a separate `.debug` package and cannot install stable updates. Release builds use the signing environment variables described in [the update guide](docs/UPDATES.md).

## Android updates stay separate

Android has its own application ID, `version.properties`, `android-tv-v…` tags and `updates/android-tv/stable.json` feed in this repository. PS5 releases do not change any of these. Updates verify the platform, package, version code, APK checksum/size and installed signing certificate before opening Android's installer for confirmation.

See [the update and release guide](docs/UPDATES.md) for signing secrets, release steps and device acceptance checks.

## Using the port

Use **Sign in to Nuvio** to pair with a phone or continue as Guest. Choose the same account/profile and progress source on your other devices for cross-device resume. Add addon manifests under **Addons & JavaScript providers**, then enable individual scrapers. Provider compatibility depends on their APIs and configuration.

Live TV accepts your M3U or Xtream Codes sources; IPTV Library shows Xtream VOD separately. Connect your own media servers or optional tracking/debrid services in their menus. Peer-to-peer torrents start disabled and require an explicit opt-in in Settings. Web Drop starts only when requested and requires confirmation on the TV before opening a received torrent.

Media3 is the default player. VLC provides a second compatibility path, including embedded chapter navigation and styled subtitles. Display modes, HDR conversion, surround/passthrough, codecs and simultaneous decoders depend on the device. Night-mode/downmix processing and torrent data sources use Media3. Settings and progress are stored in the app's private profile directories. Cloud Library membership remains managed in Nuvio; local Save/remove is labelled separately.

## License and credits

[GPL-3.0](LICENSE), consistent with the PS5 edition. See [third-party notices](THIRD_PARTY.md). Nuvio Max'd is an unofficial client and is not affiliated with Nuvio, Stremio, Sony or Google.

The logo is copied unchanged from the PS5 project. The portable C++ service source is pinned in [shared/UPSTREAM.json](shared/UPSTREAM.json), with original notices retained. PS5 account/branding credits include [NuvioTVSmart](https://github.com/NuvioMedia/NuvioTVSmart), [Sp9nky's port](https://github.com/Sp9nky/unofficial-stremio-ps5-port) and [BlackBearReloaded's UI toolkit](https://github.com/blackbearreloaded/ps5-homebrew-ui). PS5 UI, console decoder/EVO, lighting and installer code are replaced by Android implementations. Native dependency source URLs and checksums are in [native-dependencies.json](native-dependencies.json). The source archive and pinned dependency/build recipes accompany Android releases.
