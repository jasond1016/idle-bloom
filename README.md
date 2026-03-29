# Idle Bloom

`Idle Bloom` is an Android TV screensaver app for showing family photos from a NAS over WebDAV.

## Current Status

The project is past the empty-skeleton stage and currently supports:

- Android TV launcher activity for configuration
- `DreamService`-based system screensaver integration
- WebDAV photo discovery using `PROPFIND`
- Basic Auth for listing and image loading
- Chinese path support for shared folder names and subdirectories
- Cleartext `http` support for local-network NAS testing
- Trust of user-installed certificates for self-signed `https` setups

The project does not yet support:

- SMB sources
- local indexing or offline caching of the photo list
- in-app slideshow preview button
- stronger TV-specific focus polish
- custom certificate pinning or advanced TLS UX

## Important Configuration Semantics

The settings UI intentionally splits the NAS address from the NAS folder path.

- `WebDAV base URL` should be only the NAS host and WebDAV port
- `Photo folder` should contain the shared folder path beginning with `/`

For QNAP-like setups, use values like:

- `WebDAV base URL`: `http://192.168.1.20:80`
- `Photo folder`: `/Public`

Or a nested album:

- `WebDAV base URL`: `http://192.168.1.20:80`
- `Photo folder`: `/家庭照片/精选`

Do not put the shared folder into both fields.

## Build

Open the `idle-bloom` folder in Android Studio. The project includes a Gradle wrapper.

If you build from a terminal on this machine, make sure Gradle runs on a full JDK 17 or newer. The machine-wide `JAVA_HOME` currently points to Java 8, which is too old for Android Gradle Plugin 8.7.

PowerShell example:

```powershell
cd D:\my_projects\idle-bloom
$env:JAVA_HOME='C:\Users\Jason\.jdks\corretto-17.0.13'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleDebug
```

Generated APK:

`app/build/outputs/apk/debug/app-debug.apk`

## Install On Android TV

```powershell
adb connect <tv-box-ip>
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## First Run Checklist

1. Open the app from the Android TV launcher.
2. Fill in `WebDAV base URL`, `Username`, `Password`, and `Photo folder`.
3. Press `Test connection`.
4. If discovery succeeds, go to Android TV screensaver settings and set `Idle Bloom` as the active screensaver.
5. Start the dream manually or wait for the idle timeout.

## Trigger The Screensaver Quickly

You do not need to wait for the idle timer every time.

ADB options:

```powershell
adb shell settings put secure screensaver_enabled 1
adb shell settings put secure screensaver_components com.idlebloom.app/.dream.IdleBloomDreamService
adb shell am start -n "com.android.systemui/.Somnambulator"
adb shell dumpsys dream
```

The `Somnambulator` command usually behaves like the system's `Start now` action for the current screensaver.

## Troubleshooting Notes

### `CLEARTEXT communication not permitted`

The app already opts in to cleartext local-network traffic. If you still see this, reinstall the latest APK because the old build did not include the network security config.

### `https ... not verified`

This usually means one of these:

- the NAS uses a self-signed certificate the TV does not trust yet
- the certificate is issued for a hostname but you are connecting via raw IP

The app trusts user-installed certificates, so you can install your NAS CA certificate on the TV and retry.

### `WebDAV PROPFIND failed: 400`

Most likely causes:

- wrong port
- `Photo folder` left as `/`
- request is hitting a normal web endpoint rather than the actual WebDAV share path

For QNAP, start with:

- `WebDAV base URL`: `http://NAS_IP:WebDAV_PORT`
- `Photo folder`: `/Public`

## More Context

For a fuller project handoff, architecture summary, and known next steps, see `HANDOFF.md` in the project root.
