---
name: dynamic-edge
description: Dyn Edge - ultra-light Android floating corner widget (black bean-shaped notch in the bottom-right that melts open into quick toggles + media player). Architecture, interface contract, build/install/grant runbook.
---

# Dyn Edge

Project root: `D:\VS\PROJECTS\FREE WORK\dynamic-edge`. Package `com.dynedge`, label "Dyn Edge".
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
| `MediaListener` | `MediaListener.kt` | NotificationListenerService: allows getActiveSessions(), feeds live activities, and keeps the process alive for PauseGate. |
| `A11yGate` / `PauseGate` / `GateReceiver` | `A11yGate.kt`, `PauseGate.kt` | Pause in sensitive apps (see that section). |
| `MainActivity` | `MainActivity.kt` | Settings host: adb hooks (set_prefs, preview, dump_prefs, wallpaper) + one `Studio`. |
| `Studio` | `Studio.kt`, `StudioViews.kt` | Every settings page: main, Settings, window editors (see Settings screens). |
| `Skin` | `Skin.kt` | The settings app's visual system: grey surface scale, text ramp, accent contrast helpers. |
| `EdgePaint` | `EdgePaint.kt` | The edge gradient maths - the single source of truth for the shape's colours. |
| `HsvPickerView` / `ColorPicker` | `ColorPicker.kt` | Framework-only colour picker (SV square + hue strip) and the modal picker. |
| `WindowEditActivity` / `SettingsActivity` | `WindowEditActivity.kt` | Hosts of a `Studio` page in a window-editor mode / the Settings mode. |
| `DragGrid` / `RingPreview` / `DotIcon` | `EditorViews.kt` | Drag-to-reorder equal-cell grid, live APPS ring sketch, icon button with an attention dot. |
| `AppRing` | `AppRing.kt` | The APPS window ring layout maths (overlay + preview). |
| `ClipPopup` | `ClipPopup.kt` | Floating clipboard popup: lazy second accessibility overlay window owned by EdgeService (see Clipboard popup). |
| `TileSupport` | `TileSupport.kt` | Does a quick tile work by itself? Red markers in QuickEditorActivity (see Tile support markers). |

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
- Grab square (closed only): region = bean + 6 dp UNION an invisible axis-aligned square of side
  max(`Prefs.grabDp` (`grab`, default 72, 48..120, Size > Grab area), bean + 6 dp) at the window's bottom-right
  (`EdgeView.publishRegion`, `Region.op UNION`). handleDown tests `region.contains`, so a DOWN in the square is a
  bean touch (tap opens, up/left drag opens). Back-gesture exclusion covers its height (cap 200 dp). Never drawn,
  not in any settings preview; hidden = empty region; expanded unchanged. The window (always open size) is at least grabPx.
- Collapse on ACTION_OUTSIDE, screen off, IME shown, after launching an Activity.
- Slider sizes: overlay arc sliders (`ArcSet.STROKE_DP`) 9 dp round-capped stroke, arc start pushed past the badge
  by the cap; QUICK tile ring sits half a stroke + 6 dp inside the inner arc. Settings `FlatSlider`: 8 dp track,
  thumb radius 13 / 15 dp (idle / dragging), 48 dp touch cell.
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

## Settings screens (2026-11-11) - replaces the v1.5 canvas / tabs studio
All settings pages are one class, `Studio(activity, mode)` (`Studio.kt`), so every page shares the same flat,
captioned controls. Background `Skin.BG`, everything interactive is `Skin.accentUi()`.
- **Main page** (`MainActivity`, mode `Studio.MODE_MAIN`): top bar "Dyn Edge" + gear (`ic_settings`,
  `DotIcon`: accent dot while `Perms.missing` is non-empty) -> `SettingsActivity`. Accent status banner only when
  the overlay is off / stopped / permissions are missing (its Fix opens SettingsActivity). One scrolling page:
  Colour (edge colour swatches, equal-width cells + hue cell -> ColorPicker), Shape (corner, open, flare, grab area,
  opacity sliders; Glow switch), Windows (list), Music (5 animated visualizer thumbnails, captioned), Motion
  (speed; release replays the motion). Nothing window-specific lives on the main page.
