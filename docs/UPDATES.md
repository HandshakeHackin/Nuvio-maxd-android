# Independent Android TV updates

Android and PS5 releases have separate repositories, package formats, version histories and feeds. Updating the PS5 repository cannot advertise an Android APK.

| Setting | Android TV |
| --- | --- |
| Repository | `HandshakeHackin/Nuvio-maxd-android` |
| Release package | `io.github.handshakehackin.nuviomaxd.androidtv` |
| Development package | `io.github.handshakehackin.nuviomaxd.androidtv.debug` |
| Stable feed | `updates/android-tv/stable.json` on `main` in this repository |
| Release tags | `android-tv-v0.1.0`, etc. |
| APK names | `NuvioMaxd-AndroidTV-0.1.0.apk`, etc. |
| Version source | This repository's `version.properties` |

The initial feed has `latest: null`. A build is advertised only after its signed APK has been uploaded successfully. PS5 release numbers have no relationship to Android version codes.

## On the TV

1. Open **App updates → Check for updates**.
2. Select **Download update** if a newer compatible Android TV release exists.
3. The app downloads into its private cache and verifies the advertised size and SHA-256, APK package/version/minimum SDK, and signing certificate against the installed app.
4. Select **Allow app installs** if Android requires permission. Grant it in Android Settings, return to Max'd and select **Install update**.
5. Android's package installer asks for confirmation. The app never installs silently.

Downloads use HTTPS. The manifest must identify Android TV, the stable channel and the exact Android package. Initial APK URLs must match this repository, the Android tag and the expected APK filename; redirects are restricted to GitHub's HTTPS asset storage. Downgrades, wrong-platform releases, partial/corrupt APKs and different signing certificates are rejected. The APK is checked again immediately before opening the installer.

No PS5 updater code, catalog submission, executable replacement or console storage paths are used. Checks are manual in this preview; there is no background installation service.

## Publish an Android release

Create and securely back up one Android release keystore. Keep the same signing key for all releases; this initial updater requires matching current signing certificates and does not implement key rotation. Never commit the key, passwords or a base64 copy.

Configure these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: base64 of the release keystore.
- `ANDROID_KEYSTORE_PASSWORD`: keystore password.
- `ANDROID_KEY_ALIAS`: signing alias.
- `ANDROID_KEY_PASSWORD`: key password.

For each release:

1. Increase `VERSION_CODE` in `version.properties`; set a new numeric `VERSION_NAME` such as `0.2.0`. Keep this sequence independent of PS5 versions.
2. Update `docs/RELEASE_NOTES.md` and merge the tested Android changes into `main`.
3. Run **Publish Android TV release** from Actions on `main`.

The workflow runs checks, builds and verifies a signed APK, creates an Android-tagged release with its matching source archive, then advances only the Android TV feed. It refuses to reuse an existing tag or advertise a non-increasing version code. If the feed commit is blocked by branch protection, submit the generated `android-tv-stable.json` as `updates/android-tv/stable.json` through a pull request after confirming that the APK is published. Do not overwrite a published APK.

GitHub feed availability requires network access. Installation still depends on Android's installer, device policy and a compatible signed APK. Development builds are deliberately excluded from stable installation.

## Device acceptance checks

Before the first stable release, test on a real Android TV / Google TV device:

- D-pad focus, Back, keyboard entry and player controls.
- Working and unsupported HTTPS media, pause/seek, audio and subtitle selection, app background/resume.
- No-release/current/newer/unsupported-Android update states and network failures.
- Upgrade from signed version N to N+1 using the same release key; settings retention and updated version display.
- Installer cancellation, denied install permission, return from Settings and download interruption.
- Reject APKs with the wrong package, signing key or advertised version, and corrupted/truncated downloads.

JVM tests cover feed isolation, version decisions, URL constraints and artifact integrity. A successful build does not establish TV playback or installation behavior.
