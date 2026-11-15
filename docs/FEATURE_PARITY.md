# PS5 → Android TV feature coverage

Baseline: PS5 Max'd 1.0.0.11, source commit `441054bbbeff490df1c0e03ac18ed295f72aff65`. Android 0.2.0 imports the portable services and provides Android UI/playback adapters. “Implemented” describes code coverage, not a certification of every service or Android TV model. This comparison follows the PS5 README's feature inventory.

| PS5 feature | Android TV implementation | Verification / limits |
| --- | --- | --- |
| Phone sign-in; persistent login | Shared pairing, refresh/session adapter; QR UI | Account protocol fixtures; live account acceptance required |
| Guest; six profiles; PIN; profile creation | Shared account adapter; private per-profile data and stale-request rejection | Account fixtures and Android profile-state/bridge tests |
| Profile addons; inherited sources; local scraper preferences | Shared source sync and provider settings; Android management screens | Account/provider fixtures; inherited cloud writes retain server-side checks |
| Catalogs, genres, search, details, seasons, episodes and artwork | Shared Stremio protocol; Android D-pad lists and episode rows | Bridge fixture covers manifest → catalog → details → sources |
| Source metadata, filters and cached results | Shared stream parser/display/cache and explicit Reload | Shared cache/episode fixtures; advertised badges remain source claims |
| Nuvio library, title search, sorting, continue watching | Shared library/progress adapter; Android library UI | Account/watch fixtures; cloud membership is managed in Nuvio as in the PS5 read workflow; separately labelled TV-local Save/remove |
| Offline progress; watched/unwatched; new-episode badges | Shared watch queue, history and release-date checks | Account and series-update fixtures; real cross-device acceptance required |
| Trakt, SIMKL, MDBList; separate library/progress sources | Shared tracker adapters; pairing/key/import/source UI | Tracking fixtures; SIMKL needs application configuration |
| TorBox, Premiumize; Nuvio import; background preparation | Shared service clients and prepared-link lifecycle | Connected-service and instant-playback fixtures |
| JavaScript provider repositories and options | Shared QuickJS runtime and bundled polyfills | Provider regressions; switches, selects, text/password and numeric settings; DEX/CS3 and browser challenges remain unsupported |
| Direct HTTP(S), HLS and DASH; request headers | Media3 with an origin-scoped OkHttp transport; VLC for compatible requests | Header policy unit tests; VLC accepts User-Agent/Referer, authenticated custom-header streams use Media3 |
| Trailers | Shared discovery/resolution and history-free Android playback | Trailer fixtures; unsupported hosts/formats remain unavailable |
| IntroDB; ARM/AniSkip; optional Anime-Skip | Shared conservative timing parsers/identity mapping; Android skip prompts | 61 shared episode tests plus Android playback-flow tests |
| Next episode; matching source; cancellable countdown | Shared episode/source matcher; Android HTTP/debrid/torrent preparation | Shared matcher and Android countdown tests; pause/buffering hold and post-credit protection |
| M3U/Xtream Live TV; groups/search; per-profile favorites/recents | Shared parser/fetcher and Android menus | Large-list live fixtures and bridge favorites/recents tests |
| XMLTV and Xtream Now/Next; manual guide; guide switch | Shared bounded XML/EPG parser/cache; Android options | Live-guide fixtures; provider guide availability varies |
| IPTV movie/series library; A–Z; categories/search; outage cache; local resume | Shared IPTV library and Android browser/player | Large catalogs, cache/outage and account isolation fixtures |
| Four-screen TV | Four adaptive Media3 previews, focus and selected audio | Experimental; 640×360/800 kbps preview caps, hardware and provider connection limits |
| Plex phone pairing/discovery | Shared Plex adapter; QR/server selection | Plex fixtures; real server discovery acceptance required |
| Plex/Jellyfin/Emby browsing, auth artwork, resume, original/compatible streams and reporting | Shared server adapter; Android browsing/auth-art cache/player reporting | Media-server protocol/security fixtures; real server acceptance required |
| Native torrent playback, magnets, metadata files, rolling cache | Shared engine; JNI-backed seekable Media3 data source; Android document picker | Bridge test verifies opt-in and ranged data against SHA-1-verified owned content; DHT/UPnP/real-peer behavior needs network testing |
| Phone/PC Web Drop and recents | Shared Web Drop, QR UI, explicit confirmation, foreground-only listener | Shared engine; physical LAN acceptance required; stop on close/background/profile change |
| Native player; optional EVO/VLC | Android Media3 and VLC | PS5 native/EVO binaries depend on console APIs and are replaced by Android backends |
| 4K presentation and display resolution/refresh choices | Android display modes, Surface frame-rate hints and hardware decoders | Device/TV capabilities determine actual output; real hardware verification required |
| HDR auto/SDR conversion/required HDR display | Media3 hardware HDR path and API 31+ decoder SDR request; display guard | Tone mapping is decoder-dependent; SDR conversion is not promised for VLC or every Android device |
| PCM, surround, passthrough and 50–300% gain | Media3 audio sink/PCM processor; VLC output/equalizer | PCM identity/downmix/night-mode unit tests; receiver/codec passthrough acceptance required |
| Audio delay ±2 s; subtitle delay ±10 s; read-ahead and seek intervals | Android PCM/text timing, VLC delays, player buffering and remote actions | Unit/build coverage; actual A/V sync and network recovery require media testing |
| Preferred languages, embedded/addon subtitles and manual Off/track recovery | Media3/VLC selectors, addon fallback and retained choices on reopen | Build coverage; varied track layouts need playback acceptance |
| Styled ASS/SSA, PGS, Unicode, size/font/colour/outline/background and preview | Media3 supported text/bitmap formats and Android caption styling; VLC compatibility path | Embedded ASS styles may override preferences; rendering differs between engines |
| Embedded chapters and seeking | VLC chapter list/jump, timeline markers and current chapter label | Use VLC; Media3's current UI does not expose chapter markers/timeline labels |
| Playback details and diagnostics | Actual Media3/VLC format/display facts; redacted startup/drop/error/crash report | No stream URLs, credentials or exception messages are put in the share report |
| D-pad/gamepad/Media Remote/CEC; local mapping | Android KeyEvent navigation and mapping | Only keys delivered by Android can be mapped |
| Settings search; reduced motion; contrast; calm background; sounds | Android searchable preferences and native UI | Android implementation; screen-reader/device acceptance still required |
| Logo-inspired controller and console lighting | Optional RGB gamepad fade on supported Android 12+ input devices | Android does not expose PS5 console lights; sessions close to restore device control |
| Changelog and optional support QR | Android version-change dialog, About and Ko-fi QR | Included in app |
| Install-source reminders and verified in-app update installation | Independent Android release feed; APK validation and system installer | 11 updater unit tests; Android install confirmation replaces PS5 ZIP/FFPFSC/helper paths |

The port does not add PS5-excluded features such as recording/catch-up, DEX/CS3 providers or guaranteed support for every streaming host. Hardware-specific output and the Media3 chapter UI are explicit limits, not evidence that the whole app has reached verified feature parity. Use the acceptance checklist before a stable release.
