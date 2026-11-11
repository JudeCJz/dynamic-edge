# Dynamic Edge

An ultra-light Android corner widget. A small black notch sits in the bottom-right corner of the
screen, over every app. Tap or drag it and it melts open into six quick toggles (Wi-Fi, Bluetooth,
flashlight, Do Not Disturb, auto-rotate, vibrate) and a media player. It uses no AndroidX, no
Compose and does no work while idle.

Package `com.dynedge`. Target phone: Android 14, minSdk 30. Full design notes live in
`.agents/skills/dynamic-edge/SKILL.md`.

## Build

Needs JDK 21 and the Android SDK (`ANDROID_HOME`).

```
cd "D:\VS\PROJECTS\FREE WORK\dynamic-edge"
.\gradlew.bat assembleRelease
```

The APK lands in `app\build\outputs\apk\release\app-release.apk`.

## Install and grant

One step: `tools\install.ps1` (add `-NoBuild` to skip the build). It installs the APK, enables the
accessibility service without removing other enabled services, restarts it, and grants the rest.

By hand:

```
adb install -r app\build\outputs\apk\release\app-release.apk
adb shell settings put secure enabled_accessibility_services com.dynedge/com.dynedge.EdgeService
adb shell settings put secure accessibility_enabled 1
adb shell cmd notification allow_listener com.dynedge/com.dynedge.MediaListener
adb shell cmd notification allow_dnd com.dynedge
adb shell appops set com.dynedge WRITE_SETTINGS allow
adb shell pm grant com.dynedge android.permission.WRITE_SECURE_SETTINGS
```

`enabled_accessibility_services` is a colon-separated list. If other services are enabled, append
instead of overwriting.

The launcher app shows the state of each permission and opens the matching settings screen.

## Settings

Open the Dynamic Edge app on the phone:

- Size: "Corner size" (32 to 80 dp, default 48) is the idle notch; "Open size" (170 to 300 dp,
  default 216) is the expanded panel. Changes apply live to the running overlay. Reset restores
  the defaults.
- Apps: up to six app shortcuts shown in the open panel. Tap a row to remove it, "Add app" opens
  a picker. When never configured, defaults are resolved at runtime (dialer, camera, browser,
  messaging, gallery, Settings).

## Assistant corner swipe

On vivo phones the bottom-corner swipe launches the assistant (`navigation_google_assist` in
Settings.Secure). Dynamic Edge turns it off automatically when the service starts, which needs
`WRITE_SECURE_SETTINGS` (granted once over adb, line above). The Permissions list shows its
current state.

## Remove

```
adb uninstall com.dynedge
```
