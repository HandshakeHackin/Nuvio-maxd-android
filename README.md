# Nuvio Max'd — Android TV

An Android TV / Google TV port of [Nuvio Max'd](https://github.com/HandshakeHackin/Nuvio-maxd-releases), with its own APKs and update channel.

## Current preview

The first Android TV foundation includes a TV launcher, D-pad navigation, HTTPS direct/HLS/DASH playback using Android Media3, and a separate Android update screen. Android 8.0 / API 26 or newer is required.

**Nuvio sign-in, profiles, addon browsing and library/watch-progress sync are not implemented yet.** This preview is the starting point for the port. Phone and tablet layouts are out of scope for this first stage.

Development APKs are available as artifacts of successful [Android TV checks](https://github.com/HandshakeHackin/Nuvio-maxd-android/actions/workflows/android-ci.yml). Signed public APKs will appear under [Releases](https://github.com/HandshakeHackin/Nuvio-maxd-android/releases). The stable feed stays empty until a signed release is published.

## Build and test

Install JDK 17 or 21 and the Android SDK with `platforms;android-35` and `build-tools;35.0.0`. Set `ANDROID_HOME` to the SDK directory or set `sdk.dir` in an untracked `local.properties` file.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The development APK is `app/build/outputs/apk/debug/app-debug.apk`. It installs as a separate `.debug` package and cannot install stable updates. Release builds use the signing environment variables described in [the update guide](docs/UPDATES.md).

## Android updates stay separate

Android has its own application ID, `version.properties`, `android-tv-v…` tags and `updates/android-tv/stable.json` feed in this repository. PS5 releases do not change any of these. Updates verify the platform, package, version code, APK checksum/size and installed signing certificate before opening Android's installer for confirmation.

See [the update and release guide](docs/UPDATES.md) for signing secrets, release steps and device acceptance checks.

## Next porting milestones

1. Bring over Nuvio account pairing, profile selection and persistence.
2. Add addon catalogs, title details and stream selection.
3. Connect library and watch-progress sync.
4. Port and test the remaining Max'd features incrementally on TV hardware.

## License and credits

[GPL-3.0](LICENSE), consistent with the PS5 edition. See [third-party notices](THIRD_PARTY.md). Nuvio Max'd is an unofficial client and is not affiliated with Nuvio, Stremio, Sony or Google.

The logo is copied unchanged from the PS5 project. The PS5 edition credits [NuvioTVSmart](https://github.com/NuvioMedia/NuvioTVSmart) for branding/account protocols, [Sp9nky's port](https://github.com/Sp9nky/unofficial-stremio-ps5-port) for its native UI/player base and [BlackBearReloaded's UI toolkit](https://github.com/blackbearreloaded/ps5-homebrew-ui). This initial Android implementation uses Android platform views and [AndroidX Media3](https://github.com/androidx/media), licensed under Apache-2.0; no PS5 C++ implementation has been imported yet. Preserve corresponding license notices when shared code is brought over.