- **Windows list**: row = icon, name, grip (`ic_grip`), switch. Switch = on / off. Grip = drag at once; long-press
  anywhere on the row also lifts it. The row follows the finger (opaque SURFACE while lifted, scale 1.02,
  elevation), the rows it passes glide one slot (views are never removed mid-gesture: that would cancel it), release
  animates into the slot and saves `WindowsConfig`, the prefs listener then rebuilds the list. Row tap = that
  window's editor: Quick -> `QuickEditorActivity`, every other window -> `WindowEditActivity` (extra `window`).
- **Settings page** (`SettingsActivity` extends WindowEditActivity, mode `MODE_SETTINGS`, title "Settings"):
  permissions as a 4-column grid of `PermDot`s with short captions (tap = its screen, long-press = full name;
  Secure settings = ic_lock), Pause in apps, Saved looks, Reset look / Reset feel (5 s Undo).
- **Window editors** (`WindowEditActivity`, mode = window id, title = window name). Each control changes a real
  element of that window through a `w_` key (Prefs.WIN_PREFIX); defaults = the old behaviour:

| Window | Control | Pref key | Element |
|---|---|---|---|
| Apps | ring preview (`RingPreview`, true scale, same `AppRing` maths) | - | - |
| Apps | app grid (`DragGrid`, 4 equal columns): grip top-left = drag at once, long-press lifts, x top-right = remove, + = AppPickerActivity | `apps` (order = overlay order) | shortcut apps |
| Apps | Icon size 32..48 dp | `w_apps_icon` (40) | icon diameter on the rings |
| Apps | Spacing 4..20 dp | `w_apps_gap` (8) | gap between icons and between rings |
| Apps | Recent apps | `w_apps_recents` (true) | recent-apps ribbon |
| Apps | Monochrome | `icon_source` (IconTheme.KEY) | themed / system icons |
| Media | Album art / Skip buttons / Title and artist / Seek arc | `w_media_art` / `_skip` / `_text` / `_seek` (true) | art bitmap (off = plain disc), prev+next (also not hit), title+artist arcs, progress arc + times (also no seek capture) |
| Clipboard | Rows 1..4; Close after copy; History -> ClipboardActivity | `w_clip_rows` (4); `clip_close` | clip rows shown |
| Alerts | Rows 1..4; Message text; App icons | `w_notif_rows` (4), `w_notif_body`, `w_notif_icons` (true) | rows, grey body text, icon discs (text starts at the arc start when off) |
| Timer | four preset sliders 1..60, caption shows "N min" live | `w_timer_p0..p3` (1, 5, 10, 25) | the four chips |
| Device | Battery / RAM / Storage / Temperature | `w_dev_bat` / `_ram` / `_disk` / `_temp` (true) | each arc + badge + label (battery also the %), the temperature text |

- Overlay wiring: `EdgeService` prefs listener sends `w_*`, `apps` and `icon_source` to `EdgeView.reloadWinPrefs()`
  (coalesced per frame): every enabled window gets `Win.onPrefs()` (WinApps reloads its shortcuts) and then
  `layoutAll()`; each window reads its `w_` prefs in `layout()`.
- Symmetry rules (user): sections have 8 dp side padding; every row has a 24 dp icon column at 8 dp, captions at
  +14 dp, slider captions in one 104 dp column (all tracks start at one x), the right control on the same right
  edge (4 dp); grids and thumbnail rows use equal-weight columns; app cells mirror grip (left) and remove (right).
- Locked options (black and white plus the accent): `Prefs.gradOn/base/fill/grad1/grad2/gradient/idleStyle/
  bounce/swipeSens/haptics/visualizer/beat/beatAmount/live` return constants. `Prefs.LOOK_KEYS` = accent, glow,
  opacity, flare, viz style, speed; `restore()` skips every other key, so an old saved look cannot bring a removed
  option back. Migrations (`migrate`, `migrateLook`, one-shot by `looks_v`) run in MainActivity and in
  `EdgeService.onServiceConnected`. The border / rim option is gone everywhere.
