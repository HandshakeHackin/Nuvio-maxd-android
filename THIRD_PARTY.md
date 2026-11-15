# Third-party notices

The APK bundles GPL-3.0, Apache-2.0, LGPL-2.1 and native/provider dependency notices under `assets/licenses/`. Imported source notices are retained. Historical PS5 component credits are preserved in [shared/PS5-THIRD_PARTY.md](shared/PS5-THIRD_PARTY.md); only the imported portable subset and the components below are used in Android.

| Component | License | Source |
| --- | --- | --- |
| Nuvio Max'd logo (copied unchanged) | PS5 project's GPL-3.0 distribution; retain original branding credits | https://github.com/HandshakeHackin/Nuvio-maxd-releases |
| Portable Max'd C++ service core and regression fixtures | GPL-3.0-or-later; original notices retained | [Pinned provenance](shared/UPSTREAM.json) |
| AndroidX Core 1.15.0 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Media3 1.6.1 | Apache-2.0 | https://github.com/androidx/media |
| AndroidX Lifecycle 2.8.7 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin standard library 2.1.20 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| OkHttp 4.12.0 and transitive Okio | Apache-2.0 | https://github.com/square/okhttp |
| ZXing Core 3.5.3 | Apache-2.0 | https://github.com/zxing/zxing |
| VideoLAN libVLC Android 3.6.5 | LGPL-2.1-or-later and bundled component notices; Max'd is GPL-3.0 | https://code.videolan.org/videolan/libvlcjni |
| OpenSSL 3.5.9 | Apache-2.0 | [Exact source/checksum](native-dependencies.json) |
| curl 8.23.0 | curl license | [Exact source/checksum](native-dependencies.json) |
| Expat 2.10.0 | MIT | [Exact source/checksum](native-dependencies.json) |
| QuickJS 2026-06-04 | MIT | [Exact source/checksum](native-dependencies.json) |
| jech/dht pinned commit | MIT | [Exact source/checksum](native-dependencies.json) |
| miniupnpc 2.2.8 | BSD-3-Clause | [Exact source/checksum](native-dependencies.json) |
| nlohmann/json bundled header | MIT | Copyright Niels Lohmann; notice in [json.hpp](shared/native-app/third_party/json.hpp) |
| Provider runtime/polyfills | Respective upstream licenses | [Source/build recipes and complete licenses](shared/native-app/third_party/provider-runtime/) |
| Android zlib and C++ runtime | zlib / Apache-2.0 with LLVM exceptions | Android NDK 27.2.12479018 |

Media3 / AndroidX: Copyright The Android Open Source Project. Kotlin: Copyright JetBrains s.r.o. and respective authors and developers. Components and their transitive dependencies retain their own notices. Build and test dependencies are not part of the app's implementation license.

The Android playback adapters do not bundle the PS5 decoder/EVO, console UI, console lighting or FFPFSC installer. VLC is consumed as the unmodified `org.videolan.android:libvlc-all:3.6.5` Maven artifact; its upstream build/source and component licensing remain applicable. Native dependencies are rebuilt from the checksum-pinned archives rather than copied PS5 binaries.

This product uses the TMDB API but is not endorsed or certified by TMDB. Provider metadata integrations require their own supported configuration. Optional IntroDB, ARM, AniSkip, Anime-Skip, Plex, Trakt, SIMKL, MDBList, TorBox and Premiumize integrations retain their service requirements. The APK's bundled account configuration and Trakt client identifier are public application identifiers, not private account tokens.
