# Idle Bloom Handoff

## What This Project Is

`Idle Bloom` is an Android TV app whose goal is to show NAS-hosted family photos as the system screensaver.

The current implementation is intentionally narrow:

- one app module
- one settings screen
- one `DreamService`
- one WebDAV source implementation

This keeps the project easy to extend without over-abstracting the early version.

## Product Intent

The target use case is a TV box such as Mi Box / Android TV sitting on a home network, reading family photos from a QNAP NAS or another WebDAV-capable NAS.

Important framing:

- the app is for screensaver-time playback, not deep-sleep display
- the near-term focus is reliability on local-network WebDAV, not feature breadth

## Current Architecture

### Configuration and Persistence

- `app/src/main/java/com/idlebloom/app/settings/SettingsActivity.kt`
- `app/src/main/java/com/idlebloom/app/settings/SettingsStore.kt`

`SettingsActivity` is the only launcher activity today. It saves:

- base URL
- username
- password
- remote directory
- slide interval
- shuffle enabled

Persistence is via `DataStore Preferences`.

### Screensaver Runtime

- `app/src/main/java/com/idlebloom/app/dream/IdleBloomDreamService.kt`

The dream service:

- loads saved settings
- lists photos from WebDAV
- shuffles if configured
- loops forever with a simple delay-based slideshow
- uses Coil to display each image with Basic Auth added through a dedicated `OkHttpClient`

It is a minimal implementation and deliberately avoids extra layers.

### Source Model

- `app/src/main/java/com/idlebloom/app/data/SourceConfig.kt`
- `app/src/main/java/com/idlebloom/app/data/RemotePhoto.kt`
- `app/src/main/java/com/idlebloom/app/data/WebDavClient.kt`
- `app/src/main/java/com/idlebloom/app/data/WebDavPhotoSource.kt`
- `app/src/main/java/com/idlebloom/app/data/OkHttpWebDavClient.kt`

`WebDavPhotoSource` is just a thin wrapper around `WebDavClient` today.

That is enough abstraction to add SMB later without forcing a large architecture now.

## Current Networking Behavior

### Path Semantics

The app expects the NAS address and share path to be split:

- `baseUrl`: only scheme + host + port
- `remoteDirectory`: only the share path, beginning with `/`

Example:

- `baseUrl = http://192.168.1.20:80`
- `remoteDirectory = /Public`

The code now builds URLs with `HttpUrl` path segments rather than raw string concatenation, so Chinese share names and subfolders are encoded correctly.

### WebDAV Listing Strategy

`OkHttpWebDavClient` currently:

- sends `PROPFIND`
- tries both trailing-slash and no-trailing-slash variants of the directory URL
- tries multiple request body styles:
  - explicit `<prop>` request
  - `<allprop>` request
  - empty body
- adds a `User-Agent`, because some intermediaries reject requests without one

This compatibility behavior was added after real 400 responses while testing against NAS-style WebDAV servers.

### Image URL Handling

WebDAV `href` resolution is handled more defensively now:

- prefer HTTP URL resolution through `HttpUrl`
- fall back to `URI.resolve`
- decode path segments for nicer displayed names

### Network Security Configuration

The app includes:

- `android:usesCleartextTraffic="true"`
- `android:networkSecurityConfig="@xml/network_security_config"`

And the security config allows:

- cleartext traffic
- system CA trust
- user CA trust

This was added to support local `http` NAS testing and self-signed `https` certificates installed on the TV.

## Build and Environment Notes

### Important JDK Gotcha

The machine-wide `JAVA_HOME` in this environment points to Java 8.

That breaks Android Gradle Plugin 8.7 builds.

Working JDK used for successful builds here:

- `C:\Users\Jason\.jdks\corretto-17.0.13`

Working command:

```powershell
cd D:\my_projects\idle-bloom
$env:JAVA_HOME='C:\Users\Jason\.jdks\corretto-17.0.13'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleDebug
```

### Current Build Result

The project currently builds successfully with:

- `assembleDebug`

Debug APK location:

- `app/build/outputs/apk/debug/app-debug.apk`

## How To Test Quickly On TV

### Install

```powershell
adb connect <tv-ip>
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### Activate As Screensaver

Either use Android TV settings or ADB:

```powershell
adb shell settings put secure screensaver_enabled 1
adb shell settings put secure screensaver_components com.idlebloom.app/.dream.IdleBloomDreamService
```

### Trigger Immediately

Useful for fast testing instead of waiting for the idle timer:

```powershell
adb shell am start -n "com.android.systemui/.Somnambulator"
```

This usually launches the currently selected dream.

## Known Limitations

### UX

- no in-app preview button yet
- no dedicated `Start preview` action from the settings screen
- no special TV focus styling beyond standard Material behavior

### WebDAV / NAS

- no auto-discovery of shares
- no browse dialog for remote folders
- no richer diagnostic logging surfaced in the UI
- no support for digest auth, token auth, or SMB

### Performance / Robustness

- no cached photo index
- no photo prefetch scheduling beyond Coil's own behavior
- no retry backoff or connectivity-state awareness
- no EXIF/date overlay features yet

## Known Failure Modes

### `CLEARTEXT communication not permitted`

Usually means the user is still running an older APK from before network security config was added.

### `https ... not verified`

Usually means either:

- self-signed certificate not installed on the TV
- hostname mismatch because the user connected to a raw IP instead of the hostname in the certificate

### `WebDAV PROPFIND failed: 400`

Most often caused by:

- wrong WebDAV port
- invalid `remoteDirectory`, especially `/`
- hitting a non-WebDAV endpoint on the NAS

## Recommended Next Steps

If another AI or developer picks this up, these are the highest-value next tasks:

1. Add a `Preview slideshow` button in `SettingsActivity` that launches a normal full-screen activity using the same source config. This will remove the need to trigger the dream for every visual test.
2. Improve connection diagnostics by surfacing the exact candidate URL and method variant that succeeded or failed.
3. Add a lightweight local cache or index for the discovered photo list to reduce startup latency.
4. Add optional SMB support behind the existing `WebDavClient` / source abstraction.
5. Improve TV UX with clearer focus states and larger action targets.

## Files Worth Reading First

If you are handing off to another AI, the best order to read is:

1. `README.md`
2. `app/src/main/java/com/idlebloom/app/settings/SettingsActivity.kt`
3. `app/src/main/java/com/idlebloom/app/data/SourceConfig.kt`
4. `app/src/main/java/com/idlebloom/app/data/OkHttpWebDavClient.kt`
5. `app/src/main/java/com/idlebloom/app/dream/IdleBloomDreamService.kt`