- Removed files: `EdgePreviewView.kt`, `EdgeGeom.kt`, `WindowSketch.kt` (no longer instantiated; QuickEditorActivity
  uses `EdgeView.C_TRACK`).

## APPS window ring layout (2026-11-11, `AppRing.kt`, used by WinApps and RingPreview)
- Up to `Prefs.MAX_APPS` = 12 apps. Polar frame at the screen corner, angles 0 (bottom edge) .. 90 (right edge).
- Rings: outermost centre radius = ribbon radius (`min(0.92R, R - 20dp)`) - 14 dp ribbon icon - gap - icon radius
  (no ribbon: R - 8 dp - icon radius); then inward every 2 * iconR + gap while >= max(0.34R, 50 dp + iconR).
- Capacity of a ring: angular margin m = asin((iconR + 4dp) / r) from each edge, neighbour step >=
  2 asin((2 iconR + gap) / 2r); cap = floor((90 - 2m) / step) + 1. If all rings cannot hold the apps the icons
  shrink 1 dp at a time (min 12 dp radius).
- Fill: the fewest rings from the outermost inward; each app goes to the ring with the most radius per app, so
  outer rings hold more. Each ring is centred on 45 deg with step min(span / (c - 1), max(90 / (c + 1), minStep)),
  so it is mirrored about the diagonal. Default (216 dp, 6 apps): outer 4 at 18/36/54/72, inner 2 at 30/60.
- Order: app 0 = innermost ring, each ring from 0 toward 90 deg (the settings grid order, row by row).
- Rotation: every icon, its press ring, and the recent-ribbon icons are drawn with `canvas.rotate(angle - 90)`, so
  the icon's up axis lies on the radius pointing away from the corner (clock-face numbers). Hit testing is
  circular and unchanged. Items: apps 0..11, PLUS 12, ribbon 13..17.

## Overlay motion
- Page swaps are a **glide**, not a switch: `PageAnim` eases the page position over ~250-340 ms between the
  two pages. A deliberate release uses an ease-in-out curve (it drifts), a fling uses ease-out (it continues
  the gesture). The motion is strictly monotonic: it never overshoots and never springs back.
- The crossfade finishes early in the glide (by 45% of the travel), so the incoming page is fully opaque
  while it is still rotating into place. The panel never dips half-transparent and the rotation stays readable.
- The "Bounce" setting does not affect page swaps.

## Launcher icon
- The icon is generated from `D:\Downloads\logo.png`: the white glyph (curve + bars) is extracted and used as
  the adaptive foreground on a black background, with a monochrome layer for themed icons (Android 13+).
  Legacy square and round PNGs are in `mipmap-*dpi`. Regenerate with the script in the handoff notes.

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
  2 APPS: AppShortcuts on concentric rings, icons turned along the radius (see APPS window ring layout).
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

## Quick tiles (2026-11-11, hybrid direct + QS click)
Files: `Tiles.kt` (routing, optimistic pending state, listeners), `DirectToggles.kt` (silent APIs), `QsDriver.kt`
(clicks the real QS tile), `QsCatalog.kt` (which tiles exist, specs, labels, icons), `TileState.kt` (real state).
- Direct (silent): wifi (setWifiEnabled, verify 1.5 s), bt / flashlight / dnd / rotation / vibrate (QuickToggles),
  ringer (normal -> vibrate -> silent), night (Secure night_display_activated), reduce_brightness (Secure
  reduce_bright_colors_activated), battery (Global low_power, verify isPowerSaveMode at 600 ms), dark (UiModeManager,
  verify 900 ms), location (LOCATION_MODE / setLocationEnabledForUser, verify 800 ms). A failed verify -> QS click.
