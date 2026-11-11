Reading additional input from stdin...
OpenAI Codex v0.167.0
--------
workdir: D:\VS\PROJECTS\FREE WORK\dynamic-edge
model: gpt-6-luna
provider: openai
approval: never
sandbox: read-only
reasoning effort: medium
reasoning summaries: none
session id: 01a1c56d-d80b-7c50-89a3-1c13c62cc876
--------
user
You are a harsh senior Android + UI reviewer. Project: Dynamic Edge (folder dynamic-edge, package com.dynedge), an
Android overlay (accessibility service) that shows a black bean in the bottom-right screen corner; tapping it opens a
quarter-circle radial panel with swipeable windows (QUICK, MEDIA, CLIPBOARD, APPS, and new VOLUME, TIMER,
NOTIFICATIONS, DEVICE). Read .agents/skills/dynamic-edge/SKILL.md, review/SPEC_v1_4.md, review/LOOKS_DECISION.md,
then the Kotlin sources in app/src/main/java/com/dynedge (EdgeView.kt and EdgeService.kt are the core; the code is
mid-refactor, some window code may be moving into Win*.kt files). Do NOT edit any file. Output only your review.

Criticize everything, ranked most severe first, each item with file:line, the concrete failure, and the fix:
1. Reliability: anything that can make the overlay disappear or crash (window add/remove, service lifecycle,
   BadTokenException in onServiceConnected, OEM background kills such as vivo com.vivo.abe force-stopping the app,
   receivers/listeners leaks, threading).
2. A reported bug: "windows go black when I swipe back and up" (panel content goes dark/black during or after paging
   back to a previous window, or on a vertical swipe). Find the most likely root cause in the paging / RenderNode
   section layers / pageFade / alpha / gesture code and say exactly how to fix it.
3. Rendering performance (per-frame allocations, invalidation, layers, jank at 120 Hz).
4. Gesture handling (tap vs drag vs page swipe vs arc drag, MotionEvent mutation, gesture exclusion, touch region).
5. UI/UX and visual design of the panel, windows and settings screen: hierarchy, legibility, touch targets, motion,
   consistency, anything that looks cheap. Rules the app must follow: no emojis, Lucide-style icons, flat UI (no boxes
   in boxes), minimal text.
6. Code structure and anything else that is bad.
End with a top-10 "rework these first" list. Be specific and blunt; no praise.
codex
I’ll read the requested skill and review documents first, then inspect the current Kotlin sources with line numbers. I’ll keep the checkout read-only and return only ranked findings plus the top ten rework list.
exec
"C:\\WINDOWS\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" -Command 'Get-Content .agents/skills/dynamic-edge/SKILL.md; Get-Content review/SPEC_v1_4.md; Get-Content review/LOOKS_DECISION.md; rg --files app/src/main/java/com/dynedge' in D:\VS\PROJECTS\FREE WORK\dynamic-edge
 succeeded in 1385ms:
---
name: dynamic-edge
description: Dynamic Edge - ultra-light Android floating corner widget (black bean-shaped notch in the bottom-right that melts open into quick toggles + media player). Architecture, interface contract, build/install/grant runbook.
---

# Dynamic Edge

Project root: `D:\VS\PROJECTS\FREE WORK\dynamic-edge`. Package `com.dynedge`, label "Dynamic Edge".
Design source: `D:\Downloads\index.html` (DYN EDGE radial corner study). Only the idle shape is taken
from it literally; everything is pure black / white / grey, no accent colour, no theming.

Target phone: vivo I2202, Android 14 (API 34), 1080x2400 @ 440 dpi (392 dp wide), 120 Hz,
gesture nav, display rounded corner radius 78 px.

## Hard rules
- No emojis anywhere. Icons are Lucide-style VectorDrawables (24 viewport, stroke 2, round caps/joins).
- No AndroidX, no Compose, no AppCompat, no XML layouts for the overlay. Framework APIs only.
- Zero work while idle: no Choreographer frames, no timers, no broadcast receivers except screen on/off/user-present.
- No per-frame allocations in the draw/animation path (reuse Path, Paint, RectF, Region).

## Architecture

| Component | File | Role |
|---|---|---|
| `EdgeService` | `EdgeService.kt` | AccessibilityService. Owns the overlay window (TYPE_ACCESSIBILITY_OVERLAY: trusted, so Android 12+ untrusted-touch blocking never applies; system-bound so no foreground notification). Hides on IME / keyguard / screen off. |
| `EdgeView` | `EdgeView.kt` | Single custom View. Draws shape + all content on Canvas, runs spring physics, handles touch. |
| `Spring` | `Spring.kt` | Damped spring (value, velocity, target, stiffness, dampingRatio), analytic or substepped integration. |
| `TouchRegion` | `TouchRegion.kt` | Reflection hook `ViewTreeObserver.addOnComputeInternalInsetsListener` (hidden, greylisted; targetSdk 28 keeps it reachable) -> TOUCHABLE_INSETS_REGION = shape region. Fallback: whole window touchable. |
| `QuickToggles` | `QuickToggles.kt` | State + actions for 6 toggles. Listeners only between start()/stop(). |
| `MediaBridge` | `MediaBridge.kt` | MediaSessionManager active sessions -> title/artist/art/state/position + transport controls. Listeners only between start()/stop(). |
| `MediaListener` | `MediaListener.kt` | Empty NotificationListenerService; exists only so getActiveSessions() is allowed. |
| `MainActivity` | `MainActivity.kt` | Setup/status screen, programmatic framework Views. |

targetSdk = 28 on purpose: WifiManager.setWifiEnabled and BluetoothAdapter.enable/disable still work
for apps targeting <= 28 / < 33, and the hidden insets API stays accessible. minSdk 30, compileSdk 36.

## Interface contract (A writes QuickToggles/MediaBridge, B consumes them)

```kotlin
package com.dynedge

enum class ToggleId { WIFI, BLUETOOTH, TORCH, DND, ROTATE, VIBRATE }
// Grid order: row 1 = WIFI BLUETOOTH TORCH, row 2 = DND ROTATE VIBRATE

class QuickToggles(ctx: Context, onChange: () -> Unit) {
    fun start()                         // read all states, register listeners (panel opening)
    fun stop()                          // unregister everything (panel closed)
    fun isOn(id: ToggleId): Boolean
    fun isAvailable(id: ToggleId): Boolean   // e.g. no flash unit -> false
    fun iconRes(id: ToggleId): Int      // drawable for the current state (R.drawable.*)
    fun toggle(id: ToggleId): Boolean   // perform; returns true if it launched an Activity (caller collapses)
    fun openSettings(id: ToggleId): Boolean  // long-press; true if an Activity was launched
}

class MediaBridge(ctx: Context, artPx: Int, onChange: () -> Unit) {
    fun start(); fun stop()
    val hasSession: Boolean
    val title: String?
    val artist: String?
    val art: Bitmap?            // center-cropped to artPx x artPx, or null
    val isPlaying: Boolean
    val durationMs: Long        // 0 if unknown
    fun positionMs(): Long      // extrapolated while playing
    fun playPause(); fun next(); fun prev()
    fun openPlayer(): Boolean   // launch session activity / app; true if launched
}
```
All callbacks (`onChange`) are delivered on the main thread.

Drawables (white `#FFFFFF` stroke, B tints with `mutate().setTint()`):
`ic_wifi ic_wifi_off ic_bluetooth ic_bluetooth_off ic_flashlight ic_flashlight_off ic_moon ic_rotate ic_rotate_lock ic_vibrate ic_bell ic_play ic_pause ic_skip_back ic_skip_forward ic_music`
(play/pause/skip are filled shapes, white fill.)

## Geometry (dp unless noted)

Shape bbox anchored to the window's bottom-right (= screen bottom-right, inset 0).
Path, with W/H the current size and k the curve factor:
`moveTo(0,H)` -> `cubicTo(0, H-kH, W-kW, 0, W, 0)` -> `lineTo(W, H-tip)` -> `arcTo` tip corner -> `lineTo(0,H)` -> close.
- Idle: W = H = 48, k = 0.5523 (exact quarter circle, like the HTML `border-radius:100% 0 tip 0`).
- tip = `display.getRoundedCorner(POSITION_BOTTOM_RIGHT).radius` px (78 px here); 0 if null. The black tip
  then sits exactly on the physical screen corner, so the shape reads as a notch cut out of the display.
- Expanded: W = min(screenWidthDp - 12, 400), H = min(0.86 W, 340), k = 0.70 (fuller sweep, more room).
- k is derived: `k = lerp(0.5523, 0.70, p)` with p = clamped progress of the smaller axis.
- Theme (v1.0.1, user request): radial gradient from the corner #2E1B1A -> #1A1718 -> #0B0B0C -> #000, a 1 dp rim
  along the curve only (white 10% at rest -> red #FF4B3E 75% when touched), and a red radial glow whose strength
  = max(press, drag 0.85, progress*0.4). Pressed toggles/buttons get a red ring; media progress fill is red.
  Shaders are built once and positioned with setLocalMatrix (no per-frame allocation).

Content layout (computed once per expanded geometry, using `curveXAt(y)` / `curveYAt(x)` sampled from the cubic):
- Toggle grid 3x2, circles 46, gap 12, right pad 20, top pad = max(20, curveYAt(gridLeft) + 12).
- Media block bottom-left: bottom pad 22, height ~84, left pad = max(20, curveXAt(blockTop) + 14),
  right edge W - 24. Row 1: art 52 (radius 12) | title 15sp white / artist 12sp #8E8E93 (ellipsized) |
  prev 36, play/pause 44 white circle with black icon, next 36. Row 2: 3 dp progress track #2C2C2E fill white,
  elapsed / total 10sp #8E8E93 under it.
- No session: art placeholder #1C1C1E with ic_music, title "Not playing", artist "Tap play to resume".
- Toggle off: fill #1C1C1E, white icon. On: fill #FFFFFF, black icon. Unavailable: alpha 0.35.

## Motion
- Expand: width spring stiffness 420 / damping 0.74, height spring 300 / 0.78 (width leads, height lags -> it
  stretches left, then melts up and settles). Content alpha = smoothstep(0.55, 0.95, p); content offset
  (1-a)*16 toward the corner, scale 0.96 + 0.04a about the bottom-right; content clipped by the shape path.
- Collapse: content fades in ~90 ms; width spring 520 / 0.92, height 600 / 0.92.
- Press on idle: target 0.92 x idle; release restores. Haptic tick on expand.
- Drag: idle drag up-left stretches live (rawX/rawY, rubber band past expanded size x0.3); expanded drag
  toward the corner shrinks live. Release: velocity-aware decide, velocity handed to the springs.
- Frames via Choreographer only while any spring is unsettled. Progress text/bar refresh once a second
  only while expanded and playing.

## Window / touch
- LayoutParams: TYPE_ACCESSIBILITY_OVERLAY, TRANSLUCENT, flags NOT_FOCUSABLE | NOT_TOUCH_MODAL |
  LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS | WATCH_OUTSIDE_TOUCH | HARDWARE_ACCELERATED, gravity BOTTOM|RIGHT,
  fitInsetsTypes = 0, layoutInDisplayCutoutMode = ALWAYS.
- Window is idle-sized (idle + 8) when settled idle, and expanded-sized (+ overshoot pad) otherwise.
  Resize happens at gesture/animation start, never per frame. Drawing is anchored bottom-right so a resize
  never moves pixels.
- Touchable region = shape region (expanding: target region immediately; collapsing: keep the large region
  until settled). Taps in the curve's cut-out area pass to the app below.
- Collapse on ACTION_OUTSIDE, screen off, IME shown, after launching an Activity.
- Hidden (alpha 0, empty region) while IME window is visible or keyguard is locked.

## Runbook

Build (JDK 21, Android SDK at %ANDROID_HOME%):
```
cd "D:\VS\PROJECTS\FREE WORK\dynamic-edge"
.\gradlew.bat assembleRelease
```
Install + grant (or run `tools\install.ps1`):
```
adb install -r app\build\outputs\apk\release\app-release.apk
adb shell settings put secure enabled_accessibility_services com.dynedge/com.dynedge.EdgeService
adb shell settings put secure accessibility_enabled 1
adb shell cmd notification allow_listener com.dynedge/com.dynedge.MediaListener
adb shell cmd notification allow_dnd com.dynedge
adb shell appops set com.dynedge WRITE_SETTINGS allow
```
Note: `enabled_accessibility_services` is a colon-separated list; append instead of overwrite if other
services are enabled (install.ps1 does this).

Restart the service after reinstall: toggling the setting off/on, or
`adb shell settings put secure enabled_accessibility_services ""` then the put above.

## Status (2026-11-10, v1.0.0)
Built (81 KB release APK, debug-signed) and installed on the vivo I2202. Verified on device:
- Overlay window is TYPE 2032 TRUSTED_OVERLAY; touchable region reported by `dumpsys input` is exactly the
  bean shape (reflection hook works on Android 14 / targetSdk 28). Idle window 154x154 px.
- Tap expands, outside tap collapses, drag-to-close and small-drag snap-back work, Torch toggle on/off works.
- Idle: 0% CPU, ~16 MB PSS. Expand/collapse: UI thread < 0.5 ms per frame, ~3% janky frames (one-time window
  resize + vivo panel dropping 120 -> 60 Hz after motion).

- White wallpaper: `adb shell am start -n com.dynedge/.MainActivity --ez white_wallpaper true` (home screen only).

## Gotchas
- Diagonal drag starting in the bottom-right corner can trigger the Google Assistant corner-swipe system gesture
  (it steals the pointer before the overlay sees it). Tap-to-open is unaffected. Turn off "swipe from corner
  for assistant" in the system gesture settings if drag-to-open matters.
- Swipes starting inside the bottom gesture strip (y >= 2351 px here) go to the Home gesture.
- After `adb install -r`, wait ~3 s for the accessibility service to rebind before testing.
- Listener registration (toggles/media) happens on finger-down, so it is paid before the expand animation.
- Not yet tested on device: Wi-Fi/Bluetooth/DND/rotate/vibrate toggles (they change real phone state), media
  with an actual playing session, IME hide, lock-screen hide.

## v1.1 redesign (2026-11-10) - supersedes the Geometry/Content layout sections above
User feedback: too big; wanted compact sections you slide between, everything quarter-circle shaped.
- Open shape: true quarter circle, R = Prefs.openDp (default 216, 170..300), idle = Prefs.idleDp (48, 32..80).
  Both set live from MainActivity sliders; EdgeService listens to SharedPreferences "edge" -> applySizes().
- Sections (rotate about the corner by (i - pagePos) * 70 deg, horizontal swipe inside the shape):
  0 QUICK: inner ring ROTATE / ASSIST (AssistControl.launch, ic_mic) / VIBRATE, middle ring WIFI BT TORCH DND,
    outer ring = brightness arc slider (Brightness.kt, Settings.System 0..255 gamma 2.2).
  1 MEDIA: circular art (tap = play/pause, long-press = open player), prev/next on the ring, title/artist
    drawn on arcs, progress = red arc along the outer edge.
  2 APPS: up to 6 AppShortcuts (Prefs apps, picker = AppPickerActivity) on two rings, white disc behind icons.
- Close X sits on the idle bean's centroid; tap it (or outside, or swipe down) to close.
- Page dots: small arc just outside the X (r = max(0.26 R, |X| + 26 dp)).
- Opens on MEDIA if something is playing, else the last used section.
- Google Assistant corner swipe (vivo key navigation_google_assist, AOSP assist_touch_gesture_enabled) is turned
  off by AssistControl.disableCornerGesture() on every service start; needs
  `adb shell pm grant com.dynedge android.permission.WRITE_SECURE_SETTINGS` (install.ps1 does it).
- Rendering: each section is a RenderNode with a compositing layer (rasterized once, re-recorded only when
  dirty); rotation/alpha/scale are layer properties, no clipPath (content scales with the shape). Layers are
  discarded when the widget settles closed. Unchanged shape paths are not rewound (keeps HWUI path cache).
  Profile before/after: RenderThread issue 6-22 ms -> avg 6.5 ms; janky 11.5% -> 3.2% over open/page/close.
- Overlay-only PSS ~16 MB; the settings Activity adds ~50-100 MB while it is alive.

## Status 2026-11-10 17:45
Installed: v1.3 (+ pageFade fix). v1.4 in progress, see HANDOFF.md (project root) - platform files mostly done,
EdgeView/EdgeService v1.4 work not started.
# Dynamic Edge v1.4 spec (lead)

Read first: .agents/skills/dynamic-edge/SKILL.md, review/SPEC_v1_3.md (contracts still valid), and the design reports
review/opencode_looks.md + review/codex_looks.md (when present; section "LOOKS" below references them).
Rules unchanged: Kotlin, framework only, minSdk 30, targetSdk 28, no emojis, no per-frame allocations, section
RenderNode layers re-recorded only when dirty, near-zero idle work, minimal text.

Verified on device in v1.3: arc drag fixed (continuous), paging OK, idle bars react to music.

## User requests (verbatim intent)
1. "I wanted the idle island to react to music as well" -> closed bean must visibly react: bars already move; make the
   whole bean breathe with the bass (scale up to +8%, spring-smoothed), the rim/glow brighten, and the bean's fill
   (gradient or pattern) pulse - see 4.
2. "The clipboard was supposed to be in a quick tile if the user wants" -> new tile id "clipboard" (icon ic_clipboard,
   label "Clipboard"): tap = jump to the CLIPBOARD window if enabled, else open ClipboardActivity; long-press = open
   ClipboardActivity.
3. "Make sure the user can rearrange each window, enable or disable, and add more variety of windows" -> windows are
   data-driven (WindowsConfig below). New windows: VOLUME, TIMER, NOTIFICATIONS, DEVICE.
4. "Make the gradient react to the music too instead of that stupid play/pause button [halo]" -> remove the breathing
   halo ring around the art circle. Instead the shape FILL reacts: with bass energy b (0..1, smoothed attack 60 ms /
   release 280 ms): gradient core radius grows (x1.0 -> x1.35), core brightness rises and shifts toward the accent
   (mix up to 35%), mids add a slow rotation/drift of the gradient centre (max 6 dp), highs add a subtle rim sparkle
   (rim alpha +40%). Same for pattern fills (pattern brightness + accent tint follows b). Works open and closed.
   Keep the art circle with a small play/pause glyph (no big dark disc behind it; 24 dp translucent glyph only).
5. "Round the top and the bottom-left side of the corners too, as I want" -> edge FLARES: where the shape meets the
   screen edges (top end on the right edge, left end on the bottom edge) add a concave fillet ("ear") of radius
   Prefs.flareDp (0..28 dp, default 10) so the shape flows into the screen edge instead of meeting it at 90 deg.
   Apply to the idle bean (scaled with idle size, max 40% of idle) and the open shape; include in the touchable region.

## Contracts (PLATFORM implements; ENGINE consumes)
```kotlin
// WindowsConfig.kt
object WindowsConfig {
    const val KEY = "windows"   // JSON array of {"id": String, "on": Boolean} in display order
    val ALL = listOf("quick", "media", "clipboard", "apps", "volume", "timer", "notifications", "device")
    val DEFAULT_ON = setOf("quick", "media", "clipboard", "apps")
    data class Entry(val id: String, val on: Boolean)
    fun load(ctx: Context): List<Entry>          // always contains every id in ALL exactly once (missing appended, off)
    fun enabled(ctx: Context): List<String>      // ids with on == true, in order; never empty (falls back to ["quick"])
    fun save(ctx: Context, list: List<Entry>)
    fun label(id: String): String; fun icon(id: String): Int
}
// Prefs additions: KEY_FLARE (Float dp, 0..28, default 10) + flareDp(ctx); KEY_FILL (String, fill style id, default
// "gradient") + fill(ctx); idle style list replaced per LOOKS section (keep KEY_IDLE_STYLE).

// Arcs: add "notification_volume" (STREAM_NOTIFICATION) and "call_volume" (STREAM_VOICE_CALL) to ALL.

// NotifFeed.kt (fed by MediaListener)
object NotifFeed {
    data class Item(val key: String, val pkg: String, val title: String, val text: String, val time: Long,
                    val contentIntent: android.app.PendingIntent?, val clearable: Boolean)
    fun recent(): List<Item>                       // newest first, max 8, excludes ongoing/media/our package/group summaries
    fun appIcon(ctx: Context, pkg: String, px: Int): android.graphics.Bitmap?   // cached, small LRU (8)
    fun dismiss(key: String)                       // asks MediaListener.cancelNotification(key)
    var onChange: (() -> Unit)?                    // main thread
}

// TimerControl.kt
object TimerControl {
    fun start(ctx: Context, seconds: Int): Boolean // AlarmClock.ACTION_SET_TIMER, EXTRA_LENGTH, EXTRA_SKIP_UI=true, NEW_TASK
    fun openTimers(ctx: Context): Boolean          // AlarmClock.ACTION_SHOW_TIMERS
    // Manifest: com.android.alarm.permission.SET_ALARM
}

// DeviceStats.kt
class DeviceStats(ctx: Context, onChange: () -> Unit) {
    fun start(); fun stop()                        // battery via sticky intent + ACTION_BATTERY_CHANGED receiver ONLY while started
    val batteryPct: Int; val charging: Boolean; val tempC: Float
    val ramUsedFrac: Float; val ramTotalGb: Float  // ActivityManager.MemoryInfo, refreshed every 2 s while started
    val storageUsedFrac: Float; val storageTotalGb: Float  // StatFs(dataDir), on start
}
```

## ENGINE (EdgeView/EdgeService)
- Window list from WindowsConfig.enabled (reload on KEY change; keep current window if still enabled). Page bars =
  enabled count (2..8; shrink bar angle to fit 28..62 deg). Remember last window by id.
- New windows, all radial, same design language (see LOOKS design system):
  VOLUME: up to 4 concentric arcs (media, ring, alarm, notification) each with its badge (tap = mute toggle) and the
  same robust drag capture as QUICK arcs.
  TIMER: a dial arc at 0.80R: drag sets 1..60 min (snap 1 min, haptic tick per 5 min), the chosen value shown as large
  text near 45 deg / 0.52R ("12:00"), preset chips on the inner ring (1, 5, 10, 25 min), a start button (accent) at
  0.36R / 45 deg -> TimerControl.start, then collapse. Long-press start = openTimers.
  NOTIFICATIONS: up to 4 recent notifications as arc rows (like CLIPBOARD rows): small app icon at the row start,
  "Title - text" along the arc; tap = contentIntent.send() + collapse; long-press = dismiss. Empty: bell icon.
  DEVICE: concentric arcs (display-only): battery (accent, bolt badge when charging), RAM, storage; a big battery % at
  0.45R / 45 deg; small labels at arc starts (10sp). DeviceStats start/stop with the window's visibility (only while
  that window is visible, not whenever the panel is open).
- Tile "clipboard" handling (see request 2).
- Requests 1, 4, 5 above.
- LOOKS: implement the idle designs and fill styles chosen in the LOOKS section.

## PLATFORM
- WindowsConfig, NotifFeed (+ MediaListener feeding it, cancelNotification), TimerControl, DeviceStats, Arcs
  additions, Tiles "clipboard" entry, Prefs additions, manifest permission.
- Settings (MainActivity): new "WINDOWS" section right after SIZE: one flat row per window (icon + label), a
  FlatToggle on the right, and drag-to-reorder by long-pressing a row (or up/down chevrons if drag is too complex);
  "Flare" slider (0..28 dp, live preview "idle" while dragging) in LOOKS; "Fill" picker (row of mini previews like the
  idle-style picker) in LOOKS; idle-style picker updated to the new idle designs; "Beat reaction" switch (KEY_BEAT).
  Minimal text.

6. "Give me more custom visualizer options, different styles in our app style" -> Prefs.KEY_VIZ_STYLE with 6 styles
   (from the design reports), used on the idle bean and on the open panel's rim; picker in settings with mini previews.
7. "Why can I see the next windows faded in the current window" -> fixed by the lead already (pageFade: exactly 0 once
   |d| >= 0.85, smoothstep). Keep it.

## LOOKS (from design reports; lead fills in after reading them)
(see review/LOOKS_DECISION.md)

## User requests added 2026-11-10 evening (ENGINE part B must do these)
8. Drag-to-open clipping: when the user drags the bean upward while opening, the shape sometimes gets cut off by a
   hard border (the overlay window bounds). Clamp the open/drag size so the shape (incl. glow, flares, overshoot of
   the bounce spring) never exceeds the window bounds; or size the window to the max reachable extent. No visible
   cut edge ever.
9. Close X: subtle, no circle/disc behind it. Just the X glyph (Lucide x, ~2 dp stroke, ~60% white), same hit area
   (>= 44 dp).
10. Page indicator: the window-changing dots/bars must sit on an arc concentric with the X glyph centre (same
    centre point), evenly spaced, so they visibly circle the X. Currently they bend around a different centre.
11. Idle bean beat bounce: when any media plays, the idle bean scales/bounces with the beat (request 1), and the
    intensity is user-configurable: Prefs KEY_BEAT_AMOUNT (Int 0..100, default 50; 0 = off, 100 = max scale +12%).
    Slider "Bounce" in LOOKS next to the Beat reaction switch (hidden/disabled when the switch is off).
12. Overlay opacity: Prefs KEY_OPACITY (Int 30..100 %, default 100) applied to the entire overlay (idle and open;
    apply as one view/layer alpha, not per-paint), slider "Opacity" in LOOKS with live preview.
# LOOKS decision (lead) - source: review/opencode_looks.md (exact geometry/numbers there)

Idle designs (Prefs.KEY_IDLE_STYLE, replace the old list entirely; default "glyph"):
  "none" (plain bean), "glyph" (1 Glyph Notch), "pill" (2 Island Pill), "orbit" (3 Orbit Dot), "slits" (4 Twin Slits),
  "halo" (5 Halo Core), "meter" (6 Meter Arc, battery), "grid" (7 TE Grid), "eclipse" (8 Eclipse).
  Old values ("plain","dot","arc","battery","clock") map to: plain->none, dot->halo, arc->slits, battery->meter,
  clock->glyph.

Fill styles (Prefs.KEY_FILL; default "gradient"):
  "gradient" (current corner gradient), "solid" (pure black), "grooves" (3.1), "dots" (3.2), "sunburst" (3.3),
  "carbon" (3.5). Patterns are drawn over the base dark fill (gradient strength still tints the base); all cached
  (BitmapShader tile built once, or Path built once per size and drawn via the canvas-scale trick); patterns also
  pulse with the beat per SPEC_v1_4 request 4.

Visualizer styles (Prefs.KEY_VIZ_STYLE; default "spectrum"):
  "bars" (current 4 bars), "spectrum" (4b.1 Rim Spectrum, idle + open rim), "rays" (4b.2), "ripple" (4b.3, open edge;
  on idle use 2 rings), "meter" (4b.4 Dot-Matrix), "wave" (4b.5 Rim Waveform), "liquid" (4b.6 Liquid Bulge).
  Each works on the idle bean (scaled to idle size) and, when the panel is open and music plays, on the open rim
  (styles that are idle-only fall back to "spectrum" on the open rim).

Beat reaction: section 4 of the report (attack 0.40 / release 0.12 per 30 Hz tick; idle scale <= 1.04 (user asked
for a visible reaction: allow up to 1.06), alpha deltas <= 0.25) + SPEC_v1_4 request 4 fill reaction.

Design system: section 5 of the report (spacing 4/8/12/16/24, touch >= 44 dp, accent only for active/value/focus,
arc typography 14/12/10 sp, max 80% arc length, upright text).

Settings pickers: Idle design, Fill, Visualizer each as a horizontal row of 40-44 dp mini bean previews drawn with the
same code (no labels; selected = accent ring).
app/src/main/java/com/dynedge\AppShortcuts.kt
app/src/main/java/com/dynedge\AssistControl.kt
app/src/main/java/com/dynedge\AppPickerActivity.kt
app/src/main/java/com/dynedge\Arcs.kt
app/src/main/java/com/dynedge\ArcSet.kt
app/src/main/java/com/dynedge\EdgeService.kt
app/src/main/java/com/dynedge\DeviceStats.kt
app/src/main/java/com/dynedge\ClipStore.kt
app/src/main/java/com/dynedge\ClipPaste.kt
app/src/main/java/com/dynedge\ClipboardActivity.kt
app/src/main/java/com/dynedge\Brightness.kt
app/src/main/java/com/dynedge\IdleMediaWatch.kt
app/src/main/java/com/dynedge\FlatSlider.kt
app/src/main/java/com/dynedge\EdgeView.kt
app/src/main/java/com/dynedge\LiveBus.kt
app/src/main/java/com/dynedge\IdleVisualizer.kt
app/src/main/java/com/dynedge\MainActivity.kt
app/src/main/java/com/dynedge\MediaBridge.kt
app/src/main/java/com/dynedge\MediaListener.kt
app/src/main/java/com/dynedge\NotifFeed.kt
app/src/main/java/com/dynedge\WinVolume.kt
app/src/main/java/com/dynedge\WinTimer.kt
app/src/main/java/com/dynedge\WinQuick.kt
app/src/main/java/com/dynedge\WinNotifications.kt
app/src/main/java/com/dynedge\WinMedia.kt
app/src/main/java/com/dynedge\WindowsConfig.kt
app/src/main/java/com/dynedge\WinDevice.kt
app/src/main/java/com/dynedge\WinClipboard.kt
app/src/main/java/com/dynedge\WinApps.kt
app/src/main/java/com/dynedge\Win.kt
app/src/main/java/com/dynedge\TouchRegion.kt
app/src/main/java/com/dynedge\TimerControl.kt
app/src/main/java/com/dynedge\Tiles.kt
app/src/main/java/com/dynedge\Spring.kt
app/src/main/java/com/dynedge\QuickToggles.kt
app/src/main/java/com/dynedge\QuickEditorActivity.kt
app/src/main/java/com/dynedge\QuickConfig.kt
app/src/main/java/com/dynedge\Prefs.kt

exec
"C:\\WINDOWS\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" -Command 'Get-Content review/LOOKS_DECISION.md; $files = Get-ChildItem app/src/main/java/com/dynedge/*.kt | Sort-Object Name; foreach ($f in $files) { "===== $($f.Name) ====="; $n=0; Get-Content $f.FullName | ForEach-Object { $n++; '"'{0,4}: {1}' -f "'$n, $_ } }' in D:\VS\PROJECTS\FREE WORK\dynamic-edge
 succeeded in 2454ms:
# LOOKS decision (lead) - source: review/opencode_looks.md (exact geometry/numbers there)

Idle designs (Prefs.KEY_IDLE_STYLE, replace the old list entirely; default "glyph"):
  "none" (plain bean), "glyph" (1 Glyph Notch), "pill" (2 Island Pill), "orbit" (3 Orbit Dot), "slits" (4 Twin Slits),
  "halo" (5 Halo Core), "meter" (6 Meter Arc, battery), "grid" (7 TE Grid), "eclipse" (8 Eclipse).
  Old values ("plain","dot","arc","battery","clock") map to: plain->none, dot->halo, arc->slits, battery->meter,
  clock->glyph.

Fill styles (Prefs.KEY_FILL; default "gradient"):
  "gradient" (current corner gradient), "solid" (pure black), "grooves" (3.1), "dots" (3.2), "sunburst" (3.3),
  "carbon" (3.5). Patterns are drawn over the base dark fill (gradient strength still tints the base); all cached
  (BitmapShader tile built once, or Path built once per size and drawn via the canvas-scale trick); patterns also
  pulse with the beat per SPEC_v1_4 request 4.

Visualizer styles (Prefs.KEY_VIZ_STYLE; default "spectrum"):
  "bars" (current 4 bars), "spectrum" (4b.1 Rim Spectrum, idle + open rim), "rays" (4b.2), "ripple" (4b.3, open edge;
  on idle use 2 rings), "meter" (4b.4 Dot-Matrix), "wave" (4b.5 Rim Waveform), "liquid" (4b.6 Liquid Bulge).
  Each works on the idle bean (scaled to idle size) and, when the panel is open and music plays, on the open rim
  (styles that are idle-only fall back to "spectrum" on the open rim).

Beat reaction: section 4 of the report (attack 0.40 / release 0.12 per 30 Hz tick; idle scale <= 1.04 (user asked
for a visible reaction: allow up to 1.06), alpha deltas <= 0.25) + SPEC_v1_4 request 4 fill reaction.

Design system: section 5 of the report (spacing 4/8/12/16/24, touch >= 44 dp, accent only for active/value/focus,
arc typography 14/12/10 sp, max 80% arc length, upright text).

Settings pickers: Idle design, Fill, Visualizer each as a horizontal row of 40-44 dp mini bean previews drawn with the
same code (no labels; selected = accent ring).
===== AppPickerActivity.kt =====
   1: package com.dynedge
   2: 
   3: import android.app.Activity
   4: import android.content.ComponentName
   5: import android.content.Intent
   6: import android.content.pm.ResolveInfo
   7: import android.graphics.Bitmap
   8: import android.graphics.Canvas
   9: import android.graphics.Color
  10: import android.graphics.Typeface
  11: import android.os.Bundle
  12: import android.util.LruCache
  13: import android.util.TypedValue
  14: import android.view.Gravity
  15: import android.view.View
  16: import android.view.ViewGroup
  17: import android.view.WindowInsets
  18: import android.widget.BaseAdapter
  19: import android.widget.ImageView
  20: import android.widget.LinearLayout
  21: import android.widget.ListView
  22: import android.widget.TextView
  23: 
  24: /** Full-screen list of launchable apps. Picking one appends it to the shortcut list. */
  25: class AppPickerActivity : Activity() {
  26: 
  27:     private class Item(val label: String, val info: ResolveInfo)
  28: 
  29:     private var items: List<Item> = emptyList()
  30:     private val cache = LruCache<Int, Bitmap>(64)
  31:     private var iconPx = 0
  32: 
  33:     private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
  34: 
  35:     override fun onCreate(savedInstanceState: Bundle?) {
  36:         super.onCreate(savedInstanceState)
  37:         window.statusBarColor = Color.BLACK
  38:         window.navigationBarColor = Color.BLACK
  39:         window.setDecorFitsSystemWindows(false)
  40:         iconPx = dp(40)
  41: 
  42:         val pm = packageManager
  43:         val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  44:         items = pm.queryIntentActivities(q, 0)
  45:             .filter { it.activityInfo.packageName != packageName }
  46:             .map { Item(it.loadLabel(pm).toString(), it) }
  47:             .sortedBy { it.label.lowercase() }
  48: 
  49:         val list = ListView(this).apply {
  50:             setBackgroundColor(Color.BLACK)
  51:             divider = android.graphics.drawable.ColorDrawable(Color.parseColor("#1C1C1E"))
  52:             dividerHeight = 1
  53:             selector = android.graphics.drawable.ColorDrawable(0x33FFFFFF)
  54:             adapter = Adapter()
  55:             setOnItemClickListener { _, _, pos, _ -> pick(items[pos]) }
  56:             clipToPadding = false
  57:             setOnApplyWindowInsetsListener { v, insets ->
  58:                 val bars = insets.getInsets(WindowInsets.Type.systemBars())
  59:                 v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
  60:                 WindowInsets.CONSUMED
  61:             }
  62:         }
  63:         setContentView(list)
  64:     }
  65: 
  66:     private fun pick(item: Item) {
  67:         val ai = item.info.activityInfo
  68:         val cn = ComponentName(ai.packageName, ai.name)
  69:         val cur = Prefs.apps(this)
  70:         if (cn !in cur && cur.size < Prefs.MAX_APPS) Prefs.setApps(this, cur + cn)
  71:         finish()
  72:     }
  73: 
  74:     private inner class Adapter : BaseAdapter() {
  75:         override fun getCount(): Int = items.size
  76:         override fun getItem(position: Int): Any = items[position]
  77:         override fun getItemId(position: Int): Long = position.toLong()
  78: 
  79:         override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
  80:             val row: LinearLayout
  81:             val icon: ImageView
  82:             val name: TextView
  83:             if (convertView == null) {
  84:                 row = LinearLayout(this@AppPickerActivity).apply {
  85:                     orientation = LinearLayout.HORIZONTAL
  86:                     gravity = Gravity.CENTER_VERTICAL
  87:                     setPadding(dp(20), dp(10), dp(20), dp(10))
  88:                 }
  89:                 icon = ImageView(this@AppPickerActivity)
  90:                 name = TextView(this@AppPickerActivity).apply {
  91:                     setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
  92:                     setTextColor(Color.WHITE)
  93:                     typeface = Typeface.DEFAULT
  94:                     setPadding(dp(16), 0, 0, 0)
  95:                     maxLines = 1
  96:                 }
  97:                 row.addView(icon, LinearLayout.LayoutParams(dp(32), dp(32)))
  98:                 row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
  99:                 row.tag = arrayOf(icon, name)
 100:             } else {
 101:                 row = convertView as LinearLayout
 102:                 @Suppress("UNCHECKED_CAST")
 103:                 val t = row.tag as Array<View>
 104:                 icon = t[0] as ImageView
 105:                 name = t[1] as TextView
 106:             }
 107:             val item = items[position]
 108:             name.text = item.label
 109:             var bmp = cache.get(position)
 110:             if (bmp == null) {
 111:                 bmp = try {
 112:                     val d = item.info.loadIcon(packageManager)
 113:                     val b = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
 114:                     d.setBounds(0, 0, iconPx, iconPx)
 115:                     d.draw(Canvas(b))
 116:                     b
 117:                 } catch (e: Exception) {
 118:                     null
 119:                 }
 120:                 if (bmp != null) cache.put(position, bmp)
 121:             }
 122:             icon.setImageBitmap(bmp)
 123:             return row
 124:         }
 125:     }
 126: }
===== AppShortcuts.kt =====
   1: package com.dynedge
   2: 
   3: import android.content.ComponentName
   4: import android.content.Context
   5: import android.content.Intent
   6: import android.graphics.Bitmap
   7: import android.graphics.Canvas
   8: 
   9: /** Loads the user's shortcut apps as ready-to-draw bitmaps. Call load() on open, release() on close. */
  10: class AppShortcuts(private val ctx: Context, private val iconPx: Int) {
  11: 
  12:     private var comps: List<ComponentName> = emptyList()
  13:     private var icons: List<Bitmap> = emptyList()
  14:     private var labels: List<String> = emptyList()
  15: 
  16:     val count: Int get() = comps.size
  17: 
  18:     fun load() {
  19:         val pm = ctx.packageManager
  20:         val c = ArrayList<ComponentName>()
  21:         val b = ArrayList<Bitmap>()
  22:         val l = ArrayList<String>()
  23:         for (cn in Prefs.apps(ctx)) {
  24:             if (c.size >= Prefs.MAX_APPS) break
  25:             try {
  26:                 val info = pm.getActivityInfo(cn, 0)
  27:                 val d = info.loadIcon(pm)
  28:                 val bmp = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
  29:                 d.setBounds(0, 0, iconPx, iconPx)
  30:                 d.draw(Canvas(bmp))
  31:                 c.add(cn)
  32:                 b.add(bmp)
  33:                 l.add(info.loadLabel(pm).toString())
  34:             } catch (e: Exception) {
  35:                 // app removed or icon failed: skip
  36:             }
  37:         }
  38:         comps = c
  39:         icons = b
  40:         labels = l
  41:     }
  42: 
  43:     fun release() {
  44:         comps = emptyList()
  45:         icons = emptyList()
  46:         labels = emptyList()
  47:     }
  48: 
  49:     fun icon(i: Int): Bitmap? = icons.getOrNull(i)
  50: 
  51:     fun label(i: Int): String = labels.getOrNull(i) ?: ""
  52: 
  53:     fun launch(i: Int): Boolean {
  54:         val c = comps.getOrNull(i) ?: return false
  55:         return try {
  56:             val intent = Intent(Intent.ACTION_MAIN)
  57:                 .addCategory(Intent.CATEGORY_LAUNCHER)
  58:                 .setComponent(c)
  59:                 .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
  60:             ctx.startActivity(intent)
  61:             true
  62:         } catch (e: Exception) {
  63:             false
  64:         }
  65:     }
  66: }
===== Arcs.kt =====
   1: package com.dynedge
   2: 
   3: import android.content.BroadcastReceiver
   4: import android.content.Context
   5: import android.content.Intent
   6: import android.content.IntentFilter
   7: import android.database.ContentObserver
   8: import android.media.AudioManager
   9: import android.os.Build
  10: import android.os.Handler
  11: import android.os.Looper
  12: import android.os.SystemClock
  13: import android.provider.Settings
  14: 
  15: /** Outer-ring sliders: brightness and the three volume streams. */
  16: class Arcs(ctx: Context, private val onChange: () -> Unit) {
  17: 
  18:     private val app: Context = ctx.applicationContext ?: ctx
  19:     private val main = Handler(Looper.getMainLooper())
  20:     private val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
  21:     private val bright = Brightness(app) { onChange() }
  22: 
  23:     private var started = false
  24:     private val heldLevel = HashMap<String, Float>()
  25:     private val heldUntil = HashMap<String, Long>()
  26:     private val savedVolume = HashMap<String, Int>()
  27: 
  28:     private val observer = object : ContentObserver(main) {
  29:         override fun onChange(selfChange: Boolean) {
  30:             onChange()
  31:         }
  32:     }
  33: 
  34:     private val receiver = object : BroadcastReceiver() {
  35:         override fun onReceive(context: Context?, intent: Intent?) {
  36:             onChange()
  37:         }
  38:     }
  39: 
  40:     fun start() {
  41:         if (started) return
  42:         started = true
  43:         bright.start()
  44:         try {
  45:             app.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
  46:         } catch (e: Exception) {
  47:         }
  48:         try {
  49:             val f = IntentFilter(VOLUME_CHANGED)
  50:             if (Build.VERSION.SDK_INT >= 33) {
  51:                 app.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
  52:             } else {
  53:                 @Suppress("UnspecifiedRegisterReceiverFlag")
  54:                 app.registerReceiver(receiver, f)
  55:             }
  56:         } catch (e: Exception) {
  57:         }
  58:     }
  59: 
  60:     fun stop() {
  61:         if (!started) return
  62:         started = false
  63:         bright.stop()
  64:         try {
  65:             app.contentResolver.unregisterContentObserver(observer)
  66:         } catch (e: Exception) {
  67:         }
  68:         try {
  69:             app.unregisterReceiver(receiver)
  70:         } catch (e: Exception) {
  71:         }
  72:     }
  73: 
  74:     fun level(id: String): Float {
  75:         val until = heldUntil[id] ?: 0L
  76:         if (SystemClock.uptimeMillis() < until) {
  77:             heldLevel[id]?.let { return it }
  78:         }
  79:         return read(id)
  80:     }
  81: 
  82:     fun setLevel(id: String, f: Float) {
  83:         val v = f.coerceIn(0f, 1f)
  84:         hold(id, v)
  85:         write(id, v)
  86:     }
  87: 
  88:     fun commit(id: String) {
  89:         val v = heldLevel[id] ?: return
  90:         write(id, v)
  91:         heldUntil[id] = SystemClock.uptimeMillis() + HOLD_MS
  92:     }
  93: 
  94:     fun iconRes(id: String): Int = when (id) {
  95:         "brightness" -> R.drawable.ic_sun
  96:         "media_volume" -> if (iconActive(id)) R.drawable.ic_volume_off else R.drawable.ic_volume
  97:         "ring_volume" -> R.drawable.ic_ring
  98:         "alarm_volume" -> R.drawable.ic_alarm
  99:         "notification_volume" -> R.drawable.ic_bell
 100:         "call_volume" -> R.drawable.ic_phone
 101:         else -> R.drawable.ic_volume
 102:     }
 103: 
 104:     fun iconActive(id: String): Boolean = when (id) {
 105:         "brightness" -> bright.isAuto
 106:         else -> {
 107:             val s = stream(id)
 108:             s >= 0 && (audio?.getStreamVolume(s) ?: 1) == 0
 109:         }
 110:     }
 111: 
 112:     fun tapIcon(id: String) {
 113:         if (id == "brightness") {
 114:             bright.setAuto(!bright.isAuto)
 115:             heldUntil.remove(id)
 116:             onChange()
 117:             return
 118:         }
 119:         val am = audio ?: return
 120:         val s = stream(id)
 121:         if (s < 0) return
 122:         val max = am.getStreamMaxVolume(s).coerceAtLeast(1)
 123:         val cur = am.getStreamVolume(s)
 124:         val target = if (cur == 0) (savedVolume[id] ?: (max / 2).coerceAtLeast(1)) else {
 125:             savedVolume[id] = cur
 126:             0
 127:         }
 128:         try {
 129:             am.setStreamVolume(s, target, 0)
 130:         } catch (e: SecurityException) {
 131:         }
 132:         heldUntil.remove(id)
 133:         onChange()
 134:     }
 135: 
 136:     // ---------------------------------------------------------------- internals
 137: 
 138:     private fun hold(id: String, v: Float) {
 139:         heldLevel[id] = v
 140:         heldUntil[id] = SystemClock.uptimeMillis() + HOLD_MS
 141:     }
 142: 
 143:     private fun read(id: String): Float {
 144:         if (id == "brightness") return bright.level()
 145:         val am = audio ?: return 0f
 146:         val s = stream(id)
 147:         if (s < 0) return 0f
 148:         val max = am.getStreamMaxVolume(s).coerceAtLeast(1)
 149:         return (am.getStreamVolume(s).toFloat() / max).coerceIn(0f, 1f)
 150:     }
 151: 
 152:     private fun write(id: String, v: Float) {
 153:         if (id == "brightness") {
 154:             bright.setLevel(v)
 155:             return
 156:         }
 157:         val am = audio ?: return
 158:         val s = stream(id)
 159:         if (s < 0) return
 160:         val max = am.getStreamMaxVolume(s)
 161:         val target = Math.round(v * max)
 162:         try {
 163:             if (am.getStreamVolume(s) != target) am.setStreamVolume(s, target, 0)
 164:         } catch (e: SecurityException) {
 165:         }
 166:     }
 167: 
 168:     private fun stream(id: String): Int = when (id) {
 169:         "media_volume" -> AudioManager.STREAM_MUSIC
 170:         "ring_volume" -> AudioManager.STREAM_RING
 171:         "alarm_volume" -> AudioManager.STREAM_ALARM
 172:         "notification_volume" -> AudioManager.STREAM_NOTIFICATION
 173:         "call_volume" -> AudioManager.STREAM_VOICE_CALL
 174:         else -> -1
 175:     }
 176: 
 177:     companion object {
 178:         private const val HOLD_MS = 500L
 179:         private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
 180: 
 181:         val ALL = listOf("brightness", "media_volume", "ring_volume", "alarm_volume", "notification_volume", "call_volume")
 182: 
 183:         fun label(id: String): String = when (id) {
 184:             "brightness" -> "Brightness"
 185:             "media_volume" -> "Media"
 186:             "ring_volume" -> "Ring"
 187:             "alarm_volume" -> "Alarm"
 188:             "notification_volume" -> "Alerts"
 189:             "call_volume" -> "Call"
 190:             else -> id
 191:         }
 192:     }
 193: }
===== ArcSet.kt =====
   1: package com.dynedge
   2: 
   3: import android.graphics.Canvas
   4: import android.graphics.Color
   5: import android.graphics.Paint
   6: import android.graphics.Rect
   7: import android.graphics.RectF
   8: import android.graphics.Typeface
   9: import android.os.SystemClock
  10: import android.text.TextPaint
  11: import android.util.TypedValue
  12: import android.view.HapticFeedbackConstants
  13: import kotlin.math.abs
  14: import kotlin.math.asin
  15: import kotlin.math.cos
  16: import kotlin.math.min
  17: import kotlin.math.sin
  18: import kotlin.math.sqrt
  19: 
  20: /**
  21:  * A stack of concentric outer-ring sliders (brightness / volume streams) with a badge at the low end of each.
  22:  * Shared by the QUICK and VOLUME windows. A DOWN inside an arc band claims the whole gesture (robust drag).
  23:  */
  24: internal class ArcSet(
  25:     private val h: EdgeView,
  26:     private val win: Win,
  27:     private val badgeBase: Int,
  28:     max: Int,
  29:     private val badgeR: Float,
  30:     private val fixedStepDeg: Float
  31: ) {
  32:     var n = 0
  33:         private set
  34:     private val ids = arrayOfNulls<String>(max)
  35:     private val level = FloatArray(max)
  36:     private val icon = IntArray(max)
  37:     private val act = BooleanArray(max)
  38:     private val bright = BooleanArray(max)
  39:     private val r = FloatArray(max)
  40:     private val a0 = FloatArray(max)
  41:     private val oval = Array(max) { RectF() }
  42:     private val bx = FloatArray(max)
  43:     private val by = FloatArray(max)
  44:     private val iconRect = Array(max) { Rect() }
  45:     private val sent = FloatArray(max) { -1f }
  46:     private val sentMs = LongArray(max)
  47:     private val hold = LongArray(max)
  48:     private var drag = -1
  49:     private var tol = 0f
  50:     private val autoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
  51:         color = Color.BLACK
  52:         textAlign = Paint.Align.CENTER
  53:         typeface = Typeface.DEFAULT_BOLD
  54:         textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, h.resources.displayMetrics)
  55:     }
  56:     private val refreshRunnable = Runnable {
  57:         if (win.started) {
  58:             refresh()
  59:             h.invalidate()
  60:         }
  61:     }
  62: 
  63:     val dragging: Boolean get() = drag >= 0
  64: 
  65:     fun set(list: List<String>) {
  66:         n = min(list.size, ids.size)
  67:         for (k in ids.indices) ids[k] = if (k < n) list[k] else null
  68:         for (k in ids.indices) {
  69:             bright[k] = ids[k] == "brightness"
  70:             icon[k] = 0
  71:             act[k] = false
  72:             level[k] = 0.5f
  73:         }
  74:         win.dirty = true
  75:     }
  76: 
  77:     /** radii in px (outermost first). */
  78:     fun layout(radii: FloatArray) {
  79:         val sunR = badgeR
  80:         tol = h.dp(22f)
  81:         for (k in 0 until n) {
  82:             val rr = radii[k]
  83:             r[k] = rr
  84:             oval[k].set(-rr, -rr, rr, rr)
  85:             var badgeA = Math.toDegrees(asin(((sunR + h.dp(4f)) / rr).toDouble().coerceAtMost(1.0))).toFloat()
  86:             badgeA += if (fixedStepDeg > 0f) {
  87:                 k * fixedStepDeg
  88:             } else if (k > 0) {
  89:                 Math.toDegrees(((2f * sunR + h.dp(2f)) / rr).toDouble()).toFloat()
  90:             } else 0f
  91:             val sunHalf = Math.toDegrees(asin((sunR / rr).toDouble().coerceAtMost(1.0))).toFloat()
  92:             a0[k] = badgeA + sunHalf + 2f
  93:             val ba = Math.toRadians(badgeA.toDouble())
  94:             bx[k] = -rr * cos(ba).toFloat()
  95:             by[k] = -rr * sin(ba).toFloat()
  96:             val sh = (sunR * 0.6f).toInt()
  97:             iconRect[k].set((bx[k] - sh).toInt(), (by[k] - sh).toInt(), (bx[k] + sh).toInt(), (by[k] + sh).toInt())
  98:         }
  99:         if (n > 1) {
 100:             var sp = Float.MAX_VALUE
 101:             for (k in 1 until n) sp = min(sp, abs(r[k - 1] - r[k]))
 102:             tol = min(tol, sp / 2f + h.dp(2f))
 103:         }
 104:     }
 105: 
 106:     fun radius(k: Int): Float = r[k]
 107: 
 108:     fun refresh() {
 109:         win.dirty = true
 110:         val now = SystemClock.uptimeMillis()
 111:         for (k in 0 until n) {
 112:             val id = ids[k] ?: continue
 113:             icon[k] = h.arcs.iconRes(id)
 114:             h.warm(icon[k])
 115:             act[k] = h.arcs.iconActive(id)
 116:             if (drag != k && now >= hold[k]) level[k] = h.arcs.level(id).coerceIn(0f, 1f)
 117:         }
 118:     }
 119: 
 120:     fun draw(c: Canvas, a: Float, accent: Int) {
 121:         val ai = (255f * a + 0.5f).toInt()
 122:         val p = h.arcPaint
 123:         for (k in 0 until n) {
 124:             p.strokeWidth = h.dp(5f)
 125:             p.color = EdgeView.C_TRACK
 126:             p.alpha = ai
 127:             c.drawArc(oval[k], 180f + a0[k], Win.SL_A1 - a0[k], false, p)
 128:             val lv = level[k].coerceIn(0f, 1f)
 129:             if (lv > 0.001f) {
 130:                 p.color = accent
 131:                 p.alpha = ai
 132:                 c.drawArc(oval[k], 180f + a0[k], (Win.SL_A1 - a0[k]) * lv, false, p)
 133:             }
 134:             drawBadge(c, k, a, accent)
 135:         }
 136:     }
 137: 
 138:     private fun drawBadge(c: Canvas, k: Int, a: Float, accent: Int) {
 139:         val x = bx[k]
 140:         val y = by[k]
 141:         val ps = win.isPressed(badgeBase + k)
 142:         val ai = (255f * a + 0.5f).toInt()
 143:         if (ps) {
 144:             c.save()
 145:             c.scale(EdgeView.PRESS_SCALE, EdgeView.PRESS_SCALE, x, y)
 146:         }
 147:         val on = act[k]
 148:         h.fillP.color = if (on) accent else EdgeView.C_FILL
 149:         h.fillP.alpha = ai
 150:         c.drawCircle(x, y, badgeR, h.fillP)
 151:         if (ps) {
 152:             h.ringPaint.alpha = ai
 153:             c.drawCircle(x, y, badgeR + h.dp(3f), h.ringPaint)
 154:         }
 155:         if (icon[k] != 0) {
 156:             val ic = h.icon(icon[k], on)
 157:             ic.bounds = iconRect[k]
 158:             ic.alpha = ai
 159:             ic.draw(c)
 160:         }
 161:         if (ps) c.restore()
 162:         if (on && bright[k]) {
 163:             autoPaint.alpha = ai
 164:             c.drawText("A", x + h.dp(6f), y + h.dp(11f), autoPaint)
 165:         }
 166:     }
 167: 
 168:     /** Badge index under the point or -1. */
 169:     fun badgeHit(lx: Float, ly: Float): Int {
 170:         var best = -1
 171:         var bestD = Float.MAX_VALUE
 172:         val hr = h.dp(22f)
 173:         for (k in 0 until n) {
 174:             val dx = lx - bx[k]
 175:             val dy = ly - by[k]
 176:             val d2 = dx * dx + dy * dy
 177:             if (d2 <= hr * hr && d2 < bestD) {
 178:                 best = k
 179:                 bestD = d2
 180:             }
 181:         }
 182:         return best
 183:     }
 184: 
 185:     fun capture(lx: Float, ly: Float): Boolean {
 186:         if (n == 0) return false
 187:         val pr = sqrt(lx * lx + ly * ly)
 188:         val pa = Math.toDegrees(kotlin.math.atan2(-ly.toDouble(), -lx.toDouble())).toFloat()
 189:         var best = -1
 190:         var bestD = tol
 191:         for (k in 0 until n) {
 192:             val d = abs(pr - r[k])
 193:             if (d < bestD && pa > a0[k] - 4f && pa < Win.SL_A1 + 4f) {
 194:                 best = k
 195:                 bestD = d
 196:             }
 197:         }
 198:         if (best < 0) return false
 199:         drag = best
 200:         return true
 201:     }
 202: 
 203:     fun dragTo(lx: Float, ly: Float) {
 204:         val k = drag
 205:         if (k < 0) return
 206:         val pa = Math.toDegrees(kotlin.math.atan2(-ly.toDouble(), -lx.toDouble())).toFloat()
 207:         val lv = ((pa - a0[k]) / (Win.SL_A1 - a0[k])).coerceIn(0f, 1f)
 208:         if (level[k] != lv) {
 209:             level[k] = lv
 210:             win.dirty = true
 211:         }
 212:         val now = SystemClock.uptimeMillis()
 213:         if (abs(lv - sent[k]) > 0.004f && now - sentMs[k] >= 16L) {
 214:             sent[k] = lv
 215:             sentMs[k] = now
 216:             val id = ids[k]
 217:             if (id != null) h.arcs.setLevel(id, lv)
 218:         }
 219:         h.invalidate()
 220:     }
 221: 
 222:     fun dragEnd(cancel: Boolean) {
 223:         val k = drag
 224:         if (k < 0) return
 225:         drag = -1
 226:         if (!cancel) {
 227:             val id = ids[k]
 228:             if (id != null) {
 229:                 h.arcs.setLevel(id, level[k])
 230:                 h.arcs.commit(id)
 231:             }
 232:             // keep the dragged value as truth while the setting catches up
 233:             hold[k] = SystemClock.uptimeMillis() + 500L
 234:             h.removeCallbacks(refreshRunnable)
 235:             h.postDelayed(refreshRunnable, 520L)
 236:             h.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
 237:         } else if (win.started) {
 238:             refresh()
 239:         }
 240:     }
 241: 
 242:     fun tap(k: Int) {
 243:         val id = ids.getOrNull(k) ?: return
 244:         h.arcs.tapIcon(id)
 245:         refresh()
 246:         h.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
 247:     }
 248: 
 249:     fun release() {
 250:         h.removeCallbacks(refreshRunnable)
 251:         drag = -1
 252:     }
 253: }
===== AssistControl.kt =====
   1: package com.dynedge
   2: 
   3: import android.content.Context
   4: import android.content.Intent
   5: import android.provider.Settings
   6: import android.util.Log
   7: 
   8: /**
   9:  * Google Assistant handling.
  10:  * - The corner-swipe Assistant gesture fights with the widget in the same corner, so it is switched
  11:  *   off whenever the service starts (needs WRITE_SECURE_SETTINGS, granted once over adb).
  12:  * - The widget offers its own on-screen Assistant button instead (launch()).
  13:  */
  14: object AssistControl {
  15: 
  16:     // vivo/Funtouch key, then the AOSP key used by Pixel-style gesture nav.
  17:     private val GESTURE_KEYS = arrayOf("navigation_google_assist", "assist_touch_gesture_enabled")
  18: 
  19:     fun disableCornerGesture(ctx: Context) {
  20:         val cr = ctx.contentResolver
  21:         for (k in GESTURE_KEYS) {
  22:             try {
  23:                 if (Settings.Secure.getInt(cr, k, 0) != 0) Settings.Secure.putInt(cr, k, 0)
  24:             } catch (e: SecurityException) {
  25:                 Log.w("DynamicEdge", "no WRITE_SECURE_SETTINGS; corner Assistant gesture left on")
  26:                 return
  27:             } catch (e: Exception) {
  28:                 // key not present on this ROM
  29:             }
  30:         }
  31:     }
  32: 
  33:     /** Opens the default assistant. Returns true if an Activity was launched. */
  34:     fun launch(ctx: Context): Boolean {
  35:         val intents = arrayOf(
  36:             Intent(Intent.ACTION_VOICE_COMMAND),
  37:             Intent(Intent.ACTION_ASSIST),
  38:         )
  39:         for (i in intents) {
  40:             try {
  41:                 i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  42:                 ctx.startActivity(i)
  43:                 return true
  44:             } catch (e: Exception) {
  45:                 // try next
  46:             }
  47:         }
  48:         return false
  49:     }
  50: }
===== Brightness.kt =====
   1: package com.dynedge
   2: 
   3: import android.content.Context
   4: import android.content.res.Resources
   5: import android.database.ContentObserver
   6: import android.net.Uri
   7: import android.os.Handler
   8: import android.os.Looper
   9: import android.provider.Settings
  10: 
  11: /** Reads and writes system brightness on a perceptual (gamma 2.2) curve. */
  12: class Brightness(private val ctx: Context, private val onChange: () -> Unit) {
  13: 
  14:     private val resolver = ctx.contentResolver
  15:     private val handler = Handler(Looper.getMainLooper())
  16:     private var started = false
  17:     private var wasAuto = false
  18: 
  19:     private val observer = object : ContentObserver(handler) {
  20:         override fun onChange(selfChange: Boolean) {
  21:             onChange.invoke()
  22:         }
  23: 
  24:         override fun onChange(selfChange: Boolean, uri: Uri?) {
  25:             onChange.invoke()
  26:         }
  27:     }
  28: 
  29:     val isAuto: Boolean
  30:         get() = try {
  31:             Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
  32:                 Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
  33:         } catch (e: Exception) {
  34:             false
  35:         }
  36: 
  37:     fun start() {
  38:         if (started) return
  39:         started = true
  40:         resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, observer)
  41:         resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, observer)
  42:     }
  43: 
  44:     fun stop() {
  45:         if (!started) return
  46:         started = false
  47:         resolver.unregisterContentObserver(observer)
  48:     }
  49: 
  50:     fun level(): Float {
  51:         val raw = try {
  52:             Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
  53:         } catch (e: Exception) {
  54:             return 0f
  55:         }
  56:         val lin = ((raw - MIN).toFloat() / (MAX - MIN).toFloat()).coerceIn(0f, 1f)
  57:         return Math.pow(lin.toDouble(), 1.0 / 2.2).toFloat()
  58:     }
  59: 
  60:     fun setLevel(f: Float) {
  61:         if (!Settings.System.canWrite(ctx)) return
  62:         try {
  63:             if (isAuto) {
  64:                 wasAuto = true
  65:                 Settings.System.putInt(
  66:                     resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
  67:                     Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
  68:                 )
  69:             }
  70:             val lin = Math.pow(f.coerceIn(0f, 1f).toDouble(), 2.2)
  71:             val raw = Math.round(MIN + lin * (MAX - MIN)).toInt().coerceIn(MIN, MAX)
  72:             val cur = try {
  73:                 Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
  74:             } catch (e: Exception) {
  75:                 -1
  76:             }
  77:             if (raw != cur) Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, raw)
  78:         } catch (e: Exception) {
  79:         }
  80:     }
  81: 
  82:     fun setAuto(on: Boolean) {
  83:         if (!Settings.System.canWrite(ctx)) return
  84:         try {
  85:             Settings.System.putInt(
  86:                 resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
  87:                 if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
  88:             )
  89:             wasAuto = false
  90:         } catch (e: Exception) {
  91:         }
  92:     }
  93: 
  94:     private companion object {
  95:         val MIN: Int = frameworkInt("config_screenBrightnessSettingMinimum", 1)
  96:         val MAX: Int = frameworkInt("config_screenBrightnessSettingMaximum", 255).coerceAtLeast(MIN + 1)
  97: 
  98:         fun frameworkInt(name: String, def: Int): Int {
  99:             return try {
 100:                 val res = Resources.getSystem()
 101:                 val id = res.getIdentifier(name, "integer", "android")
 102:                 if (id != 0) res.getInteger(id) else def
 103:             } catch (e: Exception) {
 104:                 def
 105:             }
 106:         }
 107:     }
 108: }
===== ClipboardActivity.kt =====
   1: package com.dynedge
   2: 
   3: import android.app.Activity
   4: import android.app.AlertDialog
   5: import android.content.ClipData
   6: import android.content.ClipboardManager
   7: import android.content.Context
   8: import android.graphics.Color
   9: import android.graphics.drawable.ColorDrawable
  10: import android.os.Bundle
  11: import android.text.Editable
  12: import android.text.TextUtils
  13: import android.text.TextWatcher
  14: import android.util.TypedValue
  15: import android.view.Gravity
  16: import android.view.View
  17: import android.view.ViewGroup
  18: import android.view.WindowInsets
  19: import android.widget.BaseAdapter
  20: import android.widget.EditText
  21: import android.widget.LinearLayout
  22: import android.widget.ListView
  23: import android.widget.TextView
  24: import android.widget.Toast
  25: 
  26: /** Full clipboard history: search, tap to copy, long-press for pin / delete. */
  27: class ClipboardActivity : Activity() {
  28: 
  29:     private lateinit var store: ClipStore
  30:     private lateinit var listView: ListView
  31:     private var shown: List<ClipStore.Clip> = emptyList()
  32:     private var query = ""
  33:     private var accent = Color.WHITE
  34:     private val refresh: () -> Unit = { reload() }
  35: 
  36:     private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
  37: 
  38:     override fun onCreate(savedInstanceState: Bundle?) {
  39:         super.onCreate(savedInstanceState)
  40:         window.statusBarColor = Color.BLACK
  41:         window.navigationBarColor = Color.BLACK
  42:         window.setDecorFitsSystemWindows(false)
  43:         accent = Prefs.accent(this)
  44:         store = ClipStore.get(this)
  45: 
  46:         val root = LinearLayout(this).apply {
  47:             orientation = LinearLayout.VERTICAL
  48:             setBackgroundColor(Color.BLACK)
  49:             setOnApplyWindowInsetsListener { v, insets ->
  50:                 val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
  51:                 v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
  52:                 WindowInsets.CONSUMED
  53:             }
  54:         }
  55: 
  56:         val header = LinearLayout(this).apply {
  57:             orientation = LinearLayout.HORIZONTAL
  58:             gravity = Gravity.CENTER_VERTICAL
  59:             setPadding(dp(16), dp(4), dp(4), dp(4))
  60:         }
  61:         val search = EditText(this).apply {
  62:             hint = "Search"
  63:             setHintTextColor(Color.parseColor("#636366"))
  64:             setTextColor(Color.WHITE)
  65:             setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
  66:             setSingleLine(true)
  67:             background = null
  68:             setPadding(0, dp(12), dp(8), dp(12))
  69:             addTextChangedListener(object : TextWatcher {
  70:                 override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
  71:                 override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
  72:                 override fun afterTextChanged(s: Editable?) {
  73:                     query = s?.toString()?.trim().orEmpty()
  74:                     reload()
  75:                 }
  76:             })
  77:         }
  78:         header.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
  79:         header.addView(TextView(this).apply {
  80:             text = "Clear"
  81:             setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
  82:             setTextColor(accent)
  83:             gravity = Gravity.CENTER
  84:             setPadding(dp(16), 0, dp(16), 0)
  85:             minimumHeight = dp(48)
  86:             setOnClickListener { store.clearUnpinned() }
  87:         }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
  88:         root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
  89: 
  90:         listView = ListView(this).apply {
  91:             setBackgroundColor(Color.BLACK)
  92:             divider = ColorDrawable(Color.parseColor("#1C1C1E"))
  93:             dividerHeight = 1
  94:             selector = ColorDrawable(0x33FFFFFF)
  95:             adapter = ClipAdapter()
  96:             setOnItemClickListener { _, _, pos, _ -> copy(shown[pos]) }
  97:             setOnItemLongClickListener { _, _, pos, _ ->
  98:                 menu(shown[pos])
  99:                 true
 100:             }
 101:         }
 102:         root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
 103:         setContentView(root)
 104:         reload()
 105:     }
 106: 
 107:     override fun onResume() {
 108:         super.onResume()
 109:         store.addListener(refresh)
 110:         ClipPaste.capture(this)
 111:         reload()
 112:     }
 113: 
 114:     override fun onPause() {
 115:         super.onPause()
 116:         store.removeListener(refresh)
 117:     }
 118: 
 119:     override fun onWindowFocusChanged(hasFocus: Boolean) {
 120:         super.onWindowFocusChanged(hasFocus)
 121:         if (hasFocus) ClipPaste.capture(this)
 122:     }
 123: 
 124:     private fun reload() {
 125:         val all = store.list()
 126:         shown = if (query.isEmpty()) all else all.filter { it.text.contains(query, ignoreCase = true) }
 127:         (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
 128:     }
 129: 
 130:     private fun copy(c: ClipStore.Clip) {
 131:         try {
 132:             val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
 133:             cm.setPrimaryClip(ClipData.newPlainText("clip", c.text))
 134:             Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
 135:         } catch (e: Exception) {
 136:         }
 137:     }
 138: 
 139:     private fun menu(c: ClipStore.Clip) {
 140:         val items = arrayOf(if (c.pinned) "Unpin" else "Pin", "Delete")
 141:         AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
 142:             .setItems(items) { _, which ->
 143:                 if (which == 0) store.pin(c.id, !c.pinned) else store.delete(c.id)
 144:             }
 145:             .show()
 146:     }
 147: 
 148:     private inner class ClipAdapter : BaseAdapter() {
 149:         override fun getCount(): Int = shown.size
 150:         override fun getItem(position: Int): Any = shown[position]
 151:         override fun getItemId(position: Int): Long = shown[position].id
 152: 
 153:         override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
 154:             val row: LinearLayout
 155:             val tick: View
 156:             val text: TextView
 157:             if (convertView == null) {
 158:                 row = LinearLayout(this@ClipboardActivity).apply {
 159:                     orientation = LinearLayout.HORIZONTAL
 160:                     gravity = Gravity.CENTER_VERTICAL
 161:                     setPadding(0, dp(12), dp(20), dp(12))
 162:                 }
 163:                 tick = View(this@ClipboardActivity)
 164:                 text = TextView(this@ClipboardActivity).apply {
 165:                     setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
 166:                     setTextColor(Color.WHITE)
 167:                     maxLines = 2
 168:                     ellipsize = TextUtils.TruncateAt.END
 169:                 }
 170:                 row.addView(tick, LinearLayout.LayoutParams(dp(3), dp(28)).apply {
 171:                     leftMargin = dp(8)
 172:                     rightMargin = dp(9)
 173:                 })
 174:                 row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
 175:                 row.tag = arrayOf(tick, text)
 176:             } else {
 177:                 row = convertView as LinearLayout
 178:                 @Suppress("UNCHECKED_CAST")
 179:                 val t = row.tag as Array<View>
 180:                 tick = t[0]
 181:                 text = t[1] as TextView
 182:             }
 183:             val c = shown[position]
 184:             text.text = c.text
 185:             tick.setBackgroundColor(if (c.pinned) accent else Color.TRANSPARENT)
 186:             return row
 187:         }
 188:     }
 189: }
===== ClipPaste.kt =====
   1: package com.dynedge
   2: 
   3: import android.accessibilityservice.AccessibilityService
   4: import android.content.ClipData
   5: import android.content.ClipboardManager
   6: import android.content.Context
   7: import android.os.Bundle
   8: import android.view.accessibility.AccessibilityNodeInfo
   9: 
  10: object ClipPaste {
  11: 
  12:     /** Reads the current primary clip into the store. Only works while this app has focus. */
  13:     fun capture(ctx: Context): Boolean {
  14:         return try {
  15:             val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
  16:             val clip = cm.primaryClip ?: return false
  17:             if (clip.itemCount == 0) return false
  18:             val text = clip.getItemAt(0).coerceToText(ctx)?.toString() ?: return false
  19:             ClipStore.get(ctx).add(text)
  20:         } catch (e: Exception) {
  21:             false
  22:         }
  23:     }
  24: 
  25:     /** Copies text and pastes it into the focused editable field. False if there is none. */
  26:     fun paste(service: AccessibilityService, text: String): Boolean {
  27:         try {
  28:             val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  29:             cm.setPrimaryClip(ClipData.newPlainText("clip", text))
  30:         } catch (e: Exception) {
  31:             return false
  32:         }
  33:         val node = findEditable(service) ?: return false
  34:         if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
  35:         return setText(node, text)
  36:     }
  37: 
  38:     private fun findEditable(service: AccessibilityService): AccessibilityNodeInfo? {
  39:         try {
  40:             val windows = service.windows
  41:             if (windows != null) {
  42:                 for (w in windows) {
  43:                     val root = w.root ?: continue
  44:                     val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
  45:                     if (f != null && f.isEditable) return f
  46:                 }
  47:             }
  48:         } catch (e: Exception) {
  49:         }
  50:         try {
  51:             val root = service.rootInActiveWindow
  52:             val f = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
  53:             if (f != null && f.isEditable) return f
  54:         } catch (e: Exception) {
  55:         }
  56:         return null
  57:     }
  58: 
  59:     private fun setText(node: AccessibilityNodeInfo, clip: String): Boolean {
  60:         val existing = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
  61:         var s = node.textSelectionStart
  62:         var e = node.textSelectionEnd
  63:         if (s < 0 || e < 0) {
  64:             s = existing.length
  65:             e = existing.length
  66:         }
  67:         if (s > e) {
  68:             val t = s
  69:             s = e
  70:             e = t
  71:         }
  72:         s = s.coerceIn(0, existing.length)
  73:         e = e.coerceIn(0, existing.length)
  74:         val out = existing.substring(0, s) + clip + existing.substring(e)
  75:         val args = Bundle()
  76:         args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, out)
  77:         val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
  78:         if (ok) {
  79:             val sel = Bundle()
  80:             val caret = s + clip.length
  81:             sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
  82:             sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
  83:             node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
  84:         }
  85:         return ok
  86:     }
  87: }
===== ClipStore.kt =====
   1: package com.dynedge
   2: 
   3: import android.content.Context
   4: import android.os.Handler
   5: import android.os.Looper
   6: import org.json.JSONArray
   7: import org.json.JSONObject
   8: import java.io.File
   9: import java.util.concurrent.Executors
  10: 
  11: /** Clipboard history, persisted to filesDir/clips.json. Listeners are called on the main thread. */
  12: class ClipStore private constructor(ctx: Context) {
  13: 
  14:     data class Clip(val id: Long, val text: String, val time: Long, val pinned: Boolean)
  15: 
  16:     private val file = File(ctx.applicationContext.filesDir, "clips.json")
  17:     private val main = Handler(Looper.getMainLooper())
  18:     private val io = Executors.newSingleThreadExecutor()
  19:     private val lock = Any()
  20:     private val items = ArrayList<Clip>()
  21:     private val listeners = ArrayList<() -> Unit>()
  22:     private var nextId = 1L
  23: 
  24:     private val notifier = Runnable {
  25:         val copy = synchronized(lock) { ArrayList(listeners) }
  26:         for (l in copy) l()
  27:     }
  28: 
  29:     init {
  30:         load()
  31:     }
  32: 
  33:     fun list(): List<Clip> = synchronized(lock) {
  34:         val pinned = items.filter { it.pinned }.sortedByDescending { it.time }
  35:         val rest = items.filter { !it.pinned }.sortedByDescending { it.time }
  36:         pinned + rest
  37:     }
  38: 
  39:     fun add(text: String): Boolean {
  40:         var t = text.trim()
  41:         if (t.isEmpty()) return false
  42:         if (t.length > MAX_TEXT) t = t.substring(0, MAX_TEXT)
  43:         synchronized(lock) {
  44:             val newest = items.maxByOrNull { it.time }
  45:             if (newest != null && newest.text == t) return false
  46:             val now = maxOf(System.currentTimeMillis(), (newest?.time ?: 0L) + 1)
  47:             val idx = items.indexOfFirst { it.text == t }
  48:             if (idx >= 0) {
  49:                 items[idx] = items[idx].copy(time = now)
  50:             } else {
  51:                 items.add(Clip(nextId++, t, now, false))
  52:                 val unpinned = items.filter { !it.pinned }.sortedBy { it.time }
  53:                 var extra = unpinned.size - MAX_UNPINNED
  54:                 var i = 0
  55:                 while (extra > 0 && i < unpinned.size) {
  56:                     items.remove(unpinned[i])
  57:                     i++
  58:                     extra--
  59:                 }
  60:             }
  61:         }
  62:         changed()
  63:         return true
  64:     }
  65: 
  66:     fun pin(id: Long, pinned: Boolean) {
  67:         val did = synchronized(lock) {
  68:             val i = items.indexOfFirst { it.id == id }
  69:             if (i < 0 || items[i].pinned == pinned) false else {
  70:                 items[i] = items[i].copy(pinned = pinned)
  71:                 true
  72:             }
  73:         }
  74:         if (did) changed()
  75:     }
  76: 
  77:     fun delete(id: Long) {
  78:         val did = synchronized(lock) { items.removeAll { it.id == id } }
  79:         if (did) changed()
  80:     }
  81: 
  82:     fun clearUnpinned() {
  83:         val did = synchronized(lock) { items.removeAll { !it.pinned } }
  84:         if (did) changed()
  85:     }
  86: 
  87:     fun addListener(l: () -> Unit) {
  88:         synchronized(lock) { if (l !in listeners) listeners.add(l) }
  89:     }
  90: 
  91:     fun removeListener(l: () -> Unit) {
  92:         synchronized(lock) { listeners.remove(l) }
  93:     }
  94: 
  95:     private fun changed() {
  96:         persist()
  97:         main.removeCallbacks(notifier)
  98:         main.post(notifier)
  99:     }
 100: 
 101:     private fun persist() {
 102:         val json = synchronized(lock) {
 103:             val a = JSONArray()
 104:             for (c in items) {
 105:                 a.put(
 106:                     JSONObject().put("id", c.id).put("text", c.text)
 107:                         .put("time", c.time).put("pinned", c.pinned)
 108:                 )
 109:             }
 110:             a.toString()
 111:         }
 112:         io.execute {
 113:             try {
 114:                 val tmp = File(file.parentFile, "clips.json.tmp")
 115:                 tmp.writeText(json)
 116:                 if (!tmp.renameTo(file)) {
 117:                     file.writeText(json)
 118:                     tmp.delete()
 119:                 }
 120:             } catch (e: Exception) {
 121:             }
 122:         }
 123:     }
 124: 
 125:     private fun load() {
 126:         try {
 127:             if (!file.exists()) return
 128:             val a = JSONArray(file.readText())
 129:             synchronized(lock) {
 130:                 for (i in 0 until a.length()) {
 131:                     val o = a.optJSONObject(i) ?: continue
 132:                     val text = o.optString("text", "")
 133:                     if (text.isEmpty()) continue
 134:                     val id = o.optLong("id", nextId)
 135:                     items.add(Clip(id, text, o.optLong("time", 0L), o.optBoolean("pinned", false)))
 136:                     if (id >= nextId) nextId = id + 1
 137:                 }
 138:             }
 139:         } catch (e: Exception) {
 140:         }
 141:     }
 142: 
 143:     companion object {
 144:         private const val MAX_UNPINNED = 60
 145:         private const val MAX_TEXT = 20000
 146: 
 147:         @Volatile
 148:         private var instance: ClipStore? = null
 149: 
 150:         fun get(ctx: Context): ClipStore =
 151:             instance ?: synchronized(this) {
 152:                 instance ?: ClipStore(ctx).also { instance = it }
 153:             }
 154:     }
 155: }
===== DeviceStats.kt =====
   1: package com.dynedge
   2: 
   3: import android.app.ActivityManager
   4: import android.content.BroadcastReceiver
   5: import android.content.Context
   6: import android.content.Intent
   7: import android.content.IntentFilter
   8: import android.os.BatteryManager
   9: import android.os.Handler
  10: import android.os.Looper
  11: import android.os.StatFs
  12: 
  13: /** Battery, RAM and storage numbers. Receiver and 2 s timer exist only between start() and stop(). */
  14: class DeviceStats(ctx: Context, private val onChange: () -> Unit) {
  15: 
  16:     private val app: Context = ctx.applicationContext ?: ctx
  17:     private val main = Handler(Looper.getMainLooper())
  18:     private val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
  19:     private val mem = ActivityManager.MemoryInfo()
  20:     private var started = false
  21: 
  22:     var batteryPct = 0; private set
  23:     var charging = false; private set
  24:     var tempC = 0f; private set
  25:     var ramUsedFrac = 0f; private set
  26:     var ramTotalGb = 0f; private set
  27:     var storageUsedFrac = 0f; private set
  28:     var storageTotalGb = 0f; private set
  29: 
  30:     private val receiver = object : BroadcastReceiver() {
  31:         override fun onReceive(context: Context?, intent: Intent?) {
  32:             if (intent != null && readBattery(intent)) onChange()
  33:         }
  34:     }
  35: 
  36:     private val tick = object : Runnable {
  37:         override fun run() {
  38:             if (!started) return
  39:             if (readRam()) onChange()
  40:             main.postDelayed(this, 2000L)
  41:         }
  42:     }
  43: 
  44:     fun start() {
  45:         if (started) return
  46:         started = true
  47:         try {
  48:             val sticky = app.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
  49:             if (sticky != null) readBattery(sticky)
  50:         } catch (e: Exception) {
  51:         }
  52:         readRam()
  53:         readStorage()
  54:         main.postDelayed(tick, 2000L)
  55:         onChange()
  56:     }
  57: 
  58:     fun stop() {
  59:         if (!started) return
  60:         started = false
  61:         main.removeCallbacks(tick)
  62:         try {
  63:             app.unregisterReceiver(receiver)
  64:         } catch (e: Exception) {
  65:         }
  66:     }
  67: 
  68:     private fun readBattery(i: Intent): Boolean {
  69:         val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
  70:         val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
  71:         val pct = if (level >= 0) (level * 100f / scale).toInt().coerceIn(0, 100) else batteryPct
  72:         val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
  73:         val ch = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
  74:         val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
  75:         val changed = pct != batteryPct || ch != charging || t != tempC
  76:         batteryPct = pct; charging = ch; tempC = t
  77:         return changed
  78:     }
  79: 
  80:     private fun readRam(): Boolean {
  81:         val m = am ?: return false
  82:         return try {
  83:             m.getMemoryInfo(mem)
  84:             val total = mem.totalMem.coerceAtLeast(1L)
  85:             val used = ((total - mem.availMem).toFloat() / total).coerceIn(0f, 1f)
  86:             val gb = total / 1073741824f
  87:             val changed = Math.abs(used - ramUsedFrac) > 0.004f || gb != ramTotalGb
  88:             ramUsedFrac = used; ramTotalGb = gb
  89:             changed
  90:         } catch (e: Exception) {
  91:             false
  92:         }
  93:     }
  94: 
  95:     private fun readStorage() {
  96:         try {
  97:             val s = StatFs(app.dataDir.path)
  98:             val total = s.totalBytes.coerceAtLeast(1L)
  99:             storageUsedFrac = ((total - s.availableBytes).toFloat() / total).coerceIn(0f, 1f)
 100:             storageTotalGb = total / 1073741824f
 101:         } catch (e: Exception) {
 102:         }
 103:     }
 104: }
===== EdgeService.kt =====
   1: package com.dynedge
   2: 
   3: import android.accessibilityservice.AccessibilityService
   4: import android.app.KeyguardManager
   5: import android.content.BroadcastReceiver
   6: import android.content.Context
   7: import android.content.Intent
   8: import android.content.SharedPreferences
   9: import android.content.IntentFilter
  10: import android.content.res.Configuration
  11: import android.graphics.PixelFormat
  12: import android.graphics.Region
  13: import android.hardware.display.DisplayManager
  14: import android.os.BatteryManager
  15: import android.os.Build
  16: import android.os.Handler
  17: import android.os.Looper
  18: import android.os.PowerManager
  19: import android.view.Display
  20: import android.view.Gravity
  21: import android.view.RoundedCorner
  22: import android.view.WindowManager
  23: import android.view.accessibility.AccessibilityEvent
  24: import android.view.accessibility.AccessibilityWindowInfo
  25: 
  26: class EdgeService : AccessibilityService(), EdgeView.Host {
  27: 
  28:     private var wm: WindowManager? = null
  29:     private var edge: EdgeView? = null
  30:     private var lp: WindowManager.LayoutParams? = null
  31:     private var touch: TouchRegion? = null
  32:     private var sizesPosted = false
  33:     private var imeVisible = false
  34:     private var screenOff = false
  35:     private var receiverRegistered = false
  36: 
  37:     private val receiver = object : BroadcastReceiver() {
  38:         override fun onReceive(context: Context?, intent: Intent?) {
  39:             when (intent?.action) {
  40:                 Intent.ACTION_SCREEN_OFF -> {
  41:                     screenOff = true
  42:                     edge?.collapseImmediately()
  43:                     edge?.setScreenOn(false)
  44:                     updateHidden()
  45:                 }
  46:                 Intent.ACTION_SCREEN_ON -> {
  47:                     screenOff = false
  48:                     edge?.setScreenOn(true)
  49:                     updateHidden()
  50:                 }
  51:                 Intent.ACTION_USER_PRESENT -> updateHidden()
  52:                 Intent.ACTION_POWER_CONNECTED -> postCharging()
  53:                 Intent.ACTION_POWER_DISCONNECTED -> {
  54:                     mainHandler.removeCallbacks(chargingRemove)
  55:                     LiveBus.remove("charging")
  56:                 }
  57:             }
  58:         }
  59:     }
  60: 
  61:     private val mainHandler = Handler(Looper.getMainLooper())
  62:     private val chargingRemove = Runnable { LiveBus.remove("charging") }
  63: 
  64:     private fun postCharging() {
  65:         if (!Prefs.live(this) || screenOff) return
  66:         val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
  67:         val lv = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
  68:         val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
  69:         val pct = if (lv >= 0 && sc > 0) (lv * 100 / sc) else 0
  70:         LiveBus.put(
  71:             LiveActivity(
  72:                 "charging", LiveActivity.Kind.CHARGING, "$pct%", pct / 100f, 0L, false,
  73:                 R.drawable.ic_bolt, false, null, null, null
  74:             )
  75:         )
  76:         mainHandler.removeCallbacks(chargingRemove)
  77:         mainHandler.postDelayed(chargingRemove, 3500L)
  78:     }
  79: 
  80:     private var prefListenerRegistered = false
  81:     // strong reference: SharedPreferences only holds listeners weakly
  82:     private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
  83:         when (key) {
  84:             Prefs.KEY_IDLE_DP, Prefs.KEY_OPEN_DP -> scheduleSizes()
  85:             Prefs.KEY_PREVIEW -> applyPreview()
  86:             Prefs.KEY_ACCENT, Prefs.KEY_GRADIENT, Prefs.KEY_RIM, Prefs.KEY_GLOW, Prefs.KEY_BANDS,
  87:             Prefs.KEY_SPEED, Prefs.KEY_BOUNCE, Prefs.KEY_HAPTICS, Prefs.KEY_IDLE_STYLE,
  88:             Prefs.KEY_VISUALIZER, Prefs.KEY_LIVE, Prefs.KEY_BEAT -> edge?.applyLooks()
  89:             QuickConfig.KEY -> edge?.reloadQuick()
  90:             WindowsConfig.KEY -> edge?.reloadWindows()
  91:             Prefs.KEY_FLARE -> applyFlare()
  92:         }
  93:     }
  94: 
  95:     private val sizesRunnable = Runnable {
  96:         sizesPosted = false
  97:         applySizesFromPrefs()
  98:     }
  99: 
 100:     /** Coalesces a stream of size changes (slider drag) to one relayout per frame. */
 101:     private fun scheduleSizes() {
 102:         val view = edge ?: return
 103:         if (sizesPosted) return
 104:         sizesPosted = true
 105:         view.postOnAnimation(sizesRunnable)
 106:     }
 107: 
 108:     private fun applyPreview() {
 109:         val view = edge ?: return
 110:         when (Prefs.preview(this)) {
 111:             Prefs.PREVIEW_OPEN -> {
 112:                 view.holdOpen = true
 113:                 view.expand()
 114:             }
 115:             else -> {
 116:                 view.holdOpen = false
 117:                 view.collapse()
 118:             }
 119:         }
 120:     }
 121: 
 122:     private fun applySizesFromPrefs() {
 123:         val view = edge ?: return
 124:         val manager = wm ?: return
 125:         val params = lp ?: return
 126:         view.applySizes(Prefs.idleDp(this), Prefs.openDp(this))
 127:         resizeWindow(view, manager, params)
 128:     }
 129: 
 130:     private fun applyFlare() {
 131:         val view = edge ?: return
 132:         val manager = wm ?: return
 133:         val params = lp ?: return
 134:         view.applyFlare()
 135:         resizeWindow(view, manager, params)
 136:     }
 137: 
 138:     /** The window is always the large size; it only changes when the sizes change in prefs. */
 139:     private fun resizeWindow(view: EdgeView, manager: WindowManager, params: WindowManager.LayoutParams) {
 140:         val pad = (24f * density).toInt()
 141:         val w = view.maxOpenWidthPx + pad
 142:         val h = view.maxOpenHeightPx + pad
 143:         if (params.width == w && params.height == h) return
 144:         params.width = w
 145:         params.height = h
 146:         try {
 147:             manager.updateViewLayout(view, params)
 148:         } catch (e: Throwable) {
 149:         }
 150:     }
 151: 
 152:     private val density: Float get() = resources.displayMetrics.density
 153: 
 154:     private var attached = false
 155:     private var attachTries = 0
 156:     private val retryDelays = longArrayOf(300L, 1000L, 3000L, 3000L, 3000L)
 157:     private val attachRunnable = Runnable { attach() }
 158: 
 159:     override fun onServiceConnected() {
 160:         super.onServiceConnected()
 161:         mainHandler.removeCallbacks(attachRunnable)
 162:         attachTries = 0
 163:         attach()
 164:     }
 165: 
 166:     /** Idempotent: builds the view once, adds the window, and retries with backoff if the token is not valid yet. */
 167:     private fun attach() {
 168:         if (attached) return
 169:         val manager = wm ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { wm = it }
 170:         var view = edge
 171:         var params = lp
 172:         if (view == null || params == null) {
 173:             AssistControl.disableCornerGesture(this)
 174:             val v = EdgeView(this)
 175:             v.host = this
 176:             v.setService(this)
 177:             v.setTipRadius(readTipRadius())
 178:             val winPx = v.maxOpenWidthPx + (24f * density).toInt()
 179:             val p = WindowManager.LayoutParams(
 180:                 winPx,
 181:                 winPx,
 182:                 WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
 183:                 WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
 184:                     WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
 185:                     WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
 186:                     WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
 187:                     WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
 188:                     WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
 189:                 PixelFormat.TRANSLUCENT
 190:             )
 191:             p.gravity = Gravity.BOTTOM or Gravity.RIGHT
 192:             p.x = 0
 193:             p.y = 0
 194:             p.fitInsetsTypes = 0
 195:             p.fitInsetsSides = 0
 196:             p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
 197:             p.title = "DynamicEdge"
 198:             edge = v
 199:             lp = p
 200:             view = v
 201:             params = p
 202:         }
 203:         try {
 204:             if (!view.isAttachedToWindow) manager.addView(view, params)
 205:         } catch (e: WindowManager.BadTokenException) {
 206:             scheduleRetry()
 207:             return
 208:         } catch (e: IllegalStateException) {
 209:             scheduleRetry()
 210:             return
 211:         } catch (e: Throwable) {
 212:             scheduleRetry()
 213:             return
 214:         }
 215:         attached = true
 216:         touch?.uninstall()
 217:         touch = TouchRegion.install(view)
 218:         touch?.set(view.currentRegion())
 219: 
 220:         if (!receiverRegistered) {
 221:             val filter = IntentFilter()
 222:             filter.addAction(Intent.ACTION_SCREEN_OFF)
 223:             filter.addAction(Intent.ACTION_SCREEN_ON)
 224:             filter.addAction(Intent.ACTION_USER_PRESENT)
 225:             filter.addAction(Intent.ACTION_POWER_CONNECTED)
 226:             filter.addAction(Intent.ACTION_POWER_DISCONNECTED)
 227:             registerReceiver(receiver, filter)
 228:             receiverRegistered = true
 229:         }
 230: 
 231:         screenOff = false
 232:         updateHidden()
 233:         view.setScreenOn((getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true)
 234: 
 235:         if (!prefListenerRegistered) {
 236:             Prefs.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
 237:             prefListenerRegistered = true
 238:         }
 239:     }
 240: 
 241:     private fun scheduleRetry() {
 242:         if (attachTries >= retryDelays.size) return
 243:         val d = retryDelays[attachTries]
 244:         attachTries++
 245:         mainHandler.removeCallbacks(attachRunnable)
 246:         mainHandler.postDelayed(attachRunnable, d)
 247:     }
 248: 
 249:     override fun onAccessibilityEvent(event: AccessibilityEvent?) {
 250:         if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
 251:         val ime = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
 252:         if (ime != imeVisible) {
 253:             imeVisible = ime
 254:             updateHidden()
 255:         }
 256:     }
 257: 
 258:     override fun onInterrupt() {
 259:         // Nothing to interrupt.
 260:     }
 261: 
 262:     override fun onConfigurationChanged(newConfig: Configuration) {
 263:         super.onConfigurationChanged(newConfig)
 264:         if (!attached) return
 265:         val view = edge ?: return
 266:         val manager = wm ?: return
 267:         val params = lp ?: return
 268:         view.setTipRadius(readTipRadius())
 269:         view.collapseImmediately()
 270:         resizeWindow(view, manager, params)
 271:     }
 272: 
 273:     override fun onUnbind(intent: Intent?): Boolean {
 274:         teardown()
 275:         return super.onUnbind(intent)
 276:     }
 277: 
 278:     override fun onDestroy() {
 279:         teardown()
 280:         super.onDestroy()
 281:     }
 282: 
 283:     // ------------------------------------------------------------ EdgeView.Host
 284: 
 285:     override fun onSettled(expanded: Boolean) {
 286:         // The window never resizes while opening or closing.
 287:     }
 288: 
 289:     override fun onRegionChanged(region: Region) {
 290:         touch?.set(region)
 291:     }
 292: 
 293:     override fun setWindowFocusable(on: Boolean) {
 294:         val view = edge ?: return
 295:         val params = lp ?: return
 296:         val manager = wm ?: return
 297:         val has = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
 298:         if (has == on) return
 299:         params.flags = if (on) {
 300:             params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
 301:         } else {
 302:             params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
 303:         }
 304:         try {
 305:             manager.updateViewLayout(view, params)
 306:         } catch (e: Throwable) {
 307:         }
 308:     }
 309: 
 310:     // ------------------------------------------------------------ helpers
 311: 
 312:     private fun updateHidden() {
 313:         val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
 314:         val locked = km?.isKeyguardLocked ?: false
 315:         edge?.setHidden(imeVisible || locked || screenOff)
 316:     }
 317: 
 318:     private fun readTipRadius(): Float {
 319:         if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0f
 320:         val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return 0f
 321:         val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return 0f
 322:         return display.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius?.toFloat() ?: 0f
 323:     }
 324: 
 325:     private fun teardown() {
 326:         if (receiverRegistered) {
 327:             receiverRegistered = false
 328:             try {
 329:                 unregisterReceiver(receiver)
 330:             } catch (e: Throwable) {
 331:             }
 332:         }
 333:         if (prefListenerRegistered) {
 334:             prefListenerRegistered = false
 335:             try {
 336:                 Prefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
 337:             } catch (e: Throwable) {
 338:             }
 339:         }
 340:         mainHandler.removeCallbacks(attachRunnable)
 341:         attached = false
 342:         edge?.removeCallbacks(sizesRunnable)
 343:         mainHandler.removeCallbacks(chargingRemove)
 344:         val view = edge ?: return
 345:         edge = null
 346:         touch?.uninstall()
 347:         touch = null
 348:         view.host = null
 349:         view.setService(null)
 350:         view.release()
 351:         try {
 352:             if (view.isAttachedToWindow) wm?.removeView(view)
 353:         } catch (e: Throwable) {
 354:         }
 355:         lp = null
 356:     }
 357: }
===== EdgeView.kt =====
   1: package com.dynedge
   2: 
   3: import android.accessibilityservice.AccessibilityService
   4: import android.app.PendingIntent
   5: import android.content.BroadcastReceiver
   6: import android.content.Context
   7: import android.content.IntentFilter
   8: import android.os.BatteryManager
   9: import android.os.SystemClock
  10: import android.text.format.DateFormat
  11: import java.util.Calendar
  12: import android.content.Intent
  13: import android.graphics.Bitmap
  14: import android.graphics.BitmapShader
  15: import android.graphics.Canvas
  16: import android.graphics.Color
  17: import android.graphics.Matrix
  18: import android.graphics.Paint
  19: import android.graphics.Path
  20: import android.graphics.Rect
  21: import android.graphics.RectF
  22: import android.graphics.RenderNode
  23: import android.graphics.Region
  24: import android.graphics.Shader
  25: import android.graphics.Typeface
  26: import android.graphics.drawable.Drawable
  27: import android.text.TextPaint
  28: import android.text.TextUtils
  29: import android.util.SparseArray
  30: import android.util.TypedValue
  31: import android.view.Choreographer
  32: import android.view.HapticFeedbackConstants
  33: import android.view.MotionEvent
  34: import android.view.VelocityTracker
  35: import android.view.View
  36: import android.view.ViewConfiguration
  37: import kotlin.math.abs
  38: import kotlin.math.asin
  39: import kotlin.math.atan2
  40: import kotlin.math.ceil
  41: import kotlin.math.cos
  42: import kotlin.math.max
  43: import kotlin.math.min
  44: import kotlin.math.sin
  45: import kotlin.math.sqrt
  46: 
  47: 
  48: /**
  49:  * The Dynamic Edge host view: shape, springs, paging, section layers and touch dispatch.
  50:  * The view fills its window and everything is anchored to the bottom-right corner.
  51:  *
  52:  * The open state is a true quarter circle of radius R centred on the corner. Each window (WinQuick, WinMedia,
  53:  * WinClipboard, WinApps, WinVolume, WinTimer, WinNotifications, WinDevice) lays out, draws and hit-tests its own
  54:  * content in polar coordinates around the corner; the list and order come from WindowsConfig. Windows are
  55:  * rotated around the corner when paging and cached as RenderNode layers (re-recorded only when dirty).
  56:  * To add a window: subclass Win, register it in make(), add its id to WindowsConfig.
  57:  * When closed, the bean can morph into a live activity (LiveBus): call, timer, progress, charging, track change.
  58:  */
  59: class EdgeView(ctx: Context) : View(ctx) {
  60: 
  61:     interface Host {
  62:         fun onSettled(expanded: Boolean)
  63:         fun onRegionChanged(region: Region)
  64:         /** Makes the overlay window focusable (true) or not focusable (false); used for a short clipboard capture. */
  65:         fun setWindowFocusable(on: Boolean)
  66:     }
  67: 
  68:     var host: Host? = null
  69: 
  70:     // ---------------------------------------------------------------- metrics
  71:     private val density = resources.displayMetrics.density
  72:     internal fun dp(v: Float) = v * density
  73:     internal fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
  74: 
  75:     private var idle = dp(Prefs.idleDp(ctx))
  76:     internal var rad = dp(Prefs.openDp(ctx)) // R, radius of the open quarter circle
  77:         private set
  78:     private var tipPx = 0f
  79:     private var flarePx = dp(Prefs.flareDp(ctx)) // edge fillet radius (open size)
  80: 
  81:     val idleSizePx: Int get() = ceil(idle).toInt()
  82:     val maxOpenWidthPx: Int get() = ceil(rad + flarePx).toInt()
  83:     val maxOpenHeightPx: Int get() = ceil(rad + flarePx).toInt()
  84: 
  85:     // ---------------------------------------------------------------- state
  86:     private val wS = Spring(idle, 420f, 0.74f)
  87:     private val hS = Spring(idle, 300f, 0.78f)
  88:     private val pageS = Spring(0f, 380f, 0.86f)
  89:     private val press = Spring(1f, 700f, 0.75f)
  90: 
  91:     private var page = 0
  92:     private var lastPageId = "quick"
  93:     private var openPicked = false
  94:     private var userPaged = false
  95:     private var pageStart = 0f
  96:     private var dragShift = 0f
  97: 
  98:     private var progress = 0f
  99:     private var contentAlpha = 0f
 100:     private var collapsing = true
 101:     private var expandedTarget = false
 102:     private var settledExpanded = false
 103:     private var regionExpanded = false
 104:     private var regionClosed = idle
 105:     private var hidden = false
 106:     internal var sourcesStarted = false
 107:         private set
 108: 
 109:     val isExpanded: Boolean get() = expandedTarget
 110: 
 111:     /** The accessibility service: needed by global-action tiles and by clipboard paste. */
 112:     internal var service: AccessibilityService? = null
 113:         private set
 114: 
 115:     fun setService(s: AccessibilityService?) {
 116:         service = s
 117:         (winMap["quick"] as? WinQuick)?.setService(s)
 118:     }
 119: 
 120:     // ---------------------------------------------------------------- windows and sources
 121:     private val winMap = HashMap<String, Win>()
 122:     private var order = ArrayList<Win>()      // enabled windows in display order
 123:     private var nWin = 1
 124:     private var shownWin: Win? = null
 125:     internal val arcs = Arcs(ctx) { onArcsChanged() }
 126:     private var arcsOn = false
 127: 
 128:     private fun make(id: String): Win = when (id) {
 129:         "media" -> WinMedia(this)
 130:         "clipboard" -> WinClipboard(this)
 131:         "apps" -> WinApps(this)
 132:         "volume" -> WinVolume(this)
 133:         "timer" -> WinTimer(this)
 134:         "notifications" -> WinNotifications(this)
 135:         "device" -> WinDevice(this)
 136:         else -> WinQuick(this)
 137:     }
 138: 
 139:     private fun indexOfId(id: String): Int {
 140:         for (i in order.indices) if (order[i].id == id) return i
 141:         return -1
 142:     }
 143: 
 144:     private val mediaWin: WinMedia? get() = order.firstOrNull { it is WinMedia } as? WinMedia
 145: 
 146:     private fun startWin(w: Win) {
 147:         if (w.started) return
 148:         w.started = true
 149:         w.start()
 150:     }
 151: 
 152:     private fun stopWin(w: Win) {
 153:         if (!w.started) return
 154:         w.started = false
 155:         w.stop()
 156:     }
 157: 
 158:     private fun syncArcs() {
 159:         val need = sourcesStarted && order.any { it is WinQuick || it is WinVolume }
 160:         if (need == arcsOn) return
 161:         arcsOn = need
 162:         if (need) arcs.start() else arcs.stop()
 163:     }
 164: 
 165:     private fun onArcsChanged() {
 166:         if (!sourcesStarted) return
 167:         for (w in order) w.onArcs()
 168:         invalidate()
 169:     }
 170: 
 171:     /** The visible, settled window gets onShown(true); everything else false. */
 172:     private fun syncShown() {
 173:         val want: Win? = if (expandedTarget && settledExpanded && !hidden && screenOn) order.getOrNull(page) else null
 174:         if (want === shownWin) return
 175:         shownWin?.onShown(false)
 176:         shownWin = want
 177:         want?.onShown(true)
 178:     }
 179: 
 180:     /** Re-reads WindowsConfig: keeps the current window if still enabled, else the last one by id. */
 181:     internal fun reloadWindows() {
 182:         val ids = WindowsConfig.enabled(context)
 183:         val curId = order.getOrNull(page)?.id
 184:         val next = ArrayList<Win>(ids.size)
 185:         for (id in ids) next.add(winMap.getOrPut(id) { make(id) })
 186:         val old = order
 187:         for (w in old) {
 188:             if (w !in next) {
 189:                 if (shownWin === w) {
 190:                     w.onShown(false)
 191:                     shownWin = null
 192:                 }
 193:                 stopWin(w)
 194:                 w.node?.discardDisplayList()
 195:                 w.node = null
 196:                 w.dirty = true
 197:             }
 198:         }
 199:         order = next
 200:         nWin = next.size
 201:         for (i in next.indices) next[i].index = i
 202:         (winMap["quick"] as? WinQuick)?.setService(service)
 203:         val want = if (curId != null && next.any { it.id == curId }) curId else lastPageId
 204:         val wi = indexOfId(want)
 205:         page = if (wi < 0) 0 else wi
 206:         lastPageId = order[page].id
 207:         pageS.snapTo(page.toFloat())
 208:         layoutAll()
 209:         if (sourcesStarted) {
 210:             syncArcs()
 211:             for (w in next) startWin(w)
 212:         } else {
 213:             syncArcs()
 214:         }
 215:         syncShown()
 216:         invalidate()
 217:     }
 218: 
 219:     internal fun relayout() {
 220:         layoutAll()
 221:         invalidate()
 222:     }
 223: 
 224:     /** Animates to the window with this id; false if it is not enabled. */
 225:     internal fun jumpTo(id: String): Boolean {
 226:         val i = indexOfId(id)
 227:         if (i < 0) return false
 228:         userPaged = true
 229:         goToPage(i, 0f)
 230:         return true
 231:     }
 232:     private val path = Path()
 233:     private val regionPath = Path()
 234:     private val arcRect = RectF()
 235:     private val region = Region()
 236:     private val clipRegion = Region()
 237:     private val hitRegion = Region()
 238: 
 239:     // Looks (from Prefs, see applyLooksNow). Shaders are immutable at radius GR; the shape path is drawn in
 240:     // gradient-unit space under a canvas scale (never mutate a shader's local matrix per frame).
 241:     internal var accent = C_RED
 242:     private var gradientPct = 35
 243:     private var rimOn = true
 244:     private var glowOn = true
 245:     internal var bandsOn = false
 246:     private var hapticsOn = true
 247:     private var stiffScale = 1f
 248:     private var openDamp = 0.8f
 249:     private var idleStyleId = 1
 250:     private var vizOn = true
 251:     private var liveOn = true
 252:     private var beatOn = true
 253:     private var musicPlaying = false
 254:     private var beat = 0f
 255:     private val exclRect = Rect()
 256:     private val exclList = ArrayList<Rect>(1).apply { add(exclRect) }
 257:     private var exclSet = false
 258:     private var shapeShader: Shader = buildShapeShader()
 259:     private var glowShader: Shader = buildGlowShader()
 260: 
 261:     private fun mixColor(a: Int, b: Int, t: Float): Int {
 262:         val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t + 0.5f).toInt()
 263:         val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t + 0.5f).toInt()
 264:         val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t + 0.5f).toInt()
 265:         return Color.rgb(r, g, bl)
 266:     }
 267: 
 268:     private fun buildShapeShader(): Shader {
 269:         val warm = mixColor(0xFF2E1B1A.toInt(), accent or (0xFF shl 24), 0.25f)
 270:         val core = mixColor(Color.BLACK, warm, gradientPct / 100f)
 271:         return android.graphics.RadialGradient(
 272:             0f, 0f, GR,
 273:             intArrayOf(core, mixColor(Color.BLACK, core, 0.55f), mixColor(Color.BLACK, core, 0.24f), Color.BLACK),
 274:             floatArrayOf(0f, 0.38f, 0.72f, 1f), Shader.TileMode.CLAMP
 275:         )
 276:     }
 277: 
 278:     private fun buildGlowShader(): Shader {
 279:         val rgb = accent and 0xFFFFFF
 280:         return android.graphics.RadialGradient(
 281:             0f, 0f, GR,
 282:             intArrayOf((0x88 shl 24) or rgb, (0x30 shl 24) or rgb, rgb),
 283:             floatArrayOf(0f, 0.38f, 0.85f), Shader.TileMode.CLAMP
 284:         )
 285:     }
 286:     private val unitPath = Path()
 287:     private var gradScale = 1f
 288:     private val gradMatrix = Matrix()
 289:     private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
 290:         style = Paint.Style.FILL
 291:         shader = shapeShader
 292:     }
 293:     private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
 294:         style = Paint.Style.FILL
 295:         shader = glowShader
 296:     }
 297:     private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
 298:         style = Paint.Style.STROKE
 299:         strokeWidth = dp(1f)
 300:         strokeCap = Paint.Cap.ROUND
 301:     }
 302:     internal val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
 303:         style = Paint.Style.STROKE
 304:         strokeWidth = dp(1.5f)
 305:         color = C_RED
 306:     }
 307:     internal val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
 308:         style = Paint.Style.FILL_AND_STROKE
 309:         strokeJoin = Paint.Join.ROUND
 310:         strokeWidth = dp(2f)
 311:         color = Color.WHITE
 312:     }
 313:     internal val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
 314:         style = Paint.Style.STROKE
 315:         strokeCap = Paint.Cap.ROUND
 316:     }
 317:     internal val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
 318:         style = Paint.Style.STROKE
 319:         strokeCap = Paint.Cap.ROUND
 320:     }
 321:     private val rimPath = Path()
 322:     private var glow = 0f
 323:     private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
 324:     private val liveTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
 325:         color = Color.WHITE
 326:         textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
 327:         typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
 328:     }
 329:     private val whiteIcons = SparseArray<Drawable>()
 330:     private val blackIcons = SparseArray<Drawable>()
 331:     private var resourcesReady = false
 332:     private var lpX = 0f
 333:     private var lpY = 0f
 334:     private val sb = StringBuilder(8)
 335: 
 336:     internal val fillP: Paint get() = fill
 337:     internal val ovalScratch = RectF()
 338:     private val xOval = RectF()
 339:     private var xCx = 0f
 340:     private var xCy = 0f
 341:     private var xRad = 0f
 342:     private var xHit = 0f
 343:     private var lastFl = -1f
 344: 
 345:     // ---------------------------------------------------------------- touch state
 346:     private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
 347:     private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
 348:     private var vt: VelocityTracker? = null
 349:     private var gestureActive = false
 350:     private var startMode = MODE_IDLE
 351:     private var dragMode = 0 // 0 none, 1 expanding from idle, 2 shrinking (close), 3 paging
 352:     private var downRawX = 0f
 353:     private var downRawY = 0f
 354:     private var baseW = 0f
 355:     private var baseH = 0f
 356:     private var moved = false
 357:     /** Pressed item: local id inside [pressedWin], or ID_X (pressedWin == null) for the close button. */
 358:     internal var pressed = -1
 359:         private set
 360:     internal var pressedWin: Win? = null
 361:         private set
 362:     private var hitWin: Win? = null
 363:     private var captureWin: Win? = null
 364:     private var longFired = false
 365: 
 366:     private fun setPressed(w: Win?, item: Int) {
 367:         if (pressed == item && pressedWin === w) return
 368:         pressedWin?.dirty = true
 369:         w?.dirty = true
 370:         pressed = item
 371:         pressedWin = w
 372:     }
 373: 
 374:     private fun clearPressed() = setPressed(null, -1)
 375: 
 376:     private val longPressRunnable = Runnable { onLongPress() }
 377: 
 378:     // ---------------------------------------------------------------- live activities
 379:     private var liveCur: LiveActivity? = null
 380:     private var liveSec: LiveActivity? = null
 381:     private var liveTitleRaw: String? = null
 382:     private var liveTitleShown: String = ""
 383:     private var liveTitleOff = 0f
 384:     private var liveTitleLen = 0f
 385:     private var liveCallLen = 0f
 386:     private var liveTitleKey: String? = null
 387:     private var liveTitleCall = false
 388:     private var liveTotalMs = 0L
 389:     private val liveTitlePath = Path()
 390:     private val liveTitlePathCall = Path()
 391:     private val liveOval = RectF()
 392:     private val liveIconRect = Rect()
 393:     private val liveAnsRect = Rect()
 394:     private val liveDecRect = Rect()
 395:     private var liveAnsX = 0f
 396:     private var liveAnsY = 0f
 397:     private var liveDecX = 0f
 398:     private var liveDecY = 0f
 399:     private var liveDotR = 0f
 400:     private var lastLiveTrack: String? = null
 401:     private val transientRunnables = HashMap<String, Runnable>()
 402: 
 403:     private val liveSize: Float get() = idle * LIVE_SCALE
 404:     private val liveWanted: Boolean get() = liveOn && liveCur != null && screenOn && !hidden
 405:     private fun closedSize(): Float = if (liveWanted) liveSize else idle
 406: 
 407:     private val liveTickRunnable = object : Runnable {
 408:         override fun run() {
 409:             val a = liveCur
 410:             if (!liveWanted || expandedTarget || a == null) return
 411:             if (a.chronoBase != 0L) rebuildLiveTitle()
 412:             invalidateBean()
 413:             val next = if (a.progress == -2f) 40L else if (a.chronoBase != 0L) 1000L else 0L
 414:             if (next > 0L) postDelayed(this, next)
 415:         }
 416:     }
 417:     // ---------------------------------------------------------------- frame loop
 418:     private var framePosted = false
 419:     private var lastFrameNanos = 0L
 420:     private val frameCb = Choreographer.FrameCallback { t -> doFrame(t) }
 421: 
 422:     // ---------------------------------------------------------------- idle designs / visualizer
 423:     var holdOpen = false // set by the service while the settings screen previews the open size
 424:     private var screenOn = false
 425:     private var clockStr = ""
 426:     private var battFrac = 1f
 427:     private var charging = false
 428:     private var clockReg = false
 429:     private var powerReg = false
 430:     private var mediaWatch: IdleMediaWatch? = null
 431:     private var idlePlaying = false
 432:     private val visualizer = IdleVisualizer(ctx)
 433:     private var vizRunning = false
 434:     private var vizPosted = false
 435:     private var lastVizNanos = 0L
 436:     private var beanPx = 0
 437:     private var looksPosted = false
 438:     private val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
 439:     private val timeFilter = IntentFilter().apply {
 440:         addAction(Intent.ACTION_TIME_TICK)
 441:         addAction(Intent.ACTION_TIME_CHANGED)
 442:         addAction(Intent.ACTION_TIMEZONE_CHANGED)
 443:     }
 444:     private val powerFilter = IntentFilter().apply {
 445:         addAction(Intent.ACTION_POWER_CONNECTED)
 446:         addAction(Intent.ACTION_POWER_DISCONNECTED)
 447:     }
 448:     private val timeReceiver = object : BroadcastReceiver() {
 449:         override fun onReceive(context: Context?, intent: Intent?) {
 450:             updateClock()
 451:             invalidateBean()
 452:         }
 453:     }
 454:     private val powerReceiver = object : BroadcastReceiver() {
 455:         override fun onReceive(context: Context?, intent: Intent?) {
 456:             readBattery()
 457:             invalidateBean()
 458:         }
 459:     }
 460:     private val vizCb = Choreographer.FrameCallback { t -> vizFrame(t) }
 461:     private val looksRunnable = Runnable {
 462:         looksPosted = false
 463:         applyLooksNow()
 464:     }
 465:     private val idleRect = RectF()
 466:     private val boltPath = Path()
 467:     private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
 468:         style = Paint.Style.STROKE
 469:         strokeCap = Paint.Cap.ROUND
 470:     }
 471:     private val clockPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
 472:         color = Color.WHITE
 473:         textAlign = Paint.Align.CENTER
 474:         typeface = Typeface.DEFAULT_BOLD
 475:     }
 476: 
 477:     // ================================================================ public API
 478: 
 479:     fun expand() = startExpand(false, 0f, 0f)
 480: 
 481:     fun collapse() = startCollapse(false, 0f, 0f)
 482: 
 483:     private fun cancelCapture() {
 484:         val cw = captureWin ?: return
 485:         captureWin = null
 486:         cw.dragEnd(true)
 487:     }
 488: 
 489:     fun collapseImmediately() {
 490:         if (framePosted) {
 491:             Choreographer.getInstance().removeFrameCallback(frameCb)
 492:             framePosted = false
 493:         }
 494:         lastFrameNanos = 0L
 495:         cancelItemLongPress()
 496:         removeCallbacks(liveTickRunnable)
 497:         releaseTracker()
 498:         gestureActive = false
 499:         dragMode = 0
 500:         cancelCapture()
 501:         clearPressed()
 502:         expandedTarget = false
 503:         collapsing = true
 504:         settledExpanded = false
 505:         syncShown()
 506:         openPicked = false
 507:         val cs = closedSize()
 508:         wS.snapTo(cs)
 509:         hS.snapTo(cs)
 510:         pageS.snapTo(page.toFloat())
 511:         press.snapTo(1f)
 512:         progress = 0f
 513:         contentAlpha = 0f
 514:         stopSources()
 515:         regionExpanded = false
 516:         regionClosed = cs
 517:         publishRegion()
 518:         host?.onSettled(false)
 519:         updateViz()
 520:         syncLiveTick()
 521:         invalidate()
 522:     }
 523: 
 524:     /**
 525:      * Applies new idle size / open radius (dp) from the settings app. Keeps the current state: an open
 526:      * widget stays open at the new size (springs snapped), a closed one stays closed.
 527:      */
 528:     fun applySizes(idleDp: Float, openDp: Float) {
 529:         if (gestureActive || dragMode != 0) {
 530:             cancelItemLongPress()
 531:             releaseTracker()
 532:             gestureActive = false
 533:             dragMode = 0
 534:             cancelCapture()
 535:             clearPressed()
 536:         }
 537:         idle = dp(idleDp.coerceIn(32f, 80f))
 538:         rad = dp(openDp.coerceIn(170f, 300f))
 539:         layoutAll()
 540:         val cs = closedSize()
 541:         if (expandedTarget) {
 542:             wS.snapTo(rad)
 543:             hS.snapTo(rad)
 544:         } else {
 545:             wS.snapTo(cs)
 546:             hS.snapTo(cs)
 547:             regionClosed = cs
 548:         }
 549:         wS.target = if (expandedTarget) rad else cs
 550:         hS.target = wS.target
 551:         updateDerived(0f)
 552:         publishRegion()
 553:         updateViz()
 554:         invalidate()
 555:     }
 556: 
 557:     /** Re-reads the edge flare radius (Prefs.KEY_FLARE); the service then resizes the window. */
 558:     fun applyFlare() {
 559:         flarePx = dp(Prefs.flareDp(context))
 560:         lastDw = -1f
 561:         publishRegion()
 562:         invalidate()
 563:     }
 564: 
 565:     /** Re-reads the looks and feel prefs (coalesced to one pass per frame). */
 566:     fun applyLooks() {
 567:         if (looksPosted) return
 568:         looksPosted = true
 569:         postOnAnimation(looksRunnable)
 570:     }
 571: 
 572:     /** Re-reads the QUICK layout (QuickConfig.KEY changed). */
 573:     fun reloadQuick() {
 574:         (winMap["quick"] as? WinQuick)?.reload()
 575:     }
 576: 
 577:     fun setScreenOn(on: Boolean) {
 578:         if (screenOn == on) return
 579:         screenOn = on
 580:         syncIdleServices()
 581:         onLiveChanged()
 582:         syncShown()
 583:     }
 584: 
 585:     fun setTipRadius(px: Float) {
 586:         tipPx = px
 587:         publishRegion()
 588:         invalidate()
 589:     }
 590: 
 591:     fun setHidden(h: Boolean) {
 592:         if (hidden == h) return
 593:         hidden = h
 594:         if (h) {
 595:             collapseImmediately()
 596:             animate().alpha(0f).setDuration(150L).start()
 597:         } else {
 598:             animate().alpha(1f).setDuration(150L).start()
 599:         }
 600:         publishRegion()
 601:         updateViz()
 602:         onLiveChanged()
 603:     }
 604: 
 605:     fun currentRegion(): Region = region
 606: 
 607:     /** Stops listeners and callbacks; call before removing the view for good. */
 608:     fun release() {
 609:         if (framePosted) {
 610:             Choreographer.getInstance().removeFrameCallback(frameCb)
 611:             framePosted = false
 612:         }
 613:         cancelItemLongPress()
 614:         removeCallbacks(liveTickRunnable)
 615:         removeCallbacks(captureTimeout)
 616:         for (r in transientRunnables.values) removeCallbacks(r)
 617:         transientRunnables.clear()
 618:         releaseTracker()
 619:         expandedTarget = false
 620:         settledExpanded = false
 621:         syncShown()
 622:         stopSources()
 623:         removeCallbacks(looksRunnable)
 624:         screenOn = false
 625:         syncIdleServices()
 626:         mediaWatch?.stop()
 627:         mediaWatch = null
 628:         visualizer.stop()
 629:         LiveBus.onChange = null
 630:         service = null
 631:         (winMap["quick"] as? WinQuick)?.setService(null)
 632:     }
 633: 
 634:     // ================================================================ view plumbing
 635: 
 636:     override fun hasOverlappingRendering(): Boolean = false
 637: 
 638:     override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
 639:         publishRegion()
 640:     }
 641: 
 642:     override fun onAttachedToWindow() {
 643:         super.onAttachedToWindow()
 644:         LiveBus.onChange = { onLiveChanged() }
 645:         onLiveChanged()
 646:     }
 647: 
 648:     override fun onDetachedFromWindow() {
 649:         super.onDetachedFromWindow()
 650:         if (framePosted) {
 651:             Choreographer.getInstance().removeFrameCallback(frameCb)
 652:             framePosted = false
 653:         }
 654:         removeCallbacks(liveTickRunnable)
 655:         removeCallbacks(longPressRunnable)
 656:         // the display lists die with the window: force a re-record when it comes back
 657:         for (w in order) w.dirty = true
 658:     }
 659: 
 660:     override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
 661:         super.onWindowFocusChanged(hasWindowFocus)
 662:         if (hasWindowFocus && captureWanted) {
 663:             captureWanted = false
 664:             removeCallbacks(captureTimeout)
 665:             try {
 666:                 ClipPaste.capture(context)
 667:             } catch (e: Throwable) {
 668:             }
 669:             host?.setWindowFocusable(false)
 670:         }
 671:     }
 672: 
 673:     // ---------------------------------------------------------------- clipboard capture / helpers for windows
 674:     private var captureWanted = false
 675:     private var captureLast = 0L
 676:     private val captureTimeout = Runnable {
 677:         if (captureWanted) {
 678:             captureWanted = false
 679:             host?.setWindowFocusable(false)
 680:         }
 681:     }
 682: 
 683:     /** Asks the service to make the window focusable for one focus change so the clipboard can be read. */
 684:     internal fun requestCapture() {
 685:         val now = SystemClock.uptimeMillis()
 686:         if (captureWanted || now - captureLast < 1500L) return
 687:         captureLast = now
 688:         captureWanted = true
 689:         host?.setWindowFocusable(true)
 690:         removeCallbacks(captureTimeout)
 691:         postDelayed(captureTimeout, 300L)
 692:     }
 693: 
 694:     internal fun showCopied() {
 695:         putTransient(
 696:             LiveActivity(
 697:                 "copied", LiveActivity.Kind.PROGRESS, "Copied", -1f, 0L, false,
 698:                 R.drawable.ic_clipboard, false, null, null, null
 699:             ), 1800L
 700:         )
 701:     }
 702: 
 703:     internal fun openClipboard() {
 704:         try {
 705:             val i = Intent(context, ClipboardActivity::class.java)
 706:             i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
 707:             context.startActivity(i)
 708:         } catch (e: Exception) {
 709:         }
 710:     }
 711:     // ================================================================ looks
 712: 
 713:     private fun applyLooksNow() {
 714:         val c = context
 715:         accent = Prefs.accent(c)
 716:         gradientPct = Prefs.prefs(c).getInt(Prefs.KEY_GRADIENT, 35).coerceIn(0, 100)
 717:         rimOn = Prefs.rim(c)
 718:         glowOn = Prefs.glow(c)
 719:         bandsOn = Prefs.bands(c)
 720:         hapticsOn = Prefs.haptics(c)
 721:         stiffScale = Prefs.stiffnessScale(c)
 722:         openDamp = Prefs.openDamping(c)
 723:         idleStyleId = when (Prefs.idleStyle(c)) {
 724:             "none" -> 0
 725:             "meter" -> 3
 726:             "pill", "slits", "grid" -> 2
 727:             else -> 1
 728:         } // LOOKS hook: replace with the new idle designs
 729:         vizOn = Prefs.visualizer(c)
 730:         liveOn = Prefs.live(c)
 731:         beatOn = Prefs.beat(c)
 732:         shapeShader = buildShapeShader()
 733:         glowShader = buildGlowShader()
 734:         shapePaint.shader = shapeShader
 735:         glowPaint.shader = glowShader
 736:         ringPaint.color = accent
 737:         press.stiffness = 700f * stiffScale
 738:         for (w in order) w.dirty = true
 739:         syncIdleServices()
 740:         onLiveChanged()
 741:         invalidate()
 742:     }
 743: 
 744:     internal fun haptic(kind: Int) {
 745:         if (hapticsOn) performHapticFeedback(kind)
 746:     }
 747: 
 748:     // ================================================================ idle services
 749: 
 750:     private fun invalidateBean() {
 751:         invalidate(width - beanPx, height - beanPx, width, height)
 752:     }
 753: 
 754:     private fun syncIdleServices() {
 755:         val wantClock = screenOn && idleStyleId == 4
 756:         if (wantClock != clockReg) {
 757:             clockReg = wantClock
 758:             try {
 759:                 if (wantClock) context.registerReceiver(timeReceiver, timeFilter)
 760:                 else context.unregisterReceiver(timeReceiver)
 761:             } catch (e: Throwable) {
 762:             }
 763:         }
 764:         if (wantClock) updateClock()
 765:         val wantPower = screenOn && idleStyleId == 3
 766:         if (wantPower != powerReg) {
 767:             powerReg = wantPower
 768:             try {
 769:                 if (wantPower) context.registerReceiver(powerReceiver, powerFilter)
 770:                 else context.unregisterReceiver(powerReceiver)
 771:             } catch (e: Throwable) {
 772:             }
 773:         }
 774:         if (wantPower) readBattery()
 775:         // The light media watch serves the visualizer and the track-change live activity.
 776:         if (screenOn && (vizOn || liveOn || beatOn)) {
 777:             var w = mediaWatch
 778:             if (w == null) {
 779:                 w = IdleMediaWatch(context) { p ->
 780:                     musicPlaying = p
 781:                     idlePlaying = p && vizOn
 782:                     updateViz()
 783:                     invalidateBean()
 784:                 }
 785:                 w.onTrack = { t -> onTrackChanged(t) }
 786:                 mediaWatch = w
 787:             }
 788:             w.start()
 789:             musicPlaying = w.playing
 790:             idlePlaying = w.playing && vizOn
 791:         } else {
 792:             mediaWatch?.stop()
 793:             musicPlaying = false
 794:             idlePlaying = false
 795:         }
 796:         updateViz()
 797:         invalidateBean()
 798:     }
 799: 
 800:     private fun updateClock() {
 801:         val cal = Calendar.getInstance()
 802:         var h = cal.get(Calendar.HOUR_OF_DAY)
 803:         val m = cal.get(Calendar.MINUTE)
 804:         val h24 = DateFormat.is24HourFormat(context)
 805:         if (!h24) {
 806:             h %= 12
 807:             if (h == 0) h = 12
 808:         }
 809:         clockStr = (if (h < 10 && h24) "0" else "") + h + ":" + (if (m < 10) "0" else "") + m
 810:     }
 811: 
 812:     private fun readBattery() {
 813:         val i = try {
 814:             context.registerReceiver(null, batteryFilter)
 815:         } catch (e: Throwable) {
 816:             null
 817:         } ?: return
 818:         val lv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
 819:         val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
 820:         battFrac = if (lv >= 0 && sc > 0) (lv.toFloat() / sc).coerceIn(0f, 1f) else 1f
 821:         charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
 822:     }
 823: 
 824:     /** Visualizer frames run only while playing && closed && screen on && enabled && no live activity. */
 825:     private fun updateViz() {
 826:         val barsWant = idlePlaying && !expandedTarget && !gestureActive && dragMode == 0 && !liveWanted
 827:         val beatWant = beatOn && musicPlaying
 828:         val want = screenOn && !hidden && (barsWant || beatWant)
 829:         if (want == vizRunning) return
 830:         vizRunning = want
 831:         if (want) {
 832:             visualizer.start()
 833:             lastVizNanos = 0L
 834:             if (!vizPosted) {
 835:                 vizPosted = true
 836:                 Choreographer.getInstance().postFrameCallbackDelayed(vizCb, 25L)
 837:             }
 838:         } else {
 839:             visualizer.stop()
 840:             beat = 0f
 841:             if (vizPosted) {
 842:                 Choreographer.getInstance().removeFrameCallback(vizCb)
 843:                 vizPosted = false
 844:             }
 845:         }
 846:     }
 847: 
 848:     private fun vizFrame(t: Long) {
 849:         vizPosted = false
 850:         if (!vizRunning) return
 851:         val dtN = t - lastVizNanos
 852:         if (lastVizNanos == 0L || dtN >= 31_000_000L) {
 853:             val dt = if (lastVizNanos == 0L) 0.033f else (dtN * 1e-9f).coerceAtMost(0.1f)
 854:             lastVizNanos = t
 855:             visualizer.tick(dt, t / 1_000_000L)
 856:             if (beatOn) beat += (visualizer.levels[0].coerceIn(0f, 1f) - beat) * 0.4f
 857:             if (expandedTarget || dragMode != 0) invalidate() else invalidateBean()
 858:         }
 859:         vizPosted = true
 860:         Choreographer.getInstance().postFrameCallbackDelayed(vizCb, 25L)
 861:     }
 862: 
 863:     private fun drawIdle(c: Canvas, ia: Float) {
 864:         val sc = idle / dp(48f)
 865:         c.save()
 866:         c.translate(width.toFloat(), height.toFloat())
 867:         val ps = press.value
 868:         c.scale(ps, ps)
 869:         val cen = -0.4244f * idle
 870:         if (vizOn && idlePlaying) {
 871:             val bw = dp(2.5f) * sc
 872:             val pitch = dp(4.5f) * sc
 873:             val minH = dp(3f) * sc
 874:             val maxH = 0.34f * idle
 875:             val lv = visualizer.levels
 876:             for (i in 0 until 4) {
 877:                 val h = minH + (maxH - minH) * lv[i]
 878:                 val x = cen + (i - 1.5f) * pitch
 879:                 fill.color = if (i == 2) accent else Color.WHITE
 880:                 fill.alpha = (255f * 0.85f * ia + 0.5f).toInt()
 881:                 c.drawRoundRect(x - bw / 2f, cen - h / 2f, x + bw / 2f, cen + h / 2f, bw / 2f, bw / 2f, fill)
 882:             }
 883:         } else when (idleStyleId) {
 884:             1 -> {
 885:                 fill.color = accent
 886:                 fill.alpha = (255f * ia + 0.5f).toInt()
 887:                 c.drawCircle(-0.30f * idle, -0.30f * idle, dp(2f) * sc, fill)
 888:             }
 889:             2 -> {
 890:                 val r = 0.70f * idle
 891:                 idleRect.set(-r, -r, r, r)
 892:                 idlePaint.strokeWidth = dp(1.4f) * sc
 893:                 idlePaint.color = C_CREAM
 894:                 idlePaint.alpha = (255f * 0.85f * ia + 0.5f).toInt()
 895:                 c.drawArc(idleRect, 180f + 12f, 66f, false, idlePaint)
 896:             }
 897:             3 -> {
 898:                 val r = 0.66f * idle
 899:                 idleRect.set(-r, -r, r, r)
 900:                 idlePaint.strokeWidth = dp(2.6f) * sc
 901:                 idlePaint.color = Color.WHITE
 902:                 idlePaint.alpha = (255f * 0.22f * ia + 0.5f).toInt()
 903:                 c.drawArc(idleRect, 180f + 10f, 70f, false, idlePaint)
 904:                 if (battFrac > 0.01f) {
 905:                     idlePaint.color = if (charging) Color.WHITE else accent
 906:                     idlePaint.alpha = (255f * 0.9f * ia + 0.5f).toInt()
 907:                     c.drawArc(idleRect, 180f + 10f, 70f * battFrac, false, idlePaint)
 908:                 }
 909:                 if (charging) {
 910:                     val u = 0.07f * idle
 911:                     boltPath.rewind()
 912:                     boltPath.moveTo(cen + 0.3f * u, cen - u)
 913:                     boltPath.lineTo(cen - 0.5f * u, cen + 0.1f * u)
 914:                     boltPath.lineTo(cen, cen + 0.1f * u)
 915:                     boltPath.lineTo(cen - 0.3f * u, cen + u)
 916:                     boltPath.lineTo(cen + 0.5f * u, cen - 0.1f * u)
 917:                     boltPath.lineTo(cen, cen - 0.1f * u)
 918:                     boltPath.close()
 919:                     fill.color = Color.WHITE
 920:                     fill.alpha = (255f * 0.85f * ia + 0.5f).toInt()
 921:                     c.drawPath(boltPath, fill)
 922:                 }
 923:             }
 924:             4 -> if (clockStr.isNotEmpty()) {
 925:                 clockPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics) * sc
 926:                 clockPaint.alpha = (255f * 0.85f * ia + 0.5f).toInt()
 927:                 c.drawText(clockStr, cen, cen - (clockPaint.ascent() + clockPaint.descent()) / 2f, clockPaint)
 928:             }
 929:         }
 930:         c.restore()
 931:     }
 932:     // ================================================================ live activities
 933: 
 934:     private fun onTrackChanged(title: String?) {
 935:         if (!liveOn || expandedTarget || !screenOn || hidden || title.isNullOrBlank()) return
 936:         if (title == lastLiveTrack) return
 937:         lastLiveTrack = title
 938:         putTransient(
 939:             LiveActivity(
 940:                 "media", LiveActivity.Kind.MEDIA, title.take(40), -1f, 0L, false,
 941:                 R.drawable.ic_music, false, null, null, null
 942:             ), 3000L
 943:         )
 944:     }
 945: 
 946:     /** Posts a transient activity and removes it after [ms]. */
 947:     private fun putTransient(a: LiveActivity, ms: Long) {
 948:         val old = transientRunnables.remove(a.key)
 949:         if (old != null) removeCallbacks(old)
 950:         val key = a.key
 951:         val r = Runnable {
 952:             transientRunnables.remove(key)
 953:             LiveBus.remove(key)
 954:         }
 955:         transientRunnables[key] = r
 956:         postDelayed(r, ms)
 957:         LiveBus.put(a)
 958:     }
 959: 
 960:     private fun scheduleTransient(a: LiveActivity?) {
 961:         if (a == null || a.ongoing || transientRunnables.containsKey(a.key)) return
 962:         val key = a.key
 963:         val ms = when (a.kind) {
 964:             LiveActivity.Kind.MEDIA -> 3000L
 965:             LiveActivity.Kind.CHARGING -> 3500L
 966:             else -> 4000L
 967:         }
 968:         val r = Runnable {
 969:             transientRunnables.remove(key)
 970:             LiveBus.remove(key)
 971:         }
 972:         transientRunnables[key] = r
 973:         postDelayed(r, ms)
 974:     }
 975: 
 976:     private fun onLiveChanged() {
 977:         val top = if (liveOn) LiveBus.top() else null
 978:         val sec = if (liveOn) LiveBus.second() else null
 979:         liveCur = top
 980:         liveSec = sec
 981:         scheduleTransient(top)
 982:         scheduleTransient(sec)
 983:         if (top?.key != liveTitleKey) {
 984:             liveTitleKey = top?.key
 985:             liveTitleRaw = null
 986:             liveTotalMs = 0L
 987:             if (top != null && top.countDown && top.chronoBase != 0L) {
 988:                 liveTotalMs = max(1000L, top.chronoBase - System.currentTimeMillis())
 989:             }
 990:         }
 991:         rebuildLiveTitle()
 992:         syncClosedSize()
 993:         updateViz()
 994:         syncLiveTick()
 995:         invalidateBean()
 996:     }
 997: 
 998:     /** Morphs the closed bean between idle and live size. */
 999:     private fun syncClosedSize() {
1000:         if (expandedTarget || gestureActive || dragMode != 0 || hidden) return
1001:         val tgt = closedSize()
1002:         if (wS.target == tgt && hS.target == tgt) return
1003:         if (tgt > regionClosed) {
1004:             regionClosed = tgt
1005:             publishRegion()
1006:         }
1007:         collapsing = true
1008:         wS.stiffness = 420f * stiffScale; wS.dampingRatio = openDamp
1009:         hS.stiffness = 300f * stiffScale; hS.dampingRatio = min(0.99f, openDamp + 0.04f)
1010:         wS.target = tgt
1011:         hS.target = tgt
1012:         requestFrames()
1013:     }
1014: 
1015:     private fun syncLiveTick() {
1016:         removeCallbacks(liveTickRunnable)
1017:         val a = liveCur ?: return
1018:         if (!liveWanted || expandedTarget) return
1019:         if (a.progress == -2f || a.chronoBase != 0L) postDelayed(liveTickRunnable, 40L)
1020:     }
1021: 
1022:     private fun fmtChrono(ms: Long): String {
1023:         val t = if (ms < 0L) 0L else ms / 1000L
1024:         sb.setLength(0)
1025:         val h = t / 3600L
1026:         val m = (t % 3600L) / 60L
1027:         val s = t % 60L
1028:         if (h > 0L) {
1029:             sb.append(h).append(':')
1030:             if (m < 10L) sb.append('0')
1031:         }
1032:         sb.append(m).append(':')
1033:         if (s < 10L) sb.append('0')
1034:         sb.append(s)
1035:         return sb.toString()
1036:     }
1037: 
1038:     private fun rebuildLiveTitle() {
1039:         val a = liveCur ?: return
1040:         val call = a.kind == LiveActivity.Kind.CALL
1041:         val raw = if (a.chronoBase != 0L) {
1042:             val now = System.currentTimeMillis()
1043:             fmtChrono(if (a.countDown) a.chronoBase - now else now - a.chronoBase)
1044:         } else {
1045:             a.title
1046:         }
1047:         if (raw == liveTitleRaw && call == liveTitleCall) return
1048:         liveTitleRaw = raw
1049:         liveTitleCall = call
1050:         liveTitleShown = TextUtils.ellipsize(
1051:             raw, liveTitlePaint, if (call) liveCallLen else liveTitleLen, TextUtils.TruncateAt.END
1052:         ).toString()
1053:         val avail = if (call) liveCallLen else liveTitleLen
1054:         liveTitleOff = (avail - liveTitlePaint.measureText(liveTitleShown)) / 2f
1055:     }
1056: 
1057:     private fun drawLive(c: Canvas) {
1058:         val a = liveCur ?: return
1059:         val s = liveSize
1060:         val w = min(wS.value, hS.value)
1061:         val f = ((w - idle) / max(s - idle, 1f)).coerceIn(0f, 1f)
1062:         val la = f * f * (3f - 2f * f) * (1f - progress * 4f).coerceIn(0f, 1f)
1063:         if (la <= 0.01f) return
1064:         val ai = (255f * la + 0.5f).toInt()
1065:         val call = a.kind == LiveActivity.Kind.CALL
1066:         val tint = if (call) C_GREEN else accent
1067:         c.save()
1068:         c.translate(width.toFloat(), height.toFloat())
1069:         val ps = press.value * (w / s).coerceIn(0.5f, 1.15f)
1070:         c.scale(ps, ps)
1071:         // progress arc along the curved rim
1072:         val r = s - dp(5f)
1073:         liveOval.set(-r, -r, r, r)
1074:         arcPaint.strokeWidth = dp(3f)
1075:         arcPaint.color = Color.WHITE
1076:         arcPaint.alpha = (255f * 0.14f * la + 0.5f).toInt()
1077:         c.drawArc(liveOval, 180f + SL_A0, SL_A1 - SL_A0, false, arcPaint)
1078:         arcPaint.color = tint
1079:         arcPaint.alpha = ai
1080:         if (a.progress == -2f) {
1081:             val head = ((SystemClock.uptimeMillis() % 1400L) / 1400f) * (SL_A1 - SL_A0 + 24f)
1082:             val st = max(0f, head - 24f)
1083:             val en = min(SL_A1 - SL_A0, head)
1084:             if (en > st) c.drawArc(liveOval, 180f + SL_A0 + st, en - st, false, arcPaint)
1085:         } else {
1086:             val fr = when {
1087:                 a.progress >= 0f -> a.progress
1088:                 a.chronoBase != 0L && a.countDown && liveTotalMs > 0L ->
1089:                     (a.chronoBase - System.currentTimeMillis()).toFloat() / liveTotalMs
1090:                 else -> -1f
1091:             }
1092:             if (fr > 0.002f) c.drawArc(liveOval, 180f + SL_A0, (SL_A1 - SL_A0) * fr.coerceAtMost(1f), false, arcPaint)
1093:         }
1094:         // icon at the centroid
1095:         if (a.iconRes != 0) {
1096:             val ic = icon(a.iconRes, false)
1097:             ic.bounds = liveIconRect
1098:             ic.alpha = ai
1099:             ic.draw(c)
1100:         }
1101:         // short title along the rim
1102:         liveTitlePaint.alpha = ai
1103:         if (liveTitleShown.isNotEmpty()) {
1104:             c.drawTextOnPath(
1105:                 liveTitleShown, if (call) liveTitlePathCall else liveTitlePath,
1106:                 liveTitleOff, 0f, liveTitlePaint
1107:             )
1108:         }
1109:         if (call) {
1110:             if (a.answer != null) {
1111:                 fill.color = C_GREEN
1112:                 fill.alpha = ai
1113:                 c.drawCircle(liveAnsX, liveAnsY, liveDotR, fill)
1114:                 val ic = icon(R.drawable.ic_phone, true)
1115:                 ic.bounds = liveAnsRect
1116:                 ic.alpha = ai
1117:                 ic.draw(c)
1118:             }
1119:             if (a.decline != null) {
1120:                 fill.color = C_DECLINE
1121:                 fill.alpha = ai
1122:                 c.drawCircle(liveDecX, liveDecY, liveDotR, fill)
1123:                 val ic = icon(R.drawable.ic_phone_off, true)
1124:                 ic.bounds = liveDecRect
1125:                 ic.alpha = ai
1126:                 ic.draw(c)
1127:             }
1128:         }
1129:         if (liveSec != null) {
1130:             val ang = Math.toRadians(88.0)
1131:             fill.color = Color.WHITE
1132:             fill.alpha = (255f * 0.7f * la + 0.5f).toInt()
1133:             c.drawCircle(-r * cos(ang).toFloat(), -r * sin(ang).toFloat(), dp(2.2f), fill)
1134:         }
1135:         c.restore()
1136:     }
1137: 
1138:     /** 1 = answer dot, 2 = decline dot, 0 = none (view coordinates). */
1139:     private fun liveDotHit(x: Float, y: Float): Int {
1140:         val a = liveCur ?: return 0
1141:         if (a.kind != LiveActivity.Kind.CALL) return 0
1142:         val px = x - width
1143:         val py = y - height
1144:         val hr = dp(18f)
1145:         if (a.answer != null) {
1146:             val dx = px - liveAnsX
1147:             val dy = py - liveAnsY
1148:             if (dx * dx + dy * dy <= hr * hr) return 1
1149:         }
1150:         if (a.decline != null) {
1151:             val dx = px - liveDecX
1152:             val dy = py - liveDecY
1153:             if (dx * dx + dy * dy <= hr * hr) return 2
1154:         }
1155:         return 0
1156:     }
1157: 
1158:     private fun sendPi(pi: PendingIntent?) {
1159:         if (pi == null) return
1160:         try {
1161:             pi.send()
1162:         } catch (e: Exception) {
1163:         }
1164:     }
1165: 
1166:     private fun onBeanTap(x: Float, y: Float) {
1167:         val a = liveCur
1168:         if (liveWanted && a != null) {
1169:             when (liveDotHit(x, y)) {
1170:                 1 -> { haptic(HapticFeedbackConstants.VIRTUAL_KEY); sendPi(a.answer); return }
1171:                 2 -> { haptic(HapticFeedbackConstants.VIRTUAL_KEY); sendPi(a.decline); return }
1172:             }
1173:             if (a.kind == LiveActivity.Kind.CALL && a.contentIntent != null) {
1174:                 haptic(HapticFeedbackConstants.VIRTUAL_KEY)
1175:                 sendPi(a.contentIntent)
1176:                 return
1177:             }
1178:             if (a.kind == LiveActivity.Kind.MEDIA && contentAlpha < 0.2f) {
1179:                 val mi = indexOfId("media")
1180:                 if (mi >= 0) {
1181:                     openPicked = true
1182:                     userPaged = true
1183:                     page = mi
1184:                     lastPageId = "media"
1185:                     pageS.snapTo(mi.toFloat())
1186:                 }
1187:             }
1188:         }
1189:         startExpand(false, 0f, 0f)
1190:     }
1191: 
1192:     // ================================================================ geometry
1193: 
1194:     /** Edge fillet radius for a shape of the given size: scaled with the idle size, at most 40% of it. */
1195:     private fun flareFor(size: Float, p: Float): Float {
1196:         if (flarePx <= 0.5f) return 0f
1197:         val fIdle = min(flarePx * idle / dp(48f), 0.4f * idle)
1198:         val f = fIdle + (flarePx - fIdle) * p
1199:         return min(f, 0.4f * size)
1200:     }
1201: 
1202:     /**
1203:      * Shape path: the quarter-circle bean plus concave fillets ("ears") of radius [fl] where it meets the
1204:      * bottom edge (left end) and the right edge (top end).
1205:      */
1206:     private fun fillPath(p: Path, ox: Float, oy: Float, w: Float, h: Float, k: Float, tip: Float, fl: Float) {
1207:         p.rewind()
1208:         val right = ox + w
1209:         val bottom = oy + h
1210:         val f = if (fl > 0.5f) fl else 0f
1211:         if (f > 0f) {
1212:             p.moveTo(ox - f, bottom)
1213:             arcRect.set(ox - 2f * f, bottom - 2f * f, ox, bottom)
1214:             p.arcTo(arcRect, 90f, -90f)
1215:             p.lineTo(ox, bottom)
1216:         } else {
1217:             p.moveTo(ox, bottom)
1218:         }
1219:         p.cubicTo(ox, bottom - k * h, right - k * w, oy, right, oy)
1220:         if (f > 0f) {
1221:             p.lineTo(right - f, oy)
1222:             arcRect.set(right - 2f * f, oy - 2f * f, right, oy)
1223:             p.arcTo(arcRect, 90f, -90f)
1224:         }
1225:         val t = min(tip, min(w, h))
1226:         if (t > 0.5f) {
1227:             p.lineTo(right, bottom - t)
1228:             arcRect.set(right - 2f * t, bottom - 2f * t, right, bottom)
1229:             p.arcTo(arcRect, 0f, 90f)
1230:         } else {
1231:             p.lineTo(right, bottom)
1232:         }
1233:         p.lineTo(ox - f, bottom)
1234:         p.close()
1235:     }
1236: 
1237:     private fun layoutAll() {
1238:         nodeS = ceil(rad).toInt() + ceil(dp(3f)).toInt()
1239:         beanPx = ceil(idle * LIVE_SCALE).toInt() + ceil(dp(4f)).toInt()
1240:         for (w in order) w.dirty = true
1241:         val hw = min(dp(24.5f), 0.12f * rad - dp(1.5f)).coerceAtLeast(dp(12f))
1242:         val brad = min(dp(20f), hw - dp(2f))
1243: 
1244:         // close X: at the idle bean's centroid (quarter disc centroid = 4/(3 pi) * size).
1245:         // Visual 34 dp, 48 dp hit target.
1246:         val xc = min(0.4244f * idle, 0.16f * rad)
1247:         xCx = -xc
1248:         xCy = -xc
1249:         xRad = dp(17f)
1250:         xHit = dp(24f)
1251: 
1252:         for (w in order) {
1253:             w.bands.clear()
1254:             w.layout(hw, brad)
1255:         }
1256:         layoutLive()
1257:         liveTitleRaw = null
1258:         rebuildLiveTitle()
1259:     }
1260: 
1261:     private var nodeS = 0
1262: 
1263:     private fun arcPath(p: Path, r: Float, a0: Float, a1: Float) {
1264:         xOval.set(-r, -r, r, r)
1265:         p.rewind()
1266:         p.arcTo(xOval, 180f + a0, a1 - a0, true)
1267:     }
1268: 
1269:     private fun layoutLive() {
1270:         val s = liveSize
1271:         val cen = 0.4244f * s
1272:         val ih = dp(9f).toInt()
1273:         liveIconRect.set((-cen).toInt() - ih, (-cen).toInt() - ih, (-cen).toInt() + ih, (-cen).toInt() + ih)
1274:         val tr = s - dp(17f)
1275:         arcPath(liveTitlePath, tr, LV_A0, LV_A1)
1276:         liveTitleLen = tr * Math.toRadians((LV_A1 - LV_A0).toDouble()).toFloat()
1277:         // call title sits between the two dots
1278:         val call0 = 28f
1279:         arcPath(liveTitlePathCall, tr, call0, LV_A1 - (call0 - LV_A0))
1280:         liveCallLen = tr * Math.toRadians((LV_A1 - LV_A0 - 2f * (call0 - LV_A0)).toDouble()).toFloat()
1281:         liveDotR = dp(8f)
1282:         val dr = s - dp(16f)
1283:         val dA = Math.toRadians(15.0)
1284:         val aA = Math.toRadians(75.0)
1285:         liveDecX = -dr * cos(dA).toFloat()
1286:         liveDecY = -dr * sin(dA).toFloat()
1287:         liveAnsX = -dr * cos(aA).toFloat()
1288:         liveAnsY = -dr * sin(aA).toFloat()
1289:         val gh = dp(4.5f).toInt()
1290:         liveAnsRect.set(liveAnsX.toInt() - gh, liveAnsY.toInt() - gh, liveAnsX.toInt() + gh, liveAnsY.toInt() + gh)
1291:         liveDecRect.set(liveDecX.toInt() - gh, liveDecY.toInt() - gh, liveDecX.toInt() + gh, liveDecY.toInt() + gh)
1292:     }
1293: 
1294:     private fun ensureResources() {
1295:         if (resourcesReady) return
1296:         resourcesReady = true
1297:         val all = intArrayOf(
1298:             R.drawable.ic_play, R.drawable.ic_pause, R.drawable.ic_skip_back, R.drawable.ic_skip_forward,
1299:             R.drawable.ic_music, R.drawable.ic_clipboard, R.drawable.ic_phone, R.drawable.ic_phone_off,
1300:             R.drawable.ic_bell, R.drawable.ic_bolt
1301:         )
1302:         for (r in all) {
1303:             icon(r, false)
1304:             icon(r, true)
1305:         }
1306:     }
1307: 
1308:     internal fun icon(res: Int, black: Boolean): Drawable {
1309:         val cache = if (black) blackIcons else whiteIcons
1310:         var d = cache.get(res)
1311:         if (d == null) {
1312:             d = context.getDrawable(res)!!.mutate()
1313:             d.setTint(if (black) Color.BLACK else Color.WHITE)
1314:             cache.put(res, d)
1315:         }
1316:         return d
1317:     }
1318: 
1319:     internal fun warm(res: Int) {
1320:         if (res == 0) return
1321:         icon(res, false)
1322:         icon(res, true)
1323:     }
1324: 
1325:     private var lastDw = -1f
1326:     private var lastDh = -1f
1327:     private var lastW = -1
1328:     private var lastH = -1
1329:     private var lastTip = -1f
1330: 
1331:     // ================================================================ sources
1332: 
1333:     private fun ensureSources() {
1334:         if (sourcesStarted) return
1335:         sourcesStarted = true
1336:         syncArcs()
1337:         for (w in order) startWin(w)
1338:     }
1339: 
1340:     private val startSourcesRunnable = Runnable {
1341:         if (expandedTarget || dragMode != 0) {
1342:             ensureSources()
1343:             val mw = mediaWin
1344:             if (!userPaged && mw != null && mw.playing && page != mw.index && !gestureActive) goToPage(mw.index, 0f)
1345:             invalidate()
1346:         }
1347:     }
1348: 
1349:     private fun discardLayers() {
1350:         for (w in order) {
1351:             w.node?.discardDisplayList()
1352:             w.dirty = true
1353:         }
1354:     }
1355: 
1356:     private fun stopSources() {
1357:         discardLayers()
1358:         removeCallbacks(startSourcesRunnable)
1359:         if (!sourcesStarted) return
1360:         syncShown()
1361:         sourcesStarted = false
1362:         for (w in order) stopWin(w)
1363:         syncArcs()
1364:     }
1365: 
1366:     // ================================================================ region
1367: 
1368:     private fun publishRegion() {
1369:         val ww = width
1370:         val hh = height
1371:         if (hidden) {
1372:             region.setEmpty()
1373:         } else {
1374:             if (ww <= 0 || hh <= 0) return
1375:             val w: Float
1376:             val h: Float
1377:             val fl: Float
1378:             if (regionExpanded) {
1379:                 w = rad; h = rad
1380:                 fl = flareFor(rad, 1f)
1381:             } else {
1382:                 w = regionClosed; h = regionClosed
1383:                 fl = flareFor(regionClosed, 0f)
1384:             }
1385:             fillPath(regionPath, ww - w, hh - h, w, h, K, tipPx, fl)
1386:             clipRegion.set(0, 0, ww, hh)
1387:             region.setPath(regionPath, clipRegion)
1388:         }
1389:         host?.onRegionChanged(region)
1390:         updateExclusion()
1391:     }
1392: 
1393:     /** While open, keep the system Back gesture from stealing page swipes near the right screen edge. */
1394:     private fun updateExclusion() {
1395:         val want = regionExpanded && !hidden && width > 0 && height > 0
1396:         if (want) {
1397:             val s = ceil(rad + flareFor(rad, 1f)).toInt()
1398:             exclRect.set(max(0, width - s), max(0, height - s), width, height)
1399:             systemGestureExclusionRects = exclList
1400:             exclSet = true
1401:         } else if (exclSet) {
1402:             systemGestureExclusionRects = emptyList()
1403:             exclSet = false
1404:         }
1405:     }
1406: 
1407:     // ================================================================ paging
1408: 
1409:     /** Chooses the window to open on: media if playing, else the last used one. */
1410:     private fun pickPage() {
1411:         openPicked = true
1412:         if (contentAlpha > 0.2f) return // mid-collapse: keep what is on screen
1413:         userPaged = false
1414:         val mw = mediaWin
1415:         val li = indexOfId(lastPageId)
1416:         page = if (sourcesStarted && mw != null && mw.playing) mw.index else if (li >= 0) li else 0
1417:         pageS.snapTo(page.toFloat())
1418:     }
1419: 
1420:     private fun goToPage(p: Int, vel: Float) {
1421:         page = p.coerceIn(0, nWin - 1)
1422:         lastPageId = order[page].id
1423:         collapsing = false
1424:         settledExpanded = false
1425:         syncShown()
1426:         pageS.stiffness = 380f * stiffScale; pageS.dampingRatio = openDamp
1427:         pageS.target = page.toFloat()
1428:         pageS.velocity = vel
1429:         requestFrames()
1430:     }
1431: 
1432:     // ================================================================ animation
1433: 
1434:     private fun startExpand(setVel: Boolean, vw: Float, vh: Float) {
1435:         if (hidden) return
1436:         ensureResources()
1437:         val was = expandedTarget
1438:         expandedTarget = true
1439:         removeCallbacks(liveTickRunnable)
1440:         updateViz()
1441:         if (!was && idleStyleId == 3 && screenOn) readBattery()
1442:         collapsing = false
1443:         settledExpanded = false
1444:         syncShown()
1445:         if (!openPicked) pickPage()
1446:         // Binder-heavy listener setup is kept off the first animation frames.
1447:         removeCallbacks(startSourcesRunnable)
1448:         if (!sourcesStarted) postDelayed(startSourcesRunnable, 40L)
1449:         regionExpanded = true
1450:         publishRegion()
1451:         wS.stiffness = 420f * stiffScale; wS.dampingRatio = openDamp
1452:         hS.stiffness = 300f * stiffScale; hS.dampingRatio = min(0.99f, openDamp + 0.04f)
1453:         pageS.target = page.toFloat()
1454:         wS.target = rad
1455:         hS.target = rad
1456:         if (setVel) {
1457:             wS.velocity = vw
1458:             hS.velocity = vh
1459:         }
1460:         press.target = 1f
1461:         if (!was) haptic(HapticFeedbackConstants.VIRTUAL_KEY)
1462:         requestFrames()
1463:     }
1464: 
1465:     private fun startCollapse(setVel: Boolean, vw: Float, vh: Float) {
1466:         cancelItemLongPress()
1467:         clearPressed()
1468:         expandedTarget = false
1469:         settledExpanded = false
1470:         syncShown()
1471:         updateViz()
1472:         collapsing = true
1473:         openPicked = false
1474:         val cs = closedSize()
1475:         wS.stiffness = 520f * stiffScale; wS.dampingRatio = 0.92f
1476:         hS.stiffness = 600f * stiffScale; hS.dampingRatio = 0.92f
1477:         wS.target = cs
1478:         hS.target = cs
1479:         if (setVel) {
1480:             wS.velocity = vw
1481:             hS.velocity = vh
1482:         }
1483:         press.target = 1f
1484:         requestFrames()
1485:     }
1486: 
1487:     private fun requestFrames() {
1488:         if (framePosted) return
1489:         framePosted = true
1490:         Choreographer.getInstance().postFrameCallback(frameCb)
1491:     }
1492: 
1493:     private fun doFrame(frameTimeNanos: Long) {
1494:         framePosted = false
1495:         val dt = if (lastFrameNanos == 0L) {
1496:             1f / 120f
1497:         } else {
1498:             ((frameTimeNanos - lastFrameNanos) * 1e-9f).coerceIn(0f, 1f / 30f)
1499:         }
1500:         lastFrameNanos = frameTimeNanos
1501:         wS.step(dt)
1502:         hS.step(dt)
1503:         pageS.step(dt)
1504:         press.step(dt)
1505:         updateDerived(dt)
1506:         invalidate()
1507:         if (allSettled()) {
1508:             wS.snapTo(wS.target)
1509:             hS.snapTo(hS.target)
1510:             pageS.snapTo(pageS.target)
1511:             press.snapTo(press.target)
1512:             updateDerived(0f)
1513:             lastFrameNanos = 0L
1514:             if (!gestureActive) finishSettle()
1515:         } else {
1516:             requestFrames()
1517:         }
1518:     }
1519: 
1520:     private fun allSettled(): Boolean =
1521:         wS.isSettled(0.3f, 15f) && hS.isSettled(0.3f, 15f) &&
1522:             pageS.isSettled(0.002f, 0.05f) && press.isSettled(0.002f, 0.1f)
1523: 
1524:     private fun finishSettle() {
1525:         if (expandedTarget) {
1526:             regionExpanded = true
1527:             settledExpanded = true
1528:             publishRegion()
1529:             host?.onSettled(true)
1530:             syncShown()
1531:         } else {
1532:             regionExpanded = false
1533:             settledExpanded = false
1534:             syncShown()
1535:             stopSources()
1536:             regionClosed = closedSize()
1537:             publishRegion()
1538:             host?.onSettled(false)
1539:             if (idleStyleId == 3 && screenOn) readBattery()
1540:             updateViz()
1541:             syncLiveTick()
1542:             syncClosedSize()
1543:             invalidateBean()
1544:         }
1545:     }
1546: 
1547:     private fun updateDerived(dt: Float) {
1548:         val span = max(rad - idle, 1f)
1549:         val p = min((wS.value - idle) / span, (hS.value - idle) / span).coerceIn(0f, 1f)
1550:         progress = p
1551:         val t = ((p - 0.55f) / 0.40f).coerceIn(0f, 1f)
1552:         val sm = t * t * (3f - 2f * t)
1553:         contentAlpha = if (collapsing) {
1554:             min(sm, max(0f, contentAlpha - dt / 0.09f))
1555:         } else {
1556:             sm
1557:         }
1558:     }
1559: 
1560:     // ================================================================ draw
1561: 
1562:     private fun buildLivePath() {
1563:         val pulse = if (beatOn && vizRunning) 1f + beat * (if (expandedTarget) 0.015f else 0.06f) else 1f
1564:         val ps = press.value * pulse
1565:         val dw = wS.value * ps
1566:         val dh = hS.value * ps
1567:         val ox = width - dw
1568:         val oy = height - dh
1569:         val pressGlow = ((1f - press.value) / (1f - PRESS_SCALE)).coerceIn(0f, 1f)
1570:         glow = max(max(pressGlow, if (dragMode != 0 || captureWin != null) 0.85f else 0f), max(progress * 0.4f, if (beatOn && vizRunning) beat * 0.55f else 0f))
1571:         val fl = flareFor(min(dw, dh), progress)
1572:         // Static shape: keep the Path objects untouched so HWUI reuses their cached geometry.
1573:         if (dw == lastDw && dh == lastDh && width == lastW && height == lastH && tipPx == lastTip && fl == lastFl) return
1574:         lastDw = dw; lastDh = dh; lastW = width; lastH = height; lastTip = tipPx; lastFl = fl
1575:         fillPath(path, ox, oy, dw, dh, K, tipPx, fl)
1576:         rimPath.rewind()
1577:         rimPath.moveTo(ox, height.toFloat())
1578:         rimPath.cubicTo(ox, height - K * dh, width - K * dw, oy, width.toFloat(), oy)
1579:         val r = max(dw, dh) * 1.15f / GR
1580:         gradScale = r
1581:         gradMatrix.setTranslate(-width.toFloat(), -height.toFloat())
1582:         gradMatrix.postScale(1f / r, 1f / r)
1583:         path.transform(gradMatrix, unitPath)
1584:     }
1585: 
1586:     override fun onDraw(c: Canvas) {
1587:         buildLivePath()
1588:         val g = glow
1589:         drawShapeFill(c, g)
1590:         if (rimOn) {
1591:             val ra = RIM_REST_A + (RIM_HOT_A - RIM_REST_A) * g
1592:             rimPaint.color = (mixColor(Color.WHITE, accent, g) and 0xFFFFFF) or ((255f * ra + 0.5f).toInt() shl 24)
1593:             c.drawPath(rimPath, rimPaint)
1594:         }
1595:         val ia = 1f - progress * 3f
1596:         if (liveWanted && !expandedTarget) {
1597:             drawLive(c)
1598:         } else if (ia > 0.01f && !liveWanted) {
1599:             drawIdle(c, ia) // LOOKS hook: idle decoration (drawIdle)
1600:         }
1601:         val a = contentAlpha
1602:         if (a > 0.004f && resourcesReady) drawContent(c, a)
1603:     }
1604: 
1605:     /** LOOKS hook: the shape fill (gradient today; fills and beat reaction plug in here). */
1606:     private fun drawShapeFill(c: Canvas, g: Float) {
1607:         c.save()
1608:         c.translate(width.toFloat(), height.toFloat())
1609:         c.scale(gradScale, gradScale)
1610:         c.drawPath(unitPath, shapePaint)
1611:         if (glowOn && g > 0.01f) {
1612:             glowPaint.alpha = (255f * g + 0.5f).toInt()
1613:             c.drawPath(unitPath, glowPaint)
1614:         }
1615:         c.restore()
1616:     }
1617: 
1618:     private fun drawContent(c: Canvas, a: Float) {
1619:         if (!c.isHardwareAccelerated) {
1620:             drawContentDirect(c, a)
1621:             return
1622:         }
1623:         val off = (1f - a) * dp(16f)
1624:         // content grows with the melting shape, so no clip is needed
1625:         val cs = (min(wS.value, hS.value) / rad).coerceIn(0f, 1f)
1626:         val pos = pageS.value
1627:         val s = nodeS
1628:         val sf = s.toFloat()
1629:         val w = width
1630:         val h = height
1631:         val mw = mediaWin
1632:         if (mw != null && beatOn && vizRunning && beat > 0.03f && abs(pos - mw.index) < 0.6f) {
1633:             val fa = pageFade(pos - mw.index)
1634:             c.save()
1635:             c.translate(w + off, h + off)
1636:             c.scale(cs, cs)
1637:             c.rotate((mw.index - pos) * PAGE_DEG)
1638:             arcPaint.color = accent
1639:             arcPaint.strokeWidth = dp(5f) + beat * dp(5f)
1640:             arcPaint.alpha = (255f * 0.38f * beat * a * fa + 0.5f).toInt()
1641:             c.drawCircle(mw.artX, mw.artY, mw.artRad + dp(3f) + beat * dp(5f), arcPaint)
1642:             c.restore()
1643:         }
1644:         for (i in 0 until nWin) {
1645:             val win = order[i]
1646:             val d = i - pos
1647:             val fa = pageFade(d)
1648:             if (fa <= 0.004f) continue
1649:             var n = win.node
1650:             if (n == null) {
1651:                 n = RenderNode("edgeWin" + win.id)
1652:                 n.setUseCompositingLayer(true, null)
1653:                 n.setClipToBounds(true)
1654:                 win.node = n
1655:             }
1656:             n.setPosition(w - s, h - s, w, h)
1657:             // A node skipped while faded out loses its display list: re-record it when it comes back.
1658:             if (win.dirty || !n.hasDisplayList()) {
1659:                 val rc = n.beginRecording(s, s)
1660:                 try {
1661:                     rc.translate(sf, sf)
1662:                     drawWin(rc, win, 1f)
1663:                 } finally {
1664:                     n.endRecording()
1665:                 }
1666:                 win.dirty = false
1667:             }
1668:             n.setPivotX(sf)
1669:             n.setPivotY(sf)
1670:             n.setRotationZ(d * PAGE_DEG)
1671:             n.setAlpha(a * fa)
1672:             n.setScaleX(cs)
1673:             n.setScaleY(cs)
1674:             n.setTranslationX(off)
1675:             n.setTranslationY(off)
1676:             c.drawRenderNode(n)
1677:         }
1678:         c.save()
1679:         c.translate(w + off, h + off)
1680:         c.scale(cs, cs)
1681:         drawPageBars(c, a)
1682:         drawClose(c, a)
1683:         c.restore()
1684:     }
1685: 
1686:     /** Neighbour-window fade: exactly 0 once a page is settled (|d| >= 0.85), ~55% each mid-swipe. */
1687:     private fun pageFade(d: Float): Float {
1688:         val t = (1f - abs(d) / 0.85f).coerceIn(0f, 1f)
1689:         return t * t * (3f - 2f * t)
1690:     }
1691: 
1692:     private fun drawContentDirect(c: Canvas, a: Float) {
1693:         c.save()
1694:         c.clipPath(path)
1695:         val off = (1f - a) * dp(16f)
1696:         val s = 0.96f + 0.04f * a
1697:         c.translate(width + off, height + off)
1698:         c.scale(s, s)
1699:         val pos = pageS.value
1700:         for (i in 0 until nWin) {
1701:             val d = i - pos
1702:             val fa = pageFade(d)
1703:             if (fa <= 0.004f) continue
1704:             c.save()
1705:             c.rotate(d * PAGE_DEG)
1706:             drawWin(c, order[i], a * fa)
1707:             c.restore()
1708:         }
1709:         drawPageBars(c, a)
1710:         drawClose(c, a)
1711:         c.restore()
1712:     }
1713: 
1714:     private fun drawWin(c: Canvas, w: Win, a: Float) {
1715:         w.drawBands(c, a)
1716:         w.draw(c, a)
1717:     }
1718: 
1719:     private fun drawPageBars(c: Canvas, a: Float) {
1720:         // Short curved bars just outside the close X (one per enabled window). The accent highlight is one arc
1721:         // whose ends are interpolated from the continuous page position (stretches mid-transition).
1722:         val n = nWin
1723:         if (n < 2) return
1724:         val xd = kotlin.math.hypot(xCx, xCy)
1725:         val rd = xd + xRad + dp(7f)
1726:         val ai = (255f * a + 0.5f).toInt()
1727:         // 7.5 deg bars at 4 deg gaps, total span clamped to 28..62 deg and centred on 45 deg
1728:         var seg = 7.5f
1729:         val gap = BAR_GAP
1730:         var total = n * seg + (n - 1) * gap
1731:         if (total > 62f) {
1732:             seg = (62f - (n - 1) * gap) / n
1733:             total = 62f
1734:         } else if (total < 28f) {
1735:             seg = (28f - (n - 1) * gap) / n
1736:             total = 28f
1737:         }
1738:         val a0 = 45f - total / 2f
1739:         val step = seg + gap
1740:         val cap = Math.toDegrees((dp(1.5f) / rd).toDouble()).toFloat().coerceAtMost(seg / 3f)
1741:         xOval.set(-rd, -rd, rd, rd)
1742:         arcPaint.strokeWidth = dp(3f)
1743:         arcPaint.color = C_BAR
1744:         arcPaint.alpha = (255f * 0.6f * a + 0.5f).toInt()
1745:         for (i in 0 until n) {
1746:             c.drawArc(xOval, 180f + a0 + i * step + cap, seg - 2f * cap, false, arcPaint)
1747:         }
1748:         val pos = pageS.value.coerceIn(0f, (n - 1).toFloat())
1749:         val i0 = min(pos.toInt(), n - 2)
1750:         val f = pos - i0
1751:         val bulge = sin(Math.PI * f).toFloat() * 3f
1752:         val st = a0 + (i0 + f) * step - bulge + cap
1753:         val en = a0 + (i0 + f) * step + seg + bulge - cap
1754:         arcPaint.color = accent
1755:         arcPaint.alpha = ai
1756:         c.drawArc(xOval, 180f + st, en - st, false, arcPaint)
1757:     }
1758: 
1759:     private fun drawClose(c: Canvas, a: Float) {
1760:         val cx = xCx
1761:         val cy = xCy
1762:         val sc = 0.6f + 0.4f * a
1763:         val ai = (255f * a + 0.5f).toInt()
1764:         val px = pressed == ID_X && pressedWin == null
1765:         c.save()
1766:         c.scale(sc * (if (px) PRESS_SCALE else 1f), sc * (if (px) PRESS_SCALE else 1f), cx, cy)
1767:         fill.color = C_FILL
1768:         fill.alpha = ai
1769:         c.drawCircle(cx, cy, xRad, fill)
1770:         if (px) {
1771:             ringPaint.alpha = ai
1772:             c.drawCircle(cx, cy, xRad + dp(3f), ringPaint)
1773:         }
1774:         linePaint.color = Color.WHITE
1775:         linePaint.alpha = ai
1776:         linePaint.strokeWidth = dp(1.8f)
1777:         val h = dp(7f)
1778:         c.drawLine(cx - h, cy - h, cx + h, cy + h, linePaint)
1779:         c.drawLine(cx - h, cy + h, cx + h, cy - h, linePaint)
1780:         c.restore()
1781:     }
1782: 
1783:     // ================================================================ touch
1784: 
1785:     private fun addMove(ev: MotionEvent, rx: Float, ry: Float) {
1786:         val t = vt ?: return
1787:         // The window may resize mid-gesture, so feed the tracker raw (screen) coordinates.
1788:         val lx = ev.x
1789:         val ly = ev.y
1790:         ev.setLocation(rx, ry)
1791:         t.addMovement(ev)
1792:         ev.setLocation(lx, ly) // restore: callers read ev.x / ev.y in view coordinates afterwards
1793:     }
1794: 
1795:     private fun releaseTracker() {
1796:         vt?.recycle()
1797:         vt = null
1798:     }
1799: 
1800:     private fun cancelItemLongPress() {
1801:         removeCallbacks(longPressRunnable)
1802:     }
1803: 
1804:     override fun onTouchEvent(ev: MotionEvent): Boolean {
1805:         val action = ev.actionMasked
1806:         if (action == MotionEvent.ACTION_OUTSIDE) {
1807:             if (expandedTarget && !gestureActive && !holdOpen) startCollapse(false, 0f, 0f)
1808:             return false
1809:         }
1810:         if (hidden) return false
1811:         when (action) {
1812:             MotionEvent.ACTION_DOWN -> return handleDown(ev)
1813:             MotionEvent.ACTION_MOVE -> {
1814:                 if (!gestureActive) return false
1815:                 handleMove(ev)
1816:             }
1817:             MotionEvent.ACTION_UP -> {
1818:                 if (!gestureActive) return false
1819:                 handleUp(ev, false)
1820:             }
1821:             MotionEvent.ACTION_CANCEL -> {
1822:                 if (!gestureActive) return false
1823:                 handleUp(ev, true)
1824:             }
1825:         }
1826:         return true
1827:     }
1828: 
1829:     private fun handleDown(ev: MotionEvent): Boolean {
1830:         val x = ev.x
1831:         val y = ev.y
1832:         val rx = ev.rawX
1833:         val ry = ev.rawY
1834:         ensureResources()
1835:         buildLivePath()
1836:         clipRegion.set(0, 0, max(width, 1), max(height, 1))
1837:         hitRegion.setPath(path, clipRegion)
1838:         if (!hitRegion.contains(x.toInt(), y.toInt())) return false
1839: 
1840:         releaseTracker()
1841:         vt = VelocityTracker.obtain()
1842:         gestureActive = true
1843:         downRawX = rx
1844:         downRawY = ry
1845:         moved = false
1846:         dragMode = 0
1847:         captureWin = null
1848:         clearPressed()
1849:         longFired = false
1850:         addMove(ev, rx, ry)
1851: 
1852:         if (!expandedTarget) {
1853:             startMode = MODE_IDLE
1854:             updateViz()
1855:             removeCallbacks(liveTickRunnable)
1856:             // Pay the listener-registration cost during the finger-down, before the expand animation.
1857:             ensureSources()
1858:             press.target = PRESS_SCALE
1859:             requestFrames()
1860:         } else {
1861:             startMode = MODE_EXPANDED
1862:             baseW = wS.value
1863:             baseH = hS.value
1864:             pageStart = pageS.value
1865:             if (contentAlpha > 0.5f) {
1866:                 val item = hitItem(x, y)
1867:                 if (item == ID_X) {
1868:                     setPressed(null, ID_X)
1869:                     invalidate()
1870:                 } else if (item >= 0) {
1871:                     val w = hitWin
1872:                     if (w != null && w.usable(item)) {
1873:                         setPressed(w, item)
1874:                         if (w.longPressable(item)) postDelayed(longPressRunnable, longPressMs)
1875:                         invalidate()
1876:                     }
1877:                 } else {
1878:                     // a DOWN on a slider / dial / seek bar claims the whole gesture
1879:                     val w = settledWin(x, y)
1880:                     if (w != null && w.capture(lpX, lpY)) {
1881:                         captureWin = w
1882:                         moved = true
1883:                         w.dragTo(lpX, lpY)
1884:                         invalidate()
1885:                     }
1886:                 }
1887:             }
1888:         }
1889:         return true
1890:     }
1891: 
1892:     /** The window under the finger when a page is settled (sets lpX/lpY to its local frame), else null. */
1893:     private fun settledWin(x: Float, y: Float): Win? {
1894:         if (nWin == 0) return null
1895:         val pos = pageS.value
1896:         val s = Math.round(pos).coerceIn(0, nWin - 1)
1897:         if (abs(pos - s) > 0.2f) return null
1898:         toLocal(x, y, s - pos)
1899:         return order[s]
1900:     }
1901: 
1902:     /** Hit test: ID_X, a window-local item (hitWin set), or -1. */
1903:     private fun hitItem(x: Float, y: Float): Int {
1904:         hitWin = null
1905:         val px = x - width
1906:         val py = y - height
1907:         if ((px - xCx) * (px - xCx) + (py - xCy) * (py - xCy) <= xHit * xHit) return ID_X
1908:         val w = settledWin(x, y) ?: return -1
1909:         val i = w.hit(lpX, lpY)
1910:         if (i >= 0) hitWin = w
1911:         return i
1912:     }
1913: 
1914:     /** Converts a view point to the local (un-rotated) polar frame of a window page d pages away. */
1915:     private fun toLocal(x: Float, y: Float, d: Float) {
1916:         val px = x - width
1917:         val py = y - height
1918:         val ang = Math.toRadians((-d * PAGE_DEG).toDouble())
1919:         val cs = cos(ang).toFloat()
1920:         val sn = sin(ang).toFloat()
1921:         lpX = px * cs - py * sn
1922:         lpY = px * sn + py * cs
1923:     }
1924: 
1925:     private fun handleMove(ev: MotionEvent) {
1926:         val rx = ev.rawX
1927:         val ry = ev.rawY
1928:         addMove(ev, rx, ry)
1929:         val cw = captureWin
1930:         if (cw != null) {
1931:             moved = true
1932:             toLocal(ev.x, ev.y, cw.index - pageS.value)
1933:             cw.dragTo(lpX, lpY)
1934:             return
1935:         }
1936:         val dx = rx - downRawX
1937:         val dy = ry - downRawY
1938:         if (dragMode == 0) {
1939:             if (sqrt(dx * dx + dy * dy) > touchSlop) {
1940:                 moved = true
1941:                 cancelItemLongPress()
1942:                 if (pressed >= 0) {
1943:                     clearPressed()
1944:                     invalidate()
1945:                 }
1946:                 if (startMode == MODE_IDLE) {
1947:                     if (max(-dx, -dy) > touchSlop) beginDrag(1)
1948:                 } else if (abs(dx) > abs(dy) && abs(dx) > touchSlop) {
1949:                     beginDrag(3)
1950:                 } else if (dy > abs(dx) && dy > touchSlop) {
1951:                     beginDrag(2)
1952:                 }
1953:             }
1954:         }
1955:         if (dragMode != 0) applyDrag(dx, dy)
1956:     }
1957: 
1958:     private fun beginDrag(mode: Int) {
1959:         dragMode = mode
1960:         ensureResources()
1961:         collapsing = false
1962:         settledExpanded = false
1963:         removeCallbacks(liveTickRunnable)
1964:         if (mode == 1 && !openPicked) pickPage()
1965:         removeCallbacks(startSourcesRunnable)
1966:         if (!sourcesStarted) postDelayed(startSourcesRunnable, 40L)
1967:         regionExpanded = true
1968:         publishRegion()
1969:         updateViz()
1970:         when (mode) {
1971:             1 -> {
1972:                 baseW = max(idle, wS.value)
1973:                 baseH = max(idle, hS.value)
1974:             }
1975:             2 -> {
1976:                 baseW = wS.value
1977:                 baseH = hS.value
1978:             }
1979:             else -> {
1980:                 pageStart = pageS.value
1981:                 dragShift = max(0.9f * rad, 1f)
1982:                 pageS.velocity = 0f
1983:             }
1984:         }
1985:         press.target = 1f
1986:         requestFrames()
1987:     }
1988: 
1989:     private fun rubber(v: Float, maxV: Float): Float {
1990:         if (v <= idle) return idle
1991:         if (v <= maxV) return v
1992:         return maxV + min((v - maxV) * 0.3f, dp(18f))
1993:     }
1994: 
1995:     private fun setSizeDirect(w: Float, h: Float) {
1996:         wS.value = w; wS.target = w; wS.velocity = 0f
1997:         hS.value = h; hS.target = h; hS.velocity = 0f
1998:     }
1999: 
2000:     private fun applyDrag(dx: Float, dy: Float) {
2001:         when (dragMode) {
2002:             1 -> setSizeDirect(rubber(baseW - dx, rad), rubber(baseH - dy, rad))
2003:             2 -> {
2004:                 val hh = rubber(baseH - dy, baseH)
2005:                 val fr = (hh - idle) / max(baseH - idle, 1f)
2006:                 setSizeDirect(idle + (baseW - idle) * fr, hh)
2007:             }
2008:             3 -> {
2009:                 val last = (nWin - 1).toFloat()
2010:                 var pos = pageStart - dx / dragShift
2011:                 if (pos < 0f) pos *= 0.3f
2012:                 else if (pos > last) pos = last + (pos - last) * 0.3f
2013:                 pageS.value = pos; pageS.target = pos; pageS.velocity = 0f
2014:             }
2015:         }
2016:         updateDerived(0f)
2017:         invalidate()
2018:     }
2019: 
2020:     private fun finishDrag(vx: Float, vy: Float) {
2021:         val mode = dragMode
2022:         val outward = if (mode == 2) -vy else -(vx + vy) * 0.5f
2023:         val thr = dp(500f)
2024:         val doExpand = when {
2025:             outward > thr -> true
2026:             outward < -thr -> false
2027:             mode == 1 -> progress > 0.33f
2028:             else -> progress > 0.67f
2029:         }
2030:         dragMode = 0
2031:         val vh = -vy
2032:         val vw = if (mode == 2) -vy * (baseW - idle) / max(baseH - idle, 1f) else -vx
2033:         if (doExpand) startExpand(true, vw, vh) else startCollapse(true, vw, vh)
2034:     }
2035: 
2036:     private fun finishPage(dx: Float, vx: Float) {
2037:         val base = Math.round(pageStart).coerceIn(0, nWin - 1)
2038:         val thr = 0.28f * dragShift
2039:         val vthr = dp(500f)
2040:         var target = base
2041:         if (dx < -thr || vx < -vthr) target = base + 1
2042:         else if (dx > thr || vx > vthr) target = base - 1
2043:         target = target.coerceIn(0, nWin - 1)
2044:         if (target != base) userPaged = true
2045:         dragMode = 0
2046:         goToPage(target, -vx / dragShift)
2047:     }
2048: 
2049:     private fun handleUp(ev: MotionEvent, cancel: Boolean) {
2050:         val rx = ev.rawX
2051:         val ry = ev.rawY
2052:         val x = ev.x
2053:         val y = ev.y
2054:         cancelItemLongPress()
2055:         var vx = 0f
2056:         var vy = 0f
2057:         val t = vt
2058:         if (t != null) {
2059:             if (!cancel) addMove(ev, rx, ry)
2060:             t.computeCurrentVelocity(1000)
2061:             vx = t.xVelocity
2062:             vy = t.yVelocity
2063:         }
2064:         releaseTracker()
2065:         val item = pressed
2066:         val pw = pressedWin
2067:         clearPressed()
2068:         gestureActive = false
2069:         val quick = !moved && (ev.eventTime - ev.downTime) < TAP_MS
2070: 
2071:         val cw = captureWin
2072:         if (cw != null) {
2073:             captureWin = null
2074:             cw.dragEnd(cancel)
2075:         } else if (dragMode == 3) {
2076:             finishPage(if (cancel) 0f else rx - downRawX, if (cancel) 0f else vx)
2077:         } else if (dragMode != 0) {
2078:             finishDrag(if (cancel) 0f else vx, if (cancel) 0f else vy)
2079:         } else if (startMode == MODE_IDLE) {
2080:             press.target = 1f
2081:             if (!cancel && quick) onBeanTap(x, y)
2082:         } else if (!cancel && !longFired && !moved && item >= 0 && hitItem(x, y) == item && hitWin === pw) {
2083:             if (item == ID_X) startCollapse(false, 0f, 0f) else pw?.onTap(item)
2084:         }
2085:         updateViz()
2086:         syncClosedSize()
2087:         syncLiveTick()
2088:         invalidate()
2089:         requestFrames()
2090:     }
2091: 
2092:     private fun onLongPress() {
2093:         val item = pressed
2094:         val w = pressedWin
2095:         if (!gestureActive || w == null || !w.longPressable(item)) return
2096:         clearPressed()
2097:         longFired = true
2098:         haptic(HapticFeedbackConstants.LONG_PRESS)
2099:         val launched = w.onLongPress(item)
2100:         invalidate()
2101:         if (launched) startCollapse(false, 0f, 0f)
2102:     }
2103: 
2104:     // ================================================================ init
2105: 
2106:     init {
2107:         applyLooksNow()
2108:         reloadWindows()
2109:     }
2110: 
2111:     internal companion object {
2112:         const val K = 0.5523f
2113:         const val PRESS_SCALE = 0.92f
2114:         const val TAP_MS = 300L
2115:         const val MODE_IDLE = 0
2116:         const val MODE_EXPANDED = 1
2117:         const val ID_X = 1000
2118:         const val PAGE_DEG = 70f
2119:         const val SL_A0 = 8f
2120:         const val SL_A1 = 82f
2121:         const val LV_A0 = 16f
2122:         const val LV_A1 = 74f
2123:         const val LIVE_SCALE = 1.55f
2124:         val C_FILL = Color.parseColor("#1C1C1E")
2125:         val C_TRACK = Color.parseColor("#2C2C2E")
2126:         val C_GREY = Color.parseColor("#8E8E93")
2127:         val C_RED = Color.parseColor("#FF4B3E")
2128:         val C_BAR = Color.parseColor("#5A5A5E")
2129:         val C_CREAM = Color.parseColor("#F2EDE4")
2130:         val C_GREEN = Color.parseColor("#30D158")
2131:         val C_DECLINE = Color.parseColor("#FF453A")
2132:         const val BAR_GAP = 4f
2133:         const val GR = 100f
2134:         const val RIM_REST_A = 0.10f
2135:         const val RIM_HOT_A = 0.75f
2136:     }
2137: }
===== FlatSlider.kt =====
   1: package com.dynedge
   2: 
   3: import android.content.Context
   4: import android.graphics.Canvas
   5: import android.graphics.Color
   6: import android.graphics.LinearGradient
   7: import android.graphics.Paint
   8: import android.graphics.Path
   9: import android.graphics.RadialGradient
  10: import android.graphics.RectF
  11: import android.graphics.Shader
  12: import android.view.MotionEvent
  13: import android.view.View
  14: 
  15: /** Knobless flat slider: 6 dp rounded track with an accent fill. Drag or tap anywhere on it. */
  16: class FlatSlider(ctx: Context) : View(ctx) {
  17:     var min = 0f
  18:     var max = 100f
  19:     var accent = Color.WHITE
  20:         set(v) { field = v; invalidate() }
  21:     var onStart: () -> Unit = {}
  22:     var onChange: (Float) -> Unit = {}
  23:     var onStop: () -> Unit = {}
  24: 
  25:     var value = 0f
  26:         set(v) { field = v.coerceIn(min, max); invalidate() }
  27: 
  28:     private val d = resources.displayMetrics.density
  29:     private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2C2C2E") }
  30:     private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
  31:     private val rect = RectF()
  32:     private var dragging = false
  33: 
  34:     override fun onMeasure(w: Int, h: Int) {
  35:         setMeasuredDimension(MeasureSpec.getSize(w), (32 * d + 0.5f).toInt())
  36:     }
  37: 
  38:     override fun onDraw(c: Canvas) {
  39:         val th = 6 * d
  40:         val cy = height / 2f
  41:         val l = paddingLeft.toFloat()
  42:         val r = (width - paddingRight).toFloat()
  43:         rect.set(l, cy - th / 2, r, cy + th / 2)
  44:         c.drawRoundRect(rect, th / 2, th / 2, track)
  45:         val f = if (max > min) (value - min) / (max - min) else 0f
  46:         val fr = l + (r - l) * f
  47:         fill.color = accent
  48:         rect.set(l, cy - th / 2, maxOf(fr, l + th), cy + th / 2)
  49:         c.drawRoundRect(rect, th / 2, th / 2, fill)
  50:     }
  51: 
  52:     private fun setFromX(x: Float) {
  53:         val l = paddingLeft.toFloat()
  54:         val r = (width - paddingRight).toFloat()
  55:         val f = ((x - l) / (r - l)).coerceIn(0f, 1f)
  56:         val nv = Math.round(min + (max - min) * f).toFloat()
  57:         if (nv != value) {
  58:             value = nv
  59:             onChange(nv)
  60:         }
  61:     }
  62: 
  63:     override fun onTouchEvent(e: MotionEvent): Boolean {
  64:         when (e.actionMasked) {
  65:             MotionEvent.ACTION_DOWN -> {
  66:                 parent?.requestDisallowInterceptTouchEvent(true)
  67:                 dragging = true
  68:                 onStart()
  69:                 setFromX(e.x)
  70:             }
  71:             MotionEvent.ACTION_MOVE -> if (dragging) setFromX(e.x)
  72:             MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
  73:                 dragging = false
  74:                 if (e.actionMasked == MotionEvent.ACTION_UP) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
  75:                 onStop()
  76:             }
  77:         }
  78:         return true
  79:     }
  80: }
  81: 
  82: /** Small flat on/off toggle: rounded track, round thumb. */
  83: class FlatToggle(ctx: Context) : View(ctx) {
  84:     var accent = Color.WHITE
  85:         set(v) { field = v; invalidate() }
  86:     var checked = false
  87:         set(v) { field = v; invalidate() }
  88:     var onToggle: (Boolean) -> Unit = {}
  89: 
  90:     private val d = resources.displayMetrics.density
  91:     private val p = Paint(Paint.ANTI_ALIAS_FLAG)
  92:     private val rect = RectF()
  93: 
  94:     init {
  95:         isClickable = true
  96:         setOnClickListener {
  97:             checked = !checked
  98:             performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
  99:             onToggle(checked)
 100:         }
 101:     }
 102: 
 103:     override fun onMeasure(w: Int, h: Int) {
 104:         setMeasuredDimension((46 * d).toInt(), (28 * d).toInt())
 105:     }
 106: 
 107:     override fun onDraw(c: Canvas) {
 108:         val h = height.toFloat()
 109:         p.color = if (checked) accent else Color.parseColor("#3A3A3C")
 110:         rect.set(0f, 0f, width.toFloat(), h)
 111:         c.drawRoundRect(rect, h / 2, h / 2, p)
 112:         p.color = if (checked) Color.BLACK else Color.parseColor("#8E8E93")
 113:         val r = h / 2 - 4 * d
 114:         val cx = if (checked) width - h / 2 else h / 2
 115:         c.drawCircle(cx, h / 2, r, p)
 116:     }
 117: }
 118: 
 119: 
 120: /**
 121:  * Mini bean preview (quarter circle, tip at the bottom-right). Draws one idle design, one fill style or one
 122:  * visualizer style on the bean, using the same polar geometry as the widget (angle 0 = bottom edge, 90 = right edge).
 123:  */
 124: class IdlePreview(ctx: Context, private val style: String, private val kind: Int = IDLE) : View(ctx) {
 125:     companion object {
 126:         const val IDLE = 0
 127:         const val FILL = 1
 128:         const val VIZ = 2
 129:     }
 130: 
 131:     var accent = Color.WHITE
 132:         set(v) { field = v; invalidate() }
 133:     var gradient = 60
 134:         set(v) { field = v; invalidate() }
 135:     var selectedStyle = false
 136:         set(v) { field = v; invalidate() }
 137:     var sizeDp = 44f
 138:         set(v) { field = v; requestLayout() }
 139: 
 140:     private val d = resources.displayMetrics.density
 141:     private val path = Path()
 142:     private val tmp = Path()
 143:     private val p = Paint(Paint.ANTI_ALIAS_FLAG)
 144:     private val rect = RectF()
 145:     private val bands = floatArrayOf(0.9f, 0.55f, 0.75f, 0.4f, 0.65f, 0.3f, 0.8f, 0.5f)
 146:     private var w = 0f
 147:     private var h = 0f
 148:     private var u = 1f
 149: 
 150:     override fun onMeasure(wSpec: Int, hSpec: Int) {
 151:         val s = (sizeDp * d + 0.5f).toInt()
 152:         setMeasuredDimension(s, s)
 153:     }
 154: 
 155:     private fun px(r: Float, a: Float): Float = w - r * Math.cos(Math.toRadians(a.toDouble())).toFloat()
 156:     private fun py(r: Float, a: Float): Float = h - r * Math.sin(Math.toRadians(a.toDouble())).toFloat()
 157: 
 158:     private fun strokeArc(c: Canvas, r: Float, a1: Float, a2: Float, width: Float, color: Int, cap: Paint.Cap = Paint.Cap.ROUND) {
 159:         rect.set(w - r, h - r, w + r, h + r)
 160:         p.style = Paint.Style.STROKE
 161:         p.strokeCap = cap
 162:         p.strokeWidth = Math.max(1f, width * u)
 163:         p.color = color
 164:         c.drawArc(rect, 180f + a1, a2 - a1, false, p)
 165:     }
 166: 
 167:     private fun line(c: Canvas, r1: Float, r2: Float, a: Float, width: Float, color: Int) {
 168:         p.style = Paint.Style.STROKE
 169:         p.strokeCap = Paint.Cap.ROUND
 170:         p.strokeWidth = Math.max(1f, width * u)
 171:         p.color = color
 172:         c.drawLine(px(r1, a), py(r1, a), px(r2, a), py(r2, a), p)
 173:     }
 174: 
 175:     private fun dot(c: Canvas, x: Float, y: Float, diam: Float, color: Int) {
 176:         p.style = Paint.Style.FILL
 177:         p.color = color
 178:         c.drawCircle(x, y, Math.max(1f, diam * u / 2f), p)
 179:     }
 180: 
 181:     private fun alpha(color: Int, a: Float): Int =
 182:         Color.argb((a * 255f).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
 183: 
 184:     override fun onDraw(c: Canvas) {
 185:         w = width.toFloat()
 186:         h = height.toFloat()
 187:         val s = w * 0.86f
 188:         u = s / 48f
 189:         val x0 = w - s
 190:         val y0 = h - s
 191:         path.rewind()
 192:         path.moveTo(x0, h)
 193:         path.cubicTo(x0, h - s * 0.5523f, w - s * 0.5523f, y0, w, y0)
 194:         path.lineTo(w, h)
 195:         path.close()
 196:         p.style = Paint.Style.FILL
 197:         p.shader = null
 198:         p.color = Color.BLACK
 199:         c.drawPath(path, p)
 200: 
 201:         val fillId = if (kind == FILL) style else "gradient"
 202:         if (fillId != "solid") {
 203:             val a = (gradient * 2.2f).toInt().coerceIn(0, 255)
 204:             val warm = Color.argb(a, Color.red(accent), Color.green(accent), Color.blue(accent))
 205:             p.shader = RadialGradient(w, h, s, intArrayOf(warm, Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
 206:             c.drawPath(path, p)
 207:             p.shader = null
 208:         }
 209:         if (fillId != "gradient" && fillId != "solid") {
 210:             c.save()
 211:             c.clipPath(path)
 212:             val white = Color.WHITE
 213:             when (fillId) {
 214:                 "grooves" -> {
 215:                     var r = 8f
 216:                     while (r < 50f) {
 217:                         strokeArc(c, r * u, 0f, 90f, 0.8f, alpha(white, 0.22f), Paint.Cap.BUTT)
 218:                         r += 6f
 219:                     }
 220:                 }
 221:                 "dots" -> {
 222:                     val st = 7f * u
 223:                     var j = 0
 224:                     var yy = h
 225:                     while (yy > y0 - st) {
 226:                         var xx = w - (if (j % 2 == 0) 0f else st / 2f)
 227:                         while (xx > x0 - st) {
 228:                             dot(c, xx, yy, 1.8f, alpha(white, 0.30f))
 229:                             xx -= st
 230:                         }
 231:                         yy -= st
 232:                         j++
 233:                     }
 234:                 }
 235:                 "sunburst" -> {
 236:                     for (i in 0..36) {
 237:                         val ang = i * 2.5f
 238:                         val col = if (i % 6 == 0) alpha(accent, 0.45f) else alpha(white, if (i % 2 == 0) 0.22f else 0.06f)
 239:                         line(c, 5f * u, 50f * u, ang, 0.7f, col)
 240:                     }
 241:                 }
 242:                 "carbon" -> {
 243:                     val cell = 4f * u
 244:                     p.style = Paint.Style.FILL
 245:                     var gy = 0
 246:                     var yy = y0
 247:                     while (yy < h) {
 248:                         var gx = 0
 249:                         var xx = x0
 250:                         while (xx < w) {
 251:                             p.color = alpha(white, if ((gx + gy) % 2 == 0) 0.22f else 0.07f)
 252:                             c.drawRect(xx, yy, xx + cell, yy + cell, p)
 253:                             xx += cell
 254:                             gx++
 255:                         }
 256:                         yy += cell
 257:                         gy++
 258:                     }
 259:                 }
 260:             }
 261:             c.restore()
 262:         }
 263: 
 264:         // outline: accent ring when selected
 265:         p.style = Paint.Style.STROKE
 266:         p.strokeCap = Paint.Cap.BUTT
 267:         p.strokeWidth = if (selectedStyle) 2f * d else 1f * d
 268:         p.color = if (selectedStyle) accent else Color.parseColor("#3A3A3C")
 269:         c.drawPath(path, p)
 270: 
 271:         when (kind) {
 272:             IDLE -> drawIdle(c, s)
 273:             VIZ -> drawViz(c, s)
 274:         }
 275:         p.strokeCap = Paint.Cap.BUTT
 276:         p.style = Paint.Style.FILL
 277:         p.shader = null
 278:     }
 279: 
 280:     private fun drawIdle(c: Canvas, s: Float) {
 281:         val white = Color.WHITE
 282:         when (style) {
 283:             "glyph" -> {
 284:                 val r = 0.58f * s
 285:                 line(c, r - 5f * u, r, 35f, 1.8f, alpha(white, 0.9f))
 286:                 line(c, r - 5f * u, r, 45f, 1.8f, accent)
 287:                 line(c, r - 5f * u, r, 55f, 1.8f, alpha(white, 0.9f))
 288:             }
 289:             "pill" -> {
 290:                 val cx = px(0.46f * s, 45f)
 291:                 val cy = py(0.46f * s, 45f)
 292:                 c.save()
 293:                 c.rotate(-45f, cx, cy)
 294:                 p.style = Paint.Style.FILL
 295:                 p.color = alpha(accent, 0.95f)
 296:                 rect.set(cx - 5f * u, cy - 1.6f * u, cx + 5f * u, cy + 1.6f * u)
 297:                 c.drawRoundRect(rect, 1.6f * u, 1.6f * u, p)
 298:                 c.restore()
 299:             }
 300:             "orbit" -> {
 301:                 strokeArc(c, 0.62f * s, 18f, 72f, 1.2f, alpha(white, 0.18f))
 302:                 val a = 18f + 54f * 0.5f
 303:                 dot(c, px(0.62f * s, a), py(0.62f * s, a), 3.2f, accent)
 304:             }
 305:             "slits" -> {
 306:                 strokeArc(c, 0.54f * s, 28f, 62f, 1.2f, alpha(white, 0.55f), Paint.Cap.SQUARE)
 307:                 strokeArc(c, 0.68f * s, 28f, 62f, 1.2f, alpha(accent, 0.7f), Paint.Cap.SQUARE)
 308:             }
 309:             "halo" -> {
 310:                 val cx = px(0.424f * s, 45f)
 311:                 val cy = py(0.424f * s, 45f)
 312:                 dot(c, cx, cy, 6f, alpha(accent, 0.35f))
 313:                 dot(c, cx, cy, 2.4f, alpha(white, 0.95f))
 314:             }
 315:             "meter" -> {
 316:                 strokeArc(c, 0.64f * s, 20f, 70f, 2f, alpha(white, 0.16f))
 317:                 strokeArc(c, 0.64f * s, 20f, 20f + 50f * 0.7f, 2f, alpha(accent, 0.9f))
 318:             }
 319:             "grid" -> {
 320:                 val cx = px(0.424f * s, 45f)
 321:                 val cy = py(0.424f * s, 45f)
 322:                 c.save()
 323:                 c.rotate(-45f, cx, cy)
 324:                 dot(c, cx - 2f * u, cy - 2f * u, 1.6f, alpha(white, 0.8f))
 325:                 dot(c, cx + 2f * u, cy - 2f * u, 1.6f, alpha(accent, 0.8f))
 326:                 dot(c, cx - 2f * u, cy + 2f * u, 1.6f, alpha(white, 0.3f))
 327:                 dot(c, cx + 2f * u, cy + 2f * u, 1.6f, alpha(accent, 0.3f))
 328:                 c.restore()
 329:             }
 330:             "eclipse" -> {
 331:                 val rd = 0.22f * s
 332:                 p.style = Paint.Style.FILL
 333:                 p.color = alpha(accent, 0.95f)
 334:                 c.drawCircle(px(0.44f * s, 40f), py(0.44f * s, 40f), rd, p)
 335:                 p.color = Color.BLACK
 336:                 c.drawCircle(px(0.44f * s, 50f), py(0.44f * s, 50f), rd, p)
 337:             }
 338:         }
 339:     }
 340: 
 341:     private fun drawViz(c: Canvas, s: Float) {
 342:         val white = Color.WHITE
 343:         when (style) {
 344:             "bars" -> {
 345:                 for (i in 0 until 4) {
 346:                     val a = 30f + i * 10f
 347:                     line(c, 0.30f * s, 0.30f * s + (0.10f + 0.35f * bands[i]) * s, a, 2f, alpha(white, 0.9f))
 348:                 }
 349:             }
 350:             "spectrum" -> {
 351:                 for (i in 0 until 12) {
 352:                     val b = bands[i % 6]
 353:                     val a = 12f + i * 5.5f
 354:                     val thick = 1.5f + 5f * b
 355:                     val col = if (b > 0.7f) accent else alpha(white, 0.8f)
 356:                     line(c, 0.82f * s - thick * u / 2f, 0.82f * s + thick * u / 2f, a + 1.9f, 1.8f, col)
 357:                 }
 358:             }
 359:             "rays" -> {
 360:                 for (i in 0 until 8) {
 361:                     val a = 18f + i * 7.7f
 362:                     val b = bands[i % 4]
 363:                     line(c, 0.30f * s, 0.30f * s + (0.08f + 0.30f * b) * s, a, 1.4f, if (i % 2 == 0) accent else alpha(white, 0.9f))
 364:                 }
 365:             }
 366:             "ripple" -> {
 367:                 for (i in 0 until 2) {
 368:                     val b = bands[i]
 369:                     strokeArc(c, (0.80f - i * 0.2f) * s, 10f, 80f, 1f + 2f * b, alpha(accent, 0.15f + 0.55f * b))
 370:                 }
 371:             }
 372:             "meter" -> {
 373:                 for (col in 0 until 4) {
 374:                     val a = 30f + col * 10f
 375:                     val lit = Math.ceil((4f * bands[col]).toDouble()).toInt()
 376:                     for (k in 0 until 4) {
 377:                         val r = 0.36f * s + k * 4.5f * u
 378:                         dot(c, px(r, a), py(r, a), 1.8f, if (k < lit) accent else alpha(white, 0.12f))
 379:                     }
 380:                 }
 381:             }
 382:             "wave" -> {
 383:                 tmp.rewind()
 384:                 val n = 24
 385:                 for (i in 0 until n) {
 386:                     val t = i / (n - 1f)
 387:                     val a = 8f + 74f * t
 388:                     val f = t * 7f
 389:                     val i0 = f.toInt().coerceIn(0, 6)
 390:                     val v = bands[i0] + (bands[i0 + 1] - bands[i0]) * (f - i0)
 391:                     val r = 0.82f * s + (v - 0.5f) * 8f * u
 392:                     if (i == 0) tmp.moveTo(px(r, a), py(r, a)) else tmp.lineTo(px(r, a), py(r, a))
 393:                 }
 394:                 p.style = Paint.Style.STROKE
 395:                 p.strokeCap = Paint.Cap.ROUND
 396:                 p.strokeWidth = Math.max(1f, 1.6f * u)
 397:                 p.color = alpha(accent, 0.8f)
 398:                 c.drawPath(tmp, p)
 399:             }
 400:             "liquid" -> {
 401:                 tmp.rewind()
 402:                 val n = 16
 403:                 for (i in 0..n) {
 404:                     val a = 90f * i / n
 405:                     val dev = (a - 45f) / 30f
 406:                     val bulge = 1f + 0.07f * Math.exp((-dev * dev).toDouble()).toFloat()
 407:                     val r = s * 0.98f * bulge
 408:                     if (i == 0) tmp.moveTo(px(r, a), py(r, a)) else tmp.lineTo(px(r, a), py(r, a))
 409:                 }
 410:                 p.style = Paint.Style.STROKE
 411:                 p.strokeCap = Paint.Cap.ROUND
 412:                 p.strokeWidth = Math.max(1f, 1.6f * u)
 413:                 p.color = alpha(accent, 0.9f)
 414:                 c.drawPath(tmp, p)
 415:             }
 416:         }
 417:     }
 418: }
===== IdleMediaWatch.kt =====
   1: package com.dynedge
   2: 
   3: import android.content.ComponentName
   4: import android.content.Context
   5: import android.media.MediaMetadata
   6: import android.media.session.MediaController
   7: import android.media.session.MediaSessionManager
   8: import android.media.session.PlaybackState
   9: import android.os.Handler
  10: import android.os.Looper
  11: 
  12: /**
  13:  * Light "is anything playing" watch used while the widget is closed: active-session changes plus a
  14:  * playback-state callback per session. No metadata or art work. Registered only while the screen is on.
  15:  */
  16: class IdleMediaWatch(ctx: Context, private val onChange: (Boolean) -> Unit) {
  17: 
  18:     private val app: Context = ctx.applicationContext ?: ctx
  19:     private val main = Handler(Looper.getMainLooper())
  20:     private val msm = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
  21:     private val component = ComponentName(app, MediaListener::class.java)
  22:     private var started = false
  23:     private val controllers = ArrayList<MediaController>()
  24:     private val callbacks = ArrayList<MediaController.Callback>()
  25: 
  26:     var playing = false
  27:         private set
  28: 
  29:     /** Called with the new track title when the metadata of a session changes (track change). */
  30:     var onTrack: ((String?) -> Unit)? = null
  31: 
  32:     private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
  33:         track(list ?: emptyList())
  34:     }
  35: 
  36:     private inner class Cb : MediaController.Callback() {
  37:         override fun onPlaybackStateChanged(state: PlaybackState?) {
  38:             recompute()
  39:         }
  40: 
  41:         override fun onMetadataChanged(metadata: MediaMetadata?) {
  42:             if (started) onTrack?.invoke(metadata?.getString(MediaMetadata.METADATA_KEY_TITLE))
  43:         }
  44: 
  45:         override fun onSessionDestroyed() {
  46:             recompute()
  47:         }
  48:     }
  49: 
  50:     fun start() {
  51:         if (started) return
  52:         started = true
  53:         try {
  54:             msm?.addOnActiveSessionsChangedListener(sessionsListener, component, main)
  55:         } catch (e: Exception) {
  56:         }
  57:         val list = try {
  58:             msm?.getActiveSessions(component) ?: emptyList()
  59:         } catch (e: Exception) {
  60:             emptyList<MediaController>()
  61:         }
  62:         track(list)
  63:     }
  64: 
  65:     fun stop() {
  66:         if (!started) return
  67:         started = false
  68:         try {
  69:             msm?.removeOnActiveSessionsChangedListener(sessionsListener)
  70:         } catch (e: Exception) {
  71:         }
  72:         untrack()
  73:         playing = false
  74:     }
  75: 
  76:     private fun untrack() {
  77:         for (i in controllers.indices) {
  78:             try {
  79:                 controllers[i].unregisterCallback(callbacks[i])
  80:             } catch (e: Exception) {
  81:             }
  82:         }
  83:         controllers.clear()
  84:         callbacks.clear()
  85:     }
  86: 
  87:     private fun track(list: List<MediaController>) {
  88:         untrack()
  89:         for (c in list) {
  90:             val cb = Cb()
  91:             try {
  92:                 c.registerCallback(cb, main)
  93:                 controllers.add(c)
  94:                 callbacks.add(cb)
  95:             } catch (e: Exception) {
  96:             }
  97:         }
  98:         recompute()
  99:     }
 100: 
 101:     private fun recompute() {
 102:         var p = false
 103:         for (c in controllers) {
 104:             val st = try {
 105:                 c.playbackState?.state
 106:             } catch (e: Exception) {
 107:                 null
 108:             }
 109:             if (st == PlaybackState.STATE_PLAYING) {
 110:                 p = true
 111:                 break
 112:             }
 113:         }
 114:         if (p != playing) {
 115:             playing = p
 116:             if (started) onChange(p)
 117:         }
 118:     }
 119: }
===== IdleVisualizer.kt =====
   1: package com.dynedge
   2: 
   3: import android.content.Context
   4: import android.content.pm.PackageManager
   5: import android.media.audiofx.Visualizer
   6: import kotlin.math.max
   7: import kotlin.math.min
   8: import kotlin.math.sin
   9: import kotlin.math.sqrt
  10: 
  11: /**
  12:  * Four-band level meter for the idle bean. Uses the global output mix (Visualizer session 0, needs
  13:  * RECORD_AUDIO) and falls back to a synthetic animation when that is unavailable or silent.
  14:  * start()/stop() create and release the Visualizer; tick() runs from the owner's throttled frame loop.
  15:  */
  16: class IdleVisualizer(private val ctx: Context) {
  17: 
  18:     /** Smoothed bar heights, 0..1. */
  19:     val levels = FloatArray(4)
  20: 
  21:     private val target = FloatArray(4)
  22:     private val mags = FloatArray(4)
  23:     private var viz: Visualizer? = null
  24:     private var peak = 6f
  25:     private var lastRealMs = 0L
  26:     private var startMs = 0L
  27:     private var phase = 0f
  28:     private var seed = 12345
  29: 
  30:     private val listener = object : Visualizer.OnDataCaptureListener {
  31:         override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
  32: 
  33:         override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
  34:             if (fft != null) reduce(fft)
  35:         }
  36:     }
  37: 
  38:     fun start() {
  39:         if (viz != null) return
  40:         startMs = 0L
  41:         phase = 0f
  42:         if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
  43:         var v: Visualizer? = null
  44:         try {
  45:             v = Visualizer(0)
  46:             v.setEnabled(false)
  47:             v.setCaptureSize(Visualizer.getCaptureSizeRange()[0])
  48:             v.setDataCaptureListener(listener, min(20000, Visualizer.getMaxCaptureRate()), false, true)
  49:             v.setEnabled(true)
  50:             viz = v
  51:         } catch (e: Throwable) {
  52:             try {
  53:                 v?.setEnabled(false)
  54:                 v?.release()
  55:             } catch (e2: Throwable) {
  56:             }
  57:             viz = null
  58:         }
  59:     }
  60: 
  61:     fun stop() {
  62:         val v = viz ?: return
  63:         viz = null
  64:         try {
  65:             v.setEnabled(false)
  66:             v.setDataCaptureListener(null, 0, false, false)
  67:             v.release()
  68:         } catch (e: Throwable) {
  69:         }
  70:     }
  71: 
  72:     /** Bins: [re0 (DC), re(n/2), re1, im1, re2, im2, ...]. Four bands, low to high. */
  73:     private fun reduce(fft: ByteArray) {
  74:         val n = fft.size / 2
  75:         if (n < 8) return
  76:         var top = 0f
  77:         var lo = 1
  78:         for (b in 0 until 4) {
  79:             val hi = max(lo + 1, min(n, EDGES[b] * n / 64))
  80:             var sum = 0f
  81:             for (k in lo until hi) {
  82:                 val re = fft[2 * k].toFloat()
  83:                 val im = fft[2 * k + 1].toFloat()
  84:                 sum += sqrt(re * re + im * im)
  85:             }
  86:             mags[b] = sum / (hi - lo)
  87:             if (mags[b] > top) top = mags[b]
  88:             lo = hi
  89:         }
  90:         peak = max(max(peak * 0.97f, top), 6f)
  91:         for (b in 0 until 4) target[b] = min(1f, mags[b] / peak * WEIGHT[b])
  92:         if (top > 3f) lastRealMs = System.currentTimeMillis()
  93:     }
  94: 
  95:     fun tick(dt: Float, nowMs: Long) {
  96:         if (startMs == 0L) startMs = nowMs
  97:         phase += dt
  98:         val real = viz != null && System.currentTimeMillis() - lastRealMs < 1200L && nowMs - startMs > 0L
  99:         for (b in 0 until 4) {
 100:             val t: Float
 101:             if (real) {
 102:                 t = target[b]
 103:             } else {
 104:                 seed = seed * 1103515245 + 12345
 105:                 val noise = ((seed ushr 16) and 0x7FFF) / 32767f
 106:                 t = (0.5f + 0.38f * sin(phase * SPEED[b] + b * 1.7f) + 0.12f * noise).coerceIn(0.08f, 1f)
 107:             }
 108:             val k = if (t > levels[b]) 0.65f else 0.22f
 109:             levels[b] += (t - levels[b]) * min(1f, k * dt * 30f)
 110:         }
 111:     }
 112: 
 113:     private companion object {
 114:         val EDGES = intArrayOf(3, 7, 17, 48)
 115:         val WEIGHT = floatArrayOf(0.9f, 1.1f, 1.5f, 2.2f)
 116:         val SPEED = floatArrayOf(6.3f, 8.1f, 5.2f, 9.4f)
 117:     }
 118: }
===== LiveBus.kt =====
   1: package com.dynedge
   2: 
   3: import android.os.Handler
   4: import android.os.Looper
   5: 
   6: data class LiveActivity(
   7:     val key: String, val kind: Kind, val title: String,
   8:     val progress: Float,
   9:     val chronoBase: Long,
  10:     val countDown: Boolean,
  11:     val iconRes: Int, val ongoing: Boolean,
  12:     val contentIntent: android.app.PendingIntent?,
  13:     val answer: android.app.PendingIntent?, val decline: android.app.PendingIntent?
  14: ) {
  15:     enum class Kind { CALL, TIMER, PROGRESS, CHARGING, MEDIA }
  16: }
  17: 
  18: /** Holds the current live activities. Thread-safe; onChange is always delivered on the main thread. */
  19: object LiveBus {
  20:     private class Entry(val seq: Long, val a: LiveActivity)
  21: 
  22:     private val lock = Any()
  23:     private val map = LinkedHashMap<String, Entry>()
  24:     private var seq = 0L
  25:     private val main = Handler(Looper.getMainLooper())
  26: 
  27:     var onChange: (() -> Unit)? = null
  28: 
  29:     private val notifier = Runnable { onChange?.invoke() }
  30: 
  31:     fun put(a: LiveActivity) {
  32:         synchronized(lock) {
  33:             map[a.key] = Entry(++seq, a)
  34:         }
  35:         notifyChanged()
  36:     }
  37: 
  38:     fun remove(key: String) {
  39:         val had = synchronized(lock) { map.remove(key) != null }
  40:         if (had) notifyChanged()
  41:     }
  42: 
  43:     fun top(): LiveActivity? = sorted(0)
  44: 
  45:     fun second(): LiveActivity? = sorted(1)
  46: 
  47:     private fun sorted(index: Int): LiveActivity? {
  48:         synchronized(lock) {
  49:             if (map.size <= index) return null
  50:             val list = map.values.sortedWith(
  51:                 compareBy<Entry> { it.a.kind.ordinal }.thenByDescending { it.seq }
  52:             )
  53:             return list.getOrNull(index)?.a
  54:         }
  55:     }
  56: 
  57:     private fun notifyChanged() {
  58:         if (Looper.myLooper() == Looper.getMainLooper()) {
  59:             onChange?.invoke()
  60:         } else {
  61:             main.removeCallbacks(notifier)
  62:             main.post(notifier)
  63:         }
  64:     }
  65: }
===== MainActivity.kt =====
   1: package com.dynedge
   2: 
   3: import android.app.Activity
   4: import android.app.NotificationManager
   5: import android.content.ClipData
   6: import android.content.ClipboardManager
   7: import android.content.ComponentName
   8: import android.content.Context
   9: import android.content.Intent
  10: import android.content.res.ColorStateList
  11: import android.graphics.Color
  12: import android.graphics.Typeface
  13: import android.graphics.drawable.ColorDrawable
  14: import android.graphics.drawable.GradientDrawable
  15: import android.graphics.drawable.RippleDrawable
  16: import android.content.pm.PackageManager
  17: import android.net.Uri
  18: import android.os.Bundle
  19: import android.provider.Settings
  20: import android.util.TypedValue
  21: import android.view.Gravity
  22: import android.view.MotionEvent
  23: import android.view.View
  24: import android.view.ViewGroup
  25: import android.view.WindowInsets
  26: import android.widget.ImageView
  27: import android.widget.LinearLayout
  28: import android.widget.ScrollView
  29: import android.widget.TextView
  30: 
  31: /** Setup and settings screen. Framework Views built in code, pure black, flat rows. */
  32: class MainActivity : Activity() {
  33: 
  34:     private class Perm(val label: String, val ok: () -> Boolean, val intent: () -> Intent, val adb: List<String>)
  35: 
  36:     private var firstInSection = true
  37:     private var showAll = false
  38:     private var adbOpen = false
  39:     private lateinit var setupBox: LinearLayout
  40:     private lateinit var setupCount: TextView
  41:     private val sliders = ArrayList<FlatSlider>()
  42:     private val toggles = ArrayList<FlatToggle>()
  43:     private val previews = ArrayList<IdlePreview>()
  44:     private lateinit var appsBox: LinearLayout
  45:     private lateinit var scroll: ScrollView
  46: 
  47:     private val white = Color.WHITE
  48:     private val grey = Color.parseColor("#8E8E93")
  49:     private val divider = Color.parseColor("#1C1C1E")
  50:     private val red = Color.parseColor("#FF4B3E")
  51: 
  52:     private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
  53:     private fun edit() = Prefs.prefs(this).edit()
  54: 
  55:     override fun onCreate(savedInstanceState: Bundle?) {
  56:         super.onCreate(savedInstanceState)
  57:         // adb hook: am start -n com.dynedge/.MainActivity --ez white_wallpaper true
  58:         if (intent.getBooleanExtra("white_wallpaper", false)) setWhiteWallpaper()
  59:         // adb hook: push an image to Android/data/com.dynedge/files/<name>, then
  60:         // am start -n com.dynedge/.MainActivity --es wallpaper_file <name>
  61:         intent.getStringExtra("wallpaper_file")?.let { name ->
  62:             try {
  63:                 val f = java.io.File(getExternalFilesDir(null), name)
  64:                 java.io.FileInputStream(f).use {
  65:                     android.app.WallpaperManager.getInstance(this)
  66:                         .setStream(it, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
  67:                 }
  68:             } catch (e: Exception) {
  69:                 android.util.Log.w("DynamicEdge", "wallpaper_file failed", e)
  70:             }
  71:         }
  72:         window.statusBarColor = Color.BLACK
  73:         window.navigationBarColor = Color.BLACK
  74:         window.setDecorFitsSystemWindows(false)
  75:         setContentView(build())
  76:     }
  77: 
  78:     private fun build(): ScrollView {
  79:         sliders.clear(); toggles.clear(); previews.clear()
  80:         val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
  81: 
  82:         content.addView(text("Dynamic Edge", 22f, white, Typeface.DEFAULT_BOLD).apply {
  83:             setPadding(dp(16), dp(20), dp(16), dp(0))
  84:         })
  85: 
  86:         // SIZE (first, so the live preview does not cover the slider being dragged)
  87:         header(content, "SIZE")
  88:         addSlider(content, "Corner size", Prefs.IDLE_MIN, Prefs.IDLE_MAX, Prefs.idleDp(this), { "${it.toInt()} dp" },
  89:             Prefs.PREVIEW_IDLE, { Prefs.setIdleDp(this, it) })
  90:         addSlider(content, "Open size", Prefs.OPEN_MIN, Prefs.OPEN_MAX, Prefs.openDp(this), { "${it.toInt()} dp" },
  91:             Prefs.PREVIEW_OPEN, { Prefs.setOpenDp(this, it) })
  92: 
  93:         // WINDOWS (on/off + order; long-press a row and drag)
  94:         header(content, "WINDOWS")
  95:         addWindows(content)
  96: 
  97:         // EDGE (layout editor, clipboard history, live activities)
  98:         header(content, "EDGE")
  99:         content.addView(simpleRow("Quick layout", ">", grey) {
 100:             open { Intent(this, QuickEditorActivity::class.java) }
 101:         })
 102:         sep(content)
 103:         content.addView(simpleRow("Clipboard", ">", grey) {
 104:             open { Intent(this, ClipboardActivity::class.java) }
 105:         })
 106:         sep(content)
 107:         addSwitch(content, "Live activities", Prefs.live(this)) { edit().putBoolean(Prefs.KEY_LIVE, it).apply() }
 108: 
 109:         // LOOKS
 110:         header(content, "LOOKS")
 111:         addSwatches(content)
 112:         addSlider(content, "Corner gradient", 0f, 100f, Prefs.gradient(this).toFloat(), { "${it.toInt()}%" },
 113:             Prefs.PREVIEW_OPEN, { edit().putInt(Prefs.KEY_GRADIENT, it.toInt()).apply(); previews.forEach { p -> p.gradient = it.toInt() } })
 114:         addSwitch(content, "Border", Prefs.rim(this)) { edit().putBoolean(Prefs.KEY_RIM, it).apply() }
 115:         addSwitch(content, "Glow", Prefs.glow(this)) { edit().putBoolean(Prefs.KEY_GLOW, it).apply() }
 116:         addSwitch(content, "Ring tiles", Prefs.bands(this)) { edit().putBoolean(Prefs.KEY_BANDS, it).apply() }
 117:         addPicker(content, IdlePreview.FILL, Prefs.FILLS, Prefs.fill(this), Prefs.KEY_FILL)
 118:         addSlider(content, "Flare", 0f, Prefs.FLARE_MAX, Prefs.flareDp(this), { "${it.toInt()} dp" },
 119:             Prefs.PREVIEW_IDLE, { edit().putFloat(Prefs.KEY_FLARE, it).apply() })
 120:         addPicker(content, IdlePreview.IDLE, Prefs.IDLE_STYLES, Prefs.idleStyle(this), Prefs.KEY_IDLE_STYLE)
 121:         addSwitch(content, "Music visualizer", Prefs.visualizer(this)) { edit().putBoolean(Prefs.KEY_VISUALIZER, it).apply() }
 122:         addPicker(content, IdlePreview.VIZ, Prefs.VIZ_STYLES, Prefs.vizStyle(this), Prefs.KEY_VIZ_STYLE)
 123:         addSwitch(content, "Beat reaction", Prefs.beat(this)) { edit().putBoolean(Prefs.KEY_BEAT, it).apply() }
 124: 
 125:         // FEEL
 126:         header(content, "FEEL")
 127:         addSlider(content, "Speed", 0f, 100f, Prefs.speed(this).toFloat(), { "${it.toInt()}%" },
 128:             null, { edit().putInt(Prefs.KEY_SPEED, it.toInt()).apply() })
 129:         addSlider(content, "Bounce", 0f, 100f, Prefs.bounce(this).toFloat(), { "${it.toInt()}%" },
 130:             null, { edit().putInt(Prefs.KEY_BOUNCE, it.toInt()).apply() })
 131:         addSwitch(content, "Haptics", Prefs.haptics(this)) { edit().putBoolean(Prefs.KEY_HAPTICS, it).apply() }
 132:         sep(content)
 133:         content.addView(text("Reset looks", 15f, red, Typeface.DEFAULT).apply {
 134:             setPadding(dp(16), dp(8), dp(16), dp(8))
 135:             minimumHeight = dp(44)
 136:             gravity = Gravity.CENTER_VERTICAL
 137:             isClickable = true
 138:             background = ripple()
 139:             setOnClickListener {
 140:                 Prefs.resetLooks(this@MainActivity)
 141:                 val y = scroll.scrollY
 142:                 val s = build()
 143:                 setContentView(s)
 144:                 s.post { s.scrollTo(0, y) }
 145:                 buildApps()
 146:                 refreshSetup()
 147:             }
 148:         })
 149: 
 150:         // APPS
 151:         header(content, "APPS")
 152:         appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
 153:         content.addView(appsBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
 154: 
 155:         // SETUP (last)
 156:         setupCount = text("", 11f, grey, Typeface.DEFAULT_BOLD)
 157:         header(content, "SETUP", setupCount)
 158:         setupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
 159:         content.addView(setupBox, matchWrap())
 160:         content.addView(View(this), LinearLayout.LayoutParams(1, dp(24)))
 161: 
 162:         scroll = ScrollView(this).apply {
 163:             setBackgroundColor(Color.BLACK)
 164:             isFillViewport = true
 165:             addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
 166:             setOnApplyWindowInsetsListener { v, insets ->
 167:                 val bars = insets.getInsets(WindowInsets.Type.systemBars())
 168:                 v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
 169:                 WindowInsets.CONSUMED
 170:             }
 171:         }
 172:         return scroll
 173:     }
 174: 
 175:     override fun onPause() {
 176:         super.onPause()
 177:         edit().putString(Prefs.KEY_PREVIEW, Prefs.PREVIEW_NONE).apply()
 178:     }
 179: 
 180:     override fun onResume() {
 181:         super.onResume()
 182:         refreshSetup()
 183:         buildApps()
 184:     }
 185: 
 186:     private fun perms(): List<Perm> = listOf(
 187:         Perm("Overlay service", { isOverlayServiceEnabled() }, { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
 188:             listOf(
 189:                 "adb shell settings put secure enabled_accessibility_services com.dynedge/com.dynedge.EdgeService",
 190:                 "adb shell settings put secure accessibility_enabled 1"
 191:             )),
 192:         Perm("Media access", { isMediaListenerEnabled() }, { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
 193:             listOf("adb shell cmd notification allow_listener com.dynedge/com.dynedge.MediaListener")),
 194:         Perm("Do Not Disturb access", { isDndGranted() }, { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
 195:             listOf("adb shell cmd notification allow_dnd com.dynedge")),
 196:         Perm("Modify system settings", { Settings.System.canWrite(this) },
 197:             { Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")) },
 198:             listOf("adb shell appops set com.dynedge WRITE_SETTINGS allow")),
 199:         Perm("Assistant corner swipe off", { !isAssistSwipeOn() }, { Intent(Settings.ACTION_SETTINGS) },
 200:             listOf("adb shell pm grant com.dynedge android.permission.WRITE_SECURE_SETTINGS")),
 201:         Perm("Microphone", { granted("android.permission.RECORD_AUDIO") }, { appDetails() },
 202:             listOf("adb shell pm grant com.dynedge android.permission.RECORD_AUDIO")),
 203:         Perm("Secure settings", { granted("android.permission.WRITE_SECURE_SETTINGS") }, { appDetails() },
 204:             listOf("adb shell pm grant com.dynedge android.permission.WRITE_SECURE_SETTINGS"))
 205:     )
 206: 
 207:     private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
 208: 
 209:     private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
 210: 
 211:     private fun refreshSetup() {
 212:         setupBox.removeAllViews()
 213:         val all = perms()
 214:         val missing = all.filter { p -> !(try { p.ok() } catch (e: Exception) { false }) }
 215:         val accent = Prefs.accent(this)
 216:         if (missing.isEmpty()) {
 217:             setupCount.text = ""
 218:             val row = simpleRow("Setup", "Done", Color.parseColor("#30D158")) {
 219:                 showAll = !showAll
 220:                 refreshSetup()
 221:             }
 222:             setupBox.addView(row, matchWrap())
 223:             if (showAll) {
 224:                 for (p in all) {
 225:                     setupBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
 226:                     setupBox.addView(simpleRow(p.label, "On", white) { open(p.intent) }, matchWrap())
 227:                 }
 228:             }
 229:             return
 230:         }
 231:         setupCount.text = "${missing.size} missing"
 232:         for ((i, p) in missing.withIndex()) {
 233:             if (i > 0) setupBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
 234:             setupBox.addView(simpleRow(p.label, "Fix", accent) { open(p.intent) }, matchWrap())
 235:         }
 236:         val cmds = LinkedHashSet<String>()
 237:         missing.forEach { cmds.addAll(it.adb) }
 238:         val cmdText = cmds.joinToString("\n")
 239:         val adbText = text(cmdText, 11f, white, Typeface.MONOSPACE).apply {
 240:             setPadding(dp(16), dp(8), dp(16), dp(12))
 241:             visibility = if (adbOpen) View.VISIBLE else View.GONE
 242:             setOnLongClickListener {
 243:                 val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
 244:                 cm.setPrimaryClip(ClipData.newPlainText("adb", cmdText))
 245:                 performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
 246:                 true
 247:             }
 248:         }
 249:         val adbRow = simpleRow("ADB", if (adbOpen) "Hide" else "Show", grey) {}
 250:         val adbState = adbRow.getChildAt(1) as TextView
 251:         adbRow.setOnClickListener {
 252:             adbOpen = !adbOpen
 253:             adbText.visibility = if (adbOpen) View.VISIBLE else View.GONE
 254:             adbState.text = if (adbOpen) "Hide" else "Show"
 255:         }
 256:         setupBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
 257:         setupBox.addView(adbRow, matchWrap())
 258:         setupBox.addView(adbText, matchWrap())
 259:     }
 260: 
 261:     private fun open(intent: () -> Intent) {
 262:         try {
 263:             startActivity(intent())
 264:         } catch (e: Exception) {
 265:         }
 266:     }
 267: 
 268:     private fun simpleRow(label: String, state: String, stateColor: Int, onClick: () -> Unit): LinearLayout =
 269:         LinearLayout(this).apply {
 270:             orientation = LinearLayout.HORIZONTAL
 271:             gravity = Gravity.CENTER_VERTICAL
 272:             minimumHeight = dp(44)
 273:             setPadding(dp(16), dp(6), dp(16), dp(6))
 274:             isClickable = true
 275:             background = ripple()
 276:             addView(text(label, 15f, white, Typeface.DEFAULT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
 277:             addView(text(state, 14f, stateColor, Typeface.DEFAULT))
 278:             setOnClickListener { onClick() }
 279:         }
 280: 
 281:     private fun buildApps() {
 282:         appsBox.removeAllViews()
 283:         val pm = packageManager
 284:         val list = Prefs.apps(this).toMutableList()
 285:         val shown = ArrayList<ComponentName>()
 286:         for (cn in list) {
 287:             val info = try {
 288:                 pm.getActivityInfo(cn, 0)
 289:             } catch (e: Exception) {
 290:                 continue
 291:             }
 292:             shown.add(cn)
 293:             val row = LinearLayout(this).apply {
 294:                 orientation = LinearLayout.HORIZONTAL
 295:                 gravity = Gravity.CENTER_VERTICAL
 296:                 minimumHeight = dp(44)
 297:                 setPadding(dp(16), dp(6), dp(16), dp(6))
 298:                 isClickable = true
 299:                 background = ripple()
 300:             }
 301:             val icon = ImageView(this).apply {
 302:                 try {
 303:                     setImageDrawable(info.loadIcon(pm))
 304:                 } catch (e: Exception) {
 305:                 }
 306:             }
 307:             row.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
 308:             val name = text(info.loadLabel(pm).toString(), 15f, white, Typeface.DEFAULT).apply {
 309:                 setPadding(dp(12), 0, 0, 0)
 310:             }
 311:             row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
 312:             row.addView(text("Remove", 13f, grey, Typeface.DEFAULT))
 313:             row.setOnClickListener {
 314:                 shown.remove(cn)
 315:                 Prefs.setApps(this, shown)
 316:                 buildApps()
 317:             }
 318:             if (appsBox.childCount > 0) appsBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
 319:             appsBox.addView(row, matchWrap())
 320:         }
 321:         if (shown.size < Prefs.MAX_APPS) {
 322:             val add = text("Add app", 15f, red, Typeface.DEFAULT).apply {
 323:                 minimumHeight = dp(44)
 324:                 gravity = Gravity.CENTER_VERTICAL
 325:                 setPadding(dp(16), dp(6), dp(16), dp(6))
 326:                 isClickable = true
 327:                 background = ripple()
 328:                 setOnClickListener {
 329:                     // persist the cleaned list so removed packages do not linger
 330:                     Prefs.setApps(this@MainActivity, shown)
 331:                     startActivity(Intent(this@MainActivity, AppPickerActivity::class.java))
 332:                 }
 333:             }
 334:             if (appsBox.childCount > 0) appsBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
 335:             appsBox.addView(add, matchWrap())
 336:         }
 337:     }
 338: 
 339:     private fun ripple() = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, ColorDrawable(Color.WHITE))
 340: 
 341:     private fun line(): View = View(this).apply { setBackgroundColor(divider) }
 342: 
 343:     private fun text(s: String, sp: Float, color: Int, face: Typeface): TextView = TextView(this).apply {
 344:         text = s
 345:         setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
 346:         setTextColor(color)
 347:         typeface = face
 348:     }
 349: 
 350:     private fun header(parent: LinearLayout, s: String, right: TextView? = null) {
 351:         firstInSection = true
 352:         val row = LinearLayout(this).apply {
 353:             orientation = LinearLayout.HORIZONTAL
 354:             gravity = Gravity.CENTER_VERTICAL
 355:             setPadding(dp(16), dp(20), dp(16), dp(4))
 356:         }
 357:         row.addView(text(s.uppercase(), 11f, grey, Typeface.DEFAULT_BOLD).apply { letterSpacing = 0.1f },
 358:             LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
 359:         if (right != null) row.addView(right)
 360:         parent.addView(row, matchWrap())
 361:     }
 362: 
 363:     /** Divider between rows inside a section (not before the first). */
 364:     private fun sep(parent: LinearLayout) {
 365:         if (!firstInSection) parent.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
 366:         firstInSection = false
 367:     }
 368: 
 369:     private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
 370: 
 371:     private fun applyAccent(c: Int) {
 372:         sliders.forEach { it.accent = c }
 373:         toggles.forEach { it.accent = c }
 374:         previews.forEach { it.accent = c }
 375:     }
 376: 
 377:     private fun addSlider(
 378:         parent: LinearLayout, label: String, min: Float, max: Float, value: Float,
 379:         fmt: (Float) -> String, preview: String?, save: (Float) -> Unit
 380:     ) {
 381:         sep(parent)
 382:         val head = LinearLayout(this).apply {
 383:             orientation = LinearLayout.HORIZONTAL
 384:             setPadding(dp(16), dp(8), dp(16), 0)
 385:         }
 386:         val valueView = text(fmt(value), 14f, grey, Typeface.DEFAULT)
 387:         head.addView(text(label, 15f, white, Typeface.DEFAULT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
 388:         head.addView(valueView)
 389:         parent.addView(head, matchWrap())
 390: 
 391:         val s = FlatSlider(this).apply {
 392:             this.min = min
 393:             this.max = max
 394:             this.value = value
 395:             accent = Prefs.accent(this@MainActivity)
 396:             setPadding(dp(16), 0, dp(16), 0)
 397:             onStart = { if (preview != null) edit().putString(Prefs.KEY_PREVIEW, preview).apply() }
 398:             onChange = { v -> valueView.text = fmt(v); save(v) }
 399:             onStop = { if (preview != null) edit().putString(Prefs.KEY_PREVIEW, Prefs.PREVIEW_NONE).apply() }
 400:         }
 401:         sliders.add(s)
 402:         parent.addView(s, matchWrap())
 403:     }
 404: 
 405:     private fun addSwitch(parent: LinearLayout, label: String, on: Boolean, save: (Boolean) -> Unit) {
 406:         sep(parent)
 407:         val row = LinearLayout(this).apply {
 408:             orientation = LinearLayout.HORIZONTAL
 409:             gravity = Gravity.CENTER_VERTICAL
 410:             minimumHeight = dp(44)
 411:             setPadding(dp(16), dp(4), dp(16), dp(4))
 412:         }
 413:         row.addView(text(label, 15f, white, Typeface.DEFAULT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
 414:         val t = FlatToggle(this).apply {
 415:             accent = Prefs.accent(this@MainActivity)
 416:             checked = on
 417:             onToggle = save
 418:         }
 419:         toggles.add(t)
 420:         row.addView(t)
 421:         parent.addView(row, matchWrap())
 422:     }
 423: 
 424:     private fun addSwatches(parent: LinearLayout) {
 425:         sep(parent)
 426:         val row = LinearLayout(this).apply {
 427:             orientation = LinearLayout.HORIZONTAL
 428:             gravity = Gravity.CENTER_VERTICAL
 429:             minimumHeight = dp(44)
 430:             setPadding(dp(16), dp(8), dp(16), dp(8))
 431:         }
 432:         val cur = Prefs.accent(this)
 433:         val dots = ArrayList<View>()
 434:         fun mark(sel: Int) {
 435:             for ((i, v) in dots.withIndex()) {
 436:                 val c = Prefs.ACCENTS[i]
 437:                 v.background = GradientDrawable().apply {
 438:                     shape = GradientDrawable.OVAL
 439:                     setColor(c)
 440:                     if (c == sel) setStroke(dp(2), Color.WHITE) else setStroke(0, Color.TRANSPARENT)
 441:                 }
 442:             }
 443:         }
 444:         for ((i, c) in Prefs.ACCENTS.withIndex()) {
 445:             val v = View(this).apply {
 446:                 isClickable = true
 447:                 setOnClickListener {
 448:                     edit().putInt(Prefs.KEY_ACCENT, c).apply()
 449:                     mark(c)
 450:                     applyAccent(c)
 451:                     performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
 452:                 }
 453:             }
 454:             dots.add(v)
 455:             row.addView(v, LinearLayout.LayoutParams(dp(28), dp(28)))
 456:             if (i < Prefs.ACCENTS.size - 1) {
 457:                 row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
 458:             }
 459:         }
 460:         mark(cur)
 461:         parent.addView(row, matchWrap())
 462:     }
 463: 
 464:     /** Row of mini bean previews (no labels); the selected one gets an accent ring. */
 465:     private fun addPicker(parent: LinearLayout, kind: Int, ids: List<String>, cur: String, key: String) {
 466:         sep(parent)
 467:         val row = LinearLayout(this).apply {
 468:             orientation = LinearLayout.HORIZONTAL
 469:             gravity = Gravity.CENTER_VERTICAL
 470:             minimumHeight = dp(44)
 471:             setPadding(dp(16), dp(8), dp(16), dp(8))
 472:         }
 473:         val availDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density - 32f
 474:         val size = ((availDp - 4f * (ids.size - 1)) / ids.size).coerceIn(30f, 44f)
 475:         val mine = ArrayList<IdlePreview>()
 476:         for ((i, id) in ids.withIndex()) {
 477:             val p = IdlePreview(this, id, kind).apply {
 478:                 sizeDp = size
 479:                 accent = Prefs.accent(this@MainActivity)
 480:                 gradient = Prefs.gradient(this@MainActivity)
 481:                 selectedStyle = id == cur
 482:                 isClickable = true
 483:                 setOnClickListener {
 484:                     edit().putString(key, id).apply()
 485:                     mine.forEach { it.selectedStyle = it === this }
 486:                     performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
 487:                 }
 488:             }
 489:             mine.add(p)
 490:             previews.add(p)
 491:             row.addView(p)
 492:             if (i < ids.size - 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
 493:         }
 494:         parent.addView(row, matchWrap())
 495:     }
 496: 
 497:     // ---------------------------------------------------------------- windows list
 498: 
 499:     private var winEntries = ArrayList<WindowsConfig.Entry>()
 500:     private val winRows = ArrayList<View>()
 501:     private lateinit var winBox: LinearLayout
 502: 
 503:     private fun addWindows(parent: LinearLayout) {
 504:         winEntries = ArrayList(WindowsConfig.load(this))
 505:         winBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
 506:         parent.addView(winBox, matchWrap())
 507:         buildWindowRows()
 508:     }
 509: 
 510:     private fun buildWindowRows() {
 511:         winBox.removeAllViews()
 512:         winRows.clear()
 513:         val rowH = dp(48)
 514:         val accent = Prefs.accent(this)
 515:         for (e in winEntries) {
 516:             val row = LinearLayout(this).apply {
 517:                 orientation = LinearLayout.HORIZONTAL
 518:                 gravity = Gravity.CENTER_VERTICAL
 519:                 setPadding(dp(16), 0, dp(16), 0)
 520:                 tag = e.id
 521:             }
 522:             val icon = ImageView(this).apply {
 523:                 setImageResource(WindowsConfig.icon(e.id))
 524:                 setColorFilter(if (e.on) white else grey)
 525:             }
 526:             row.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
 527:             val label = text(WindowsConfig.label(e.id), 15f, if (e.on) white else grey, Typeface.DEFAULT).apply {
 528:                 setPadding(dp(14), 0, 0, 0)
 529:             }
 530:             row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
 531:             val t = FlatToggle(this).apply {
 532:                 this.accent = accent
 533:                 checked = e.on
 534:                 onToggle = { on ->
 535:                     val idx = winEntries.indexOfFirst { it.id == e.id }
 536:                     if (idx >= 0) winEntries[idx] = WindowsConfig.Entry(e.id, on)
 537:                     icon.setColorFilter(if (on) white else grey)
 538:                     label.setTextColor(if (on) white else grey)
 539:                     WindowsConfig.save(this@MainActivity, winEntries)
 540:                 }
 541:             }
 542:             toggles.add(t)
 543:             row.addView(t)
 544:             attachDrag(row)
 545:             winBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH))
 546:             winRows.add(row)
 547:         }
 548:     }
 549: 
 550:     private fun attachDrag(row: View) {
 551:         val rowH = dp(48)
 552:         var dragging = false
 553:         var orig = 0
 554:         var downY = 0f
 555:         var target = 0
 556:         row.setOnLongClickListener {
 557:             orig = winRows.indexOf(row)
 558:             if (orig < 0) return@setOnLongClickListener false
 559:             dragging = true
 560:             target = orig
 561:             downY = lastRawY
 562:             row.parent?.requestDisallowInterceptTouchEvent(true)
 563:             row.setBackgroundColor(divider)
 564:             row.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
 565:             true
 566:         }
 567:         row.setOnTouchListener { _, e ->
 568:             when (e.actionMasked) {
 569:                 MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> if (!dragging) lastRawY = e.rawY
 570:             }
 571:             if (!dragging) return@setOnTouchListener false
 572:             when (e.actionMasked) {
 573:                 MotionEvent.ACTION_MOVE -> {
 574:                     val last = winRows.size - 1
 575:                     val dy = (e.rawY - downY).coerceIn(-orig * rowH.toFloat(), (last - orig) * rowH.toFloat())
 576:                     row.translationY = dy
 577:                     val t = Math.round(orig + dy / rowH).coerceIn(0, last)
 578:                     if (t != target) {
 579:                         target = t
 580:                         row.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
 581:                     }
 582:                     for ((i, r) in winRows.withIndex()) {
 583:                         if (r === row) continue
 584:                         r.translationY = when {
 585:                             i in (orig + 1)..target -> -rowH.toFloat()
 586:                             i in target until orig -> rowH.toFloat()
 587:                             else -> 0f
 588:                         }
 589:                     }
 590:                     true
 591:                 }
 592:                 MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
 593:                     dragging = false
 594:                     row.isPressed = false
 595:                     if (target != orig) {
 596:                         val moved = winEntries.removeAt(orig)
 597:                         winEntries.add(target, moved)
 598:                         WindowsConfig.save(this, winEntries)
 599:                     }
 600:                     buildWindowRows()
 601:                     true
 602:                 }
 603:                 else -> true
 604:             }
 605:         }
 606:     }
 607: 
 608:     private var lastRawY = 0f
 609: 
 610:     private fun isOverlayServiceEnabled(): Boolean {
 611:         val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
 612:             ?: return false
 613:         val cn = ComponentName(this, EdgeService::class.java)
 614:         return enabled.contains(cn.flattenToString()) || enabled.contains(cn.flattenToShortString())
 615:     }
 616: 
 617:     private fun isMediaListenerEnabled(): Boolean {
 618:         val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
 619:         return enabled.contains(packageName)
 620:     }
 621: 
 622:     private fun isDndGranted(): Boolean {
 623:         val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
 624:         return nm.isNotificationPolicyAccessGranted
 625:     }
 626: 
 627:     private fun isAssistSwipeOn(): Boolean =
 628:         Settings.Secure.getInt(contentResolver, "navigation_google_assist", 0) != 0
 629: 
 630:     private fun setWhiteWallpaper() {
 631:         try {
 632:             val bmp = android.graphics.Bitmap.createBitmap(108, 240, android.graphics.Bitmap.Config.ARGB_8888)
 633:             bmp.eraseColor(Color.WHITE)
 634:             android.app.WallpaperManager.getInstance(this)
 635:                 .setBitmap(bmp, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
 636:             bmp.recycle()
 637:         } catch (e: Exception) {
 638:             android.util.Log.w("DynamicEdge", "wallpaper failed", e)
 639:         }
 640:     }
 641: }
===== MediaBridge.kt =====
   1: package com.dynedge
   2: 
   3: import android.content.ComponentName
   4: import android.content.Context
   5: import android.content.Intent
   6: import android.graphics.Bitmap
   7: import android.graphics.Canvas
   8: import android.graphics.Paint
   9: import android.graphics.Rect
  10: import android.media.AudioManager
  11: import android.media.MediaMetadata
  12: import android.media.session.MediaController
  13: import android.media.session.MediaSessionManager
  14: import android.media.session.PlaybackState
  15: import android.os.Handler
  16: import android.os.Looper
  17: import android.os.SystemClock
  18: import android.view.KeyEvent
  19: 
  20: /**
  21:  * Follows the active media session: title, artist, art, playback state, position and transport.
  22:  * Listeners exist only between start() and stop(). All onChange callbacks arrive on the main thread.
  23:  */
  24: class MediaBridge(private val ctx: Context, private val artPx: Int, private val onChange: () -> Unit) {
  25: 
  26:     private val app: Context = ctx.applicationContext ?: ctx
  27:     private val main = Handler(Looper.getMainLooper())
  28:     private val msm: MediaSessionManager? =
  29:         app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
  30:     private val audio: AudioManager? = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
  31:     private val listenerComponent = ComponentName(app, MediaListener::class.java)
  32: 
  33:     private var started = false
  34:     private var controller: MediaController? = null
  35:     private var playback: PlaybackState? = null
  36:     private var sourceArt: Bitmap? = null
  37: 
  38:     var title: String? = null
  39:         private set
  40:     var artist: String? = null
  41:         private set
  42:     var art: Bitmap? = null
  43:         private set
  44:     var durationMs: Long = 0L
  45:         private set
  46: 
  47:     val hasSession: Boolean get() = controller != null
  48:     val isPlaying: Boolean get() = playback?.state == PlaybackState.STATE_PLAYING
  49: 
  50:     private fun fire() = onChange()
  51: 
  52:     private val sessionsListener =
  53:         MediaSessionManager.OnActiveSessionsChangedListener { list ->
  54:             select(list ?: emptyList())
  55:             fire()
  56:         }
  57: 
  58:     private val callback = object : MediaController.Callback() {
  59:         override fun onPlaybackStateChanged(state: PlaybackState?) {
  60:             refresh()
  61:             fire()
  62:         }
  63: 
  64:         override fun onMetadataChanged(metadata: MediaMetadata?) {
  65:             refresh()
  66:             fire()
  67:         }
  68: 
  69:         override fun onSessionDestroyed() {
  70:             select(querySessions())
  71:             fire()
  72:         }
  73:     }
  74: 
  75:     fun start() {
  76:         if (started) return
  77:         started = true
  78:         val m = msm
  79:         if (m != null) {
  80:             try {
  81:                 m.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, main)
  82:             } catch (e: Exception) {
  83:             }
  84:         }
  85:         select(querySessions())
  86:     }
  87: 
  88:     fun stop() {
  89:         if (!started) return
  90:         started = false
  91:         val m = msm
  92:         if (m != null) {
  93:             try {
  94:                 m.removeOnActiveSessionsChangedListener(sessionsListener)
  95:             } catch (e: Exception) {
  96:             }
  97:         }
  98:         controller?.let {
  99:             try {
 100:                 it.unregisterCallback(callback)
 101:             } catch (e: Exception) {
 102:             }
 103:         }
 104:         controller = null
 105:         playback = null
 106:         sourceArt = null
 107:         art = null
 108:         title = null
 109:         artist = null
 110:         durationMs = 0L
 111:     }
 112: 
 113:     fun positionMs(): Long {
 114:         val s = playback ?: return 0L
 115:         var p = s.position
 116:         if (s.state == PlaybackState.STATE_PLAYING) {
 117:             val elapsed = SystemClock.elapsedRealtime() - s.lastPositionUpdateTime
 118:             p += (elapsed * s.playbackSpeed).toLong()
 119:         }
 120:         if (p < 0L) p = 0L
 121:         if (durationMs > 0L && p > durationMs) p = durationMs
 122:         return p
 123:     }
 124: 
 125:     fun playPause() {
 126:         val c = controller
 127:         if (c == null) {
 128:             dispatchKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
 129:             return
 130:         }
 131:         try {
 132:             if (isPlaying) c.transportControls.pause() else c.transportControls.play()
 133:         } catch (e: Exception) {
 134:         }
 135:     }
 136: 
 137:     fun next() {
 138:         try {
 139:             controller?.transportControls?.skipToNext()
 140:         } catch (e: Exception) {
 141:         }
 142:     }
 143: 
 144:     fun prev() {
 145:         try {
 146:             controller?.transportControls?.skipToPrevious()
 147:         } catch (e: Exception) {
 148:         }
 149:     }
 150: 
 151:     /** True when the active session supports seeking and the duration is known. */
 152:     val canSeek: Boolean
 153:         get() {
 154:             val c = controller ?: return false
 155:             val actions = c.playbackState?.actions ?: return false
 156:             return durationMs > 0L && (actions and android.media.session.PlaybackState.ACTION_SEEK_TO) != 0L
 157:         }
 158: 
 159:     fun seekTo(ms: Long) {
 160:         try {
 161:             controller?.transportControls?.seekTo(ms.coerceIn(0L, if (durationMs > 0L) durationMs else ms))
 162:         } catch (e: Exception) {
 163:         }
 164:     }
 165: 
 166:     fun openPlayer(): Boolean {
 167:         val c = controller ?: return false
 168:         try {
 169:             val pi = c.sessionActivity
 170:             if (pi != null) {
 171:                 pi.send()
 172:                 return true
 173:             }
 174:         } catch (e: Exception) {
 175:         }
 176:         return try {
 177:             val i = app.packageManager.getLaunchIntentForPackage(c.packageName) ?: return false
 178:             i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
 179:             ctx.startActivity(i)
 180:             true
 181:         } catch (e: Exception) {
 182:             false
 183:         }
 184:     }
 185: 
 186:     // ---- internals --------------------------------------------------------------------
 187: 
 188:     private fun dispatchKey(code: Int) {
 189:         val am = audio ?: return
 190:         try {
 191:             am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
 192:             am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
 193:         } catch (e: Exception) {
 194:         }
 195:     }
 196: 
 197:     private fun querySessions(): List<MediaController> {
 198:         val m = msm ?: return emptyList()
 199:         return try {
 200:             m.getActiveSessions(listenerComponent)
 201:         } catch (e: SecurityException) {
 202:             emptyList()
 203:         } catch (e: Exception) {
 204:             emptyList()
 205:         }
 206:     }
 207: 
 208:     /** Picks the playing session, else the first, and switches callbacks if it changed. */
 209:     private fun select(list: List<MediaController>) {
 210:         val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
 211:             ?: list.firstOrNull()
 212:         val current = controller
 213:         val same = best != null && current != null && best.sessionToken == current.sessionToken
 214:         if (!same) {
 215:             if (current != null) {
 216:                 try {
 217:                     current.unregisterCallback(callback)
 218:                 } catch (e: Exception) {
 219:                 }
 220:             }
 221:             controller = best
 222:             if (best != null) {
 223:                 try {
 224:                     best.registerCallback(callback, main)
 225:                 } catch (e: Exception) {
 226:                 }
 227:             }
 228:         }
 229:         refresh()
 230:     }
 231: 
 232:     private fun refresh() {
 233:         val c = controller
 234:         if (c == null) {
 235:             playback = null
 236:             title = null
 237:             artist = null
 238:             durationMs = 0L
 239:             sourceArt = null
 240:             art = null
 241:             return
 242:         }
 243:         playback = try {
 244:             c.playbackState
 245:         } catch (e: Exception) {
 246:             null
 247:         }
 248:         val meta: MediaMetadata? = try {
 249:             c.metadata
 250:         } catch (e: Exception) {
 251:             null
 252:         }
 253:         title = if (meta == null) null else firstText(
 254:             meta,
 255:             MediaMetadata.METADATA_KEY_TITLE,
 256:             MediaMetadata.METADATA_KEY_DISPLAY_TITLE
 257:         )
 258:         artist = if (meta == null) null else firstText(
 259:             meta,
 260:             MediaMetadata.METADATA_KEY_ARTIST,
 261:             MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
 262:             MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE
 263:         )
 264:         durationMs = if (meta == null) 0L else maxOf(0L, meta.getLong(MediaMetadata.METADATA_KEY_DURATION))
 265:         updateArt(meta)
 266:     }
 267: 
 268:     private fun firstText(meta: MediaMetadata, vararg keys: String): String? {
 269:         for (k in keys) {
 270:             val v = meta.getString(k)
 271:             if (!v.isNullOrBlank()) return v
 272:         }
 273:         return null
 274:     }
 275: 
 276:     private fun updateArt(meta: MediaMetadata?) {
 277:         val src: Bitmap? = if (meta == null) null else (
 278:             meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
 279:                 ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
 280:                 ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
 281:             )
 282:         if (src === sourceArt) return
 283:         sourceArt = src
 284:         // The previous scaled bitmap is not recycled: the view may still be drawing it. GC reclaims it.
 285:         art = if (src == null) null else centerCrop(src)
 286:     }
 287: 
 288:     private fun centerCrop(src: Bitmap): Bitmap? {
 289:         if (artPx <= 0 || src.width <= 0 || src.height <= 0) return null
 290:         return try {
 291:             val side = minOf(src.width, src.height)
 292:             val left = (src.width - side) / 2
 293:             val top = (src.height - side) / 2
 294:             val out = Bitmap.createBitmap(artPx, artPx, Bitmap.Config.ARGB_8888)
 295:             val paint = Paint(Paint.FILTER_BITMAP_FLAG)
 296:             Canvas(out).drawBitmap(src, Rect(left, top, left + side, top + side), Rect(0, 0, artPx, artPx), paint)
 297:             out
 298:         } catch (e: Exception) {
 299:             null
 300:         }
 301:     }
 302: }
===== MediaListener.kt =====
   1: package com.dynedge
   2: 
   3: import android.app.Notification
   4: import android.app.PendingIntent
   5: import android.service.notification.NotificationListenerService
   6: import android.service.notification.StatusBarNotification
   7: 
   8: /**
   9:  * Enabling it allows MediaSessionManager.getActiveSessions(). It also classifies notifications into
  10:  * live activities (calls, timers, progress) and feeds LiveBus.
  11:  */
  12: class MediaListener : NotificationListenerService() {
  13: 
  14:     override fun onListenerConnected() {
  15:         NotifFeed.listener = this
  16:         rebuildFeed()
  17:         try {
  18:             val all = activeNotifications ?: return
  19:             for (sbn in all) handle(sbn)
  20:         } catch (e: Exception) {
  21:         }
  22:     }
  23: 
  24:     override fun onListenerDisconnected() {
  25:         if (NotifFeed.listener === this) NotifFeed.listener = null
  26:         NotifFeed.publish(emptyList())
  27:     }
  28: 
  29:     override fun onNotificationPosted(sbn: StatusBarNotification?) {
  30:         if (sbn != null) handle(sbn)
  31:         rebuildFeed()
  32:     }
  33: 
  34:     override fun onNotificationRemoved(sbn: StatusBarNotification?) {
  35:         if (sbn != null) LiveBus.remove(keyOf(sbn))
  36:         rebuildFeed()
  37:     }
  38: 
  39:     private fun rebuildFeed() {
  40:         try {
  41:             val all = activeNotifications ?: return
  42:             val out = ArrayList<NotifFeed.Item>()
  43:             for (sbn in all.sortedByDescending { it.postTime }) {
  44:                 val n = sbn.notification ?: continue
  45:                 if (sbn.packageName == packageName) continue
  46:                 if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT or
  47:                         Notification.FLAG_FOREGROUND_SERVICE) != 0) continue
  48:                 if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_CALL) continue
  49:                 val ex = n.extras ?: continue
  50:                 if (ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) continue
  51:                 val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
  52:                 val text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT))
  53:                     ?.toString()?.trim().orEmpty()
  54:                 if (title.isEmpty() && text.isEmpty()) continue
  55:                 out.add(NotifFeed.Item(sbn.key, sbn.packageName, title, text, sbn.postTime, n.contentIntent, sbn.isClearable))
  56:                 if (out.size >= NotifFeed.MAX) break
  57:             }
  58:             NotifFeed.publish(out)
  59:         } catch (e: Exception) {
  60:         }
  61:     }
  62: 
  63:     private fun keyOf(sbn: StatusBarNotification): String = "n:" + sbn.key
  64: 
  65:     private fun handle(sbn: StatusBarNotification) {
  66:         val key = keyOf(sbn)
  67:         if (!Prefs.live(this) || sbn.packageName == packageName) {
  68:             LiveBus.remove(key)
  69:             return
  70:         }
  71:         val a = classify(sbn, key)
  72:         if (a != null) LiveBus.put(a) else LiveBus.remove(key)
  73:     }
  74: 
  75:     private fun classify(sbn: StatusBarNotification, key: String): LiveActivity? {
  76:         val n = sbn.notification ?: return null
  77:         if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
  78:         val ex = n.extras ?: return null
  79:         val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
  80:         val pkg = sbn.packageName
  81: 
  82:         // CALL
  83:         if (n.category == Notification.CATEGORY_CALL) {
  84:             var answer: PendingIntent? = null
  85:             var decline: PendingIntent? = null
  86:             val actions = n.actions
  87:             if (actions != null) {
  88:                 for (act in actions) {
  89:                     val t = act.title?.toString()?.lowercase() ?: ""
  90:                     if (act.actionIntent == null) continue
  91:                     if (answer == null && (act.semanticAction == Notification.Action.SEMANTIC_ACTION_CALL ||
  92:                             t.contains("answer") || t.contains("accept"))) {
  93:                         answer = act.actionIntent
  94:                     } else if (decline == null && (t.contains("decline") || t.contains("reject") ||
  95:                             t.contains("hang") || t.contains("end") || t.contains("dismiss"))) {
  96:                         decline = act.actionIntent
  97:                     }
  98:                 }
  99:             }
 100:             val who = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
 101:             val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
 102:             return LiveActivity(
 103:                 key, LiveActivity.Kind.CALL, who.ifEmpty { "Call" }.take(18), -1f,
 104:                 if (chrono) n.`when` else 0L, false,
 105:                 R.drawable.ic_phone, true, n.contentIntent, answer, decline
 106:             )
 107:         }
 108: 
 109:         // TIMER
 110:         if (ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) && isClockApp(pkg)) {
 111:             val down = ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
 112:             return LiveActivity(
 113:                 key, LiveActivity.Kind.TIMER, if (down) "Timer" else "Stopwatch", -1f,
 114:                 n.`when`, down, R.drawable.ic_timer, true, n.contentIntent, null, null
 115:             )
 116:         }
 117: 
 118:         // PROGRESS
 119:         val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
 120:         if (max > 0 && ongoing && n.category != Notification.CATEGORY_TRANSPORT) {
 121:             val indeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
 122:             val p = ex.getInt(Notification.EXTRA_PROGRESS, 0)
 123:             val f = if (indeterminate) -2f else (p.toFloat() / max).coerceIn(0f, 1f)
 124:             return LiveActivity(
 125:                 key, LiveActivity.Kind.PROGRESS, "Downloading", f, 0L, false,
 126:                 R.drawable.ic_download, true, n.contentIntent, null, null
 127:             )
 128:         }
 129:         return null
 130:     }
 131: 
 132:     private fun isClockApp(pkg: String): Boolean {
 133:         val p = pkg.lowercase()
 134:         return p.contains("clock") || p.contains("timer") || p.contains("alarm")
 135:     }
 136: }
===== NotifFeed.kt =====
   1: package com.dynedge
   2: 
   3: import android.app.PendingIntent
   4: import android.content.Context
   5: import android.graphics.Bitmap
   6: import android.graphics.Canvas
   7: import android.os.Handler
   8: import android.os.Looper
   9: 
  10: /** Recent notifications, fed by MediaListener. */
  11: object NotifFeed {
  12:     data class Item(
  13:         val key: String, val pkg: String, val title: String, val text: String, val time: Long,
  14:         val contentIntent: PendingIntent?, val clearable: Boolean
  15:     )
  16: 
  17:     const val MAX = 8
  18: 
  19:     private val lock = Any()
  20:     private var items: List<Item> = emptyList()
  21:     private val main = Handler(Looper.getMainLooper())
  22:     private val icons = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
  23:         override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 8
  24:     }
  25: 
  26:     @Volatile
  27:     var onChange: (() -> Unit)? = null
  28: 
  29:     @Volatile
  30:     internal var listener: MediaListener? = null
  31: 
  32:     private val notifier = Runnable { onChange?.invoke() }
  33: 
  34:     /** Newest first, max 8. */
  35:     fun recent(): List<Item> = synchronized(lock) { items }
  36: 
  37:     /** Called by MediaListener with the full, already filtered and sorted list. */
  38:     internal fun publish(list: List<Item>) {
  39:         val l = list.take(MAX)
  40:         synchronized(lock) {
  41:             if (l == items) return
  42:             items = l
  43:         }
  44:         main.removeCallbacks(notifier)
  45:         main.post(notifier)
  46:     }
  47: 
  48:     fun dismiss(key: String) {
  49:         try {
  50:             listener?.cancelNotification(key)
  51:         } catch (e: Exception) {
  52:         }
  53:     }
  54: 
  55:     fun appIcon(ctx: Context, pkg: String, px: Int): Bitmap? {
  56:         val k = "$pkg@$px"
  57:         synchronized(icons) { icons[k]?.let { return it } }
  58:         return try {
  59:             val d = ctx.packageManager.getApplicationIcon(pkg)
  60:             val s = px.coerceAtLeast(1)
  61:             val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
  62:             val c = Canvas(b)
  63:             d.setBounds(0, 0, s, s)
  64:             d.draw(c)
  65:             synchronized(icons) { icons[k] = b }
  66:             b
  67:         } catch (e: Exception) {
  68:             null
  69:         }
  70:     }
  71: }
===== Prefs.kt =====
   1: package com.dynedge
   2: 
   3: import android.content.ComponentName
   4: import android.content.Context
   5: import android.content.Intent
   6: import android.content.SharedPreferences
   7: import android.provider.MediaStore
   8: 
   9: object Prefs {
  10:     const val NAME = "edge"
  11:     const val KEY_IDLE_DP = "idle_dp"
  12:     const val KEY_OPEN_DP = "open_dp"
  13:     const val KEY_APPS = "apps"
  14:     const val IDLE_MIN = 32f
  15:     const val IDLE_MAX = 80f
  16:     const val IDLE_DEF = 48f
  17:     const val OPEN_MIN = 170f
  18:     const val OPEN_MAX = 300f
  19:     const val OPEN_DEF = 216f
  20:     const val MAX_APPS = 6
  21: 
  22:     fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
  23: 
  24:     fun idleDp(ctx: Context): Float =
  25:         prefs(ctx).getFloat(KEY_IDLE_DP, IDLE_DEF).coerceIn(IDLE_MIN, IDLE_MAX)
  26: 
  27:     fun openDp(ctx: Context): Float =
  28:         prefs(ctx).getFloat(KEY_OPEN_DP, OPEN_DEF).coerceIn(OPEN_MIN, OPEN_MAX)
  29: 
  30:     fun setIdleDp(ctx: Context, v: Float) {
  31:         prefs(ctx).edit().putFloat(KEY_IDLE_DP, v.coerceIn(IDLE_MIN, IDLE_MAX)).apply()
  32:     }
  33: 
  34:     fun setOpenDp(ctx: Context, v: Float) {
  35:         prefs(ctx).edit().putFloat(KEY_OPEN_DP, v.coerceIn(OPEN_MIN, OPEN_MAX)).apply()
  36:     }
  37: 
  38:     // ---------------------------------------------------------------- looks and feel
  39:     const val KEY_ACCENT = "accent"            // Int ARGB
  40:     const val KEY_GRADIENT = "gradient"        // Int 0..100, warmth/strength of the corner gradient
  41:     const val KEY_RIM = "rim"                  // Boolean, thin border along the curve
  42:     const val KEY_GLOW = "glow"                // Boolean, accent glow on touch / open
  43:     const val KEY_BANDS = "bands"              // Boolean, faint ring tiles behind items (off by default)
  44:     const val KEY_SPEED = "speed"              // Int 0..100, 0 = slow and fluid, 100 = snappy
  45:     const val KEY_BOUNCE = "bounce"            // Int 0..100, 0 = no overshoot, 100 = very bouncy
  46:     const val KEY_HAPTICS = "haptics"          // Boolean
  47:     const val KEY_IDLE_STYLE = "idle_style"    // String, one of IDLE_STYLES
  48:     const val KEY_VISUALIZER = "visualizer"    // Boolean, music bars on the idle bean while playing
  49:     const val KEY_PREVIEW = "preview"          // String "", "idle" or "open"; transient, set by the settings screen
  50: 
  51:     const val KEY_LIVE = "live"                // Boolean, live activities (default true)
  52:     fun live(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LIVE, true)
  53: 
  54:     val ACCENTS = intArrayOf(
  55:         0xFFFF4B3E.toInt(), // red (default)
  56:         0xFFFF9F0A.toInt(), // orange
  57:         0xFFFFD60A.toInt(), // yellow
  58:         0xFF30D158.toInt(), // green
  59:         0xFF0A84FF.toInt(), // blue
  60:         0xFFBF5AF2.toInt(), // purple
  61:         0xFFFF375F.toInt(), // pink
  62:         0xFFF2EDE4.toInt()  // cream / white
  63:     )
  64:     val IDLE_STYLES = listOf("none", "glyph", "pill", "orbit", "slits", "halo", "meter", "grid", "eclipse")
  65:     val FILLS = listOf("gradient", "solid", "grooves", "dots", "sunburst", "carbon")
  66:     val VIZ_STYLES = listOf("bars", "spectrum", "rays", "ripple", "meter", "wave", "liquid")
  67:     const val PREVIEW_NONE = ""
  68:     const val PREVIEW_IDLE = "idle"
  69:     const val PREVIEW_OPEN = "open"
  70: 
  71:     fun accent(ctx: Context): Int = prefs(ctx).getInt(KEY_ACCENT, ACCENTS[0])
  72:     fun gradient(ctx: Context): Int = prefs(ctx).getInt(KEY_GRADIENT, 35).coerceIn(0, 100)
  73:     fun rim(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_RIM, true)
  74:     fun glow(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_GLOW, true)
  75:     fun bands(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BANDS, false)
  76:     fun speed(ctx: Context): Int = prefs(ctx).getInt(KEY_SPEED, 50).coerceIn(0, 100)
  77:     fun bounce(ctx: Context): Int = prefs(ctx).getInt(KEY_BOUNCE, 40).coerceIn(0, 100)
  78:     fun haptics(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HAPTICS, true)
  79:     fun idleStyle(ctx: Context): String {
  80:         val v = prefs(ctx).getString(KEY_IDLE_STYLE, "glyph") ?: "glyph"
  81:         val mapped = when (v) {
  82:             "plain" -> "none"
  83:             "dot" -> "halo"
  84:             "arc" -> "slits"
  85:             "battery" -> "meter"
  86:             "clock" -> "glyph"
  87:             else -> v
  88:         }
  89:         return if (mapped in IDLE_STYLES) mapped else "glyph"
  90:     }
  91: 
  92:     const val KEY_FLARE = "flare"              // Float dp 0..28, edge fillets where the shape meets the screen edges
  93:     const val FLARE_MAX = 28f
  94:     const val FLARE_DEF = 10f
  95:     fun flareDp(ctx: Context): Float = prefs(ctx).getFloat(KEY_FLARE, FLARE_DEF).coerceIn(0f, FLARE_MAX)
  96: 
  97:     const val KEY_FILL = "fill"                // String, one of FILLS
  98:     fun fill(ctx: Context): String =
  99:         prefs(ctx).getString(KEY_FILL, "gradient")?.takeIf { it in FILLS } ?: "gradient"
 100: 
 101:     const val KEY_VIZ_STYLE = "viz_style"      // String, one of VIZ_STYLES
 102:     fun vizStyle(ctx: Context): String =
 103:         prefs(ctx).getString(KEY_VIZ_STYLE, "spectrum")?.takeIf { it in VIZ_STYLES } ?: "spectrum"
 104:     fun visualizer(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_VISUALIZER, true)
 105:     fun preview(ctx: Context): String = prefs(ctx).getString(KEY_PREVIEW, PREVIEW_NONE) ?: PREVIEW_NONE
 106: 
 107:     /** Stiffness multiplier from KEY_SPEED: 0.55 (fluid) .. 1.6 (snappy). */
 108:     fun stiffnessScale(ctx: Context): Float = 0.55f + 1.05f * speed(ctx) / 100f
 109:     /** Damping ratio for open motion from KEY_BOUNCE: 0.98 (none) .. 0.55 (bouncy). */
 110:     fun openDamping(ctx: Context): Float = 0.98f - 0.43f * bounce(ctx) / 100f
 111: 
 112:     fun resetLooks(ctx: Context) {
 113:         prefs(ctx).edit()
 114:             .remove(KEY_ACCENT).remove(KEY_GRADIENT).remove(KEY_RIM).remove(KEY_GLOW).remove(KEY_BANDS)
 115:             .remove(KEY_SPEED).remove(KEY_BOUNCE).remove(KEY_HAPTICS).remove(KEY_IDLE_STYLE)
 116:             .remove(KEY_VISUALIZER).remove(KEY_FLARE).remove(KEY_FILL).remove(KEY_VIZ_STYLE).remove(KEY_BEAT)
 117:             .apply()
 118:     }
 119: 
 120:     const val KEY_BEAT = "beat"               // Boolean, whole edge reacts to music beats (default on)
 121:     fun beat(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BEAT, true)
 122: 
 123:     fun apps(ctx: Context): List<ComponentName> {
 124:         val s = prefs(ctx).getString(KEY_APPS, null) ?: return defaultApps(ctx)
 125:         val out = ArrayList<ComponentName>()
 126:         for (part in s.split("|")) {
 127:             if (part.isEmpty()) continue
 128:             val c = ComponentName.unflattenFromString(part) ?: continue
 129:             if (c !in out) out.add(c)
 130:             if (out.size >= MAX_APPS) break
 131:         }
 132:         return out
 133:     }
 134: 
 135:     fun setApps(ctx: Context, list: List<ComponentName>) {
 136:         val s = list.distinct().take(MAX_APPS).joinToString("|") { it.flattenToString() }
 137:         prefs(ctx).edit().putString(KEY_APPS, s).apply()
 138:     }
 139: 
 140:     private fun defaultApps(ctx: Context): List<ComponentName> {
 141:         val pm = ctx.packageManager
 142:         val intents = listOf(
 143:             Intent(Intent.ACTION_DIAL),
 144:             Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
 145:             Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
 146:             Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
 147:             Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY),
 148:             Intent(android.provider.Settings.ACTION_SETTINGS)
 149:         )
 150:         val out = ArrayList<ComponentName>()
 151:         for (i in intents) {
 152:             val ai = try {
 153:                 pm.resolveActivity(i, 0)?.activityInfo
 154:             } catch (e: Exception) {
 155:                 null
 156:             } ?: continue
 157:             if (ai.packageName == "android") continue
 158:             val c = ComponentName(ai.packageName, ai.name)
 159:             if (c !in out) out.add(c)
 160:         }
 161:         return out
 162:     }
 163: }
===== QuickConfig.kt =====
   1: package com.dynedge
   2: 
   3: import android.content.Context
   4: import org.json.JSONArray
   5: import org.json.JSONObject
   6: 
   7: /** User-editable layout of the QUICK section. Stored as JSON in Prefs under KEY. */
   8: object QuickConfig {
   9:     const val KEY = "quick_layout"
  10:     const val INNER_MAX = 4
  11:     const val MIDDLE_MAX = 5
  12:     const val ARCS_MAX = 2
  13: 
  14:     data class Layout(val inner: List<String>, val middle: List<String>, val arcs: List<String>)
  15: 
  16:     val DEFAULT = Layout(
  17:         inner = listOf("rotate", "assist", "vibrate"),
  18:         middle = listOf("wifi", "bluetooth", "torch", "dnd"),
  19:         arcs = listOf("brightness")
  20:     )
  21: 
  22:     fun load(ctx: Context): Layout {
  23:         val s = Prefs.prefs(ctx).getString(KEY, null) ?: return DEFAULT
  24:         return try {
  25:             val o = JSONObject(s)
  26:             val seen = HashSet<String>()
  27:             val inner = ids(o.optJSONArray("inner"), Tiles.ALL, INNER_MAX, seen)
  28:             val middle = ids(o.optJSONArray("middle"), Tiles.ALL, MIDDLE_MAX, seen)
  29:             val arcs = ids(o.optJSONArray("arcs"), Arcs.ALL, ARCS_MAX, HashSet())
  30:             Layout(inner, middle, arcs)
  31:         } catch (e: Exception) {
  32:             DEFAULT
  33:         }
  34:     }
  35: 
  36:     fun save(ctx: Context, l: Layout) {
  37:         val o = JSONObject()
  38:         o.put("inner", JSONArray(l.inner.take(INNER_MAX)))
  39:         o.put("middle", JSONArray(l.middle.take(MIDDLE_MAX)))
  40:         o.put("arcs", JSONArray(l.arcs.take(ARCS_MAX)))
  41:         Prefs.prefs(ctx).edit().putString(KEY, o.toString()).apply()
  42:     }
  43: 
  44:     fun reset(ctx: Context) {
  45:         Prefs.prefs(ctx).edit().remove(KEY).apply()
  46:     }
  47: 
  48:     private fun ids(a: JSONArray?, valid: List<String>, max: Int, seen: MutableSet<String>): List<String> {
  49:         val out = ArrayList<String>()
  50:         if (a == null) return out
  51:         for (i in 0 until a.length()) {
  52:             val id = a.optString(i, "")
  53:             if (id in valid && seen.add(id)) out.add(id)
  54:             if (out.size >= max) break
  55:         }
  56:         return out
  57:     }
  58: }
===== QuickEditorActivity.kt =====
   1: package com.dynedge
   2: 
   3: import android.app.Activity
   4: import android.content.Context
   5: import android.graphics.Canvas
   6: import android.graphics.Color
   7: import android.graphics.Paint
   8: import android.graphics.RectF
   9: import android.graphics.drawable.Drawable
  10: import android.graphics.drawable.GradientDrawable
  11: import android.os.Bundle
  12: import android.util.TypedValue
  13: import android.view.Gravity
  14: import android.view.MotionEvent
  15: import android.view.View
  16: import android.view.ViewGroup
  17: import android.view.WindowInsets
  18: import android.widget.ImageView
  19: import android.widget.LinearLayout
  20: import android.widget.ScrollView
  21: import android.widget.TextView
  22: 
  23: /** Visual editor for the QUICK section: tap a slot, then tap a tile to place it. */
  24: class QuickEditorActivity : Activity() {
  25: 
  26:     private val inner = ArrayList<String>()
  27:     private val middle = ArrayList<String>()
  28:     private val arcs = ArrayList<String>()
  29: 
  30:     /** ring: 0 = inner, 1 = middle; idx == list size means a new slot. */
  31:     private var selRing = -1
  32:     private var selIdx = -1
  33: 
  34:     private var accent = Color.WHITE
  35:     private lateinit var preview: PreviewView
  36:     private lateinit var catalog: LinearLayout
  37:     private lateinit var arcRow: LinearLayout
  38: 
  39:     private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
  40: 
  41:     override fun onCreate(savedInstanceState: Bundle?) {
  42:         super.onCreate(savedInstanceState)
  43:         window.statusBarColor = Color.BLACK
  44:         window.navigationBarColor = Color.BLACK
  45:         window.setDecorFitsSystemWindows(false)
  46:         accent = Prefs.accent(this)
  47: 
  48:         val l = QuickConfig.load(this)
  49:         inner.addAll(l.inner)
  50:         middle.addAll(l.middle)
  51:         arcs.addAll(l.arcs)
  52: 
  53:         val root = LinearLayout(this).apply {
  54:             orientation = LinearLayout.VERTICAL
  55:             setBackgroundColor(Color.BLACK)
  56:             setOnApplyWindowInsetsListener { v, insets ->
  57:                 val bars = insets.getInsets(WindowInsets.Type.systemBars())
  58:                 v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
  59:                 WindowInsets.CONSUMED
  60:             }
  61:         }
  62: 
  63:         // header: back arrow area (finish) left, reset right
  64:         val header = LinearLayout(this).apply {
  65:             orientation = LinearLayout.HORIZONTAL
  66:             gravity = Gravity.CENTER_VERTICAL
  67:             setPadding(dp(8), dp(4), dp(8), dp(4))
  68:         }
  69:         header.addView(iconButton(R.drawable.ic_skip_back) { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
  70:         header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
  71:         header.addView(TextView(this).apply {
  72:             text = "Reset"
  73:             setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
  74:             setTextColor(accent)
  75:             gravity = Gravity.CENTER
  76:             setPadding(dp(16), 0, dp(16), 0)
  77:             minimumHeight = dp(48)
  78:             setOnClickListener {
  79:                 QuickConfig.reset(this@QuickEditorActivity)
  80:                 val d = QuickConfig.DEFAULT
  81:                 inner.clear(); inner.addAll(d.inner)
  82:                 middle.clear(); middle.addAll(d.middle)
  83:                 arcs.clear(); arcs.addAll(d.arcs)
  84:                 clearSelection()
  85:                 refreshAll()
  86:             }
  87:         }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
  88:         root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
  89: 
  90:         preview = PreviewView(this)
  91:         root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(270)))
  92: 
  93:         val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
  94:         val body = LinearLayout(this).apply {
  95:             orientation = LinearLayout.VERTICAL
  96:             setPadding(dp(12), dp(8), dp(12), dp(24))
  97:         }
  98:         catalog = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
  99:         body.addView(catalog)
 100:         body.addView(View(this), LinearLayout.LayoutParams(1, dp(16)))
 101:         arcRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
 102:         body.addView(arcRow)
 103:         scroll.addView(body)
 104:         root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
 105: 
 106:         setContentView(root)
 107:         buildCatalog()
 108:         buildArcRow()
 109:     }
 110: 
 111:     // ---------------------------------------------------------------- UI building
 112: 
 113:     private fun iconButton(res: Int, click: () -> Unit): View {
 114:         val v = ImageView(this)
 115:         v.setImageResource(res)
 116:         v.setColorFilter(Color.WHITE)
 117:         v.scaleType = ImageView.ScaleType.CENTER_INSIDE
 118:         v.setPadding(dp(12), dp(12), dp(12), dp(12))
 119:         v.setOnClickListener { click() }
 120:         return v
 121:     }
 122: 
 123:     private fun cell(res: Int, label: String, active: Boolean, click: () -> Unit): View {
 124:         val c = LinearLayout(this).apply {
 125:             orientation = LinearLayout.VERTICAL
 126:             gravity = Gravity.CENTER_HORIZONTAL
 127:             setPadding(0, dp(6), 0, dp(6))
 128:             setOnClickListener { click() }
 129:         }
 130:         val img = ImageView(this).apply {
 131:             setImageResource(res)
 132:             setColorFilter(if (active) Color.BLACK else Color.WHITE)
 133:             scaleType = ImageView.ScaleType.CENTER_INSIDE
 134:             setPadding(dp(11), dp(11), dp(11), dp(11))
 135:             background = GradientDrawable().apply {
 136:                 shape = GradientDrawable.OVAL
 137:                 setColor(if (active) accent else Color.parseColor("#1C1C1E"))
 138:             }
 139:         }
 140:         c.addView(img, LinearLayout.LayoutParams(dp(46), dp(46)))
 141:         c.addView(TextView(this).apply {
 142:             text = label
 143:             setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
 144:             setTextColor(Color.parseColor("#8E8E93"))
 145:             gravity = Gravity.CENTER
 146:             maxLines = 1
 147:             setPadding(0, dp(4), 0, 0)
 148:         })
 149:         return c
 150:     }
 151: 
 152:     private fun buildCatalog() {
 153:         catalog.removeAllViews()
 154:         val cols = 4
 155:         val ids = Tiles.ALL
 156:         var row: LinearLayout? = null
 157:         for ((i, id) in ids.withIndex()) {
 158:             if (i % cols == 0) {
 159:                 row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
 160:                 catalog.addView(row)
 161:             }
 162:             val used = id in inner || id in middle
 163:             val v = cell(Tiles.staticIcon(id), Tiles.label(id), used) { place(id) }
 164:             row!!.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
 165:         }
 166:         // pad the last row so cells keep their width
 167:         val rem = ids.size % cols
 168:         if (rem != 0) {
 169:             for (k in rem until cols) row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
 170:         }
 171:     }
 172: 
 173:     private fun buildArcRow() {
 174:         arcRow.removeAllViews()
 175:         for (id in Arcs.ALL) {
 176:             val on = id in arcs
 177:             val iconRes = when (id) {
 178:                 "brightness" -> R.drawable.ic_sun
 179:                 "media_volume" -> R.drawable.ic_volume
 180:                 "ring_volume" -> R.drawable.ic_ring
 181:                 else -> R.drawable.ic_alarm
 182:             }
 183:             arcRow.addView(
 184:                 cell(iconRes, Arcs.label(id), on) { toggleArc(id) },
 185:                 LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
 186:             )
 187:         }
 188:     }
 189: 
 190:     private fun refreshAll() {
 191:         buildCatalog()
 192:         buildArcRow()
 193:         preview.invalidate()
 194:     }
 195: 
 196:     // ---------------------------------------------------------------- editing
 197: 
 198:     private fun clearSelection() {
 199:         selRing = -1
 200:         selIdx = -1
 201:     }
 202: 
 203:     private fun list(ring: Int) = if (ring == 0) inner else middle
 204:     private fun max(ring: Int) = if (ring == 0) QuickConfig.INNER_MAX else QuickConfig.MIDDLE_MAX
 205: 
 206:     private fun persist() {
 207:         QuickConfig.save(this, QuickConfig.Layout(inner.toList(), middle.toList(), arcs.toList()))
 208:     }
 209: 
 210:     /** Called by the preview when a slot (or ghost) is tapped. */
 211:     private fun tapSlot(ring: Int, idx: Int) {
 212:         val l = list(ring)
 213:         if (selRing == ring && selIdx == idx) {
 214:             if (idx < l.size) {
 215:                 l.removeAt(idx)
 216:                 persist()
 217:             }
 218:             clearSelection()
 219:             refreshAll()
 220:             return
 221:         }
 222:         selRing = ring
 223:         selIdx = idx
 224:         preview.invalidate()
 225:     }
 226: 
 227:     private fun place(id: String) {
 228:         var ring = selRing
 229:         var idx = selIdx
 230:         if (ring < 0) {
 231:             // no slot selected: append to the first ring that has room
 232:             ring = when {
 233:                 id in inner || id in middle -> -1
 234:                 middle.size < QuickConfig.MIDDLE_MAX -> 1
 235:                 inner.size < QuickConfig.INNER_MAX -> 0
 236:                 else -> -1
 237:             }
 238:             if (ring < 0) {
 239:                 // already placed (or no room): tapping a placed tile removes it
 240:                 if (inner.remove(id) || middle.remove(id)) {
 241:                     persist()
 242:                     refreshAll()
 243:                 }
 244:                 return
 245:             }
 246:             idx = list(ring).size
 247:         }
 248:         val target = list(ring)
 249:         val existing = target.indexOf(id)
 250:         if (existing == idx) {
 251:             clearSelection()
 252:             refreshAll()
 253:             return
 254:         }
 255:         if (existing >= 0) {
 256:             target.removeAt(existing)
 257:             if (existing < idx) idx--
 258:         }
 259:         val other = list(1 - ring)
 260:         other.remove(id)
 261:         if (idx >= target.size) {
 262:             if (target.size < max(ring)) target.add(id)
 263:         } else {
 264:             target[idx] = id
 265:         }
 266:         clearSelection()
 267:         persist()
 268:         refreshAll()
 269:     }
 270: 
 271:     private fun toggleArc(id: String) {
 272:         if (id in arcs) arcs.remove(id)
 273:         else if (arcs.size < QuickConfig.ARCS_MAX) arcs.add(id)
 274:         persist()
 275:         refreshAll()
 276:     }
 277: 
 278:     // ---------------------------------------------------------------- preview
 279: 
 280:     private inner class PreviewView(ctx: Context) : View(ctx) {
 281:         private val d = resources.displayMetrics.density
 282:         private val p = Paint(Paint.ANTI_ALIAS_FLAG)
 283:         private val rect = RectF()
 284:         private val icons = HashMap<Int, Drawable>()
 285: 
 286:         private val slotX = FloatArray(16)
 287:         private val slotY = FloatArray(16)
 288:         private val slotRing = IntArray(16)
 289:         private val slotIdx = IntArray(16)
 290:         private var slotCount = 0
 291:         private val slotR = 20 * d
 292: 
 293:         private var cx = 0f
 294:         private var cy = 0f
 295:         private var radius = 0f
 296: 
 297:         private fun icon(res: Int): Drawable? {
 298:             var dr = icons[res]
 299:             if (dr == null) {
 300:                 dr = context.getDrawable(res)?.mutate() ?: return null
 301:                 icons[res] = dr
 302:             }
 303:             return dr
 304:         }
 305: 
 306:         private fun drawIcon(res: Int, x: Float, y: Float, half: Float, color: Int) {
 307:             val dr = icon(res) ?: return
 308:             dr.setTint(color)
 309:             dr.setBounds((x - half).toInt(), (y - half).toInt(), (x + half).toInt(), (y + half).toInt())
 310:             dr.draw(canvasRef!!)
 311:         }
 312: 
 313:         private var canvasRef: Canvas? = null
 314: 
 315:         private fun geometry() {
 316:             val w = width.toFloat()
 317:             val h = height.toFloat()
 318:             cx = w - 14 * d
 319:             cy = h - 14 * d
 320:             radius = minOf(cx - 8 * d, cy - 8 * d)
 321:         }
 322: 
 323:         private fun angleOf(i: Int, n: Int): Float =
 324:             if (n <= 1) 45f else 12f + i * (66f / (n - 1))
 325: 
 326:         override fun onDraw(c: Canvas) {
 327:             geometry()
 328:             canvasRef = c
 329:             val two = arcs.size >= 2
 330:             val rInner = radius * if (two) 0.38f else 0.42f
 331:             val rMiddle = radius * if (two) 0.60f else 0.66f
 332: 
 333:             // quarter outline
 334:             p.style = Paint.Style.STROKE
 335:             p.strokeWidth = 1f * d
 336:             p.color = Color.parseColor("#2C2C2E")
 337:             rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
 338:             c.drawArc(rect, 180f, 90f, false, p)
 339: 
 340:             // arcs
 341:             p.strokeCap = Paint.Cap.ROUND
 342:             for ((i, id) in arcs.withIndex()) {
 343:                 val r = radius * if (two) (if (i == 0) 0.88f else 0.78f) else 0.88f
 344:                 p.style = Paint.Style.STROKE
 345:                 p.strokeWidth = 5f * d
 346:                 p.color = Color.parseColor("#2C2C2E")
 347:                 rect.set(cx - r, cy - r, cx + r, cy + r)
 348:                 c.drawArc(rect, 180f, 90f, false, p)
 349:                 p.color = accent
 350:                 c.drawArc(rect, 180f, 52f, false, p)
 351:                 // badge at the low end
 352:                 val bx = cx - r
 353:                 val by = cy - 14 * d
 354:                 p.style = Paint.Style.FILL
 355:                 p.color = Color.parseColor("#1C1C1E")
 356:                 c.drawCircle(bx, by, 11 * d, p)
 357:                 drawIcon(arcIcon(id), bx, by, 7 * d, Color.WHITE)
 358:             }
 359:             p.strokeCap = Paint.Cap.BUTT
 360: 
 361:             slotCount = 0
 362:             drawRing(c, 0, inner, rInner)
 363:             drawRing(c, 1, middle, rMiddle)
 364:             canvasRef = null
 365:         }
 366: 
 367:         private fun arcIcon(id: String): Int = when (id) {
 368:             "brightness" -> R.drawable.ic_sun
 369:             "media_volume" -> R.drawable.ic_volume
 370:             "ring_volume" -> R.drawable.ic_ring
 371:             else -> R.drawable.ic_alarm
 372:         }
 373: 
 374:         private fun drawRing(c: Canvas, ring: Int, ids: List<String>, r: Float) {
 375:             val ghost = ids.size < max(ring)
 376:             val n = ids.size + if (ghost) 1 else 0
 377:             if (n == 0) return
 378:             for (i in 0 until n) {
 379:                 val a = Math.toRadians(angleOf(i, n).toDouble())
 380:                 val x = cx - (r * Math.cos(a)).toFloat()
 381:                 val y = cy - (r * Math.sin(a)).toFloat()
 382:                 if (slotCount < slotX.size) {
 383:                     slotX[slotCount] = x
 384:                     slotY[slotCount] = y
 385:                     slotRing[slotCount] = ring
 386:                     slotIdx[slotCount] = i
 387:                     slotCount++
 388:                 }
 389:                 val selected = selRing == ring && selIdx == i
 390:                 if (i < ids.size) {
 391:                     p.style = Paint.Style.FILL
 392:                     p.color = Color.parseColor("#1C1C1E")
 393:                     c.drawCircle(x, y, slotR, p)
 394:                     drawIcon(Tiles.staticIcon(ids[i]), x, y, 10 * d, Color.WHITE)
 395:                 } else {
 396:                     p.style = Paint.Style.STROKE
 397:                     p.strokeWidth = 1.5f * d
 398:                     p.color = Color.parseColor("#3A3A3C")
 399:                     c.drawCircle(x, y, slotR, p)
 400:                     val k = 6 * d
 401:                     c.drawLine(x - k, y, x + k, y, p)
 402:                     c.drawLine(x, y - k, x, y + k, p)
 403:                 }
 404:                 if (selected) {
 405:                     p.style = Paint.Style.STROKE
 406:                     p.strokeWidth = 2.5f * d
 407:                     p.color = accent
 408:                     c.drawCircle(x, y, slotR + 2 * d, p)
 409:                 }
 410:             }
 411:         }
 412: 
 413:         override fun onTouchEvent(e: MotionEvent): Boolean {
 414:             when (e.actionMasked) {
 415:                 MotionEvent.ACTION_DOWN -> return true
 416:                 MotionEvent.ACTION_UP -> {
 417:                     var best = -1
 418:                     var bd = (slotR + 8 * d) * (slotR + 8 * d)
 419:                     for (i in 0 until slotCount) {
 420:                         val dx = e.x - slotX[i]
 421:                         val dy = e.y - slotY[i]
 422:                         val dd = dx * dx + dy * dy
 423:                         if (dd <= bd) {
 424:                             bd = dd
 425:                             best = i
 426:                         }
 427:                     }
 428:                     if (best >= 0) tapSlot(slotRing[best], slotIdx[best])
 429:                     else if (selRing >= 0) {
 430:                         clearSelection()
 431:                         invalidate()
 432:                     }
 433:                     return true
 434:                 }
 435:             }
 436:             return true
 437:         }
 438:     }
 439: }
===== QuickToggles.kt =====
   1: package com.dynedge
   2: 
   3: import android.app.NotificationManager
   4: import android.bluetooth.BluetoothAdapter
   5: import android.bluetooth.BluetoothManager
   6: import android.content.BroadcastReceiver
   7: import android.content.Context
   8: import android.content.Intent
   9: import android.content.IntentFilter
  10: import android.database.ContentObserver
  11: import android.hardware.camera2.CameraAccessException
  12: import android.hardware.camera2.CameraCharacteristics
  13: import android.hardware.camera2.CameraManager
  14: import android.media.AudioManager
  15: import android.net.Uri
  16: import android.net.wifi.WifiManager
  17: import android.os.Build
  18: import android.os.Handler
  19: import android.os.Looper
  20: import android.provider.Settings
  21: 
  22: enum class ToggleId { WIFI, BLUETOOTH, TORCH, DND, ROTATE, VIBRATE }
  23: 
  24: /**
  25:  * State and actions for the six quick toggles. Listeners exist only between start() and stop().
  26:  * All onChange callbacks arrive on the main thread.
  27:  */
  28: @Suppress("DEPRECATION", "MissingPermission")
  29: class QuickToggles(private val ctx: Context, private val onChange: () -> Unit) {
  30: 
  31:     private val app: Context = ctx.applicationContext ?: ctx
  32:     private val main = Handler(Looper.getMainLooper())
  33: 
  34:     private val wifi: WifiManager? = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
  35:     private val adapter: BluetoothAdapter? =
  36:         (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
  37:     private val camera: CameraManager? = app.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
  38:     private val nm: NotificationManager? =
  39:         app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
  40:     private val audio: AudioManager? = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
  41:     private val resolver = app.contentResolver
  42:     private val rotateUri: Uri = Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION)
  43: 
  44:     private val torchId: String? = findTorchId()
  45: 
  46:     private var wifiOn = false
  47:     private var btOn = false
  48:     private var torchOn = false
  49:     private var dndOn = false
  50:     private var rotateOn = true
  51:     private var vibOn = false
  52: 
  53:     private var started = false
  54: 
  55:     private fun fire() = onChange()
  56: 
  57:     private val receiver = object : BroadcastReceiver() {
  58:         override fun onReceive(context: Context?, intent: Intent?) {
  59:             if (refreshAll()) fire()
  60:         }
  61:     }
  62: 
  63:     private val rotateObserver = object : ContentObserver(main) {
  64:         override fun onChange(selfChange: Boolean) {
  65:             if (refreshAll()) fire()
  66:         }
  67:     }
  68: 
  69:     private val torchCallback = object : CameraManager.TorchCallback() {
  70:         override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
  71:             if (cameraId == torchId && torchOn != enabled) {
  72:                 torchOn = enabled
  73:                 fire()
  74:             }
  75:         }
  76:     }
  77: 
  78:     fun start() {
  79:         if (started) return
  80:         started = true
  81:         refreshAll()
  82: 
  83:         val filter = IntentFilter().apply {
  84:             addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
  85:             addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
  86:             addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
  87:             addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
  88:         }
  89:         try {
  90:             if (Build.VERSION.SDK_INT >= 33) {
  91:                 app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
  92:             } else {
  93:                 app.registerReceiver(receiver, filter)
  94:             }
  95:         } catch (e: Exception) {
  96:             // Without the receiver the states still refresh on every start().
  97:         }
  98:         try {
  99:             resolver.registerContentObserver(rotateUri, false, rotateObserver)
 100:         } catch (e: Exception) {
 101:         }
 102:         if (camera != null && torchId != null) {
 103:             try {
 104:                 camera.registerTorchCallback(torchCallback, main)
 105:             } catch (e: Exception) {
 106:             }
 107:         }
 108:     }
 109: 
 110:     fun stop() {
 111:         if (!started) return
 112:         started = false
 113:         try {
 114:             app.unregisterReceiver(receiver)
 115:         } catch (e: Exception) {
 116:         }
 117:         try {
 118:             resolver.unregisterContentObserver(rotateObserver)
 119:         } catch (e: Exception) {
 120:         }
 121:         if (camera != null) {
 122:             try {
 123:                 camera.unregisterTorchCallback(torchCallback)
 124:             } catch (e: Exception) {
 125:             }
 126:         }
 127:     }
 128: 
 129:     fun isOn(id: ToggleId): Boolean = when (id) {
 130:         ToggleId.WIFI -> wifiOn
 131:         ToggleId.BLUETOOTH -> btOn
 132:         ToggleId.TORCH -> torchOn
 133:         ToggleId.DND -> dndOn
 134:         ToggleId.ROTATE -> rotateOn
 135:         ToggleId.VIBRATE -> vibOn
 136:     }
 137: 
 138:     fun isAvailable(id: ToggleId): Boolean = when (id) {
 139:         ToggleId.WIFI -> wifi != null
 140:         ToggleId.BLUETOOTH -> adapter != null
 141:         ToggleId.TORCH -> torchId != null
 142:         ToggleId.DND -> nm != null
 143:         ToggleId.ROTATE -> true
 144:         ToggleId.VIBRATE -> audio != null
 145:     }
 146: 
 147:     fun iconRes(id: ToggleId): Int = when (id) {
 148:         ToggleId.WIFI -> if (wifiOn) R.drawable.ic_wifi else R.drawable.ic_wifi_off
 149:         ToggleId.BLUETOOTH -> if (btOn) R.drawable.ic_bluetooth else R.drawable.ic_bluetooth_off
 150:         ToggleId.TORCH -> if (torchOn) R.drawable.ic_flashlight else R.drawable.ic_flashlight_off
 151:         ToggleId.DND -> R.drawable.ic_moon
 152:         ToggleId.ROTATE -> if (rotateOn) R.drawable.ic_rotate else R.drawable.ic_rotate_lock
 153:         ToggleId.VIBRATE -> if (vibOn) R.drawable.ic_vibrate else R.drawable.ic_bell
 154:     }
 155: 
 156:     /** Performs the toggle. Returns true if an Activity was launched (caller collapses). */
 157:     fun toggle(id: ToggleId): Boolean {
 158:         if (!isAvailable(id)) return false
 159:         return when (id) {
 160:             ToggleId.WIFI -> toggleWifi()
 161:             ToggleId.BLUETOOTH -> toggleBluetooth()
 162:             ToggleId.TORCH -> toggleTorch()
 163:             ToggleId.DND -> toggleDnd()
 164:             ToggleId.ROTATE -> toggleRotate()
 165:             ToggleId.VIBRATE -> toggleVibrate()
 166:         }
 167:     }
 168: 
 169:     /** Long-press target. Returns true if an Activity was launched. */
 170:     fun openSettings(id: ToggleId): Boolean = when (id) {
 171:         ToggleId.WIFI -> launch(Intent(Settings.ACTION_WIFI_SETTINGS))
 172:         ToggleId.BLUETOOTH -> launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
 173:         ToggleId.TORCH -> false
 174:         ToggleId.DND -> launch(Intent("android.settings.ZEN_MODE_SETTINGS")) ||
 175:             launch(Intent(Settings.ACTION_SOUND_SETTINGS))
 176:         ToggleId.ROTATE -> launch(Intent(Settings.ACTION_DISPLAY_SETTINGS))
 177:         ToggleId.VIBRATE -> launch(Intent(Settings.ACTION_SOUND_SETTINGS))
 178:     }
 179: 
 180:     // ---- individual toggles -------------------------------------------------------------
 181: 
 182:     private fun toggleWifi(): Boolean {
 183:         val wm = wifi ?: return false
 184:         val target = !wifiOn
 185:         val ok = try {
 186:             wm.setWifiEnabled(target)
 187:         } catch (e: Exception) {
 188:             false
 189:         }
 190:         if (!ok) return launch(Intent(Settings.Panel.ACTION_WIFI))
 191:         wifiOn = target
 192:         fire()
 193:         return false
 194:     }
 195: 
 196:     private fun toggleBluetooth(): Boolean {
 197:         val a = adapter ?: return false
 198:         val target = !btOn
 199:         val ok = try {
 200:             if (target) a.enable() else a.disable()
 201:         } catch (e: Exception) {
 202:             false
 203:         }
 204:         if (!ok) return launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
 205:         btOn = target
 206:         fire()
 207:         return false
 208:     }
 209: 
 210:     private fun toggleTorch(): Boolean {
 211:         val cm = camera ?: return false
 212:         val id = torchId ?: return false
 213:         try {
 214:             cm.setTorchMode(id, !torchOn)
 215:         } catch (e: CameraAccessException) {
 216:         } catch (e: IllegalArgumentException) {
 217:         } catch (e: Exception) {
 218:         }
 219:         return false
 220:     }
 221: 
 222:     private fun toggleDnd(): Boolean {
 223:         val m = nm ?: return false
 224:         if (!m.isNotificationPolicyAccessGranted) {
 225:             return launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
 226:         }
 227:         try {
 228:             m.setInterruptionFilter(
 229:                 if (dndOn) NotificationManager.INTERRUPTION_FILTER_ALL
 230:                 else NotificationManager.INTERRUPTION_FILTER_PRIORITY
 231:             )
 232:         } catch (e: SecurityException) {
 233:             return launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
 234:         }
 235:         if (refreshAll()) fire()
 236:         return false
 237:     }
 238: 
 239:     private fun toggleRotate(): Boolean {
 240:         if (!Settings.System.canWrite(app)) {
 241:             return launch(
 242:                 Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + app.packageName))
 243:             )
 244:         }
 245:         try {
 246:             Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, if (rotateOn) 0 else 1)
 247:         } catch (e: Exception) {
 248:             return false
 249:         }
 250:         if (refreshAll()) fire()
 251:         return false
 252:     }
 253: 
 254:     private fun toggleVibrate(): Boolean {
 255:         val am = audio ?: return false
 256:         try {
 257:             am.ringerMode = if (vibOn) AudioManager.RINGER_MODE_NORMAL else AudioManager.RINGER_MODE_VIBRATE
 258:         } catch (e: SecurityException) {
 259:             return launch(Intent(Settings.ACTION_SOUND_SETTINGS))
 260:         }
 261:         if (refreshAll()) fire()
 262:         return false
 263:     }
 264: 
 265:     // ---- state ------------------------------------------------------------------------
 266: 
 267:     /** Re-reads every state except the torch (which has its own callback). True if anything changed. */
 268:     private fun refreshAll(): Boolean {
 269:         var changed = false
 270: 
 271:         val w = readWifi()
 272:         if (w != wifiOn) { wifiOn = w; changed = true }
 273: 
 274:         val b = readBluetooth()
 275:         if (b != btOn) { btOn = b; changed = true }
 276: 
 277:         val d = readDnd()
 278:         if (d != dndOn) { dndOn = d; changed = true }
 279: 
 280:         val r = readRotate()
 281:         if (r != rotateOn) { rotateOn = r; changed = true }
 282: 
 283:         val v = readVibrate()
 284:         if (v != vibOn) { vibOn = v; changed = true }
 285: 
 286:         return changed
 287:     }
 288: 
 289:     private fun readWifi(): Boolean {
 290:         val wm = wifi ?: return false
 291:         return try {
 292:             val s = wm.wifiState
 293:             s == WifiManager.WIFI_STATE_ENABLED || s == WifiManager.WIFI_STATE_ENABLING
 294:         } catch (e: Exception) {
 295:             false
 296:         }
 297:     }
 298: 
 299:     private fun readBluetooth(): Boolean {
 300:         val a = adapter ?: return false
 301:         return try {
 302:             val s = a.state
 303:             s == BluetoothAdapter.STATE_ON || s == BluetoothAdapter.STATE_TURNING_ON
 304:         } catch (e: Exception) {
 305:             false
 306:         }
 307:     }
 308: 
 309:     private fun readDnd(): Boolean {
 310:         val m = nm ?: return false
 311:         return try {
 312:             m.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
 313:         } catch (e: Exception) {
 314:             false
 315:         }
 316:     }
 317: 
 318:     private fun readRotate(): Boolean = try {
 319:         Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
 320:     } catch (e: Exception) {
 321:         true
 322:     }
 323: 
 324:     private fun readVibrate(): Boolean {
 325:         val am = audio ?: return false
 326:         return try {
 327:             am.ringerMode != AudioManager.RINGER_MODE_NORMAL
 328:         } catch (e: Exception) {
 329:             false
 330:         }
 331:     }
 332: 
 333:     private fun findTorchId(): String? {
 334:         val cm = camera ?: return null
 335:         return try {
 336:             var any: String? = null
 337:             for (id in cm.cameraIdList) {
 338:                 val c = cm.getCameraCharacteristics(id)
 339:                 if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) continue
 340:                 if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) return id
 341:                 if (any == null) any = id
 342:             }
 343:             any
 344:         } catch (e: Exception) {
 345:             null
 346:         }
 347:     }
 348: 
 349:     private fun launch(intent: Intent): Boolean = try {
 350:         intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
 351:         ctx.startActivity(intent)
 352:         true
 353:     } catch (e: Exception) {
 354:         false
 355:     }
 356: }
===== Spring.kt =====
   1: package com.dynedge
   2: 
   3: import kotlin.math.abs
   4: import kotlin.math.cos
   5: import kotlin.math.exp
   6: import kotlin.math.sin
   7: import kotlin.math.sqrt
   8: 
   9: /**
  10:  * Damped spring with unit mass, integrated with the exact closed-form solution
  11:  * (under-, critically- and over-damped). Units: px and px/s. No allocations.
  12:  */
  13: class Spring(
  14:     var value: Float = 0f,
  15:     var stiffness: Float = 400f,
  16:     var dampingRatio: Float = 0.8f
  17: ) {
  18:     var velocity: Float = 0f
  19:     var target: Float = value
  20: 
  21:     fun snapTo(v: Float) {
  22:         value = v
  23:         target = v
  24:         velocity = 0f
  25:     }
  26: 
  27:     fun isSettled(posEps: Float, velEps: Float): Boolean =
  28:         abs(value - target) < posEps && abs(velocity) < velEps
  29: 
  30:     fun step(dtSec: Float) {
  31:         if (dtSec <= 0f) return
  32:         val t = dtSec.toDouble()
  33:         val w = sqrt(stiffness.toDouble().coerceAtLeast(1e-6))
  34:         val z = dampingRatio.toDouble().coerceAtLeast(0.0)
  35:         val x0 = (value - target).toDouble()
  36:         val v0 = velocity.toDouble()
  37:         val x: Double
  38:         val v: Double
  39:         if (z < 0.999) {
  40:             val a = z * w
  41:             val wd = w * sqrt(1.0 - z * z)
  42:             val b = (v0 + a * x0) / wd
  43:             val e = exp(-a * t)
  44:             val c = cos(wd * t)
  45:             val s = sin(wd * t)
  46:             x = e * (x0 * c + b * s)
  47:             v = e * ((-a * x0 + b * wd) * c + (-a * b - x0 * wd) * s)
  48:         } else if (z <= 1.001) {
  49:             val b = v0 + w * x0
  50:             val e = exp(-w * t)
  51:             x = (x0 + b * t) * e
  52:             v = (b - w * (x0 + b * t)) * e
  53:         } else {
  54:             val sq = sqrt(z * z - 1.0)
  55:             val r1 = -w * (z - sq)
  56:             val r2 = -w * (z + sq)
  57:             val c2 = (v0 - r1 * x0) / (r2 - r1)
  58:             val c1 = x0 - c2
  59:             val e1 = exp(r1 * t)
  60:             val e2 = exp(r2 * t)
  61:             x = c1 * e1 + c2 * e2
  62:             v = c1 * r1 * e1 + c2 * r2 * e2
  63:         }
  64:         value = (target + x).toFloat()
  65:         velocity = v.toFloat()
  66:     }
  67: }
===== Tiles.kt =====
   1: package com.dynedge
   2: 
   3: import android.accessibilityservice.AccessibilityService
   4: import android.content.Context
   5: import android.content.Intent
   6: import android.database.ContentObserver
   7: import android.net.Uri
   8: import android.os.Handler
   9: import android.os.Looper
  10: import android.provider.Settings
  11: 
  12: /** Quick tiles: the six QuickToggles plus battery saver, caffeine and global-action tiles. */
  13: class Tiles(private val ctx: Context, private val onChange: () -> Unit) {
  14: 
  15:     var service: AccessibilityService? = null
  16: 
  17:     private val app: Context = ctx.applicationContext ?: ctx
  18:     private val main = Handler(Looper.getMainLooper())
  19:     private val resolver = app.contentResolver
  20:     private val qt = QuickToggles(ctx, onChange)
  21: 
  22:     private var saverOn = false
  23:     private var caffeineOn = false
  24:     private var started = false
  25: 
  26:     private val lowPowerUri: Uri = Settings.Global.getUriFor(LOW_POWER)
  27:     private val timeoutUri: Uri = Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT)
  28: 
  29:     private val observer = object : ContentObserver(main) {
  30:         override fun onChange(selfChange: Boolean) {
  31:             if (refresh()) onChange()
  32:         }
  33:     }
  34: 
  35:     fun start() {
  36:         if (started) return
  37:         started = true
  38:         qt.start()
  39:         refresh()
  40:         try {
  41:             resolver.registerContentObserver(lowPowerUri, false, observer)
  42:             resolver.registerContentObserver(timeoutUri, false, observer)
  43:         } catch (e: Exception) {
  44:         }
  45:     }
  46: 
  47:     fun stop() {
  48:         if (!started) return
  49:         started = false
  50:         qt.stop()
  51:         try {
  52:             resolver.unregisterContentObserver(observer)
  53:         } catch (e: Exception) {
  54:         }
  55:     }
  56: 
  57:     fun isToggle(id: String): Boolean = id in TOGGLES
  58: 
  59:     fun isOn(id: String): Boolean {
  60:         val t = toggleId(id)
  61:         if (t != null) return qt.isOn(t)
  62:         return when (id) {
  63:             "battery_saver" -> saverOn
  64:             "caffeine" -> caffeineOn
  65:             else -> false
  66:         }
  67:     }
  68: 
  69:     fun isAvailable(id: String): Boolean {
  70:         val t = toggleId(id)
  71:         if (t != null) return qt.isAvailable(t)
  72:         return id in ALL
  73:     }
  74: 
  75:     fun iconRes(id: String): Int {
  76:         val t = toggleId(id)
  77:         if (t != null) return qt.iconRes(t)
  78:         return staticIcon(id)
  79:     }
  80: 
  81:     /** True => the caller collapses the panel first (activity launch or global action). */
  82:     fun perform(id: String): Boolean {
  83:         val t = toggleId(id)
  84:         if (t != null) return qt.toggle(t)
  85:         return when (id) {
  86:             "assist" -> AssistControl.launch(app)
  87:             "battery_saver" -> toggleSaver()
  88:             "caffeine" -> toggleCaffeine()
  89:             else -> {
  90:                 val action = globalAction(id)
  91:                 val s = service
  92:                 if (action != 0 && s != null) {
  93:                     s.performGlobalAction(action)
  94:                     true
  95:                 } else false
  96:             }
  97:         }
  98:     }
  99: 
 100:     fun openSettings(id: String): Boolean {
 101:         val t = toggleId(id)
 102:         if (t != null) return qt.openSettings(t)
 103:         return when (id) {
 104:             "battery_saver" -> launch(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
 105:             "caffeine" -> launch(Intent(Settings.ACTION_DISPLAY_SETTINGS))
 106:             else -> false
 107:         }
 108:     }
 109: 
 110:     // ---------------------------------------------------------------- tiles
 111: 
 112:     private fun toggleSaver(): Boolean {
 113:         try {
 114:             Settings.Global.putInt(resolver, LOW_POWER, if (saverOn) 0 else 1)
 115:         } catch (e: Exception) {
 116:             return launch(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
 117:         }
 118:         if (refresh()) onChange()
 119:         return false
 120:     }
 121: 
 122:     private fun toggleCaffeine(): Boolean {
 123:         if (!Settings.System.canWrite(app)) {
 124:             return launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + app.packageName)))
 125:         }
 126:         val p = Prefs.prefs(app)
 127:         try {
 128:             if (caffeineOn) {
 129:                 val prev = p.getInt(PREV_TIMEOUT, DEFAULT_TIMEOUT)
 130:                 Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, prev)
 131:                 p.edit().remove(PREV_TIMEOUT).apply()
 132:             } else {
 133:                 val cur = Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_TIMEOUT)
 134:                 p.edit().putInt(PREV_TIMEOUT, cur).apply()
 135:                 Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, CAFFEINE_MS)
 136:             }
 137:         } catch (e: Exception) {
 138:             return false
 139:         }
 140:         if (refresh()) onChange()
 141:         return false
 142:     }
 143: 
 144:     private fun refresh(): Boolean {
 145:         val s = try {
 146:             Settings.Global.getInt(resolver, LOW_POWER, 0) == 1
 147:         } catch (e: Exception) {
 148:             false
 149:         }
 150:         val c = try {
 151:             Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_TIMEOUT) == CAFFEINE_MS
 152:         } catch (e: Exception) {
 153:             false
 154:         }
 155:         val changed = s != saverOn || c != caffeineOn
 156:         saverOn = s
 157:         caffeineOn = c
 158:         return changed
 159:     }
 160: 
 161:     private fun launch(intent: Intent): Boolean = try {
 162:         intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
 163:         ctx.startActivity(intent)
 164:         true
 165:     } catch (e: Exception) {
 166:         false
 167:     }
 168: 
 169:     companion object {
 170:         private const val LOW_POWER = "low_power"
 171:         private const val PREV_TIMEOUT = "caffeine_prev"
 172:         private const val DEFAULT_TIMEOUT = 60000
 173:         private const val CAFFEINE_MS = 30 * 60 * 1000
 174: 
 175:         val ALL: List<String> = listOf(
 176:             "wifi", "bluetooth", "torch", "dnd", "rotate", "vibrate", "assist",
 177:             "battery_saver", "caffeine", "screenshot", "lock",
 178:             "notifications", "quick_settings", "recents", "power_menu", "clipboard"
 179:         )
 180: 
 181:         private val TOGGLES = setOf(
 182:             "wifi", "bluetooth", "torch", "dnd", "rotate", "vibrate", "battery_saver", "caffeine"
 183:         )
 184: 
 185:         private fun toggleId(id: String): ToggleId? = when (id) {
 186:             "wifi" -> ToggleId.WIFI
 187:             "bluetooth" -> ToggleId.BLUETOOTH
 188:             "torch" -> ToggleId.TORCH
 189:             "dnd" -> ToggleId.DND
 190:             "rotate" -> ToggleId.ROTATE
 191:             "vibrate" -> ToggleId.VIBRATE
 192:             else -> null
 193:         }
 194: 
 195:         private fun globalAction(id: String): Int = when (id) {
 196:             "screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
 197:             "lock" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
 198:             "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
 199:             "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
 200:             "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
 201:             "power_menu" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
 202:             else -> 0
 203:         }
 204: 
 205:         fun label(id: String): String = when (id) {
 206:             "wifi" -> "Wi-Fi"
 207:             "bluetooth" -> "Bluetooth"
 208:             "torch" -> "Torch"
 209:             "dnd" -> "DND"
 210:             "rotate" -> "Rotate"
 211:             "vibrate" -> "Vibrate"
 212:             "assist" -> "Assistant"
 213:             "battery_saver" -> "Saver"
 214:             "caffeine" -> "Caffeine"
 215:             "screenshot" -> "Shot"
 216:             "lock" -> "Lock"
 217:             "notifications" -> "Alerts"
 218:             "quick_settings" -> "Settings"
 219:             "recents" -> "Recents"
 220:             "power_menu" -> "Power"
 221:             "clipboard" -> "Clipboard"
 222:             else -> id
 223:         }
 224: 
 225:         fun staticIcon(id: String): Int = when (id) {
 226:             "wifi" -> R.drawable.ic_wifi
 227:             "bluetooth" -> R.drawable.ic_bluetooth
 228:             "torch" -> R.drawable.ic_flashlight
 229:             "dnd" -> R.drawable.ic_moon
 230:             "rotate" -> R.drawable.ic_rotate
 231:             "vibrate" -> R.drawable.ic_vibrate
 232:             "assist" -> R.drawable.ic_mic
 233:             "battery_saver" -> R.drawable.ic_battery_saver
 234:             "caffeine" -> R.drawable.ic_coffee
 235:             "screenshot" -> R.drawable.ic_screenshot
 236:             "lock" -> R.drawable.ic_lock
 237:             "notifications" -> R.drawable.ic_panel_top
 238:             "quick_settings" -> R.drawable.ic_sliders
 239:             "recents" -> R.drawable.ic_layers
 240:             "power_menu" -> R.drawable.ic_power
 241:             "clipboard" -> R.drawable.ic_clipboard
 242:             else -> R.drawable.ic_sliders
 243:         }
 244: 
 245:         fun needsCollapseDelay(id: String): Boolean = id == "screenshot"
 246:     }
 247: }
===== TimerControl.kt =====
   1: package com.dynedge
   2: 
   3: import android.content.Context
   4: import android.content.Intent
   5: import android.provider.AlarmClock
   6: 
   7: object TimerControl {
   8:     /** Starts a countdown in the system clock app without opening its UI. */
   9:     fun start(ctx: Context, seconds: Int): Boolean {
  10:         val i = Intent(AlarmClock.ACTION_SET_TIMER)
  11:             .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86400))
  12:             .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
  13:             .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  14:         return launch(ctx, i)
  15:     }
  16: 
  17:     fun openTimers(ctx: Context): Boolean =
  18:         launch(ctx, Intent(AlarmClock.ACTION_SHOW_TIMERS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  19: 
  20:     private fun launch(ctx: Context, i: Intent): Boolean = try {
  21:         ctx.startActivity(i)
  22:         true
  23:     } catch (e: Exception) {
  24:         false
  25:     }
  26: }
===== TouchRegion.kt =====
   1: package com.dynedge
   2: 
   3: import android.graphics.Region
   4: import android.util.Log
   5: import android.view.View
   6: import android.view.ViewTreeObserver
   7: import java.lang.reflect.Field
   8: import java.lang.reflect.InvocationHandler
   9: import java.lang.reflect.Method
  10: import java.lang.reflect.Proxy
  11: 
  12: /**
  13:  * Restricts the window's touchable area to an arbitrary Region through the hidden
  14:  * ViewTreeObserver.OnComputeInternalInsetsListener (TOUCHABLE_INSETS_REGION).
  15:  */
  16: class TouchRegion private constructor(
  17:     private val view: View,
  18:     private val setTouchableInsets: Method,
  19:     private val touchableRegion: Field,
  20:     private val addMethod: Method,
  21:     private val removeMethod: Method
  22: ) {
  23:     private val region = Region()
  24:     private var listener: Any? = null
  25:     private var observer: ViewTreeObserver? = null
  26: 
  27:     private fun attach(): Boolean {
  28:         val handler = InvocationHandler { p, m, args ->
  29:             when (m.name) {
  30:                 "onComputeInternalInsets" -> {
  31:                     val info = args?.getOrNull(0)
  32:                     if (info != null) {
  33:                         try {
  34:                             setTouchableInsets.invoke(info, TOUCHABLE_INSETS_REGION)
  35:                             (touchableRegion.get(info) as? Region)?.set(region)
  36:                         } catch (t: Throwable) {
  37:                             logOnce(t)
  38:                         }
  39:                     }
  40:                     null
  41:                 }
  42:                 "equals" -> p === args?.getOrNull(0)
  43:                 "hashCode" -> System.identityHashCode(p)
  44:                 "toString" -> "TouchRegion@" + Integer.toHexString(System.identityHashCode(p))
  45:                 else -> null
  46:             }
  47:         }
  48:         val cls = addMethod.parameterTypes[0]
  49:         val l = Proxy.newProxyInstance(cls.classLoader, arrayOf(cls), handler)
  50:         val vto = view.viewTreeObserver
  51:         addMethod.invoke(vto, l)
  52:         listener = l
  53:         observer = vto
  54:         return true
  55:     }
  56: 
  57:     /** Copies [r] and triggers a traversal so the insets are re-reported. */
  58:     fun set(r: Region) {
  59:         region.set(r)
  60:         view.invalidate()
  61:     }
  62: 
  63:     fun uninstall() {
  64:         val l = listener ?: return
  65:         listener = null
  66:         try {
  67:             val cur = view.viewTreeObserver
  68:             if (cur.isAlive) removeMethod.invoke(cur, l)
  69:             val old = observer
  70:             if (old != null && old !== cur && old.isAlive) removeMethod.invoke(old, l)
  71:         } catch (t: Throwable) {
  72:             logOnce(t)
  73:         }
  74:         observer = null
  75:     }
  76: 
  77:     companion object {
  78:         private const val TAG = "DynamicEdge"
  79:         private const val TOUCHABLE_INSETS_REGION = 3
  80:         private var logged = false
  81: 
  82:         private var cached = false
  83:         private var mSetInsets: Method? = null
  84:         private var fRegion: Field? = null
  85:         private var mAdd: Method? = null
  86:         private var mRemove: Method? = null
  87: 
  88:         private fun logOnce(t: Throwable) {
  89:             if (!logged) {
  90:                 logged = true
  91:                 Log.w(TAG, "TouchRegion reflection failed, falling back to full window", t)
  92:             }
  93:         }
  94: 
  95:         private fun lookup(): Boolean {
  96:             if (cached) return mSetInsets != null
  97:             cached = true
  98:             try {
  99:                 val listenerCls = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
 100:                 val infoCls = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
 101:                 val set = infoCls.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
 102:                 val field = infoCls.getField("touchableRegion")
 103:                 val add = ViewTreeObserver::class.java.getMethod("addOnComputeInternalInsetsListener", listenerCls)
 104:                 val remove = ViewTreeObserver::class.java.getMethod("removeOnComputeInternalInsetsListener", listenerCls)
 105:                 mSetInsets = set
 106:                 fRegion = field
 107:                 mAdd = add
 108:                 mRemove = remove
 109:                 return true
 110:             } catch (t: Throwable) {
 111:                 logOnce(t)
 112:                 return false
 113:             }
 114:         }
 115: 
 116:         fun install(view: View): TouchRegion? {
 117:             if (!lookup()) return null
 118:             return try {
 119:                 val tr = TouchRegion(view, mSetInsets!!, fRegion!!, mAdd!!, mRemove!!)
 120:                 tr.attach()
 121:                 tr
 122:             } catch (t: Throwable) {
 123:                 logOnce(t)
 124:                 null
 125:             }
 126:         }
 127:     }
 128: }
===== Win.kt =====
   1: package com.dynedge
   2: 
   3: import android.graphics.Canvas
   4: import android.graphics.Path
   5: import android.graphics.Rect
   6: import android.graphics.RectF
   7: import android.graphics.RenderNode
   8: import kotlin.math.max
   9: import kotlin.math.min
  10: 
  11: /**
  12:  * One panel window (QUICK, MEDIA, ...). A window owns its own layout, drawing and hit testing in the
  13:  * unrotated polar frame whose origin is the screen corner (x and y are negative inside the panel).
  14:  * The host ([EdgeView]) owns gestures, springs, paging, section layers and the shared paints.
  15:  *
  16:  * Item ids are local to the window (0 until its item count); the host adds the window to the pressed state.
  17:  */
  18: internal abstract class Win(@JvmField protected val h: EdgeView, val id: String, items: Int) {
  19: 
  20:     /** True while the section layer must be re-recorded. */
  21:     @JvmField var dirty = true
  22:     @JvmField var node: RenderNode? = null
  23:     @JvmField var index = -1
  24:     /** True between start() and stop() (panel open, listeners registered). */
  25:     @JvmField var started = false
  26:     @JvmField val bands = ArrayList<Array<Path>>()
  27: 
  28:     @JvmField protected val cx = FloatArray(items)
  29:     @JvmField protected val cy = FloatArray(items)
  30:     @JvmField protected val ir = FloatArray(items)
  31:     @JvmField protected val hitR = FloatArray(items)
  32:     @JvmField protected val iconRect = Array(items) { Rect() }
  33: 
  34:     @JvmField protected var pr = 0f // polar scratch: radius and angle (deg from the bottom edge)
  35:     @JvmField protected var pa = 0f
  36:     private var hBest = -1
  37:     private var hBestD = 0f
  38:     private val bandOval = RectF()
  39: 
  40:     protected fun dp(v: Float): Float = h.dp(v)
  41:     protected val rad: Float get() = h.rad
  42: 
  43:     // ---------------------------------------------------------------- lifecycle
  44:     /** Computes geometry for the current panel radius; bands are cleared by the host before. */
  45:     open fun layout(hw: Float, brad: Float) {}
  46:     /** Panel opened: register listeners and load data. */
  47:     open fun start() {}
  48:     /** Panel closed or window removed: release everything. */
  49:     open fun stop() {}
  50:     /** This window became (true) or stopped being (false) the visible, settled window. */
  51:     open fun onShown(on: Boolean) {}
  52:     /** Shared outer-ring sliders (Arcs) reported a change. */
  53:     open fun onArcs() {}
  54: 
  55:     // ---------------------------------------------------------------- drawing
  56:     abstract fun draw(c: Canvas, a: Float)
  57: 
  58:     fun drawBands(c: Canvas, a: Float) {
  59:         if (!h.bandsOn) return
  60:         h.tilePaint.alpha = (255f * 0.045f * a + 0.5f).toInt()
  61:         for (b in bands.indices) {
  62:             val ts = bands[b]
  63:             for (t in ts.indices) c.drawPath(ts[t], h.tilePaint)
  64:         }
  65:     }
  66: 
  67:     // ---------------------------------------------------------------- touch (local, unrotated coordinates)
  68:     /** Item under the point or -1. */
  69:     open fun hit(lx: Float, ly: Float): Int = -1
  70:     open fun usable(item: Int): Boolean = true
  71:     open fun longPressable(item: Int): Boolean = false
  72:     open fun onTap(item: Int) {}
  73:     /** Returns true if an Activity was launched (the host collapses). */
  74:     open fun onLongPress(item: Int): Boolean = false
  75:     /** DOWN landed on a draggable control (arc slider, seek bar, dial): claim the whole gesture. */
  76:     open fun capture(lx: Float, ly: Float): Boolean = false
  77:     open fun dragTo(lx: Float, ly: Float) {}
  78:     open fun dragEnd(cancel: Boolean) {}
  79: 
  80:     fun isPressed(i: Int): Boolean = h.pressed == i && h.pressedWin === this
  81: 
  82:     // ---------------------------------------------------------------- helpers
  83:     protected fun polar(lx: Float, ly: Float) {
  84:         pr = kotlin.math.sqrt(lx * lx + ly * ly)
  85:         pa = Math.toDegrees(kotlin.math.atan2(-ly.toDouble(), -lx.toDouble())).toFloat()
  86:     }
  87: 
  88:     protected fun beginHit() {
  89:         hBest = -1
  90:         hBestD = Float.MAX_VALUE
  91:     }
  92: 
  93:     protected fun consider(i: Int, lx: Float, ly: Float) {
  94:         val dx = lx - cx[i]
  95:         val dy = ly - cy[i]
  96:         val d2 = dx * dx + dy * dy
  97:         val hr = hitR[i]
  98:         if (d2 <= hr * hr && d2 < hBestD) {
  99:             hBest = i
 100:             hBestD = d2
 101:         }
 102:     }
 103: 
 104:     protected fun bestHit(): Int = hBest
 105: 
 106:     protected fun forceHit(i: Int) {
 107:         hBest = i
 108:     }
 109: 
 110:     /** Places item i on a ring (radius rc, angle aDeg) shrunk to stay inside the curve. */
 111:     protected fun place(i: Int, rc: Float, aDeg: Float, itemR: Float) {
 112:         val r = min(rc, rad - dp(6f) - itemR)
 113:         val a = Math.toRadians(aDeg.toDouble())
 114:         cx[i] = -r * kotlin.math.cos(a).toFloat()
 115:         cy[i] = -r * kotlin.math.sin(a).toFloat()
 116:         ir[i] = itemR
 117:         hitR[i] = max(itemR + dp(3f), dp(22f)) // 44 dp minimum touch target
 118:         val half = (itemR * 0.475f).toInt().coerceAtLeast(1)
 119:         val x = cx[i].toInt()
 120:         val y = cy[i].toInt()
 121:         iconRect[i].set(x - half, y - half, x + half, y + half)
 122:     }
 123: 
 124:     protected fun setIconRect(i: Int, half: Int) {
 125:         val x = cx[i].toInt()
 126:         val y = cy[i].toInt()
 127:         iconRect[i].set(x - half, y - half, x + half, y + half)
 128:     }
 129: 
 130:     protected fun addBand(r1: Float, r2: Float, a0: Float, a1: Float, n: Int) {
 131:         val inset = dp(1f)
 132:         val ri = r1 + inset
 133:         val ro = r2 - inset
 134:         if (ro <= ri + dp(2f) || n <= 0) return
 135:         val gapHalf = (dp(3f) + dp(2f)) / 2f
 136:         val oOut = Math.toDegrees((gapHalf / ro).toDouble()).toFloat()
 137:         val oIn = Math.toDegrees((gapHalf / ri).toDouble()).toFloat()
 138:         val outer = RectF(-ro, -ro, ro, ro)
 139:         val inner = RectF(-ri, -ri, ri, ri)
 140:         val span = (a1 - a0) / n
 141:         val arr = Array(n) { Path() }
 142:         for (t in 0 until n) {
 143:             val s = a0 + t * span
 144:             val e = s + span
 145:             val p = arr[t]
 146:             p.arcTo(outer, 180f + s + oOut, span - 2f * oOut, true)
 147:             p.arcTo(inner, 180f + e - oIn, -(span - 2f * oIn), false)
 148:             p.close()
 149:         }
 150:         bands.add(arr)
 151:     }
 152: 
 153:     protected fun arcPath(p: Path, r: Float, a0: Float, a1: Float) {
 154:         bandOval.set(-r, -r, r, r)
 155:         p.rewind()
 156:         p.arcTo(bandOval, 180f + a0, a1 - a0, true)
 157:     }
 158: 
 159:     /** Round button: optional fill disc, press ring and icon. */
 160:     protected fun drawBtn(c: Canvas, i: Int, fillColor: Int, res: Int, black: Boolean, a: Float, ba: Float) {
 161:         val x = cx[i]
 162:         val y = cy[i]
 163:         val ps = isPressed(i)
 164:         if (ps) {
 165:             c.save()
 166:             c.scale(EdgeView.PRESS_SCALE, EdgeView.PRESS_SCALE, x, y)
 167:         }
 168:         val ai = (255f * ba + 0.5f).toInt()
 169:         if (fillColor != 0) {
 170:             h.fillP.color = fillColor
 171:             h.fillP.alpha = ai
 172:             c.drawCircle(x, y, ir[i], h.fillP)
 173:         }
 174:         if (ps) {
 175:             h.ringPaint.alpha = (255f * a + 0.5f).toInt()
 176:             c.drawCircle(x, y, ir[i] + dp(3f), h.ringPaint)
 177:         }
 178:         if (res != 0) {
 179:             val ic = h.icon(res, black)
 180:             ic.bounds = iconRect[i]
 181:             ic.alpha = ai
 182:             ic.draw(c)
 183:         }
 184:         if (ps) c.restore()
 185:     }
 186: 
 187:     companion object {
 188:         const val SL_A0 = 8f
 189:         const val SL_A1 = 82f
 190: 
 191:         fun slotAngle(i: Int, n: Int): Float = if (n <= 1) 45f else 12f + i * 66f / (n - 1)
 192:     }
 193: }
===== WinApps.kt =====
   1: package com.dynedge
   2: 
   3: import android.content.Intent
   4: import android.graphics.Bitmap
   5: import android.graphics.BitmapShader
   6: import android.graphics.Canvas
   7: import android.graphics.Color
   8: import android.graphics.Matrix
   9: import android.graphics.Paint
  10: import android.graphics.Rect
  11: import android.graphics.Shader
  12: import kotlin.math.min
  13: 
  14: /** APPS window: up to 6 shortcut apps on two rings; "+" opens settings when none are set. */
  15: internal class WinApps(h: EdgeView) : Win(h, "apps", 7) {
  16: 
  17:     private val apps = AppShortcuts(h.context, h.dp(40f).toInt())
  18:     private var appCount = 0
  19:     private val appBmp = arrayOfNulls<Bitmap>(6)
  20:     private val appPaint = Array(6) { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
  21:     private val appMatrix = Matrix()
  22:     private val plusRect = Rect()
  23: 
  24:     override fun layout(hw: Float, brad: Float) {
  25:         val ri = 0.44f * rad
  26:         val ro = 0.70f * rad
  27:         place(0, ri, 25f, brad)
  28:         place(1, ri, 65f, brad)
  29:         for (k in 0..3) place(2 + k, ro, MID_A[k], brad)
  30:         place(PLUS, 0.60f * rad, 45f, dp(22f))
  31:         val ph = dp(9f).toInt()
  32:         plusRect.set((cx[PLUS] - ph).toInt(), (cy[PLUS] - ph).toInt(), (cx[PLUS] + ph).toInt(), (cy[PLUS] + ph).toInt())
  33:         addBand(ri - hw, ri + hw, 4f, 86f, 2)
  34:         addBand(ro - hw, ro + hw, 4f, 86f, 4)
  35:         for (i in 0..5) appBmp[i] = null
  36:         if (started) refresh()
  37:     }
  38: 
  39:     override fun start() {
  40:         apps.load()
  41:         refresh()
  42:     }
  43: 
  44:     override fun stop() {
  45:         apps.release()
  46:         for (i in 0..5) {
  47:             appBmp[i] = null
  48:             appPaint[i].shader = null
  49:         }
  50:         appCount = 0
  51:     }
  52: 
  53:     private fun refresh() {
  54:         dirty = true
  55:         appCount = min(apps.count, 6)
  56:         val r = dp(20f)
  57:         for (i in 0 until 6) {
  58:             val b = if (i < appCount) apps.icon(i) else null
  59:             if (b !== appBmp[i]) {
  60:                 appBmp[i] = b
  61:                 if (b == null) {
  62:                     appPaint[i].shader = null
  63:                 } else {
  64:                     val s = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
  65:                     appMatrix.setScale(2f * r / b.width, 2f * r / b.height)
  66:                     appMatrix.postTranslate(-r, -r)
  67:                     s.setLocalMatrix(appMatrix)
  68:                     appPaint[i].shader = s
  69:                 }
  70:             }
  71:         }
  72:     }
  73: 
  74:     override fun draw(c: Canvas, a: Float) {
  75:         if (appCount == 0) {
  76:             val x = cx[PLUS]
  77:             val y = cy[PLUS]
  78:             val ps = isPressed(PLUS)
  79:             val ai = (255f * a + 0.5f).toInt()
  80:             if (ps) {
  81:                 c.save()
  82:                 c.scale(EdgeView.PRESS_SCALE, EdgeView.PRESS_SCALE, x, y)
  83:                 h.ringPaint.alpha = ai
  84:                 c.drawCircle(x, y, ir[PLUS], h.ringPaint)
  85:             }
  86:             val lp = h.linePaint
  87:             lp.color = EdgeView.C_GREY
  88:             lp.alpha = ai
  89:             lp.strokeWidth = dp(2f)
  90:             val hh = dp(9f)
  91:             c.drawLine(x - hh, y, x + hh, y, lp)
  92:             c.drawLine(x, y - hh, x, y + hh, lp)
  93:             if (ps) c.restore()
  94:             return
  95:         }
  96:         for (k in 0 until appCount) {
  97:             val x = cx[k]
  98:             val y = cy[k]
  99:             val ps = isPressed(k)
 100:             c.save()
 101:             c.translate(x, y)
 102:             if (ps) c.scale(EdgeView.PRESS_SCALE, EdgeView.PRESS_SCALE)
 103:             val p = appPaint[k]
 104:             if (p.shader != null) {
 105:                 // white disc behind, so square/legacy icons don't show transparent corners
 106:                 h.fillP.color = Color.WHITE
 107:                 h.fillP.alpha = (255f * a + 0.5f).toInt()
 108:                 c.drawCircle(0f, 0f, dp(20f), h.fillP)
 109:                 p.alpha = (255f * a + 0.5f).toInt()
 110:                 c.drawCircle(0f, 0f, dp(20f), p)
 111:             }
 112:             if (ps) {
 113:                 h.ringPaint.alpha = (255f * a + 0.5f).toInt()
 114:                 c.drawCircle(0f, 0f, dp(23f), h.ringPaint)
 115:             }
 116:             c.restore()
 117:         }
 118:     }
 119: 
 120:     override fun hit(lx: Float, ly: Float): Int {
 121:         beginHit()
 122:         if (appCount == 0) consider(PLUS, lx, ly) else for (k in 0 until appCount) consider(k, lx, ly)
 123:         return bestHit()
 124:     }
 125: 
 126:     override fun onTap(item: Int) {
 127:         if (item == PLUS) {
 128:             try {
 129:                 val i = Intent(h.context, MainActivity::class.java)
 130:                 i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
 131:                 h.context.startActivity(i)
 132:             } catch (e: Exception) {
 133:             }
 134:             h.collapse()
 135:         } else if (apps.launch(item)) {
 136:             h.collapse()
 137:         }
 138:     }
 139: 
 140:     private companion object {
 141:         const val PLUS = 6
 142:         val MID_A = floatArrayOf(12f, 34.7f, 57.3f, 80f)
 143:     }
 144: }
===== WinClipboard.kt =====
   1: package com.dynedge
   2: 
   3: import android.graphics.Canvas
   4: import android.graphics.Color
   5: import android.graphics.Path
   6: import android.graphics.Rect
   7: import android.text.TextPaint
   8: import android.text.TextUtils
   9: import kotlin.math.abs
  10: import kotlin.math.cos
  11: import kotlin.math.min
  12: import kotlin.math.sin
  13: 
  14: /** CLIPBOARD window: up to 4 recent clips as arc rows; tap = paste, long-press = pin, badge = full history. */
  15: internal class WinClipboard(h: EdgeView) : Win(h, "clipboard", 5) {
  16: 
  17:     private val clipPaint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
  18:         color = Color.WHITE
  19:         textSize = h.sp(13f)
  20:     }
  21:     private val rowR = FloatArray(4)
  22:     private val rowLen = FloatArray(4)
  23:     private val rowPath = Array(4) { Path() }
  24:     private val emptyRect = Rect()
  25:     private var rowHalf = 0f
  26:     private var clips: List<ClipStore.Clip> = emptyList()
  27:     private var nClip = 0
  28:     private val clipShown = Array(4) { "" }
  29:     private val clipPin = BooleanArray(4)
  30:     private var store: ClipStore? = null
  31:     private val listener: () -> Unit = {
  32:         if (started) {
  33:             refresh()
  34:             h.invalidate()
  35:         }
  36:     }
  37: 
  38:     override fun layout(hw: Float, brad: Float) {
  39:         for (k in 0 until 4) {
  40:             rowR[k] = min(CLIP_F[k] * rad, rad - dp(24f))
  41:             arcPath(rowPath[k], rowR[k], CL_A0, CL_A1)
  42:             rowLen[k] = rowR[k] * Math.toRadians((CL_A1 - CL_A0).toDouble()).toFloat()
  43:         }
  44:         rowHalf = min(dp(16f), (rowR[1] - rowR[0]) / 2f)
  45:         place(MORE, 0.92f * rad, 80f, dp(13f))
  46:         // empty state icon
  47:         val ea = Math.toRadians(45.0)
  48:         val er = 0.55f * rad
  49:         val ex = (-er * cos(ea)).toInt()
  50:         val ey = (-er * sin(ea)).toInt()
  51:         val eh = dp(18f).toInt()
  52:         emptyRect.set(ex - eh, ey - eh, ex + eh, ey + eh)
  53:         for (k in 0 until 4) addBand(rowR[k] - dp(4f), rowR[k] + dp(18f), 6f, 76f, 1)
  54:         if (started) refresh()
  55:     }
  56: 
  57:     override fun start() {
  58:         val cs = ClipStore.get(h.context)
  59:         store = cs
  60:         cs.addListener(listener)
  61:         refresh()
  62:     }
  63: 
  64:     override fun stop() {
  65:         store?.removeListener(listener)
  66:         store = null
  67:     }
  68: 
  69:     override fun onShown(on: Boolean) {
  70:         // capture the system clipboard when this window becomes the visible one
  71:         if (on) h.requestCapture()
  72:     }
  73: 
  74:     private fun refresh() {
  75:         dirty = true
  76:         val list = (store ?: ClipStore.get(h.context)).list()
  77:         clips = list
  78:         nClip = min(4, list.size)
  79:         for (k in 0 until nClip) {
  80:             val c = list[k]
  81:             clipPin[k] = c.pinned
  82:             val flat = c.text.take(160).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()
  83:             clipShown[k] = TextUtils.ellipsize(flat, clipPaint, rowLen[k], TextUtils.TruncateAt.END).toString()
  84:         }
  85:     }
  86: 
  87:     override fun draw(c: Canvas, a: Float) {
  88:         val ai = (255f * a + 0.5f).toInt()
  89:         if (nClip == 0) {
  90:             val ic = h.icon(R.drawable.ic_clipboard, false)
  91:             ic.bounds = emptyRect
  92:             ic.alpha = (255f * 0.32f * a + 0.5f).toInt()
  93:             ic.draw(c)
  94:         } else {
  95:             clipPaint.alpha = ai
  96:             val ap = h.arcPaint
  97:             val lp = h.linePaint
  98:             for (k in 0 until nClip) {
  99:                 val r = rowR[k]
 100:                 if (isPressed(k)) {
 101:                     val rr = r + dp(5f)
 102:                     h.ovalScratch.set(-rr, -rr, rr, rr)
 103:                     ap.strokeWidth = rowHalf * 2f
 104:                     ap.color = Color.WHITE
 105:                     ap.alpha = (255f * 0.10f * a + 0.5f).toInt()
 106:                     c.drawArc(h.ovalScratch, 180f + CL_A0 - 2f, CL_A1 - CL_A0 + 4f, false, ap)
 107:                 }
 108:                 if (clipPin[k]) {
 109:                     val ta = Math.toRadians(4.5)
 110:                     val ct = cos(ta).toFloat()
 111:                     val st = sin(ta).toFloat()
 112:                     lp.color = h.accent
 113:                     lp.alpha = ai
 114:                     lp.strokeWidth = dp(2.2f)
 115:                     c.drawLine(-(r - dp(3f)) * ct, -(r - dp(3f)) * st, -(r + dp(13f)) * ct, -(r + dp(13f)) * st, lp)
 116:                 }
 117:                 c.drawTextOnPath(clipShown[k], rowPath[k], 0f, 0f, clipPaint)
 118:             }
 119:         }
 120:         // "more" badge: opens the full history
 121:         drawBtn(c, MORE, EdgeView.C_FILL, 0, false, a, a)
 122:         h.fillP.color = Color.WHITE
 123:         h.fillP.alpha = ai
 124:         val mx = cx[MORE]
 125:         val my = cy[MORE]
 126:         for (j in -1..1) c.drawCircle(mx + j * dp(5f), my, dp(1.6f), h.fillP)
 127:     }
 128: 
 129:     // ---------------------------------------------------------------- touch
 130:     override fun hit(lx: Float, ly: Float): Int {
 131:         beginHit()
 132:         consider(MORE, lx, ly)
 133:         if (bestHit() < 0) {
 134:             polar(lx, ly)
 135:             if (pa > CL_A0 - 4f && pa < CL_A1 + 4f) {
 136:                 for (k in 0 until nClip) {
 137:                     if (abs(pr - (rowR[k] + dp(5f))) < rowHalf) {
 138:                         forceHit(k)
 139:                         break
 140:                     }
 141:                 }
 142:             }
 143:         }
 144:         return bestHit()
 145:     }
 146: 
 147:     override fun longPressable(item: Int): Boolean = item in 0..3
 148: 
 149:     override fun onTap(item: Int) {
 150:         if (item == MORE) {
 151:             h.openClipboard()
 152:             h.collapse()
 153:             return
 154:         }
 155:         if (item >= clips.size) return
 156:         val text = clips[item].text
 157:         h.collapse()
 158:         h.postDelayed({
 159:             val svc = h.service
 160:             val ok = svc != null && ClipPaste.paste(svc, text)
 161:             if (!ok) h.showCopied()
 162:         }, 120L)
 163:     }
 164: 
 165:     override fun onLongPress(item: Int): Boolean {
 166:         if (item >= clips.size) return false
 167:         val c = clips[item]
 168:         (store ?: ClipStore.get(h.context)).pin(c.id, !c.pinned)
 169:         return false
 170:     }
 171: 
 172:     private companion object {
 173:         const val MORE = 4
 174:         const val CL_A0 = 8f
 175:         const val CL_A1 = 72f
 176:         val CLIP_F = floatArrayOf(0.36f, 0.52f, 0.68f, 0.84f)
 177:     }
 178: }
===== WinDevice.kt =====
   1: package com.dynedge
   2: 
   3: import android.graphics.Canvas
   4: import android.graphics.Color
   5: import android.graphics.Paint
   6: import android.graphics.Path
   7: import android.graphics.RectF
   8: import android.graphics.Typeface
   9: import android.text.TextPaint
  10: import kotlin.math.asin
  11: import kotlin.math.cos
  12: import kotlin.math.min
  13: import kotlin.math.sin
  14: 
  15: /** DEVICE window: display-only arcs for battery, RAM and storage. DeviceStats runs only while visible. */
  16: internal class WinDevice(h: EdgeView) : Win(h, "device", 1) {
  17: 
  18:     private val stats = DeviceStats(h.context) {
  19:         if (shown) {
  20:             refresh()
  21:             h.invalidate()
  22:         }
  23:     }
  24:     private var shown = false
  25:     private val oval = Array(3) { RectF() }
  26:     private val r = FloatArray(3)
  27:     private val a0 = FloatArray(3)
  28:     private val labelPath = Array(3) { Path() }
  29:     private val label = arrayOf("Battery", "RAM", "Storage")
  30:     private val frac = FloatArray(3)
  31:     private var pctStr = "0%"
  32:     private var tempStr = ""
  33:     private var charging = false
  34:     private var bx = 0f
  35:     private var by = 0f
  36:     private var pctX = 0f
  37:     private var pctY = 0f
  38:     private var tempY = 0f
  39:     private val sb = StringBuilder(16)
  40:     private val pctPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
  41:         color = Color.WHITE
  42:         textAlign = Paint.Align.CENTER
  43:         typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
  44:     }
  45:     private val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
  46:         color = EdgeView.C_GREY
  47:         textAlign = Paint.Align.CENTER
  48:         textSize = h.sp(10f)
  49:     }
  50:     private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
  51:         color = EdgeView.C_GREY
  52:         textSize = h.sp(10f)
  53:     }
  54: 
  55:     override fun layout(hw: Float, brad: Float) {
  56:         val f = floatArrayOf(0.86f, 0.72f, 0.58f)
  57:         val badgeR = dp(10f)
  58:         for (k in 0 until 3) {
  59:             val rr = min(f[k] * rad, rad - dp(14f))
  60:             r[k] = rr
  61:             oval[k].set(-rr, -rr, rr, rr)
  62:             a0[k] = SL_A0
  63:         }
  64:         // battery arc starts after the charging badge
  65:         val ba = Math.toDegrees(asin(((badgeR + dp(4f)) / r[0]).toDouble().coerceAtMost(1.0))).toFloat()
  66:         val bh = Math.toDegrees(asin((badgeR / r[0]).toDouble().coerceAtMost(1.0))).toFloat()
  67:         val bar = Math.toRadians(ba.toDouble())
  68:         bx = -r[0] * cos(bar).toFloat()
  69:         by = -r[0] * sin(bar).toFloat()
  70:         a0[0] = ba + bh + 2f
  71:         val s = badgeR * 0.6f
  72:         iconRect[0].set((bx - s).toInt(), (by - s).toInt(), (bx + s).toInt(), (by + s).toInt())
  73:         ir[0] = badgeR
  74:         // labels sit just inside each track, from the arc start
  75:         for (k in 0 until 3) {
  76:             arcPath(labelPath[k], r[k] - dp(15f), a0[k] + 4f, 80f)
  77:         }
  78:         val inner = 0.45f * rad
  79:         val va = Math.toRadians(45.0)
  80:         pctPaint.textSize = min(h.sp(32f), 0.15f * rad)
  81:         pctX = -inner * cos(va).toFloat()
  82:         pctY = -inner * sin(va).toFloat() - (pctPaint.ascent() + pctPaint.descent()) / 2f
  83:         tempY = pctY + dp(16f)
  84:         for (k in 0 until 3) addBand(r[k] - dp(9f), min(r[k] + dp(9f), rad - dp(2.5f)), 4f, 86f, 1)
  85:         if (started) refresh()
  86:     }
  87: 
  88:     override fun start() {
  89:         // DeviceStats itself runs only while this window is the visible one (onShown)
  90:     }
  91: 
  92:     override fun stop() {
  93:         shown = false
  94:         stats.stop()
  95:     }
  96: 
  97:     override fun onShown(on: Boolean) {
  98:         shown = on
  99:         if (on) stats.start() else stats.stop()
 100:     }
 101: 
 102:     private fun refresh() {
 103:         dirty = true
 104:         frac[0] = stats.batteryPct / 100f
 105:         frac[1] = stats.ramUsedFrac
 106:         frac[2] = stats.storageUsedFrac
 107:         charging = stats.charging
 108:         sb.setLength(0)
 109:         sb.append(stats.batteryPct).append('%')
 110:         pctStr = sb.toString()
 111:         sb.setLength(0)
 112:         sb.append(Math.round(stats.tempC)).append(" C")
 113:         tempStr = sb.toString()
 114:     }
 115: 
 116:     override fun draw(c: Canvas, a: Float) {
 117:         val ai = (255f * a + 0.5f).toInt()
 118:         val ap = h.arcPaint
 119:         for (k in 0 until 3) {
 120:             ap.strokeWidth = dp(5f)
 121:             ap.color = EdgeView.C_TRACK
 122:             ap.alpha = ai
 123:             c.drawArc(oval[k], 180f + a0[k], SL_A1 - a0[k], false, ap)
 124:             val lv = frac[k].coerceIn(0f, 1f)
 125:             if (lv > 0.001f) {
 126:                 ap.color = if (k == 0) h.accent else Color.WHITE
 127:                 ap.alpha = if (k == 0) ai else (255f * 0.85f * a + 0.5f).toInt()
 128:                 c.drawArc(oval[k], 180f + a0[k], (SL_A1 - a0[k]) * lv, false, ap)
 129:             }
 130:             labelPaint.alpha = ai
 131:             c.drawTextOnPath(label[k], labelPath[k], 0f, 0f, labelPaint)
 132:         }
 133:         if (charging) {
 134:             h.fillP.color = h.accent
 135:             h.fillP.alpha = ai
 136:             c.drawCircle(bx, by, ir[0], h.fillP)
 137:             val ic = h.icon(R.drawable.ic_bolt, true)
 138:             ic.bounds = iconRect[0]
 139:             ic.alpha = ai
 140:             ic.draw(c)
 141:         }
 142:         pctPaint.alpha = ai
 143:         c.drawText(pctStr, pctX, pctY, pctPaint)
 144:         smallPaint.alpha = ai
 145:         c.drawText(tempStr, pctX, tempY, smallPaint)
 146:     }
 147: }
===== WindowsConfig.kt =====
   1: package com.dynedge
   2: 
   3: import android.content.Context
   4: import org.json.JSONArray
   5: import org.json.JSONObject
   6: 
   7: /** Data-driven list of panel windows: order and on/off, stored as JSON in Prefs. */
   8: object WindowsConfig {
   9:     const val KEY = "windows"
  10:     val ALL = listOf("quick", "media", "clipboard", "apps", "volume", "timer", "notifications", "device")
  11:     val DEFAULT_ON = setOf("quick", "media", "clipboard", "apps")
  12: 
  13:     data class Entry(val id: String, val on: Boolean)
  14: 
  15:     fun load(ctx: Context): List<Entry> {
  16:         val out = ArrayList<Entry>()
  17:         val s = Prefs.prefs(ctx).getString(KEY, null)
  18:         if (s != null) {
  19:             try {
  20:                 val a = JSONArray(s)
  21:                 for (i in 0 until a.length()) {
  22:                     val o = a.optJSONObject(i) ?: continue
  23:                     val id = o.optString("id")
  24:                     if (id in ALL && out.none { it.id == id }) out.add(Entry(id, o.optBoolean("on", false)))
  25:                 }
  26:             } catch (e: Exception) {
  27:                 out.clear()
  28:             }
  29:         }
  30:         if (s == null || out.isEmpty()) {
  31:             out.clear()
  32:             for (id in ALL) out.add(Entry(id, id in DEFAULT_ON))
  33:             return out
  34:         }
  35:         for (id in ALL) if (out.none { it.id == id }) out.add(Entry(id, false))
  36:         return out
  37:     }
  38: 
  39:     fun enabled(ctx: Context): List<String> {
  40:         val l = load(ctx).filter { it.on }.map { it.id }
  41:         return if (l.isEmpty()) listOf("quick") else l
  42:     }
  43: 
  44:     fun save(ctx: Context, list: List<Entry>) {
  45:         val a = JSONArray()
  46:         val seen = HashSet<String>()
  47:         for (e in list) {
  48:             if (e.id !in ALL || !seen.add(e.id)) continue
  49:             a.put(JSONObject().put("id", e.id).put("on", e.on))
  50:         }
  51:         Prefs.prefs(ctx).edit().putString(KEY, a.toString()).apply()
  52:     }
  53: 
  54:     fun label(id: String): String = when (id) {
  55:         "quick" -> "Quick"
  56:         "media" -> "Media"
  57:         "clipboard" -> "Clipboard"
  58:         "apps" -> "Apps"
  59:         "volume" -> "Volume"
  60:         "timer" -> "Timer"
  61:         "notifications" -> "Alerts"
  62:         "device" -> "Device"
  63:         else -> id
  64:     }
  65: 
  66:     fun icon(id: String): Int = when (id) {
  67:         "quick" -> R.drawable.ic_sliders
  68:         "media" -> R.drawable.ic_music
  69:         "clipboard" -> R.drawable.ic_clipboard
  70:         "apps" -> R.drawable.ic_layers
  71:         "volume" -> R.drawable.ic_volume
  72:         "timer" -> R.drawable.ic_timer
  73:         "notifications" -> R.drawable.ic_bell
  74:         "device" -> R.drawable.ic_cpu
  75:         else -> R.drawable.ic_sliders
  76:     }
  77: }
===== WinMedia.kt =====
   1: package com.dynedge
   2: 
   3: import android.graphics.Bitmap
   4: import android.graphics.BitmapShader
   5: import android.graphics.Canvas
   6: import android.graphics.Color
   7: import android.graphics.Matrix
   8: import android.graphics.Paint
   9: import android.graphics.Path
  10: import android.graphics.Rect
  11: import android.graphics.RectF
  12: import android.graphics.Shader
  13: import android.graphics.Typeface
  14: import android.text.TextPaint
  15: import android.text.TextUtils
  16: import android.view.HapticFeedbackConstants
  17: import kotlin.math.cos
  18: import kotlin.math.max
  19: import kotlin.math.min
  20: import kotlin.math.sin
  21: import kotlin.math.sqrt
  22: 
  23: /** MEDIA window: art circle (tap = play/pause), prev/next on the ring, title/artist on arcs, progress arc. */
  24: internal class WinMedia(h: EdgeView) : Win(h, "media", 3) {
  25: 
  26:     private val media = MediaBridge(h.context, h.dp(76f).toInt()) { onMedia() }
  27: 
  28:     private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  29:     private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
  30:         color = Color.WHITE
  31:         textSize = h.sp(14f)
  32:         typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
  33:     }
  34:     private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
  35:         color = EdgeView.C_GREY
  36:         textSize = h.sp(12f)
  37:     }
  38:     private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
  39:         color = EdgeView.C_GREY
  40:         textAlign = Paint.Align.CENTER
  41:         textSize = h.sp(10f)
  42:     }
  43: 
  44:     // geometry
  45:     var artRad = 0f
  46:         private set
  47:     private val artIconRect = Rect()
  48:     private val playIconRect = Rect()
  49:     private val mArcOval = RectF()
  50:     private var mArcR = 0f
  51:     private val titlePath = Path()
  52:     private val artistPath = Path()
  53:     private var titleLen = 0f
  54:     private var artistLen = 0f
  55:     private var elapsedX = 0f
  56:     private var elapsedY = 0f
  57:     private var totalX = 0f
  58:     private var totalY = 0f
  59: 
  60:     // state
  61:     var playing = false
  62:         private set
  63:     private var hasSession = false
  64:     private var rawTitle: String? = null
  65:     private var rawArtist: String? = null
  66:     private var titleShown: String = ""
  67:     private var artistShown: String = ""
  68:     private var titleOff = 0f
  69:     private var artistOff = 0f
  70:     private var artBmp: Bitmap? = null
  71:     private val shaderMatrix = Matrix()
  72:     private var progressFrac = 0f
  73:     private var lastElapsedSec = -1L
  74:     private var lastTotalSec = -1L
  75:     private var elapsedStr: String = "0:00"
  76:     private var totalStr: String = "0:00"
  77:     private val sb = StringBuilder(8)
  78:     private var seekActive = false
  79:     private var seekFrac = 0f
  80:     private var shown = false
  81: 
  82:     val artX: Float get() = cx[ART]
  83:     val artY: Float get() = cy[ART]
  84: 
  85:     private val tickRunnable = object : Runnable {
  86:         override fun run() {
  87:             if (shown && started) {
  88:                 refresh()
  89:                 h.invalidate()
  90:                 if (playing) h.postDelayed(this, 1000L)
  91:             }
  92:         }
  93:     }
  94: 
  95:     override fun layout(hw: Float, brad: Float) {
  96:         val artD = (0.30f * rad).coerceIn(dp(52f), dp(76f))
  97:         artRad = artD / 2f
  98:         val rArt = 0.52f * rad
  99:         place(ART, rArt, 45f, artRad)
 100:         place(PREV, rArt, 14f, min(brad, dp(18f)))
 101:         place(NEXT, rArt, 76f, min(brad, dp(18f)))
 102:         val artEdge = sqrt(cx[ART] * cx[ART] + cy[ART] * cy[ART]) + artRad
 103:         val artistR = max(0.72f * rad, artEdge + dp(8f))
 104:         val titleR = artistR + dp(17f)
 105:         mArcR = min(max(0.88f * rad, titleR + dp(29f)), rad - dp(10f))
 106:         mArcOval.set(-mArcR, -mArcR, mArcR, mArcR)
 107:         arcPath(titlePath, titleR, MT_A0, MT_A1)
 108:         arcPath(artistPath, artistR, MT_A0, MT_A1)
 109:         val span = Math.toRadians((MT_A1 - MT_A0).toDouble()).toFloat()
 110:         titleLen = titleR * span
 111:         artistLen = artistR * span
 112: 
 113:         val ih = dp(10f).toInt()
 114:         artIconRect.set((cx[ART] - ih).toInt(), (cy[ART] - ih).toInt(), (cx[ART] + ih).toInt(), (cy[ART] + ih).toInt())
 115:         val ph = dp(9f).toInt()
 116:         playIconRect.set((cx[ART] - ph).toInt(), (cy[ART] - ph).toInt(), (cx[ART] + ph).toInt(), (cy[ART] + ph).toInt())
 117: 
 118:         val tr = mArcR - dp(22f)
 119:         val a8 = Math.toRadians(8.0)
 120:         val a82 = Math.toRadians(82.0)
 121:         elapsedX = -tr * cos(a8).toFloat()
 122:         elapsedY = -tr * sin(a8).toFloat() + dp(3.5f)
 123:         totalX = -tr * cos(a82).toFloat()
 124:         totalY = -tr * sin(a82).toFloat() + dp(3.5f)
 125: 
 126:         val bandHalf = max(artRad, dp(18f)) + dp(4f)
 127:         addBand(rArt - bandHalf, rArt + bandHalf, 4f, 86f, 3)
 128:         addBand(artistR - dp(5f), titleR + dp(17f), 14f, 76f, 1)
 129:         addBand(mArcR - dp(11f), min(mArcR + dp(11f), rad - dp(2.5f)), 4f, 86f, 1)
 130: 
 131:         // force caches to rebuild against the new geometry
 132:         rawTitle = null
 133:         rawArtist = null
 134:         artBmp = null
 135:         lastElapsedSec = -1L
 136:         lastTotalSec = -1L
 137:         if (started) refresh()
 138:     }
 139: 
 140:     override fun start() {
 141:         media.start()
 142:         refresh()
 143:     }
 144: 
 145:     override fun stop() {
 146:         shown = false
 147:         h.removeCallbacks(tickRunnable)
 148:         media.stop()
 149:     }
 150: 
 151:     override fun onShown(on: Boolean) {
 152:         shown = on
 153:         h.removeCallbacks(tickRunnable)
 154:         if (on && playing) h.postDelayed(tickRunnable, 1000L)
 155:     }
 156: 
 157:     private fun onMedia() {
 158:         if (!started) return
 159:         refresh()
 160:         h.invalidate()
 161:         if (shown) {
 162:             h.removeCallbacks(tickRunnable)
 163:             if (playing) h.postDelayed(tickRunnable, 1000L)
 164:         }
 165:     }
 166: 
 167:     private fun refresh() {
 168:         dirty = true
 169:         val has = media.hasSession
 170:         hasSession = has
 171:         val newTitle: String
 172:         val newArtist: String
 173:         if (has) {
 174:             val t = media.title
 175:             val idleSession = t.isNullOrBlank() && !media.isPlaying
 176:             newTitle = if (!t.isNullOrBlank()) t else if (idleSession) "Not playing" else "Playing"
 177:             newArtist = media.artist ?: if (idleSession) "Tap play to resume" else ""
 178:         } else {
 179:             newTitle = "Not playing"
 180:             newArtist = "Tap play to resume"
 181:         }
 182:         if (newTitle != rawTitle) {
 183:             rawTitle = newTitle
 184:             titleShown = TextUtils.ellipsize(newTitle, titlePaint, titleLen, TextUtils.TruncateAt.END).toString()
 185:             titleOff = (titleLen - titlePaint.measureText(titleShown)) / 2f
 186:         }
 187:         if (newArtist != rawArtist) {
 188:             rawArtist = newArtist
 189:             artistShown = TextUtils.ellipsize(newArtist, artistPaint, artistLen, TextUtils.TruncateAt.END).toString()
 190:             artistOff = (artistLen - artistPaint.measureText(artistShown)) / 2f
 191:         }
 192:         val b = if (has) media.art else null
 193:         if (b !== artBmp) {
 194:             artBmp = b
 195:             if (b == null) {
 196:                 artPaint.shader = null
 197:             } else {
 198:                 val s = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
 199:                 shaderMatrix.setScale(2f * artRad / b.width, 2f * artRad / b.height)
 200:                 shaderMatrix.postTranslate(-artRad, -artRad)
 201:                 s.setLocalMatrix(shaderMatrix)
 202:                 artPaint.shader = s
 203:             }
 204:         }
 205:         playing = has && media.isPlaying
 206:         if (seekActive) return
 207:         val dur = if (has) media.durationMs else 0L
 208:         val pos = if (has) media.positionMs() else 0L
 209:         progressFrac = if (dur > 0L) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
 210:         val es = pos / 1000L
 211:         if (es != lastElapsedSec) {
 212:             lastElapsedSec = es
 213:             elapsedStr = fmt(es)
 214:         }
 215:         val ts = dur / 1000L
 216:         if (ts != lastTotalSec) {
 217:             lastTotalSec = ts
 218:             totalStr = fmt(ts)
 219:         }
 220:     }
 221: 
 222:     private fun fmt(totalSec: Long): String {
 223:         val s = if (totalSec < 0L) 0L else totalSec
 224:         sb.setLength(0)
 225:         sb.append(s / 60L).append(':')
 226:         val r = s % 60L
 227:         if (r < 10L) sb.append('0')
 228:         sb.append(r)
 229:         return sb.toString()
 230:     }
 231: 
 232:     override fun draw(c: Canvas, a: Float) {
 233:         val ai = (255f * a + 0.5f).toInt()
 234:         val fill = h.fillP
 235:         // art circle (tap = play/pause)
 236:         run {
 237:             val x = cx[ART]
 238:             val y = cy[ART]
 239:             val ps = isPressed(ART)
 240:             if (ps) {
 241:                 c.save()
 242:                 c.scale(EdgeView.PRESS_SCALE, EdgeView.PRESS_SCALE, x, y)
 243:             }
 244:             if (artBmp != null) {
 245:                 c.save()
 246:                 c.translate(x, y)
 247:                 artPaint.alpha = ai
 248:                 c.drawCircle(0f, 0f, artRad, artPaint)
 249:                 c.restore()
 250:             } else {
 251:                 fill.color = EdgeView.C_FILL
 252:                 fill.alpha = ai
 253:                 c.drawCircle(x, y, artRad, fill)
 254:             }
 255:             fill.color = Color.BLACK
 256:             fill.alpha = (255f * 0.42f * a + 0.5f).toInt()
 257:             c.drawCircle(x, y, dp(15f), fill)
 258:             val pi = h.icon(if (playing) R.drawable.ic_pause else R.drawable.ic_play, false)
 259:             pi.bounds = playIconRect
 260:             pi.alpha = ai
 261:             pi.draw(c)
 262:             if (ps) {
 263:                 h.ringPaint.alpha = ai
 264:                 c.drawCircle(x, y, artRad + dp(3f), h.ringPaint)
 265:                 c.restore()
 266:             }
 267:         }
 268:         drawBtn(c, PREV, EdgeView.C_FILL, R.drawable.ic_skip_back, false, a, a)
 269:         drawBtn(c, NEXT, EdgeView.C_FILL, R.drawable.ic_skip_forward, false, a, a)
 270: 
 271:         // title / artist on arcs
 272:         titlePaint.alpha = ai
 273:         artistPaint.alpha = ai
 274:         timePaint.alpha = ai
 275:         c.drawTextOnPath(titleShown, titlePath, titleOff, 0f, titlePaint)
 276:         c.drawTextOnPath(artistShown, artistPath, artistOff, 0f, artistPaint)
 277: 
 278:         // progress arc
 279:         val ap = h.arcPaint
 280:         ap.strokeWidth = dp(3.5f)
 281:         ap.color = EdgeView.C_TRACK
 282:         ap.alpha = ai
 283:         c.drawArc(mArcOval, 180f + SL_A0, SL_A1 - SL_A0, false, ap)
 284:         if (progressFrac > 0.002f) {
 285:             ap.color = h.accent
 286:             ap.alpha = ai
 287:             c.drawArc(mArcOval, 180f + SL_A0, (SL_A1 - SL_A0) * progressFrac, false, ap)
 288:         }
 289:         val ang = Math.toRadians((SL_A0 + (SL_A1 - SL_A0) * progressFrac).toDouble())
 290:         fill.color = h.accent
 291:         fill.alpha = ai
 292:         c.drawCircle(-mArcR * cos(ang).toFloat(), -mArcR * sin(ang).toFloat(), dp(4.5f), fill)
 293:         c.drawText(elapsedStr, elapsedX, elapsedY, timePaint)
 294:         c.drawText(totalStr, totalX, totalY, timePaint)
 295:     }
 296: 
 297:     // ---------------------------------------------------------------- touch
 298:     override fun hit(lx: Float, ly: Float): Int {
 299:         beginHit()
 300:         consider(ART, lx, ly); consider(PREV, lx, ly); consider(NEXT, lx, ly)
 301:         return bestHit()
 302:     }
 303: 
 304:     override fun longPressable(item: Int): Boolean = item == ART
 305: 
 306:     override fun onTap(item: Int) {
 307:         when (item) {
 308:             ART -> media.playPause()
 309:             PREV -> media.prev()
 310:             NEXT -> media.next()
 311:         }
 312:     }
 313: 
 314:     override fun onLongPress(item: Int): Boolean = if (item == ART) media.openPlayer() else false
 315: 
 316:     override fun capture(lx: Float, ly: Float): Boolean {
 317:         if (!started || !media.canSeek || media.durationMs <= 0L) return false
 318:         polar(lx, ly)
 319:         if (!(kotlin.math.abs(pr - mArcR) < dp(22f) && pa > SL_A0 - 4f && pa < SL_A1 + 4f)) return false
 320:         seekActive = true
 321:         return true
 322:     }
 323: 
 324:     override fun dragTo(lx: Float, ly: Float) {
 325:         polar(lx, ly)
 326:         val f = ((pa - SL_A0) / (SL_A1 - SL_A0)).coerceIn(0f, 1f)
 327:         seekFrac = f
 328:         progressFrac = f
 329:         dirty = true
 330:         val es = (f * media.durationMs / 1000f).toLong()
 331:         if (es != lastElapsedSec) {
 332:             lastElapsedSec = es
 333:             elapsedStr = fmt(es)
 334:         }
 335:         h.invalidate()
 336:     }
 337: 
 338:     override fun dragEnd(cancel: Boolean) {
 339:         if (!seekActive) return
 340:         seekActive = false
 341:         if (!cancel && started) {
 342:             media.seekTo((seekFrac * media.durationMs).toLong())
 343:             h.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
 344:         }
 345:     }
 346: 
 347:     private companion object {
 348:         const val ART = 0
 349:         const val PREV = 1
 350:         const val NEXT = 2
 351:         const val MT_A0 = 16f
 352:         const val MT_A1 = 74f
 353:     }
 354: }
===== WinNotifications.kt =====
   1: package com.dynedge
   2: 
   3: import android.graphics.Bitmap
   4: import android.graphics.BitmapShader
   5: import android.graphics.Canvas
   6: import android.graphics.Color
   7: import android.graphics.Matrix
   8: import android.graphics.Paint
   9: import android.graphics.Path
  10: import android.graphics.Rect
  11: import android.graphics.Shader
  12: import android.text.TextPaint
  13: import android.text.TextUtils
  14: import kotlin.math.abs
  15: import kotlin.math.cos
  16: import kotlin.math.min
  17: import kotlin.math.sin
  18: 
  19: /** NOTIFICATIONS window: up to 4 recent notifications as arc rows; tap opens, long-press dismisses. */
  20: internal class WinNotifications(h: EdgeView) : Win(h, "notifications", 4) {
  21: 
  22:     private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
  23:         color = Color.WHITE
  24:         textSize = h.sp(13f)
  25:     }
  26:     private val rowR = FloatArray(4)
  27:     private val rowPath = Array(4) { Path() }
  28:     private val rowLen = FloatArray(4)
  29:     private val iconX = FloatArray(4)
  30:     private val iconY = FloatArray(4)
  31:     private val emptyRect = Rect()
  32:     private var rowHalf = 0f
  33:     private var iconD = 0f
  34:     private var textOff = 0f
  35:     private var items: List<NotifFeed.Item> = emptyList()
  36:     private var n = 0
  37:     private val shown = Array(4) { "" }
  38:     private val iconPaint = Array(4) { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
  39:     private val iconBmp = arrayOfNulls<Bitmap>(4)
  40:     private val iconMatrix = Matrix()
  41: 
  42:     override fun layout(hw: Float, brad: Float) {
  43:         iconD = dp(16f)
  44:         textOff = iconD + dp(8f)
  45:         for (k in 0 until 4) {
  46:             rowR[k] = min(CLIP_F[k] * rad, rad - dp(24f))
  47:             arcPath(rowPath[k], rowR[k], A0, A1)
  48:             rowLen[k] = rowR[k] * Math.toRadians((A1 - A0).toDouble()).toFloat()
  49:             val ia = Math.toRadians((A0 + Math.toDegrees(((iconD / 2f + dp(1f)) / rowR[k]).toDouble())))
  50:             val ro = rowR[k] + dp(5f)
  51:             iconX[k] = -ro * cos(ia).toFloat()
  52:             iconY[k] = -ro * sin(ia).toFloat()
  53:         }
  54:         rowHalf = min(dp(16f), (rowR[1] - rowR[0]) / 2f)
  55:         val ea = Math.toRadians(45.0)
  56:         val er = 0.55f * rad
  57:         val ex = (-er * cos(ea)).toInt()
  58:         val ey = (-er * sin(ea)).toInt()
  59:         val eh = dp(18f).toInt()
  60:         emptyRect.set(ex - eh, ey - eh, ex + eh, ey + eh)
  61:         for (k in 0 until 4) addBand(rowR[k] - dp(4f), rowR[k] + dp(18f), 6f, 76f, 1)
  62:         for (k in 0 until 4) iconBmp[k] = null
  63:         if (started) refresh()
  64:     }
  65: 
  66:     override fun start() {
  67:         NotifFeed.onChange = {
  68:             if (started) {
  69:                 refresh()
  70:                 h.invalidate()
  71:             }
  72:         }
  73:         refresh()
  74:     }
  75: 
  76:     override fun stop() {
  77:         NotifFeed.onChange = null
  78:         for (k in 0 until 4) {
  79:             iconBmp[k] = null
  80:             iconPaint[k].shader = null
  81:         }
  82:         items = emptyList()
  83:         n = 0
  84:     }
  85: 
  86:     private fun refresh() {
  87:         dirty = true
  88:         val list = NotifFeed.recent()
  89:         items = list
  90:         n = min(4, list.size)
  91:         val px = dp(16f).toInt().coerceAtLeast(8)
  92:         val r = iconD / 2f
  93:         for (k in 0 until n) {
  94:             val it = list[k]
  95:             val body = if (it.title.isBlank()) it.text else if (it.text.isBlank()) it.title else it.title + " - " + it.text
  96:             val flat = body.take(160).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()
  97:             shown[k] = TextUtils.ellipsize(flat, textPaint, max0(rowLen[k] - textOff), TextUtils.TruncateAt.END).toString()
  98:             val b = NotifFeed.appIcon(h.context, it.pkg, px)
  99:             if (b !== iconBmp[k]) {
 100:                 iconBmp[k] = b
 101:                 if (b == null) {
 102:                     iconPaint[k].shader = null
 103:                 } else {
 104:                     val s = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
 105:                     iconMatrix.setScale(2f * r / b.width, 2f * r / b.height)
 106:                     iconMatrix.postTranslate(-r, -r)
 107:                     s.setLocalMatrix(iconMatrix)
 108:                     iconPaint[k].shader = s
 109:                 }
 110:             }
 111:         }
 112:         for (k in n until 4) {
 113:             iconBmp[k] = null
 114:             iconPaint[k].shader = null
 115:         }
 116:     }
 117: 
 118:     private fun max0(v: Float) = if (v < 0f) 0f else v
 119: 
 120:     override fun draw(c: Canvas, a: Float) {
 121:         val ai = (255f * a + 0.5f).toInt()
 122:         if (n == 0) {
 123:             val ic = h.icon(R.drawable.ic_bell, false)
 124:             ic.bounds = emptyRect
 125:             ic.alpha = (255f * 0.32f * a + 0.5f).toInt()
 126:             ic.draw(c)
 127:             return
 128:         }
 129:         textPaint.alpha = ai
 130:         val ap = h.arcPaint
 131:         for (k in 0 until n) {
 132:             val r = rowR[k]
 133:             if (isPressed(k)) {
 134:                 val rr = r + dp(5f)
 135:                 h.ovalScratch.set(-rr, -rr, rr, rr)
 136:                 ap.strokeWidth = rowHalf * 2f
 137:                 ap.color = Color.WHITE
 138:                 ap.alpha = (255f * 0.10f * a + 0.5f).toInt()
 139:                 c.drawArc(h.ovalScratch, 180f + A0 - 2f, A1 - A0 + 4f, false, ap)
 140:             }
 141:             val p = iconPaint[k]
 142:             if (p.shader != null) {
 143:                 c.save()
 144:                 c.translate(iconX[k], iconY[k])
 145:                 p.alpha = ai
 146:                 c.drawCircle(0f, 0f, iconD / 2f, p)
 147:                 c.restore()
 148:             }
 149:             c.drawTextOnPath(shown[k], rowPath[k], textOff, 0f, textPaint)
 150:         }
 151:     }
 152: 
 153:     override fun hit(lx: Float, ly: Float): Int {
 154:         beginHit()
 155:         polar(lx, ly)
 156:         if (pa > A0 - 4f && pa < A1 + 4f) {
 157:             for (k in 0 until n) {
 158:                 if (abs(pr - (rowR[k] + dp(5f))) < rowHalf) {
 159:                     forceHit(k)
 160:                     break
 161:                 }
 162:             }
 163:         }
 164:         return bestHit()
 165:     }
 166: 
 167:     override fun longPressable(item: Int): Boolean = true
 168: 
 169:     override fun onTap(item: Int) {
 170:         val it = items.getOrNull(item) ?: return
 171:         val pi = it.contentIntent
 172:         if (pi != null) {
 173:             try {
 174:                 pi.send()
 175:             } catch (e: Exception) {
 176:             }
 177:         }
 178:         h.collapse()
 179:     }
 180: 
 181:     override fun onLongPress(item: Int): Boolean {
 182:         val it = items.getOrNull(item) ?: return false
 183:         if (it.clearable) NotifFeed.dismiss(it.key)
 184:         return false
 185:     }
 186: 
 187:     private companion object {
 188:         const val A0 = 6f
 189:         const val A1 = 72f
 190:         val CLIP_F = floatArrayOf(0.36f, 0.52f, 0.68f, 0.84f)
 191:     }
 192: }
===== WinQuick.kt =====
   1: package com.dynedge
   2: 
   3: import android.accessibilityservice.AccessibilityService
   4: import android.graphics.Canvas
   5: import android.graphics.Color
   6: import android.view.HapticFeedbackConstants
   7: import kotlin.math.min
   8: 
   9: /** QUICK window: inner and middle tile rings plus up to two outer-arc sliders (QuickConfig driven). */
  10: internal class WinQuick(h: EdgeView) : Win(h, "quick", 11) {
  11: 
  12:     private val tiles = Tiles(h.context) { onTiles() }
  13:     private val arcSet = ArcSet(h, this, ARC0, 2, h.dp(15f), 0f)
  14:     private var nIn = 0
  15:     private var nMid = 0
  16:     private val slotId = arrayOfNulls<String>(9)     // 0..3 inner, 4..8 middle
  17:     private val sOn = BooleanArray(9)
  18:     private val sAvail = BooleanArray(9)
  19:     private val sTog = BooleanArray(9)
  20:     private val sIcon = IntArray(9)
  21:     private val radii = FloatArray(2)
  22: 
  23:     init {
  24:         load()
  25:     }
  26: 
  27:     fun setService(s: AccessibilityService?) {
  28:         tiles.service = s
  29:     }
  30: 
  31:     /** QuickConfig.KEY changed. */
  32:     fun reload() {
  33:         load()
  34:         h.relayout()
  35:         if (started) {
  36:             refreshTiles()
  37:             arcSet.refresh()
  38:         }
  39:         dirty = true
  40:     }
  41: 
  42:     private fun load() {
  43:         val l = QuickConfig.load(h.context)
  44:         nIn = min(l.inner.size, QuickConfig.INNER_MAX).coerceAtMost(4)
  45:         nMid = min(l.middle.size, QuickConfig.MIDDLE_MAX).coerceAtMost(5)
  46:         arcSet.set(l.arcs.take(min(l.arcs.size, QuickConfig.ARCS_MAX).coerceAtMost(2)))
  47:         for (i in 0 until 9) slotId[i] = null
  48:         for (i in 0 until nIn) slotId[i] = l.inner[i]
  49:         for (i in 0 until nMid) slotId[MID0 + i] = l.middle[i]
  50:         for (i in 0 until 9) {
  51:             val id = slotId[i]
  52:             sIcon[i] = if (id != null) Tiles.staticIcon(id) else 0
  53:             sOn[i] = false
  54:             sTog[i] = false
  55:             sAvail[i] = true
  56:         }
  57:         dirty = true
  58:     }
  59: 
  60:     private fun slotValid(i: Int): Boolean = if (i < 4) i < nIn else (i - MID0) < nMid
  61: 
  62:     override fun layout(hw: Float, brad: Float) {
  63:         val two = arcSet.n >= 2
  64:         val ri = (if (two) 0.38f else 0.42f) * rad
  65:         val rm = (if (two) 0.60f else 0.66f) * rad
  66:         for (i in 0 until nIn) place(i, ri, slotAngle(i, nIn), slotRadius(ri, nIn, brad))
  67:         for (i in 0 until nMid) place(MID0 + i, rm, slotAngle(i, nMid), slotRadius(rm, nMid, brad))
  68:         for (k in 0 until arcSet.n) {
  69:             val f = if (k == 0) 0.88f else 0.78f
  70:             radii[k] = min(f * rad, rad - dp(14f))
  71:         }
  72:         arcSet.layout(radii)
  73:         addBand(ri - hw, ri + hw, 4f, 86f, nIn)
  74:         addBand(rm - hw, rm + hw, 4f, 86f, nMid)
  75:         for (k in 0 until arcSet.n) {
  76:             val r = arcSet.radius(k)
  77:             addBand(r - dp(10f), min(r + dp(10f), rad - dp(2.5f)), 4f, 86f, 1)
  78:         }
  79:         if (started) {
  80:             refreshTiles()
  81:             arcSet.refresh()
  82:         }
  83:     }
  84: 
  85:     private fun slotRadius(r: Float, n: Int, base: Float): Float {
  86:         if (n <= 1) return base
  87:         val half = r * kotlin.math.sin(Math.toRadians(66.0 / (n - 1) / 2.0)).toFloat()
  88:         return min(base, half - dp(1.5f)).coerceAtLeast(dp(10f))
  89:     }
  90: 
  91:     override fun start() {
  92:         tiles.service = h.service
  93:         tiles.start()
  94:         refreshTiles()
  95:         arcSet.refresh()
  96:     }
  97: 
  98:     override fun stop() {
  99:         tiles.stop()
 100:         arcSet.release()
 101:     }
 102: 
 103:     override fun onArcs() {
 104:         if (!started) return
 105:         arcSet.refresh()
 106:         h.invalidate()
 107:     }
 108: 
 109:     private fun onTiles() {
 110:         if (!started) return
 111:         refreshTiles()
 112:         h.invalidate()
 113:     }
 114: 
 115:     private fun refreshTiles() {
 116:         dirty = true
 117:         for (i in 0 until 9) {
 118:             val id = slotId[i]
 119:             if (id == null || !slotValid(i)) continue
 120:             sTog[i] = tiles.isToggle(id)
 121:             sOn[i] = tiles.isOn(id)
 122:             sAvail[i] = tiles.isAvailable(id)
 123:             val ic = tiles.iconRes(id)
 124:             sIcon[i] = ic
 125:             h.warm(ic)
 126:         }
 127:     }
 128: 
 129:     override fun draw(c: Canvas, a: Float) {
 130:         // Inactive controls are monochrome; the accent marks only active / selected state.
 131:         for (i in 0 until 9) {
 132:             if (!slotValid(i)) continue
 133:             val on = sTog[i] && sOn[i]
 134:             drawBtn(c, i, if (on) Color.WHITE else EdgeView.C_FILL, sIcon[i], on, a, (if (sAvail[i]) 1f else 0.35f) * a)
 135:         }
 136:         arcSet.draw(c, a, h.accent)
 137:     }
 138: 
 139:     // ---------------------------------------------------------------- touch
 140:     override fun hit(lx: Float, ly: Float): Int {
 141:         beginHit()
 142:         for (i in 0 until 9) if (slotValid(i)) consider(i, lx, ly)
 143:         val k = arcSet.badgeHit(lx, ly)
 144:         if (k >= 0) forceHit(ARC0 + k)
 145:         return bestHit()
 146:     }
 147: 
 148:     override fun usable(item: Int): Boolean = if (item < 9) sAvail[item] else true
 149: 
 150:     override fun longPressable(item: Int): Boolean = item in 0..8
 151: 
 152:     override fun capture(lx: Float, ly: Float): Boolean = arcSet.capture(lx, ly)
 153:     override fun dragTo(lx: Float, ly: Float) = arcSet.dragTo(lx, ly)
 154:     override fun dragEnd(cancel: Boolean) = arcSet.dragEnd(cancel)
 155: 
 156:     override fun onTap(item: Int) {
 157:         if (item >= ARC0) {
 158:             arcSet.tap(item - ARC0)
 159:             return
 160:         }
 161:         val id = slotId[item] ?: return
 162:         if (id == "clipboard") {
 163:             // jump to the CLIPBOARD window when it is enabled, else open the full history
 164:             if (!h.jumpTo("clipboard")) {
 165:                 h.openClipboard()
 166:                 h.collapse()
 167:             }
 168:             h.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
 169:             return
 170:         }
 171:         if (Tiles.needsCollapseDelay(id)) {
 172:             // e.g. screenshot: let the panel get out of the picture first
 173:             h.collapse()
 174:             val t = tiles
 175:             h.postDelayed({ t.perform(id) }, 350L)
 176:             return
 177:         }
 178:         val collapse = tiles.perform(id)
 179:         refreshTiles()
 180:         if (collapse) h.collapse()
 181:     }
 182: 
 183:     override fun onLongPress(item: Int): Boolean {
 184:         val id = slotId.getOrNull(item) ?: return false
 185:         if (id == "clipboard") {
 186:             h.openClipboard()
 187:             return true
 188:         }
 189:         return tiles.openSettings(id)
 190:     }
 191: 
 192:     private companion object {
 193:         const val MID0 = 4
 194:         const val ARC0 = 9
 195:     }
 196: }
===== WinTimer.kt =====
   1: package com.dynedge
   2: 
   3: import android.graphics.Canvas
   4: import android.graphics.Color
   5: import android.graphics.Paint
   6: import android.graphics.RectF
   7: import android.graphics.Typeface
   8: import android.text.TextPaint
   9: import android.view.HapticFeedbackConstants
  10: import kotlin.math.abs
  11: import kotlin.math.cos
  12: import kotlin.math.max
  13: import kotlin.math.min
  14: import kotlin.math.sin
  15: 
  16: /**
  17:  * TIMER window: dial arc (drag sets 1..60 min, snaps per minute, tick every 5), preset chips, start button.
  18:  * Start hands the countdown to the system clock app and collapses; long-press start opens the timer list.
  19:  */
  20: internal class WinTimer(h: EdgeView) : Win(h, "timer", 5) {
  21: 
  22:     private val dial = RectF()
  23:     private var dialR = 0f
  24:     private var minutes = 10
  25:     private var valueStr = "10:00"
  26:     private var valueX = 0f
  27:     private var valueY = 0f
  28:     private var dragging = false
  29:     private val sb = StringBuilder(8)
  30:     private val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
  31:         color = Color.WHITE
  32:         textAlign = Paint.Align.CENTER
  33:         typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
  34:     }
  35:     private val chipPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
  36:         textAlign = Paint.Align.CENTER
  37:         typeface = Typeface.DEFAULT_BOLD
  38:         textSize = h.sp(12f)
  39:     }
  40: 
  41:     init {
  42:         buildValue()
  43:     }
  44: 
  45:     override fun layout(hw: Float, brad: Float) {
  46:         dialR = min(0.86f * rad, rad - dp(14f))
  47:         dial.set(-dialR, -dialR, dialR, dialR)
  48:         val chipR = min(dp(15f), brad)
  49:         val ringR = 0.68f * rad
  50:         for (i in 0 until 4) place(i, ringR, slotAngle(i, 4), chipR)
  51:         val inner = max(0.40f * rad, dp(78f))
  52:         place(START, inner, 24f, dp(20f))
  53:         setIconRect(START, dp(10f).toInt())
  54:         val va = Math.toRadians(62.0)
  55:         valueX = -inner * cos(va).toFloat()
  56:         valueY = -inner * sin(va).toFloat()
  57:         valuePaint.textSize = min(h.sp(24f), 0.13f * rad)
  58:         valueY -= (valuePaint.ascent() + valuePaint.descent()) / 2f
  59:         addBand(dialR - dp(10f), min(dialR + dp(10f), rad - dp(2.5f)), 4f, 86f, 1)
  60:         addBand(ringR - dp(18f), ringR + dp(18f), 4f, 86f, 4)
  61:     }
  62: 
  63:     private fun buildValue() {
  64:         sb.setLength(0)
  65:         sb.append(minutes).append(":00")
  66:         valueStr = sb.toString()
  67:     }
  68: 
  69:     private fun setMinutes(m: Int) {
  70:         val v = m.coerceIn(1, 60)
  71:         if (v == minutes) return
  72:         minutes = v
  73:         buildValue()
  74:         dirty = true
  75:         h.invalidate()
  76:     }
  77: 
  78:     override fun draw(c: Canvas, a: Float) {
  79:         val ai = (255f * a + 0.5f).toInt()
  80:         val ap = h.arcPaint
  81:         ap.strokeWidth = dp(6f)
  82:         ap.color = EdgeView.C_TRACK
  83:         ap.alpha = ai
  84:         c.drawArc(dial, 180f + SL_A0, SL_A1 - SL_A0, false, ap)
  85:         val f = minutes / 60f
  86:         ap.color = h.accent
  87:         ap.alpha = ai
  88:         c.drawArc(dial, 180f + SL_A0, (SL_A1 - SL_A0) * f, false, ap)
  89:         val ang = Math.toRadians((SL_A0 + (SL_A1 - SL_A0) * f).toDouble())
  90:         h.fillP.color = Color.WHITE
  91:         h.fillP.alpha = ai
  92:         c.drawCircle(-dialR * cos(ang).toFloat(), -dialR * sin(ang).toFloat(), dp(if (dragging) 9f else 7f), h.fillP)
  93: 
  94:         valuePaint.alpha = ai
  95:         c.drawText(valueStr, valueX, valueY, valuePaint)
  96: 
  97:         for (i in 0 until 4) {
  98:             val sel = PRESETS[i] == minutes
  99:             drawBtn(c, i, if (sel) h.accent else EdgeView.C_FILL, 0, false, a, a)
 100:             chipPaint.color = if (sel) Color.BLACK else Color.WHITE
 101:             chipPaint.alpha = ai
 102:             c.drawText(PRESET_TXT[i], cx[i], cy[i] - (chipPaint.ascent() + chipPaint.descent()) / 2f, chipPaint)
 103:         }
 104:         drawBtn(c, START, h.accent, R.drawable.ic_play, true, a, a)
 105:     }
 106: 
 107:     override fun hit(lx: Float, ly: Float): Int {
 108:         beginHit()
 109:         for (i in 0..START) consider(i, lx, ly)
 110:         return bestHit()
 111:     }
 112: 
 113:     override fun longPressable(item: Int): Boolean = item == START
 114: 
 115:     override fun onTap(item: Int) {
 116:         if (item < 4) {
 117:             setMinutes(PRESETS[item])
 118:             h.haptic(HapticFeedbackConstants.CLOCK_TICK)
 119:             return
 120:         }
 121:         TimerControl.start(h.context, minutes * 60)
 122:         h.haptic(HapticFeedbackConstants.CONFIRM)
 123:         h.collapse()
 124:     }
 125: 
 126:     override fun onLongPress(item: Int): Boolean = TimerControl.openTimers(h.context)
 127: 
 128:     override fun capture(lx: Float, ly: Float): Boolean {
 129:         polar(lx, ly)
 130:         if (abs(pr - dialR) < dp(22f) && pa > SL_A0 - 4f && pa < SL_A1 + 4f) {
 131:             dragging = true
 132:             return true
 133:         }
 134:         return false
 135:     }
 136: 
 137:     override fun dragTo(lx: Float, ly: Float) {
 138:         polar(lx, ly)
 139:         val f = ((pa - SL_A0) / (SL_A1 - SL_A0)).coerceIn(0f, 1f)
 140:         val m = Math.round(f * 60f).coerceIn(1, 60)
 141:         if (m != minutes) {
 142:             minutes = m
 143:             buildValue()
 144:             dirty = true
 145:             if (m % 5 == 0) h.haptic(HapticFeedbackConstants.CLOCK_TICK)
 146:         }
 147:         h.invalidate()
 148:     }
 149: 
 150:     override fun dragEnd(cancel: Boolean) {
 151:         if (!dragging) return
 152:         dragging = false
 153:         dirty = true
 154:         h.invalidate()
 155:     }
 156: 
 157:     private companion object {
 158:         const val START = 4
 159:         val PRESETS = intArrayOf(1, 5, 10, 25)
 160:         val PRESET_TXT = arrayOf("1", "5", "10", "25")
 161:     }
 162: }
===== WinVolume.kt =====
   1: package com.dynedge
   2: 
   3: import android.graphics.Canvas
   4: import kotlin.math.min
   5: 
   6: /** VOLUME window: up to four concentric stream sliders (media, ring, alarm, notification), badge = mute. */
   7: internal class WinVolume(h: EdgeView) : Win(h, "volume", 4) {
   8: 
   9:     private val arcSet = ArcSet(h, this, 0, 4, h.dp(11f), 5f)
  10:     private val radii = FloatArray(4)
  11: 
  12:     init {
  13:         arcSet.set(listOf("media_volume", "ring_volume", "alarm_volume", "notification_volume"))
  14:     }
  15: 
  16:     override fun layout(hw: Float, brad: Float) {
  17:         for (k in 0 until arcSet.n) radii[k] = min(RF[k] * rad, rad - dp(14f))
  18:         arcSet.layout(radii)
  19:         for (k in 0 until arcSet.n) {
  20:             val r = arcSet.radius(k)
  21:             addBand(r - dp(9f), min(r + dp(9f), rad - dp(2.5f)), 4f, 86f, 1)
  22:         }
  23:         if (started) arcSet.refresh()
  24:     }
  25: 
  26:     override fun start() {
  27:         arcSet.refresh()
  28:     }
  29: 
  30:     override fun stop() {
  31:         arcSet.release()
  32:     }
  33: 
  34:     override fun onArcs() {
  35:         if (!started) return
  36:         arcSet.refresh()
  37:         h.invalidate()
  38:     }
  39: 
  40:     override fun draw(c: Canvas, a: Float) {
  41:         arcSet.draw(c, a, h.accent)
  42:     }
  43: 
  44:     override fun hit(lx: Float, ly: Float): Int = arcSet.badgeHit(lx, ly)
  45: 
  46:     override fun onTap(item: Int) {
  47:         arcSet.tap(item)
  48:     }
  49: 
  50:     override fun capture(lx: Float, ly: Float): Boolean = arcSet.capture(lx, ly)
  51:     override fun dragTo(lx: Float, ly: Float) = arcSet.dragTo(lx, ly)
  52:     override fun dragEnd(cancel: Boolean) = arcSet.dragEnd(cancel)
  53: 
  54:     private companion object {
  55:         val RF = floatArrayOf(0.92f, 0.80f, 0.68f, 0.56f)
  56:     }
  57: }

codex
The repository is at v1.4 platform code, and the engine is present too, so I’m reviewing the implementation against the v1.4 contract rather than the older v1.3 architecture note alone. The initial bulk dump was truncated; I’m narrowing the inspection to the service, renderer/gesture path, settings, and shared window lifecycle.
exec
"C:\\WINDOWS\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" -Command '$names='"'EdgeView.kt','EdgeService.kt','Win.kt','MainActivity.kt','TouchRegion.kt','Spring.kt','IdleVisualizer.kt','IdleMediaWatch.kt','WinMedia.kt','WinClipboard.kt','WinNotifications.kt','WinDevice.kt','WinApps.kt','WindowsConfig.kt','NotifFeed.kt','DeviceStats.kt'; foreach("'$name in $names){$f="app/src/main/java/com/dynedge/$name"; "===== $name ====="; $n=0; Get-Content $f | ForEach-Object {$n++; '"'{0,4}: {1}' -f "'$n,$_}}' in D:\VS\PROJECTS\FREE WORK\dynamic-edge
 succeeded in 678ms:
===== EdgeView.kt =====
   1: package com.dynedge
   2: 
   3: import android.accessibilityservice.AccessibilityService
   4: import android.app.PendingIntent
   5: import android.content.BroadcastReceiver
   6: import android.content.Context
   7: import android.content.IntentFilter
   8: import android.os.BatteryManager
   9: import android.os.SystemClock
  10: import android.text.format.DateFormat
  11: import java.util.Calendar
  12: import android.content.Intent
  13: import android.graphics.Bitmap
  14: import android.graphics.BitmapShader
  15: import android.graphics.Canvas
  16: import android.graphics.Color
  17: import android.graphics.Matrix
  18: import android.graphics.Paint
  19: import android.graphics.Path
  20: import android.graphics.Rect
  21: import android.graphics.RectF
  22: import android.graphics.RenderNode
  23: import android.graphics.Region
  24: import android.graphics.Shader
  25: import android.graphics.Typeface
  26: import android.graphics.drawable.Drawable
  27: import android.text.TextPaint
  28: import android.text.TextUtils
  29: import android.util.SparseArray
  30: import android.util.TypedValue
  31: import android.view.Choreographer
  32: import android.view.HapticFeedbackConstants
  33: import android.view.MotionEvent
  34: import android.view.VelocityTracker
  35: import android.view.View
  36: import android.view.ViewConfiguration
  37: import kotlin.math.abs
  38: import kotlin.math.asin
  39: import kotlin.math.atan2
  40: import kotlin.math.ceil
  41: import kotlin.math.cos
  42: import kotlin.math.max
  43: import kotlin.math.min
  44: import kotlin.math.sin
  45: import kotlin.math.sqrt
  46: 
  47: 
  48: /**
  49:  * The Dynamic Edge host view: shape, springs, paging, section layers and touch dispatch.
  50:  * The view fills its window and everything is anchored to the bottom-right corner.
  51:  *
  52:  * The open state is a true quarter circle of radius R centred on the corner. Each window (WinQuick, WinMedia,
  53:  * WinClipboard, WinApps, WinVolume, WinTimer, WinNotifications, WinDevice) lays out, draws and hit-tests its own
  54:  * content in polar coordinates around the corner; the list and order come from WindowsConfig. Windows are
  55:  * rotated around the corner when paging and cached as RenderNode layers (re-recorded only when dirty).
  56:  * To add a window: subclass Win, register it in make(), add its id to WindowsConfig.
  57:  * When closed, the bean can morph into a live activity (LiveBus): call, timer, progress, charging, track change.
  58:  */
  59: class EdgeView(ctx: Context) : View(ctx) {
  60: 
  61:     interface Host {
  62:         fun onSettled(expanded: Boolean)
  63:         fun onRegionChanged(region: Region)
  64:         /** Makes the overlay window focusable (true) or not focusable (false); used for a short clipboard capture. */
  65:         fun setWindowFocusable(on: Boolean)
  66:     }
  67: 
  68:     var host: Host? = null
  69: 
  70:     // ---------------------------------------------------------------- metrics
  71:     private val density = resources.displayMetrics.density
  72:     internal fun dp(v: Float) = v * density
  73:     internal fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
  74: 
  75:     private var idle = dp(Prefs.idleDp(ctx))
  76:     internal var rad = dp(Prefs.openDp(ctx)) // R, radius of the open quarter circle
  77:         private set
  78:     private var tipPx = 0f
  79:     private var flarePx = dp(Prefs.flareDp(ctx)) // edge fillet radius (open size)
  80: 
  81:     val idleSizePx: Int get() = ceil(idle).toInt()
  82:     val maxOpenWidthPx: Int get() = ceil(rad + flarePx).toInt()
  83:     val maxOpenHeightPx: Int get() = ceil(rad + flarePx).toInt()
  84: 
  85:     // ---------------------------------------------------------------- state
  86:     private val wS = Spring(idle, 420f, 0.74f)
  87:     private val hS = Spring(idle, 300f, 0.78f)
  88:     private val pageS = Spring(0f, 380f, 0.86f)
  89:     private val press = Spring(1f, 700f, 0.75f)
  90: 
  91:     private var page = 0
  92:     private var lastPageId = "quick"
  93:     private var openPicked = false
  94:     private var userPaged = false
  95:     private var pageStart = 0f
  96:     private var dragShift = 0f
  97: 
  98:     private var progress = 0f
  99:     private var contentAlpha = 0f
 100:     private var collapsing = true
 101:     private var expandedTarget = false
 102:     private var settledExpanded = false
 103:     private var regionExpanded = false
 104:     private var regionClosed = idle
 105:     private var hidden = false
 106:     internal var sourcesStarted = false
 107:         private set
 108: 
 109:     val isExpanded: Boolean get() = expandedTarget
 110: 
 111:     /** The accessibility service: needed by global-action tiles and by clipboard paste. */
 112:     internal var service: AccessibilityService? = null
 113:         private set
 114: 
 115:     fun setService(s: AccessibilityService?) {
 116:         service = s
 117:         (winMap["quick"] as? WinQuick)?.setService(s)
 118:     }
 119: 
 120:     // ---------------------------------------------------------------- windows and sources
 121:     private val winMap = HashMap<String, Win>()
 122:     private var order = ArrayList<Win>()      // enabled windows in display order
 123:     private var nWin = 1
 124:     private var shownWin: Win? = null
 125:     internal val arcs = Arcs(ctx) { onArcsChanged() }
 126:     private var arcsOn = false
 127: 
 128:     private fun make(id: String): Win = when (id) {
 129:         "media" -> WinMedia(this)
 130:         "clipboard" -> WinClipboard(this)
 131:         "apps" -> WinApps(this)
 132:         "volume" -> WinVolume(this)
 133:         "timer" -> WinTimer(this)
 134:         "notifications" -> WinNotifications(this)
 135:         "device" -> WinDevice(this)
 136:         else -> WinQuick(this)
 137:     }
 138: 
 139:     private fun indexOfId(id: String): Int {
 140:         for (i in order.indices) if (order[i].id == id) return i
 141:         return -1
 142:     }
 143: 
 144:     private val mediaWin: WinMedia? get() = order.firstOrNull { it is WinMedia } as? WinMedia
 145: 
 146:     private fun startWin(w: Win) {
 147:         if (w.started) return
 148:         w.started = true
 149:         w.start()
 150:     }
 151: 
 152:     private fun stopWin(w: Win) {
 153:         if (!w.started) return
 154:         w.started = false
 155:         w.stop()
 156:     }
 157: 
 158:     private fun syncArcs() {
 159:         val need = sourcesStarted && order.any { it is WinQuick || it is WinVolume }
 160:         if (need == arcsOn) return
 161:         arcsOn = need
 162:         if (need) arcs.start() else arcs.stop()
 163:     }
 164: 
 165:     private fun onArcsChanged() {
 166:         if (!sourcesStarted) return
 167:         for (w in order) w.onArcs()
 168:         invalidate()
 169:     }
 170: 
 171:     /** The visible, settled window gets onShown(true); everything else false. */
 172:     private fun syncShown() {
 173:         val want: Win? = if (expandedTarget && settledExpanded && !hidden && screenOn) order.getOrNull(page) else null
 174:         if (want === shownWin) return
 175:         shownWin?.onShown(false)
 176:         shownWin = want
 177:         want?.onShown(true)
 178:     }
 179: 
 180:     /** Re-reads WindowsConfig: keeps the current window if still enabled, else the last one by id. */
 181:     internal fun reloadWindows() {
 182:         val ids = WindowsConfig.enabled(context)
 183:         val curId = order.getOrNull(page)?.id
 184:         val next = ArrayList<Win>(ids.size)
 185:         for (id in ids) next.add(winMap.getOrPut(id) { make(id) })
 186:         val old = order
 187:         for (w in old) {
 188:             if (w !in next) {
 189:                 if (shownWin === w) {
 190:                     w.onShown(false)
 191:                     shownWin = null
 192:                 }
 193:                 stopWin(w)
 194:                 w.node?.discardDisplayList()
 195:                 w.node = null
 196:                 w.dirty = true
 197:             }
 198:         }
 199:         order = next
 200:         nWin = next.size
 201:         for (i in next.indices) next[i].index = i
 202:         (winMap["quick"] as? WinQuick)?.setService(service)
 203:         val want = if (curId != null && next.any { it.id == curId }) curId else lastPageId
 204:         val wi = indexOfId(want)
 205:         page = if (wi < 0) 0 else wi
 206:         lastPageId = order[page].id
 207:         pageS.snapTo(page.toFloat())
 208:         layoutAll()
 209:         if (sourcesStarted) {
 210:             syncArcs()
 211:             for (w in next) startWin(w)
 212:         } else {
 213:             syncArcs()
 214:         }
 215:         syncShown()
 216:         invalidate()
 217:     }
 218: 
 219:     internal fun relayout() {
 220:         layoutAll()
 221:         invalidate()
 222:     }
 223: 
 224:     /** Animates to the window with this id; false if it is not enabled. */
 225:     internal fun jumpTo(id: String): Boolean {
 226:         val i = indexOfId(id)
 227:         if (i < 0) return false
 228:         userPaged = true
 229:         goToPage(i, 0f)
 230:         return true
 231:     }
 232:     private val path = Path()
 233:     private val regionPath = Path()
 234:     private val arcRect = RectF()
 235:     private val region = Region()
 236:     private val clipRegion = Region()
 237:     private val hitRegion = Region()
 238: 
 239:     // Looks (from Prefs, see applyLooksNow). Shaders are immutable at radius GR; the shape path is drawn in
 240:     // gradient-unit space under a canvas scale (never mutate a shader's local matrix per frame).
 241:     internal var accent = C_RED
 242:     private var gradientPct = 35
 243:     private var rimOn = true
 244:     private var glowOn = true
 245:     internal var bandsOn = false
 246:     private var hapticsOn = true
 247:     private var stiffScale = 1f
 248:     private var openDamp = 0.8f
 249:     private var idleStyleId = 1
 250:     private var vizOn = true
 251:     private var liveOn = true
 252:     private var beatOn = true
 253:     private var musicPlaying = false
 254:     private var beat = 0f
 255:     private val exclRect = Rect()
 256:     private val exclList = ArrayList<Rect>(1).apply { add(exclRect) }
 257:     private var exclSet = false
 258:     private var shapeShader: Shader = buildShapeShader()
 259:     private var glowShader: Shader = buildGlowShader()
 260: 
 261:     private fun mixColor(a: Int, b: Int, t: Float): Int {
 262:         val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t + 0.5f).toInt()
 263:         val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t + 0.5f).toInt()
 264:         val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t + 0.5f).toInt()
 265:         return Color.rgb(r, g, bl)
 266:     }
 267: 
 268:     private fun buildShapeShader(): Shader {
 269:         val warm = mixColor(0xFF2E1B1A.toInt(), accent or (0xFF shl 24), 0.25f)
 270:         val core = mixColor(Color.BLACK, warm, gradientPct / 100f)
 271:         return android.graphics.RadialGradient(
 272:             0f, 0f, GR,
 273:             intArrayOf(core, mixColor(Color.BLACK, core, 0.55f), mixColor(Color.BLACK, core, 0.24f), Color.BLACK),
 274:             floatArrayOf(0f, 0.38f, 0.72f, 1f), Shader.TileMode.CLAMP
 275:         )
 276:     }
 277: 
 278:     private fun buildGlowShader(): Shader {
 279:         val rgb = accent and 0xFFFFFF
 280:         return android.graphics.RadialGradient(
 281:             0f, 0f, GR,
 282:             intArrayOf((0x88 shl 24) or rgb, (0x30 shl 24) or rgb, rgb),
 283:             floatArrayOf(0f, 0.38f, 0.85f), Shader.TileMode.CLAMP
 284:         )
 285:     }
 286:     private val unitPath = Path()
 287:     private var gradScale = 1f
 288:     private val gradMatrix = Matrix()
 289:     private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
 290:         style = Paint.Style.FILL
 291:         shader = shapeShader
 292:     }
 293:     private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
 294:         style = Paint.Style.FILL
 295:         shader = glowShader
 296:     }
 297:     private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
 298:         style = Paint.Style.STROKE
 299:         strokeWidth = dp(1f)
 300:         strokeCap = Paint.Cap.ROUND
 301:     }
 302:     internal val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
 303:         style = Paint.Style.STROKE
 304:         strokeWidth = dp(1.5f)
 305:         color = C_RED
 306:     }
 307:     internal val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
 308:         style = Paint.Style.FILL_AND_STROKE
 309:         strokeJoin = Paint.Join.ROUND
 310:         strokeWidth = dp(2f)
 311:         color = Color.WHITE
 312:     }
 313:     internal val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
 314:         style = Paint.Style.STROKE
 315:         strokeCap = Paint.Cap.ROUND
 316:     }
 317:     internal val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
 318:         style = Paint.Style.STROKE
 319:         strokeCap = Paint.Cap.ROUND
 320:     }
 321:     private val rimPath = Path()
 322:     private var glow = 0f
 323:     private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
 324:     private val liveTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
 325:         color = Color.WHITE
 326:         textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
 327:         typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
 328:     }
 329:     private val whiteIcons = SparseArray<Drawable>()
 330:     private val blackIcons = SparseArray<Drawable>()
 331:     private var resourcesReady = false
 332:     private var lpX = 0f
 333:     private var lpY = 0f
 334:     private val sb = StringBuilder(8)
 335: 
 336:     internal val fillP: Paint get() = fill
 337:     internal val ovalScratch = RectF()
 338:     private val xOval = RectF()
 339:     private var xCx = 0f
 340:     private var xCy = 0f
 341:     private var xRad = 0f
 342:     private var xHit = 0f
 343:     private var lastFl = -1f
 344: 
 345:     // ---------------------------------------------------------------- touch state
 346:     private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
 347:     private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
 348:     private var vt: VelocityTracker? = null
 349:     private var gestureActive = false
 350:     private var startMode = MODE_IDLE
 351:     private var dragMode = 0 // 0 none, 1 expanding from idle, 2 shrinking (close), 3 paging
 352:     private var downRawX = 0f
 353:     private var downRawY = 0f
 354:     private var baseW = 0f
 355:     private var baseH = 0f
 356:     private var moved = false
 357:     /** Pressed item: local id inside [pressedWin], or ID_X (pressedWin == null) for the close button. */
 358:     internal var pressed = -1
 359:         private set
 360:     internal var pressedWin: Win? = null
 361:         private set
 362:     private var hitWin: Win? = null
 363:     private var captureWin: Win? = null
 364:     private var longFired = false
 365: 
 366:     private fun setPressed(w: Win?, item: Int) {
 367:         if (pressed == item && pressedWin === w) return
 368:         pressedWin?.dirty = true
 369:         w?.dirty = true
 370:         pressed = item
 371:         pressedWin = w
 372:     }
 373: 
 374:     private fun clearPressed() = setPressed(null, -1)
 375: 
 376:     private val longPressRunnable = Runnable { onLongPress() }
 377: 
 378:     // ---------------------------------------------------------------- live activities
 379:     private var liveCur: LiveActivity? = null
 380:     private var liveSec: LiveActivity? = null
 381:     private var liveTitleRaw: String? = null
 382:     private var liveTitleShown: String = ""
 383:     private var liveTitleOff = 0f
 384:     private var liveTitleLen = 0f
 385:     private var liveCallLen = 0f
 386:     private var liveTitleKey: String? = null
 387:     private var liveTitleCall = false
 388:     private var liveTotalMs = 0L
 389:     private val liveTitlePath = Path()
 390:     private val liveTitlePathCall = Path()
 391:     private val liveOval = RectF()
 392:     private val liveIconRect = Rect()
 393:     private val liveAnsRect = Rect()
 394:     private val liveDecRect = Rect()
 395:     private var liveAnsX = 0f
 396:     private var liveAnsY = 0f
 397:     private var liveDecX = 0f
 398:     private var liveDecY = 0f
 399:     private var liveDotR = 0f
 400:     private var lastLiveTrack: String? = null
 401:     private val transientRunnables = HashMap<String, Runnable>()
 402: 
 403:     private val liveSize: Float get() = idle * LIVE_SCALE
 404:     private val liveWanted: Boolean get() = liveOn && liveCur != null && screenOn && !hidden
 405:     private fun closedSize(): Float = if (liveWanted) liveSize else idle
 406: 
 407:     private val liveTickRunnable = object : Runnable {
 408:         override fun run() {
 409:             val a = liveCur
 410:             if (!liveWanted || expandedTarget || a == null) return
 411:             if (a.chronoBase != 0L) rebuildLiveTitle()
 412:             invalidateBean()
 413:             val next = if (a.progress == -2f) 40L else if (a.chronoBase != 0L) 1000L else 0L
 414:             if (next > 0L) postDelayed(this, next)
 415:         }
 416:     }
 417:     // ---------------------------------------------------------------- frame loop
 418:     private var framePosted = false
 419:     private var lastFrameNanos = 0L
 420:     private val frameCb = Choreographer.FrameCallback { t -> doFrame(t) }
 421: 
 422:     // ---------------------------------------------------------------- idle designs / visualizer
 423:     var holdOpen = false // set by the service while the settings screen previews the open size
 424:     private var screenOn = false
 425:     private var clockStr = ""
 426:     private var battFrac = 1f
 427:     private var charging = false
 428:     private var clockReg = false
 429:     private var powerReg = false
 430:     private var mediaWatch: IdleMediaWatch? = null
 431:     private var idlePlaying = false
 432:     private val visualizer = IdleVisualizer(ctx)
 433:     private var vizRunning = false
 434:     private var vizPosted = false
 435:     private var lastVizNanos = 0L
 436:     private var beanPx = 0
 437:     private var looksPosted = false
 438:     private val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
 439:     private val timeFilter = IntentFilter().apply {
 440:         addAction(Intent.ACTION_TIME_TICK)
 441:         addAction(Intent.ACTION_TIME_CHANGED)
 442:         addAction(Intent.ACTION_TIMEZONE_CHANGED)
 443:     }
 444:     private val powerFilter = IntentFilter().apply {
 445:         addAction(Intent.ACTION_POWER_CONNECTED)
 446:         addAction(Intent.ACTION_POWER_DISCONNECTED)
 447:     }
 448:     private val timeReceiver = object : BroadcastReceiver() {
 449:         override fun onReceive(context: Context?, intent: Intent?) {
 450:             updateClock()
 451:             invalidateBean()
 452:         }
 453:     }
 454:     private val powerReceiver = object : BroadcastReceiver() {
 455:         override fun onReceive(context: Context?, intent: Intent?) {
 456:             readBattery()
 457:             invalidateBean()
 458:         }
 459:     }
 460:     private val vizCb = Choreographer.FrameCallback { t -> vizFrame(t) }
 461:     private val looksRunnable = Runnable {
 462:         looksPosted = false
 463:         applyLooksNow()
 464:     }
 465:     private val idleRect = RectF()
 466:     private val boltPath = Path()
 467:     private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
 468:         style = Paint.Style.STROKE
 469:         strokeCap = Paint.Cap.ROUND
 470:     }
 471:     private val clockPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
 472:         color = Color.WHITE
 473:         textAlign = Paint.Align.CENTER
 474:         typeface = Typeface.DEFAULT_BOLD
 475:     }
 476: 
 477:     // ================================================================ public API
 478: 
 479:     fun expand() = startExpand(false, 0f, 0f)
 480: 
 481:     fun collapse() = startCollapse(false, 0f, 0f)
 482: 
 483:     private fun cancelCapture() {
 484:         val cw = captureWin ?: return
 485:         captureWin = null
 486:         cw.dragEnd(true)
 487:     }
 488: 
 489:     fun collapseImmediately() {
 490:         if (framePosted) {
 491:             Choreographer.getInstance().removeFrameCallback(frameCb)
 492:             framePosted = false
 493:         }
 494:         lastFrameNanos = 0L
 495:         cancelItemLongPress()
 496:         removeCallbacks(liveTickRunnable)
 497:         releaseTracker()
 498:         gestureActive = false
 499:         dragMode = 0
 500:         cancelCapture()
 501:         clearPressed()
 502:         expandedTarget = false
 503:         collapsing = true
 504:         settledExpanded = false
 505:         syncShown()
 506:         openPicked = false
 507:         val cs = closedSize()
 508:         wS.snapTo(cs)
 509:         hS.snapTo(cs)
 510:         pageS.snapTo(page.toFloat())
 511:         press.snapTo(1f)
 512:         progress = 0f
 513:         contentAlpha = 0f
 514:         stopSources()
 515:         regionExpanded = false
 516:         regionClosed = cs
 517:         publishRegion()
 518:         host?.onSettled(false)
 519:         updateViz()
 520:         syncLiveTick()
 521:         invalidate()
 522:     }
 523: 
 524:     /**
 525:      * Applies new idle size / open radius (dp) from the settings app. Keeps the current state: an open
 526:      * widget stays open at the new size (springs snapped), a closed one stays closed.
 527:      */
 528:     fun applySizes(idleDp: Float, openDp: Float) {
 529:         if (gestureActive || dragMode != 0) {
 530:             cancelItemLongPress()
 531:             releaseTracker()
 532:             gestureActive = false
 533:             dragMode = 0
 534:             cancelCapture()
 535:             clearPressed()
 536:         }
 537:         idle = dp(idleDp.coerceIn(32f, 80f))
 538:         rad = dp(openDp.coerceIn(170f, 300f))
 539:         layoutAll()
 540:         val cs = closedSize()
 541:         if (expandedTarget) {
 542:             wS.snapTo(rad)
 543:             hS.snapTo(rad)
 544:         } else {
 545:             wS.snapTo(cs)
 546:             hS.snapTo(cs)
 547:             regionClosed = cs
 548:         }
 549:         wS.target = if (expandedTarget) rad else cs
 550:         hS.target = wS.target
 551:         updateDerived(0f)
 552:         publishRegion()
 553:         updateViz()
 554:         invalidate()
 555:     }
 556: 
 557:     /** Re-reads the edge flare radius (Prefs.KEY_FLARE); the service then resizes the window. */
 558:     fun applyFlare() {
 559:         flarePx = dp(Prefs.flareDp(context))
 560:         lastDw = -1f
 561:         publishRegion()
 562:         invalidate()
 563:     }
 564: 
 565:     /** Re-reads the looks and feel prefs (coalesced to one pass per frame). */
 566:     fun applyLooks() {
 567:         if (looksPosted) return
 568:         looksPosted = true
 569:         postOnAnimation(looksRunnable)
 570:     }
 571: 
 572:     /** Re-reads the QUICK layout (QuickConfig.KEY changed). */
 573:     fun reloadQuick() {
 574:         (winMap["quick"] as? WinQuick)?.reload()
 575:     }
 576: 
 577:     fun setScreenOn(on: Boolean) {
 578:         if (screenOn == on) return
 579:         screenOn = on
 580:         syncIdleServices()
 581:         onLiveChanged()
 582:         syncShown()
 583:     }
 584: 
 585:     fun setTipRadius(px: Float) {
 586:         tipPx = px
 587:         publishRegion()
 588:         invalidate()
 589:     }
 590: 
 591:     fun setHidden(h: Boolean) {
 592:         if (hidden == h) return
 593:         hidden = h
 594:         if (h) {
 595:             collapseImmediately()
 596:             animate().alpha(0f).setDuration(150L).start()
 597:         } else {
 598:             animate().alpha(1f).setDuration(150L).start()
 599:         }
 600:         publishRegion()
 601:         updateViz()
 602:         onLiveChanged()
 603:     }
 604: 
 605:     fun currentRegion(): Region = region
 606: 
 607:     /** Stops listeners and callbacks; call before removing the view for good. */
 608:     fun release() {
 609:         if (framePosted) {
 610:             Choreographer.getInstance().removeFrameCallback(frameCb)
 611:             framePosted = false
 612:         }
 613:         cancelItemLongPress()
 614:         removeCallbacks(liveTickRunnable)
 615:         removeCallbacks(captureTimeout)
 616:         for (r in transientRunnables.values) removeCallbacks(r)
 617:         transientRunnables.clear()
 618:         releaseTracker()
 619:         expandedTarget = false
 620:         settledExpanded = false
 621:         syncShown()
 622:         stopSources()
 623:         removeCallbacks(looksRunnable)
 624:         screenOn = false
 625:         syncIdleServices()
 626:         mediaWatch?.stop()
 627:         mediaWatch = null
 628:         visualizer.stop()
 629:         LiveBus.onChange = null
 630:         service = null
 631:         (winMap["quick"] as? WinQuick)?.setService(null)
 632:     }
 633: 
 634:     // ================================================================ view plumbing
 635: 
 636:     override fun hasOverlappingRendering(): Boolean = false
 637: 
 638:     override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
 639:         publishRegion()
 640:     }
 641: 
 642:     override fun onAttachedToWindow() {
 643:         super.onAttachedToWindow()
 644:         LiveBus.onChange = { onLiveChanged() }
 645:         onLiveChanged()
 646:     }
 647: 
 648:     override fun onDetachedFromWindow() {
 649:         super.onDetachedFromWindow()
 650:         if (framePosted) {
 651:             Choreographer.getInstance().removeFrameCallback(frameCb)
 652:             framePosted = false
 653:         }
 654:         removeCallbacks(liveTickRunnable)
 655:         removeCallbacks(longPressRunnable)
 656:         // the display lists die with the window: force a re-record when it comes back
 657:         for (w in order) w.dirty = true
 658:     }
 659: 
 660:     override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
 661:         super.onWindowFocusChanged(hasWindowFocus)
 662:         if (hasWindowFocus && captureWanted) {
 663:             captureWanted = false
 664:             removeCallbacks(captureTimeout)
 665:             try {
 666:                 ClipPaste.capture(context)
 667:             } catch (e: Throwable) {
 668:             }
 669:             host?.setWindowFocusable(false)
 670:         }
 671:     }
 672: 
 673:     // ---------------------------------------------------------------- clipboard capture / helpers for windows
 674:     private var captureWanted = false
 675:     private var captureLast = 0L
 676:     private val captureTimeout = Runnable {
 677:         if (captureWanted) {
 678:             captureWanted = false
 679:             host?.setWindowFocusable(false)
 680:         }
 681:     }
 682: 
 683:     /** Asks the service to make the window focusable for one focus change so the clipboard can be read. */
 684:     internal fun requestCapture() {
 685:         val now = SystemClock.uptimeMillis()
 686:         if (captureWanted || now - captureLast < 1500L) return
 687:         captureLast = now
 688:         captureWanted = true
 689:         host?.setWindowFocusable(true)
 690:         removeCallbacks(captureTimeout)
 691:         postDelayed(captureTimeout, 300L)
 692:     }
 693: 
 694:     internal fun showCopied() {
 695:         putTransient(
 696:             LiveActivity(
 697:                 "copied", LiveActivity.Kind.PROGRESS, "Copied", -1f, 0L, false,
 698:                 R.drawable.ic_clipboard, false, null, null, null
 699:             ), 1800L
 700:         )
 701:     }
 702: 
 703:     internal fun openClipboard() {
 704:         try {
 705:             val i = Intent(context, ClipboardActivity::class.java)
 706:             i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
 707:             context.startActivity(i)
 708:         } catch (e: Exception) {
 709:         }
 710:     }
 711:     // ================================================================ looks
 712: 
 713:     private fun applyLooksNow() {
 714:         val c = context
 715:         accent = Prefs.accent(c)
 716:         gradientPct = Prefs.prefs(c).getInt(Prefs.KEY_GRADIENT, 35).coerceIn(0, 100)
 717:         rimOn = Prefs.rim(c)
 718:         glowOn = Prefs.glow(c)
 719:         bandsOn = Prefs.bands(c)
 720:         hapticsOn = Prefs.haptics(c)
 721:         stiffScale = Prefs.stiffnessScale(c)
 722:         openDamp = Prefs.openDamping(c)
 723:         idleStyleId = when (Prefs.idleStyle(c)) {
 724:             "none" -> 0
 725:             "meter" -> 3
 726:             "pill", "slits", "grid" -> 2
 727:             else -> 1
 728:         } // LOOKS hook: replace with the new idle designs
 729:         vizOn = Prefs.visualizer(c)
 730:         liveOn = Prefs.live(c)
 731:         beatOn = Prefs.beat(c)
 732:         shapeShader = buildShapeShader()
 733:         glowShader = buildGlowShader()
 734:         shapePaint.shader = shapeShader
 735:         glowPaint.shader = glowShader
 736:         ringPaint.color = accent
 737:         press.stiffness = 700f * stiffScale
 738:         for (w in order) w.dirty = true
 739:         syncIdleServices()
 740:         onLiveChanged()
 741:         invalidate()
 742:     }
 743: 
 744:     internal fun haptic(kind: Int) {
 745:         if (hapticsOn) performHapticFeedback(kind)
 746:     }
 747: 
 748:     // ================================================================ idle services
 749: 
 750:     private fun invalidateBean() {
 751:         invalidate(width - beanPx, height - beanPx, width, height)
 752:     }
 753: 
 754:     private fun syncIdleServices() {
 755:         val wantClock = screenOn && idleStyleId == 4
 756:         if (wantClock != clockReg) {
 757:             clockReg = wantClock
 758:             try {
 759:                 if (wantClock) context.registerReceiver(timeReceiver, timeFilter)
 760:                 else context.unregisterReceiver(timeReceiver)
 761:             } catch (e: Throwable) {
 762:             }
 763:         }
 764:         if (wantClock) updateClock()
 765:         val wantPower = screenOn && idleStyleId == 3
 766:         if (wantPower != powerReg) {
 767:             powerReg = wantPower
 768:             try {
 769:                 if (wantPower) context.registerReceiver(powerReceiver, powerFilter)
 770:                 else context.unregisterReceiver(powerReceiver)
 771:             } catch (e: Throwable) {
 772:             }
 773:         }
 774:         if (wantPower) readBattery()
 775:         // The light media watch serves the visualizer and the track-change live activity.
 776:         if (screenOn && (vizOn || liveOn || beatOn)) {
 777:             var w = mediaWatch
 778:             if (w == null) {
 779:                 w = IdleMediaWatch(context) { p ->
 780:                     musicPlaying = p
 781:                     idlePlaying = p && vizOn
 782:                     updateViz()
 783:                     invalidateBean()
 784:                 }
 785:                 w.onTrack = { t -> onTrackChanged(t) }
 786:                 mediaWatch = w
 787:             }
 788:             w.start()
 789:             musicPlaying = w.playing
 790:             idlePlaying = w.playing && vizOn
 791:         } else {
 792:             mediaWatch?.stop()
 793:             musicPlaying = false
 794:             idlePlaying = false
 795:         }
 796:         updateViz()
 797:         invalidateBean()
 798:     }
 799: 
 800:     private fun updateClock() {
 801:         val cal = Calendar.getInstance()
 802:         var h = cal.get(Calendar.HOUR_OF_DAY)
 803:         val m = cal.get(Calendar.MINUTE)
 804:         val h24 = DateFormat.is24HourFormat(context)
 805:         if (!h24) {
 806:             h %= 12
 807:             if (h == 0) h = 12
 808:         }
 809:         clockStr = (if (h < 10 && h24) "0" else "") + h + ":" + (if (m < 10) "0" else "") + m
 810:     }
 811: 
 812:     private fun readBattery() {
 813:         val i = try {
 814:             context.registerReceiver(null, batteryFilter)
 815:         } catch (e: Throwable) {
 816:             null
 817:         } ?: return
 818:         val lv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
 819:         val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
 820:         battFrac = if (lv >= 0 && sc > 0) (lv.toFloat() / sc).coerceIn(0f, 1f) else 1f
 821:         charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
 822:     }
 823: 
 824:     /** Visualizer frames run only while playing && closed && screen on && enabled && no live activity. */
 825:     private fun updateViz() {
 826:         val barsWant = idlePlaying && !expandedTarget && !gestureActive && dragMode == 0 && !liveWanted
 827:         val beatWant = beatOn && musicPlaying
 828:         val want = screenOn && !hidden && (barsWant || beatWant)
 829:         if (want == vizRunning) return
 830:         vizRunning = want
 831:         if (want) {
 832:             visualizer.start()
 833:             lastVizNanos = 0L
 834:             if (!vizPosted) {
 835:                 vizPosted = true
 836:                 Choreographer.getInstance().postFrameCallbackDelayed(vizCb, 25L)
 837:             }
 838:         } else {
 839:             visualizer.stop()
 840:             beat = 0f
 841:             if (vizPosted) {
 842:                 Choreographer.getInstance().removeFrameCallback(vizCb)
 843:                 vizPosted = false
 844:             }
 845:         }
 846:     }
 847: 
 848:     private fun vizFrame(t: Long) {
 849:         vizPosted = false
 850:         if (!vizRunning) return
 851:         val dtN = t - lastVizNanos
 852:         if (lastVizNanos == 0L || dtN >= 31_000_000L) {
 853:             val dt = if (lastVizNanos == 0L) 0.033f else (dtN * 1e-9f).coerceAtMost(0.1f)
 854:             lastVizNanos = t
 855:             visualizer.tick(dt, t / 1_000_000L)
 856:             if (beatOn) beat += (visualizer.levels[0].coerceIn(0f, 1f) - beat) * 0.4f
 857:             if (expandedTarget || dragMode != 0) invalidate() else invalidateBean()
 858:         }
 859:         vizPosted = true
 860:         Choreographer.getInstance().postFrameCallbackDelayed(vizCb, 25L)
 861:     }
 862: 
 863:     private fun drawIdle(c: Canvas, ia: Float) {
 864:         val sc = idle / dp(48f)
 865:         c.save()
 866:         c.translate(width.toFloat(), height.toFloat())
 867:         val ps = press.value
 868:         c.scale(ps, ps)
 869:         val cen = -0.4244f * idle
 870:         if (vizOn && idlePlaying) {
 871:             val bw = dp(2.5f) * sc
 872:             val pitch = dp(4.5f) * sc
 873:             val minH = dp(3f) * sc
 874:             val maxH = 0.34f * idle
 875:             val lv = visualizer.levels
 876:             for (i in 0 until 4) {
 877:                 val h = minH + (maxH - minH) * lv[i]
 878:                 val x = cen + (i - 1.5f) * pitch
 879:                 fill.color = if (i == 2) accent else Color.WHITE
 880:                 fill.alpha = (255f * 0.85f * ia + 0.5f).toInt()
 881:                 c.drawRoundRect(x - bw / 2f, cen - h / 2f, x + bw / 2f, cen + h / 2f, bw / 2f, bw / 2f, fill)
 882:             }
 883:         } else when (idleStyleId) {
 884:             1 -> {
 885:                 fill.color = accent
 886:                 fill.alpha = (255f * ia + 0.5f).toInt()
 887:                 c.drawCircle(-0.30f * idle, -0.30f * idle, dp(2f) * sc, fill)
 888:             }
 889:             2 -> {
 890:                 val r = 0.70f * idle
 891:                 idleRect.set(-r, -r, r, r)
 892:                 idlePaint.strokeWidth = dp(1.4f) * sc
 893:                 idlePaint.color = C_CREAM
 894:                 idlePaint.alpha = (255f * 0.85f * ia + 0.5f).toInt()
 895:                 c.drawArc(idleRect, 180f + 12f, 66f, false, idlePaint)
 896:             }
 897:             3 -> {
 898:                 val r = 0.66f * idle
 899:                 idleRect.set(-r, -r, r, r)
 900:                 idlePaint.strokeWidth = dp(2.6f) * sc
 901:                 idlePaint.color = Color.WHITE
 902:                 idlePaint.alpha = (255f * 0.22f * ia + 0.5f).toInt()
 903:                 c.drawArc(idleRect, 180f + 10f, 70f, false, idlePaint)
 904:                 if (battFrac > 0.01f) {
 905:                     idlePaint.color = if (charging) Color.WHITE else accent
 906:                     idlePaint.alpha = (255f * 0.9f * ia + 0.5f).toInt()
 907:                     c.drawArc(idleRect, 180f + 10f, 70f * battFrac, false, idlePaint)
 908:                 }
 909:                 if (charging) {
 910:                     val u = 0.07f * idle
 911:                     boltPath.rewind()
 912:                     boltPath.moveTo(cen + 0.3f * u, cen - u)
 913:                     boltPath.lineTo(cen - 0.5f * u, cen + 0.1f * u)
 914:                     boltPath.lineTo(cen, cen + 0.1f * u)
 915:                     boltPath.lineTo(cen - 0.3f * u, cen + u)
 916:                     boltPath.lineTo(cen + 0.5f * u, cen - 0.1f * u)
 917:                     boltPath.lineTo(cen, cen - 0.1f * u)
 918:                     boltPath.close()
 919:                     fill.color = Color.WHITE
 920:                     fill.alpha = (255f * 0.85f * ia + 0.5f).toInt()
 921:                     c.drawPath(boltPath, fill)
 922:                 }
 923:             }
 924:             4 -> if (clockStr.isNotEmpty()) {
 925:                 clockPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics) * sc
 926:                 clockPaint.alpha = (255f * 0.85f * ia + 0.5f).toInt()
 927:                 c.drawText(clockStr, cen, cen - (clockPaint.ascent() + clockPaint.descent()) / 2f, clockPaint)
 928:             }
 929:         }
 930:         c.restore()
 931:     }
 932:     // ================================================================ live activities
 933: 
 934:     private fun onTrackChanged(title: String?) {
 935:         if (!liveOn || expandedTarget || !screenOn || hidden || title.isNullOrBlank()) return
 936:         if (title == lastLiveTrack) return
 937:         lastLiveTrack = title
 938:         putTransient(
 939:             LiveActivity(
 940:                 "media", LiveActivity.Kind.MEDIA, title.take(40), -1f, 0L, false,
 941:                 R.drawable.ic_music, false, null, null, null
 942:             ), 3000L
 943:         )
 944:     }
 945: 
 946:     /** Posts a transient activity and removes it after [ms]. */
 947:     private fun putTransient(a: LiveActivity, ms: Long) {
 948:         val old = transientRunnables.remove(a.key)
 949:         if (old != null) removeCallbacks(old)
 950:         val key = a.key
 951:         val r = Runnable {
 952:             transientRunnables.remove(key)
 953:             LiveBus.remove(key)
 954:         }
 955:         transientRunnables[key] = r
 956:         postDelayed(r, ms)
 957:         LiveBus.put(a)
 958:     }
 959: 
 960:     private fun scheduleTransient(a: LiveActivity?) {
 961:         if (a == null || a.ongoing || transientRunnables.containsKey(a.key)) return
 962:         val key = a.key
 963:         val ms = when (a.kind) {
 964:             LiveActivity.Kind.MEDIA -> 3000L
 965:             LiveActivity.Kind.CHARGING -> 3500L
 966:             else -> 4000L
 967:         }
 968:         val r = Runnable {
 969:             transientRunnables.remove(key)
 970:             LiveBus.remove(key)
 971:         }
 972:         transientRunnables[key] = r
 973:         postDelayed(r, ms)
 974:     }
 975: 
 976:     private fun onLiveChanged() {
 977:         val top = if (liveOn) LiveBus.top() else null
 978:         val sec = if (liveOn) LiveBus.second() else null
 979:         liveCur = top
 980:         liveSec = sec
 981:         scheduleTransient(top)
 982:         scheduleTransient(sec)
 983:         if (top?.key != liveTitleKey) {
 984:             liveTitleKey = top?.key
 985:             liveTitleRaw = null
 986:             liveTotalMs = 0L
 987:             if (top != null && top.countDown && top.chronoBase != 0L) {
 988:                 liveTotalMs = max(1000L, top.chronoBase - System.currentTimeMillis())
 989:             }
 990:         }
 991:         rebuildLiveTitle()
 992:         syncClosedSize()
 993:         updateViz()
 994:         syncLiveTick()
 995:         invalidateBean()
 996:     }
 997: 
 998:     /** Morphs the closed bean between idle and live size. */
 999:     private fun syncClosedSize() {
1000:         if (expandedTarget || gestureActive || dragMode != 0 || hidden) return
1001:         val tgt = closedSize()
1002:         if (wS.target == tgt && hS.target == tgt) return
1003:         if (tgt > regionClosed) {
1004:             regionClosed = tgt
1005:             publishRegion()
1006:         }
1007:         collapsing = true
1008:         wS.stiffness = 420f * stiffScale; wS.dampingRatio = openDamp
1009:         hS.stiffness = 300f * stiffScale; hS.dampingRatio = min(0.99f, openDamp + 0.04f)
1010:         wS.target = tgt
1011:         hS.target = tgt
1012:         requestFrames()
1013:     }
1014: 
1015:     private fun syncLiveTick() {
1016:         removeCallbacks(liveTickRunnable)
1017:         val a = liveCur ?: return
1018:         if (!liveWanted || expandedTarget) return
1019:         if (a.progress == -2f || a.chronoBase != 0L) postDelayed(liveTickRunnable, 40L)
1020:     }
1021: 
1022:     private fun fmtChrono(ms: Long): String {
1023:         val t = if (ms < 0L) 0L else ms / 1000L
1024:         sb.setLength(0)
1025:         val h = t / 3600L
1026:         val m = (t % 3600L) / 60L
1027:         val s = t % 60L
1028:         if (h > 0L) {
1029:             sb.append(h).append(':')
1030:             if (m < 10L) sb.append('0')
1031:         }
1032:         sb.append(m).append(':')
1033:         if (s < 10L) sb.append('0')
1034:         sb.append(s)
1035:         return sb.toString()
1036:     }
1037: 
1038:     private fun rebuildLiveTitle() {
1039:         val a = liveCur ?: return
1040:         val call = a.kind == LiveActivity.Kind.CALL
1041:         val raw = if (a.chronoBase != 0L) {
1042:             val now = System.currentTimeMillis()
1043:             fmtChrono(if (a.countDown) a.chronoBase - now else now - a.chronoBase)
1044:         } else {
1045:             a.title
1046:         }
1047:         if (raw == liveTitleRaw && call == liveTitleCall) return
1048:         liveTitleRaw = raw
1049:         liveTitleCall = call
1050:         liveTitleShown = TextUtils.ellipsize(
1051:             raw, liveTitlePaint, if (call) liveCallLen else liveTitleLen, TextUtils.TruncateAt.END
1052:         ).toString()
1053:         val avail = if (call) liveCallLen else liveTitleLen
1054:         liveTitleOff = (avail - liveTitlePaint.measureText(liveTitleShown)) / 2f
1055:     }
1056: 
1057:     private fun drawLive(c: Canvas) {
1058:         val a = liveCur ?: return
1059:         val s = liveSize
1060:         val w = min(wS.value, hS.value)
1061:         val f = ((w - idle) / max(s - idle, 1f)).coerceIn(0f, 1f)
1062:         val la = f * f * (3f - 2f * f) * (1f - progress * 4f).coerceIn(0f, 1f)
1063:         if (la <= 0.01f) return
1064:         val ai = (255f * la + 0.5f).toInt()
1065:         val call = a.kind == LiveActivity.Kind.CALL
1066:         val tint = if (call) C_GREEN else accent
1067:         c.save()
1068:         c.translate(width.toFloat(), height.toFloat())
1069:         val ps = press.value * (w / s).coerceIn(0.5f, 1.15f)
1070:         c.scale(ps, ps)
1071:         // progress arc along the curved rim
1072:         val r = s - dp(5f)
1073:         liveOval.set(-r, -r, r, r)
1074:         arcPaint.strokeWidth = dp(3f)
1075:         arcPaint.color = Color.WHITE
1076:         arcPaint.alpha = (255f * 0.14f * la + 0.5f).toInt()
1077:         c.drawArc(liveOval, 180f + SL_A0, SL_A1 - SL_A0, false, arcPaint)
1078:         arcPaint.color = tint
1079:         arcPaint.alpha = ai
1080:         if (a.progress == -2f) {
1081:             val head = ((SystemClock.uptimeMillis() % 1400L) / 1400f) * (SL_A1 - SL_A0 + 24f)
1082:             val st = max(0f, head - 24f)
1083:             val en = min(SL_A1 - SL_A0, head)
1084:             if (en > st) c.drawArc(liveOval, 180f + SL_A0 + st, en - st, false, arcPaint)
1085:         } else {
1086:             val fr = when {
1087:                 a.progress >= 0f -> a.progress
1088:                 a.chronoBase != 0L && a.countDown && liveTotalMs > 0L ->
1089:                     (a.chronoBase - System.currentTimeMillis()).toFloat() / liveTotalMs
1090:                 else -> -1f
1091:             }
1092:             if (fr > 0.002f) c.drawArc(liveOval, 180f + SL_A0, (SL_A1 - SL_A0) * fr.coerceAtMost(1f), false, arcPaint)
1093:         }
1094:         // icon at the centroid
1095:         if (a.iconRes != 0) {
1096:             val ic = icon(a.iconRes, false)
1097:             ic.bounds = liveIconRect
1098:             ic.alpha = ai
1099:             ic.draw(c)
1100:         }
1101:         // short title along the rim
1102:         liveTitlePaint.alpha = ai
1103:         if (liveTitleShown.isNotEmpty()) {
1104:             c.drawTextOnPath(
1105:                 liveTitleShown, if (call) liveTitlePathCall else liveTitlePath,
1106:                 liveTitleOff, 0f, liveTitlePaint
1107:             )
1108:         }
1109:         if (call) {
1110:             if (a.answer != null) {
1111:                 fill.color = C_GREEN
1112:                 fill.alpha = ai
1113:                 c.drawCircle(liveAnsX, liveAnsY, liveDotR, fill)
1114:                 val ic = icon(R.drawable.ic_phone, true)
1115:                 ic.bounds = liveAnsRect
1116:                 ic.alpha = ai
1117:                 ic.draw(c)
1118:             }
1119:             if (a.decline != null) {
1120:                 fill.color = C_DECLINE
1121:                 fill.alpha = ai
1122:                 c.drawCircle(liveDecX, liveDecY, liveDotR, fill)
1123:                 val ic = icon(R.drawable.ic_phone_off, true)
1124:                 ic.bounds = liveDecRect
1125:                 ic.alpha = ai
1126:                 ic.draw(c)
1127:             }
1128:         }
1129:         if (liveSec != null) {
1130:             val ang = Math.toRadians(88.0)
1131:             fill.color = Color.WHITE
1132:             fill.alpha = (255f * 0.7f * la + 0.5f).toInt()
1133:             c.drawCircle(-r * cos(ang).toFloat(), -r * sin(ang).toFloat(), dp(2.2f), fill)
1134:         }
1135:         c.restore()
1136:     }
1137: 
1138:     /** 1 = answer dot, 2 = decline dot, 0 = none (view coordinates). */
1139:     private fun liveDotHit(x: Float, y: Float): Int {
1140:         val a = liveCur ?: return 0
1141:         if (a.kind != LiveActivity.Kind.CALL) return 0
1142:         val px = x - width
1143:         val py = y - height
1144:         val hr = dp(18f)
1145:         if (a.answer != null) {
1146:             val dx = px - liveAnsX
1147:             val dy = py - liveAnsY
1148:             if (dx * dx + dy * dy <= hr * hr) return 1
1149:         }
1150:         if (a.decline != null) {
1151:             val dx = px - liveDecX
1152:             val dy = py - liveDecY
1153:             if (dx * dx + dy * dy <= hr * hr) return 2
1154:         }
1155:         return 0
1156:     }
1157: 
1158:     private fun sendPi(pi: PendingIntent?) {
1159:         if (pi == null) return
1160:         try {
1161:             pi.send()
1162:         } catch (e: Exception) {
1163:         }
1164:     }
1165: 
1166:     private fun onBeanTap(x: Float, y: Float) {
1167:         val a = liveCur
1168:         if (liveWanted && a != null) {
1169:             when (liveDotHit(x, y)) {
1170:                 1 -> { haptic(HapticFeedbackConstants.VIRTUAL_KEY); sendPi(a.answer); return }
1171:                 2 -> { haptic(HapticFeedbackConstants.VIRTUAL_KEY); sendPi(a.decline); return }
1172:             }
1173:             if (a.kind == LiveActivity.Kind.CALL && a.contentIntent != null) {
1174:                 haptic(HapticFeedbackConstants.VIRTUAL_KEY)
1175:                 sendPi(a.contentIntent)
1176:                 return
1177:             }
1178:             if (a.kind == LiveActivity.Kind.MEDIA && contentAlpha < 0.2f) {
1179:                 val mi = indexOfId("media")
1180:                 if (mi >= 0) {
1181:                     openPicked = true
1182:                     userPaged = true
1183:                     page = mi
1184:                     lastPageId = "media"
1185:                     pageS.snapTo(mi.toFloat())
1186:                 }
1187:             }
1188:         }
1189:         startExpand(false, 0f, 0f)
1190:     }
1191: 
1192:     // ================================================================ geometry
1193: 
1194:     /** Edge fillet radius for a shape of the given size: scaled with the idle size, at most 40% of it. */
1195:     private fun flareFor(size: Float, p: Float): Float {
1196:         if (flarePx <= 0.5f) return 0f
1197:         val fIdle = min(flarePx * idle / dp(48f), 0.4f * idle)
1198:         val f = fIdle + (flarePx - fIdle) * p
1199:         return min(f, 0.4f * size)
1200:     }
1201: 
1202:     /**
1203:      * Shape path: the quarter-circle bean plus concave fillets ("ears") of radius [fl] where it meets the
1204:      * bottom edge (left end) and the right edge (top end).
1205:      */
1206:     private fun fillPath(p: Path, ox: Float, oy: Float, w: Float, h: Float, k: Float, tip: Float, fl: Float) {
1207:         p.rewind()
1208:         val right = ox + w
1209:         val bottom = oy + h
1210:         val f = if (fl > 0.5f) fl else 0f
1211:         if (f > 0f) {
1212:             p.moveTo(ox - f, bottom)
1213:             arcRect.set(ox - 2f * f, bottom - 2f * f, ox, bottom)
1214:             p.arcTo(arcRect, 90f, -90f)
1215:             p.lineTo(ox, bottom)
1216:         } else {
1217:             p.moveTo(ox, bottom)
1218:         }
1219:         p.cubicTo(ox, bottom - k * h, right - k * w, oy, right, oy)
1220:         if (f > 0f) {
1221:             p.lineTo(right - f, oy)
1222:             arcRect.set(right - 2f * f, oy - 2f * f, right, oy)
1223:             p.arcTo(arcRect, 90f, -90f)
1224:         }
1225:         val t = min(tip, min(w, h))
1226:         if (t > 0.5f) {
1227:             p.lineTo(right, bottom - t)
1228:             arcRect.set(right - 2f * t, bottom - 2f * t, right, bottom)
1229:             p.arcTo(arcRect, 0f, 90f)
1230:         } else {
1231:             p.lineTo(right, bottom)
1232:         }
1233:         p.lineTo(ox - f, bottom)
1234:         p.close()
1235:     }
1236: 
1237:     private fun layoutAll() {
1238:         nodeS = ceil(rad).toInt() + ceil(dp(3f)).toInt()
1239:         beanPx = ceil(idle * LIVE_SCALE).toInt() + ceil(dp(4f)).toInt()
1240:         for (w in order) w.dirty = true
1241:         val hw = min(dp(24.5f), 0.12f * rad - dp(1.5f)).coerceAtLeast(dp(12f))
1242:         val brad = min(dp(20f), hw - dp(2f))
1243: 
1244:         // close X: at the idle bean's centroid (quarter disc centroid = 4/(3 pi) * size).
1245:         // Visual 34 dp, 48 dp hit target.
1246:         val xc = min(0.4244f * idle, 0.16f * rad)
1247:         xCx = -xc
1248:         xCy = -xc
1249:         xRad = dp(17f)
1250:         xHit = dp(24f)
1251: 
1252:         for (w in order) {
1253:             w.bands.clear()
1254:             w.layout(hw, brad)
1255:         }
1256:         layoutLive()
1257:         liveTitleRaw = null
1258:         rebuildLiveTitle()
1259:     }
1260: 
1261:     private var nodeS = 0
1262: 
1263:     private fun arcPath(p: Path, r: Float, a0: Float, a1: Float) {
1264:         xOval.set(-r, -r, r, r)
1265:         p.rewind()
1266:         p.arcTo(xOval, 180f + a0, a1 - a0, true)
1267:     }
1268: 
1269:     private fun layoutLive() {
1270:         val s = liveSize
1271:         val cen = 0.4244f * s
1272:         val ih = dp(9f).toInt()
1273:         liveIconRect.set((-cen).toInt() - ih, (-cen).toInt() - ih, (-cen).toInt() + ih, (-cen).toInt() + ih)
1274:         val tr = s - dp(17f)
1275:         arcPath(liveTitlePath, tr, LV_A0, LV_A1)
1276:         liveTitleLen = tr * Math.toRadians((LV_A1 - LV_A0).toDouble()).toFloat()
1277:         // call title sits between the two dots
1278:         val call0 = 28f
1279:         arcPath(liveTitlePathCall, tr, call0, LV_A1 - (call0 - LV_A0))
1280:         liveCallLen = tr * Math.toRadians((LV_A1 - LV_A0 - 2f * (call0 - LV_A0)).toDouble()).toFloat()
1281:         liveDotR = dp(8f)
1282:         val dr = s - dp(16f)
1283:         val dA = Math.toRadians(15.0)
1284:         val aA = Math.toRadians(75.0)
1285:         liveDecX = -dr * cos(dA).toFloat()
1286:         liveDecY = -dr * sin(dA).toFloat()
1287:         liveAnsX = -dr * cos(aA).toFloat()
1288:         liveAnsY = -dr * sin(aA).toFloat()
1289:         val gh = dp(4.5f).toInt()
1290:         liveAnsRect.set(liveAnsX.toInt() - gh, liveAnsY.toInt() - gh, liveAnsX.toInt() + gh, liveAnsY.toInt() + gh)
1291:         liveDecRect.set(liveDecX.toInt() - gh, liveDecY.toInt() - gh, liveDecX.toInt() + gh, liveDecY.toInt() + gh)
1292:     }
1293: 
1294:     private fun ensureResources() {
1295:         if (resourcesReady) return
1296:         resourcesReady = true
1297:         val all = intArrayOf(
1298:             R.drawable.ic_play, R.drawable.ic_pause, R.drawable.ic_skip_back, R.drawable.ic_skip_forward,
1299:             R.drawable.ic_music, R.drawable.ic_clipboard, R.drawable.ic_phone, R.drawable.ic_phone_off,
1300:             R.drawable.ic_bell, R.drawable.ic_bolt
1301:         )
1302:         for (r in all) {
1303:             icon(r, false)
1304:             icon(r, true)
1305:         }
1306:     }
1307: 
1308:     internal fun icon(res: Int, black: Boolean): Drawable {
1309:         val cache = if (black) blackIcons else whiteIcons
1310:         var d = cache.get(res)
1311:         if (d == null) {
1312:             d = context.getDrawable(res)!!.mutate()
1313:             d.setTint(if (black) Color.BLACK else Color.WHITE)
1314:             cache.put(res, d)
1315:         }
1316:         return d
1317:     }
1318: 
1319:     internal fun warm(res: Int) {
1320:         if (res == 0) return
1321:         icon(res, false)
1322:         icon(res, true)
1323:     }
1324: 
1325:     private var lastDw = -1f
1326:     private var lastDh = -1f
1327:     private var lastW = -1
1328:     private var lastH = -1
1329:     private var lastTip = -1f
1330: 
1331:     // ================================================================ sources
1332: 
1333:     private fun ensureSources() {
1334:         if (sourcesStarted) return
1335:         sourcesStarted = true
1336:         syncArcs()
1337:         for (w in order) startWin(w)
1338:     }
1339: 
1340:     private val startSourcesRunnable = Runnable {
1341:         if (expandedTarget || dragMode != 0) {
1342:             ensureSources()
1343:             val mw = mediaWin
1344:             if (!userPaged && mw != null && mw.playing && page != mw.index && !gestureActive) goToPage(mw.index, 0f)
1345:             invalidate()
1346:         }
1347:     }
1348: 
1349:     private fun discardLayers() {
1350:         for (w in order) {
1351:             w.node?.discardDisplayList()
1352:             w.dirty = true
1353:         }
1354:     }
1355: 
1356:     private fun stopSources() {
1357:         discardLayers()
1358:         removeCallbacks(startSourcesRunnable)
1359:         if (!sourcesStarted) return
1360:         syncShown()
1361:         sourcesStarted = false
1362:         for (w in order) stopWin(w)
1363:         syncArcs()
1364:     }
1365: 
1366:     // ================================================================ region
1367: 
1368:     private fun publishRegion() {
1369:         val ww = width
1370:         val hh = height
1371:         if (hidden) {
1372:             region.setEmpty()
1373:         } else {
1374:             if (ww <= 0 || hh <= 0) return
1375:             val w: Float
1376:             val h: Float
1377:             val fl: Float
1378:             if (regionExpanded) {
1379:                 w = rad; h = rad
1380:                 fl = flareFor(rad, 1f)
1381:             } else {
1382:                 w = regionClosed; h = regionClosed
1383:                 fl = flareFor(regionClosed, 0f)
1384:             }
1385:             fillPath(regionPath, ww - w, hh - h, w, h, K, tipPx, fl)
1386:             clipRegion.set(0, 0, ww, hh)
1387:             region.setPath(regionPath, clipRegion)
1388:         }
1389:         host?.onRegionChanged(region)
1390:         updateExclusion()
1391:     }
1392: 
1393:     /** While open, keep the system Back gesture from stealing page swipes near the right screen edge. */
1394:     private fun updateExclusion() {
1395:         val want = regionExpanded && !hidden && width > 0 && height > 0
1396:         if (want) {
1397:             val s = ceil(rad + flareFor(rad, 1f)).toInt()
1398:             exclRect.set(max(0, width - s), max(0, height - s), width, height)
1399:             systemGestureExclusionRects = exclList
1400:             exclSet = true
1401:         } else if (exclSet) {
1402:             systemGestureExclusionRects = emptyList()
1403:             exclSet = false
1404:         }
1405:     }
1406: 
1407:     // ================================================================ paging
1408: 
1409:     /** Chooses the window to open on: media if playing, else the last used one. */
1410:     private fun pickPage() {
1411:         openPicked = true
1412:         if (contentAlpha > 0.2f) return // mid-collapse: keep what is on screen
1413:         userPaged = false
1414:         val mw = mediaWin
1415:         val li = indexOfId(lastPageId)
1416:         page = if (sourcesStarted && mw != null && mw.playing) mw.index else if (li >= 0) li else 0
1417:         pageS.snapTo(page.toFloat())
1418:     }
1419: 
1420:     private fun goToPage(p: Int, vel: Float) {
1421:         page = p.coerceIn(0, nWin - 1)
1422:         lastPageId = order[page].id
1423:         collapsing = false
1424:         settledExpanded = false
1425:         syncShown()
1426:         pageS.stiffness = 380f * stiffScale; pageS.dampingRatio = openDamp
1427:         pageS.target = page.toFloat()
1428:         pageS.velocity = vel
1429:         requestFrames()
1430:     }
1431: 
1432:     // ================================================================ animation
1433: 
1434:     private fun startExpand(setVel: Boolean, vw: Float, vh: Float) {
1435:         if (hidden) return
1436:         ensureResources()
1437:         val was = expandedTarget
1438:         expandedTarget = true
1439:         removeCallbacks(liveTickRunnable)
1440:         updateViz()
1441:         if (!was && idleStyleId == 3 && screenOn) readBattery()
1442:         collapsing = false
1443:         settledExpanded = false
1444:         syncShown()
1445:         if (!openPicked) pickPage()
1446:         // Binder-heavy listener setup is kept off the first animation frames.
1447:         removeCallbacks(startSourcesRunnable)
1448:         if (!sourcesStarted) postDelayed(startSourcesRunnable, 40L)
1449:         regionExpanded = true
1450:         publishRegion()
1451:         wS.stiffness = 420f * stiffScale; wS.dampingRatio = openDamp
1452:         hS.stiffness = 300f * stiffScale; hS.dampingRatio = min(0.99f, openDamp + 0.04f)
1453:         pageS.target = page.toFloat()
1454:         wS.target = rad
1455:         hS.target = rad
1456:         if (setVel) {
1457:             wS.velocity = vw
1458:             hS.velocity = vh
1459:         }
1460:         press.target = 1f
1461:         if (!was) haptic(HapticFeedbackConstants.VIRTUAL_KEY)
1462:         requestFrames()
1463:     }
1464: 
1465:     private fun startCollapse(setVel: Boolean, vw: Float, vh: Float) {
1466:         cancelItemLongPress()
1467:         clearPressed()
1468:         expandedTarget = false
1469:         settledExpanded = false
1470:         syncShown()
1471:         updateViz()
1472:         collapsing = true
1473:         openPicked = false
1474:         val cs = closedSize()
1475:         wS.stiffness = 520f * stiffScale; wS.dampingRatio = 0.92f
1476:         hS.stiffness = 600f * stiffScale; hS.dampingRatio = 0.92f
1477:         wS.target = cs
1478:         hS.target = cs
1479:         if (setVel) {
1480:             wS.velocity = vw
1481:             hS.velocity = vh
1482:         }
1483:         press.target = 1f
1484:         requestFrames()
1485:     }
1486: 
1487:     private fun requestFrames() {
1488:         if (framePosted) return
1489:         framePosted = true
1490:         Choreographer.getInstance().postFrameCallback(frameCb)
1491:     }
1492: 
1493:     private fun doFrame(frameTimeNanos: Long) {
1494:         framePosted = false
1495:         val dt = if (lastFrameNanos == 0L) {
1496:             1f / 120f
1497:         } else {
1498:             ((frameTimeNanos - lastFrameNanos) * 1e-9f).coerceIn(0f, 1f / 30f)
1499:         }
1500:         lastFrameNanos = frameTimeNanos
1501:         wS.step(dt)
1502:         hS.step(dt)
1503:         pageS.step(dt)
1504:         press.step(dt)
1505:         updateDerived(dt)
1506:         invalidate()
1507:         if (allSettled()) {
1508:             wS.snapTo(wS.target)
1509:             hS.snapTo(hS.target)
1510:             pageS.snapTo(pageS.target)
1511:             press.snapTo(press.target)
1512:             updateDerived(0f)
1513:             lastFrameNanos = 0L
1514:             if (!gestureActive) finishSettle()
1515:         } else {
1516:             requestFrames()
1517:         }
1518:     }
1519: 
1520:     private fun allSettled(): Boolean =
1521:         wS.isSettled(0.3f, 15f) && hS.isSettled(0.3f, 15f) &&
1522:             pageS.isSettled(0.002f, 0.05f) && press.isSettled(0.002f, 0.1f)
1523: 
1524:     private fun finishSettle() {
1525:         if (expandedTarget) {
1526:             regionExpanded = true
1527:             settledExpanded = true
1528:             publishRegion()
1529:             host?.onSettled(true)
1530:             syncShown()
1531:         } else {
1532:             regionExpanded = false
1533:             settledExpanded = false
1534:             syncShown()
1535:             stopSources()
1536:             regionClosed = closedSize()
1537:             publishRegion()
1538:             host?.onSettled(false)
1539:             if (idleStyleId == 3 && screenOn) readBattery()
1540:             updateViz()
1541:             syncLiveTick()
1542:             syncClosedSize()
1543:             invalidateBean()
1544:         }
1545:     }
1546: 
1547:     private fun updateDerived(dt: Float) {
1548:         val span = max(rad - idle, 1f)
1549:         val p = min((wS.value - idle) / span, (hS.value - idle) / span).coerceIn(0f, 1f)
1550:         progress = p
1551:         val t = ((p - 0.55f) / 0.40f).coerceIn(0f, 1f)
1552:         val sm = t * t * (3f - 2f * t)
1553:         contentAlpha = if (collapsing) {
1554:             min(sm, max(0f, contentAlpha - dt / 0.09f))
1555:         } else {
1556:             sm
1557:         }
1558:     }
1559: 
1560:     // ================================================================ draw
1561: 
1562:     private fun buildLivePath() {
1563:         val pulse = if (beatOn && vizRunning) 1f + beat * (if (expandedTarget) 0.015f else 0.06f) else 1f
1564:         val ps = press.value * pulse
1565:         val dw = wS.value * ps
1566:         val dh = hS.value * ps
1567:         val ox = width - dw
1568:         val oy = height - dh
1569:         val pressGlow = ((1f - press.value) / (1f - PRESS_SCALE)).coerceIn(0f, 1f)
1570:         glow = max(max(pressGlow, if (dragMode != 0 || captureWin != null) 0.85f else 0f), max(progress * 0.4f, if (beatOn && vizRunning) beat * 0.55f else 0f))
1571:         val fl = flareFor(min(dw, dh), progress)
1572:         // Static shape: keep the Path objects untouched so HWUI reuses their cached geometry.
1573:         if (dw == lastDw && dh == lastDh && width == lastW && height == lastH && tipPx == lastTip && fl == lastFl) return
1574:         lastDw = dw; lastDh = dh; lastW = width; lastH = height; lastTip = tipPx; lastFl = fl
1575:         fillPath(path, ox, oy, dw, dh, K, tipPx, fl)
1576:         rimPath.rewind()
1577:         rimPath.moveTo(ox, height.toFloat())
1578:         rimPath.cubicTo(ox, height - K * dh, width - K * dw, oy, width.toFloat(), oy)
1579:         val r = max(dw, dh) * 1.15f / GR
1580:         gradScale = r
1581:         gradMatrix.setTranslate(-width.toFloat(), -height.toFloat())
1582:         gradMatrix.postScale(1f / r, 1f / r)
1583:         path.transform(gradMatrix, unitPath)
1584:     }
1585: 
1586:     override fun onDraw(c: Canvas) {
1587:         buildLivePath()
1588:         val g = glow
1589:         drawShapeFill(c, g)
1590:         if (rimOn) {
1591:             val ra = RIM_REST_A + (RIM_HOT_A - RIM_REST_A) * g
1592:             rimPaint.color = (mixColor(Color.WHITE, accent, g) and 0xFFFFFF) or ((255f * ra + 0.5f).toInt() shl 24)
1593:             c.drawPath(rimPath, rimPaint)
1594:         }
1595:         val ia = 1f - progress * 3f
1596:         if (liveWanted && !expandedTarget) {
1597:             drawLive(c)
1598:         } else if (ia > 0.01f && !liveWanted) {
1599:             drawIdle(c, ia) // LOOKS hook: idle decoration (drawIdle)
1600:         }
1601:         val a = contentAlpha
1602:         if (a > 0.004f && resourcesReady) drawContent(c, a)
1603:     }
1604: 
1605:     /** LOOKS hook: the shape fill (gradient today; fills and beat reaction plug in here). */
1606:     private fun drawShapeFill(c: Canvas, g: Float) {
1607:         c.save()
1608:         c.translate(width.toFloat(), height.toFloat())
1609:         c.scale(gradScale, gradScale)
1610:         c.drawPath(unitPath, shapePaint)
1611:         if (glowOn && g > 0.01f) {
1612:             glowPaint.alpha = (255f * g + 0.5f).toInt()
1613:             c.drawPath(unitPath, glowPaint)
1614:         }
1615:         c.restore()
1616:     }
1617: 
1618:     private fun drawContent(c: Canvas, a: Float) {
1619:         if (!c.isHardwareAccelerated) {
1620:             drawContentDirect(c, a)
1621:             return
1622:         }
1623:         val off = (1f - a) * dp(16f)
1624:         // content grows with the melting shape, so no clip is needed
1625:         val cs = (min(wS.value, hS.value) / rad).coerceIn(0f, 1f)
1626:         val pos = pageS.value
1627:         val s = nodeS
1628:         val sf = s.toFloat()
1629:         val w = width
1630:         val h = height
1631:         val mw = mediaWin
1632:         if (mw != null && beatOn && vizRunning && beat > 0.03f && abs(pos - mw.index) < 0.6f) {
1633:             val fa = pageFade(pos - mw.index)
1634:             c.save()
1635:             c.translate(w + off, h + off)
1636:             c.scale(cs, cs)
1637:             c.rotate((mw.index - pos) * PAGE_DEG)
1638:             arcPaint.color = accent
1639:             arcPaint.strokeWidth = dp(5f) + beat * dp(5f)
1640:             arcPaint.alpha = (255f * 0.38f * beat * a * fa + 0.5f).toInt()
1641:             c.drawCircle(mw.artX, mw.artY, mw.artRad + dp(3f) + beat * dp(5f), arcPaint)
1642:             c.restore()
1643:         }
1644:         for (i in 0 until nWin) {
1645:             val win = order[i]
1646:             val d = i - pos
1647:             val fa = pageFade(d)
1648:             if (fa <= 0.004f) continue
1649:             var n = win.node
1650:             if (n == null) {
1651:                 n = RenderNode("edgeWin" + win.id)
1652:                 n.setUseCompositingLayer(true, null)
1653:                 n.setClipToBounds(true)
1654:                 win.node = n
1655:             }
1656:             n.setPosition(w - s, h - s, w, h)
1657:             // A node skipped while faded out loses its display list: re-record it when it comes back.
1658:             if (win.dirty || !n.hasDisplayList()) {
1659:                 val rc = n.beginRecording(s, s)
1660:                 try {
1661:                     rc.translate(sf, sf)
1662:                     drawWin(rc, win, 1f)
1663:                 } finally {
1664:                     n.endRecording()
1665:                 }
1666:                 win.dirty = false
1667:             }
1668:             n.setPivotX(sf)
1669:             n.setPivotY(sf)
1670:             n.setRotationZ(d * PAGE_DEG)
1671:             n.setAlpha(a * fa)
1672:             n.setScaleX(cs)
1673:             n.setScaleY(cs)
1674:             n.setTranslationX(off)
1675:             n.setTranslationY(off)
1676:             c.drawRenderNode(n)
1677:         }
1678:         c.save()
1679:         c.translate(w + off, h + off)
1680:         c.scale(cs, cs)
1681:         drawPageBars(c, a)
1682:         drawClose(c, a)
1683:         c.restore()
1684:     }
1685: 
1686:     /** Neighbour-window fade: exactly 0 once a page is settled (|d| >= 0.85), ~55% each mid-swipe. */
1687:     private fun pageFade(d: Float): Float {
1688:         val t = (1f - abs(d) / 0.85f).coerceIn(0f, 1f)
1689:         return t * t * (3f - 2f * t)
1690:     }
1691: 
1692:     private fun drawContentDirect(c: Canvas, a: Float) {
1693:         c.save()
1694:         c.clipPath(path)
1695:         val off = (1f - a) * dp(16f)
1696:         val s = 0.96f + 0.04f * a
1697:         c.translate(width + off, height + off)
1698:         c.scale(s, s)
1699:         val pos = pageS.value
1700:         for (i in 0 until nWin) {
1701:             val d = i - pos
1702:             val fa = pageFade(d)
1703:             if (fa <= 0.004f) continue
1704:             c.save()
1705:             c.rotate(d * PAGE_DEG)
1706:             drawWin(c, order[i], a * fa)
1707:             c.restore()
1708:         }
1709:         drawPageBars(c, a)
1710:         drawClose(c, a)
1711:         c.restore()
1712:     }
1713: 
1714:     private fun drawWin(c: Canvas, w: Win, a: Float) {
1715:         w.drawBands(c, a)
1716:         w.draw(c, a)
1717:     }
1718: 
1719:     private fun drawPageBars(c: Canvas, a: Float) {
1720:         // Short curved bars just outside the close X (one per enabled window). The accent highlight is one arc
1721:         // whose ends are interpolated from the continuous page position (stretches mid-transition).
1722:         val n = nWin
1723:         if (n < 2) return
1724:         val xd = kotlin.math.hypot(xCx, xCy)
1725:         val rd = xd + xRad + dp(7f)
1726:         val ai = (255f * a + 0.5f).toInt()
1727:         // 7.5 deg bars at 4 deg gaps, total span clamped to 28..62 deg and centred on 45 deg
1728:         var seg = 7.5f
1729:         val gap = BAR_GAP
1730:         var total = n * seg + (n - 1) * gap
1731:         if (total > 62f) {
1732:             seg = (62f - (n - 1) * gap) / n
1733:             total = 62f
1734:         } else if (total < 28f) {
1735:             seg = (28f - (n - 1) * gap) / n
1736:             total = 28f
1737:         }
1738:         val a0 = 45f - total / 2f
1739:         val step = seg + gap
1740:         val cap = Math.toDegrees((dp(1.5f) / rd).toDouble()).toFloat().coerceAtMost(seg / 3f)
1741:         xOval.set(-rd, -rd, rd, rd)
1742:         arcPaint.strokeWidth = dp(3f)
1743:         arcPaint.color = C_BAR
1744:         arcPaint.alpha = (255f * 0.6f * a + 0.5f).toInt()
1745:         for (i in 0 until n) {
1746:             c.drawArc(xOval, 180f + a0 + i * step + cap, seg - 2f * cap, false, arcPaint)
1747:         }
1748:         val pos = pageS.value.coerceIn(0f, (n - 1).toFloat())
1749:         val i0 = min(pos.toInt(), n - 2)
1750:         val f = pos - i0
1751:         val bulge = sin(Math.PI * f).toFloat() * 3f
1752:         val st = a0 + (i0 + f) * step - bulge + cap
1753:         val en = a0 + (i0 + f) * step + seg + bulge - cap
1754:         arcPaint.color = accent
1755:         arcPaint.alpha = ai
1756:         c.drawArc(xOval, 180f + st, en - st, false, arcPaint)
1757:     }
1758: 
1759:     private fun drawClose(c: Canvas, a: Float) {
1760:         val cx = xCx
1761:         val cy = xCy
1762:         val sc = 0.6f + 0.4f * a
1763:         val ai = (255f * a + 0.5f).toInt()
1764:         val px = pressed == ID_X && pressedWin == null
1765:         c.save()
1766:         c.scale(sc * (if (px) PRESS_SCALE else 1f), sc * (if (px) PRESS_SCALE else 1f), cx, cy)
1767:         fill.color = C_FILL
1768:         fill.alpha = ai
1769:         c.drawCircle(cx, cy, xRad, fill)
1770:         if (px) {
1771:             ringPaint.alpha = ai
1772:             c.drawCircle(cx, cy, xRad + dp(3f), ringPaint)
1773:         }
1774:         linePaint.color = Color.WHITE
1775:         linePaint.alpha = ai
1776:         linePaint.strokeWidth = dp(1.8f)
1777:         val h = dp(7f)
1778:         c.drawLine(cx - h, cy - h, cx + h, cy + h, linePaint)
1779:         c.drawLine(cx - h, cy + h, cx + h, cy - h, linePaint)
1780:         c.restore()
1781:     }
1782: 
1783:     // ================================================================ touch
1784: 
1785:     private fun addMove(ev: MotionEvent, rx: Float, ry: Float) {
1786:         val t = vt ?: return
1787:         // The window may resize mid-gesture, so feed the tracker raw (screen) coordinates.
1788:         val lx = ev.x
1789:         val ly = ev.y
1790:         ev.setLocation(rx, ry)
1791:         t.addMovement(ev)
1792:         ev.setLocation(lx, ly) // restore: callers read ev.x / ev.y in view coordinates afterwards
1793:     }
1794: 
1795:     private fun releaseTracker() {
1796:         vt?.recycle()
1797:         vt = null
1798:     }
1799: 
1800:     private fun cancelItemLongPress() {
1801:         removeCallbacks(longPressRunnable)
1802:     }
1803: 
1804:     override fun onTouchEvent(ev: MotionEvent): Boolean {
1805:         val action = ev.actionMasked
1806:         if (action == MotionEvent.ACTION_OUTSIDE) {
1807:             if (expandedTarget && !gestureActive && !holdOpen) startCollapse(false, 0f, 0f)
1808:             return false
1809:         }
1810:         if (hidden) return false
1811:         when (action) {
1812:             MotionEvent.ACTION_DOWN -> return handleDown(ev)
1813:             MotionEvent.ACTION_MOVE -> {
1814:                 if (!gestureActive) return false
1815:                 handleMove(ev)
1816:             }
1817:             MotionEvent.ACTION_UP -> {
1818:                 if (!gestureActive) return false
1819:                 handleUp(ev, false)
1820:             }
1821:             MotionEvent.ACTION_CANCEL -> {
1822:                 if (!gestureActive) return false
1823:                 handleUp(ev, true)
1824:             }
1825:         }
1826:         return true
1827:     }
1828: 
1829:     private fun handleDown(ev: MotionEvent): Boolean {
1830:         val x = ev.x
1831:         val y = ev.y
1832:         val rx = ev.rawX
1833:         val ry = ev.rawY
1834:         ensureResources()
1835:         buildLivePath()
1836:         clipRegion.set(0, 0, max(width, 1), max(height, 1))
1837:         hitRegion.setPath(path, clipRegion)
1838:         if (!hitRegion.contains(x.toInt(), y.toInt())) return false
1839: 
1840:         releaseTracker()
1841:         vt = VelocityTracker.obtain()
1842:         gestureActive = true
1843:         downRawX = rx
1844:         downRawY = ry
1845:         moved = false
1846:         dragMode = 0
1847:         captureWin = null
1848:         clearPressed()
1849:         longFired = false
1850:         addMove(ev, rx, ry)
1851: 
1852:         if (!expandedTarget) {
1853:             startMode = MODE_IDLE
1854:             updateViz()
1855:             removeCallbacks(liveTickRunnable)
1856:             // Pay the listener-registration cost during the finger-down, before the expand animation.
1857:             ensureSources()
1858:             press.target = PRESS_SCALE
1859:             requestFrames()
1860:         } else {
1861:             startMode = MODE_EXPANDED
1862:             baseW = wS.value
1863:             baseH = hS.value
1864:             pageStart = pageS.value
1865:             if (contentAlpha > 0.5f) {
1866:                 val item = hitItem(x, y)
1867:                 if (item == ID_X) {
1868:                     setPressed(null, ID_X)
1869:                     invalidate()
1870:                 } else if (item >= 0) {
1871:                     val w = hitWin
1872:                     if (w != null && w.usable(item)) {
1873:                         setPressed(w, item)
1874:                         if (w.longPressable(item)) postDelayed(longPressRunnable, longPressMs)
1875:                         invalidate()
1876:                     }
1877:                 } else {
1878:                     // a DOWN on a slider / dial / seek bar claims the whole gesture
1879:                     val w = settledWin(x, y)
1880:                     if (w != null && w.capture(lpX, lpY)) {
1881:                         captureWin = w
1882:                         moved = true
1883:                         w.dragTo(lpX, lpY)
1884:                         invalidate()
1885:                     }
1886:                 }
1887:             }
1888:         }
1889:         return true
1890:     }
1891: 
1892:     /** The window under the finger when a page is settled (sets lpX/lpY to its local frame), else null. */
1893:     private fun settledWin(x: Float, y: Float): Win? {
1894:         if (nWin == 0) return null
1895:         val pos = pageS.value
1896:         val s = Math.round(pos).coerceIn(0, nWin - 1)
1897:         if (abs(pos - s) > 0.2f) return null
1898:         toLocal(x, y, s - pos)
1899:         return order[s]
1900:     }
1901: 
1902:     /** Hit test: ID_X, a window-local item (hitWin set), or -1. */
1903:     private fun hitItem(x: Float, y: Float): Int {
1904:         hitWin = null
1905:         val px = x - width
1906:         val py = y - height
1907:         if ((px - xCx) * (px - xCx) + (py - xCy) * (py - xCy) <= xHit * xHit) return ID_X
1908:         val w = settledWin(x, y) ?: return -1
1909:         val i = w.hit(lpX, lpY)
1910:         if (i >= 0) hitWin = w
1911:         return i
1912:     }
1913: 
1914:     /** Converts a view point to the local (un-rotated) polar frame of a window page d pages away. */
1915:     private fun toLocal(x: Float, y: Float, d: Float) {
1916:         val px = x - width
1917:         val py = y - height
1918:         val ang = Math.toRadians((-d * PAGE_DEG).toDouble())
1919:         val cs = cos(ang).toFloat()
1920:         val sn = sin(ang).toFloat()
1921:         lpX = px * cs - py * sn
1922:         lpY = px * sn + py * cs
1923:     }
1924: 
1925:     private fun handleMove(ev: MotionEvent) {
1926:         val rx = ev.rawX
1927:         val ry = ev.rawY
1928:         addMove(ev, rx, ry)
1929:         val cw = captureWin
1930:         if (cw != null) {
1931:             moved = true
1932:             toLocal(ev.x, ev.y, cw.index - pageS.value)
1933:             cw.dragTo(lpX, lpY)
1934:             return
1935:         }
1936:         val dx = rx - downRawX
1937:         val dy = ry - downRawY
1938:         if (dragMode == 0) {
1939:             if (sqrt(dx * dx + dy * dy) > touchSlop) {
1940:                 moved = true
1941:                 cancelItemLongPress()
1942:                 if (pressed >= 0) {
1943:                     clearPressed()
1944:                     invalidate()
1945:                 }
1946:                 if (startMode == MODE_IDLE) {
1947:                     if (max(-dx, -dy) > touchSlop) beginDrag(1)
1948:                 } else if (abs(dx) > abs(dy) && abs(dx) > touchSlop) {
1949:                     beginDrag(3)
1950:                 } else if (dy > abs(dx) && dy > touchSlop) {
1951:                     beginDrag(2)
1952:                 }
1953:             }
1954:         }
1955:         if (dragMode != 0) applyDrag(dx, dy)
1956:     }
1957: 
1958:     private fun beginDrag(mode: Int) {
1959:         dragMode = mode
1960:         ensureResources()
1961:         collapsing = false
1962:         settledExpanded = false
1963:         removeCallbacks(liveTickRunnable)
1964:         if (mode == 1 && !openPicked) pickPage()
1965:         removeCallbacks(startSourcesRunnable)
1966:         if (!sourcesStarted) postDelayed(startSourcesRunnable, 40L)
1967:         regionExpanded = true
1968:         publishRegion()
1969:         updateViz()
1970:         when (mode) {
1971:             1 -> {
1972:                 baseW = max(idle, wS.value)
1973:                 baseH = max(idle, hS.value)
1974:             }
1975:             2 -> {
1976:                 baseW = wS.value
1977:                 baseH = hS.value
1978:             }
1979:             else -> {
1980:                 pageStart = pageS.value
1981:                 dragShift = max(0.9f * rad, 1f)
1982:                 pageS.velocity = 0f
1983:             }
1984:         }
1985:         press.target = 1f
1986:         requestFrames()
1987:     }
1988: 
1989:     private fun rubber(v: Float, maxV: Float): Float {
1990:         if (v <= idle) return idle
1991:         if (v <= maxV) return v
1992:         return maxV + min((v - maxV) * 0.3f, dp(18f))
1993:     }
1994: 
1995:     private fun setSizeDirect(w: Float, h: Float) {
1996:         wS.value = w; wS.target = w; wS.velocity = 0f
1997:         hS.value = h; hS.target = h; hS.velocity = 0f
1998:     }
1999: 
2000:     private fun applyDrag(dx: Float, dy: Float) {
2001:         when (dragMode) {
2002:             1 -> setSizeDirect(rubber(baseW - dx, rad), rubber(baseH - dy, rad))
2003:             2 -> {
2004:                 val hh = rubber(baseH - dy, baseH)
2005:                 val fr = (hh - idle) / max(baseH - idle, 1f)
2006:                 setSizeDirect(idle + (baseW - idle) * fr, hh)
2007:             }
2008:             3 -> {
2009:                 val last = (nWin - 1).toFloat()
2010:                 var pos = pageStart - dx / dragShift
2011:                 if (pos < 0f) pos *= 0.3f
2012:                 else if (pos > last) pos = last + (pos - last) * 0.3f
2013:                 pageS.value = pos; pageS.target = pos; pageS.velocity = 0f
2014:             }
2015:         }
2016:         updateDerived(0f)
2017:         invalidate()
2018:     }
2019: 
2020:     private fun finishDrag(vx: Float, vy: Float) {
2021:         val mode = dragMode
2022:         val outward = if (mode == 2) -vy else -(vx + vy) * 0.5f
2023:         val thr = dp(500f)
2024:         val doExpand = when {
2025:             outward > thr -> true
2026:             outward < -thr -> false
2027:             mode == 1 -> progress > 0.33f
2028:             else -> progress > 0.67f
2029:         }
2030:         dragMode = 0
2031:         val vh = -vy
2032:         val vw = if (mode == 2) -vy * (baseW - idle) / max(baseH - idle, 1f) else -vx
2033:         if (doExpand) startExpand(true, vw, vh) else startCollapse(true, vw, vh)
2034:     }
2035: 
2036:     private fun finishPage(dx: Float, vx: Float) {
2037:         val base = Math.round(pageStart).coerceIn(0, nWin - 1)
2038:         val thr = 0.28f * dragShift
2039:         val vthr = dp(500f)
2040:         var target = base
2041:         if (dx < -thr || vx < -vthr) target = base + 1
2042:         else if (dx > thr || vx > vthr) target = base - 1
2043:         target = target.coerceIn(0, nWin - 1)
2044:         if (target != base) userPaged = true
2045:         dragMode = 0
2046:         goToPage(target, -vx / dragShift)
2047:     }
2048: 
2049:     private fun handleUp(ev: MotionEvent, cancel: Boolean) {
2050:         val rx = ev.rawX
2051:         val ry = ev.rawY
2052:         val x = ev.x
2053:         val y = ev.y
2054:         cancelItemLongPress()
2055:         var vx = 0f
2056:         var vy = 0f
2057:         val t = vt
2058:         if (t != null) {
2059:             if (!cancel) addMove(ev, rx, ry)
2060:             t.computeCurrentVelocity(1000)
2061:             vx = t.xVelocity
2062:             vy = t.yVelocity
2063:         }
2064:         releaseTracker()
2065:         val item = pressed
2066:         val pw = pressedWin
2067:         clearPressed()
2068:         gestureActive = false
2069:         val quick = !moved && (ev.eventTime - ev.downTime) < TAP_MS
2070: 
2071:         val cw = captureWin
2072:         if (cw != null) {
2073:             captureWin = null
2074:             cw.dragEnd(cancel)
2075:         } else if (dragMode == 3) {
2076:             finishPage(if (cancel) 0f else rx - downRawX, if (cancel) 0f else vx)
2077:         } else if (dragMode != 0) {
2078:             finishDrag(if (cancel) 0f else vx, if (cancel) 0f else vy)
2079:         } else if (startMode == MODE_IDLE) {
2080:             press.target = 1f
2081:             if (!cancel && quick) onBeanTap(x, y)
2082:         } else if (!cancel && !longFired && !moved && item >= 0 && hitItem(x, y) == item && hitWin === pw) {
2083:             if (item == ID_X) startCollapse(false, 0f, 0f) else pw?.onTap(item)
2084:         }
2085:         updateViz()
2086:         syncClosedSize()
2087:         syncLiveTick()
2088:         invalidate()
2089:         requestFrames()
2090:     }
2091: 
2092:     private fun onLongPress() {
2093:         val item = pressed
2094:         val w = pressedWin
2095:         if (!gestureActive || w == null || !w.longPressable(item)) return
2096:         clearPressed()
2097:         longFired = true
2098:         haptic(HapticFeedbackConstants.LONG_PRESS)
2099:         val launched = w.onLongPress(item)
2100:         invalidate()
2101:         if (launched) startCollapse(false, 0f, 0f)
2102:     }
2103: 
2104:     // ================================================================ init
2105: 
2106:     init {
2107:         applyLooksNow()
2108:         reloadWindows()
2109:     }
2110: 
2111:     internal companion object {
2112:         const val K = 0.5523f
2113:         const val PRESS_SCALE = 0.92f
2114:         const val TAP_MS = 300L
2115:         const val MODE_IDLE = 0
2116:         const val MODE_EXPANDED = 1
2117:         const val ID_X = 1000
2118:         const val PAGE_DEG = 70f
2119:         const val SL_A0 = 8f
2120:         const val SL_A1 = 82f
2121:         const val LV_A0 = 16f
2122:         const val LV_A1 = 74f
2123:         const val LIVE_SCALE = 1.55f
2124:         val C_FILL = Color.parseColor("#1C1C1E")
2125:         val C_TRACK = Color.parseColor("#2C2C2E")
2126:         val C_GREY = Color.parseColor("#8E8E93")
2127:         val C_RED = Color.parseColor("#FF4B3E")
2128:         val C_BAR = Color.parseColor("#5A5A5E")
2129:         val C_CREAM = Color.parseColor("#F2EDE4")
2130:         val C_GREEN = Color.parseColor("#30D158")
2131:         val C_DECLINE = Color.parseColor("#FF453A")
2132:         const val BAR_GAP = 4f
2133:         const val GR = 100f
2134:         const val RIM_REST_A = 0.10f
2135:         const val RIM_HOT_A = 0.75f
2136:     }
2137: }
===== EdgeService.kt =====
   1: package com.dynedge
   2: 
   3: import android.accessibilityservice.AccessibilityService
   4: import android.app.KeyguardManager
   5: import android.content.BroadcastReceiver
   6: import android.content.Context
   7: import android.content.Intent
   8: import android.content.SharedPreferences
   9: import android.content.IntentFilter
  10: import android.content.res.Configuration
  11: import android.graphics.PixelFormat
  12: import android.graphics.Region
  13: import android.hardware.display.DisplayManager
  14: import android.os.BatteryManager
  15: import android.os.Build
  16: import android.os.Handler
  17: import android.os.Looper
  18: import android.os.PowerManager
  19: import android.view.Display
  20: import android.view.Gravity
  21: import android.view.RoundedCorner
  22: import android.view.WindowManager
  23: import android.view.accessibility.AccessibilityEvent
  24: import android.view.accessibility.AccessibilityWindowInfo
  25: 
  26: class EdgeService : AccessibilityService(), EdgeView.Host {
  27: 
  28:     private var wm: WindowManager? = null
  29:     private var edge: EdgeView? = null
  30:     private var lp: WindowManager.LayoutParams? = null
  31:     private var touch: TouchRegion? = null
  32:     private var sizesPosted = false
  33:     private var imeVisible = false
  34:     private var screenOff = false
  35:     private var receiverRegistered = false
  36: 
  37:     private val receiver = object : BroadcastReceiver() {
  38:         override fun onReceive(context: Context?, intent: Intent?) {
  39:             when (intent?.action) {
  40:                 Intent.ACTION_SCREEN_OFF -> {
  41:                     screenOff = true
  42:                     edge?.collapseImmediately()
  43:                     edge?.setScreenOn(false)
  44:                     updateHidden()
  45:                 }
  46:                 Intent.ACTION_SCREEN_ON -> {
  47:                     screenOff = false
  48:                     edge?.setScreenOn(true)
  49:                     updateHidden()
  50:                 }
  51:                 Intent.ACTION_USER_PRESENT -> updateHidden()
  52:                 Intent.ACTION_POWER_CONNECTED -> postCharging()
  53:                 Intent.ACTION_POWER_DISCONNECTED -> {
  54:                     mainHandler.removeCallbacks(chargingRemove)
  55:                     LiveBus.remove("charging")
  56:                 }
  57:             }
  58:         }
  59:     }
  60: 
  61:     private val mainHandler = Handler(Looper.getMainLooper())
  62:     private val chargingRemove = Runnable { LiveBus.remove("charging") }
  63: 
  64:     private fun postCharging() {
  65:         if (!Prefs.live(this) || screenOff) return
  66:         val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
  67:         val lv = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
  68:         val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
  69:         val pct = if (lv >= 0 && sc > 0) (lv * 100 / sc) else 0
  70:         LiveBus.put(
  71:             LiveActivity(
  72:                 "charging", LiveActivity.Kind.CHARGING, "$pct%", pct / 100f, 0L, false,
  73:                 R.drawable.ic_bolt, false, null, null, null
  74:             )
  75:         )
  76:         mainHandler.removeCallbacks(chargingRemove)
  77:         mainHandler.postDelayed(chargingRemove, 3500L)
  78:     }
  79: 
  80:     private var prefListenerRegistered = false
  81:     // strong reference: SharedPreferences only holds listeners weakly
  82:     private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
  83:         when (key) {
  84:             Prefs.KEY_IDLE_DP, Prefs.KEY_OPEN_DP -> scheduleSizes()
  85:             Prefs.KEY_PREVIEW -> applyPreview()
  86:             Prefs.KEY_ACCENT, Prefs.KEY_GRADIENT, Prefs.KEY_RIM, Prefs.KEY_GLOW, Prefs.KEY_BANDS,
  87:             Prefs.KEY_SPEED, Prefs.KEY_BOUNCE, Prefs.KEY_HAPTICS, Prefs.KEY_IDLE_STYLE,
  88:             Prefs.KEY_VISUALIZER, Prefs.KEY_LIVE, Prefs.KEY_BEAT -> edge?.applyLooks()
  89:             QuickConfig.KEY -> edge?.reloadQuick()
  90:             WindowsConfig.KEY -> edge?.reloadWindows()
  91:             Prefs.KEY_FLARE -> applyFlare()
  92:         }
  93:     }
  94: 
  95:     private val sizesRunnable = Runnable {
  96:         sizesPosted = false
  97:         applySizesFromPrefs()
  98:     }
  99: 
 100:     /** Coalesces a stream of size changes (slider drag) to one relayout per frame. */
 101:     private fun scheduleSizes() {
 102:         val view = edge ?: return
 103:         if (sizesPosted) return
 104:         sizesPosted = true
 105:         view.postOnAnimation(sizesRunnable)
 106:     }
 107: 
 108:     private fun applyPreview() {
 109:         val view = edge ?: return
 110:         when (Prefs.preview(this)) {
 111:             Prefs.PREVIEW_OPEN -> {
 112:                 view.holdOpen = true
 113:                 view.expand()
 114:             }
 115:             else -> {
 116:                 view.holdOpen = false
 117:                 view.collapse()
 118:             }
 119:         }
 120:     }
 121: 
 122:     private fun applySizesFromPrefs() {
 123:         val view = edge ?: return
 124:         val manager = wm ?: return
 125:         val params = lp ?: return
 126:         view.applySizes(Prefs.idleDp(this), Prefs.openDp(this))
 127:         resizeWindow(view, manager, params)
 128:     }
 129: 
 130:     private fun applyFlare() {
 131:         val view = edge ?: return
 132:         val manager = wm ?: return
 133:         val params = lp ?: return
 134:         view.applyFlare()
 135:         resizeWindow(view, manager, params)
 136:     }
 137: 
 138:     /** The window is always the large size; it only changes when the sizes change in prefs. */
 139:     private fun resizeWindow(view: EdgeView, manager: WindowManager, params: WindowManager.LayoutParams) {
 140:         val pad = (24f * density).toInt()
 141:         val w = view.maxOpenWidthPx + pad
 142:         val h = view.maxOpenHeightPx + pad
 143:         if (params.width == w && params.height == h) return
 144:         params.width = w
 145:         params.height = h
 146:         try {
 147:             manager.updateViewLayout(view, params)
 148:         } catch (e: Throwable) {
 149:         }
 150:     }
 151: 
 152:     private val density: Float get() = resources.displayMetrics.density
 153: 
 154:     private var attached = false
 155:     private var attachTries = 0
 156:     private val retryDelays = longArrayOf(300L, 1000L, 3000L, 3000L, 3000L)
 157:     private val attachRunnable = Runnable { attach() }
 158: 
 159:     override fun onServiceConnected() {
 160:         super.onServiceConnected()
 161:         mainHandler.removeCallbacks(attachRunnable)
 162:         attachTries = 0
 163:         attach()
 164:     }
 165: 
 166:     /** Idempotent: builds the view once, adds the window, and retries with backoff if the token is not valid yet. */
 167:     private fun attach() {
 168:         if (attached) return
 169:         val manager = wm ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { wm = it }
 170:         var view = edge
 171:         var params = lp
 172:         if (view == null || params == null) {
 173:             AssistControl.disableCornerGesture(this)
 174:             val v = EdgeView(this)
 175:             v.host = this
 176:             v.setService(this)
 177:             v.setTipRadius(readTipRadius())
 178:             val winPx = v.maxOpenWidthPx + (24f * density).toInt()
 179:             val p = WindowManager.LayoutParams(
 180:                 winPx,
 181:                 winPx,
 182:                 WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
 183:                 WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
 184:                     WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
 185:                     WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
 186:                     WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
 187:                     WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
 188:                     WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
 189:                 PixelFormat.TRANSLUCENT
 190:             )
 191:             p.gravity = Gravity.BOTTOM or Gravity.RIGHT
 192:             p.x = 0
 193:             p.y = 0
 194:             p.fitInsetsTypes = 0
 195:             p.fitInsetsSides = 0
 196:             p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
 197:             p.title = "DynamicEdge"
 198:             edge = v
 199:             lp = p
 200:             view = v
 201:             params = p
 202:         }
 203:         try {
 204:             if (!view.isAttachedToWindow) manager.addView(view, params)
 205:         } catch (e: WindowManager.BadTokenException) {
 206:             scheduleRetry()
 207:             return
 208:         } catch (e: IllegalStateException) {
 209:             scheduleRetry()
 210:             return
 211:         } catch (e: Throwable) {
 212:             scheduleRetry()
 213:             return
 214:         }
 215:         attached = true
 216:         touch?.uninstall()
 217:         touch = TouchRegion.install(view)
 218:         touch?.set(view.currentRegion())
 219: 
 220:         if (!receiverRegistered) {
 221:             val filter = IntentFilter()
 222:             filter.addAction(Intent.ACTION_SCREEN_OFF)
 223:             filter.addAction(Intent.ACTION_SCREEN_ON)
 224:             filter.addAction(Intent.ACTION_USER_PRESENT)
 225:             filter.addAction(Intent.ACTION_POWER_CONNECTED)
 226:             filter.addAction(Intent.ACTION_POWER_DISCONNECTED)
 227:             registerReceiver(receiver, filter)
 228:             receiverRegistered = true
 229:         }
 230: 
 231:         screenOff = false
 232:         updateHidden()
 233:         view.setScreenOn((getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true)
 234: 
 235:         if (!prefListenerRegistered) {
 236:             Prefs.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
 237:             prefListenerRegistered = true
 238:         }
 239:     }
 240: 
 241:     private fun scheduleRetry() {
 242:         if (attachTries >= retryDelays.size) return
 243:         val d = retryDelays[attachTries]
 244:         attachTries++
 245:         mainHandler.removeCallbacks(attachRunnable)
 246:         mainHandler.postDelayed(attachRunnable, d)
 247:     }
 248: 
 249:     override fun onAccessibilityEvent(event: AccessibilityEvent?) {
 250:         if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
 251:         val ime = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
 252:         if (ime != imeVisible) {
 253:             imeVisible = ime
 254:             updateHidden()
 255:         }
 256:     }
 257: 
 258:     override fun onInterrupt() {
 259:         // Nothing to interrupt.
 260:     }
 261: 
 262:     override fun onConfigurationChanged(newConfig: Configuration) {
 263:         super.onConfigurationChanged(newConfig)
 264:         if (!attached) return
 265:         val view = edge ?: return
 266:         val manager = wm ?: return
 267:         val params = lp ?: return
 268:         view.setTipRadius(readTipRadius())
 269:         view.collapseImmediately()
 270:         resizeWindow(view, manager, params)
 271:     }
 272: 
 273:     override fun onUnbind(intent: Intent?): Boolean {
 274:         teardown()
 275:         return super.onUnbind(intent)
 276:     }
 277: 
 278:     override fun onDestroy() {
 279:         teardown()
 280:         super.onDestroy()
 281:     }
 282: 
 283:     // ------------------------------------------------------------ EdgeView.Host
 284: 
 285:     override fun onSettled(expanded: Boolean) {
 286:         // The window never resizes while opening or closing.
 287:     }
 288: 
 289:     override fun onRegionChanged(region: Region) {
 290:         touch?.set(region)
 291:     }
 292: 
 293:     override fun setWindowFocusable(on: Boolean) {
 294:         val view = edge ?: return
 295:         val params = lp ?: return
 296:         val manager = wm ?: return
 297:         val has = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
 298:         if (has == on) return
 299:         params.flags = if (on) {
 300:             params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
 301:         } else {
 302:             params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
 303:         }
 304:         try {
 305:             manager.updateViewLayout(view, params)
 306:         } catch (e: Throwable) {
 307:         }
 308:     }
 309: 
 310:     // ------------------------------------------------------------ helpers
 311: 
 312:     private fun updateHidden() {
 313:         val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
 314:         val locked = km?.isKeyguardLocked ?: false
 315:         edge?.setHidden(imeVisible || locked || screenOff)
 316:     }
 317: 
 318:     private fun readTipRadius(): Float {
 319:         if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0f
 320:         val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return 0f
 321:         val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return 0f
 322:         return display.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius?.toFloat() ?: 0f
 323:     }
 324: 
 325:     private fun teardown() {
 326:         if (receiverRegistered) {
 327:             receiverRegistered = false
 328:             try {
 329:                 unregisterReceiver(receiver)
 330:             } catch (e: Throwable) {
 331:             }
 332:         }
 333:         if (prefListenerRegistered) {
 334:             prefListenerRegistered = false
 335:             try {
 336:                 Prefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
 337:             } catch (e: Throwable) {
 338:             }
 339:         }
 340:         mainHandler.removeCallbacks(attachRunnable)
 341:         attached = false
 342:         edge?.removeCallbacks(sizesRunnable)
 343:         mainHandler.removeCallbacks(chargingRemove)
 344:         val view = edge ?: return
 345:         edge = null
 346:         touch?.uninstall()
 347:         touch = null
 348:         view.host = null
 349:         view.setService(null)
 350:         view.release()
 351:         try {
 352:             if (view.isAttachedToWindow) wm?.removeView(view)
 353:         } catch (e: Throwable) {
 354:         }
 355:         lp = null
 356:     }
 357: }
===== Win.kt =====
   1: package com.dynedge
   2: 
   3: import android.graphics.Canvas
   4: import android.graphics.Path
   5: import android.graphics.Rect
   6: import android.graphics.RectF
   7: import android.graphics.RenderNode
   8: import kotlin.math.max
   9: import kotlin.math.min
  10: 
  11: /**
  12:  * One panel window (QUICK, MEDIA, ...). A window owns its own layout, drawing and hit testing in the
  13:  * unrotated polar frame whose origin is the screen corner (x and y are negative inside the panel).
  14:  * The host ([EdgeView]) owns gestures, springs, paging, section layers and the shared paints.
  15:  *
  16:  * Item ids are local to the window (0 until its item count); the host adds the window to the pressed state.
  17:  */
  18: internal abstract class Win(@JvmField protected val h: EdgeView, val id: String, items: Int) {
  19: 
  20:     /** True while the section layer must be re-recorded. */
  21:     @JvmField var dirty = true
  22:     @JvmField var node: RenderNode? = null
  23:     @JvmField var index = -1
  24:     /** True between start() and stop() (panel open, listeners registered). */
  25:     @JvmField var started = false
  26:     @JvmField val bands = ArrayList<Array<Path>>()
  27: 
  28:     @JvmField protected val cx = FloatArray(items)
  29:     @JvmField protected val cy = FloatArray(items)
  30:     @JvmField protected val ir = FloatArray(items)
  31:     @JvmField protected val hitR = FloatArray(items)
  32:     @JvmField protected val iconRect = Array(items) { Rect() }
  33: 
  34:     @JvmField protected var pr = 0f // polar scratch: radius and angle (deg from the bottom edge)
  35:     @JvmField protected var pa = 0f
  36:     private var hBest = -1
  37:     private var hBestD = 0f
  38:     private val bandOval = RectF()
  39: 
  40:     protected fun dp(v: Float): Float = h.dp(v)
  41:     protected val rad: Float get() = h.rad
  42: 
  43:     // ---------------------------------------------------------------- lifecycle
  44:     /** Computes geometry for the current panel radius; bands are cleared by the host before. */
  45:     open fun layout(hw: Float, brad: Float) {}
  46:     /** Panel opened: register listeners and load data. */
  47:     open fun start() {}
  48:     /** Panel closed or window removed: release everything. */
  49:     open fun stop() {}
  50:     /** This window became (true) or stopped being (false) the visible, settled window. */
  51:     open fun onShown(on: Boolean) {}
  52:     /** Shared outer-ring sliders (Arcs) reported a change. */
  53:     open fun onArcs() {}
  54: 
  55:     // ---------------------------------------------------------------- drawing
  56:     abstract fun draw(c: Canvas, a: Float)
  57: 
  58:     fun drawBands(c: Canvas, a: Float) {
  59:         if (!h.bandsOn) return
  60:         h.tilePaint.alpha = (255f * 0.045f * a + 0.5f).toInt()
  61:         for (b in bands.indices) {
  62:             val ts = bands[b]
  63:             for (t in ts.indices) c.drawPath(ts[t], h.tilePaint)
  64:         }
  65:     }
  66: 
  67:     // ---------------------------------------------------------------- touch (local, unrotated coordinates)
  68:     /** Item under the point or -1. */
  69:     open fun hit(lx: Float, ly: Float): Int = -1
  70:     open fun usable(item: Int): Boolean = true
  71:     open fun longPressable(item: Int): Boolean = false
  72:     open fun onTap(item: Int) {}
  73:     /** Returns true if an Activity was launched (the host collapses). */
  74:     open fun onLongPress(item: Int): Boolean = false
  75:     /** DOWN landed on a draggable control (arc slider, seek bar, dial): claim the whole gesture. */
  76:     open fun capture(lx: Float, ly: Float): Boolean = false
  77:     open fun dragTo(lx: Float, ly: Float) {}
  78:     open fun dragEnd(cancel: Boolean) {}
  79: 
  80:     fun isPressed(i: Int): Boolean = h.pressed == i && h.pressedWin === this
  81: 
  82:     // ---------------------------------------------------------------- helpers
  83:     protected fun polar(lx: Float, ly: Float) {
  84:         pr = kotlin.math.sqrt(lx * lx + ly * ly)
  85:         pa = Math.toDegrees(kotlin.math.atan2(-ly.toDouble(), -lx.toDouble())).toFloat()
  86:     }
  87: 
  88:     protected fun beginHit() {
  89:         hBest = -1
  90:         hBestD = Float.MAX_VALUE
  91:     }
  92: 
  93:     protected fun consider(i: Int, lx: Float, ly: Float) {
  94:         val dx = lx - cx[i]
  95:         val dy = ly - cy[i]
  96:         val d2 = dx * dx + dy * dy
  97:         val hr = hitR[i]
  98:         if (d2 <= hr * hr && d2 < hBestD) {
  99:             hBest = i
 100:             hBestD = d2
 101:         }
 102:     }
 103: 
 104:     protected fun bestHit(): Int = hBest
 105: 
 106:     protected fun forceHit(i: Int) {
 107:         hBest = i
 108:     }
 109: 
 110:     /** Places item i on a ring (radius rc, angle aDeg) shrunk to stay inside the curve. */
 111:     protected fun place(i: Int, rc: Float, aDeg: Float, itemR: Float) {
 112:         val r = min(rc, rad - dp(6f) - itemR)
 113:         val a = Math.toRadians(aDeg.toDouble())
 114:         cx[i] = -r * kotlin.math.cos(a).toFloat()
 115:         cy[i] = -r * kotlin.math.sin(a).toFloat()
 116:         ir[i] = itemR
 117:         hitR[i] = max(itemR + dp(3f), dp(22f)) // 44 dp minimum touch target
 118:         val half = (itemR * 0.475f).toInt().coerceAtLeast(1)
 119:         val x = cx[i].toInt()
 120:         val y = cy[i].toInt()
 121:         iconRect[i].set(x - half, y - half, x + half, y + half)
 122:     }
 123: 
 124:     protected fun setIconRect(i: Int, half: Int) {
 125:         val x = cx[i].toInt()
 126:         val y = cy[i].toInt()
 127:         iconRect[i].set(x - half, y - half, x + half, y + half)
 128:     }
 129: 
 130:     protected fun addBand(r1: Float, r2: Float, a0: Float, a1: Float, n: Int) {
 131:         val inset = dp(1f)
 132:         val ri = r1 + inset
 133:         val ro = r2 - inset
 134:         if (ro <= ri + dp(2f) || n <= 0) return
 135:         val gapHalf = (dp(3f) + dp(2f)) / 2f
 136:         val oOut = Math.toDegrees((gapHalf / ro).toDouble()).toFloat()
 137:         val oIn = Math.toDegrees((gapHalf / ri).toDouble()).toFloat()
 138:         val outer = RectF(-ro, -ro, ro, ro)
 139:         val inner = RectF(-ri, -ri, ri, ri)
 140:         val span = (a1 - a0) / n
 141:         val arr = Array(n) { Path() }
 142:         for (t in 0 until n) {
 143:             val s = a0 + t * span
 144:             val e = s + span
 145:             val p = arr[t]
 146:             p.arcTo(outer, 180f + s + oOut, span - 2f * oOut, true)
 147:             p.arcTo(inner, 180f + e - oIn, -(span - 2f * oIn), false)
 148:             p.close()
 149:         }
 150:         bands.add(arr)
 151:     }
 152: 
 153:     protected fun arcPath(p: Path, r: Float, a0: Float, a1: Float) {
 154:         bandOval.set(-r, -r, r, r)
 155:         p.rewind()
 156:         p.arcTo(bandOval, 180f + a0, a1 - a0, true)
 157:     }
 158: 
 159:     /** Round button: optional fill disc, press ring and icon. */
 160:     protected fun drawBtn(c: Canvas, i: Int, fillColor: Int, res: Int, black: Boolean, a: Float, ba: Float) {
 161:         val x = cx[i]
 162:         val y = cy[i]
 163:         val ps = isPressed(i)
 164:         if (ps) {
 165:             c.save()
 166:             c.scale(EdgeView.PRESS_SCALE, EdgeView.PRESS_SCALE, x, y)
 167:         }
 168:         val ai = (255f * ba + 0.5f).toInt()
 169:         if (fillColor != 0) {
 170:             h.fillP.color = fillColor
 171:             h.fillP.alpha = ai
 172:             c.drawCircle(x, y, ir[i], h.fillP)
 173:         }
 174:         if (ps) {
 175:             h.ringPaint.alpha = (255f * a + 0.5f).toInt()
 176:             c.drawCircle(x, y, ir[i] + dp(3f), h.ringPaint)
 177:         }
 178:         if (res != 0) {
 179:             val ic = h.icon(res, black)
 180:             ic.bounds = iconRect[i]
 181:             ic.alpha = ai
 182:             ic.draw(c)
 183:         }
 184:         if (ps) c.restore()
 185:     }
 186: 
 187:     companion object {
 188:         const val SL_A0 = 8f
 189:         const val SL_A1 = 82f
 190: 
 191:         fun slotAngle(i: Int, n: Int): Float = if (n <= 1) 45f else 12f + i * 66f / (n - 1)
 192:     }
 193: }
===== MainActivity.kt =====
   1: package com.dynedge
   2: 
   3: import android.app.Activity
   4: import android.app.NotificationManager
   5: import android.content.ClipData
   6: import android.content.ClipboardManager
   7: import android.content.ComponentName
   8: import android.content.Context
   9: import android.content.Intent
  10: import android.content.res.ColorStateList
  11: import android.graphics.Color
  12: import android.graphics.Typeface
  13: import android.graphics.drawable.ColorDrawable
  14: import android.graphics.drawable.GradientDrawable
  15: import android.graphics.drawable.RippleDrawable
  16: import android.content.pm.PackageManager
  17: import android.net.Uri
  18: import android.os.Bundle
  19: import android.provider.Settings
  20: import android.util.TypedValue
  21: import android.view.Gravity
  22: import android.view.MotionEvent
  23: import android.view.View
  24: import android.view.ViewGroup
  25: import android.view.WindowInsets
  26: import android.widget.ImageView
  27: import android.widget.LinearLayout
  28: import android.widget.ScrollView
  29: import android.widget.TextView
  30: 
  31: /** Setup and settings screen. Framework Views built in code, pure black, flat rows. */
  32: class MainActivity : Activity() {
  33: 
  34:     private class Perm(val label: String, val ok: () -> Boolean, val intent: () -> Intent, val adb: List<String>)
  35: 
  36:     private var firstInSection = true
  37:     private var showAll = false
  38:     private var adbOpen = false
  39:     private lateinit var setupBox: LinearLayout
  40:     private lateinit var setupCount: TextView
  41:     private val sliders = ArrayList<FlatSlider>()
  42:     private val toggles = ArrayList<FlatToggle>()
  43:     private val previews = ArrayList<IdlePreview>()
  44:     private lateinit var appsBox: LinearLayout
  45:     private lateinit var scroll: ScrollView
  46: 
  47:     private val white = Color.WHITE
  48:     private val grey = Color.parseColor("#8E8E93")
  49:     private val divider = Color.parseColor("#1C1C1E")
  50:     private val red = Color.parseColor("#FF4B3E")
  51: 
  52:     private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
  53:     private fun edit() = Prefs.prefs(this).edit()
  54: 
  55:     override fun onCreate(savedInstanceState: Bundle?) {
  56:         super.onCreate(savedInstanceState)
  57:         // adb hook: am start -n com.dynedge/.MainActivity --ez white_wallpaper true
  58:         if (intent.getBooleanExtra("white_wallpaper", false)) setWhiteWallpaper()
  59:         // adb hook: push an image to Android/data/com.dynedge/files/<name>, then
  60:         // am start -n com.dynedge/.MainActivity --es wallpaper_file <name>
  61:         intent.getStringExtra("wallpaper_file")?.let { name ->
  62:             try {
  63:                 val f = java.io.File(getExternalFilesDir(null), name)
  64:                 java.io.FileInputStream(f).use {
  65:                     android.app.WallpaperManager.getInstance(this)
  66:                         .setStream(it, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
  67:                 }
  68:             } catch (e: Exception) {
  69:                 android.util.Log.w("DynamicEdge", "wallpaper_file failed", e)
  70:             }
  71:         }
  72:         window.statusBarColor = Color.BLACK
  73:         window.navigationBarColor = Color.BLACK
  74:         window.setDecorFitsSystemWindows(false)
  75:         setContentView(build())
  76:     }
  77: 
  78:     private fun build(): ScrollView {
  79:         sliders.clear(); toggles.clear(); previews.clear()
  80:         val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
  81: 
  82:         content.addView(text("Dynamic Edge", 22f, white, Typeface.DEFAULT_BOLD).apply {
  83:             setPadding(dp(16), dp(20), dp(16), dp(0))
  84:         })
  85: 
  86:         // SIZE (first, so the live preview does not cover the slider being dragged)
  87:         header(content, "SIZE")
  88:         addSlider(content, "Corner size", Prefs.IDLE_MIN, Prefs.IDLE_MAX, Prefs.idleDp(this), { "${it.toInt()} dp" },
  89:             Prefs.PREVIEW_IDLE, { Prefs.setIdleDp(this, it) })
  90:         addSlider(content, "Open size", Prefs.OPEN_MIN, Prefs.OPEN_MAX, Prefs.openDp(this), { "${it.toInt()} dp" },
  91:             Prefs.PREVIEW_OPEN, { Prefs.setOpenDp(this, it) })
  92: 
  93:         // WINDOWS (on/off + order; long-press a row and drag)
  94:         header(content, "WINDOWS")
  95:         addWindows(content)
  96: 
  97:         // EDGE (layout editor, clipboard history, live activities)
  98:         header(content, "EDGE")
  99:         content.addView(simpleRow("Quick layout", ">", grey) {
 100:             open { Intent(this, QuickEditorActivity::class.java) }
 101:         })
 102:         sep(content)
 103:         content.addView(simpleRow("Clipboard", ">", grey) {
 104:             open { Intent(this, ClipboardActivity::class.java) }
 105:         })
 106:         sep(content)
 107:         addSwitch(content, "Live activities", Prefs.live(this)) { edit().putBoolean(Prefs.KEY_LIVE, it).apply() }
 108: 
 109:         // LOOKS
 110:         header(content, "LOOKS")
 111:         addSwatches(content)
 112:         addSlider(content, "Corner gradient", 0f, 100f, Prefs.gradient(this).toFloat(), { "${it.toInt()}%" },
 113:             Prefs.PREVIEW_OPEN, { edit().putInt(Prefs.KEY_GRADIENT, it.toInt()).apply(); previews.forEach { p -> p.gradient = it.toInt() } })
 114:         addSwitch(content, "Border", Prefs.rim(this)) { edit().putBoolean(Prefs.KEY_RIM, it).apply() }
 115:         addSwitch(content, "Glow", Prefs.glow(this)) { edit().putBoolean(Prefs.KEY_GLOW, it).apply() }
 116:         addSwitch(content, "Ring tiles", Prefs.bands(this)) { edit().putBoolean(Prefs.KEY_BANDS, it).apply() }
 117:         addPicker(content, IdlePreview.FILL, Prefs.FILLS, Prefs.fill(this), Prefs.KEY_FILL)
 118:         addSlider(content, "Flare", 0f, Prefs.FLARE_MAX, Prefs.flareDp(this), { "${it.toInt()} dp" },
 119:             Prefs.PREVIEW_IDLE, { edit().putFloat(Prefs.KEY_FLARE, it).apply() })
 120:         addPicker(content, IdlePreview.IDLE, Prefs.IDLE_STYLES, Prefs.idleStyle(this), Prefs.KEY_IDLE_STYLE)
 121:         addSwitch(content, "Music visualizer", Prefs.visualizer(this)) { edit().putBoolean(Prefs.KEY_VISUALIZER, it).apply() }
 122:         addPicker(content, IdlePreview.VIZ, Prefs.VIZ_STYLES, Prefs.vizStyle(this), Prefs.KEY_VIZ_STYLE)
 123:         addSwitch(content, "Beat reaction", Prefs.beat(this)) { edit().putBoolean(Prefs.KEY_BEAT, it).apply() }
 124: 
 125:         // FEEL
 126:         header(content, "FEEL")
 127:         addSlider(content, "Speed", 0f, 100f, Prefs.speed(this).toFloat(), { "${it.toInt()}%" },
 128:             null, { edit().putInt(Prefs.KEY_SPEED, it.toInt()).apply() })
 129:         addSlider(content, "Bounce", 0f, 100f, Prefs.bounce(this).toFloat(), { "${it.toInt()}%" },
 130:             null, { edit().putInt(Prefs.KEY_BOUNCE, it.toInt()).apply() })
 131:         addSwitch(content, "Haptics", Prefs.haptics(this)) { edit().putBoolean(Prefs.KEY_HAPTICS, it).apply() }
 132:         sep(content)
 133:         content.addView(text("Reset looks", 15f, red, Typeface.DEFAULT).apply {
 134:             setPadding(dp(16), dp(8), dp(16), dp(8))
 135:             minimumHeight = dp(44)
 136:             gravity = Gravity.CENTER_VERTICAL
 137:             isClickable = true
 138:             background = ripple()
 139:             setOnClickListener {
 140:                 Prefs.resetLooks(this@MainActivity)
 141:                 val y = scroll.scrollY
 142:                 val s = build()
 143:                 setContentView(s)
 144:                 s.post { s.scrollTo(0, y) }
 145:                 buildApps()
 146:                 refreshSetup()
 147:             }
 148:         })
 149: 
 150:         // APPS
 151:         header(content, "APPS")
 152:         appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
 153:         content.addView(appsBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
 154: 
 155:         // SETUP (last)
 156:         setupCount = text("", 11f, grey, Typeface.DEFAULT_BOLD)
 157:         header(content, "SETUP", setupCount)
 158:         setupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
 159:         content.addView(setupBox, matchWrap())
 160:         content.addView(View(this), LinearLayout.LayoutParams(1, dp(24)))
 161: 
 162:         scroll = ScrollView(this).apply {
 163:             setBackgroundColor(Color.BLACK)
 164:             isFillViewport = true
 165:             addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
 166:             setOnApplyWindowInsetsListener { v, insets ->
 167:                 val bars = insets.getInsets(WindowInsets.Type.systemBars())
 168:                 v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
 169:                 WindowInsets.CONSUMED
 170:             }
 171:         }
 172:         return scroll
 173:     }
 174: 
 175:     override fun onPause() {
 176:         super.onPause()
 177:         edit().putString(Prefs.KEY_PREVIEW, Prefs.PREVIEW_NONE).apply()
 178:     }
 179: 
 180:     override fun onResume() {
 181:         super.onResume()
 182:         refreshSetup()
 183:         buildApps()
 184:     }
 185: 
 186:     private fun perms(): List<Perm> = listOf(
 187:         Perm("Overlay service", { isOverlayServiceEnabled() }, { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
 188:             listOf(
 189:                 "adb shell settings put secure enabled_accessibility_services com.dynedge/com.dynedge.EdgeService",
 190:                 "adb shell settings put secure accessibility_enabled 1"
 191:             )),
 192:         Perm("Media access", { isMediaListenerEnabled() }, { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
 193:             listOf("adb shell cmd notification allow_listener com.dynedge/com.dynedge.MediaListener")),
 194:         Perm("Do Not Disturb access", { isDndGranted() }, { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
 195:             listOf("adb shell cmd notification allow_dnd com.dynedge")),
 196:         Perm("Modify system settings", { Settings.System.canWrite(this) },
 197:             { Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")) },
 198:             listOf("adb shell appops set com.dynedge WRITE_SETTINGS allow")),
 199:         Perm("Assistant corner swipe off", { !isAssistSwipeOn() }, { Intent(Settings.ACTION_SETTINGS) },
 200:             listOf("adb shell pm grant com.dynedge android.permission.WRITE_SECURE_SETTINGS")),
 201:         Perm("Microphone", { granted("android.permission.RECORD_AUDIO") }, { appDetails() },
 202:             listOf("adb shell pm grant com.dynedge android.permission.RECORD_AUDIO")),
 203:         Perm("Secure settings", { granted("android.permission.WRITE_SECURE_SETTINGS") }, { appDetails() },
 204:             listOf("adb shell pm grant com.dynedge android.permission.WRITE_SECURE_SETTINGS"))
 205:     )
 206: 
 207:     private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
 208: 
 209:     private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
 210: 
 211:     private fun refreshSetup() {
 212:         setupBox.removeAllViews()
 213:         val all = perms()
 214:         val missing = all.filter { p -> !(try { p.ok() } catch (e: Exception) { false }) }
 215:         val accent = Prefs.accent(this)
 216:         if (missing.isEmpty()) {
 217:             setupCount.text = ""
 218:             val row = simpleRow("Setup", "Done", Color.parseColor("#30D158")) {
 219:                 showAll = !showAll
 220:                 refreshSetup()
 221:             }
 222:             setupBox.addView(row, matchWrap())
 223:             if (showAll) {
 224:                 for (p in all) {
 225:                     setupBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
 226:                     setupBox.addView(simpleRow(p.label, "On", white) { open(p.intent) }, matchWrap())
 227:                 }
 228:             }
 229:             return
 230:         }
 231:         setupCount.text = "${missing.size} missing"
 232:         for ((i, p) in missing.withIndex()) {
 233:             if (i > 0) setupBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
 234:             setupBox.addView(simpleRow(p.label, "Fix", accent) { open(p.intent) }, matchWrap())
 235:         }
 236:         val cmds = LinkedHashSet<String>()
 237:         missing.forEach { cmds.addAll(it.adb) }
 238:         val cmdText = cmds.joinToString("\n")
 239:         val adbText = text(cmdText, 11f, white, Typeface.MONOSPACE).apply {
 240:             setPadding(dp(16), dp(8), dp(16), dp(12))
 241:             visibility = if (adbOpen) View.VISIBLE else View.GONE
 242:             setOnLongClickListener {
 243:                 val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
 244:                 cm.setPrimaryClip(ClipData.newPlainText("adb", cmdText))
 245:                 performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
 246:                 true
 247:             }
 248:         }
 249:         val adbRow = simpleRow("ADB", if (adbOpen) "Hide" else "Show", grey) {}
 250:         val adbState = adbRow.getChildAt(1) as TextView
 251:         adbRow.setOnClickListener {
 252:             adbOpen = !adbOpen
 253:             adbText.visibility = if (adbOpen) View.VISIBLE else View.GONE
 254:             adbState.text = if (adbOpen) "Hide" else "Show"
 255:         }
 256:         setupBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
 257:         setupBox.addView(adbRow, matchWrap())
 258:         setupBox.addView(adbText, matchWrap())
 259:     }
 260: 
 261:     private fun open(intent: () -> Intent) {
 262:         try {
 263:             startActivity(intent())
 264:         } catch (e: Exception) {
 265:         }
 266:     }
 267: 
 268:     private fun simpleRow(label: String, state: String, stateColor: Int, onClick: () -> Unit): LinearLayout =
 269:         LinearLayout(this).apply {
 270:             orientation = LinearLayout.HORIZONTAL
 271:             gravity = Gravity.CENTER_VERTICAL
 272:             minimumHeight = dp(44)
 273:             setPadding(dp(16), dp(6), dp(16), dp(6))
 274:             isClickable = true
 275:             background = ripple()
 276:             addView(text(label, 15f, white, Typeface.DEFAULT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
 277:             addView(text(state, 14f, stateColor, Typeface.DEFAULT))
 278:             setOnClickListener { onClick() }
 279:         }
 280: 
 281:     private fun buildApps() {
 282:         appsBox.removeAllViews()
 283:         val pm = packageManager
 284:         val list = Prefs.apps(this).toMutableList()
 285:         val shown = ArrayList<ComponentName>()
 286:         for (cn in list) {
 287:             val info = try {
 288:                 pm.getActivityInfo(cn, 0)
 289:             } catch (e: Exception) {
 290:                 continue
 291:             }
 292:             shown.add(cn)
 293:             val row = LinearLayout(this).apply {
 294:                 orientation = LinearLayout.HORIZONTAL
 295:                 gravity = Gravity.CENTER_VERTICAL
 296:                 minimumHeight = dp(44)
 297:                 setPadding(dp(16), dp(6), dp(16), dp(6))
 298:                 isClickable = true
 299:                 background = ripple()
 300:             }
 301:             val icon = ImageView(this).apply {
 302:                 try {
 303:                     setImageDrawable(info.loadIcon(pm))
 304:                 } catch (e: Exception) {
 305:                 }
 306:             }
 307:             row.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
 308:             val name = text(info.loadLabel(pm).toString(), 15f, white, Typeface.DEFAULT).apply {
 309:                 setPadding(dp(12), 0, 0, 0)
 310:             }
 311:             row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
 312:             row.addView(text("Remove", 13f, grey, Typeface.DEFAULT))
 313:             row.setOnClickListener {
 314:                 shown.remove(cn)
 315:                 Prefs.setApps(this, shown)
 316:                 buildApps()
 317:             }
 318:             if (appsBox.childCount > 0) appsBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
 319:             appsBox.addView(row, matchWrap())
 320:         }
 321:         if (shown.size < Prefs.MAX_APPS) {
 322:             val add = text("Add app", 15f, red, Typeface.DEFAULT).apply {
 323:                 minimumHeight = dp(44)
 324:                 gravity = Gravity.CENTER_VERTICAL
 325:                 setPadding(dp(16), dp(6), dp(16), dp(6))
 326:                 isClickable = true
 327:                 background = ripple()
 328:                 setOnClickListener {
 329:                     // persist the cleaned list so removed packages do not linger
 330:                     Prefs.setApps(this@MainActivity, shown)
 331:                     startActivity(Intent(this@MainActivity, AppPickerActivity::class.java))
 332:                 }
 333:             }
 334:             if (appsBox.childCount > 0) appsBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
 335:             appsBox.addView(add, matchWrap())
 336:         }
 337:     }
 338: 
 339:     private fun ripple() = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, ColorDrawable(Color.WHITE))
 340: 
 341:     private fun line(): View = View(this).apply { setBackgroundColor(divider) }
 342: 
 343:     private fun text(s: String, sp: Float, color: Int, face: Typeface): TextView = TextView(this).apply {
 344:         text = s
 345:         setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
 346:         setTextColor(color)
 347:         typeface = face
 348:     }
 349: 
 350:     private fun header(parent: LinearLayout, s: String, right: TextView? = null) {
 351:         firstInSection = true
 352:         val row = LinearLayout(this).apply {
 353:             orientation = LinearLayout.HORIZONTAL
 354:             gravity = Gravity.CENTER_VERTICAL
 355:             setPadding(dp(16), dp(20), dp(16), dp(4))
 356:         }
 357:         row.addView(text(s.uppercase(), 11f, grey, Typeface.DEFAULT_BOLD).apply { letterSpacing = 0.1f },
 358:             LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
 359:         if (right != null) row.addView(right)
 360:         parent.addView(row, matchWrap())
 361:     }
 362: 
 363:     /** Divider between rows inside a section (not before the first). */
 364:     private fun sep(parent: LinearLayout) {
 365:         if (!firstInSection) parent.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
 366:         firstInSection = false
 367:     }
 368: 
 369:     private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
 370: 
 371:     private fun applyAccent(c: Int) {
 372:         sliders.forEach { it.accent = c }
 373:         toggles.forEach { it.accent = c }
 374:         previews.forEach { it.accent = c }
 375:     }
 376: 
 377:     private fun addSlider(
 378:         parent: LinearLayout, label: String, min: Float, max: Float, value: Float,
 379:         fmt: (Float) -> String, preview: String?, save: (Float) -> Unit
 380:     ) {
 381:         sep(parent)
 382:         val head = LinearLayout(this).apply {
 383:             orientation = LinearLayout.HORIZONTAL
 384:             setPadding(dp(16), dp(8), dp(16), 0)
 385:         }
 386:         val valueView = text(fmt(value), 14f, grey, Typeface.DEFAULT)
 387:         head.addView(text(label, 15f, white, Typeface.DEFAULT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
 388:         head.addView(valueView)
 389:         parent.addView(head, matchWrap())
 390: 
 391:         val s = FlatSlider(this).apply {
 392:             this.min = min
 393:             this.max = max
 394:             this.value = value
 395:             accent = Prefs.accent(this@MainActivity)
 396:             setPadding(dp(16), 0, dp(16), 0)
 397:             onStart = { if (preview != null) edit().putString(Prefs.KEY_PREVIEW, preview).apply() }
 398:             onChange = { v -> valueView.text = fmt(v); save(v) }
 399:             onStop = { if (preview != null) edit().putString(Prefs.KEY_PREVIEW, Prefs.PREVIEW_NONE).apply() }
 400:         }
 401:         sliders.add(s)
 402:         parent.addView(s, matchWrap())
 403:     }
 404: 
 405:     private fun addSwitch(parent: LinearLayout, label: String, on: Boolean, save: (Boolean) -> Unit) {
 406:         sep(parent)
 407:         val row = LinearLayout(this).apply {
 408:             orientation = LinearLayout.HORIZONTAL
 409:             gravity = Gravity.CENTER_VERTICAL
 410:             minimumHeight = dp(44)
 411:             setPadding(dp(16), dp(4), dp(16), dp(4))
 412:         }
 413:         row.addView(text(label, 15f, white, Typeface.DEFAULT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
 414:         val t = FlatToggle(this).apply {
 415:             accent = Prefs.accent(this@MainActivity)
 416:             checked = on
 417:             onToggle = save
 418:         }
 419:         toggles.add(t)
 420:         row.addView(t)
 421:         parent.addView(row, matchWrap())
 422:     }
 423: 
 424:     private fun addSwatches(parent: LinearLayout) {
 425:         sep(parent)
 426:         val row = LinearLayout(this).apply {
 427:             orientation = LinearLayout.HORIZONTAL
 428:             gravity = Gravity.CENTER_VERTICAL
 429:             minimumHeight = dp(44)
 430:             setPadding(dp(16), dp(8), dp(16), dp(8))
 431:         }
 432:         val cur = Prefs.accent(this)
 433:         val dots = ArrayList<View>()
 434:         fun mark(sel: Int) {
 435:             for ((i, v) in dots.withIndex()) {
 436:                 val c = Prefs.ACCENTS[i]
 437:                 v.background = GradientDrawable().apply {
 438:                     shape = GradientDrawable.OVAL
 439:                     setColor(c)
 440:                     if (c == sel) setStroke(dp(2), Color.WHITE) else setStroke(0, Color.TRANSPARENT)
 441:                 }
 442:             }
 443:         }
 444:         for ((i, c) in Prefs.ACCENTS.withIndex()) {
 445:             val v = View(this).apply {
 446:                 isClickable = true
 447:                 setOnClickListener {
 448:                     edit().putInt(Prefs.KEY_ACCENT, c).apply()
 449:                     mark(c)
 450:                     applyAccent(c)
 451:                     performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
 452:                 }
 453:             }
 454:             dots.add(v)
 455:             row.addView(v, LinearLayout.LayoutParams(dp(28), dp(28)))
 456:             if (i < Prefs.ACCENTS.size - 1) {
 457:                 row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
 458:             }
 459:         }
 460:         mark(cur)
 461:         parent.addView(row, matchWrap())
 462:     }
 463: 
 464:     /** Row of mini bean previews (no labels); the selected one gets an accent ring. */
 465:     private fun addPicker(parent: LinearLayout, kind: Int, ids: List<String>, cur: String, key: String) {
 466:         sep(parent)
 467:         val row = LinearLayout(this).apply {
 468:             orientation = LinearLayout.HORIZONTAL
 469:             gravity = Gravity.CENTER_VERTICAL
 470:             minimumHeight = dp(44)
 471:             setPadding(dp(16), dp(8), dp(16), dp(8))
 472:         }
 473:         val availDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density - 32f
 474:         val size = ((availDp - 4f * (ids.size - 1)) / ids.size).coerceIn(30f, 44f)
 475:         val mine = ArrayList<IdlePreview>()
 476:         for ((i, id) in ids.withIndex()) {
 477:             val p = IdlePreview(this, id, kind).apply {
 478:                 sizeDp = size
 479:                 accent = Prefs.accent(this@MainActivity)
 480:                 gradient = Prefs.gradient(this@MainActivity)
 481:                 selectedStyle = id == cur
 482:                 isClickable = true
 483:                 setOnClickListener {
 484:                     edit().putString(key, id).apply()
 485:                     mine.forEach { it.selectedStyle = it === this }
 486:                     performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
 487:                 }
 488:             }
 489:             mine.add(p)
 490:             previews.add(p)
 491:             row.addView(p)
 492:             if (i < ids.size - 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
 493:         }
 494:         parent.addView(row, matchWrap())
 495:     }
 496: 
 497:     // ---------------------------------------------------------------- windows list
 498: 
 499:     private var winEntries = ArrayList<WindowsConfig.Entry>()
 500:     private val winRows = ArrayList<View>()
 501:     private lateinit var winBox: LinearLayout
 502: 
 503:     private fun addWindows(parent: LinearLayout) {
 504:         winEntries = ArrayList(WindowsConfig.load(this))
 505:         winBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
 506:         parent.addView(winBox, matchWrap())
 507:         buildWindowRows()
 508:     }
 509: 
 510:     private fun buildWindowRows() {
 511:         winBox.removeAllViews()
 512:         winRows.clear()
 513:         val rowH = dp(48)
 514:         val accent = Prefs.accent(this)
 515:         for (e in winEntries) {
 516:             val row = LinearLayout(this).apply {
 517:                 orientation = LinearLayout.HORIZONTAL
 518:                 gravity = Gravity.CENTER_VERTICAL
 519:                 setPadding(dp(16), 0, dp(16), 0)
 520:                 tag = e.id
 521:             }
 522:             val icon = ImageView(this).apply {
 523:                 setImageResource(WindowsConfig.icon(e.id))
 524:                 setColorFilter(if (e.on) white else grey)
 525:             }
 526:             row.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
 527:             val label = text(WindowsConfig.label(e.id), 15f, if (e.on) white else grey, Typeface.DEFAULT).apply {
 528:                 setPadding(dp(14), 0, 0, 0)
 529:             }
 530:             row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
 531:             val t = FlatToggle(this).apply {
 532:                 this.accent = accent
 533:                 checked = e.on
 534:                 onToggle = { on ->
 535:                     val idx = winEntries.indexOfFirst { it.id == e.id }
 536:                     if (idx >= 0) winEntries[idx] = WindowsConfig.Entry(e.id, on)
 537:                     icon.setColorFilter(if (on) white else grey)
 538:                     label.setTextColor(if (on) white else grey)
 539:                     WindowsConfig.save(this@MainActivity, winEntries)
 540:                 }
 541:             }
 542:             toggles.add(t)
 543:             row.addView(t)
 544:             attachDrag(row)
 545:             winBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH))
 546:             winRows.add(row)
 547:         }
 548:     }
 549: 
 550:     private fun attachDrag(row: View) {
 551:         val rowH = dp(48)
 552:         var dragging = false
 553:         var orig = 0
 554:         var downY = 0f
 555:         var target = 0
 556:         row.setOnLongClickListener {
 557:             orig = winRows.indexOf(row)
 558:             if (orig < 0) return@setOnLongClickListener false
 559:             dragging = true
 560:             target = orig
 561:             downY = lastRawY
 562:             row.parent?.requestDisallowInterceptTouchEvent(true)
 563:             row.setBackgroundColor(divider)
 564:             row.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
 565:             true
 566:         }
 567:         row.setOnTouchListener { _, e ->
 568:             when (e.actionMasked) {
 569:                 MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> if (!dragging) lastRawY = e.rawY
 570:             }
 571:             if (!dragging) return@setOnTouchListener false
 572:             when (e.actionMasked) {
 573:                 MotionEvent.ACTION_MOVE -> {
 574:                     val last = winRows.size - 1
 575:                     val dy = (e.rawY - downY).coerceIn(-orig * rowH.toFloat(), (last - orig) * rowH.toFloat())
 576:                     row.translationY = dy
 577:                     val t = Math.round(orig + dy / rowH).coerceIn(0, last)
 578:                     if (t != target) {
 579:                         target = t
 580:                         row.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
 581:                     }
 582:                     for ((i, r) in winRows.withIndex()) {
 583:                         if (r === row) continue
 584:                         r.translationY = when {
 585:                             i in (orig + 1)..target -> -rowH.toFloat()
 586:                             i in target until orig -> rowH.toFloat()
 587:                             else -> 0f
 588:                         }
 589:                     }
 590:                     true
 591:                 }
 592:                 MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
 593:                     dragging = false
 594:                     row.isPressed = false
 595:                     if (target != orig) {
 596:                         val moved = winEntries.removeAt(orig)
 597:                         winEntries.add(target, moved)
 598:                         WindowsConfig.save(this, winEntries)
 599:                     }
 600:                     buildWindowRows()
 601:                     true
 602:                 }
 603:                 else -> true
 604:             }
 605:         }
 606:     }
 607: 
 608:     private var lastRawY = 0f
 609: 
 610:     private fun isOverlayServiceEnabled(): Boolean {
 611:         val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
 612:             ?: return false
 613:         val cn = ComponentName(this, EdgeService::class.java)
 614:         return enabled.contains(cn.flattenToString()) || enabled.contains(cn.flattenToShortString())
 615:     }
 616: 
 617:     private fun isMediaListenerEnabled(): Boolean {
 618:         val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
 619:         return enabled.contains(packageName)
 620:     }
 621: 
 622:     private fun isDndGranted(): Boolean {
 623:         val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
 624:         return nm.isNotificationPolicyAccessGranted
 625:     }
 626: 
 627:     private fun isAssistSwipeOn(): Boolean =
 628:         Settings.Secure.getInt(contentResolver, "navigation_google_assist", 0) != 0
 629: 
 630:     private fun setWhiteWallpaper() {
 631:         try {
 632:             val bmp = android.graphics.Bitmap.createBitmap(108, 240, android.graphics.Bitmap.Config.ARGB_8888)
 633:             bmp.eraseColor(Color.WHITE)
 634:             android.app.WallpaperManager.getInstance(this)
 635:                 .setBitmap(bmp, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
 636:             bmp.recycle()
 637:         } catch (e: Exception) {
 638:             android.util.Log.w("DynamicEdge", "wallpaper failed", e)
 639:         }
 640:     }
 641: }
===== TouchRegion.kt =====
   1: package com.dynedge
   2: 
   3: import android.graphics.Region
   4: import android.util.Log
   5: import android.view.View
   6: import android.view.ViewTreeObserver
   7: import java.lang.reflect.Field
   8: import java.lang.reflect.InvocationHandler
   9: import java.lang.reflect.Method
  10: import java.lang.reflect.Proxy
  11: 
  12: /**
  13:  * Restricts the window's touchable area to an arbitrary Region through the hidden
  14:  * ViewTreeObserver.OnComputeInternalInsetsListener (TOUCHABLE_INSETS_REGION).
  15:  */
  16: class TouchRegion private constructor(
  17:     private val view: View,
  18:     private val setTouchableInsets: Method,
  19:     private val touchableRegion: Field,
  20:     private val addMethod: Method,
  21:     private val removeMethod: Method
  22: ) {
  23:     private val region = Region()
  24:     private var listener: Any? = null
  25:     private var observer: ViewTreeObserver? = null
  26: 
  27:     private fun attach(): Boolean {
  28:         val handler = InvocationHandler { p, m, args ->
  29:             when (m.name) {
  30:                 "onComputeInternalInsets" -> {
  31:                     val info = args?.getOrNull(0)
  32:                     if (info != null) {
  33:                         try {
  34:                             setTouchableInsets.invoke(info, TOUCHABLE_INSETS_REGION)
  35:                             (touchableRegion.get(info) as? Region)?.set(region)
  36:                         } catch (t: Throwable) {
  37:                             logOnce(t)
  38:                         }
  39:                     }
  40:                     null
  41:                 }
  42:                 "equals" -> p === args?.getOrNull(0)
  43:                 "hashCode" -> System.identityHashCode(p)
  44:                 "toString" -> "TouchRegion@" + Integer.toHexString(System.identityHashCode(p))
  45:                 else -> null
  46:             }
  47:         }
  48:         val cls = addMethod.parameterTypes[0]
  49:         val l = Proxy.newProxyInstance(cls.classLoader, arrayOf(cls), handler)
  50:         val vto = view.viewTreeObserver
  51:         addMethod.invoke(vto, l)
  52:         listener = l
  53:         observer = vto
  54:         return true
  55:     }
  56: 
  57:     /** Copies [r] and triggers a traversal so the insets are re-reported. */
  58:     fun set(r: Region) {
  59:         region.set(r)
  60:         view.invalidate()
  61:     }
  62: 
  63:     fun uninstall() {
  64:         val l = listener ?: return
  65:         listener = null
  66:         try {
  67:             val cur = view.viewTreeObserver
  68:             if (cur.isAlive) removeMethod.invoke(cur, l)
  69:             val old = observer
  70:             if (old != null && old !== cur && old.isAlive) removeMethod.invoke(old, l)
  71:         } catch (t: Throwable) {
  72:             logOnce(t)
  73:         }
  74:         observer = null
  75:     }
  76: 
  77:     companion object {
  78:         private const val TAG = "DynamicEdge"
  79:         private const val TOUCHABLE_INSETS_REGION = 3
  80:         private var logged = false
  81: 
  82:         private var cached = false
  83:         private var mSetInsets: Method? = null
  84:         private var fRegion: Field? = null
  85:         private var mAdd: Method? = null
  86:         private var mRemove: Method? = null
  87: 
  88:         private fun logOnce(t: Throwable) {
  89:             if (!logged) {
  90:                 logged = true
  91:                 Log.w(TAG, "TouchRegion reflection failed, falling back to full window", t)
  92:             }
  93:         }
  94: 
  95:         private fun lookup(): Boolean {
  96:             if (cached) return mSetInsets != null
  97:             cached = true
  98:             try {
  99:                 val listenerCls = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
 100:                 val infoCls = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
 101:                 val set = infoCls.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
 102:                 val field = infoCls.getField("touchableRegion")
 103:                 val add = ViewTreeObserver::class.java.getMethod("addOnComputeInternalInsetsListener", listenerCls)
 104:                 val remove = ViewTreeObserver::class.java.getMethod("removeOnComputeInternalInsetsListener", listenerCls)
 105:                 mSetInsets = set
 106:                 fRegion = field
 107:                 mAdd = add
 108:                 mRemove = remove
 109:                 return true
 110:             } catch (t: Throwable) {
 111:                 logOnce(t)
 112:                 return false
 113:             }
 114:         }
 115: 
 116:         fun install(view: View): TouchRegion? {
 117:             if (!lookup()) return null
 118:             return try {
 119:                 val tr = TouchRegion(view, mSetInsets!!, fRegion!!, mAdd!!, mRemove!!)
 120:                 tr.attach()
 121:                 tr
 122:             } catch (t: Throwable) {
 123:                 logOnce(t)
 124:                 null
 125:             }
 126:         }
 127:     }
 128: }
===== Spring.kt =====
   1: package com.dynedge
   2: 
   3: import kotlin.math.abs
   4: import kotlin.math.cos
   5: import kotlin.math.exp
   6: import kotlin.math.sin
   7: import kotlin.math.sqrt
   8: 
   9: /**
  10:  * Damped spring with unit mass, integrated with the exact closed-form solution
  11:  * (under-, critically- and over-damped). Units: px and px/s. No allocations.
  12:  */
  13: class Spring(
  14:     var value: Float = 0f,
  15:     var stiffness: Float = 400f,
  16:     var dampingRatio: Float = 0.8f
  17: ) {
  18:     var velocity: Float = 0f
  19:     var target: Float = value
  20: 
  21:     fun snapTo(v: Float) {
  22:         value = v
  23:         target = v
  24:         velocity = 0f
  25:     }
  26: 
  27:     fun isSettled(posEps: Float, velEps: Float): Boolean =
  28:         abs(value - target) < posEps && abs(velocity) < velEps
  29: 
  30:     fun step(dtSec: Float) {
  31:         if (dtSec <= 0f) return
  32:         val t = dtSec.toDouble()
  33:         val w = sqrt(stiffness.toDouble().coerceAtLeast(1e-6))
  34:         val z = dampingRatio.toDouble().coerceAtLeast(0.0)
  35:         val x0 = (value - target).toDouble()
  36:         val v0 = velocity.toDouble()
  37:         val x: Double
  38:         val v: Double
  39:         if (z < 0.999) {
  40:             val a = z * w
  41:             val wd = w * sqrt(1.0 - z * z)
  42:             val b = (v0 + a * x0) / wd
  43:             val e = exp(-a * t)
  44:             val c = cos(wd * t)
  45:             val s = sin(wd * t)
  46:             x = e * (x0 * c + b * s)
  47:             v = e * ((-a * x0 + b * wd) * c + (-a * b - x0 * wd) * s)
  48:         } else if (z <= 1.001) {
  49:             val b = v0 + w * x0
  50:             val e = exp(-w * t)
  51:             x = (x0 + b * t) * e
  52:             v = (b - w * (x0 + b * t)) * e
  53:         } else {
  54:             val sq = sqrt(z * z - 1.0)
  55:             val r1 = -w * (z - sq)
  56:             val r2 = -w * (z + sq)
  57:             val c2 = (v0 - r1 * x0) / (r2 - r1)
  58:             val c1 = x0 - c2
  59:             val e1 = exp(r1 * t)
  60:             val e2 = exp(r2 * t)
  61:             x = c1 * e1 + c2 * e2
  62:             v = c1 * r1 * e1 + c2 * r2 * e2
  63:         }
  64:         value = (target + x).toFloat()
  65:         velocity = v.toFloat()
  66:     }
  67: }
===== IdleVisualizer.kt =====
   1: package com.dynedge
   2: 
   3: import android.content.Context
   4: import android.content.pm.PackageManager
   5: import android.media.audiofx.Visualizer
   6: import kotlin.math.max
   7: import kotlin.math.min
   8: import kotlin.math.sin
   9: import kotlin.math.sqrt
  10: 
  11: /**
  12:  * Four-band level meter for the idle bean. Uses the global output mix (Visualizer session 0, needs
  13:  * RECORD_AUDIO) and falls back to a synthetic animation when that is unavailable or silent.
  14:  * start()/stop() create and release the Visualizer; tick() runs from the owner's throttled frame loop.
  15:  */
  16: class IdleVisualizer(private val ctx: Context) {
  17: 
  18:     /** Smoothed bar heights, 0..1. */
  19:     val levels = FloatArray(4)
  20: 
  21:     private val target = FloatArray(4)
  22:     private val mags = FloatArray(4)
  23:     private var viz: Visualizer? = null
  24:     private var peak = 6f
  25:     private var lastRealMs = 0L
  26:     private var startMs = 0L
  27:     private var phase = 0f
  28:     private var seed = 12345
  29: 
  30:     private val listener = object : Visualizer.OnDataCaptureListener {
  31:         override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
  32: 
  33:         override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
  34:             if (fft != null) reduce(fft)
  35:         }
  36:     }
  37: 
  38:     fun start() {
  39:         if (viz != null) return
  40:         startMs = 0L
  41:         phase = 0f
  42:         if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
  43:         var v: Visualizer? = null
  44:         try {
  45:             v = Visualizer(0)
  46:             v.setEnabled(false)
  47:             v.setCaptureSize(Visualizer.getCaptureSizeRange()[0])
  48:             v.setDataCaptureListener(listener, min(20000, Visualizer.getMaxCaptureRate()), false, true)
  49:             v.setEnabled(true)
  50:             viz = v
  51:         } catch (e: Throwable) {
  52:             try {
  53:                 v?.setEnabled(false)
  54:                 v?.release()
  55:             } catch (e2: Throwable) {
  56:             }
  57:             viz = null
  58:         }
  59:     }
  60: 
  61:     fun stop() {
  62:         val v = viz ?: return
  63:         viz = null
  64:         try {
  65:             v.setEnabled(false)
  66:             v.setDataCaptureListener(null, 0, false, false)
  67:             v.release()
  68:         } catch (e: Throwable) {
  69:         }
  70:     }
  71: 
  72:     /** Bins: [re0 (DC), re(n/2), re1, im1, re2, im2, ...]. Four bands, low to high. */
  73:     private fun reduce(fft: ByteArray) {
  74:         val n = fft.size / 2
  75:         if (n < 8) return
  76:         var top = 0f
  77:         var lo = 1
  78:         for (b in 0 until 4) {
  79:             val hi = max(lo + 1, min(n, EDGES[b] * n / 64))
  80:             var sum = 0f
  81:             for (k in lo until hi) {
  82:                 val re = fft[2 * k].toFloat()
  83:                 val im = fft[2 * k + 1].toFloat()
  84:                 sum += sqrt(re * re + im * im)
  85:             }
  86:             mags[b] = sum / (hi - lo)
  87:             if (mags[b] > top) top = mags[b]
  88:             lo = hi
  89:         }
  90:         peak = max(max(peak * 0.97f, top), 6f)
  91:         for (b in 0 until 4) target[b] = min(1f, mags[b] / peak * WEIGHT[b])
  92:         if (top > 3f) lastRealMs = System.currentTimeMillis()
  93:     }
  94: 
  95:     fun tick(dt: Float, nowMs: Long) {
  96:         if (startMs == 0L) startMs = nowMs
  97:         phase += dt
  98:         val real = viz != null && System.currentTimeMillis() - lastRealMs < 1200L && nowMs - startMs > 0L
  99:         for (b in 0 until 4) {
 100:             val t: Float
 101:             if (real) {
 102:                 t = target[b]
 103:             } else {
 104:                 seed = seed * 1103515245 + 12345
 105:                 val noise = ((seed ushr 16) and 0x7FFF) / 32767f
 106:                 t = (0.5f + 0.38f * sin(phase * SPEED[b] + b * 1.7f) + 0.12f * noise).coerceIn(0.08f, 1f)
 107:             }
 108:             val k = if (t > levels[b]) 0.65f else 0.22f
 109:             levels[b] += (t - levels[b]) * min(1f, k * dt * 30f)
 110:         }
 111:     }
 112: 
 113:     private companion object {
 114:         val EDGES = intArrayOf(3, 7, 17, 48)
 115:         val WEIGHT = floatArrayOf(0.9f, 1.1f, 1.5f, 2.2f)
 116:         val SPEED = floatArrayOf(6.3f, 8.1f, 5.2f, 9.4f)
 117:     }
 118: }
===== IdleMediaWatch.kt =====
   1: package com.dynedge
   2: 
   3: import android.content.ComponentName
   4: import android.content.Context
   5: import android.media.MediaMetadata
   6: import android.media.session.MediaController
   7: import android.media.session.MediaSessionManager
   8: import android.media.session.PlaybackState
   9: import android.os.Handler
  10: import android.os.Looper
  11: 
  12: /**
  13:  * Light "is anything playing" watch used while the widget is closed: active-session changes plus a
  14:  * playback-state callback per session. No metadata or art work. Registered only while the screen is on.
  15:  */
  16: class IdleMediaWatch(ctx: Context, private val onChange: (Boolean) -> Unit) {
  17: 
  18:     private val app: Context = ctx.applicationContext ?: ctx
  19:     private val main = Handler(Looper.getMainLooper())
  20:     private val msm = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
  21:     private val component = ComponentName(app, MediaListener::class.java)
  22:     private var started = false
  23:     private val controllers = ArrayList<MediaController>()
  24:     private val callbacks = ArrayList<MediaController.Callback>()
  25: 
  26:     var playing = false
  27:         private set
  28: 
  29:     /** Called with the new track title when the metadata of a session changes (track change). */
  30:     var onTrack: ((String?) -> Unit)? = null
  31: 
  32:     private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
  33:         track(list ?: emptyList())
  34:     }
  35: 
  36:     private inner class Cb : MediaController.Callback() {
  37:         override fun onPlaybackStateChanged(state: PlaybackState?) {
  38:             recompute()
  39:         }
  40: 
  41:         override fun onMetadataChanged(metadata: MediaMetadata?) {
  42:             if (started) onTrack?.invoke(metadata?.getString(MediaMetadata.METADATA_KEY_TITLE))
  43:         }
  44: 
  45:         override fun onSessionDestroyed() {
  46:             recompute()
  47:         }
  48:     }
  49: 
  50:     fun start() {
  51:         if (started) return
  52:         started = true
  53:         try {
  54:             msm?.addOnActiveSessionsChangedListener(sessionsListener, component, main)
  55:         } catch (e: Exception) {
  56:         }
  57:         val list = try {
  58:             msm?.getActiveSessions(component) ?: emptyList()
  59:         } catch (e: Exception) {
  60:             emptyList<MediaController>()
  61:         }
  62:         track(list)
  63:     }
  64: 
  65:     fun stop() {
  66:         if (!started) return
  67:         started = false
  68:         try {
  69:             msm?.removeOnActiveSessionsChangedListener(sessionsListener)
  70:         } catch (e: Exception) {
  71:         }
  72:         untrack()
  73:         playing = false
  74:     }
  75: 
  76:     private fun untrack() {
  77:         for (i in controllers.indices) {
  78:             try {
  79:                 controllers[i].unregisterCallback(callbacks[i])
  80:             } catch (e: Exception) {
  81:             }
  82:         }
  83:         controllers.clear()
  84:         callbacks.clear()
  85:     }
  86: 
  87:     private fun track(list: List<MediaController>) {
  88:         untrack()
  89:         for (c in list) {
  90:             val cb = Cb()
  91:             try {
  92:                 c.registerCallback(cb, main)
  93:                 controllers.add(c)
  94:                 callbacks.add(cb)
  95:             } catch (e: Exception) {
  96:             }
  97:         }
  98:         recompute()
  99:     }
 100: 
 101:     private fun recompute() {
 102:         var p = false
 103:         for (c in controllers) {
 104:             val st = try {
 105:                 c.playbackState?.state
 106:             } catch (e: Exception) {
 107:                 null
 108:             }
 109:             if (st == PlaybackState.STATE_PLAYING) {
 110:                 p = true
 111:                 break
 112:             }
 113:         }
 114:         if (p != playing) {
 115:             playing = p
 116:             if (started) onChange(p)
 117:         }
 118:     }
 119: }
===== WinMedia.kt =====
   1: package com.dynedge
   2: 
   3: import android.graphics.Bitmap
   4: import android.graphics.BitmapShader
   5: import android.graphics.Canvas
   6: import android.graphics.Color
   7: import android.graphics.Matrix
   8: import android.graphics.Paint
   9: import android.graphics.Path
  10: import android.graphics.Rect
  11: import android.graphics.RectF
  12: import android.graphics.Shader
  13: import android.graphics.Typeface
  14: import android.text.TextPaint
  15: import android.text.TextUtils
  16: import android.view.HapticFeedbackConstants
  17: import kotlin.math.cos
  18: import kotlin.math.max
  19: import kotlin.math.min
  20: import kotlin.math.sin
  21: import kotlin.math.sqrt
  22: 
  23: /** MEDIA window: art circle (tap = play/pause), prev/next on the ring, title/artist on arcs, progress arc. */
  24: internal class WinMedia(h: EdgeView) : Win(h, "media", 3) {
  25: 
  26:     private val media = MediaBridge(h.context, h.dp(76f).toInt()) { onMedia() }
  27: 
  28:     private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  29:     private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
  30:         color = Color.WHITE
  31:         textSize = h.sp(14f)
  32:         typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
  33:     }
  34:     private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
  35:         color = EdgeView.C_GREY
  36:         textSize = h.sp(12f)
  37:     }
  38:     private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
  39:         color = EdgeView.C_GREY
  40:         textAlign = Paint.Align.CENTER
  41:         textSize = h.sp(10f)
  42:     }
  43: 
  44:     // geometry
  45:     var artRad = 0f
  46:         private set
  47:     private val artIconRect = Rect()
  48:     private val playIconRect = Rect()
  49:     private val mArcOval = RectF()
  50:     private var mArcR = 0f
  51:     private val titlePath = Path()
  52:     private val artistPath = Path()
  53:     private var titleLen = 0f
  54:     private var artistLen = 0f
  55:     private var elapsedX = 0f
  56:     private var elapsedY = 0f
  57:     private var totalX = 0f
  58:     private var totalY = 0f
  59: 
  60:     // state
  61:     var playing = false
  62:         private set
  63:     private var hasSession = false
  64:     private var rawTitle: String? = null
  65:     private var rawArtist: String? = null
  66:     private var titleShown: String = ""
  67:     private var artistShown: String = ""
  68:     private var titleOff = 0f
  69:     private var artistOff = 0f
  70:     private var artBmp: Bitmap? = null
  71:     private val shaderMatrix = Matrix()
  72:     private var progressFrac = 0f
  73:     private var lastElapsedSec = -1L
  74:     private var lastTotalSec = -1L
  75:     private var elapsedStr: String = "0:00"
  76:     private var totalStr: String = "0:00"
  77:     private val sb = StringBuilder(8)
  78:     private var seekActive = false
  79:     private var seekFrac = 0f
  80:     private var shown = false
  81: 
  82:     val artX: Float get() = cx[ART]
  83:     val artY: Float get() = cy[ART]
  84: 
  85:     private val tickRunnable = object : Runnable {
  86:         override fun run() {
  87:             if (shown && started) {
  88:                 refresh()
  89:                 h.invalidate()
  90:                 if (playing) h.postDelayed(this, 1000L)
  91:             }
  92:         }
  93:     }
  94: 
  95:     override fun layout(hw: Float, brad: Float) {
  96:         val artD = (0.30f * rad).coerceIn(dp(52f), dp(76f))
  97:         artRad = artD / 2f
  98:         val rArt = 0.52f * rad
  99:         place(ART, rArt, 45f, artRad)
 100:         place(PREV, rArt, 14f, min(brad, dp(18f)))
 101:         place(NEXT, rArt, 76f, min(brad, dp(18f)))
 102:         val artEdge = sqrt(cx[ART] * cx[ART] + cy[ART] * cy[ART]) + artRad
 103:         val artistR = max(0.72f * rad, artEdge + dp(8f))
 104:         val titleR = artistR + dp(17f)
 105:         mArcR = min(max(0.88f * rad, titleR + dp(29f)), rad - dp(10f))
 106:         mArcOval.set(-mArcR, -mArcR, mArcR, mArcR)
 107:         arcPath(titlePath, titleR, MT_A0, MT_A1)
 108:         arcPath(artistPath, artistR, MT_A0, MT_A1)
 109:         val span = Math.toRadians((MT_A1 - MT_A0).toDouble()).toFloat()
 110:         titleLen = titleR * span
 111:         artistLen = artistR * span
 112: 
 113:         val ih = dp(10f).toInt()
 114:         artIconRect.set((cx[ART] - ih).toInt(), (cy[ART] - ih).toInt(), (cx[ART] + ih).toInt(), (cy[ART] + ih).toInt())
 115:         val ph = dp(9f).toInt()
 116:         playIconRect.set((cx[ART] - ph).toInt(), (cy[ART] - ph).toInt(), (cx[ART] + ph).toInt(), (cy[ART] + ph).toInt())
 117: 
 118:         val tr = mArcR - dp(22f)
 119:         val a8 = Math.toRadians(8.0)
 120:         val a82 = Math.toRadians(82.0)
 121:         elapsedX = -tr * cos(a8).toFloat()
 122:         elapsedY = -tr * sin(a8).toFloat() + dp(3.5f)
 123:         totalX = -tr * cos(a82).toFloat()
 124:         totalY = -tr * sin(a82).toFloat() + dp(3.5f)
 125: 
 126:         val bandHalf = max(artRad, dp(18f)) + dp(4f)
 127:         addBand(rArt - bandHalf, rArt + bandHalf, 4f, 86f, 3)
 128:         addBand(artistR - dp(5f), titleR + dp(17f), 14f, 76f, 1)
 129:         addBand(mArcR - dp(11f), min(mArcR + dp(11f), rad - dp(2.5f)), 4f, 86f, 1)
 130: 
 131:         // force caches to rebuild against the new geometry
 132:         rawTitle = null
 133:         rawArtist = null
 134:         artBmp = null
 135:         lastElapsedSec = -1L
 136:         lastTotalSec = -1L
 137:         if (started) refresh()
 138:     }
 139: 
 140:     override fun start() {
 141:         media.start()
 142:         refresh()
 143:     }
 144: 
 145:     override fun stop() {
 146:         shown = false
 147:         h.removeCallbacks(tickRunnable)
 148:         media.stop()
 149:     }
 150: 
 151:     override fun onShown(on: Boolean) {
 152:         shown = on
 153:         h.removeCallbacks(tickRunnable)
 154:         if (on && playing) h.postDelayed(tickRunnable, 1000L)
 155:     }
 156: 
 157:     private fun onMedia() {
 158:         if (!started) return
 159:         refresh()
 160:         h.invalidate()
 161:         if (shown) {
 162:             h.removeCallbacks(tickRunnable)
 163:             if (playing) h.postDelayed(tickRunnable, 1000L)
 164:         }
 165:     }
 166: 
 167:     private fun refresh() {
 168:         dirty = true
 169:         val has = media.hasSession
 170:         hasSession = has
 171:         val newTitle: String
 172:         val newArtist: String
 173:         if (has) {
 174:             val t = media.title
 175:             val idleSession = t.isNullOrBlank() && !media.isPlaying
 176:             newTitle = if (!t.isNullOrBlank()) t else if (idleSession) "Not playing" else "Playing"
 177:             newArtist = media.artist ?: if (idleSession) "Tap play to resume" else ""
 178:         } else {
 179:             newTitle = "Not playing"
 180:             newArtist = "Tap play to resume"
 181:         }
 182:         if (newTitle != rawTitle) {
 183:             rawTitle = newTitle
 184:             titleShown = TextUtils.ellipsize(newTitle, titlePaint, titleLen, TextUtils.TruncateAt.END).toString()
 185:             titleOff = (titleLen - titlePaint.measureText(titleShown)) / 2f
 186:         }
 187:         if (newArtist != rawArtist) {
 188:             rawArtist = newArtist
 189:             artistShown = TextUtils.ellipsize(newArtist, artistPaint, artistLen, TextUtils.TruncateAt.END).toString()
 190:             artistOff = (artistLen - artistPaint.measureText(artistShown)) / 2f
 191:         }
 192:         val b = if (has) media.art else null
 193:         if (b !== artBmp) {
 194:             artBmp = b
 195:             if (b == null) {
 196:                 artPaint.shader = null
 197:             } else {
 198:                 val s = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
 199:                 shaderMatrix.setScale(2f * artRad / b.width, 2f * artRad / b.height)
 200:                 shaderMatrix.postTranslate(-artRad, -artRad)
 201:                 s.setLocalMatrix(shaderMatrix)
 202:                 artPaint.shader = s
 203:             }
 204:         }
 205:         playing = has && media.isPlaying
 206:         if (seekActive) return
 207:         val dur = if (has) media.durationMs else 0L
 208:         val pos = if (has) media.positionMs() else 0L
 209:         progressFrac = if (dur > 0L) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
 210:         val es = pos / 1000L
 211:         if (es != lastElapsedSec) {
 212:             lastElapsedSec = es
 213:             elapsedStr = fmt(es)
 214:         }
 215:         val ts = dur / 1000L
 216:         if (ts != lastTotalSec) {
 217:             lastTotalSec = ts
 218:             totalStr = fmt(ts)
 219:         }
 220:     }
 221: 
 222:     private fun fmt(totalSec: Long): String {
 223:         val s = if (totalSec < 0L) 0L else totalSec
 224:         sb.setLength(0)
 225:         sb.append(s / 60L).append(':')
 226:         val r = s % 60L
 227:         if (r < 10L) sb.append('0')
 228:         sb.append(r)
 229:         return sb.toString()
 230:     }
 231: 
 232:     override fun draw(c: Canvas, a: Float) {
 233:         val ai = (255f * a + 0.5f).toInt()
 234:         val fill = h.fillP
 235:         // art circle (tap = play/pause)
 236:         run {
 237:             val x = cx[ART]
 238:             val y = cy[ART]
 239:             val ps = isPressed(ART)
 240:             if (ps) {
 241:                 c.save()
 242:                 c.scale(EdgeView.PRESS_SCALE, EdgeView.PRESS_SCALE, x, y)
 243:             }
 244:             if (artBmp != null) {
 245:                 c.save()
 246:                 c.translate(x, y)
 247:                 artPaint.alpha = ai
 248:                 c.drawCircle(0f, 0f, artRad, artPaint)
 249:                 c.restore()
 250:             } else {
 251:                 fill.color = EdgeView.C_FILL
 252:                 fill.alpha = ai
 253:                 c.drawCircle(x, y, artRad, fill)
 254:             }
 255:             fill.color = Color.BLACK
 256:             fill.alpha = (255f * 0.42f * a + 0.5f).toInt()
 257:             c.drawCircle(x, y, dp(15f), fill)
 258:             val pi = h.icon(if (playing) R.drawable.ic_pause else R.drawable.ic_play, false)
 259:             pi.bounds = playIconRect
 260:             pi.alpha = ai
 261:             pi.draw(c)
 262:             if (ps) {
 263:                 h.ringPaint.alpha = ai
 264:                 c.drawCircle(x, y, artRad + dp(3f), h.ringPaint)
 265:                 c.restore()
 266:             }
 267:         }
 268:         drawBtn(c, PREV, EdgeView.C_FILL, R.drawable.ic_skip_back, false, a, a)
 269:         drawBtn(c, NEXT, EdgeView.C_FILL, R.drawable.ic_skip_forward, false, a, a)
 270: 
 271:         // title / artist on arcs
 272:         titlePaint.alpha = ai
 273:         artistPaint.alpha = ai
 274:         timePaint.alpha = ai
 275:         c.drawTextOnPath(titleShown, titlePath, titleOff, 0f, titlePaint)
 276:         c.drawTextOnPath(artistShown, artistPath, artistOff, 0f, artistPaint)
 277: 
 278:         // progress arc
 279:         val ap = h.arcPaint
 280:         ap.strokeWidth = dp(3.5f)
 281:         ap.color = EdgeView.C_TRACK
 282:         ap.alpha = ai
 283:         c.drawArc(mArcOval, 180f + SL_A0, SL_A1 - SL_A0, false, ap)
 284:         if (progressFrac > 0.002f) {
 285:             ap.color = h.accent
 286:             ap.alpha = ai
 287:             c.drawArc(mArcOval, 180f + SL_A0, (SL_A1 - SL_A0) * progressFrac, false, ap)
 288:         }
 289:         val ang = Math.toRadians((SL_A0 + (SL_A1 - SL_A0) * progressFrac).toDouble())
 290:         fill.color = h.accent
 291:         fill.alpha = ai
 292:         c.drawCircle(-mArcR * cos(ang).toFloat(), -mArcR * sin(ang).toFloat(), dp(4.5f), fill)
 293:         c.drawText(elapsedStr, elapsedX, elapsedY, timePaint)
 294:         c.drawText(totalStr, totalX, totalY, timePaint)
 295:     }
 296: 
 297:     // ---------------------------------------------------------------- touch
 298:     override fun hit(lx: Float, ly: Float): Int {
 299:         beginHit()
 300:         consider(ART, lx, ly); consider(PREV, lx, ly); consider(NEXT, lx, ly)
 301:         return bestHit()
 302:     }
 303: 
 304:     override fun longPressable(item: Int): Boolean = item == ART
 305: 
 306:     override fun onTap(item: Int) {
 307:         when (item) {
 308:             ART -> media.playPause()
 309:             PREV -> media.prev()
 310:             NEXT -> media.next()
 311:         }
 312:     }
 313: 
 314:     override fun onLongPress(item: Int): Boolean = if (item == ART) media.openPlayer() else false
 315: 
 316:     override fun capture(lx: Float, ly: Float): Boolean {
 317:         if (!started || !media.canSeek || media.durationMs <= 0L) return false
 318:         polar(lx, ly)
 319:         if (!(kotlin.math.abs(pr - mArcR) < dp(22f) && pa > SL_A0 - 4f && pa < SL_A1 + 4f)) return false
 320:         seekActive = true
 321:         return true
 322:     }
 323: 
 324:     override fun dragTo(lx: Float, ly: Float) {
 325:         polar(lx, ly)
 326:         val f = ((pa - SL_A0) / (SL_A1 - SL_A0)).coerceIn(0f, 1f)
 327:         seekFrac = f
 328:         progressFrac = f
 329:         dirty = true
 330:         val es = (f * media.durationMs / 1000f).toLong()
 331:         if (es != lastElapsedSec) {
 332:             lastElapsedSec = es
 333:             elapsedStr = fmt(es)
 334:         }
 335:         h.invalidate()
 336:     }
 337: 
 338:     override fun dragEnd(cancel: Boolean) {
 339:         if (!seekActive) return
 340:         seekActive = false
 341:         if (!cancel && started) {
 342:             media.seekTo((seekFrac * media.durationMs).toLong())
 343:             h.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
 344:         }
 345:     }
 346: 
 347:     private companion object {
 348:         const val ART = 0
 349:         const val PREV = 1
 350:         const val NEXT = 2
 351:         const val MT_A0 = 16f
 352:         const val MT_A1 = 74f
 353:     }
 354: }
===== WinClipboard.kt =====
   1: package com.dynedge
   2: 
   3: import android.graphics.Canvas
   4: import android.graphics.Color
   5: import android.graphics.Path
   6: import android.graphics.Rect
   7: import android.text.TextPaint
   8: import android.text.TextUtils
   9: import kotlin.math.abs
  10: import kotlin.math.cos
  11: import kotlin.math.min
  12: import kotlin.math.sin
  13: 
  14: /** CLIPBOARD window: up to 4 recent clips as arc rows; tap = paste, long-press = pin, badge = full history. */
  15: internal class WinClipboard(h: EdgeView) : Win(h, "clipboard", 5) {
  16: 
  17:     private val clipPaint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
  18:         color = Color.WHITE
  19:         textSize = h.sp(13f)
  20:     }
  21:     private val rowR = FloatArray(4)
  22:     private val rowLen = FloatArray(4)
  23:     private val rowPath = Array(4) { Path() }
  24:     private val emptyRect = Rect()
  25:     private var rowHalf = 0f
  26:     private var clips: List<ClipStore.Clip> = emptyList()
  27:     private var nClip = 0
  28:     private val clipShown = Array(4) { "" }
  29:     private val clipPin = BooleanArray(4)
  30:     private var store: ClipStore? = null
  31:     private val listener: () -> Unit = {
  32:         if (started) {
  33:             refresh()
  34:             h.invalidate()
  35:         }
  36:     }
  37: 
  38:     override fun layout(hw: Float, brad: Float) {
  39:         for (k in 0 until 4) {
  40:             rowR[k] = min(CLIP_F[k] * rad, rad - dp(24f))
  41:             arcPath(rowPath[k], rowR[k], CL_A0, CL_A1)
  42:             rowLen[k] = rowR[k] * Math.toRadians((CL_A1 - CL_A0).toDouble()).toFloat()
  43:         }
  44:         rowHalf = min(dp(16f), (rowR[1] - rowR[0]) / 2f)
  45:         place(MORE, 0.92f * rad, 80f, dp(13f))
  46:         // empty state icon
  47:         val ea = Math.toRadians(45.0)
  48:         val er = 0.55f * rad
  49:         val ex = (-er * cos(ea)).toInt()
  50:         val ey = (-er * sin(ea)).toInt()
  51:         val eh = dp(18f).toInt()
  52:         emptyRect.set(ex - eh, ey - eh, ex + eh, ey + eh)
  53:         for (k in 0 until 4) addBand(rowR[k] - dp(4f), rowR[k] + dp(18f), 6f, 76f, 1)
  54:         if (started) refresh()
  55:     }
  56: 
  57:     override fun start() {
  58:         val cs = ClipStore.get(h.context)
  59:         store = cs
  60:         cs.addListener(listener)
  61:         refresh()
  62:     }
  63: 
  64:     override fun stop() {
  65:         store?.removeListener(listener)
  66:         store = null
  67:     }
  68: 
  69:     override fun onShown(on: Boolean) {
  70:         // capture the system clipboard when this window becomes the visible one
  71:         if (on) h.requestCapture()
  72:     }
  73: 
  74:     private fun refresh() {
  75:         dirty = true
  76:         val list = (store ?: ClipStore.get(h.context)).list()
  77:         clips = list
  78:         nClip = min(4, list.size)
  79:         for (k in 0 until nClip) {
  80:             val c = list[k]
  81:             clipPin[k] = c.pinned
  82:             val flat = c.text.take(160).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()
  83:             clipShown[k] = TextUtils.ellipsize(flat, clipPaint, rowLen[k], TextUtils.TruncateAt.END).toString()
  84:         }
  85:     }
  86: 
  87:     override fun draw(c: Canvas, a: Float) {
  88:         val ai = (255f * a + 0.5f).toInt()
  89:         if (nClip == 0) {
  90:             val ic = h.icon(R.drawable.ic_clipboard, false)
  91:             ic.bounds = emptyRect
  92:             ic.alpha = (255f * 0.32f * a + 0.5f).toInt()
  93:             ic.draw(c)
  94:         } else {
  95:             clipPaint.alpha = ai
  96:             val ap = h.arcPaint
  97:             val lp = h.linePaint
  98:             for (k in 0 until nClip) {
  99:                 val r = rowR[k]
 100:                 if (isPressed(k)) {
 101:                     val rr = r + dp(5f)
 102:                     h.ovalScratch.set(-rr, -rr, rr, rr)
 103:                     ap.strokeWidth = rowHalf * 2f
 104:                     ap.color = Color.WHITE
 105:                     ap.alpha = (255f * 0.10f * a + 0.5f).toInt()
 106:                     c.drawArc(h.ovalScratch, 180f + CL_A0 - 2f, CL_A1 - CL_A0 + 4f, false, ap)
 107:                 }
 108:                 if (clipPin[k]) {
 109:                     val ta = Math.toRadians(4.5)
 110:                     val ct = cos(ta).toFloat()
 111:                     val st = sin(ta).toFloat()
 112:                     lp.color = h.accent
 113:                     lp.alpha = ai
 114:                     lp.strokeWidth = dp(2.2f)
 115:                     c.drawLine(-(r - dp(3f)) * ct, -(r - dp(3f)) * st, -(r + dp(13f)) * ct, -(r + dp(13f)) * st, lp)
 116:                 }
 117:                 c.drawTextOnPath(clipShown[k], rowPath[k], 0f, 0f, clipPaint)
 118:             }
 119:         }
 120:         // "more" badge: opens the full history
 121:         drawBtn(c, MORE, EdgeView.C_FILL, 0, false, a, a)
 122:         h.fillP.color = Color.WHITE
 123:         h.fillP.alpha = ai
 124:         val mx = cx[MORE]
 125:         val my = cy[MORE]
 126:         for (j in -1..1) c.drawCircle(mx + j * dp(5f), my, dp(1.6f), h.fillP)
 127:     }
 128: 
 129:     // ---------------------------------------------------------------- touch
 130:     override fun hit(lx: Float, ly: Float): Int {
 131:         beginHit()
 132:         consider(MORE, lx, ly)
 133:         if (bestHit() < 0) {
 134:             polar(lx, ly)
 135:             if (pa > CL_A0 - 4f && pa < CL_A1 + 4f) {
 136:                 for (k in 0 until nClip) {
 137:                     if (abs(pr - (rowR[k] + dp(5f))) < rowHalf) {
 138:                         forceHit(k)
 139:                         break
 140:                     }
 141:                 }
 142:             }
 143:         }
 144:         return bestHit()
 145:     }
 146: 
 147:     override fun longPressable(item: Int): Boolean = item in 0..3
 148: 
 149:     override fun onTap(item: Int) {
 150:         if (item == MORE) {
 151:             h.openClipboard()
 152:             h.collapse()
 153:             return
 154:         }
 155:         if (item >= clips.size) return
 156:         val text = clips[item].text
 157:         h.collapse()
 158:         h.postDelayed({
 159:             val svc = h.service
 160:             val ok = svc != null && ClipPaste.paste(svc, text)
 161:             if (!ok) h.showCopied()
 162:         }, 120L)
 163:     }
 164: 
 165:     override fun onLongPress(item: Int): Boolean {
 166:         if (item >= clips.size) return false
 167:         val c = clips[item]
 168:         (store ?: ClipStore.get(h.context)).pin(c.id, !c.pinned)
 169:         return false
 170:     }
 171: 
 172:     private companion object {
 173:         const val MORE = 4
 174:         const val CL_A0 = 8f
 175:         const val CL_A1 = 72f
 176:         val CLIP_F = floatArrayOf(0.36f, 0.52f, 0.68f, 0.84f)
 177:     }
 178: }
===== WinNotifications.kt =====
   1: package com.dynedge
   2: 
   3: import android.graphics.Bitmap
   4: import android.graphics.BitmapShader
   5: import android.graphics.Canvas
   6: import android.graphics.Color
   7: import android.graphics.Matrix
   8: import android.graphics.Paint
   9: import android.graphics.Path
  10: import android.graphics.Rect
  11: import android.graphics.Shader
  12: import android.text.TextPaint
  13: import android.text.TextUtils
  14: import kotlin.math.abs
  15: import kotlin.math.cos
  16: import kotlin.math.min
  17: import kotlin.math.sin
  18: 
  19: /** NOTIFICATIONS window: up to 4 recent notifications as arc rows; tap opens, long-press dismisses. */
  20: internal class WinNotifications(h: EdgeView) : Win(h, "notifications", 4) {
  21: 
  22:     private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
  23:         color = Color.WHITE
  24:         textSize = h.sp(13f)
  25:     }
  26:     private val rowR = FloatArray(4)
  27:     private val rowPath = Array(4) { Path() }
  28:     private val rowLen = FloatArray(4)
  29:     private val iconX = FloatArray(4)
  30:     private val iconY = FloatArray(4)
  31:     private val emptyRect = Rect()
  32:     private var rowHalf = 0f
  33:     private var iconD = 0f
  34:     private var textOff = 0f
  35:     private var items: List<NotifFeed.Item> = emptyList()
  36:     private var n = 0
  37:     private val shown = Array(4) { "" }
  38:     private val iconPaint = Array(4) { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
  39:     private val iconBmp = arrayOfNulls<Bitmap>(4)
  40:     private val iconMatrix = Matrix()
  41: 
  42:     override fun layout(hw: Float, brad: Float) {
  43:         iconD = dp(16f)
  44:         textOff = iconD + dp(8f)
  45:         for (k in 0 until 4) {
  46:             rowR[k] = min(CLIP_F[k] * rad, rad - dp(24f))
  47:             arcPath(rowPath[k], rowR[k], A0, A1)
  48:             rowLen[k] = rowR[k] * Math.toRadians((A1 - A0).toDouble()).toFloat()
  49:             val ia = Math.toRadians((A0 + Math.toDegrees(((iconD / 2f + dp(1f)) / rowR[k]).toDouble())))
  50:             val ro = rowR[k] + dp(5f)
  51:             iconX[k] = -ro * cos(ia).toFloat()
  52:             iconY[k] = -ro * sin(ia).toFloat()
  53:         }
  54:         rowHalf = min(dp(16f), (rowR[1] - rowR[0]) / 2f)
  55:         val ea = Math.toRadians(45.0)
  56:         val er = 0.55f * rad
  57:         val ex = (-er * cos(ea)).toInt()
  58:         val ey = (-er * sin(ea)).toInt()
  59:         val eh = dp(18f).toInt()
  60:         emptyRect.set(ex - eh, ey - eh, ex + eh, ey + eh)
  61:         for (k in 0 until 4) addBand(rowR[k] - dp(4f), rowR[k] + dp(18f), 6f, 76f, 1)
  62:         for (k in 0 until 4) iconBmp[k] = null
  63:         if (started) refresh()
  64:     }
  65: 
  66:     override fun start() {
  67:         NotifFeed.onChange = {
  68:             if (started) {
  69:                 refresh()
  70:                 h.invalidate()
  71:             }
  72:         }
  73:         refresh()
  74:     }
  75: 
  76:     override fun stop() {
  77:         NotifFeed.onChange = null
  78:         for (k in 0 until 4) {
  79:             iconBmp[k] = null
  80:             iconPaint[k].shader = null
  81:         }
  82:         items = emptyList()
  83:         n = 0
  84:     }
  85: 
  86:     private fun refresh() {
  87:         dirty = true
  88:         val list = NotifFeed.recent()
  89:         items = list
  90:         n = min(4, list.size)
  91:         val px = dp(16f).toInt().coerceAtLeast(8)
  92:         val r = iconD / 2f
  93:         for (k in 0 until n) {
  94:             val it = list[k]
  95:             val body = if (it.title.isBlank()) it.text else if (it.text.isBlank()) it.title else it.title + " - " + it.text
  96:             val flat = body.take(160).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()
  97:             shown[k] = TextUtils.ellipsize(flat, textPaint, max0(rowLen[k] - textOff), TextUtils.TruncateAt.END).toString()
  98:             val b = NotifFeed.appIcon(h.context, it.pkg, px)
  99:             if (b !== iconBmp[k]) {
 100:                 iconBmp[k] = b
 101:                 if (b == null) {
 102:                     iconPaint[k].shader = null
 103:                 } else {
 104:                     val s = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
 105:                     iconMatrix.setScale(2f * r / b.width, 2f * r / b.height)
 106:                     iconMatrix.postTranslate(-r, -r)
 107:                     s.setLocalMatrix(iconMatrix)
 108:                     iconPaint[k].shader = s
 109:                 }
 110:             }
 111:         }
 112:         for (k in n until 4) {
 113:             iconBmp[k] = null
 114:             iconPaint[k].shader = null
 115:         }
 116:     }
 117: 
 118:     private fun max0(v: Float) = if (v < 0f) 0f else v
 119: 
 120:     override fun draw(c: Canvas, a: Float) {
 121:         val ai = (255f * a + 0.5f).toInt()
 122:         if (n == 0) {
 123:             val ic = h.icon(R.drawable.ic_bell, false)
 124:             ic.bounds = emptyRect
 125:             ic.alpha = (255f * 0.32f * a + 0.5f).toInt()
 126:             ic.draw(c)
 127:             return
 128:         }
 129:         textPaint.alpha = ai
 130:         val ap = h.arcPaint
 131:         for (k in 0 until n) {
 132:             val r = rowR[k]
 133:             if (isPressed(k)) {
 134:                 val rr = r + dp(5f)
 135:                 h.ovalScratch.set(-rr, -rr, rr, rr)
 136:                 ap.strokeWidth = rowHalf * 2f
 137:                 ap.color = Color.WHITE
 138:                 ap.alpha = (255f * 0.10f * a + 0.5f).toInt()
 139:                 c.drawArc(h.ovalScratch, 180f + A0 - 2f, A1 - A0 + 4f, false, ap)
 140:             }
 141:             val p = iconPaint[k]
 142:             if (p.shader != null) {
 143:                 c.save()
 144:                 c.translate(iconX[k], iconY[k])
 145:                 p.alpha = ai
 146:                 c.drawCircle(0f, 0f, iconD / 2f, p)
 147:                 c.restore()
 148:             }
 149:             c.drawTextOnPath(shown[k], rowPath[k], textOff, 0f, textPaint)
 150:         }
 151:     }
 152: 
 153:     override fun hit(lx: Float, ly: Float): Int {
 154:         beginHit()
 155:         polar(lx, ly)
 156:         if (pa > A0 - 4f && pa < A1 + 4f) {
 157:             for (k in 0 until n) {
 158:                 if (abs(pr - (rowR[k] + dp(5f))) < rowHalf) {
 159:                     forceHit(k)
 160:                     break
 161:                 }
 162:             }
 163:         }
 164:         return bestHit()
 165:     }
 166: 
 167:     override fun longPressable(item: Int): Boolean = true
 168: 
 169:     override fun onTap(item: Int) {
 170:         val it = items.getOrNull(item) ?: return
 171:         val pi = it.contentIntent
 172:         if (pi != null) {
 173:             try {
 174:                 pi.send()
 175:             } catch (e: Exception) {
 176:             }
 177:         }
 178:         h.collapse()
 179:     }
 180: 
 181:     override fun onLongPress(item: Int): Boolean {
 182:         val it = items.getOrNull(item) ?: return false
 183:         if (it.clearable) NotifFeed.dismiss(it.key)
 184:         return false
 185:     }
 186: 
 187:     private companion object {
 188:         const val A0 = 6f
 189:         const val A1 = 72f
 190:         val CLIP_F = floatArrayOf(0.36f, 0.52f, 0.68f, 0.84f)
 191:     }
 192: }
===== WinDevice.kt =====
   1: package com.dynedge
   2: 
   3: import android.graphics.Canvas
   4: import android.graphics.Color
   5: import android.graphics.Paint
   6: import android.graphics.Path
   7: import android.graphics.RectF
   8: import android.graphics.Typeface
   9: import android.text.TextPaint
  10: import kotlin.math.asin
  11: import kotlin.math.cos
  12: import kotlin.math.min
  13: import kotlin.math.sin
  14: 
  15: /** DEVICE window: display-only arcs for battery, RAM and storage. DeviceStats runs only while visible. */
  16: internal class WinDevice(h: EdgeView) : Win(h, "device", 1) {
  17: 
  18:     private val stats = DeviceStats(h.context) {
  19:         if (shown) {
  20:             refresh()
  21:             h.invalidate()
  22:         }
  23:     }
  24:     private var shown = false
  25:     private val oval = Array(3) { RectF() }
  26:     private val r = FloatArray(3)
  27:     private val a0 = FloatArray(3)
  28:     private val labelPath = Array(3) { Path() }
  29:     private val label = arrayOf("Battery", "RAM", "Storage")
  30:     private val frac = FloatArray(3)
  31:     private var pctStr = "0%"
  32:     private var tempStr = ""
  33:     private var charging = false
  34:     private var bx = 0f
  35:     private var by = 0f
  36:     private var pctX = 0f
  37:     private var pctY = 0f
  38:     private var tempY = 0f
  39:     private val sb = StringBuilder(16)
  40:     private val pctPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
  41:         color = Color.WHITE
  42:         textAlign = Paint.Align.CENTER
  43:         typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
  44:     }
  45:     private val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
  46:         color = EdgeView.C_GREY
  47:         textAlign = Paint.Align.CENTER
  48:         textSize = h.sp(10f)
  49:     }
  50:     private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
  51:         color = EdgeView.C_GREY
  52:         textSize = h.sp(10f)
  53:     }
  54: 
  55:     override fun layout(hw: Float, brad: Float) {
  56:         val f = floatArrayOf(0.86f, 0.72f, 0.58f)
  57:         val badgeR = dp(10f)
  58:         for (k in 0 until 3) {
  59:             val rr = min(f[k] * rad, rad - dp(14f))
  60:             r[k] = rr
  61:             oval[k].set(-rr, -rr, rr, rr)
  62:             a0[k] = SL_A0
  63:         }
  64:         // battery arc starts after the charging badge
  65:         val ba = Math.toDegrees(asin(((badgeR + dp(4f)) / r[0]).toDouble().coerceAtMost(1.0))).toFloat()
  66:         val bh = Math.toDegrees(asin((badgeR / r[0]).toDouble().coerceAtMost(1.0))).toFloat()
  67:         val bar = Math.toRadians(ba.toDouble())
  68:         bx = -r[0] * cos(bar).toFloat()
  69:         by = -r[0] * sin(bar).toFloat()
  70:         a0[0] = ba + bh + 2f
  71:         val s = badgeR * 0.6f
  72:         iconRect[0].set((bx - s).toInt(), (by - s).toInt(), (bx + s).toInt(), (by + s).toInt())
  73:         ir[0] = badgeR
  74:         // labels sit just inside each track, from the arc start
  75:         for (k in 0 until 3) {
  76:             arcPath(labelPath[k], r[k] - dp(15f), a0[k] + 4f, 80f)
  77:         }
  78:         val inner = 0.45f * rad
  79:         val va = Math.toRadians(45.0)
  80:         pctPaint.textSize = min(h.sp(32f), 0.15f * rad)
  81:         pctX = -inner * cos(va).toFloat()
  82:         pctY = -inner * sin(va).toFloat() - (pctPaint.ascent() + pctPaint.descent()) / 2f
  83:         tempY = pctY + dp(16f)
  84:         for (k in 0 until 3) addBand(r[k] - dp(9f), min(r[k] + dp(9f), rad - dp(2.5f)), 4f, 86f, 1)
  85:         if (started) refresh()
  86:     }
  87: 
  88:     override fun start() {
  89:         // DeviceStats itself runs only while this window is the visible one (onShown)
  90:     }
  91: 
  92:     override fun stop() {
  93:         shown = false
  94:         stats.stop()
  95:     }
  96: 
  97:     override fun onShown(on: Boolean) {
  98:         shown = on
  99:         if (on) stats.start() else stats.stop()
 100:     }
 101: 
 102:     private fun refresh() {
 103:         dirty = true
 104:         frac[0] = stats.batteryPct / 100f
 105:         frac[1] = stats.ramUsedFrac
 106:         frac[2] = stats.storageUsedFrac
 107:         charging = stats.charging
 108:         sb.setLength(0)
 109:         sb.append(stats.batteryPct).append('%')
 110:         pctStr = sb.toString()
 111:         sb.setLength(0)
 112:         sb.append(Math.round(stats.tempC)).append(" C")
 113:         tempStr = sb.toString()
 114:     }
 115: 
 116:     override fun draw(c: Canvas, a: Float) {
 117:         val ai = (255f * a + 0.5f).toInt()
 118:         val ap = h.arcPaint
 119:         for (k in 0 until 3) {
 120:             ap.strokeWidth = dp(5f)
 121:             ap.color = EdgeView.C_TRACK
 122:             ap.alpha = ai
 123:             c.drawArc(oval[k], 180f + a0[k], SL_A1 - a0[k], false, ap)
 124:             val lv = frac[k].coerceIn(0f, 1f)
 125:             if (lv > 0.001f) {
 126:                 ap.color = if (k == 0) h.accent else Color.WHITE
 127:                 ap.alpha = if (k == 0) ai else (255f * 0.85f * a + 0.5f).toInt()
 128:                 c.drawArc(oval[k], 180f + a0[k], (SL_A1 - a0[k]) * lv, false, ap)
 129:             }
 130:             labelPaint.alpha = ai
 131:             c.drawTextOnPath(label[k], labelPath[k], 0f, 0f, labelPaint)
 132:         }
 133:         if (charging) {
 134:             h.fillP.color = h.accent
 135:             h.fillP.alpha = ai
 136:             c.drawCircle(bx, by, ir[0], h.fillP)
 137:             val ic = h.icon(R.drawable.ic_bolt, true)
 138:             ic.bounds = iconRect[0]
 139:             ic.alpha = ai
 140:             ic.draw(c)
 141:         }
 142:         pctPaint.alpha = ai
 143:         c.drawText(pctStr, pctX, pctY, pctPaint)
 144:         smallPaint.alpha = ai
 145:         c.drawText(tempStr, pctX, tempY, smallPaint)
 146:     }
 147: }
===== WinApps.kt =====
   1: package com.dynedge
   2: 
   3: import android.content.Intent
   4: import android.graphics.Bitmap
   5: import android.graphics.BitmapShader
   6: import android.graphics.Canvas
   7: import android.graphics.Color
   8: import android.graphics.Matrix
   9: import android.graphics.Paint
  10: import android.graphics.Rect
  11: import android.graphics.Shader
  12: import kotlin.math.min
  13: 
  14: /** APPS window: up to 6 shortcut apps on two rings; "+" opens settings when none are set. */
  15: internal class WinApps(h: EdgeView) : Win(h, "apps", 7) {
  16: 
  17:     private val apps = AppShortcuts(h.context, h.dp(40f).toInt())
  18:     private var appCount = 0
  19:     private val appBmp = arrayOfNulls<Bitmap>(6)
  20:     private val appPaint = Array(6) { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
  21:     private val appMatrix = Matrix()
  22:     private val plusRect = Rect()
  23: 
  24:     override fun layout(hw: Float, brad: Float) {
  25:         val ri = 0.44f * rad
  26:         val ro = 0.70f * rad
  27:         place(0, ri, 25f, brad)
  28:         place(1, ri, 65f, brad)
  29:         for (k in 0..3) place(2 + k, ro, MID_A[k], brad)
  30:         place(PLUS, 0.60f * rad, 45f, dp(22f))
  31:         val ph = dp(9f).toInt()
  32:         plusRect.set((cx[PLUS] - ph).toInt(), (cy[PLUS] - ph).toInt(), (cx[PLUS] + ph).toInt(), (cy[PLUS] + ph).toInt())
  33:         addBand(ri - hw, ri + hw, 4f, 86f, 2)
  34:         addBand(ro - hw, ro + hw, 4f, 86f, 4)
  35:         for (i in 0..5) appBmp[i] = null
  36:         if (started) refresh()
  37:     }
  38: 
  39:     override fun start() {
  40:         apps.load()
  41:         refresh()
  42:     }
  43: 
  44:     override fun stop() {
  45:         apps.release()
  46:         for (i in 0..5) {
  47:             appBmp[i] = null
  48:             appPaint[i].shader = null
  49:         }
  50:         appCount = 0
  51:     }
  52: 
  53:     private fun refresh() {
  54:         dirty = true
  55:         appCount = min(apps.count, 6)
  56:         val r = dp(20f)
  57:         for (i in 0 until 6) {
  58:             val b = if (i < appCount) apps.icon(i) else null
  59:             if (b !== appBmp[i]) {
  60:                 appBmp[i] = b
  61:                 if (b == null) {
  62:                     appPaint[i].shader = null
  63:                 } else {
  64:                     val s = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
  65:                     appMatrix.setScale(2f * r / b.width, 2f * r / b.height)
  66:                     appMatrix.postTranslate(-r, -r)
  67:                     s.setLocalMatrix(appMatrix)
  68:                     appPaint[i].shader = s
  69:                 }
  70:             }
  71:         }
  72:     }
  73: 
  74:     override fun draw(c: Canvas, a: Float) {
  75:         if (appCount == 0) {
  76:             val x = cx[PLUS]
  77:             val y = cy[PLUS]
  78:             val ps = isPressed(PLUS)
  79:             val ai = (255f * a + 0.5f).toInt()
  80:             if (ps) {
  81:                 c.save()
  82:                 c.scale(EdgeView.PRESS_SCALE, EdgeView.PRESS_SCALE, x, y)
  83:                 h.ringPaint.alpha = ai
  84:                 c.drawCircle(x, y, ir[PLUS], h.ringPaint)
  85:             }
  86:             val lp = h.linePaint
  87:             lp.color = EdgeView.C_GREY
  88:             lp.alpha = ai
  89:             lp.strokeWidth = dp(2f)
  90:             val hh = dp(9f)
  91:             c.drawLine(x - hh, y, x + hh, y, lp)
  92:             c.drawLine(x, y - hh, x, y + hh, lp)
  93:             if (ps) c.restore()
  94:             return
  95:         }
  96:         for (k in 0 until appCount) {
  97:             val x = cx[k]
  98:             val y = cy[k]
  99:             val ps = isPressed(k)
 100:             c.save()
 101:             c.translate(x, y)
 102:             if (ps) c.scale(EdgeView.PRESS_SCALE, EdgeView.PRESS_SCALE)
 103:             val p = appPaint[k]
 104:             if (p.shader != null) {
 105:                 // white disc behind, so square/legacy icons don't show transparent corners
 106:                 h.fillP.color = Color.WHITE
 107:                 h.fillP.alpha = (255f * a + 0.5f).toInt()
 108:                 c.drawCircle(0f, 0f, dp(20f), h.fillP)
 109:                 p.alpha = (255f * a + 0.5f).toInt()
 110:                 c.drawCircle(0f, 0f, dp(20f), p)
 111:             }
 112:             if (ps) {
 113:                 h.ringPaint.alpha = (255f * a + 0.5f).toInt()
 114:                 c.drawCircle(0f, 0f, dp(23f), h.ringPaint)
 115:             }
 116:             c.restore()
 117:         }
 118:     }
 119: 
 120:     override fun hit(lx: Float, ly: Float): Int {
 121:         beginHit()
 122:         if (appCount == 0) consider(PLUS, lx, ly) else for (k in 0 until appCount) consider(k, lx, ly)
 123:         return bestHit()
 124:     }
 125: 
 126:     override fun onTap(item: Int) {
 127:         if (item == PLUS) {
 128:             try {
 129:                 val i = Intent(h.context, MainActivity::class.java)
 130:                 i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
 131:                 h.context.startActivity(i)
 132:             } catch (e: Exception) {
 133:             }
 134:             h.collapse()
 135:         } else if (apps.launch(item)) {
 136:             h.collapse()
 137:         }
 138:     }
 139: 
 140:     private companion object {
 141:         const val PLUS = 6
 142:         val MID_A = floatArrayOf(12f, 34.7f, 57.3f, 80f)
 143:     }
 144: }
===== WindowsConfig.kt =====
   1: package com.dynedge
   2: 
   3: import android.content.Context
   4: import org.json.JSONArray
   5: import org.json.JSONObject
   6: 
   7: /** Data-driven list of panel windows: order and on/off, stored as JSON in Prefs. */
   8: object WindowsConfig {
   9:     const val KEY = "windows"
  10:     val ALL = listOf("quick", "media", "clipboard", "apps", "volume", "timer", "notifications", "device")
  11:     val DEFAULT_ON = setOf("quick", "media", "clipboard", "apps")
  12: 
  13:     data class Entry(val id: String, val on: Boolean)
  14: 
  15:     fun load(ctx: Context): List<Entry> {
  16:         val out = ArrayList<Entry>()
  17:         val s = Prefs.prefs(ctx).getString(KEY, null)
  18:         if (s != null) {
  19:             try {
  20:                 val a = JSONArray(s)
  21:                 for (i in 0 until a.length()) {
  22:                     val o = a.optJSONObject(i) ?: continue
  23:                     val id = o.optString("id")
  24:                     if (id in ALL && out.none { it.id == id }) out.add(Entry(id, o.optBoolean("on", false)))
  25:                 }
  26:             } catch (e: Exception) {
  27:                 out.clear()
  28:             }
  29:         }
  30:         if (s == null || out.isEmpty()) {
  31:             out.clear()
  32:             for (id in ALL) out.add(Entry(id, id in DEFAULT_ON))
  33:             return out
  34:         }
  35:         for (id in ALL) if (out.none { it.id == id }) out.add(Entry(id, false))
  36:         return out
  37:     }
  38: 
  39:     fun enabled(ctx: Context): List<String> {
  40:         val l = load(ctx).filter { it.on }.map { it.id }
  41:         return if (l.isEmpty()) listOf("quick") else l
  42:     }
  43: 
  44:     fun save(ctx: Context, list: List<Entry>) {
  45:         val a = JSONArray()
  46:         val seen = HashSet<String>()
  47:         for (e in list) {
  48:             if (e.id !in ALL || !seen.add(e.id)) continue
  49:             a.put(JSONObject().put("id", e.id).put("on", e.on))
  50:         }
  51:         Prefs.prefs(ctx).edit().putString(KEY, a.toString()).apply()
  52:     }
  53: 
  54:     fun label(id: String): String = when (id) {
  55:         "quick" -> "Quick"
  56:         "media" -> "Media"
  57:         "clipboard" -> "Clipboard"
  58:         "apps" -> "Apps"
  59:         "volume" -> "Volume"
  60:         "timer" -> "Timer"
  61:         "notifications" -> "Alerts"
  62:         "device" -> "Device"
  63:         else -> id
  64:     }
  65: 
  66:     fun icon(id: String): Int = when (id) {
  67:         "quick" -> R.drawable.ic_sliders
  68:         "media" -> R.drawable.ic_music
  69:         "clipboard" -> R.drawable.ic_clipboard
  70:         "apps" -> R.drawable.ic_layers
  71:         "volume" -> R.drawable.ic_volume
  72:         "timer" -> R.drawable.ic_timer
  73:         "notifications" -> R.drawable.ic_bell
  74:         "device" -> R.drawable.ic_cpu
  75:         else -> R.drawable.ic_sliders
  76:     }
  77: }
===== NotifFeed.kt =====
   1: package com.dynedge
   2: 
   3: import android.app.PendingIntent
   4: import android.content.Context
   5: import android.graphics.Bitmap
   6: import android.graphics.Canvas
   7: import android.os.Handler
   8: import android.os.Looper
   9: 
  10: /** Recent notifications, fed by MediaListener. */
  11: object NotifFeed {
  12:     data class Item(
  13:         val key: String, val pkg: String, val title: String, val text: String, val time: Long,
  14:         val contentIntent: PendingIntent?, val clearable: Boolean
  15:     )
  16: 
  17:     const val MAX = 8
  18: 
  19:     private val lock = Any()
  20:     private var items: List<Item> = emptyList()
  21:     private val main = Handler(Looper.getMainLooper())
  22:     private val icons = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
  23:         override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 8
  24:     }
  25: 
  26:     @Volatile
  27:     var onChange: (() -> Unit)? = null
  28: 
  29:     @Volatile
  30:     internal var listener: MediaListener? = null
  31: 
  32:     private val notifier = Runnable { onChange?.invoke() }
  33: 
  34:     /** Newest first, max 8. */
  35:     fun recent(): List<Item> = synchronized(lock) { items }
  36: 
  37:     /** Called by MediaListener with the full, already filtered and sorted list. */
  38:     internal fun publish(list: List<Item>) {
  39:         val l = list.take(MAX)
  40:         synchronized(lock) {
  41:             if (l == items) return
  42:             items = l
  43:         }
  44:         main.removeCallbacks(notifier)
  45:         main.post(notifier)
  46:     }
  47: 
  48:     fun dismiss(key: String) {
  49:         try {
  50:             listener?.cancelNotification(key)
  51:         } catch (e: Exception) {
  52:         }
  53:     }
  54: 
  55:     fun appIcon(ctx: Context, pkg: String, px: Int): Bitmap? {
  56:         val k = "$pkg@$px"
  57:         synchronized(icons) { icons[k]?.let { return it } }
  58:         return try {
  59:             val d = ctx.packageManager.getApplicationIcon(pkg)
  60:             val s = px.coerceAtLeast(1)
  61:             val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
  62:             val c = Canvas(b)
  63:             d.setBounds(0, 0, s, s)
  64:             d.draw(c)
  65:             synchronized(icons) { icons[k] = b }
  66:             b
  67:         } catch (e: Exception) {
  68:             null
  69:         }
  70:     }
  71: }
===== DeviceStats.kt =====
   1: package com.dynedge
   2: 
   3: import android.app.ActivityManager
   4: import android.content.BroadcastReceiver
   5: import android.content.Context
   6: import android.content.Intent
   7: import android.content.IntentFilter
   8: import android.os.BatteryManager
   9: import android.os.Handler
  10: import android.os.Looper
  11: import android.os.StatFs
  12: 
  13: /** Battery, RAM and storage numbers. Receiver and 2 s timer exist only between start() and stop(). */
  14: class DeviceStats(ctx: Context, private val onChange: () -> Unit) {
  15: 
  16:     private val app: Context = ctx.applicationContext ?: ctx
  17:     private val main = Handler(Looper.getMainLooper())
  18:     private val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
  19:     private val mem = ActivityManager.MemoryInfo()
  20:     private var started = false
  21: 
  22:     var batteryPct = 0; private set
  23:     var charging = false; private set
  24:     var tempC = 0f; private set
  25:     var ramUsedFrac = 0f; private set
  26:     var ramTotalGb = 0f; private set
  27:     var storageUsedFrac = 0f; private set
  28:     var storageTotalGb = 0f; private set
  29: 
  30:     private val receiver = object : BroadcastReceiver() {
  31:         override fun onReceive(context: Context?, intent: Intent?) {
  32:             if (intent != null && readBattery(intent)) onChange()
  33:         }
  34:     }
  35: 
  36:     private val tick = object : Runnable {
  37:         override fun run() {
  38:             if (!started) return
  39:             if (readRam()) onChange()
  40:             main.postDelayed(this, 2000L)
  41:         }
  42:     }
  43: 
  44:     fun start() {
  45:         if (started) return
  46:         started = true
  47:         try {
  48:             val sticky = app.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
  49:             if (sticky != null) readBattery(sticky)
  50:         } catch (e: Exception) {
  51:         }
  52:         readRam()
  53:         readStorage()
  54:         main.postDelayed(tick, 2000L)
  55:         onChange()
  56:     }
  57: 
  58:     fun stop() {
  59:         if (!started) return
  60:         started = false
  61:         main.removeCallbacks(tick)
  62:         try {
  63:             app.unregisterReceiver(receiver)
  64:         } catch (e: Exception) {
  65:         }
  66:     }
  67: 
  68:     private fun readBattery(i: Intent): Boolean {
  69:         val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
  70:         val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
  71:         val pct = if (level >= 0) (level * 100f / scale).toInt().coerceIn(0, 100) else batteryPct
  72:         val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
  73:         val ch = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
  74:         val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
  75:         val changed = pct != batteryPct || ch != charging || t != tempC
  76:         batteryPct = pct; charging = ch; tempC = t
  77:         return changed
  78:     }
  79: 
  80:     private fun readRam(): Boolean {
  81:         val m = am ?: return false
  82:         return try {
  83:             m.getMemoryInfo(mem)
  84:             val total = mem.totalMem.coerceAtLeast(1L)
  85:             val used = ((total - mem.availMem).toFloat() / total).coerceIn(0f, 1f)
  86:             val gb = total / 1073741824f
  87:             val changed = Math.abs(used - ramUsedFrac) > 0.004f || gb != ramTotalGb
  88:             ramUsedFrac = used; ramTotalGb = gb
  89:             changed
  90:         } catch (e: Exception) {
  91:             false
  92:         }
  93:     }
  94: 
  95:     private fun readStorage() {
  96:         try {
  97:             val s = StatFs(app.dataDir.path)
  98:             val total = s.totalBytes.coerceAtLeast(1L)
  99:             storageUsedFrac = ((total - s.availableBytes).toFloat() / total).coerceIn(0f, 1f)
 100:             storageTotalGb = total / 1073741824f
 101:         } catch (e: Exception) {
 102:         }
 103:     }
 104: }

exec
"C:\\WINDOWS\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" -Command '$files='"'EdgeService.kt','Win.kt'; foreach("'$name in $files){$f="app/src/main/java/com/dynedge/$name"; "===== $name ====="; $n=0; Get-Content $f | ForEach-Object {$n++; '"'{0,4}: {1}' -f "'$n,$_}}; $f='"'app/src/main/java/com/dynedge/EdgeView.kt'; "'$lines=Get-Content $f; foreach($range in @(@(560,900),@(900,1250),@(1250,1600),@(1600,1950))){"===== EdgeView $($range[0])-$($range[1]) ====="; for($i=$range[0];$i -le [Math]::Min($range[1],$lines.Count);$i++){ '"'{0,4}: {1}' -f "'$i,$lines[$i-1] }}' in D:\VS\PROJECTS\FREE WORK\dynamic-edge
 exited 1 in 315ms:
===== EdgeService.kt =====
   1: package com.dynedge
   2: 
   3: import android.accessibilityservice.AccessibilityService
   4: import android.app.KeyguardManager
   5: import android.content.BroadcastReceiver
   6: import android.content.Context
   7: import android.content.Intent
   8: import android.content.SharedPreferences
   9: import android.content.IntentFilter
  10: import android.content.res.Configuration
  11: import android.graphics.PixelFormat
  12: import android.graphics.Region
  13: import android.hardware.display.DisplayManager
  14: import android.os.BatteryManager
  15: import android.os.Build
  16: import android.os.Handler
  17: import android.os.Looper
  18: import android.os.PowerManager
  19: import android.view.Display
  20: import android.view.Gravity
  21: import android.view.RoundedCorner
  22: import android.view.WindowManager
  23: import android.view.accessibility.AccessibilityEvent
  24: import android.view.accessibility.AccessibilityWindowInfo
  25: 
  26: class EdgeService : AccessibilityService(), EdgeView.Host {
  27: 
  28:     private var wm: WindowManager? = null
  29:     private var edge: EdgeView? = null
  30:     private var lp: WindowManager.LayoutParams? = null
  31:     private var touch: TouchRegion? = null
  32:     private var sizesPosted = false
  33:     private var imeVisible = false
  34:     private var screenOff = false
  35:     private var receiverRegistered = false
  36: 
  37:     private val receiver = object : BroadcastReceiver() {
  38:         override fun onReceive(context: Context?, intent: Intent?) {
  39:             when (intent?.action) {
  40:                 Intent.ACTION_SCREEN_OFF -> {
  41:                     screenOff = true
  42:                     edge?.collapseImmediately()
  43:                     edge?.setScreenOn(false)
  44:                     updateHidden()
  45:                 }
  46:                 Intent.ACTION_SCREEN_ON -> {
  47:                     screenOff = false
  48:                     edge?.setScreenOn(true)
  49:                     updateHidden()
  50:                 }
  51:                 Intent.ACTION_USER_PRESENT -> updateHidden()
  52:                 Intent.ACTION_POWER_CONNECTED -> postCharging()
  53:                 Intent.ACTION_POWER_DISCONNECTED -> {
  54:                     mainHandler.removeCallbacks(chargingRemove)
  55:                     LiveBus.remove("charging")
  56:                 }
  57:             }
  58:         }
  59:     }
  60: 
  61:     private val mainHandler = Handler(Looper.getMainLooper())
  62:     private val chargingRemove = Runnable { LiveBus.remove("charging") }
  63: 
  64:     private fun postCharging() {
  65:         if (!Prefs.live(this) || screenOff) return
  66:         val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
  67:         val lv = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
  68:         val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
  69:         val pct = if (lv >= 0 && sc > 0) (lv * 100 / sc) else 0
  70:         LiveBus.put(
  71:             LiveActivity(
  72:                 "charging", LiveActivity.Kind.CHARGING, "$pct%", pct / 100f, 0L, false,
  73:                 R.drawable.ic_bolt, false, null, null, null
  74:             )
  75:         )
  76:         mainHandler.removeCallbacks(chargingRemove)
  77:         mainHandler.postDelayed(chargingRemove, 3500L)
  78:     }
  79: 
  80:     private var prefListenerRegistered = false
  81:     // strong reference: SharedPreferences only holds listeners weakly
  82:     private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
  83:         when (key) {
  84:             Prefs.KEY_IDLE_DP, Prefs.KEY_OPEN_DP -> scheduleSizes()
  85:             Prefs.KEY_PREVIEW -> applyPreview()
  86:             Prefs.KEY_ACCENT, Prefs.KEY_GRADIENT, Prefs.KEY_RIM, Prefs.KEY_GLOW, Prefs.KEY_BANDS,
  87:             Prefs.KEY_SPEED, Prefs.KEY_BOUNCE, Prefs.KEY_HAPTICS, Prefs.KEY_IDLE_STYLE,
  88:             Prefs.KEY_VISUALIZER, Prefs.KEY_LIVE, Prefs.KEY_BEAT -> edge?.applyLooks()
  89:             QuickConfig.KEY -> edge?.reloadQuick()
  90:             WindowsConfig.KEY -> edge?.reloadWindows()
  91:             Prefs.KEY_FLARE -> applyFlare()
  92:         }
  93:     }
  94: 
  95:     private val sizesRunnable = Runnable {
  96:         sizesPosted = false
  97:         applySizesFromPrefs()
  98:     }
  99: 
 100:     /** Coalesces a stream of size changes (slider drag) to one relayout per frame. */
 101:     private fun scheduleSizes() {
 102:         val view = edge ?: return
 103:         if (sizesPosted) return
 104:         sizesPosted = true
 105:         view.postOnAnimation(sizesRunnable)
 106:     }
 107: 
 108:     private fun applyPreview() {
 109:         val view = edge ?: return
 110:         when (Prefs.preview(this)) {
 111:             Prefs.PREVIEW_OPEN -> {
 112:                 view.holdOpen = true
 113:                 view.expand()
 114:             }
 115:             else -> {
 116:                 view.holdOpen = false
 117:                 view.collapse()
 118:             }
 119:         }
 120:     }
 121: 
 122:     private fun applySizesFromPrefs() {
 123:         val view = edge ?: return
 124:         val manager = wm ?: return
 125:         val params = lp ?: return
 126:         view.applySizes(Prefs.idleDp(this), Prefs.openDp(this))
 127:         resizeWindow(view, manager, params)
 128:     }
 129: 
 130:     private fun applyFlare() {
 131:         val view = edge ?: return
 132:         val manager = wm ?: return
 133:         val params = lp ?: return
 134:         view.applyFlare()
 135:         resizeWindow(view, manager, params)
 136:     }
 137: 
 138:     /** The window is always the large size; it only changes when the sizes change in prefs. */
 139:     private fun resizeWindow(view: EdgeView, manager: WindowManager, params: WindowManager.LayoutParams) {
 140:         val pad = (24f * density).toInt()
 141:         val w = view.maxOpenWidthPx + pad
 142:         val h = view.maxOpenHeightPx + pad
 143:         if (params.width == w && params.height == h) return
 144:         params.width = w
 145:         params.height = h
 146:         try {
 147:             manager.updateViewLayout(view, params)
 148:         } catch (e: Throwable) {
 149:         }
 150:     }
 151: 
 152:     private val density: Float get() = resources.displayMetrics.density
 153: 
 154:     private var attached = false
 155:     private var attachTries = 0
 156:     private val retryDelays = longArrayOf(300L, 1000L, 3000L, 3000L, 3000L)
 157:     private val attachRunnable = Runnable { attach() }
 158: 
 159:     override fun onServiceConnected() {
 160:         super.onServiceConnected()
 161:         mainHandler.removeCallbacks(attachRunnable)
 162:         attachTries = 0
 163:         attach()
 164:     }
 165: 
 166:     /** Idempotent: builds the view once, adds the window, and retries with backoff if the token is not valid yet. */
 167:     private fun attach() {
 168:         if (attached) return
 169:         val manager = wm ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { wm = it }
 170:         var view = edge
 171:         var params = lp
 172:         if (view == null || params == null) {
 173:             AssistControl.disableCornerGesture(this)
 174:             val v = EdgeView(this)
 175:             v.host = this
 176:             v.setService(this)
 177:             v.setTipRadius(readTipRadius())
 178:             val winPx = v.maxOpenWidthPx + (24f * density).toInt()
 179:             val p = WindowManager.LayoutParams(
 180:                 winPx,
 181:                 winPx,
 182:                 WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
 183:                 WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
 184:                     WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
 185:                     WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
 186:                     WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
 187:                     WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
 188:                     WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
 189:                 PixelFormat.TRANSLUCENT
 190:             )
 191:             p.gravity = Gravity.BOTTOM or Gravity.RIGHT
 192:             p.x = 0
 193:             p.y = 0
 194:             p.fitInsetsTypes = 0
 195:             p.fitInsetsSides = 0
 196:             p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
 197:             p.title = "DynamicEdge"
 198:             edge = v
 199:             lp = p
 200:             view = v
 201:             params = p
 202:         }
 203:         try {
 204:             if (!view.isAttachedToWindow) manager.addView(view, params)
 205:         } catch (e: WindowManager.BadTokenException) {
 206:             scheduleRetry()
 207:             return
 208:         } catch (e: IllegalStateException) {
 209:             scheduleRetry()
 210:             return
 211:         } catch (e: Throwable) {
 212:             scheduleRetry()
 213:             return
 214:         }
 215:         attached = true
 216:         touch?.uninstall()
 217:         touch = TouchRegion.install(view)
 218:         touch?.set(view.currentRegion())
 219: 
 220:         if (!receiverRegistered) {
 221:             val filter = IntentFilter()
 222:             filter.addAction(Intent.ACTION_SCREEN_OFF)
 223:             filter.addAction(Intent.ACTION_SCREEN_ON)
 224:             filter.addAction(Intent.ACTION_USER_PRESENT)
 225:             filter.addAction(Intent.ACTION_POWER_CONNECTED)
 226:             filter.addAction(Intent.ACTION_POWER_DISCONNECTED)
 227:             registerReceiver(receiver, filter)
 228:             receiverRegistered = true
 229:         }
 230: 
 231:         screenOff = false
 232:         updateHidden()
 233:         view.setScreenOn((getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true)
 234: 
 235:         if (!prefListenerRegistered) {
 236:             Prefs.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
 237:             prefListenerRegistered = true
 238:         }
 239:     }
 240: 
 241:     private fun scheduleRetry() {
 242:         if (attachTries >= retryDelays.size) return
 243:         val d = retryDelays[attachTries]
 244:         attachTries++
 245:         mainHandler.removeCallbacks(attachRunnable)
 246:         mainHandler.postDelayed(attachRunnable, d)
 247:     }
 248: 
 249:     override fun onAccessibilityEvent(event: AccessibilityEvent?) {
 250:         if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
 251:         val ime = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
 252:         if (ime != imeVisible) {
 253:             imeVisible = ime
 254:             updateHidden()
 255:         }
 256:     }
 257: 
 258:     override fun onInterrupt() {
 259:         // Nothing to interrupt.
 260:     }
 261: 
 262:     override fun onConfigurationChanged(newConfig: Configuration) {
 263:         super.onConfigurationChanged(newConfig)
 264:         if (!attached) return
 265:         val view = edge ?: return
 266:         val manager = wm ?: return
 267:         val params = lp ?: return
 268:         view.setTipRadius(readTipRadius())
 269:         view.collapseImmediately()
 270:         resizeWindow(view, manager, params)
 271:     }
 272: 
 273:     override fun onUnbind(intent: Intent?): Boolean {
 274:         teardown()
 275:         return super.onUnbind(intent)
 276:     }
 277: 
 278:     override fun onDestroy() {
 279:         teardown()
 280:         super.onDestroy()
 281:     }
 282: 
 283:     // ------------------------------------------------------------ EdgeView.Host
 284: 
 285:     override fun onSettled(expanded: Boolean) {
 286:         // The window never resizes while opening or closing.
 287:     }
 288: 
 289:     override fun onRegionChanged(region: Region) {
 290:         touch?.set(region)
 291:     }
 292: 
 293:     override fun setWindowFocusable(on: Boolean) {
 294:         val view = edge ?: return
 295:         val params = lp ?: return
 296:         val manager = wm ?: return
 297:         val has = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
 298:         if (has == on) return
 299:         params.flags = if (on) {
 300:             params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
 301:         } else {
 302:             params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
 303:         }
 304:         try {
 305:             manager.updateViewLayout(view, params)
 306:         } catch (e: Throwable) {
 307:         }
 308:     }
 309: 
 310:     // ------------------------------------------------------------ helpers
 311: 
 312:     private fun updateHidden() {
 313:         val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
 314:         val locked = km?.isKeyguardLocked ?: false
 315:         edge?.setHidden(imeVisible || locked || screenOff)
 316:     }
 317: 
 318:     private fun readTipRadius(): Float {
 319:         if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0f
 320:         val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return 0f
 321:         val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return 0f
 322:         return display.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius?.toFloat() ?: 0f
 323:     }
 324: 
 325:     private fun teardown() {
 326:         if (receiverRegistered) {
 327:             receiverRegistered = false
 328:             try {
 329:                 unregisterReceiver(receiver)
 330:             } catch (e: Throwable) {
 331:             }
 332:         }
 333:         if (prefListenerRegistered) {
 334:             prefListenerRegistered = false
 335:             try {
 336:                 Prefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
 337:             } catch (e: Throwable) {
 338:             }
 339:         }
 340:         mainHandler.removeCallbacks(attachRunnable)
 341:         attached = false
 342:         edge?.removeCallbacks(sizesRunnable)
 343:         mainHandler.removeCallbacks(chargingRemove)
 344:         val view = edge ?: return
 345:         edge = null
 346:         touch?.uninstall()
 347:         touch = null
 348:         view.host = null
 349:         view.setService(null)
 350:         view.release()
 351:         try {
 352:             if (view.isAttachedToWindow) wm?.removeView(view)
 353:         } catch (e: Throwable) {
 354:         }
 355:         lp = null
 356:     }
 357: }
===== Win.kt =====
   1: package com.dynedge
   2: 
   3: import android.graphics.Canvas
   4: import android.graphics.Path
   5: import android.graphics.Rect
   6: import android.graphics.RectF
   7: import android.graphics.RenderNode
   8: import kotlin.math.max
   9: import kotlin.math.min
  10: 
  11: /**
  12:  * One panel window (QUICK, MEDIA, ...). A window owns its own layout, drawing and hit testing in the
  13:  * unrotated polar frame whose origin is the screen corner (x and y are negative inside the panel).
  14:  * The host ([EdgeView]) owns gestures, springs, paging, section layers and the shared paints.
  15:  *
  16:  * Item ids are local to the window (0 until its item count); the host adds the window to the pressed state.
  17:  */
  18: internal abstract class Win(@JvmField protected val h: EdgeView, val id: String, items: Int) {
  19: 
  20:     /** True while the section layer must be re-recorded. */
  21:     @JvmField var dirty = true
  22:     @JvmField var node: RenderNode? = null
  23:     @JvmField var index = -1
  24:     /** True between start() and stop() (panel open, listeners registered). */
  25:     @JvmField var started = false
  26:     @JvmField val bands = ArrayList<Array<Path>>()
  27: 
  28:     @JvmField protected val cx = FloatArray(items)
  29:     @JvmField protected val cy = FloatArray(items)
  30:     @JvmField protected val ir = FloatArray(items)
  31:     @JvmField protected val hitR = FloatArray(items)
  32:     @JvmField protected val iconRect = Array(items) { Rect() }
  33: 
  34:     @JvmField protected var pr = 0f // polar scratch: radius and angle (deg from the bottom edge)
  35:     @JvmField protected var pa = 0f
  36:     private var hBest = -1
  37:     private var hBestD = 0f
  38:     private val bandOval = RectF()
  39: 
  40:     protected fun dp(v: Float): Float = h.dp(v)
  41:     protected val rad: Float get() = h.rad
  42: 
  43:     // ---------------------------------------------------------------- lifecycle
  44:     /** Computes geometry for the current panel radius; bands are cleared by the host before. */
  45:     open fun layout(hw: Float, brad: Float) {}
  46:     /** Panel opened: register listeners and load data. */
  47:     open fun start() {}
  48:     /** Panel closed or window removed: release everything. */
  49:     open fun stop() {}
  50:     /** This window became (true) or stopped being (false) the visible, settled window. */
  51:     open fun onShown(on: Boolean) {}
  52:     /** Shared outer-ring sliders (Arcs) reported a change. */
  53:     open fun onArcs() {}
  54: 
  55:     // ---------------------------------------------------------------- drawing
  56:     abstract fun draw(c: Canvas, a: Float)
  57: 
  58:     fun drawBands(c: Canvas, a: Float) {
  59:         if (!h.bandsOn) return
  60:         h.tilePaint.alpha = (255f * 0.045f * a + 0.5f).toInt()
  61:         for (b in bands.indices) {
  62:             val ts = bands[b]
  63:             for (t in ts.indices) c.drawPath(ts[t], h.tilePaint)
  64:         }
  65:     }
  66: 
  67:     // ---------------------------------------------------------------- touch (local, unrotated coordinates)
  68:     /** Item under the point or -1. */
  69:     open fun hit(lx: Float, ly: Float): Int = -1
  70:     open fun usable(item: Int): Boolean = true
  71:     open fun longPressable(item: Int): Boolean = false
  72:     open fun onTap(item: Int) {}
  73:     /** Returns true if an Activity was launched (the host collapses). */
  74:     open fun onLongPress(item: Int): Boolean = false
  75:     /** DOWN landed on a draggable control (arc slider, seek bar, dial): claim the whole gesture. */
  76:     open fun capture(lx: Float, ly: Float): Boolean = false
  77:     open fun dragTo(lx: Float, ly: Float) {}
  78:     open fun dragEnd(cancel: Boolean) {}
  79: 
  80:     fun isPressed(i: Int): Boolean = h.pressed == i && h.pressedWin === this
  81: 
  82:     // ---------------------------------------------------------------- helpers
  83:     protected fun polar(lx: Float, ly: Float) {
  84:         pr = kotlin.math.sqrt(lx * lx + ly * ly)
  85:         pa = Math.toDegrees(kotlin.math.atan2(-ly.toDouble(), -lx.toDouble())).toFloat()
  86:     }
  87: 
  88:     protected fun beginHit() {
  89:         hBest = -1
  90:         hBestD = Float.MAX_VALUE
  91:     }
  92: 
  93:     protected fun consider(i: Int, lx: Float, ly: Float) {
  94:         val dx = lx - cx[i]
  95:         val dy = ly - cy[i]
  96:         val d2 = dx * dx + dy * dy
  97:         val hr = hitR[i]
  98:         if (d2 <= hr * hr && d2 < hBestD) {
  99:             hBest = i
 100:             hBestD = d2
 101:         }
 102:     }
 103: 
 104:     protected fun bestHit(): Int = hBest
 105: 
 106:     protected fun forceHit(i: Int) {
 107:         hBest = i
 108:     }
 109: 
 110:     /** Places item i on a ring (radius rc, angle aDeg) shrunk to stay inside the curve. */
 111:     protected fun place(i: Int, rc: Float, aDeg: Float, itemR: Float) {
 112:         val r = min(rc, rad - dp(6f) - itemR)
 113:         val a = Math.toRadians(aDeg.toDouble())
 114:         cx[i] = -r * kotlin.math.cos(a).toFloat()
 115:         cy[i] = -r * kotlin.math.sin(a).toFloat()
 116:         ir[i] = itemR
 117:         hitR[i] = max(itemR + dp(3f), dp(22f)) // 44 dp minimum touch target
 118:         val half = (itemR * 0.475f).toInt().coerceAtLeast(1)
 119:         val x = cx[i].toInt()
 120:         val y = cy[i].toInt()
 121:         iconRect[i].set(x - half, y - half, x + half, y + half)
 122:     }
 123: 
 124:     protected fun setIconRect(i: Int, half: Int) {
 125:         val x = cx[i].toInt()
 126:         val y = cy[i].toInt()
 127:         iconRect[i].set(x - half, y - half, x + half, y + half)
 128:     }
 129: 
 130:     protected fun addBand(r1: Float, r2: Float, a0: Float, a1: Float, n: Int) {
 131:         val inset = dp(1f)
 132:         val ri = r1 + inset
 133:         val ro = r2 - inset
 134:         if (ro <= ri + dp(2f) || n <= 0) return
 135:         val gapHalf = (dp(3f) + dp(2f)) / 2f
 136:         val oOut = Math.toDegrees((gapHalf / ro).toDouble()).toFloat()
 137:         val oIn = Math.toDegrees((gapHalf / ri).toDouble()).toFloat()
 138:         val outer = RectF(-ro, -ro, ro, ro)
 139:         val inner = RectF(-ri, -ri, ri, ri)
 140:         val span = (a1 - a0) / n
 141:         val arr = Array(n) { Path() }
 142:         for (t in 0 until n) {
 143:             val s = a0 + t * span
 144:             val e = s + span
 145:             val p = arr[t]
 146:             p.arcTo(outer, 180f + s + oOut, span - 2f * oOut, true)
 147:             p.arcTo(inner, 180f + e - oIn, -(span - 2f * oIn), false)
 148:             p.close()
 149:         }
 150:         bands.add(arr)
 151:     }
 152: 
 153:     protected fun arcPath(p: Path, r: Float, a0: Float, a1: Float) {
 154:         bandOval.set(-r, -r, r, r)
 155:         p.rewind()
 156:         p.arcTo(bandOval, 180f + a0, a1 - a0, true)
 157:     }
 158: 
 159:     /** Round button: optional fill disc, press ring and icon. */
 160:     protected fun drawBtn(c: Canvas, i: Int, fillColor: Int, res: Int, black: Boolean, a: Float, ba: Float) {
 161:         val x = cx[i]
 162:         val y = cy[i]
 163:         val ps = isPressed(i)
 164:         if (ps) {
 165:             c.save()
 166:             c.scale(EdgeView.PRESS_SCALE, EdgeView.PRESS_SCALE, x, y)
 167:         }
 168:         val ai = (255f * ba + 0.5f).toInt()
 169:         if (fillColor != 0) {
 170:             h.fillP.color = fillColor
 171:             h.fillP.alpha = ai
 172:             c.drawCircle(x, y, ir[i], h.fillP)
 173:         }
 174:         if (ps) {
 175:             h.ringPaint.alpha = (255f * a + 0.5f).toInt()
 176:             c.drawCircle(x, y, ir[i] + dp(3f), h.ringPaint)
 177:         }
 178:         if (res != 0) {
 179:             val ic = h.icon(res, black)
 180:             ic.bounds = iconRect[i]
 181:             ic.alpha = ai
 182:             ic.draw(c)
 183:         }
 184:         if (ps) c.restore()
 185:     }
 186: 
 187:     companion object {
 188:         const val SL_A0 = 8f
 189:         const val SL_A1 = 82f
 190: 
 191:         fun slotAngle(i: Int, n: Int): Float = if (n <= 1) 45f else 12f + i * 66f / (n - 1)
 192:     }
 193: }
===== EdgeView 560-900 =====
Cannot invoke method. Method invocation is supported only on core types in this language mode.
At line:2 char:402
+ ... ; for($i=$range[0];$i -le [Math]::Min($range[1],$lines.Count);$i++){  ...
+                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    + CategoryInfo          : InvalidOperation: (:) [], RuntimeException
    + FullyQualifiedErrorId : MethodInvocationNotSupportedInConstrainedLanguage
 
===== EdgeView 900-1250 =====
Cannot invoke method. Method invocation is supported only on core types in this language mode.
At line:2 char:402
+ ... ; for($i=$range[0];$i -le [Math]::Min($range[1],$lines.Count);$i++){  ...
+                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    + CategoryInfo          : InvalidOperation: (:) [], RuntimeException
    + FullyQualifiedErrorId : MethodInvocationNotSupportedInConstrainedLanguage
 
===== EdgeView 1250-1600 =====
Cannot invoke method. Method invocation is supported only on core types in this language mode.
At line:2 char:402
+ ... ; for($i=$range[0];$i -le [Math]::Min($range[1],$lines.Count);$i++){  ...
+                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    + CategoryInfo          : InvalidOperation: (:) [], RuntimeException
    + FullyQualifiedErrorId : MethodInvocationNotSupportedInConstrainedLanguage
 
===== EdgeView 1600-1950 =====
Cannot invoke method. Method invocation is supported only on core types in this language mode.
At line:2 char:402
+ ... ; for($i=$range[0];$i -le [Math]::Min($range[1],$lines.Count);$i++){  ...
+                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    + CategoryInfo          : InvalidOperation: (:) [], RuntimeException
    + FullyQualifiedErrorId : MethodInvocationNotSupportedInConstrainedLanguage
 

exec
"C:\\WINDOWS\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" -Command "rg -n \"override fun onDraw|fun doFrame|fun draw|pageFade|RenderNode|contentAlpha|dispatchTouchEvent|onTouchEvent|ACTION_|MotionEvent|invalidateBean|viz|beat|setHidden|release\\(|applyLooks|applySizes|flare|onDetached|onAttached|rebuild\" app/src/main/java/com/dynedge/EdgeView.kt; rg -n \"WINDOWS|LOOKS|Slider|TextView|setOn|FlatToggle|opacity|Bounce|Visualizer|windows|Flare\" app/src/main/java/com/dynedge/MainActivity.kt" in D:\VS\PROJECTS\FREE WORK\dynamic-edge
 succeeded in 593ms:
22:import android.graphics.RenderNode
33:import android.view.MotionEvent
55: * rotated around the corner when paging and cached as RenderNode layers (re-recorded only when dirty).
79:    private var flarePx = dp(Prefs.flareDp(ctx)) // edge fillet radius (open size)
82:    val maxOpenWidthPx: Int get() = ceil(rad + flarePx).toInt()
83:    val maxOpenHeightPx: Int get() = ceil(rad + flarePx).toInt()
99:    private var contentAlpha = 0f
239:    // Looks (from Prefs, see applyLooksNow). Shaders are immutable at radius GR; the shape path is drawn in
250:    private var vizOn = true
252:    private var beatOn = true
254:    private var beat = 0f
411:            if (a.chronoBase != 0L) rebuildLiveTitle()
412:            invalidateBean()
433:    private var vizRunning = false
434:    private var vizPosted = false
438:    private val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
440:        addAction(Intent.ACTION_TIME_TICK)
441:        addAction(Intent.ACTION_TIME_CHANGED)
442:        addAction(Intent.ACTION_TIMEZONE_CHANGED)
445:        addAction(Intent.ACTION_POWER_CONNECTED)
446:        addAction(Intent.ACTION_POWER_DISCONNECTED)
451:            invalidateBean()
457:            invalidateBean()
460:    private val vizCb = Choreographer.FrameCallback { t -> vizFrame(t) }
463:        applyLooksNow()
513:        contentAlpha = 0f
528:    fun applySizes(idleDp: Float, openDp: Float) {
557:    /** Re-reads the edge flare radius (Prefs.KEY_FLARE); the service then resizes the window. */
559:        flarePx = dp(Prefs.flareDp(context))
566:    fun applyLooks() {
591:    fun setHidden(h: Boolean) {
608:    fun release() {
642:    override fun onAttachedToWindow() {
643:        super.onAttachedToWindow()
648:    override fun onDetachedFromWindow() {
649:        super.onDetachedFromWindow()
713:    private fun applyLooksNow() {
729:        vizOn = Prefs.visualizer(c)
731:        beatOn = Prefs.beat(c)
750:    private fun invalidateBean() {
776:        if (screenOn && (vizOn || liveOn || beatOn)) {
781:                    idlePlaying = p && vizOn
783:                    invalidateBean()
790:            idlePlaying = w.playing && vizOn
797:        invalidateBean()
827:        val beatWant = beatOn && musicPlaying
828:        val want = screenOn && !hidden && (barsWant || beatWant)
829:        if (want == vizRunning) return
830:        vizRunning = want
834:            if (!vizPosted) {
835:                vizPosted = true
836:                Choreographer.getInstance().postFrameCallbackDelayed(vizCb, 25L)
840:            beat = 0f
841:            if (vizPosted) {
842:                Choreographer.getInstance().removeFrameCallback(vizCb)
843:                vizPosted = false
848:    private fun vizFrame(t: Long) {
849:        vizPosted = false
850:        if (!vizRunning) return
856:            if (beatOn) beat += (visualizer.levels[0].coerceIn(0f, 1f) - beat) * 0.4f
857:            if (expandedTarget || dragMode != 0) invalidate() else invalidateBean()
859:        vizPosted = true
860:        Choreographer.getInstance().postFrameCallbackDelayed(vizCb, 25L)
863:    private fun drawIdle(c: Canvas, ia: Float) {
870:        if (vizOn && idlePlaying) {
991:        rebuildLiveTitle()
995:        invalidateBean()
1038:    private fun rebuildLiveTitle() {
1057:    private fun drawLive(c: Canvas) {
1178:            if (a.kind == LiveActivity.Kind.MEDIA && contentAlpha < 0.2f) {
1195:    private fun flareFor(size: Float, p: Float): Float {
1196:        if (flarePx <= 0.5f) return 0f
1197:        val fIdle = min(flarePx * idle / dp(48f), 0.4f * idle)
1198:        val f = fIdle + (flarePx - fIdle) * p
1258:        rebuildLiveTitle()
1380:                fl = flareFor(rad, 1f)
1383:                fl = flareFor(regionClosed, 0f)
1397:            val s = ceil(rad + flareFor(rad, 1f)).toInt()
1412:        if (contentAlpha > 0.2f) return // mid-collapse: keep what is on screen
1493:    private fun doFrame(frameTimeNanos: Long) {
1543:            invalidateBean()
1553:        contentAlpha = if (collapsing) {
1554:            min(sm, max(0f, contentAlpha - dt / 0.09f))
1563:        val pulse = if (beatOn && vizRunning) 1f + beat * (if (expandedTarget) 0.015f else 0.06f) else 1f
1570:        glow = max(max(pressGlow, if (dragMode != 0 || captureWin != null) 0.85f else 0f), max(progress * 0.4f, if (beatOn && vizRunning) beat * 0.55f else 0f))
1571:        val fl = flareFor(min(dw, dh), progress)
1586:    override fun onDraw(c: Canvas) {
1601:        val a = contentAlpha
1605:    /** LOOKS hook: the shape fill (gradient today; fills and beat reaction plug in here). */
1606:    private fun drawShapeFill(c: Canvas, g: Float) {
1618:    private fun drawContent(c: Canvas, a: Float) {
1632:        if (mw != null && beatOn && vizRunning && beat > 0.03f && abs(pos - mw.index) < 0.6f) {
1633:            val fa = pageFade(pos - mw.index)
1639:            arcPaint.strokeWidth = dp(5f) + beat * dp(5f)
1640:            arcPaint.alpha = (255f * 0.38f * beat * a * fa + 0.5f).toInt()
1641:            c.drawCircle(mw.artX, mw.artY, mw.artRad + dp(3f) + beat * dp(5f), arcPaint)
1647:            val fa = pageFade(d)
1651:                n = RenderNode("edgeWin" + win.id)
1676:            c.drawRenderNode(n)
1687:    private fun pageFade(d: Float): Float {
1692:    private fun drawContentDirect(c: Canvas, a: Float) {
1702:            val fa = pageFade(d)
1714:    private fun drawWin(c: Canvas, w: Win, a: Float) {
1719:    private fun drawPageBars(c: Canvas, a: Float) {
1759:    private fun drawClose(c: Canvas, a: Float) {
1785:    private fun addMove(ev: MotionEvent, rx: Float, ry: Float) {
1804:    override fun onTouchEvent(ev: MotionEvent): Boolean {
1806:        if (action == MotionEvent.ACTION_OUTSIDE) {
1812:            MotionEvent.ACTION_DOWN -> return handleDown(ev)
1813:            MotionEvent.ACTION_MOVE -> {
1817:            MotionEvent.ACTION_UP -> {
1821:            MotionEvent.ACTION_CANCEL -> {
1829:    private fun handleDown(ev: MotionEvent): Boolean {
1865:            if (contentAlpha > 0.5f) {
1925:    private fun handleMove(ev: MotionEvent) {
2050:    private fun handleUp(ev: MotionEvent, cancel: Boolean) {
2108:        applyLooksNow()
29:import android.widget.TextView
40:    private lateinit var setupCount: TextView
41:    private val sliders = ArrayList<FlatSlider>()
42:    private val toggles = ArrayList<FlatToggle>()
88:        addSlider(content, "Corner size", Prefs.IDLE_MIN, Prefs.IDLE_MAX, Prefs.idleDp(this), { "${it.toInt()} dp" },
90:        addSlider(content, "Open size", Prefs.OPEN_MIN, Prefs.OPEN_MAX, Prefs.openDp(this), { "${it.toInt()} dp" },
93:        // WINDOWS (on/off + order; long-press a row and drag)
94:        header(content, "WINDOWS")
109:        // LOOKS
110:        header(content, "LOOKS")
112:        addSlider(content, "Corner gradient", 0f, 100f, Prefs.gradient(this).toFloat(), { "${it.toInt()}%" },
118:        addSlider(content, "Flare", 0f, Prefs.FLARE_MAX, Prefs.flareDp(this), { "${it.toInt()} dp" },
127:        addSlider(content, "Speed", 0f, 100f, Prefs.speed(this).toFloat(), { "${it.toInt()}%" },
129:        addSlider(content, "Bounce", 0f, 100f, Prefs.bounce(this).toFloat(), { "${it.toInt()}%" },
139:            setOnClickListener {
166:            setOnApplyWindowInsetsListener { v, insets ->
242:            setOnLongClickListener {
250:        val adbState = adbRow.getChildAt(1) as TextView
251:        adbRow.setOnClickListener {
278:            setOnClickListener { onClick() }
313:            row.setOnClickListener {
328:                setOnClickListener {
343:    private fun text(s: String, sp: Float, color: Int, face: Typeface): TextView = TextView(this).apply {
350:    private fun header(parent: LinearLayout, s: String, right: TextView? = null) {
377:    private fun addSlider(
391:        val s = FlatSlider(this).apply {
414:        val t = FlatToggle(this).apply {
447:                setOnClickListener {
483:                setOnClickListener {
497:    // ---------------------------------------------------------------- windows list
531:            val t = FlatToggle(this).apply {
556:        row.setOnLongClickListener {
558:            if (orig < 0) return@setOnLongClickListener false
567:        row.setOnTouchListener { _, e ->
571:            if (!dragging) return@setOnTouchListener false

exec
"C:\\WINDOWS\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" -Command '$f='"'app/src/main/java/com/dynedge/EdgeView.kt'; "'$lines=Get-Content $f; foreach($range in @(@(560,750),@(827,920),@(1190,1450),@(1490,1785),@(1785,2130))){"===== $($range[0])-$($range[1]) ====="; for($i=$range[0];$i -le $range[1];$i++){ '"'{0,4}: {1}' -f "'$i,$lines[$i-1] }}' in D:\VS\PROJECTS\FREE WORK\dynamic-edge
 succeeded in 368ms:
===== 560-750 =====
 560:         lastDw = -1f
 561:         publishRegion()
 562:         invalidate()
 563:     }
 564: 
 565:     /** Re-reads the looks and feel prefs (coalesced to one pass per frame). */
 566:     fun applyLooks() {
 567:         if (looksPosted) return
 568:         looksPosted = true
 569:         postOnAnimation(looksRunnable)
 570:     }
 571: 
 572:     /** Re-reads the QUICK layout (QuickConfig.KEY changed). */
 573:     fun reloadQuick() {
 574:         (winMap["quick"] as? WinQuick)?.reload()
 575:     }
 576: 
 577:     fun setScreenOn(on: Boolean) {
 578:         if (screenOn == on) return
 579:         screenOn = on
 580:         syncIdleServices()
 581:         onLiveChanged()
 582:         syncShown()
 583:     }
 584: 
 585:     fun setTipRadius(px: Float) {
 586:         tipPx = px
 587:         publishRegion()
 588:         invalidate()
 589:     }
 590: 
 591:     fun setHidden(h: Boolean) {
 592:         if (hidden == h) return
 593:         hidden = h
 594:         if (h) {
 595:             collapseImmediately()
 596:             animate().alpha(0f).setDuration(150L).start()
 597:         } else {
 598:             animate().alpha(1f).setDuration(150L).start()
 599:         }
 600:         publishRegion()
 601:         updateViz()
 602:         onLiveChanged()
 603:     }
 604: 
 605:     fun currentRegion(): Region = region
 606: 
 607:     /** Stops listeners and callbacks; call before removing the view for good. */
 608:     fun release() {
 609:         if (framePosted) {
 610:             Choreographer.getInstance().removeFrameCallback(frameCb)
 611:             framePosted = false
 612:         }
 613:         cancelItemLongPress()
 614:         removeCallbacks(liveTickRunnable)
 615:         removeCallbacks(captureTimeout)
 616:         for (r in transientRunnables.values) removeCallbacks(r)
 617:         transientRunnables.clear()
 618:         releaseTracker()
 619:         expandedTarget = false
 620:         settledExpanded = false
 621:         syncShown()
 622:         stopSources()
 623:         removeCallbacks(looksRunnable)
 624:         screenOn = false
 625:         syncIdleServices()
 626:         mediaWatch?.stop()
 627:         mediaWatch = null
 628:         visualizer.stop()
 629:         LiveBus.onChange = null
 630:         service = null
 631:         (winMap["quick"] as? WinQuick)?.setService(null)
 632:     }
 633: 
 634:     // ================================================================ view plumbing
 635: 
 636:     override fun hasOverlappingRendering(): Boolean = false
 637: 
 638:     override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
 639:         publishRegion()
 640:     }
 641: 
 642:     override fun onAttachedToWindow() {
 643:         super.onAttachedToWindow()
 644:         LiveBus.onChange = { onLiveChanged() }
 645:         onLiveChanged()
 646:     }
 647: 
 648:     override fun onDetachedFromWindow() {
 649:         super.onDetachedFromWindow()
 650:         if (framePosted) {
 651:             Choreographer.getInstance().removeFrameCallback(frameCb)
 652:             framePosted = false
 653:         }
 654:         removeCallbacks(liveTickRunnable)
 655:         removeCallbacks(longPressRunnable)
 656:         // the display lists die with the window: force a re-record when it comes back
 657:         for (w in order) w.dirty = true
 658:     }
 659: 
 660:     override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
 661:         super.onWindowFocusChanged(hasWindowFocus)
 662:         if (hasWindowFocus && captureWanted) {
 663:             captureWanted = false
 664:             removeCallbacks(captureTimeout)
 665:             try {
 666:                 ClipPaste.capture(context)
 667:             } catch (e: Throwable) {
 668:             }
 669:             host?.setWindowFocusable(false)
 670:         }
 671:     }
 672: 
 673:     // ---------------------------------------------------------------- clipboard capture / helpers for windows
 674:     private var captureWanted = false
 675:     private var captureLast = 0L
 676:     private val captureTimeout = Runnable {
 677:         if (captureWanted) {
 678:             captureWanted = false
 679:             host?.setWindowFocusable(false)
 680:         }
 681:     }
 682: 
 683:     /** Asks the service to make the window focusable for one focus change so the clipboard can be read. */
 684:     internal fun requestCapture() {
 685:         val now = SystemClock.uptimeMillis()
 686:         if (captureWanted || now - captureLast < 1500L) return
 687:         captureLast = now
 688:         captureWanted = true
 689:         host?.setWindowFocusable(true)
 690:         removeCallbacks(captureTimeout)
 691:         postDelayed(captureTimeout, 300L)
 692:     }
 693: 
 694:     internal fun showCopied() {
 695:         putTransient(
 696:             LiveActivity(
 697:                 "copied", LiveActivity.Kind.PROGRESS, "Copied", -1f, 0L, false,
 698:                 R.drawable.ic_clipboard, false, null, null, null
 699:             ), 1800L
 700:         )
 701:     }
 702: 
 703:     internal fun openClipboard() {
 704:         try {
 705:             val i = Intent(context, ClipboardActivity::class.java)
 706:             i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
 707:             context.startActivity(i)
 708:         } catch (e: Exception) {
 709:         }
 710:     }
 711:     // ================================================================ looks
 712: 
 713:     private fun applyLooksNow() {
 714:         val c = context
 715:         accent = Prefs.accent(c)
 716:         gradientPct = Prefs.prefs(c).getInt(Prefs.KEY_GRADIENT, 35).coerceIn(0, 100)
 717:         rimOn = Prefs.rim(c)
 718:         glowOn = Prefs.glow(c)
 719:         bandsOn = Prefs.bands(c)
 720:         hapticsOn = Prefs.haptics(c)
 721:         stiffScale = Prefs.stiffnessScale(c)
 722:         openDamp = Prefs.openDamping(c)
 723:         idleStyleId = when (Prefs.idleStyle(c)) {
 724:             "none" -> 0
 725:             "meter" -> 3
 726:             "pill", "slits", "grid" -> 2
 727:             else -> 1
 728:         } // LOOKS hook: replace with the new idle designs
 729:         vizOn = Prefs.visualizer(c)
 730:         liveOn = Prefs.live(c)
 731:         beatOn = Prefs.beat(c)
 732:         shapeShader = buildShapeShader()
 733:         glowShader = buildGlowShader()
 734:         shapePaint.shader = shapeShader
 735:         glowPaint.shader = glowShader
 736:         ringPaint.color = accent
 737:         press.stiffness = 700f * stiffScale
 738:         for (w in order) w.dirty = true
 739:         syncIdleServices()
 740:         onLiveChanged()
 741:         invalidate()
 742:     }
 743: 
 744:     internal fun haptic(kind: Int) {
 745:         if (hapticsOn) performHapticFeedback(kind)
 746:     }
 747: 
 748:     // ================================================================ idle services
 749: 
 750:     private fun invalidateBean() {
===== 827-920 =====
 827:         val beatWant = beatOn && musicPlaying
 828:         val want = screenOn && !hidden && (barsWant || beatWant)
 829:         if (want == vizRunning) return
 830:         vizRunning = want
 831:         if (want) {
 832:             visualizer.start()
 833:             lastVizNanos = 0L
 834:             if (!vizPosted) {
 835:                 vizPosted = true
 836:                 Choreographer.getInstance().postFrameCallbackDelayed(vizCb, 25L)
 837:             }
 838:         } else {
 839:             visualizer.stop()
 840:             beat = 0f
 841:             if (vizPosted) {
 842:                 Choreographer.getInstance().removeFrameCallback(vizCb)
 843:                 vizPosted = false
 844:             }
 845:         }
 846:     }
 847: 
 848:     private fun vizFrame(t: Long) {
 849:         vizPosted = false
 850:         if (!vizRunning) return
 851:         val dtN = t - lastVizNanos
 852:         if (lastVizNanos == 0L || dtN >= 31_000_000L) {
 853:             val dt = if (lastVizNanos == 0L) 0.033f else (dtN * 1e-9f).coerceAtMost(0.1f)
 854:             lastVizNanos = t
 855:             visualizer.tick(dt, t / 1_000_000L)
 856:             if (beatOn) beat += (visualizer.levels[0].coerceIn(0f, 1f) - beat) * 0.4f
 857:             if (expandedTarget || dragMode != 0) invalidate() else invalidateBean()
 858:         }
 859:         vizPosted = true
 860:         Choreographer.getInstance().postFrameCallbackDelayed(vizCb, 25L)
 861:     }
 862: 
 863:     private fun drawIdle(c: Canvas, ia: Float) {
 864:         val sc = idle / dp(48f)
 865:         c.save()
 866:         c.translate(width.toFloat(), height.toFloat())
 867:         val ps = press.value
 868:         c.scale(ps, ps)
 869:         val cen = -0.4244f * idle
 870:         if (vizOn && idlePlaying) {
 871:             val bw = dp(2.5f) * sc
 872:             val pitch = dp(4.5f) * sc
 873:             val minH = dp(3f) * sc
 874:             val maxH = 0.34f * idle
 875:             val lv = visualizer.levels
 876:             for (i in 0 until 4) {
 877:                 val h = minH + (maxH - minH) * lv[i]
 878:                 val x = cen + (i - 1.5f) * pitch
 879:                 fill.color = if (i == 2) accent else Color.WHITE
 880:                 fill.alpha = (255f * 0.85f * ia + 0.5f).toInt()
 881:                 c.drawRoundRect(x - bw / 2f, cen - h / 2f, x + bw / 2f, cen + h / 2f, bw / 2f, bw / 2f, fill)
 882:             }
 883:         } else when (idleStyleId) {
 884:             1 -> {
 885:                 fill.color = accent
 886:                 fill.alpha = (255f * ia + 0.5f).toInt()
 887:                 c.drawCircle(-0.30f * idle, -0.30f * idle, dp(2f) * sc, fill)
 888:             }
 889:             2 -> {
 890:                 val r = 0.70f * idle
 891:                 idleRect.set(-r, -r, r, r)
 892:                 idlePaint.strokeWidth = dp(1.4f) * sc
 893:                 idlePaint.color = C_CREAM
 894:                 idlePaint.alpha = (255f * 0.85f * ia + 0.5f).toInt()
 895:                 c.drawArc(idleRect, 180f + 12f, 66f, false, idlePaint)
 896:             }
 897:             3 -> {
 898:                 val r = 0.66f * idle
 899:                 idleRect.set(-r, -r, r, r)
 900:                 idlePaint.strokeWidth = dp(2.6f) * sc
 901:                 idlePaint.color = Color.WHITE
 902:                 idlePaint.alpha = (255f * 0.22f * ia + 0.5f).toInt()
 903:                 c.drawArc(idleRect, 180f + 10f, 70f, false, idlePaint)
 904:                 if (battFrac > 0.01f) {
 905:                     idlePaint.color = if (charging) Color.WHITE else accent
 906:                     idlePaint.alpha = (255f * 0.9f * ia + 0.5f).toInt()
 907:                     c.drawArc(idleRect, 180f + 10f, 70f * battFrac, false, idlePaint)
 908:                 }
 909:                 if (charging) {
 910:                     val u = 0.07f * idle
 911:                     boltPath.rewind()
 912:                     boltPath.moveTo(cen + 0.3f * u, cen - u)
 913:                     boltPath.lineTo(cen - 0.5f * u, cen + 0.1f * u)
 914:                     boltPath.lineTo(cen, cen + 0.1f * u)
 915:                     boltPath.lineTo(cen - 0.3f * u, cen + u)
 916:                     boltPath.lineTo(cen + 0.5f * u, cen - 0.1f * u)
 917:                     boltPath.lineTo(cen, cen - 0.1f * u)
 918:                     boltPath.close()
 919:                     fill.color = Color.WHITE
 920:                     fill.alpha = (255f * 0.85f * ia + 0.5f).toInt()
===== 1190-1450 =====
1190:     }
1191: 
1192:     // ================================================================ geometry
1193: 
1194:     /** Edge fillet radius for a shape of the given size: scaled with the idle size, at most 40% of it. */
1195:     private fun flareFor(size: Float, p: Float): Float {
1196:         if (flarePx <= 0.5f) return 0f
1197:         val fIdle = min(flarePx * idle / dp(48f), 0.4f * idle)
1198:         val f = fIdle + (flarePx - fIdle) * p
1199:         return min(f, 0.4f * size)
1200:     }
1201: 
1202:     /**
1203:      * Shape path: the quarter-circle bean plus concave fillets ("ears") of radius [fl] where it meets the
1204:      * bottom edge (left end) and the right edge (top end).
1205:      */
1206:     private fun fillPath(p: Path, ox: Float, oy: Float, w: Float, h: Float, k: Float, tip: Float, fl: Float) {
1207:         p.rewind()
1208:         val right = ox + w
1209:         val bottom = oy + h
1210:         val f = if (fl > 0.5f) fl else 0f
1211:         if (f > 0f) {
1212:             p.moveTo(ox - f, bottom)
1213:             arcRect.set(ox - 2f * f, bottom - 2f * f, ox, bottom)
1214:             p.arcTo(arcRect, 90f, -90f)
1215:             p.lineTo(ox, bottom)
1216:         } else {
1217:             p.moveTo(ox, bottom)
1218:         }
1219:         p.cubicTo(ox, bottom - k * h, right - k * w, oy, right, oy)
1220:         if (f > 0f) {
1221:             p.lineTo(right - f, oy)
1222:             arcRect.set(right - 2f * f, oy - 2f * f, right, oy)
1223:             p.arcTo(arcRect, 90f, -90f)
1224:         }
1225:         val t = min(tip, min(w, h))
1226:         if (t > 0.5f) {
1227:             p.lineTo(right, bottom - t)
1228:             arcRect.set(right - 2f * t, bottom - 2f * t, right, bottom)
1229:             p.arcTo(arcRect, 0f, 90f)
1230:         } else {
1231:             p.lineTo(right, bottom)
1232:         }
1233:         p.lineTo(ox - f, bottom)
1234:         p.close()
1235:     }
1236: 
1237:     private fun layoutAll() {
1238:         nodeS = ceil(rad).toInt() + ceil(dp(3f)).toInt()
1239:         beanPx = ceil(idle * LIVE_SCALE).toInt() + ceil(dp(4f)).toInt()
1240:         for (w in order) w.dirty = true
1241:         val hw = min(dp(24.5f), 0.12f * rad - dp(1.5f)).coerceAtLeast(dp(12f))
1242:         val brad = min(dp(20f), hw - dp(2f))
1243: 
1244:         // close X: at the idle bean's centroid (quarter disc centroid = 4/(3 pi) * size).
1245:         // Visual 34 dp, 48 dp hit target.
1246:         val xc = min(0.4244f * idle, 0.16f * rad)
1247:         xCx = -xc
1248:         xCy = -xc
1249:         xRad = dp(17f)
1250:         xHit = dp(24f)
1251: 
1252:         for (w in order) {
1253:             w.bands.clear()
1254:             w.layout(hw, brad)
1255:         }
1256:         layoutLive()
1257:         liveTitleRaw = null
1258:         rebuildLiveTitle()
1259:     }
1260: 
1261:     private var nodeS = 0
1262: 
1263:     private fun arcPath(p: Path, r: Float, a0: Float, a1: Float) {
1264:         xOval.set(-r, -r, r, r)
1265:         p.rewind()
1266:         p.arcTo(xOval, 180f + a0, a1 - a0, true)
1267:     }
1268: 
1269:     private fun layoutLive() {
1270:         val s = liveSize
1271:         val cen = 0.4244f * s
1272:         val ih = dp(9f).toInt()
1273:         liveIconRect.set((-cen).toInt() - ih, (-cen).toInt() - ih, (-cen).toInt() + ih, (-cen).toInt() + ih)
1274:         val tr = s - dp(17f)
1275:         arcPath(liveTitlePath, tr, LV_A0, LV_A1)
1276:         liveTitleLen = tr * Math.toRadians((LV_A1 - LV_A0).toDouble()).toFloat()
1277:         // call title sits between the two dots
1278:         val call0 = 28f
1279:         arcPath(liveTitlePathCall, tr, call0, LV_A1 - (call0 - LV_A0))
1280:         liveCallLen = tr * Math.toRadians((LV_A1 - LV_A0 - 2f * (call0 - LV_A0)).toDouble()).toFloat()
1281:         liveDotR = dp(8f)
1282:         val dr = s - dp(16f)
1283:         val dA = Math.toRadians(15.0)
1284:         val aA = Math.toRadians(75.0)
1285:         liveDecX = -dr * cos(dA).toFloat()
1286:         liveDecY = -dr * sin(dA).toFloat()
1287:         liveAnsX = -dr * cos(aA).toFloat()
1288:         liveAnsY = -dr * sin(aA).toFloat()
1289:         val gh = dp(4.5f).toInt()
1290:         liveAnsRect.set(liveAnsX.toInt() - gh, liveAnsY.toInt() - gh, liveAnsX.toInt() + gh, liveAnsY.toInt() + gh)
1291:         liveDecRect.set(liveDecX.toInt() - gh, liveDecY.toInt() - gh, liveDecX.toInt() + gh, liveDecY.toInt() + gh)
1292:     }
1293: 
1294:     private fun ensureResources() {
1295:         if (resourcesReady) return
1296:         resourcesReady = true
1297:         val all = intArrayOf(
1298:             R.drawable.ic_play, R.drawable.ic_pause, R.drawable.ic_skip_back, R.drawable.ic_skip_forward,
1299:             R.drawable.ic_music, R.drawable.ic_clipboard, R.drawable.ic_phone, R.drawable.ic_phone_off,
1300:             R.drawable.ic_bell, R.drawable.ic_bolt
1301:         )
1302:         for (r in all) {
1303:             icon(r, false)
1304:             icon(r, true)
1305:         }
1306:     }
1307: 
1308:     internal fun icon(res: Int, black: Boolean): Drawable {
1309:         val cache = if (black) blackIcons else whiteIcons
1310:         var d = cache.get(res)
1311:         if (d == null) {
1312:             d = context.getDrawable(res)!!.mutate()
1313:             d.setTint(if (black) Color.BLACK else Color.WHITE)
1314:             cache.put(res, d)
1315:         }
1316:         return d
1317:     }
1318: 
1319:     internal fun warm(res: Int) {
1320:         if (res == 0) return
1321:         icon(res, false)
1322:         icon(res, true)
1323:     }
1324: 
1325:     private var lastDw = -1f
1326:     private var lastDh = -1f
1327:     private var lastW = -1
1328:     private var lastH = -1
1329:     private var lastTip = -1f
1330: 
1331:     // ================================================================ sources
1332: 
1333:     private fun ensureSources() {
1334:         if (sourcesStarted) return
1335:         sourcesStarted = true
1336:         syncArcs()
1337:         for (w in order) startWin(w)
1338:     }
1339: 
1340:     private val startSourcesRunnable = Runnable {
1341:         if (expandedTarget || dragMode != 0) {
1342:             ensureSources()
1343:             val mw = mediaWin
1344:             if (!userPaged && mw != null && mw.playing && page != mw.index && !gestureActive) goToPage(mw.index, 0f)
1345:             invalidate()
1346:         }
1347:     }
1348: 
1349:     private fun discardLayers() {
1350:         for (w in order) {
1351:             w.node?.discardDisplayList()
1352:             w.dirty = true
1353:         }
1354:     }
1355: 
1356:     private fun stopSources() {
1357:         discardLayers()
1358:         removeCallbacks(startSourcesRunnable)
1359:         if (!sourcesStarted) return
1360:         syncShown()
1361:         sourcesStarted = false
1362:         for (w in order) stopWin(w)
1363:         syncArcs()
1364:     }
1365: 
1366:     // ================================================================ region
1367: 
1368:     private fun publishRegion() {
1369:         val ww = width
1370:         val hh = height
1371:         if (hidden) {
1372:             region.setEmpty()
1373:         } else {
1374:             if (ww <= 0 || hh <= 0) return
1375:             val w: Float
1376:             val h: Float
1377:             val fl: Float
1378:             if (regionExpanded) {
1379:                 w = rad; h = rad
1380:                 fl = flareFor(rad, 1f)
1381:             } else {
1382:                 w = regionClosed; h = regionClosed
1383:                 fl = flareFor(regionClosed, 0f)
1384:             }
1385:             fillPath(regionPath, ww - w, hh - h, w, h, K, tipPx, fl)
1386:             clipRegion.set(0, 0, ww, hh)
1387:             region.setPath(regionPath, clipRegion)
1388:         }
1389:         host?.onRegionChanged(region)
1390:         updateExclusion()
1391:     }
1392: 
1393:     /** While open, keep the system Back gesture from stealing page swipes near the right screen edge. */
1394:     private fun updateExclusion() {
1395:         val want = regionExpanded && !hidden && width > 0 && height > 0
1396:         if (want) {
1397:             val s = ceil(rad + flareFor(rad, 1f)).toInt()
1398:             exclRect.set(max(0, width - s), max(0, height - s), width, height)
1399:             systemGestureExclusionRects = exclList
1400:             exclSet = true
1401:         } else if (exclSet) {
1402:             systemGestureExclusionRects = emptyList()
1403:             exclSet = false
1404:         }
1405:     }
1406: 
1407:     // ================================================================ paging
1408: 
1409:     /** Chooses the window to open on: media if playing, else the last used one. */
1410:     private fun pickPage() {
1411:         openPicked = true
1412:         if (contentAlpha > 0.2f) return // mid-collapse: keep what is on screen
1413:         userPaged = false
1414:         val mw = mediaWin
1415:         val li = indexOfId(lastPageId)
1416:         page = if (sourcesStarted && mw != null && mw.playing) mw.index else if (li >= 0) li else 0
1417:         pageS.snapTo(page.toFloat())
1418:     }
1419: 
1420:     private fun goToPage(p: Int, vel: Float) {
1421:         page = p.coerceIn(0, nWin - 1)
1422:         lastPageId = order[page].id
1423:         collapsing = false
1424:         settledExpanded = false
1425:         syncShown()
1426:         pageS.stiffness = 380f * stiffScale; pageS.dampingRatio = openDamp
1427:         pageS.target = page.toFloat()
1428:         pageS.velocity = vel
1429:         requestFrames()
1430:     }
1431: 
1432:     // ================================================================ animation
1433: 
1434:     private fun startExpand(setVel: Boolean, vw: Float, vh: Float) {
1435:         if (hidden) return
1436:         ensureResources()
1437:         val was = expandedTarget
1438:         expandedTarget = true
1439:         removeCallbacks(liveTickRunnable)
1440:         updateViz()
1441:         if (!was && idleStyleId == 3 && screenOn) readBattery()
1442:         collapsing = false
1443:         settledExpanded = false
1444:         syncShown()
1445:         if (!openPicked) pickPage()
1446:         // Binder-heavy listener setup is kept off the first animation frames.
1447:         removeCallbacks(startSourcesRunnable)
1448:         if (!sourcesStarted) postDelayed(startSourcesRunnable, 40L)
1449:         regionExpanded = true
1450:         publishRegion()
===== 1490-1785 =====
1490:         Choreographer.getInstance().postFrameCallback(frameCb)
1491:     }
1492: 
1493:     private fun doFrame(frameTimeNanos: Long) {
1494:         framePosted = false
1495:         val dt = if (lastFrameNanos == 0L) {
1496:             1f / 120f
1497:         } else {
1498:             ((frameTimeNanos - lastFrameNanos) * 1e-9f).coerceIn(0f, 1f / 30f)
1499:         }
1500:         lastFrameNanos = frameTimeNanos
1501:         wS.step(dt)
1502:         hS.step(dt)
1503:         pageS.step(dt)
1504:         press.step(dt)
1505:         updateDerived(dt)
1506:         invalidate()
1507:         if (allSettled()) {
1508:             wS.snapTo(wS.target)
1509:             hS.snapTo(hS.target)
1510:             pageS.snapTo(pageS.target)
1511:             press.snapTo(press.target)
1512:             updateDerived(0f)
1513:             lastFrameNanos = 0L
1514:             if (!gestureActive) finishSettle()
1515:         } else {
1516:             requestFrames()
1517:         }
1518:     }
1519: 
1520:     private fun allSettled(): Boolean =
1521:         wS.isSettled(0.3f, 15f) && hS.isSettled(0.3f, 15f) &&
1522:             pageS.isSettled(0.002f, 0.05f) && press.isSettled(0.002f, 0.1f)
1523: 
1524:     private fun finishSettle() {
1525:         if (expandedTarget) {
1526:             regionExpanded = true
1527:             settledExpanded = true
1528:             publishRegion()
1529:             host?.onSettled(true)
1530:             syncShown()
1531:         } else {
1532:             regionExpanded = false
1533:             settledExpanded = false
1534:             syncShown()
1535:             stopSources()
1536:             regionClosed = closedSize()
1537:             publishRegion()
1538:             host?.onSettled(false)
1539:             if (idleStyleId == 3 && screenOn) readBattery()
1540:             updateViz()
1541:             syncLiveTick()
1542:             syncClosedSize()
1543:             invalidateBean()
1544:         }
1545:     }
1546: 
1547:     private fun updateDerived(dt: Float) {
1548:         val span = max(rad - idle, 1f)
1549:         val p = min((wS.value - idle) / span, (hS.value - idle) / span).coerceIn(0f, 1f)
1550:         progress = p
1551:         val t = ((p - 0.55f) / 0.40f).coerceIn(0f, 1f)
1552:         val sm = t * t * (3f - 2f * t)
1553:         contentAlpha = if (collapsing) {
1554:             min(sm, max(0f, contentAlpha - dt / 0.09f))
1555:         } else {
1556:             sm
1557:         }
1558:     }
1559: 
1560:     // ================================================================ draw
1561: 
1562:     private fun buildLivePath() {
1563:         val pulse = if (beatOn && vizRunning) 1f + beat * (if (expandedTarget) 0.015f else 0.06f) else 1f
1564:         val ps = press.value * pulse
1565:         val dw = wS.value * ps
1566:         val dh = hS.value * ps
1567:         val ox = width - dw
1568:         val oy = height - dh
1569:         val pressGlow = ((1f - press.value) / (1f - PRESS_SCALE)).coerceIn(0f, 1f)
1570:         glow = max(max(pressGlow, if (dragMode != 0 || captureWin != null) 0.85f else 0f), max(progress * 0.4f, if (beatOn && vizRunning) beat * 0.55f else 0f))
1571:         val fl = flareFor(min(dw, dh), progress)
1572:         // Static shape: keep the Path objects untouched so HWUI reuses their cached geometry.
1573:         if (dw == lastDw && dh == lastDh && width == lastW && height == lastH && tipPx == lastTip && fl == lastFl) return
1574:         lastDw = dw; lastDh = dh; lastW = width; lastH = height; lastTip = tipPx; lastFl = fl
1575:         fillPath(path, ox, oy, dw, dh, K, tipPx, fl)
1576:         rimPath.rewind()
1577:         rimPath.moveTo(ox, height.toFloat())
1578:         rimPath.cubicTo(ox, height - K * dh, width - K * dw, oy, width.toFloat(), oy)
1579:         val r = max(dw, dh) * 1.15f / GR
1580:         gradScale = r
1581:         gradMatrix.setTranslate(-width.toFloat(), -height.toFloat())
1582:         gradMatrix.postScale(1f / r, 1f / r)
1583:         path.transform(gradMatrix, unitPath)
1584:     }
1585: 
1586:     override fun onDraw(c: Canvas) {
1587:         buildLivePath()
1588:         val g = glow
1589:         drawShapeFill(c, g)
1590:         if (rimOn) {
1591:             val ra = RIM_REST_A + (RIM_HOT_A - RIM_REST_A) * g
1592:             rimPaint.color = (mixColor(Color.WHITE, accent, g) and 0xFFFFFF) or ((255f * ra + 0.5f).toInt() shl 24)
1593:             c.drawPath(rimPath, rimPaint)
1594:         }
1595:         val ia = 1f - progress * 3f
1596:         if (liveWanted && !expandedTarget) {
1597:             drawLive(c)
1598:         } else if (ia > 0.01f && !liveWanted) {
1599:             drawIdle(c, ia) // LOOKS hook: idle decoration (drawIdle)
1600:         }
1601:         val a = contentAlpha
1602:         if (a > 0.004f && resourcesReady) drawContent(c, a)
1603:     }
1604: 
1605:     /** LOOKS hook: the shape fill (gradient today; fills and beat reaction plug in here). */
1606:     private fun drawShapeFill(c: Canvas, g: Float) {
1607:         c.save()
1608:         c.translate(width.toFloat(), height.toFloat())
1609:         c.scale(gradScale, gradScale)
1610:         c.drawPath(unitPath, shapePaint)
1611:         if (glowOn && g > 0.01f) {
1612:             glowPaint.alpha = (255f * g + 0.5f).toInt()
1613:             c.drawPath(unitPath, glowPaint)
1614:         }
1615:         c.restore()
1616:     }
1617: 
1618:     private fun drawContent(c: Canvas, a: Float) {
1619:         if (!c.isHardwareAccelerated) {
1620:             drawContentDirect(c, a)
1621:             return
1622:         }
1623:         val off = (1f - a) * dp(16f)
1624:         // content grows with the melting shape, so no clip is needed
1625:         val cs = (min(wS.value, hS.value) / rad).coerceIn(0f, 1f)
1626:         val pos = pageS.value
1627:         val s = nodeS
1628:         val sf = s.toFloat()
1629:         val w = width
1630:         val h = height
1631:         val mw = mediaWin
1632:         if (mw != null && beatOn && vizRunning && beat > 0.03f && abs(pos - mw.index) < 0.6f) {
1633:             val fa = pageFade(pos - mw.index)
1634:             c.save()
1635:             c.translate(w + off, h + off)
1636:             c.scale(cs, cs)
1637:             c.rotate((mw.index - pos) * PAGE_DEG)
1638:             arcPaint.color = accent
1639:             arcPaint.strokeWidth = dp(5f) + beat * dp(5f)
1640:             arcPaint.alpha = (255f * 0.38f * beat * a * fa + 0.5f).toInt()
1641:             c.drawCircle(mw.artX, mw.artY, mw.artRad + dp(3f) + beat * dp(5f), arcPaint)
1642:             c.restore()
1643:         }
1644:         for (i in 0 until nWin) {
1645:             val win = order[i]
1646:             val d = i - pos
1647:             val fa = pageFade(d)
1648:             if (fa <= 0.004f) continue
1649:             var n = win.node
1650:             if (n == null) {
1651:                 n = RenderNode("edgeWin" + win.id)
1652:                 n.setUseCompositingLayer(true, null)
1653:                 n.setClipToBounds(true)
1654:                 win.node = n
1655:             }
1656:             n.setPosition(w - s, h - s, w, h)
1657:             // A node skipped while faded out loses its display list: re-record it when it comes back.
1658:             if (win.dirty || !n.hasDisplayList()) {
1659:                 val rc = n.beginRecording(s, s)
1660:                 try {
1661:                     rc.translate(sf, sf)
1662:                     drawWin(rc, win, 1f)
1663:                 } finally {
1664:                     n.endRecording()
1665:                 }
1666:                 win.dirty = false
1667:             }
1668:             n.setPivotX(sf)
1669:             n.setPivotY(sf)
1670:             n.setRotationZ(d * PAGE_DEG)
1671:             n.setAlpha(a * fa)
1672:             n.setScaleX(cs)
1673:             n.setScaleY(cs)
1674:             n.setTranslationX(off)
1675:             n.setTranslationY(off)
1676:             c.drawRenderNode(n)
1677:         }
1678:         c.save()
1679:         c.translate(w + off, h + off)
1680:         c.scale(cs, cs)
1681:         drawPageBars(c, a)
1682:         drawClose(c, a)
1683:         c.restore()
1684:     }
1685: 
1686:     /** Neighbour-window fade: exactly 0 once a page is settled (|d| >= 0.85), ~55% each mid-swipe. */
1687:     private fun pageFade(d: Float): Float {
1688:         val t = (1f - abs(d) / 0.85f).coerceIn(0f, 1f)
1689:         return t * t * (3f - 2f * t)
1690:     }
1691: 
1692:     private fun drawContentDirect(c: Canvas, a: Float) {
1693:         c.save()
1694:         c.clipPath(path)
1695:         val off = (1f - a) * dp(16f)
1696:         val s = 0.96f + 0.04f * a
1697:         c.translate(width + off, height + off)
1698:         c.scale(s, s)
1699:         val pos = pageS.value
1700:         for (i in 0 until nWin) {
1701:             val d = i - pos
1702:             val fa = pageFade(d)
1703:             if (fa <= 0.004f) continue
1704:             c.save()
1705:             c.rotate(d * PAGE_DEG)
1706:             drawWin(c, order[i], a * fa)
1707:             c.restore()
1708:         }
1709:         drawPageBars(c, a)
1710:         drawClose(c, a)
1711:         c.restore()
1712:     }
1713: 
1714:     private fun drawWin(c: Canvas, w: Win, a: Float) {
1715:         w.drawBands(c, a)
1716:         w.draw(c, a)
1717:     }
1718: 
1719:     private fun drawPageBars(c: Canvas, a: Float) {
1720:         // Short curved bars just outside the close X (one per enabled window). The accent highlight is one arc
1721:         // whose ends are interpolated from the continuous page position (stretches mid-transition).
1722:         val n = nWin
1723:         if (n < 2) return
1724:         val xd = kotlin.math.hypot(xCx, xCy)
1725:         val rd = xd + xRad + dp(7f)
1726:         val ai = (255f * a + 0.5f).toInt()
1727:         // 7.5 deg bars at 4 deg gaps, total span clamped to 28..62 deg and centred on 45 deg
1728:         var seg = 7.5f
1729:         val gap = BAR_GAP
1730:         var total = n * seg + (n - 1) * gap
1731:         if (total > 62f) {
1732:             seg = (62f - (n - 1) * gap) / n
1733:             total = 62f
1734:         } else if (total < 28f) {
1735:             seg = (28f - (n - 1) * gap) / n
1736:             total = 28f
1737:         }
1738:         val a0 = 45f - total / 2f
1739:         val step = seg + gap
1740:         val cap = Math.toDegrees((dp(1.5f) / rd).toDouble()).toFloat().coerceAtMost(seg / 3f)
1741:         xOval.set(-rd, -rd, rd, rd)
1742:         arcPaint.strokeWidth = dp(3f)
1743:         arcPaint.color = C_BAR
1744:         arcPaint.alpha = (255f * 0.6f * a + 0.5f).toInt()
1745:         for (i in 0 until n) {
1746:             c.drawArc(xOval, 180f + a0 + i * step + cap, seg - 2f * cap, false, arcPaint)
1747:         }
1748:         val pos = pageS.value.coerceIn(0f, (n - 1).toFloat())
1749:         val i0 = min(pos.toInt(), n - 2)
1750:         val f = pos - i0
1751:         val bulge = sin(Math.PI * f).toFloat() * 3f
1752:         val st = a0 + (i0 + f) * step - bulge + cap
1753:         val en = a0 + (i0 + f) * step + seg + bulge - cap
1754:         arcPaint.color = accent
1755:         arcPaint.alpha = ai
1756:         c.drawArc(xOval, 180f + st, en - st, false, arcPaint)
1757:     }
1758: 
1759:     private fun drawClose(c: Canvas, a: Float) {
1760:         val cx = xCx
1761:         val cy = xCy
1762:         val sc = 0.6f + 0.4f * a
1763:         val ai = (255f * a + 0.5f).toInt()
1764:         val px = pressed == ID_X && pressedWin == null
1765:         c.save()
1766:         c.scale(sc * (if (px) PRESS_SCALE else 1f), sc * (if (px) PRESS_SCALE else 1f), cx, cy)
1767:         fill.color = C_FILL
1768:         fill.alpha = ai
1769:         c.drawCircle(cx, cy, xRad, fill)
1770:         if (px) {
1771:             ringPaint.alpha = ai
1772:             c.drawCircle(cx, cy, xRad + dp(3f), ringPaint)
1773:         }
1774:         linePaint.color = Color.WHITE
1775:         linePaint.alpha = ai
1776:         linePaint.strokeWidth = dp(1.8f)
1777:         val h = dp(7f)
1778:         c.drawLine(cx - h, cy - h, cx + h, cy + h, linePaint)
1779:         c.drawLine(cx - h, cy + h, cx + h, cy - h, linePaint)
1780:         c.restore()
1781:     }
1782: 
1783:     // ================================================================ touch
1784: 
1785:     private fun addMove(ev: MotionEvent, rx: Float, ry: Float) {
===== 1785-2130 =====
1785:     private fun addMove(ev: MotionEvent, rx: Float, ry: Float) {
1786:         val t = vt ?: return
1787:         // The window may resize mid-gesture, so feed the tracker raw (screen) coordinates.
1788:         val lx = ev.x
1789:         val ly = ev.y
1790:         ev.setLocation(rx, ry)
1791:         t.addMovement(ev)
1792:         ev.setLocation(lx, ly) // restore: callers read ev.x / ev.y in view coordinates afterwards
1793:     }
1794: 
1795:     private fun releaseTracker() {
1796:         vt?.recycle()
1797:         vt = null
1798:     }
1799: 
1800:     private fun cancelItemLongPress() {
1801:         removeCallbacks(longPressRunnable)
1802:     }
1803: 
1804:     override fun onTouchEvent(ev: MotionEvent): Boolean {
1805:         val action = ev.actionMasked
1806:         if (action == MotionEvent.ACTION_OUTSIDE) {
1807:             if (expandedTarget && !gestureActive && !holdOpen) startCollapse(false, 0f, 0f)
1808:             return false
1809:         }
1810:         if (hidden) return false
1811:         when (action) {
1812:             MotionEvent.ACTION_DOWN -> return handleDown(ev)
1813:             MotionEvent.ACTION_MOVE -> {
1814:                 if (!gestureActive) return false
1815:                 handleMove(ev)
1816:             }
1817:             MotionEvent.ACTION_UP -> {
1818:                 if (!gestureActive) return false
1819:                 handleUp(ev, false)
1820:             }
1821:             MotionEvent.ACTION_CANCEL -> {
1822:                 if (!gestureActive) return false
1823:                 handleUp(ev, true)
1824:             }
1825:         }
1826:         return true
1827:     }
1828: 
1829:     private fun handleDown(ev: MotionEvent): Boolean {
1830:         val x = ev.x
1831:         val y = ev.y
1832:         val rx = ev.rawX
1833:         val ry = ev.rawY
1834:         ensureResources()
1835:         buildLivePath()
1836:         clipRegion.set(0, 0, max(width, 1), max(height, 1))
1837:         hitRegion.setPath(path, clipRegion)
1838:         if (!hitRegion.contains(x.toInt(), y.toInt())) return false
1839: 
1840:         releaseTracker()
1841:         vt = VelocityTracker.obtain()
1842:         gestureActive = true
1843:         downRawX = rx
1844:         downRawY = ry
1845:         moved = false
1846:         dragMode = 0
1847:         captureWin = null
1848:         clearPressed()
1849:         longFired = false
1850:         addMove(ev, rx, ry)
1851: 
1852:         if (!expandedTarget) {
1853:             startMode = MODE_IDLE
1854:             updateViz()
1855:             removeCallbacks(liveTickRunnable)
1856:             // Pay the listener-registration cost during the finger-down, before the expand animation.
1857:             ensureSources()
1858:             press.target = PRESS_SCALE
1859:             requestFrames()
1860:         } else {
1861:             startMode = MODE_EXPANDED
1862:             baseW = wS.value
1863:             baseH = hS.value
1864:             pageStart = pageS.value
1865:             if (contentAlpha > 0.5f) {
1866:                 val item = hitItem(x, y)
1867:                 if (item == ID_X) {
1868:                     setPressed(null, ID_X)
1869:                     invalidate()
1870:                 } else if (item >= 0) {
1871:                     val w = hitWin
1872:                     if (w != null && w.usable(item)) {
1873:                         setPressed(w, item)
1874:                         if (w.longPressable(item)) postDelayed(longPressRunnable, longPressMs)
1875:                         invalidate()
1876:                     }
1877:                 } else {
1878:                     // a DOWN on a slider / dial / seek bar claims the whole gesture
1879:                     val w = settledWin(x, y)
1880:                     if (w != null && w.capture(lpX, lpY)) {
1881:                         captureWin = w
1882:                         moved = true
1883:                         w.dragTo(lpX, lpY)
1884:                         invalidate()
1885:                     }
1886:                 }
1887:             }
1888:         }
1889:         return true
1890:     }
1891: 
1892:     /** The window under the finger when a page is settled (sets lpX/lpY to its local frame), else null. */
1893:     private fun settledWin(x: Float, y: Float): Win? {
1894:         if (nWin == 0) return null
1895:         val pos = pageS.value
1896:         val s = Math.round(pos).coerceIn(0, nWin - 1)
1897:         if (abs(pos - s) > 0.2f) return null
1898:         toLocal(x, y, s - pos)
1899:         return order[s]
1900:     }
1901: 
1902:     /** Hit test: ID_X, a window-local item (hitWin set), or -1. */
1903:     private fun hitItem(x: Float, y: Float): Int {
1904:         hitWin = null
1905:         val px = x - width
1906:         val py = y - height
1907:         if ((px - xCx) * (px - xCx) + (py - xCy) * (py - xCy) <= xHit * xHit) return ID_X
1908:         val w = settledWin(x, y) ?: return -1
1909:         val i = w.hit(lpX, lpY)
1910:         if (i >= 0) hitWin = w
1911:         return i
1912:     }
1913: 
1914:     /** Converts a view point to the local (un-rotated) polar frame of a window page d pages away. */
1915:     private fun toLocal(x: Float, y: Float, d: Float) {
1916:         val px = x - width
1917:         val py = y - height
1918:         val ang = Math.toRadians((-d * PAGE_DEG).toDouble())
1919:         val cs = cos(ang).toFloat()
1920:         val sn = sin(ang).toFloat()
1921:         lpX = px * cs - py * sn
1922:         lpY = px * sn + py * cs
1923:     }
1924: 
1925:     private fun handleMove(ev: MotionEvent) {
1926:         val rx = ev.rawX
1927:         val ry = ev.rawY
1928:         addMove(ev, rx, ry)
1929:         val cw = captureWin
1930:         if (cw != null) {
1931:             moved = true
1932:             toLocal(ev.x, ev.y, cw.index - pageS.value)
1933:             cw.dragTo(lpX, lpY)
1934:             return
1935:         }
1936:         val dx = rx - downRawX
1937:         val dy = ry - downRawY
1938:         if (dragMode == 0) {
1939:             if (sqrt(dx * dx + dy * dy) > touchSlop) {
1940:                 moved = true
1941:                 cancelItemLongPress()
1942:                 if (pressed >= 0) {
1943:                     clearPressed()
1944:                     invalidate()
1945:                 }
1946:                 if (startMode == MODE_IDLE) {
1947:                     if (max(-dx, -dy) > touchSlop) beginDrag(1)
1948:                 } else if (abs(dx) > abs(dy) && abs(dx) > touchSlop) {
1949:                     beginDrag(3)
1950:                 } else if (dy > abs(dx) && dy > touchSlop) {
1951:                     beginDrag(2)
1952:                 }
1953:             }
1954:         }
1955:         if (dragMode != 0) applyDrag(dx, dy)
1956:     }
1957: 
1958:     private fun beginDrag(mode: Int) {
1959:         dragMode = mode
1960:         ensureResources()
1961:         collapsing = false
1962:         settledExpanded = false
1963:         syncShown()
1964:         removeCallbacks(liveTickRunnable)
1965:         if (mode == 1 && !openPicked) pickPage()
1966:         removeCallbacks(startSourcesRunnable)
1967:         if (!sourcesStarted) postDelayed(startSourcesRunnable, 40L)
1968:         regionExpanded = true
1969:         publishRegion()
1970:         updateViz()
1971:         when (mode) {
1972:             1 -> {
1973:                 baseW = max(idle, wS.value)
1974:                 baseH = max(idle, hS.value)
1975:             }
1976:             2 -> {
1977:                 baseW = wS.value
1978:                 baseH = hS.value
1979:             }
1980:             else -> {
1981:                 pageStart = pageS.value
1982:                 dragShift = max(0.9f * rad, 1f)
1983:                 pageS.velocity = 0f
1984:             }
1985:         }
1986:         press.target = 1f
1987:         requestFrames()
1988:     }
1989: 
1990:     private fun rubber(v: Float, maxV: Float): Float {
1991:         if (v <= idle) return idle
1992:         if (v <= maxV) return v
1993:         return maxV + min((v - maxV) * 0.3f, dp(18f))
1994:     }
1995: 
1996:     private fun setSizeDirect(w: Float, h: Float) {
1997:         wS.value = w; wS.target = w; wS.velocity = 0f
1998:         hS.value = h; hS.target = h; hS.velocity = 0f
1999:     }
2000: 
2001:     private fun applyDrag(dx: Float, dy: Float) {
2002:         when (dragMode) {
2003:             1 -> setSizeDirect(rubber(baseW - dx, rad), rubber(baseH - dy, rad))
2004:             2 -> {
2005:                 val hh = rubber(baseH - dy, baseH)
2006:                 val fr = (hh - idle) / max(baseH - idle, 1f)
2007:                 setSizeDirect(idle + (baseW - idle) * fr, hh)
2008:             }
2009:             3 -> {
2010:                 val last = (nWin - 1).toFloat()
2011:                 var pos = pageStart - dx / dragShift
2012:                 if (pos < 0f) pos *= 0.3f
2013:                 else if (pos > last) pos = last + (pos - last) * 0.3f
2014:                 pageS.value = pos; pageS.target = pos; pageS.velocity = 0f
2015:             }
2016:         }
2017:         updateDerived(0f)
2018:         invalidate()
2019:     }
2020: 
2021:     private fun finishDrag(vx: Float, vy: Float) {
2022:         val mode = dragMode
2023:         val outward = if (mode == 2) -vy else -(vx + vy) * 0.5f
2024:         val thr = dp(500f)
2025:         val doExpand = when {
2026:             outward > thr -> true
2027:             outward < -thr -> false
2028:             mode == 1 -> progress > 0.33f
2029:             else -> progress > 0.67f
2030:         }
2031:         dragMode = 0
2032:         val vh = -vy
2033:         val vw = if (mode == 2) -vy * (baseW - idle) / max(baseH - idle, 1f) else -vx
2034:         if (doExpand) startExpand(true, vw, vh) else startCollapse(true, vw, vh)
2035:     }
2036: 
2037:     private fun finishPage(dx: Float, vx: Float) {
2038:         val base = Math.round(pageStart).coerceIn(0, nWin - 1)
2039:         val thr = 0.28f * dragShift
2040:         val vthr = dp(500f)
2041:         var target = base
2042:         if (dx < -thr || vx < -vthr) target = base + 1
2043:         else if (dx > thr || vx > vthr) target = base - 1
2044:         target = target.coerceIn(0, nWin - 1)
2045:         if (target != base) userPaged = true
2046:         dragMode = 0
2047:         goToPage(target, -vx / dragShift)
2048:     }
2049: 
2050:     private fun handleUp(ev: MotionEvent, cancel: Boolean) {
2051:         val rx = ev.rawX
2052:         val ry = ev.rawY
2053:         val x = ev.x
2054:         val y = ev.y
2055:         cancelItemLongPress()
2056:         var vx = 0f
2057:         var vy = 0f
2058:         val t = vt
2059:         if (t != null) {
2060:             if (!cancel) addMove(ev, rx, ry)
2061:             t.computeCurrentVelocity(1000)
2062:             vx = t.xVelocity
2063:             vy = t.yVelocity
2064:         }
2065:         releaseTracker()
2066:         val item = pressed
2067:         val pw = pressedWin
2068:         clearPressed()
2069:         gestureActive = false
2070:         val quick = !moved && (ev.eventTime - ev.downTime) < TAP_MS
2071: 
2072:         val cw = captureWin
2073:         if (cw != null) {
2074:             captureWin = null
2075:             cw.dragEnd(cancel)
2076:         } else if (dragMode == 3) {
2077:             finishPage(if (cancel) 0f else rx - downRawX, if (cancel) 0f else vx)
2078:         } else if (dragMode != 0) {
2079:             finishDrag(if (cancel) 0f else vx, if (cancel) 0f else vy)
2080:         } else if (startMode == MODE_IDLE) {
2081:             press.target = 1f
2082:             if (!cancel && quick) onBeanTap(x, y)
2083:         } else if (!cancel && !longFired && !moved && item >= 0 && hitItem(x, y) == item && hitWin === pw) {
2084:             if (item == ID_X) startCollapse(false, 0f, 0f) else pw?.onTap(item)
2085:         }
2086:         updateViz()
2087:         syncClosedSize()
2088:         syncLiveTick()
2089:         invalidate()
2090:         requestFrames()
2091:     }
2092: 
2093:     private fun onLongPress() {
2094:         val item = pressed
2095:         val w = pressedWin
2096:         if (!gestureActive || w == null || !w.longPressable(item)) return
2097:         clearPressed()
2098:         longFired = true
2099:         haptic(HapticFeedbackConstants.LONG_PRESS)
2100:         val launched = w.onLongPress(item)
2101:         invalidate()
2102:         if (launched) startCollapse(false, 0f, 0f)
2103:     }
2104: 
2105:     // ================================================================ init
2106: 
2107:     init {
2108:         applyLooksNow()
2109:         reloadWindows()
2110:     }
2111: 
2112:     internal companion object {
2113:         const val K = 0.5523f
2114:         const val PRESS_SCALE = 0.92f
2115:         const val TAP_MS = 300L
2116:         const val MODE_IDLE = 0
2117:         const val MODE_EXPANDED = 1
2118:         const val ID_X = 1000
2119:         const val PAGE_DEG = 70f
2120:         const val SL_A0 = 8f
2121:         const val SL_A1 = 82f
2122:         const val LV_A0 = 16f
2123:         const val LV_A1 = 74f
2124:         const val LIVE_SCALE = 1.55f
2125:         val C_FILL = Color.parseColor("#1C1C1E")
2126:         val C_TRACK = Color.parseColor("#2C2C2E")
2127:         val C_GREY = Color.parseColor("#8E8E93")
2128:         val C_RED = Color.parseColor("#FF4B3E")
2129:         val C_BAR = Color.parseColor("#5A5A5E")
2130:         val C_CREAM = Color.parseColor("#F2EDE4")

exec
"C:\\WINDOWS\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" -Command '$f='"'app/src/main/java/com/dynedge/EdgeView.kt'; "'$l=Get-Content $f; foreach($i in 1490..1718){ '"'{0,4}: {1}' -f "'$i,$l[$i-1] }; $f='"'app/src/main/java/com/dynedge/MainActivity.kt'; "'$l=Get-Content $f; foreach($i in 80..155){ '"'{0,4}: {1}' -f "'$i,$l[$i-1] }; foreach($i in 490..590){ '"'{0,4}: {1}' -f "'$i,$l[$i-1] };' in D:\VS\PROJECTS\FREE WORK\dynamic-edge
 succeeded in 165ms:
1490:         Choreographer.getInstance().postFrameCallback(frameCb)
1491:     }
1492: 
1493:     private fun doFrame(frameTimeNanos: Long) {
1494:         framePosted = false
1495:         val dt = if (lastFrameNanos == 0L) {
1496:             1f / 120f
1497:         } else {
1498:             ((frameTimeNanos - lastFrameNanos) * 1e-9f).coerceIn(0f, 1f / 30f)
1499:         }
1500:         lastFrameNanos = frameTimeNanos
1501:         wS.step(dt)
1502:         hS.step(dt)
1503:         pageS.step(dt)
1504:         press.step(dt)
1505:         updateDerived(dt)
1506:         invalidate()
1507:         if (allSettled()) {
1508:             wS.snapTo(wS.target)
1509:             hS.snapTo(hS.target)
1510:             pageS.snapTo(pageS.target)
1511:             press.snapTo(press.target)
1512:             updateDerived(0f)
1513:             lastFrameNanos = 0L
1514:             if (!gestureActive) finishSettle()
1515:         } else {
1516:             requestFrames()
1517:         }
1518:     }
1519: 
1520:     private fun allSettled(): Boolean =
1521:         wS.isSettled(0.3f, 15f) && hS.isSettled(0.3f, 15f) &&
1522:             pageS.isSettled(0.002f, 0.05f) && press.isSettled(0.002f, 0.1f)
1523: 
1524:     private fun finishSettle() {
1525:         if (expandedTarget) {
1526:             regionExpanded = true
1527:             settledExpanded = true
1528:             publishRegion()
1529:             host?.onSettled(true)
1530:             syncShown()
1531:         } else {
1532:             regionExpanded = false
1533:             settledExpanded = false
1534:             syncShown()
1535:             stopSources()
1536:             regionClosed = closedSize()
1537:             publishRegion()
1538:             host?.onSettled(false)
1539:             if (idleStyleId == 3 && screenOn) readBattery()
1540:             updateViz()
1541:             syncLiveTick()
1542:             syncClosedSize()
1543:             invalidateBean()
1544:         }
1545:     }
1546: 
1547:     private fun updateDerived(dt: Float) {
1548:         val span = max(rad - idle, 1f)
1549:         val p = min((wS.value - idle) / span, (hS.value - idle) / span).coerceIn(0f, 1f)
1550:         progress = p
1551:         val t = ((p - 0.55f) / 0.40f).coerceIn(0f, 1f)
1552:         val sm = t * t * (3f - 2f * t)
1553:         contentAlpha = if (collapsing) {
1554:             min(sm, max(0f, contentAlpha - dt / 0.09f))
1555:         } else {
1556:             sm
1557:         }
1558:     }
1559: 
1560:     // ================================================================ draw
1561: 
1562:     private fun buildLivePath() {
1563:         val pulse = if (beatOn && vizRunning) 1f + beat * (if (expandedTarget) 0.015f else 0.06f) else 1f
1564:         val ps = press.value * pulse
1565:         val dw = wS.value * ps
1566:         val dh = hS.value * ps
1567:         val ox = width - dw
1568:         val oy = height - dh
1569:         val pressGlow = ((1f - press.value) / (1f - PRESS_SCALE)).coerceIn(0f, 1f)
1570:         glow = max(max(pressGlow, if (dragMode != 0 || captureWin != null) 0.85f else 0f), max(progress * 0.4f, if (beatOn && vizRunning) beat * 0.55f else 0f))
1571:         val fl = flareFor(min(dw, dh), progress)
1572:         // Static shape: keep the Path objects untouched so HWUI reuses their cached geometry.
1573:         if (dw == lastDw && dh == lastDh && width == lastW && height == lastH && tipPx == lastTip && fl == lastFl) return
1574:         lastDw = dw; lastDh = dh; lastW = width; lastH = height; lastTip = tipPx; lastFl = fl
1575:         fillPath(path, ox, oy, dw, dh, K, tipPx, fl)
1576:         rimPath.rewind()
1577:         rimPath.moveTo(ox, height.toFloat())
1578:         rimPath.cubicTo(ox, height - K * dh, width - K * dw, oy, width.toFloat(), oy)
1579:         val r = max(dw, dh) * 1.15f / GR
1580:         gradScale = r
1581:         gradMatrix.setTranslate(-width.toFloat(), -height.toFloat())
1582:         gradMatrix.postScale(1f / r, 1f / r)
1583:         path.transform(gradMatrix, unitPath)
1584:     }
1585: 
1586:     override fun onDraw(c: Canvas) {
1587:         buildLivePath()
1588:         val g = glow
1589:         drawShapeFill(c, g)
1590:         if (rimOn) {
1591:             val ra = RIM_REST_A + (RIM_HOT_A - RIM_REST_A) * g
1592:             rimPaint.color = (mixColor(Color.WHITE, accent, g) and 0xFFFFFF) or ((255f * ra + 0.5f).toInt() shl 24)
1593:             c.drawPath(rimPath, rimPaint)
1594:         }
1595:         val ia = 1f - progress * 3f
1596:         if (liveWanted && !expandedTarget) {
1597:             drawLive(c)
1598:         } else if (ia > 0.01f && !liveWanted) {
1599:             drawIdle(c, ia) // LOOKS hook: idle decoration (drawIdle)
1600:         }
1601:         val a = contentAlpha
1602:         if (a > 0.004f && resourcesReady) drawContent(c, a)
1603:     }
1604: 
1605:     /** LOOKS hook: the shape fill (gradient today; fills and beat reaction plug in here). */
1606:     private fun drawShapeFill(c: Canvas, g: Float) {
1607:         c.save()
1608:         c.translate(width.toFloat(), height.toFloat())
1609:         c.scale(gradScale, gradScale)
1610:         c.drawPath(unitPath, shapePaint)
1611:         if (glowOn && g > 0.01f) {
1612:             glowPaint.alpha = (255f * g + 0.5f).toInt()
1613:             c.drawPath(unitPath, glowPaint)
1614:         }
1615:         c.restore()
1616:     }
1617: 
1618:     private fun drawContent(c: Canvas, a: Float) {
1619:         if (!c.isHardwareAccelerated) {
1620:             drawContentDirect(c, a)
1621:             return
1622:         }
1623:         val off = (1f - a) * dp(16f)
1624:         // content grows with the melting shape, so no clip is needed
1625:         val cs = (min(wS.value, hS.value) / rad).coerceIn(0f, 1f)
1626:         val pos = pageS.value
1627:         val s = nodeS
1628:         val sf = s.toFloat()
1629:         val w = width
1630:         val h = height
1631:         val mw = mediaWin
1632:         if (mw != null && beatOn && vizRunning && beat > 0.03f && abs(pos - mw.index) < 0.6f) {
1633:             val fa = pageFade(pos - mw.index)
1634:             c.save()
1635:             c.translate(w + off, h + off)
1636:             c.scale(cs, cs)
1637:             c.rotate((mw.index - pos) * PAGE_DEG)
1638:             arcPaint.color = accent
1639:             arcPaint.strokeWidth = dp(5f) + beat * dp(5f)
1640:             arcPaint.alpha = (255f * 0.38f * beat * a * fa + 0.5f).toInt()
1641:             c.drawCircle(mw.artX, mw.artY, mw.artRad + dp(3f) + beat * dp(5f), arcPaint)
1642:             c.restore()
1643:         }
1644:         for (i in 0 until nWin) {
1645:             val win = order[i]
1646:             val d = i - pos
1647:             val fa = pageFade(d)
1648:             if (fa <= 0.004f) continue
1649:             var n = win.node
1650:             if (n == null) {
1651:                 n = RenderNode("edgeWin" + win.id)
1652:                 n.setUseCompositingLayer(true, null)
1653:                 n.setClipToBounds(true)
1654:                 win.node = n
1655:             }
1656:             n.setPosition(w - s, h - s, w, h)
1657:             // A node skipped while faded out loses its display list: re-record it when it comes back.
1658:             if (win.dirty || !n.hasDisplayList()) {
1659:                 val rc = n.beginRecording(s, s)
1660:                 try {
1661:                     rc.translate(sf, sf)
1662:                     drawWin(rc, win, 1f)
1663:                 } finally {
1664:                     n.endRecording()
1665:                 }
1666:                 win.dirty = false
1667:             }
1668:             n.setPivotX(sf)
1669:             n.setPivotY(sf)
1670:             n.setRotationZ(d * PAGE_DEG)
1671:             n.setAlpha(a * fa)
1672:             n.setScaleX(cs)
1673:             n.setScaleY(cs)
1674:             n.setTranslationX(off)
1675:             n.setTranslationY(off)
1676:             c.drawRenderNode(n)
1677:         }
1678:         c.save()
1679:         c.translate(w + off, h + off)
1680:         c.scale(cs, cs)
1681:         drawPageBars(c, a)
1682:         drawClose(c, a)
1683:         c.restore()
1684:     }
1685: 
1686:     /** Neighbour-window fade: exactly 0 once a page is settled (|d| >= 0.85), ~55% each mid-swipe. */
1687:     private fun pageFade(d: Float): Float {
1688:         val t = (1f - abs(d) / 0.85f).coerceIn(0f, 1f)
1689:         return t * t * (3f - 2f * t)
1690:     }
1691: 
1692:     private fun drawContentDirect(c: Canvas, a: Float) {
1693:         c.save()
1694:         c.clipPath(path)
1695:         val off = (1f - a) * dp(16f)
1696:         val s = 0.96f + 0.04f * a
1697:         c.translate(width + off, height + off)
1698:         c.scale(s, s)
1699:         val pos = pageS.value
1700:         for (i in 0 until nWin) {
1701:             val d = i - pos
1702:             val fa = pageFade(d)
1703:             if (fa <= 0.004f) continue
1704:             c.save()
1705:             c.rotate(d * PAGE_DEG)
1706:             drawWin(c, order[i], a * fa)
1707:             c.restore()
1708:         }
1709:         drawPageBars(c, a)
1710:         drawClose(c, a)
1711:         c.restore()
1712:     }
1713: 
1714:     private fun drawWin(c: Canvas, w: Win, a: Float) {
1715:         w.drawBands(c, a)
1716:         w.draw(c, a)
1717:     }
1718: 
  80:         val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
  81: 
  82:         content.addView(text("Dynamic Edge", 22f, white, Typeface.DEFAULT_BOLD).apply {
  83:             setPadding(dp(16), dp(20), dp(16), dp(0))
  84:         })
  85: 
  86:         // SIZE (first, so the live preview does not cover the slider being dragged)
  87:         header(content, "SIZE")
  88:         addSlider(content, "Corner size", Prefs.IDLE_MIN, Prefs.IDLE_MAX, Prefs.idleDp(this), { "${it.toInt()} dp" },
  89:             Prefs.PREVIEW_IDLE, { Prefs.setIdleDp(this, it) })
  90:         addSlider(content, "Open size", Prefs.OPEN_MIN, Prefs.OPEN_MAX, Prefs.openDp(this), { "${it.toInt()} dp" },
  91:             Prefs.PREVIEW_OPEN, { Prefs.setOpenDp(this, it) })
  92: 
  93:         // WINDOWS (on/off + order; long-press a row and drag)
  94:         header(content, "WINDOWS")
  95:         addWindows(content)
  96: 
  97:         // EDGE (layout editor, clipboard history, live activities)
  98:         header(content, "EDGE")
  99:         content.addView(simpleRow("Quick layout", ">", grey) {
 100:             open { Intent(this, QuickEditorActivity::class.java) }
 101:         })
 102:         sep(content)
 103:         content.addView(simpleRow("Clipboard", ">", grey) {
 104:             open { Intent(this, ClipboardActivity::class.java) }
 105:         })
 106:         sep(content)
 107:         addSwitch(content, "Live activities", Prefs.live(this)) { edit().putBoolean(Prefs.KEY_LIVE, it).apply() }
 108: 
 109:         // LOOKS
 110:         header(content, "LOOKS")
 111:         addSwatches(content)
 112:         addSlider(content, "Corner gradient", 0f, 100f, Prefs.gradient(this).toFloat(), { "${it.toInt()}%" },
 113:             Prefs.PREVIEW_OPEN, { edit().putInt(Prefs.KEY_GRADIENT, it.toInt()).apply(); previews.forEach { p -> p.gradient = it.toInt() } })
 114:         addSwitch(content, "Border", Prefs.rim(this)) { edit().putBoolean(Prefs.KEY_RIM, it).apply() }
 115:         addSwitch(content, "Glow", Prefs.glow(this)) { edit().putBoolean(Prefs.KEY_GLOW, it).apply() }
 116:         addSwitch(content, "Ring tiles", Prefs.bands(this)) { edit().putBoolean(Prefs.KEY_BANDS, it).apply() }
 117:         addPicker(content, IdlePreview.FILL, Prefs.FILLS, Prefs.fill(this), Prefs.KEY_FILL)
 118:         addSlider(content, "Flare", 0f, Prefs.FLARE_MAX, Prefs.flareDp(this), { "${it.toInt()} dp" },
 119:             Prefs.PREVIEW_IDLE, { edit().putFloat(Prefs.KEY_FLARE, it).apply() })
 120:         addPicker(content, IdlePreview.IDLE, Prefs.IDLE_STYLES, Prefs.idleStyle(this), Prefs.KEY_IDLE_STYLE)
 121:         addSwitch(content, "Music visualizer", Prefs.visualizer(this)) { edit().putBoolean(Prefs.KEY_VISUALIZER, it).apply() }
 122:         addPicker(content, IdlePreview.VIZ, Prefs.VIZ_STYLES, Prefs.vizStyle(this), Prefs.KEY_VIZ_STYLE)
 123:         addSwitch(content, "Beat reaction", Prefs.beat(this)) { edit().putBoolean(Prefs.KEY_BEAT, it).apply() }
 124: 
 125:         // FEEL
 126:         header(content, "FEEL")
 127:         addSlider(content, "Speed", 0f, 100f, Prefs.speed(this).toFloat(), { "${it.toInt()}%" },
 128:             null, { edit().putInt(Prefs.KEY_SPEED, it.toInt()).apply() })
 129:         addSlider(content, "Bounce", 0f, 100f, Prefs.bounce(this).toFloat(), { "${it.toInt()}%" },
 130:             null, { edit().putInt(Prefs.KEY_BOUNCE, it.toInt()).apply() })
 131:         addSwitch(content, "Haptics", Prefs.haptics(this)) { edit().putBoolean(Prefs.KEY_HAPTICS, it).apply() }
 132:         sep(content)
 133:         content.addView(text("Reset looks", 15f, red, Typeface.DEFAULT).apply {
 134:             setPadding(dp(16), dp(8), dp(16), dp(8))
 135:             minimumHeight = dp(44)
 136:             gravity = Gravity.CENTER_VERTICAL
 137:             isClickable = true
 138:             background = ripple()
 139:             setOnClickListener {
 140:                 Prefs.resetLooks(this@MainActivity)
 141:                 val y = scroll.scrollY
 142:                 val s = build()
 143:                 setContentView(s)
 144:                 s.post { s.scrollTo(0, y) }
 145:                 buildApps()
 146:                 refreshSetup()
 147:             }
 148:         })
 149: 
 150:         // APPS
 151:         header(content, "APPS")
 152:         appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
 153:         content.addView(appsBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
 154: 
 155:         // SETUP (last)
 490:             previews.add(p)
 491:             row.addView(p)
 492:             if (i < ids.size - 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
 493:         }
 494:         parent.addView(row, matchWrap())
 495:     }
 496: 
 497:     // ---------------------------------------------------------------- windows list
 498: 
 499:     private var winEntries = ArrayList<WindowsConfig.Entry>()
 500:     private val winRows = ArrayList<View>()
 501:     private lateinit var winBox: LinearLayout
 502: 
 503:     private fun addWindows(parent: LinearLayout) {
 504:         winEntries = ArrayList(WindowsConfig.load(this))
 505:         winBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
 506:         parent.addView(winBox, matchWrap())
 507:         buildWindowRows()
 508:     }
 509: 
 510:     private fun buildWindowRows() {
 511:         winBox.removeAllViews()
 512:         winRows.clear()
 513:         val rowH = dp(48)
 514:         val accent = Prefs.accent(this)
 515:         for (e in winEntries) {
 516:             val row = LinearLayout(this).apply {
 517:                 orientation = LinearLayout.HORIZONTAL
 518:                 gravity = Gravity.CENTER_VERTICAL
 519:                 setPadding(dp(16), 0, dp(16), 0)
 520:                 tag = e.id
 521:             }
 522:             val icon = ImageView(this).apply {
 523:                 setImageResource(WindowsConfig.icon(e.id))
 524:                 setColorFilter(if (e.on) white else grey)
 525:             }
 526:             row.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
 527:             val label = text(WindowsConfig.label(e.id), 15f, if (e.on) white else grey, Typeface.DEFAULT).apply {
 528:                 setPadding(dp(14), 0, 0, 0)
 529:             }
 530:             row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
 531:             val t = FlatToggle(this).apply {
 532:                 this.accent = accent
 533:                 checked = e.on
 534:                 onToggle = { on ->
 535:                     val idx = winEntries.indexOfFirst { it.id == e.id }
 536:                     if (idx >= 0) winEntries[idx] = WindowsConfig.Entry(e.id, on)
 537:                     icon.setColorFilter(if (on) white else grey)
 538:                     label.setTextColor(if (on) white else grey)
 539:                     WindowsConfig.save(this@MainActivity, winEntries)
 540:                 }
 541:             }
 542:             toggles.add(t)
 543:             row.addView(t)
 544:             attachDrag(row)
 545:             winBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH))
 546:             winRows.add(row)
 547:         }
 548:     }
 549: 
 550:     private fun attachDrag(row: View) {
 551:         val rowH = dp(48)
 552:         var dragging = false
 553:         var orig = 0
 554:         var downY = 0f
 555:         var target = 0
 556:         row.setOnLongClickListener {
 557:             orig = winRows.indexOf(row)
 558:             if (orig < 0) return@setOnLongClickListener false
 559:             dragging = true
 560:             target = orig
 561:             downY = lastRawY
 562:             row.parent?.requestDisallowInterceptTouchEvent(true)
 563:             row.setBackgroundColor(divider)
 564:             row.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
 565:             true
 566:         }
 567:         row.setOnTouchListener { _, e ->
 568:             when (e.actionMasked) {
 569:                 MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> if (!dragging) lastRawY = e.rawY
 570:             }
 571:             if (!dragging) return@setOnTouchListener false
 572:             when (e.actionMasked) {
 573:                 MotionEvent.ACTION_MOVE -> {
 574:                     val last = winRows.size - 1
 575:                     val dy = (e.rawY - downY).coerceIn(-orig * rowH.toFloat(), (last - orig) * rowH.toFloat())
 576:                     row.translationY = dy
 577:                     val t = Math.round(orig + dy / rowH).coerceIn(0, last)
 578:                     if (t != target) {
 579:                         target = t
 580:                         row.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
 581:                     }
 582:                     for ((i, r) in winRows.withIndex()) {
 583:                         if (r === row) continue
 584:                         r.translationY = when {
 585:                             i in (orig + 1)..target -> -rowH.toFloat()
 586:                             i in target until orig -> rowH.toFloat()
 587:                             else -> 0f
 588:                         }
 589:                     }
 590:                     true

codex
## Review findings

### Critical

1. **[EdgeService.kt:241–247] Attach retries stop after five failures.** If the accessibility overlay token is unavailable for more than about 13 seconds, the service silently gives up and the bean stays gone. Retry while the service is connected, reset the retry state after success, and log/report the terminal failure so it can recover or be diagnosed.

2. **[EdgeService.kt:325–356] Teardown can leave the service partially alive.** It clears the view and removes the overlay, but does not clear `wm`, and cleanup is split across broad `Throwable` catches that hide failures. Make teardown idempotent with `try/finally`, remove all callbacks/listeners even if one cleanup step fails, and reset all attachment state. Also handle reconnection after `onUnbind` so `attach()` can rebuild a clean window.

3. **[EdgeView.kt:591–603] Hiding can leave a stale alpha animation.** `setHidden()` starts `ViewPropertyAnimator` fades, but doesn’t cancel an in-flight fade. A quick IME/keyguard transition can therefore finish with alpha zero while `hidden == false`, making the overlay invisible. Cancel the existing animator before setting the target alpha, and set visibility/alpha deterministically when the state changes.

4. **[EdgeView.kt:1562–1575] Beat scaling can draw outside the overlay window.** The bean pulse and press scale increase `dw`/`dh`, but `maxOpenWidthPx` and `maxOpenHeightPx` only reserve `rad + flare`; the service adds a fixed 24 dp pad. The live shape, rim, or glow can be clipped at the window boundary, especially during drag overshoot or at the largest configured size. Size the window for the maximum reachable spring/press/beat scale plus glow and flare, or clamp the drawn extent to its bounds.

5. **[EdgeView.kt:827–860] “Zero work while idle” is violated whenever music plays.** The visualizer continuously schedules a callback every 25 ms, even when the panel is closed. At 120 Hz that still wakes the UI thread about 40 times a second to animate the idle bean. Use a bounded-rate callback only while the idle visualizer needs to animate, stop it promptly when playback stops, and verify the audio source is released on every detach/stop path.

6. **[EdgeService.kt:251–255] IME detection only runs when an accessibility event arrives.** If the service misses `TYPE_WINDOWS_CHANGED` during startup or rebinding, the overlay can remain touchable over the keyboard. Recheck visible windows on service connection, screen changes, and window changes; make the query resilient to an unavailable/throwing `windows` property.

7. **[EdgeView.kt:648–658] Detach stops the frame loop but does not stop the other active sources.** The method removes some callbacks and marks layers dirty, but leaves `vizRunning`, listeners, `sourcesStarted`, and window sources potentially active if a detach occurs outside the service’s full teardown path. Stop/reconcile all sources and reset attachment state in `onDetachedFromWindow`; restart them deliberately on attach.

8. **[EdgeService.kt:139–150, 293–307] Window update failures are swallowed.** A failed resize or focusability update leaves the service believing the window is correctly configured. That can leave a clipped or focusable overlay, or break clipboard capture without any signal. Catch expected window exceptions specifically, log them, and recover by removing/re-adding the view or retrying the update.

9. **[EdgeService.kt:166–214] `attach()` catches every `Throwable`, including programming and resource failures, then retries blindly.** This masks the real failure and makes repeated bad states look like transient token races. Catch documented window exceptions, report unexpected failures, and only retry failures known to be temporary.

10. **[EdgeService.kt:166–247] The service has no defense against an OEM force-stopping the app.** A force-stop by vivo’s background manager (`com.vivo.abe`) is outside the service’s ability to recover from; Android suppresses restarts until the user launches or re-enables the app. Explain that limitation in setup/status UI and provide a direct path to the relevant battery/background settings where available. Do not claim a receiver or retry loop can survive a force-stop.

### Reported black-window / paging bug

11. **[EdgeView.kt:1644–1676] The page layer clips each rotated window to an unrotated square RenderNode.** `setClipToBounds(true)` clips the display list to the node’s original `s × s` bounds; then the node rotates around its far corner. During a backward swipe, the previous page’s quarter-circle content rotates into areas outside that square and gets hard-clipped. That is the strongest match for “goes black” during paging or on a vertical swipe: a large part of the incoming page disappears at the layer boundary. Record each window into a larger node with a centered origin and enough bounds for the full rotation plus touch/content extent, or remove node clipping and give the node sufficient bounds. Keep the pivot and translation in the expanded coordinate space.

12. **[EdgeView.kt:1657–1659] The comment and recovery logic misunderstand skipped RenderNodes.** Skipping `drawRenderNode()` when `pageFade` is near zero does not discard the display list. The “re-record when it comes back” claim is false; a stale or invalid node can remain stale indefinitely because `hasDisplayList()` stays true. Invalidate the node explicitly whenever its content inputs change, and don’t treat invisibility as display-list loss.

13. **[EdgeView.kt:1687–1690] `pageFade()` reaches zero at 0.85 pages, abruptly hiding content before a page settles.** This can leave a black-looking gap if the page spring passes through the region where both page layers have low alpha, particularly when the target changes direction. Crossfade the two nearest pages with a normalized pair of weights that always sum to one, and clamp the other six pages to zero.

14. **[EdgeView.kt:1493–1516] A swipe can leave the page spring with no useful frame updates after a frame stall.** The spring timestep is capped at 1/30 second; after a long pause it advances by only that capped interval and may take a long time to settle. Use elapsed-time-aware integration with bounded substeps or snap safely after a large stall, and ensure the frame callback is removed/reset on every cancel/detach path.

15. **[EdgeView.kt:1553–1557] Collapse alpha is updated by subtracting a fixed amount per frame.** `contentAlpha - dt / 0.09f` is frame-rate dependent and can produce a mismatch between shape progress and page opacity across 60/90/120 Hz devices. Compute collapse alpha from elapsed collapse time or a normalized spring/progress value. Keep the same mapping for RenderNode and direct drawing.

### Performance and rendering

16. **[Win.kt:130–150] `addBand()` allocates `RectF`, arrays, and `Path` objects every layout.** Relayout happens during size/flare changes and configuration changes. Cache the band paths per window and mutate/reuse them; preallocate the rects and path slots.

17. **[EdgeView.kt:1571–1583] `buildLivePath()` rebuilds and transforms multiple paths whenever animated geometry changes.** `fillPath()` rewinds and reconstructs the shape, `rimPath` is rebuilt, and `path.transform()` writes into `unitPath` during the animation. That is per-frame geometry work and allocation pressure can still occur inside framework path operations. Keep reusable geometry buffers, update only the path whose geometry changed, and profile at 120 Hz with maximum flare and beat enabled.

18. **[EdgeView.kt:1632–1641] The media beat ring is explicitly the halo the spec says to remove.** It allocates extra compositing and draws a pulsing ring around the art, contrary to the v1.4 direction to make the fill react instead. Delete this drawing path and put beat response into cached fill shader parameters.

19. **[EdgeView.kt:268–284, 713–735] Fill shaders are rebuilt on every looks change and cannot react to beat.** The v1.4 spec requires gradient radius, brightness, accent mix, drift, sparkle, and pattern tint to react to bass/mids/highs. Current code only builds a static radial gradient. Add cached styles and mutate shader local matrices/paint parameters without rebuilding shader objects per frame.

20. **[EdgeView.kt:1618–1626] The hardware and software render paths do not match.** Hardware uses RenderNodes and scales by `min(wS,hS)/rad`; software clips to the shape, uses a different scale formula, and replays all window drawing directly. This can produce visibly different content placement/visibility and makes black-page reports harder to reproduce. Use one transform and clipping contract across both paths.

21. **[EdgeView.kt:1651–1654] RenderNodes are never released when the view is finally released.** `release()` stops sources but does not discard or null the nodes, while `onDetachedFromWindow()` only marks them dirty. Discard the display lists and clear node references during final release; retain them only across ordinary transient detach if the same view will return.

22. **[EdgeView.kt:856–860] The visualizer invalidates the entire view at roughly 40 Hz.** Even when closed, every tick redraws the shape, idle decoration, and any live activity. Invalidate only the bean bounds while closed, and only update the open rim when the active window requires it.

23. **[EdgeView.kt:1562–1570] Beat pulse uses the visualizer’s lowest band as “bass” with fixed intensity.** There is no user-configurable `KEY_BEAT_AMOUNT`, and the spec’s 0–100 bounce control is absent from the renderer path. Apply the configured amount, clamp to the requested maximum, and separate bass energy from the other bands.

24. **[EdgeView.kt:863–881] Idle bars use only four coarse bands and draw accents by index.** The selected visualizer styles (`spectrum`, `rays`, `ripple`, `meter`, `wave`, `liquid`) are not implemented, and the idle shape has no fill pulse. Implement the styles as cached, low-cost paths/shaders and make the settings preview use the same renderer.

### Gestures and touch

25. **[EdgeView.kt:1785–1792] `addMove()` mutates the caller’s `MotionEvent`.** Temporarily changing its coordinates is fragile: exceptions or future early returns can leave the event modified, and pointer history is transformed inconsistently. Copy the event for `VelocityTracker`, feed raw coordinates through the copy, and recycle it in `finally`.

26. **[EdgeView.kt:1829–1838] Every DOWN rebuilds the hit region from `path`, but does not first guarantee that `path` reflects the current window size and shape.** A touch immediately after resize/beat/press can be tested against stale geometry. Call the same shape update used by drawing before hit testing, then use the published region consistently.

27. **[EdgeView.kt:1936–1952] Gesture classification uses `max(-dx,-dy)` for drag-to-open.** A horizontal leftward movement can expand the bean even when the user meant to page or interact with content, and diagonal gestures are classified by whichever component barely crosses slop first. Lock the gesture axis after a clear directional threshold; keep idle open restricted to intentional up-left motion.

28. **[EdgeView.kt:2050–2084] Tap dispatch does not verify the pointer is still inside the original hit target’s actual hit shape in all cases.** The final `hitItem()` comparison checks only item ID and window, so overlapping 44 dp hit circles can turn a drag-off one item and release over another into an accidental action if IDs collide. Store the original control identity and require a small movement threshold plus release-inside-original-target.

29. **[EdgeView.kt:1836–1838] Touch handling rebuilds the region from the draw path, not the published shape geometry.** During animation the input region and drawn shape can diverge. Update one shared geometry snapshot per frame and use it for drawing, hit testing, and touch region publication.

30. **[EdgeService.kt:182–196] The window uses `FLAG_LAYOUT_NO_LIMITS` and a large fixed-size square.** The large square receives input near the bottom/right screen edges and relies on hidden insets clipping. Combined with the whole-view touch handler, this is vulnerable to OEM gesture/nav-bar interception and accidental outside events. Verify the actual insets and gesture exclusion rectangle on the target device; keep the touchable region tight and do not claim full edge-drag reliability.

31. **[EdgeView.kt:1806–1808] `ACTION_OUTSIDE` returns false after starting collapse.** Returning false can cause inconsistent dispatch behavior for the same outside sequence. Consume the event once the overlay handles it.

32. **[EdgeView.kt:1865–1887] Controls are not hit-tested while content is still fading in.** A tap during the last half of the open animation is ignored except as a drag. Either make controls interactive as soon as their alpha is useful, or visibly indicate that the panel is still transitioning and defer taps intentionally.

### Lifecycle, sources, and data

33. **[EdgeView.kt:146–156, 180–216] `reloadWindows()` starts every enabled window source as soon as the overlay is attached.** The contract says expensive listeners and per-window sources should run only when needed; device stats are scoped correctly with `onShown`, but the other windows start even while closed and all enabled windows start together. Start shared listeners only when the panel opens and window-specific sources only for the visible page.

34. **[EdgeView.kt:180–216] Disabling the current window can select an unexpected page.** It falls back to `lastPageId`, which may be disabled or no longer in the enabled order, then defaults to index zero. Preserve the nearest surviving page or deterministically select QUICK, update `lastPageId`, and keep `shownWin` synchronized.

35. **[NotifFeed.kt:19–27, 32–45] Notification state can retain dismissed items indefinitely.** `publish()` depends on `MediaListener` being alive and the callback only notifies the view; dismiss does not remove the item optimistically. Remove it from the feed immediately, request a refreshed active list, and make listener loss clear or mark the feed stale.

36. **[NotifFeed.kt:55–69] App icons are loaded synchronously on the UI thread.** A notification refresh can call `PackageManager.getApplicationIcon()` and rasterize a bitmap during view refresh, causing jank. Load/cache icons off the UI thread and publish the completed result on main.

37. **[WinTimer.kt:137–148] Dial drag invalidates on every MotionEvent and triggers haptics for every value divisible by five.** Moving across a five-minute boundary can trigger repeated ticks on neighboring values and high-rate redraws. Haptic only when crossing a five-minute bucket boundary, and skip invalidation when the snapped minute did not change.

38. **[Arcs.kt:34–72] Receiver and observer registration failures are silently ignored.** The volume UI can then appear frozen while the user assumes system values are live. Track registration state separately, report unsupported streams, and refresh values on window visibility/resume.

### UI/UX and visual design

39. **[EdgeView.kt:1759–1780] The close control has a filled disc behind the X.** The v1.4 request explicitly requires a subtle glyph with no disc. Remove the fill and keep the existing ≥44 dp hit target.

40. **[EdgeView.kt:1719–1757] Page indicators are bars, not dots, and their spacing/arc is centered on a radius derived from X.** This does not meet the requested evenly spaced arc concentric with the X center. Place discrete indicators on the specified common center/radius, with a clear active state.

41. **[EdgeView.kt:1206–1234] The flare is not implemented as a reliable concave fillet.** The path begins outside the view (`ox - f`, `bottom`) while the service’s fixed pad may not include it; the arc directions are also not validated against the required inward “ear” geometry. Construct the two fillets explicitly, include their extents in window sizing and touch region, and visually verify at idle/open extremes.

42. **[EdgeView.kt:723–728] Idle styles collapse into four crude cases.** `glyph`, `pill`, `orbit`, `slits`, `halo`, `meter`, `grid`, and `eclipse` are not rendered distinctly; multiple choices map to the same branch. Implement each selected design with consistent Lucide-style strokes and update the preview renderer.

43. **[MainActivity.kt:111–123, 125–131] Settings controls are grouped inconsistently and omit requested options.** Beat bounce is under FEEL instead of next to Beat reaction, and there is no opacity control at all. Add `KEY_BEAT_AMOUNT` and `KEY_OPACITY`, show the bounce slider only when beat reaction is enabled, and apply opacity to the whole overlay.

44. **[MainActivity.kt:503–547] Window rows are 48 dp high but their toggles and reorder behavior compete for the same touch area.** Long-press drag starts on the whole row, including the toggle; reordering can begin when the user meant to toggle. Add a dedicated drag handle or up/down controls, preserve 48 dp targets, and expose order changes clearly.

45. **[MainActivity.kt:110–123] The settings screen exposes raw visual configuration without enough hierarchy.** A long stack of unlabeled preview pickers and switches makes “LOOKS” a dump of controls; the no-label mini previews are not self-describing. Keep the mandated minimal text, but add short section labels for idle design, fill, and visualizer, and show a selected style name/accessibility description.

46. **[WinApps.kt:104–110] App shortcuts use a white disc behind every icon.** The result is inconsistent with the monochrome, flat panel and creates the “box in a box” appearance the design rules prohibit. Render icons cleanly without a disc; use a restrained outline only for focus/press.

47. **[WinQuick.kt:129–136, WinTimer.kt:97–105] Many controls are small visual targets even when their hit targets are larger.** QUICK’s ring controls and TIMER’s preset chips can look like tiny dots with invisible large hit areas. Use clear 44 dp visual affordances where the layout permits, and show focus/pressed state without relying on a faint ring.

48. **[WinNotifications.kt:94–97, 120–150] Notification text is flattened, truncated, and drawn on arcs.** Titles and body text are difficult to scan and can become unreadable when rotated; tapping a row also opens the intent even if the notification content is malformed. Use upright text in compact arc-aligned rows, keep title/body hierarchy, and handle cancelled `PendingIntent`s explicitly.

49. **[MainActivity.kt:82–155] Settings has no clear explanation of service/background status.** The accessibility service may be enabled while vivo background management later force-stops the package. Show whether the service is connected and offer a direct OEM battery/background guidance entry, with a concise explanation that force-stop requires user relaunch.

### Code structure and contract mismatches

50. **[EdgeView.kt:723–741] Settings preferences are only partly wired into the engine.** `fill`, `vizStyle`, opacity, and beat amount are absent from `applyLooksNow()`. Updating those settings either does nothing or only changes a preview. Wire every preference through the service listener and invalidate only the affected cached layers.

51. **[EdgeService.kt:82–92] The preference listener omits several look keys.** It does not react to `KEY_FILL`, `KEY_VIZ_STYLE`, opacity, or beat amount; it handles flare separately but not as part of a consistent look update. Add all keys and apply changes atomically.

52. **[EdgeView.kt:128–137] Unknown window IDs silently become QUICK.** A bad config entry or future ID renders the wrong window while retaining the wrong ID/index semantics. Validate IDs in `WindowsConfig.load()` and fail visibly or skip unsupported entries.

53. **[WindowsConfig.kt:44–51] `save()` can write an empty or incomplete list.** The next `load()` may silently restore defaults, losing user order/on-off choices. Normalize every save to exactly one entry per known ID, preserve order, and enforce QUICK as the fallback enabled window.

54. **[EdgeView.kt:608–632] `release()` leaves RenderNode references and some animation state behind.** Reusing the same view after service reconnection can show stale nodes or alpha/spring state. Clear nodes and reset the frame, gesture, alpha, hidden, and paging state in final release.

55. **[EdgeService.kt:167–178] `AssistControl.disableCornerGesture()` runs synchronously during attach.** If secure settings access or OEM settings calls block/fail, overlay attachment is delayed or prevented. Isolate this optional system-setting work from window creation, catch/report expected failures, and keep the overlay attach path independent.

## Rework these first

1. Fix rotated RenderNode bounds/clipping and make page fades crossfade without a black gap.
2. Make attach/reconnect and teardown idempotent; keep retrying transient overlay-token failures.
3. Size the overlay for maximum beat/press/drag/flare bounds so no shape edge is clipped.
4. Cancel stale alpha animators when hiding/showing the overlay.
5. Stop the idle visualizer loop when it is not visibly needed; eliminate unnecessary full-view invalidation.
6. Remove the media halo and implement the specified music-reactive fill.
7. Implement the requested distinct idle/fill/visualizer styles and wire every preference end-to-end.
8. Fix gesture classification, event mutation, and shared draw/hit/touch geometry.
9. Remove the close-button disc, rebuild the page indicator around the X center, and make touch affordances legible.
10. Add opacity and beat-amount controls, improve window reordering affordance, and add honest service/OEM status guidance.
tokens used
85,021
## Review findings

### Critical

1. **[EdgeService.kt:241–247] Attach retries stop after five failures.** If the accessibility overlay token is unavailable for more than about 13 seconds, the service silently gives up and the bean stays gone. Retry while the service is connected, reset the retry state after success, and log/report the terminal failure so it can recover or be diagnosed.

2. **[EdgeService.kt:325–356] Teardown can leave the service partially alive.** It clears the view and removes the overlay, but does not clear `wm`, and cleanup is split across broad `Throwable` catches that hide failures. Make teardown idempotent with `try/finally`, remove all callbacks/listeners even if one cleanup step fails, and reset all attachment state. Also handle reconnection after `onUnbind` so `attach()` can rebuild a clean window.

3. **[EdgeView.kt:591–603] Hiding can leave a stale alpha animation.** `setHidden()` starts `ViewPropertyAnimator` fades, but doesn’t cancel an in-flight fade. A quick IME/keyguard transition can therefore finish with alpha zero while `hidden == false`, making the overlay invisible. Cancel the existing animator before setting the target alpha, and set visibility/alpha deterministically when the state changes.

4. **[EdgeView.kt:1562–1575] Beat scaling can draw outside the overlay window.** The bean pulse and press scale increase `dw`/`dh`, but `maxOpenWidthPx` and `maxOpenHeightPx` only reserve `rad + flare`; the service adds a fixed 24 dp pad. The live shape, rim, or glow can be clipped at the window boundary, especially during drag overshoot or at the largest configured size. Size the window for the maximum reachable spring/press/beat scale plus glow and flare, or clamp the drawn extent to its bounds.

5. **[EdgeView.kt:827–860] “Zero work while idle” is violated whenever music plays.** The visualizer continuously schedules a callback every 25 ms, even when the panel is closed. At 120 Hz that still wakes the UI thread about 40 times a second to animate the idle bean. Use a bounded-rate callback only while the idle visualizer needs to animate, stop it promptly when playback stops, and verify the audio source is released on every detach/stop path.

6. **[EdgeService.kt:251–255] IME detection only runs when an accessibility event arrives.** If the service misses `TYPE_WINDOWS_CHANGED` during startup or rebinding, the overlay can remain touchable over the keyboard. Recheck visible windows on service connection, screen changes, and window changes; make the query resilient to an unavailable/throwing `windows` property.

7. **[EdgeView.kt:648–658] Detach stops the frame loop but does not stop the other active sources.** The method removes some callbacks and marks layers dirty, but leaves `vizRunning`, listeners, `sourcesStarted`, and window sources potentially active if a detach occurs outside the service’s full teardown path. Stop/reconcile all sources and reset attachment state in `onDetachedFromWindow`; restart them deliberately on attach.

8. **[EdgeService.kt:139–150, 293–307] Window update failures are swallowed.** A failed resize or focusability update leaves the service believing the window is correctly configured. That can leave a clipped or focusable overlay, or break clipboard capture without any signal. Catch expected window exceptions specifically, log them, and recover by removing/re-adding the view or retrying the update.

9. **[EdgeService.kt:166–214] `attach()` catches every `Throwable`, including programming and resource failures, then retries blindly.** This masks the real failure and makes repeated bad states look like transient token races. Catch documented window exceptions, report unexpected failures, and only retry failures known to be temporary.

10. **[EdgeService.kt:166–247] The service has no defense against an OEM force-stopping the app.** A force-stop by vivo’s background manager (`com.vivo.abe`) is outside the service’s ability to recover from; Android suppresses restarts until the user launches or re-enables the app. Explain that limitation in setup/status UI and provide a direct path to the relevant battery/background settings where available. Do not claim a receiver or retry loop can survive a force-stop.

### Reported black-window / paging bug

11. **[EdgeView.kt:1644–1676] The page layer clips each rotated window to an unrotated square RenderNode.** `setClipToBounds(true)` clips the display list to the node’s original `s × s` bounds; then the node rotates around its far corner. During a backward swipe, the previous page’s quarter-circle content rotates into areas outside that square and gets hard-clipped. That is the strongest match for “goes black” during paging or on a vertical swipe: a large part of the incoming page disappears at the layer boundary. Record each window into a larger node with a centered origin and enough bounds for the full rotation plus touch/content extent, or remove node clipping and give the node sufficient bounds. Keep the pivot and translation in the expanded coordinate space.

12. **[EdgeView.kt:1657–1659] The comment and recovery logic misunderstand skipped RenderNodes.** Skipping `drawRenderNode()` when `pageFade` is near zero does not discard the display list. The “re-record when it comes back” claim is false; a stale or invalid node can remain stale indefinitely because `hasDisplayList()` stays true. Invalidate the node explicitly whenever its content inputs change, and don’t treat invisibility as display-list loss.

13. **[EdgeView.kt:1687–1690] `pageFade()` reaches zero at 0.85 pages, abruptly hiding content before a page settles.** This can leave a black-looking gap if the page spring passes through the region where both page layers have low alpha, particularly when the target changes direction. Crossfade the two nearest pages with a normalized pair of weights that always sum to one, and clamp the other six pages to zero.

14. **[EdgeView.kt:1493–1516] A swipe can leave the page spring with no useful frame updates after a frame stall.** The spring timestep is capped at 1/30 second; after a long pause it advances by only that capped interval and may take a long time to settle. Use elapsed-time-aware integration with bounded substeps or snap safely after a large stall, and ensure the frame callback is removed/reset on every cancel/detach path.

15. **[EdgeView.kt:1553–1557] Collapse alpha is updated by subtracting a fixed amount per frame.** `contentAlpha - dt / 0.09f` is frame-rate dependent and can produce a mismatch between shape progress and page opacity across 60/90/120 Hz devices. Compute collapse alpha from elapsed collapse time or a normalized spring/progress value. Keep the same mapping for RenderNode and direct drawing.

### Performance and rendering

16. **[Win.kt:130–150] `addBand()` allocates `RectF`, arrays, and `Path` objects every layout.** Relayout happens during size/flare changes and configuration changes. Cache the band paths per window and mutate/reuse them; preallocate the rects and path slots.

17. **[EdgeView.kt:1571–1583] `buildLivePath()` rebuilds and transforms multiple paths whenever animated geometry changes.** `fillPath()` rewinds and reconstructs the shape, `rimPath` is rebuilt, and `path.transform()` writes into `unitPath` during the animation. That is per-frame geometry work and allocation pressure can still occur inside framework path operations. Keep reusable geometry buffers, update only the path whose geometry changed, and profile at 120 Hz with maximum flare and beat enabled.

18. **[EdgeView.kt:1632–1641] The media beat ring is explicitly the halo the spec says to remove.** It allocates extra compositing and draws a pulsing ring around the art, contrary to the v1.4 direction to make the fill react instead. Delete this drawing path and put beat response into cached fill shader parameters.

19. **[EdgeView.kt:268–284, 713–735] Fill shaders are rebuilt on every looks change and cannot react to beat.** The v1.4 spec requires gradient radius, brightness, accent mix, drift, sparkle, and pattern tint to react to bass/mids/highs. Current code only builds a static radial gradient. Add cached styles and mutate shader local matrices/paint parameters without rebuilding shader objects per frame.

20. **[EdgeView.kt:1618–1626] The hardware and software render paths do not match.** Hardware uses RenderNodes and scales by `min(wS,hS)/rad`; software clips to the shape, uses a different scale formula, and replays all window drawing directly. This can produce visibly different content placement/visibility and makes black-page reports harder to reproduce. Use one transform and clipping contract across both paths.

21. **[EdgeView.kt:1651–1654] RenderNodes are never released when the view is finally released.** `release()` stops sources but does not discard or null the nodes, while `onDetachedFromWindow()` only marks them dirty. Discard the display lists and clear node references during final release; retain them only across ordinary transient detach if the same view will return.

22. **[EdgeView.kt:856–860] The visualizer invalidates the entire view at roughly 40 Hz.** Even when closed, every tick redraws the shape, idle decoration, and any live activity. Invalidate only the bean bounds while closed, and only update the open rim when the active window requires it.

23. **[EdgeView.kt:1562–1570] Beat pulse uses the visualizer’s lowest band as “bass” with fixed intensity.** There is no user-configurable `KEY_BEAT_AMOUNT`, and the spec’s 0–100 bounce control is absent from the renderer path. Apply the configured amount, clamp to the requested maximum, and separate bass energy from the other bands.

24. **[EdgeView.kt:863–881] Idle bars use only four coarse bands and draw accents by index.** The selected visualizer styles (`spectrum`, `rays`, `ripple`, `meter`, `wave`, `liquid`) are not implemented, and the idle shape has no fill pulse. Implement the styles as cached, low-cost paths/shaders and make the settings preview use the same renderer.

### Gestures and touch

25. **[EdgeView.kt:1785–1792] `addMove()` mutates the caller’s `MotionEvent`.** Temporarily changing its coordinates is fragile: exceptions or future early returns can leave the event modified, and pointer history is transformed inconsistently. Copy the event for `VelocityTracker`, feed raw coordinates through the copy, and recycle it in `finally`.

26. **[EdgeView.kt:1829–1838] Every DOWN rebuilds the hit region from `path`, but does not first guarantee that `path` reflects the current window size and shape.** A touch immediately after resize/beat/press can be tested against stale geometry. Call the same shape update used by drawing before hit testing, then use the published region consistently.

27. **[EdgeView.kt:1936–1952] Gesture classification uses `max(-dx,-dy)` for drag-to-open.** A horizontal leftward movement can expand the bean even when the user meant to page or interact with content, and diagonal gestures are classified by whichever component barely crosses slop first. Lock the gesture axis after a clear directional threshold; keep idle open restricted to intentional up-left motion.

28. **[EdgeView.kt:2050–2084] Tap dispatch does not verify the pointer is still inside the original hit target’s actual hit shape in all cases.** The final `hitItem()` comparison checks only item ID and window, so overlapping 44 dp hit circles can turn a drag-off one item and release over another into an accidental action if IDs collide. Store the original control identity and require a small movement threshold plus release-inside-original-target.

29. **[EdgeView.kt:1836–1838] Touch handling rebuilds the region from the draw path, not the published shape geometry.** During animation the input region and drawn shape can diverge. Update one shared geometry snapshot per frame and use it for drawing, hit testing, and touch region publication.

30. **[EdgeService.kt:182–196] The window uses `FLAG_LAYOUT_NO_LIMITS` and a large fixed-size square.** The large square receives input near the bottom/right screen edges and relies on hidden insets clipping. Combined with the whole-view touch handler, this is vulnerable to OEM gesture/nav-bar interception and accidental outside events. Verify the actual insets and gesture exclusion rectangle on the target device; keep the touchable region tight and do not claim full edge-drag reliability.

31. **[EdgeView.kt:1806–1808] `ACTION_OUTSIDE` returns false after starting collapse.** Returning false can cause inconsistent dispatch behavior for the same outside sequence. Consume the event once the overlay handles it.

32. **[EdgeView.kt:1865–1887] Controls are not hit-tested while content is still fading in.** A tap during the last half of the open animation is ignored except as a drag. Either make controls interactive as soon as their alpha is useful, or visibly indicate that the panel is still transitioning and defer taps intentionally.

### Lifecycle, sources, and data

33. **[EdgeView.kt:146–156, 180–216] `reloadWindows()` starts every enabled window source as soon as the overlay is attached.** The contract says expensive listeners and per-window sources should run only when needed; device stats are scoped correctly with `onShown`, but the other windows start even while closed and all enabled windows start together. Start shared listeners only when the panel opens and window-specific sources only for the visible page.

34. **[EdgeView.kt:180–216] Disabling the current window can select an unexpected page.** It falls back to `lastPageId`, which may be disabled or no longer in the enabled order, then defaults to index zero. Preserve the nearest surviving page or deterministically select QUICK, update `lastPageId`, and keep `shownWin` synchronized.

35. **[NotifFeed.kt:19–27, 32–45] Notification state can retain dismissed items indefinitely.** `publish()` depends on `MediaListener` being alive and the callback only notifies the view; dismiss does not remove the item optimistically. Remove it from the feed immediately, request a refreshed active list, and make listener loss clear or mark the feed stale.

36. **[NotifFeed.kt:55–69] App icons are loaded synchronously on the UI thread.** A notification refresh can call `PackageManager.getApplicationIcon()` and rasterize a bitmap during view refresh, causing jank. Load/cache icons off the UI thread and publish the completed result on main.

37. **[WinTimer.kt:137–148] Dial drag invalidates on every MotionEvent and triggers haptics for every value divisible by five.** Moving across a five-minute boundary can trigger repeated ticks on neighboring values and high-rate redraws. Haptic only when crossing a five-minute bucket boundary, and skip invalidation when the snapped minute did not change.

38. **[Arcs.kt:34–72] Receiver and observer registration failures are silently ignored.** The volume UI can then appear frozen while the user assumes system values are live. Track registration state separately, report unsupported streams, and refresh values on window visibility/resume.

### UI/UX and visual design

39. **[EdgeView.kt:1759–1780] The close control has a filled disc behind the X.** The v1.4 request explicitly requires a subtle glyph with no disc. Remove the fill and keep the existing ≥44 dp hit target.

40. **[EdgeView.kt:1719–1757] Page indicators are bars, not dots, and their spacing/arc is centered on a radius derived from X.** This does not meet the requested evenly spaced arc concentric with the X center. Place discrete indicators on the specified common center/radius, with a clear active state.

41. **[EdgeView.kt:1206–1234] The flare is not implemented as a reliable concave fillet.** The path begins outside the view (`ox - f`, `bottom`) while the service’s fixed pad may not include it; the arc directions are also not validated against the required inward “ear” geometry. Construct the two fillets explicitly, include their extents in window sizing and touch region, and visually verify at idle/open extremes.

42. **[EdgeView.kt:723–728] Idle styles collapse into four crude cases.** `glyph`, `pill`, `orbit`, `slits`, `halo`, `meter`, `grid`, and `eclipse` are not rendered distinctly; multiple choices map to the same branch. Implement each selected design with consistent Lucide-style strokes and update the preview renderer.

43. **[MainActivity.kt:111–123, 125–131] Settings controls are grouped inconsistently and omit requested options.** Beat bounce is under FEEL instead of next to Beat reaction, and there is no opacity control at all. Add `KEY_BEAT_AMOUNT` and `KEY_OPACITY`, show the bounce slider only when beat reaction is enabled, and apply opacity to the whole overlay.

44. **[MainActivity.kt:503–547] Window rows are 48 dp high but their toggles and reorder behavior compete for the same touch area.** Long-press drag starts on the whole row, including the toggle; reordering can begin when the user meant to toggle. Add a dedicated drag handle or up/down controls, preserve 48 dp targets, and expose order changes clearly.

45. **[MainActivity.kt:110–123] The settings screen exposes raw visual configuration without enough hierarchy.** A long stack of unlabeled preview pickers and switches makes “LOOKS” a dump of controls; the no-label mini previews are not self-describing. Keep the mandated minimal text, but add short section labels for idle design, fill, and visualizer, and show a selected style name/accessibility description.

46. **[WinApps.kt:104–110] App shortcuts use a white disc behind every icon.** The result is inconsistent with the monochrome, flat panel and creates the “box in a box” appearance the design rules prohibit. Render icons cleanly without a disc; use a restrained outline only for focus/press.

47. **[WinQuick.kt:129–136, WinTimer.kt:97–105] Many controls are small visual targets even when their hit targets are larger.** QUICK’s ring controls and TIMER’s preset chips can look like tiny dots with invisible large hit areas. Use clear 44 dp visual affordances where the layout permits, and show focus/pressed state without relying on a faint ring.

48. **[WinNotifications.kt:94–97, 120–150] Notification text is flattened, truncated, and drawn on arcs.** Titles and body text are difficult to scan and can become unreadable when rotated; tapping a row also opens the intent even if the notification content is malformed. Use upright text in compact arc-aligned rows, keep title/body hierarchy, and handle cancelled `PendingIntent`s explicitly.

49. **[MainActivity.kt:82–155] Settings has no clear explanation of service/background status.** The accessibility service may be enabled while vivo background management later force-stops the package. Show whether the service is connected and offer a direct OEM battery/background guidance entry, with a concise explanation that force-stop requires user relaunch.

### Code structure and contract mismatches

50. **[EdgeView.kt:723–741] Settings preferences are only partly wired into the engine.** `fill`, `vizStyle`, opacity, and beat amount are absent from `applyLooksNow()`. Updating those settings either does nothing or only changes a preview. Wire every preference through the service listener and invalidate only the affected cached layers.

51. **[EdgeService.kt:82–92] The preference listener omits several look keys.** It does not react to `KEY_FILL`, `KEY_VIZ_STYLE`, opacity, or beat amount; it handles flare separately but not as part of a consistent look update. Add all keys and apply changes atomically.

52. **[EdgeView.kt:128–137] Unknown window IDs silently become QUICK.** A bad config entry or future ID renders the wrong window while retaining the wrong ID/index semantics. Validate IDs in `WindowsConfig.load()` and fail visibly or skip unsupported entries.

53. **[WindowsConfig.kt:44–51] `save()` can write an empty or incomplete list.** The next `load()` may silently restore defaults, losing user order/on-off choices. Normalize every save to exactly one entry per known ID, preserve order, and enforce QUICK as the fallback enabled window.

54. **[EdgeView.kt:608–632] `release()` leaves RenderNode references and some animation state behind.** Reusing the same view after service reconnection can show stale nodes or alpha/spring state. Clear nodes and reset the frame, gesture, alpha, hidden, and paging state in final release.

55. **[EdgeService.kt:167–178] `AssistControl.disableCornerGesture()` runs synchronously during attach.** If secure settings access or OEM settings calls block/fail, overlay attachment is delayed or prevented. Isolate this optional system-setting work from window creation, catch/report expected failures, and keep the overlay attach path independent.

## Rework these first

1. Fix rotated RenderNode bounds/clipping and make page fades crossfade without a black gap.
2. Make attach/reconnect and teardown idempotent; keep retrying transient overlay-token failures.
3. Size the overlay for maximum beat/press/drag/flare bounds so no shape edge is clipped.
4. Cancel stale alpha animators when hiding/showing the overlay.
5. Stop the idle visualizer loop when it is not visibly needed; eliminate unnecessary full-view invalidation.
6. Remove the media halo and implement the specified music-reactive fill.
7. Implement the requested distinct idle/fill/visualizer styles and wire every preference end-to-end.
8. Fix gesture classification, event mutation, and shared draw/hit/touch geometry.
9. Remove the close-button disc, rebuild the page indicator around the X center, and make touch affordances legible.
10. Add opacity and beat-amount controls, improve window reordering affordance, and add honest service/OEM status guidance.