- QS click: everything else - cell, hotspot, airplane, nfc, glyphs, glyphs_torch, screenrecord, qr_code_scanner,
  cast, controls, saver/dataSaver, mictoggle, cameratoggle, every custom(...) tile and unknown specs.
  QS click failed (tile not found in 2.5 s) -> shade collapsed + tile's settings screen.
- SAFETY: hotspot, mobile data and airplane have no direct route and no fallback that reaches them; they change
  only when the user taps that tile (the PC is online through the phone's hotspot).
- QsDriver: GLOBAL_ACTION_QUICK_SETTINGS, poll SystemUI windows, click, wait 350 ms (action tiles up to 1 s); a new
  window (app / SystemUI dialog) -> leave the shade open (overlay collapses), else
  GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE (never BACK). One job at a time (taps while busy tick), hard deadline 4.2 s.
- Android 16+ matching: each tile container's content-desc is "<spec>, <W>x<H>, row R column C"; spec matched
  exactly (custom(...) by ComponentName). If the panel uses this format, label/positional guessing is never used.
  Click the single clickable child; for 2x1 tiles the square icon clickable (the body opens details). Later pages:
  ACTION_SCROLL_FORWARD/BACKWARD on the pager (direction from the sysui_qs_tiles order), swipe gesture fallback.
  Older panels (vivo): label / view-id keys + positional fallback for wifi / cell, as before.
- State: TileState reads wifi, bt, dnd, rotation, ringer, location, airplane, cell (mobile_data<defaultDataSubId>,
  then mobile_data), hotspot (isWifiApEnabled / getWifiApState), nfc, dark, night, reduce_brightness, battery;
  torch via TorchCallback. Others: checked / stateDescription cached per spec on the last QsDriver visit, else neutral.
- Optimistic: a tap shows the target state at once (drawn at 60 % alpha), further taps on that tile are ignored
  until the real state matches or 3 s pass (fixes the Nothing Wi-Fi double toggle). Receivers / observers for all
  of the above only between Tiles.start() / stop() (panel open).
- Catalog: sysui_qs_tiles + specs seen by QsDriver in the panel (Prefs `qs_seen_specs`, e.g. glyphs_torch) +
  SystemUI `quick_settings_tiles_stock`; custom tiles = installed TileServices (service label + icon). Unknown
  specs: ic_tile + humanised label.

## Tile support markers (2026-11-11, Quick layout editor only)
`TileSupport.support(ctx, id)` (`TileSupport.kt`) -> `Support.Ok` / `OpensSettings(reason)` / `MissingHardware(what)` /
`NeedsPermission(Need)`; `reason()` gives the editor line, `TileSupport.intent(ctx, need)` the screen that grants it
(null for SECURE = adb only). Decision:
- Native: wifi / bluetooth / torch -> MissingHardware without the radio / flash; dnd -> needs DND access; rotate,
  caffeine -> needs Modify system settings; vibrate, assist, clipboard -> Ok; global actions (screenshot, lock,
  notifications, quick_settings, recents, power_menu) -> need the accessibility service on (`Perms.overlayEnabled`);
  battery_saver -> the phone's `battery` QS tile if it has one (QS rules), else needs WRITE_SECURE_SETTINGS.
- sys:<spec>: nfc / flashlight / bt hardware checks first. Direct specs: wifi Ok; location / night /
  reduce_brightness / dark Ok with WRITE_SECURE_SETTINGS; ringer Ok when the phone has no ringer QS tile; else QS rules.
- QS rules (custom tiles and everything else): service off -> NeedsPermission(ACCESSIBILITY); recorded QsDriver
  failure for the id (or sys:<spec>) -> OpensSettings("not found in quick settings last time"); spec not in
  sysui_qs_tiles nor `QsCatalog.seenSpecs` (custom(...) compared by ComponentName) -> OpensSettings("not in this
  phone's quick settings"); else Ok.
- Failures: `QsDriver.finish` records FAILED-before-click (tile not found) in Prefs `qs_failed_ids` (StringSet) and
  clears the id on the next DONE / LEFT_OPEN.
- Editor: not-Ok chips get a red (#FF453A) Lucide alert-circle badge (`ic_alert_circle`) at the top-right, both in
  the tray and on placed tiles in the preview. Selecting a placed tile (tap) or tapping a bad tray chip fills the
  selection row under the preview ("Torch: This phone has no flash", "DND: Needs DND access - tap to grant" with an
  accent Grant action). Filter row "Hide tiles that won't work" (Prefs `qe_hide_bad`) at the top of the tray when any
  tray tile is bad. Markers refresh in onResume (after granting). The overlay's QUICK window shows no markers.

## Quick layout v2 (2026-11-11): two rings of fixed slots + direct-manipulation editor
Owner: arranging the old three-ring layout was "like playing a puzzle".
- Model (`QuickConfig.kt`): `Layout(rings: List<List<String?>>, arcs)`, ring 0 inner, ring 1 outer, each a FIXED slot
  array (null = empty slot, stored as ""). JSON `{"v":2,"rings":[[..],[..]],"arcs":[..]}`. Third-party (`cus:`) tiles
  live in either ring. `loadRaw` = as stored, `load` = normalised to the current capacity, `normalize(l, cap)`:
  too-long ring drops empty slots (last first), then overflowing tiles go to the other ring's empty slots, then
  unplaced (they reappear in the tray). Migration from v1 (inner/middle/custom): flattened in that order, poured into
  inner (its capacity) then outer, then normalised; v1 JSON stays until the first save, so nothing is lost.
- Geometry (`QuickGeom`, same file): used by WinQuick and the editor preview so both agree.
  arcR0 = min(0.88R, R - 14 dp), arcR1 = arcR0 - 30 dp; outer ring ro = (innermost arc - 4.5 - 6 dp, or
  min(0.94R, R - 8 dp) without arcs) - chip; inner ring ri = max(0.40R, closeX + 56 dp), at least 44 dp inside ro.
  Capacity per ring = largest n (<= 8) whose neighbour centres (angles `Win.slotAngle(k, n)`, 12..78 deg) are >= 44 dp
  apart; chip radius = min(brad, half the spacing - 1.5 dp). Default 216 dp, 1 slider: 3 inner + 5 outer. Capacity
  follows the open size / slider count live (WinQuick renormalises in layout()).
- WinQuick items: slot (ring r, k) = r * 8 + k; arcs = 16, 17. Empty slots are invisible and not hit.
- Editor (`QuickEditorActivity`): top bar Undo (30 steps) + Reset; true-to-scale preview (open radius in real dp,
  corner at the view's bottom-right, edge gradient, slider arcs, faint circle for every slot); selection row; tray
  (search + filter + Tiles / System / Custom of tiles NOT placed + Sliders toggles). Gestures: tap placed tile =
  select (row: reason, Grant, Remove); tap slider badge = select (Swap when two, Remove); long-press or drag a placed
  tile = framework drag and drop (`startDragAndDrop`, local state DragInfo, chip shadow); tray chip: tap = first free
  slot (inner first), long-press = drag. While dragging the hovered slot gets an accent ring, a 38 % ghost shows the
  landing spot and other tiles slide (per-tile eased positions) into the hypothetical arrangement: empty slot takes
  it; occupied -> the ring shifts one step toward its nearest empty slot; full ring -> swap with the source slot
  (from the tray: displaced tile to the other ring's first free slot, else back to the tray). Drop outside the quarter
  circle or anywhere off the preview = remove. Haptics: LONG_PRESS pick up, CLOCK_TICK per slot change, CONFIRM drop.
  Every change saves at once -> EdgeService prefs listener -> WinQuick.reload (live). Pick mode (EXTRA_RING) removed.

## Clipboard popup (2026-11-11)
The edge never launches ClipboardActivity any more (it stays for Clipboard editor > History and holds the
"Close popup after copying" setting, Prefs `clip_close`, default on). `EdgeView.openClipboard()` ->
`Host.openClipPopup()` -> `EdgeService` builds a `ClipPopup` (`ClipPopup.kt`) on demand:
- Second TYPE_ACCESSIBILITY_OVERLAY window, BOTTOM|RIGHT, x 12 dp, y idle + 18 dp; width min(300 dp, screen - 24 dp),
  height cap 60 % of the screen; radius 20 dp; surface = EdgePaint stops of the user's base / grad1 / grad2 /
  strength, radial from the bottom-right, lifted 30-45 % toward #1C1C1E; 1 dp white 10 % rim; accent for interactive.
- Flags NOT_FOCUSABLE | NOT_TOUCH_MODAL | WATCH_OUTSIDE_TOUCH | LAYOUT_IN_SCREEN. Focusable only (a) for one focus
  change after opening when `EdgeView.clipNeedsCapture()` (reads the system clip, 350 ms timeout), (b) while search
  is open (moves the popup to TOP|RIGHT y 56 dp, cap 42 %, so the keyboard does not cover it).
- Rows: pinned first, then newest (ClipStore.list), max 60, 2 lines. Tap = copy + accent check pop + haptic (closes
  after 420 ms when clip_close). Paste button only when `ClipPaste.hasEditable` found a focused field at open (the
  window is removed first, paste runs 140 ms later; no field -> edge "Copied" pill). Pin (accent when pinned), delete.
- Closes on: outside tap (ACTION_OUTSIDE), back key while focusable, X button, TYPE_WINDOW_STATE_CHANGED from another
  package (not ours / not the IME, 500 ms grace) - this is how gesture back is caught while not focusable -, screen off,
  keyguard, overlay detach. Removed = window + views + ClipStore listener gone; idle cost zero.

## Pause in sensitive apps (2026-11-11)
Banking / UPI apps (BHIM `in.org.npci.upiapp` etc.) refuse to open while any third-party accessibility
service is enabled. EdgeService therefore removes itself from the accessibility list while such an app is in
front. Needs WRITE_SECURE_SETTINGS (pm grant) + usage access; without both the feature is inert.
- `A11yGate.set(ctx, on)`: edits Settings.Secure `enabled_accessibility_services`, touching ONLY
  `com.dynedge/com.dynedge.EdgeService` ("pkg/.Cls" == "pkg/pkg.Cls"); every other service and its order
  kept. Enabling also sets `accessibility_enabled=1`; removing ours never touches `accessibility_enabled`.
- Prefs: `pause_on` (default true), `pause_apps` (StringSet; absent = default = installed apps from
  `Prefs.SENSITIVE_APPS` plus any package containing ".bank" / "bank." / "upi" / ".npci", recomputed on
  each service connect until the user edits the set once), `paused_by_gate` / `paused_pkg` / `paused_at`.
- Detection (EdgeService): event types `typeWindowsChanged|typeWindowStateChanged`, notificationTimeout 0
  (so the very first window event of the app, splash / starting window included, arrives without the 100 ms
  batch delay; WINDOWS_CHANGED is coalesced by a 100 ms post instead). Hot path = one HashSet lookup of
  `event.packageName`. Hit -> hide the bean, set the flag, remove our entry (the system unbinds us). At
  `onServiceConnected` the front app (rootInActiveWindow, else the newest usage event) is checked first.
- Re-enable (PauseGate, main looper, only while paused): queryEvents every 700 ms from `paused_at - 500 ms`,
  newest ACTIVITY_RESUMED wins (our own package ignored); not a paused app -> `A11yGate.set(true)` and clear
  the flag. Process alive thanks to MediaListener. Without notification access: AlarmManager non-wakeup
  `setExact` chain every 2 s to `GateReceiver` (which also gets BOOT_COMPLETED). Screen off: polling stops
  (alarm path: one 30 s non-wakeup alarm keeps a dead process revivable); screen on / user present: check at
  once. MediaListener connect with the flag set: check at once (reboot / process death never leaves the
  overlay off). Opening Dyn Edge's settings always re-enables. Idle cost when not paused: zero.
- Settings: More tab, section "Pause in apps": shield toggle (master) + "Sensitive apps / N apps" row ->
  `AppPickerActivity.pauseIntent()` (multi-select, search, checked first, saved on every tap).
- Known limits: an app that checks the accessibility list before its first window appears may still complain
  the first time - open it again (we are already off by then). If the user turns the overlay off by hand while
  paused, the gate still turns it back on when the app is left.

## Shortcut tiles (2026-11-15): custom Quick tiles with a chosen icon

A shortcut tile opens one target: a Settings page (optionally scrolled to and highlighting one preference), an app,
or an app's info page. The user makes it from the Quick layout editor tray (Shortcuts > "New shortcut").

Files: `ShortcutStore.kt` (data, intent, launch, icon list `ShortcutIcons`), `ShortcutCatalog.kt` (curated Settings
destinations), `ShortcutPickerActivity.kt` (search, then icon + label), 20 new Lucide vectors `ic_smartphone`, `ic_code`,
`ic_globe`, `ic_terminal`, `ic_bug`, `ic_info`, `ic_hash`, `ic_key`, `ic_user`, `ic_upload`, `ic_folder`, `ic_file`,
`ic_star`, `ic_heart`, `ic_zap`, `ic_home`, `ic_link`, `ic_clock`, `ic_calendar`, `ic_mail` (the other 20 icons of the
40-icon grid are existing drawables).

- Layout spec: `sc:<uuid>` in the QuickConfig v2 rings like any tile id (`QuickConfig.valid` accepts the prefix; old
  layouts load unchanged). If the data is missing the slot is skipped on the overlay.
- Data: Prefs key `quick_shortcuts` = `{"v":1,"items":[{"id","kind","action","pkg","cls","hl","icon","app","label","target"}]}`.
  kind `settings` (action + optional hl highlight key + optional pkg/cls component), `app` (pkg + cls, launcher
  activity), `appinfo` (pkg, `ACTION_APPLICATION_DETAILS_SETTINGS`). icon = name in `ShortcutIcons.ALL`; app = true
  draws the target app's own icon (kinds app / appinfo) on a white disc like third-party glyphs.
- Highlight: the intent gets extras `:settings:fragment_args_key` = key and `:settings:show_fragment_args` = Bundle with
  the same key; AOSP Settings scrolls to and highlights that preference (best effort; vendor Settings may ignore it and
  just open the page). E.g. About phone + `build_number`, Developer options + `enable_adb`.
- Overlay: `WinQuick` treats the id like other tiles: inactive look, icon from the store (or app bitmap in `cusBmp`);
  `Tiles.perform` -> `ShortcutStore.launch` (NEW_TASK, falls back to Settings, true = panel collapses); long-press ->
  `ShortcutStore.edit` (picker in edit mode). `EdgeService` listens to `quick_shortcuts` too and reloads the quick window.
- Editor: tray section "Shortcuts" = "New shortcut" (plus) + shortcuts not placed (no free slot). Created tiles go to
  the first free slot (`onActivityResult`). Selecting a placed shortcut shows "Edit" in the selection row. Removing it
  (trash or drag out) deletes its data; the editor keeps the removed item in memory so Undo restores it.
- Picker: step 1 search over catalog + launcher apps + "App info: <name>" (empty query: catalog and apps); step 2 icon
  grid (app icon first when the target has an app, then 40 Lucide icons), optional label (default = target name), Save.
- Catalog: `ShortcutCatalog.ALL` entries `e(id, label, keywords, action, hl, icon)`; actions are string literals so
  hidden AOSP actions work; entries whose intent does not resolve (`resolveActivity`) are hidden (cached per process).
  To add one: append a line with a unique id, lowercase keywords, optional highlight key (AOSP preference key) and
  one of the icon names in `ShortcutIcons`.
- Related fix: `AppRing.scale` (moved out of `WinApps.applyRingScale`) applies the apps ring percents
  (`w_apps_ring_in` / `w_apps_ring_out`); `WinApps.layout` and the editor's `RingPreview` both call it.
