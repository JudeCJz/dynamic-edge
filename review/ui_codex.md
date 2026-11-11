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
session id: 01a1c5b3-61a5-7931-abc6-1611b5ff99f1
--------
user
You are a senior Android product designer + engineer. Project: Dynamic Edge (folder dynamic-edge, package com.dynedge),
a corner overlay widget. Its SETTINGS app (MainActivity.kt + FlatSlider.kt + QuickEditorActivity.kt, AppPickerActivity,
ClipboardActivity; prefs in Prefs.kt, WindowsConfig.kt) is "a jumbled mess" per the user. Read .agents/skills/dynamic-edge/SKILL.md,
Prefs.kt, MainActivity.kt, FlatSlider.kt, QuickEditorActivity.kt. Do NOT edit files. Output a concrete redesign spec:

User requirements (must all be met):
- App background must NOT be black: a grey background (pick exact hex values for light-ish dark grey surfaces);
  app elements (toggles, sliders, selections, headers) use the user's chosen theme/accent colour.
- User can set: a custom solid colour for the edge, custom gradient colour(s) for the edge gradient, and gradient on/off/
  strength. Full custom colour picking (hue/sat/value or a compact picker), plus presets. No emojis; Lucide icons.
- Proper categorisation and visual hierarchy; flat UI (no boxes inside boxes); minimal text; >= 44 dp targets.
- Existing settings to organise (see Prefs/MainActivity): sizes, windows (order/on/off), quick layout editor, clipboard,
  apps, live activities, setup/permissions rows, accent, fill style, flare, idle design, visualizer, beat reaction,
  beat bounce, opacity, base colour, rim, glow, speed, bounce, haptics, swipe sensitivity, reset.

Deliver: (1) information architecture: top-level structure (single scroll with sticky category chips? or a home list
of categories opening sub-pages?) with every setting placed, and why; (2) visual system: exact colours (bg, surface,
divider, text primary/secondary, accent usage), type scale, spacing, row anatomy, control styles; (3) the colour
picker design for edge solid colour + gradient colours; (4) how the live preview of the edge works inside settings;
(5) a prioritised implementation plan with file-level changes. Be specific and opinionated.
codex
I’m reading the requested skill and settings files first. I’ll keep this read-only and base the redesign on the existing preference names and screen structure.
exec
"C:\\WINDOWS\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" -Command "Get-Content -Raw '.agents/skills/dynamic-edge/SKILL.md'; Get-Content -Raw 'app/src/main/java/com/dynedge/Prefs.kt'; Get-Content -Raw 'app/src/main/java/com/dynedge/MainActivity.kt'; Get-Content -Raw 'app/src/main/java/com/dynedge/FlatSlider.kt'; Get-Content -Raw 'app/src/main/java/com/dynedge/QuickEditorActivity.kt'" in D:\VS\PROJECTS\FREE WORK\dynamic-edge
 succeeded in 297ms:
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

package com.dynedge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.MediaStore

object Prefs {
    const val NAME = "edge"
    /** Neutral grey for all settings chrome (toggles, slider fills, selection rings); the overlay accent is separate. */
    val CHROME = 0xFF9A9AA0.toInt()
    const val KEY_IDLE_DP = "idle_dp"
    const val KEY_OPEN_DP = "open_dp"
    const val KEY_APPS = "apps"
    const val IDLE_MIN = 32f
    const val IDLE_MAX = 80f
    const val IDLE_DEF = 48f
    const val OPEN_MIN = 170f
    const val OPEN_MAX = 300f
    const val OPEN_DEF = 216f
    const val MAX_APPS = 6

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun idleDp(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_IDLE_DP, IDLE_DEF).coerceIn(IDLE_MIN, IDLE_MAX)

    fun openDp(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_OPEN_DP, OPEN_DEF).coerceIn(OPEN_MIN, OPEN_MAX)

    fun setIdleDp(ctx: Context, v: Float) {
        prefs(ctx).edit().putFloat(KEY_IDLE_DP, v.coerceIn(IDLE_MIN, IDLE_MAX)).apply()
    }

    fun setOpenDp(ctx: Context, v: Float) {
        prefs(ctx).edit().putFloat(KEY_OPEN_DP, v.coerceIn(OPEN_MIN, OPEN_MAX)).apply()
    }

    // ---------------------------------------------------------------- looks and feel
    const val KEY_ACCENT = "accent"            // Int ARGB
    const val KEY_GRADIENT = "gradient"        // Int 0..100, warmth/strength of the corner gradient
    const val KEY_RIM = "rim"                  // Boolean, thin border along the curve
    const val KEY_GLOW = "glow"                // Boolean, accent glow on touch / open
    const val KEY_SPEED = "speed"              // Int 0..100, 0 = slow and fluid, 100 = snappy
    const val KEY_BOUNCE = "bounce"            // Int 0..100, 0 = no overshoot, 100 = very bouncy
    const val KEY_HAPTICS = "haptics"          // Boolean
    const val KEY_IDLE_STYLE = "idle_style"    // String, one of IDLE_STYLES
    const val KEY_VISUALIZER = "visualizer"    // Boolean, music bars on the idle bean while playing
    const val KEY_PREVIEW = "preview"          // String "", "idle" or "open"; transient, set by the settings screen

    const val KEY_LIVE = "live"                // Boolean, live activities (default true)
    fun live(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LIVE, true)

    val ACCENTS = intArrayOf(
        0xFFFF4B3E.toInt(), // red (default)
        0xFFFF9F0A.toInt(), // orange
        0xFFFFD60A.toInt(), // yellow
        0xFF30D158.toInt(), // green
        0xFF0A84FF.toInt(), // blue
        0xFFBF5AF2.toInt(), // purple
        0xFFFF375F.toInt(), // pink
        0xFFF2EDE4.toInt()  // cream / white
    )
    val IDLE_STYLES = listOf("none", "glyph")
    val FILLS = listOf("gradient", "solid", "dots")
    val VIZ_STYLES = listOf("bars", "spectrum", "rays", "wave")
    const val PREVIEW_NONE = ""
    const val PREVIEW_IDLE = "idle"
    const val PREVIEW_OPEN = "open"

    fun accent(ctx: Context): Int = prefs(ctx).getInt(KEY_ACCENT, ACCENTS[0])
    fun gradient(ctx: Context): Int = prefs(ctx).getInt(KEY_GRADIENT, 35).coerceIn(0, 100)
    fun rim(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_RIM, true)
    fun glow(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_GLOW, true)
    fun speed(ctx: Context): Int = prefs(ctx).getInt(KEY_SPEED, 50).coerceIn(0, 100)
    fun bounce(ctx: Context): Int = prefs(ctx).getInt(KEY_BOUNCE, 40).coerceIn(0, 100)
    fun haptics(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HAPTICS, true)
    /** Removed designs (pill, orbit, slits, halo, meter, grid, eclipse and the old v1.3 ids) read as "glyph". */
    fun idleStyle(ctx: Context): String {
        val v = prefs(ctx).getString(KEY_IDLE_STYLE, "glyph") ?: "glyph"
        return if (v == "none" || v == "plain") "none" else "glyph"
    }

    const val KEY_FLARE = "flare"              // Float dp 0..28, edge fillets where the shape meets the screen edges
    const val FLARE_MAX = 28f
    const val FLARE_DEF = 10f
    fun flareDp(ctx: Context): Float = prefs(ctx).getFloat(KEY_FLARE, FLARE_DEF).coerceIn(0f, FLARE_MAX)

    const val KEY_FILL = "fill"                // String, one of FILLS
    fun fill(ctx: Context): String =
        prefs(ctx).getString(KEY_FILL, "gradient")?.takeIf { it in FILLS } ?: "gradient" // removed ids -> gradient

    const val KEY_VIZ_STYLE = "viz_style"      // String, one of VIZ_STYLES
    fun vizStyle(ctx: Context): String =
        prefs(ctx).getString(KEY_VIZ_STYLE, "spectrum")?.takeIf { it in VIZ_STYLES } ?: "spectrum" // removed ids -> spectrum
    fun visualizer(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_VISUALIZER, true)
    fun preview(ctx: Context): String = prefs(ctx).getString(KEY_PREVIEW, PREVIEW_NONE) ?: PREVIEW_NONE

    /** Stiffness multiplier from KEY_SPEED: 0.55 (fluid) .. 1.6 (snappy). */
    fun stiffnessScale(ctx: Context): Float = 0.55f + 1.05f * speed(ctx) / 100f
    /** Damping ratio for open motion from KEY_BOUNCE: 0.98 (none) .. 0.55 (bouncy). */
    fun openDamping(ctx: Context): Float = 0.98f - 0.43f * bounce(ctx) / 100f

    fun resetLooks(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_ACCENT).remove(KEY_GRADIENT).remove(KEY_RIM).remove(KEY_GLOW)
            .remove(KEY_SPEED).remove(KEY_BOUNCE).remove(KEY_HAPTICS).remove(KEY_IDLE_STYLE)
            .remove(KEY_VISUALIZER).remove(KEY_FLARE).remove(KEY_FILL).remove(KEY_VIZ_STYLE).remove(KEY_BEAT)
            .remove(KEY_BEAT_AMOUNT).remove(KEY_OPACITY).remove(KEY_BASE).remove(KEY_SWIPE_SENS)
            .apply()
    }

    const val KEY_BEAT = "beat"               // Boolean, whole edge reacts to music beats (default on)
    fun beat(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BEAT, true)

    const val KEY_BEAT_AMOUNT = "beat_amount"  // Int 0..100, idle bean bounce with the beat (100 = +12% scale)
    fun beatAmount(ctx: Context): Int = prefs(ctx).getInt(KEY_BEAT_AMOUNT, 50).coerceIn(0, 100)

    const val KEY_OPACITY = "opacity"          // Int 30..100 %, alpha of the whole overlay
    const val OPACITY_MIN = 30
    fun opacityPct(ctx: Context): Int = prefs(ctx).getInt(KEY_OPACITY, 100).coerceIn(OPACITY_MIN, 100)

    const val KEY_BASE = "base"                // Int colour, solid base of the edge shape (default black)
    val BASES = intArrayOf(
        0xFF000000.toInt(), 0xFF111114.toInt(), 0xFF1C1C1E.toInt(), 0xFF1A1424.toInt(),
        0xFF0E1A24.toInt(), 0xFF1A2418.toInt(), 0xFF241414.toInt(), 0xFF2C2C2E.toInt()
    )
    fun base(ctx: Context): Int = prefs(ctx).getInt(KEY_BASE, BASES[0]) or (0xFF shl 24)

    const val KEY_SWIPE_SENS = "swipe_sens"    // Int 0..100, window swipe sensitivity (default 35 = fairly firm)
    fun swipeSens(ctx: Context): Int = prefs(ctx).getInt(KEY_SWIPE_SENS, 35).coerceIn(0, 100)

    fun apps(ctx: Context): List<ComponentName> {
        val s = prefs(ctx).getString(KEY_APPS, null) ?: return defaultApps(ctx)
        val out = ArrayList<ComponentName>()
        for (part in s.split("|")) {
            if (part.isEmpty()) continue
            val c = ComponentName.unflattenFromString(part) ?: continue
            if (c !in out) out.add(c)
            if (out.size >= MAX_APPS) break
        }
        return out
    }

    fun setApps(ctx: Context, list: List<ComponentName>) {
        val s = list.distinct().take(MAX_APPS).joinToString("|") { it.flattenToString() }
        prefs(ctx).edit().putString(KEY_APPS, s).apply()
    }

    private fun defaultApps(ctx: Context): List<ComponentName> {
        val pm = ctx.packageManager
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY),
            Intent(android.provider.Settings.ACTION_SETTINGS)
        )
        val out = ArrayList<ComponentName>()
        for (i in intents) {
            val ai = try {
                pm.resolveActivity(i, 0)?.activityInfo
            } catch (e: Exception) {
                null
            } ?: continue
            if (ai.packageName == "android") continue
            val c = ComponentName(ai.packageName, ai.name)
            if (c !in out) out.add(c)
        }
        return out
    }
}

package com.dynedge

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Setup and settings screen. Framework Views built in code, pure black, flat rows. */
class MainActivity : Activity() {

    private class Perm(val label: String, val ok: () -> Boolean, val intent: () -> Intent)

    private var firstInSection = true
    private var showAll = false
    private lateinit var setupBox: LinearLayout
    private lateinit var setupCount: TextView
    private val sliders = ArrayList<FlatSlider>()
    private val toggles = ArrayList<FlatToggle>()
    private val previews = ArrayList<IdlePreview>()
    private lateinit var appsBox: LinearLayout
    private lateinit var scroll: ScrollView

    private val white = Color.WHITE
    private val grey = Color.parseColor("#8E8E93")
    private val divider = Color.parseColor("#1C1C1E")
    private val red = Color.parseColor("#C7C7CC") // action text: neutral light grey (settings chrome has no accent)
    private val chrome = Prefs.CHROME
    private lateinit var statusRow: View

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun edit() = Prefs.prefs(this).edit()

    private var hookMode = false

    private fun showRoot(s: View) {
        setContentView(s)
    }

    /**
     * adb test hook, no UI: am start -n com.dynedge/.MainActivity --es set_prefs "flare=12.0;fill=grooves;rim=false"
     * [--es preview idle|open|none]. Types: true/false Boolean, integer Int, number with a dot Float, "#RRGGBB"
     * or 0xAARRGGBB Int (colour), else String; idle_dp / open_dp / flare are always stored as Float.
     */
    private fun runHook(): Boolean {
        val sp = intent.getStringExtra("set_prefs")
        val pv = intent.getStringExtra("preview")
        if (intent.getBooleanExtra("dump_prefs", false)) {
            // adb test hook: logcat -s DynEdgePrefs prints every pref as key=value (restorable through set_prefs)
            for ((k, v) in Prefs.prefs(this).all) android.util.Log.i("DynEdgePrefs", "$k=$v")
            return true
        }
        if (sp == null && pv == null) return false
        val e = edit()
        if (sp != null) {
            for (part in sp.split(";")) {
                val i = part.indexOf('=')
                if (i <= 0) continue
                val k = part.substring(0, i).trim()
                val v = part.substring(i + 1).trim()
                when {
                    k == Prefs.KEY_IDLE_DP || k == Prefs.KEY_OPEN_DP || k == Prefs.KEY_FLARE ->
                        e.putFloat(k, v.toFloatOrNull() ?: continue)
                    v == "true" || v == "false" -> e.putBoolean(k, v == "true")
                    v.startsWith("#") -> e.putInt(k, try { Color.parseColor(v) } catch (x: Exception) { continue })
                    v.startsWith("0x") -> e.putInt(k, v.substring(2).toLongOrNull(16)?.toInt() ?: continue)
                    v.toIntOrNull() != null -> e.putInt(k, v.toInt())
                    v.contains('.') && v.toFloatOrNull() != null -> e.putFloat(k, v.toFloat())
                    else -> e.putString(k, v)
                }
            }
        }
        if (pv != null) e.putString(Prefs.KEY_PREVIEW, if (pv == "idle" || pv == "open") pv else Prefs.PREVIEW_NONE)
        e.commit()
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (runHook()) {
            hookMode = true
            finish()
            return
        }
        // adb hook: am start -n com.dynedge/.MainActivity --ez white_wallpaper true
        if (intent.getBooleanExtra("white_wallpaper", false)) setWhiteWallpaper()
        // adb hook: push an image to Android/data/com.dynedge/files/<name>, then
        // am start -n com.dynedge/.MainActivity --es wallpaper_file <name>
        intent.getStringExtra("wallpaper_file")?.let { name ->
            try {
                val f = java.io.File(getExternalFilesDir(null), name)
                java.io.FileInputStream(f).use {
                    android.app.WallpaperManager.getInstance(this)
                        .setStream(it, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
                }
            } catch (e: Exception) {
                android.util.Log.w("DynamicEdge", "wallpaper_file failed", e)
            }
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.setDecorFitsSystemWindows(false)
        showRoot(build())
    }

    private fun build(): ScrollView {
        sliders.clear(); toggles.clear(); previews.clear()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.addView(text("Dynamic Edge", 22f, white, Typeface.DEFAULT_BOLD).apply {
            setPadding(dp(16), dp(20), dp(16), dp(0))
        })

        statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            visibility = View.GONE
            addView(text("Overlay stopped", 15f, white, Typeface.DEFAULT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("Battery", 14f, grey, Typeface.DEFAULT).apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(44)
                minimumWidth = dp(64)
                isClickable = true
                background = ripple()
                setOnClickListener { open { appDetails() } }
            })
        }
        content.addView(statusRow, matchWrap())

        // SIZE (first, so the live preview does not cover the slider being dragged)
        header(content, "SIZE")
        addSlider(content, "Corner size", Prefs.IDLE_MIN, Prefs.IDLE_MAX, Prefs.idleDp(this), { "${it.toInt()} dp" },
            Prefs.PREVIEW_IDLE, { Prefs.setIdleDp(this, it) })
        addSlider(content, "Open size", Prefs.OPEN_MIN, Prefs.OPEN_MAX, Prefs.openDp(this), { "${it.toInt()} dp" },
            Prefs.PREVIEW_OPEN, { Prefs.setOpenDp(this, it) })

        // WINDOWS (on/off + order; long-press a row and drag)
        header(content, "WINDOWS")
        addWindows(content)

        // EDGE (layout editor, clipboard history, live activities)
        header(content, "EDGE")
        content.addView(simpleRow("Quick layout", ">", grey) {
            open { Intent(this, QuickEditorActivity::class.java) }
        })
        sep(content)
        content.addView(simpleRow("Clipboard", ">", grey) {
            open { Intent(this, ClipboardActivity::class.java) }
        })
        sep(content)
        addSwitch(content, "Live activities", Prefs.live(this)) { edit().putBoolean(Prefs.KEY_LIVE, it).apply() }

        // LOOKS
        header(content, "LOOKS")
        addSwatches(content)
        addColorRow(content, Prefs.BASES, Prefs.base(this), Prefs.KEY_BASE, true)
        addSlider(content, "Corner gradient", 0f, 100f, Prefs.gradient(this).toFloat(), { "${it.toInt()}%" },
            Prefs.PREVIEW_OPEN, { edit().putInt(Prefs.KEY_GRADIENT, it.toInt()).apply(); previews.forEach { p -> p.gradient = it.toInt() } })
        addSwitch(content, "Border", Prefs.rim(this)) { edit().putBoolean(Prefs.KEY_RIM, it).apply() }
        addSwitch(content, "Glow", Prefs.glow(this)) { edit().putBoolean(Prefs.KEY_GLOW, it).apply() }
        addPicker(content, IdlePreview.FILL, Prefs.FILLS, Prefs.fill(this), Prefs.KEY_FILL)
        addSlider(content, "Flare", 0f, Prefs.FLARE_MAX, Prefs.flareDp(this), { "${it.toInt()} dp" },
            Prefs.PREVIEW_IDLE, { edit().putFloat(Prefs.KEY_FLARE, it).apply() })
        addPicker(content, IdlePreview.IDLE, Prefs.IDLE_STYLES, Prefs.idleStyle(this), Prefs.KEY_IDLE_STYLE)
        addSwitch(content, "Music visualizer", Prefs.visualizer(this)) { edit().putBoolean(Prefs.KEY_VISUALIZER, it).apply() }
        addPicker(content, IdlePreview.VIZ, Prefs.VIZ_STYLES, Prefs.vizStyle(this), Prefs.KEY_VIZ_STYLE)
        val bounceBox = arrayOfNulls<View>(1)
        addSwitch(content, "Beat reaction", Prefs.beat(this)) {
            edit().putBoolean(Prefs.KEY_BEAT, it).apply()
            bounceBox[0]?.visibility = if (it) View.VISIBLE else View.GONE
        }
        bounceBox[0] = addSlider(content, "Beat bounce", 0f, 100f, Prefs.beatAmount(this).toFloat(), { "${it.toInt()}%" },
            Prefs.PREVIEW_IDLE, { edit().putInt(Prefs.KEY_BEAT_AMOUNT, it.toInt()).apply() })
        bounceBox[0]?.visibility = if (Prefs.beat(this)) View.VISIBLE else View.GONE
        addSlider(content, "Opacity", Prefs.OPACITY_MIN.toFloat(), 100f, Prefs.opacityPct(this).toFloat(), { "${it.toInt()}%" },
            Prefs.PREVIEW_OPEN, { edit().putInt(Prefs.KEY_OPACITY, it.toInt()).apply() })

        // FEEL
        header(content, "FEEL")
        addSlider(content, "Speed", 0f, 100f, Prefs.speed(this).toFloat(), { "${it.toInt()}%" },
            null, { edit().putInt(Prefs.KEY_SPEED, it.toInt()).apply() })
        addSlider(content, "Bounce", 0f, 100f, Prefs.bounce(this).toFloat(), { "${it.toInt()}%" },
            null, { edit().putInt(Prefs.KEY_BOUNCE, it.toInt()).apply() })
        addSlider(content, "Swipe sensitivity", 0f, 100f, Prefs.swipeSens(this).toFloat(), { "${it.toInt()}%" },
            null, { edit().putInt(Prefs.KEY_SWIPE_SENS, it.toInt()).apply() })
        addSwitch(content, "Haptics", Prefs.haptics(this)) { edit().putBoolean(Prefs.KEY_HAPTICS, it).apply() }
        sep(content)
        content.addView(text("Reset looks", 15f, red, Typeface.DEFAULT).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            minimumHeight = dp(44)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            background = ripple()
            setOnClickListener {
                Prefs.resetLooks(this@MainActivity)
                val y = scroll.scrollY
                val s = build()
                showRoot(s)
                s.post { s.scrollTo(0, y) }
                buildApps()
                refreshSetup()
            }
        })

        // APPS
        header(content, "APPS")
        appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(appsBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // SETUP (last)
        setupCount = text("", 11f, grey, Typeface.DEFAULT_BOLD)
        header(content, "SETUP", setupCount)
        setupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(setupBox, matchWrap())
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(24)))

        scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }
        return scroll
    }

    override fun onPause() {
        super.onPause()
        if (hookMode) return
        edit().putString(Prefs.KEY_PREVIEW, Prefs.PREVIEW_NONE).apply()
    }

    override fun onResume() {
        super.onResume()
        if (hookMode) return
        refreshSetup()
        buildApps()
        refreshStatus()
        scroll.postDelayed({ refreshStatus() }, 1500L) // the service may still be binding
    }

    /** One short row when the overlay is switched on but the service is not running (e.g. stopped by battery saving). */
    private fun refreshStatus() {
        statusRow.visibility = if (isOverlayServiceEnabled() && !EdgeService.connected) View.VISIBLE else View.GONE
    }

    private fun perms(): List<Perm> = listOf(
        Perm("Overlay service", { isOverlayServiceEnabled() }, { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) }),
        Perm("Media access", { isMediaListenerEnabled() }, { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }),
        Perm("Do Not Disturb access", { isDndGranted() }, { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }),
        Perm("Modify system settings", { Settings.System.canWrite(this) },
            { Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")) }),
        Perm("Assistant corner swipe off", { !isAssistSwipeOn() }, { Intent(Settings.ACTION_SETTINGS) }),
        Perm("Microphone", { granted("android.permission.RECORD_AUDIO") }, { appDetails() }),
        Perm("Usage access", { RecentApps.hasAccess(this) }, { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) }),
        Perm("Secure settings", { granted("android.permission.WRITE_SECURE_SETTINGS") }, { appDetails() })
    )

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    private fun refreshSetup() {
        setupBox.removeAllViews()
        val all = perms()
        val missing = all.filter { p -> !(try { p.ok() } catch (e: Exception) { false }) }
        val accent = chrome
        if (missing.isEmpty()) {
            setupCount.text = ""
            val row = simpleRow("Setup", "Done", Color.parseColor("#30D158")) {
                showAll = !showAll
                refreshSetup()
            }
            setupBox.addView(row, matchWrap())
            if (showAll) {
                for (p in all) {
                    setupBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
                    setupBox.addView(simpleRow(p.label, "On", white) { open(p.intent) }, matchWrap())
                }
            }
            return
        }
        setupCount.text = "${missing.size} missing"
        for ((i, p) in missing.withIndex()) {
            if (i > 0) setupBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            setupBox.addView(simpleRow(p.label, "Fix", accent) { open(p.intent) }, matchWrap())
        }
    }

    private fun open(intent: () -> Intent) {
        try {
            startActivity(intent())
        } catch (e: Exception) {
        }
    }

    private fun simpleRow(label: String, state: String, stateColor: Int, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            isClickable = true
            background = ripple()
            addView(text(label, 15f, white, Typeface.DEFAULT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(state, 14f, stateColor, Typeface.DEFAULT))
            setOnClickListener { onClick() }
        }

    private fun buildApps() {
        appsBox.removeAllViews()
        val pm = packageManager
        val list = Prefs.apps(this).toMutableList()
        val shown = ArrayList<ComponentName>()
        for (cn in list) {
            val info = try {
                pm.getActivityInfo(cn, 0)
            } catch (e: Exception) {
                continue
            }
            shown.add(cn)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(16), 0, dp(4), 0)
            }
            val icon = ImageView(this).apply {
                try {
                    setImageDrawable(info.loadIcon(pm))
                } catch (e: Exception) {
                }
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
            val name = text(info.loadLabel(pm).toString(), 15f, white, Typeface.DEFAULT).apply {
                setPadding(dp(12), 0, 0, 0)
            }
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // removal only through this explicit 44 dp icon, never by tapping the row
            val rm = ImageView(this).apply {
                setImageResource(R.drawable.ic_x)
                setColorFilter(grey)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(11), dp(11), dp(11), dp(11))
                isClickable = true
                background = ripple()
                contentDescription = "Remove"
                setOnClickListener {
                    shown.remove(cn)
                    Prefs.setApps(this@MainActivity, shown)
                    buildApps()
                }
            }
            row.addView(rm, LinearLayout.LayoutParams(dp(44), dp(44)))
            if (appsBox.childCount > 0) appsBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            appsBox.addView(row, matchWrap())
        }
        if (shown.size < Prefs.MAX_APPS) {
            val add = text("Add app", 15f, red, Typeface.DEFAULT).apply {
                minimumHeight = dp(44)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(6), dp(16), dp(6))
                isClickable = true
                background = ripple()
                setOnClickListener {
                    // persist the cleaned list so removed packages do not linger
                    Prefs.setApps(this@MainActivity, shown)
                    startActivity(Intent(this@MainActivity, AppPickerActivity::class.java))
                }
            }
            if (appsBox.childCount > 0) appsBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            appsBox.addView(add, matchWrap())
        }
    }

    private fun ripple() = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, ColorDrawable(Color.WHITE))

    private fun line(): View = View(this).apply { setBackgroundColor(divider) }

    private fun text(s: String, sp: Float, color: Int, face: Typeface): TextView = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = face
    }

    private fun header(parent: LinearLayout, s: String, right: TextView? = null) {
        firstInSection = true
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(4))
        }
        row.addView(text(s.uppercase(), 11f, grey, Typeface.DEFAULT_BOLD).apply { letterSpacing = 0.1f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (right != null) row.addView(right)
        parent.addView(row, matchWrap())
    }

    /** Divider between rows inside a section (not before the first). */
    private fun sep(parent: LinearLayout) {
        if (!firstInSection) parent.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        firstInSection = false
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    // sliders and toggles are grey chrome; only the look previews follow the overlay accent
    private fun applyAccent(c: Int) {
        previews.forEach { it.accent = c }
    }

    private fun addSlider(
        parent: LinearLayout, label: String, min: Float, max: Float, value: Float,
        fmt: (Float) -> String, preview: String?, save: (Float) -> Unit
    ): View {
        sep(parent)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(box, matchWrap())
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val valueView = text(fmt(value), 14f, grey, Typeface.DEFAULT)
        head.addView(text(label, 15f, white, Typeface.DEFAULT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(valueView)
        box.addView(head, matchWrap())

        val s = FlatSlider(this).apply {
            this.min = min
            this.max = max
            this.value = value
            accent = chrome
            setPadding(dp(16), 0, dp(16), 0)
            onStart = { if (preview != null) edit().putString(Prefs.KEY_PREVIEW, preview).apply() }
            onChange = { v -> valueView.text = fmt(v); save(v) }
            onStop = { if (preview != null) edit().putString(Prefs.KEY_PREVIEW, Prefs.PREVIEW_NONE).apply() }
        }
        sliders.add(s)
        box.addView(s, matchWrap())
        return box
    }

    private fun addSwitch(parent: LinearLayout, label: String, on: Boolean, save: (Boolean) -> Unit) {
        sep(parent)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        row.addView(text(label, 15f, white, Typeface.DEFAULT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val t = FlatToggle(this).apply {
            accent = chrome
            checked = on
            onToggle = save
        }
        toggles.add(t)
        row.addView(t)
        parent.addView(row, matchWrap())
    }

    private fun swatch(color: Int, selected: Boolean, outlined: Boolean): android.graphics.drawable.Drawable {
        val g = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            when {
                selected -> setStroke(dp(2), Color.parseColor("#E5E5EA"))
                outlined -> setStroke(dp(1), Color.parseColor("#48484A"))
                else -> setStroke(0, Color.TRANSPARENT)
            }
        }
        // 28 dp dot centred in a 44 dp cell
        return android.graphics.drawable.LayerDrawable(arrayOf<android.graphics.drawable.Drawable>(g)).apply {
            setLayerSize(0, dp(28), dp(28))
            setLayerGravity(0, Gravity.CENTER)
        }
    }

    /** A row of colour cells (each at least 44 dp wide and tall); the choice is stored under [key]. */
    private fun addColorRow(parent: LinearLayout, colors: IntArray, cur: Int, key: String, outlined: Boolean, onPick: (Int) -> Unit = {}) {
        sep(parent)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        val cells = ArrayList<View>()
        fun mark(sel: Int) {
            for ((i, v) in cells.withIndex()) v.background = swatch(colors[i], colors[i] == sel, outlined)
        }
        for (c in colors) {
            val v = View(this).apply {
                isClickable = true
                setOnClickListener {
                    edit().putInt(key, c).apply()
                    mark(c)
                    onPick(c)
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            cells.add(v)
            row.addView(v, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        mark(cur or (0xFF shl 24))
        parent.addView(row, matchWrap())
    }

    private fun addSwatches(parent: LinearLayout) {
        addColorRow(parent, Prefs.ACCENTS, Prefs.accent(this) or (0xFF shl 24), Prefs.KEY_ACCENT, false) { applyAccent(it) }
    }

    /** Mini bean previews (no labels) in rows of cells at least 44 dp square; the selected one gets a grey ring. */
    private fun addPicker(parent: LinearLayout, kind: Int, ids: List<String>, cur: String, key: String) {
        sep(parent)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        val availDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density - 32f
        val gap = 4f
        val maxPer = ((availDp + gap) / (44f + gap)).toInt().coerceAtLeast(1)
        val rows = (ids.size + maxPer - 1) / maxPer
        val per = (ids.size + rows - 1) / rows
        val size = ((availDp - gap * (per - 1)) / per).coerceIn(44f, 56f)
        val mine = ArrayList<IdlePreview>()
        var idx = 0
        for (rIdx in 0 until rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            var n = 0
            while (n < per && idx < ids.size) {
                val id = ids[idx]
                val p = IdlePreview(this, id, kind).apply {
                    sizeDp = size
                    accent = Prefs.accent(this@MainActivity)
                    chrome = this@MainActivity.chrome
                    gradient = Prefs.gradient(this@MainActivity)
                    selectedStyle = id == cur
                    isClickable = true
                    setOnClickListener {
                        edit().putString(key, id).apply()
                        mine.forEach { it.selectedStyle = it === this }
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                mine.add(p)
                previews.add(p)
                if (n > 0) row.addView(View(this), LinearLayout.LayoutParams(dp(gap.toInt()), 1))
                row.addView(p)
                idx++
                n++
            }
            box.addView(row, matchWrap())
        }
        parent.addView(box, matchWrap())
    }

    // ---------------------------------------------------------------- windows list

    private var winEntries = ArrayList<WindowsConfig.Entry>()
    private val winRows = ArrayList<View>()
    private lateinit var winBox: LinearLayout

    private fun addWindows(parent: LinearLayout) {
        winEntries = ArrayList(WindowsConfig.load(this))
        winBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(winBox, matchWrap())
        buildWindowRows()
    }

    private fun buildWindowRows() {
        winBox.removeAllViews()
        winRows.clear()
        val rowH = dp(48)
        val accent = chrome
        for (e in winEntries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(16), 0)
                tag = e.id
            }
            val grip = ImageView(this).apply {
                setImageResource(R.drawable.ic_grip)
                setColorFilter(grey)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(10), dp(12), dp(10), dp(12))
                contentDescription = "Reorder"
            }
            row.addView(grip, LinearLayout.LayoutParams(dp(44), rowH))
            val icon = ImageView(this).apply {
                setImageResource(WindowsConfig.icon(e.id))
                setColorFilter(if (e.on) white else grey)
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
            val label = text(WindowsConfig.label(e.id), 15f, if (e.on) white else grey, Typeface.DEFAULT).apply {
                setPadding(dp(14), 0, 0, 0)
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val t = FlatToggle(this).apply {
                this.accent = accent
                checked = e.on
                onToggle = { on ->
                    val idx = winEntries.indexOfFirst { it.id == e.id }
                    if (idx >= 0) winEntries[idx] = WindowsConfig.Entry(e.id, on)
                    icon.setColorFilter(if (on) white else grey)
                    label.setTextColor(if (on) white else grey)
                    WindowsConfig.save(this@MainActivity, winEntries)
                }
            }
            toggles.add(t)
            row.addView(t)
            attachDrag(grip, row)
            winBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH))
            winRows.add(row)
        }
    }

    /** Reordering starts on the grip handle only (so the toggle and the row never compete with it). */
    private fun attachDrag(handle: View, row: View) {
        val rowH = dp(48)
        var orig = 0
        var downY = 0f
        var target = 0
        handle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    orig = winRows.indexOf(row)
                    if (orig < 0) return@setOnTouchListener false
                    target = orig
                    downY = e.rawY
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    row.parent?.requestDisallowInterceptTouchEvent(true)
                    row.setBackgroundColor(divider)
                    row.elevation = dp(2).toFloat()
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val last = winRows.size - 1
                    val dy = (e.rawY - downY).coerceIn(-orig * rowH.toFloat(), (last - orig) * rowH.toFloat())
                    row.translationY = dy
                    val t = Math.round(orig + dy / rowH).coerceIn(0, last)
                    if (t != target) {
                        target = t
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    }
                    for ((i, r) in winRows.withIndex()) {
                        if (r === row) continue
                        r.translationY = when {
                            i in (orig + 1)..target -> -rowH.toFloat()
                            i in target until orig -> rowH.toFloat()
                            else -> 0f
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (target != orig) {
                        val moved = winEntries.removeAt(orig)
                        winEntries.add(target, moved)
                        WindowsConfig.save(this, winEntries)
                    }
                    buildWindowRows()
                    true
                }
                else -> true
            }
        }
    }


    private fun isOverlayServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val cn = ComponentName(this, EdgeService::class.java)
        return enabled.contains(cn.flattenToString()) || enabled.contains(cn.flattenToShortString())
    }

    private fun isMediaListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(packageName)
    }

    private fun isDndGranted(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        return nm.isNotificationPolicyAccessGranted
    }

    private fun isAssistSwipeOn(): Boolean =
        Settings.Secure.getInt(contentResolver, "navigation_google_assist", 0) != 0

    private fun setWhiteWallpaper() {
        try {
            val bmp = android.graphics.Bitmap.createBitmap(108, 240, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            android.app.WallpaperManager.getInstance(this)
                .setBitmap(bmp, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
            bmp.recycle()
        } catch (e: Exception) {
            android.util.Log.w("DynamicEdge", "wallpaper failed", e)
        }
    }
}

package com.dynedge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/** Knobless flat slider: 6 dp rounded track with an accent fill. Drag or tap anywhere on it. */
class FlatSlider(ctx: Context) : View(ctx) {
    var min = 0f
    var max = 100f
    var accent = Color.WHITE
        set(v) { field = v; invalidate() }
    var onStart: () -> Unit = {}
    var onChange: (Float) -> Unit = {}
    var onStop: () -> Unit = {}

    var value = 0f
        set(v) { field = v.coerceIn(min, max); invalidate() }

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2C2C2E") }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var dragging = false

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (44 * d + 0.5f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val th = 6 * d
        val cy = height / 2f
        val l = paddingLeft.toFloat()
        val r = (width - paddingRight).toFloat()
        rect.set(l, cy - th / 2, r, cy + th / 2)
        c.drawRoundRect(rect, th / 2, th / 2, track)
        val f = if (max > min) (value - min) / (max - min) else 0f
        val fr = l + (r - l) * f
        fill.color = accent
        rect.set(l, cy - th / 2, maxOf(fr, l + th), cy + th / 2)
        c.drawRoundRect(rect, th / 2, th / 2, fill)
    }

    private fun setFromX(x: Float) {
        val l = paddingLeft.toFloat()
        val r = (width - paddingRight).toFloat()
        val f = ((x - l) / (r - l)).coerceIn(0f, 1f)
        val nv = Math.round(min + (max - min) * f).toFloat()
        if (nv != value) {
            value = nv
            onChange(nv)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                onStart()
                setFromX(e.x)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) setFromX(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                if (e.actionMasked == MotionEvent.ACTION_UP) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                onStop()
            }
        }
        return true
    }
}

/** Small flat on/off toggle: rounded track, round thumb. */
class FlatToggle(ctx: Context) : View(ctx) {
    var accent = Color.WHITE
        set(v) { field = v; invalidate() }
    var checked = false
        set(v) { field = v; invalidate() }
    var onToggle: (Boolean) -> Unit = {}

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isClickable = true
        setOnClickListener {
            checked = !checked
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            onToggle(checked)
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension((46 * d).toInt(), (44 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        // 28 dp visible switch centred in the 44 dp touch cell
        val h = 28 * d
        val top = (height - h) / 2f
        p.color = if (checked) accent else Color.parseColor("#3A3A3C")
        rect.set(0f, top, width.toFloat(), top + h)
        c.drawRoundRect(rect, h / 2, h / 2, p)
        p.color = if (checked) Color.BLACK else Color.parseColor("#8E8E93")
        val r = h / 2 - 4 * d
        val cx = if (checked) width - h / 2 else h / 2
        c.drawCircle(cx, top + h / 2, r, p)
    }
}


/**
 * Mini bean preview (quarter circle, tip at the bottom-right) of one idle design, fill style or visualizer
 * style. The decoration is drawn by [Looks], the same code the widget uses.
 */
class IdlePreview(ctx: Context, private val style: String, private val kind: Int = IDLE) : View(ctx) {
    companion object {
        const val IDLE = 0
        const val FILL = 1
        const val VIZ = 2
    }

    var accent = Color.WHITE
        set(v) { field = v; invalidate() }
    var gradient = 60
        set(v) { field = v; invalidate() }
    var chrome = Color.GRAY
        set(v) { field = v; invalidate() }
    var selectedStyle = false
        set(v) { field = v; invalidate() }
    var sizeDp = 44f
        set(v) { field = v; requestLayout() }

    private val d = resources.displayMetrics.density
    private val path = Path()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val looks = Looks()
    private val levels = floatArrayOf(0.9f, 0.55f, 0.75f, 0.4f)
    private var gradShader: Shader? = null
    private var gradKey = 0L

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val s = (sizeDp * d + 0.5f).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = w * 0.86f
        val x0 = w - s
        val y0 = h - s
        path.rewind()
        path.moveTo(x0, h)
        path.cubicTo(x0, h - s * 0.5523f, w - s * 0.5523f, y0, w, y0)
        path.lineTo(w, h)
        path.close()
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Color.BLACK
        c.drawPath(path, p)

        val fillId = if (kind == FILL) style else "gradient"
        if (fillId == "gradient") {
            val a = (gradient * 2.2f).toInt().coerceIn(0, 255)
            val key = (a.toLong() shl 40) or (accent.toLong() and 0xFFFFFFFFL) or (s.toLong() shl 48)
            if (gradShader == null || key != gradKey) {
                gradKey = key
                val warm = Color.argb(a, Color.red(accent), Color.green(accent), Color.blue(accent))
                gradShader = RadialGradient(w, h, s, intArrayOf(warm, Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            }
            p.shader = gradShader
            c.drawPath(path, p)
            p.shader = null
        }
        looks.accent = accent
        val k = s / 48f
        if (kind == FILL && fillId == "dots") {
            c.save()
            c.clipPath(path)
            c.translate(w, h)
            looks.drawPattern(c, fillId, s * 1.05f, k, 3.2f, 0f)
            c.restore()
        }

        // outline: accent ring when selected
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.BUTT
        p.strokeWidth = if (selectedStyle) 2f * d else 1f * d
        p.color = if (selectedStyle) chrome else Color.parseColor("#3A3A3C")
        c.drawPath(path, p)

        c.save()
        c.translate(w, h)
        when (kind) {
            IDLE -> looks.drawIdle(c, style, s, 1f, 0f)
            VIZ -> looks.drawViz(c, style, s, false, levels, 1f, k)
        }
        c.restore()
    }
}

package com.dynedge

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Editor screen for the QUICK layout: a ring preview (drag a tile to another slot, remove badge on each tile) and
 * a flat grouped list of every available tile (native, System, Custom, Sliders). Tapping a tile adds it to the
 * first free slot of the right ring, or removes it when it is placed already.
 *
 * Pick mode (opened from a "+" on the overlay, with [EXTRA_RING] / [EXTRA_INDEX]): only the list of tiles that fit
 * that ring is shown; tapping one places it in that slot and the screen closes.
 */
class QuickEditorActivity : Activity() {

    private val rings = QuickRings()
    private val arcs = ArrayList<String>()
    private var pickRing = NO_PICK
    private var pickIndex = 0

    private var accent = Color.WHITE
    private lateinit var catalog: LinearLayout
    private var preview: PreviewView? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.setDecorFitsSystemWindows(false)
        accent = Prefs.CHROME
        pickRing = intent.getIntExtra(EXTRA_RING, NO_PICK)
        pickIndex = intent.getIntExtra(EXTRA_INDEX, Int.MAX_VALUE)
        readModel()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        header.addView(iconButton(R.drawable.ic_chevron_left) { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (pickRing == NO_PICK) {
            header.addView(TextView(this).apply {
                text = "Reset"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(accent)
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                minimumHeight = dp(48)
                setOnClickListener {
                    QuickConfig.reset(this@QuickEditorActivity)
                    refreshAll()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        }
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (pickRing == NO_PICK) {
            val pv = PreviewView(this)
            preview = pv
            root.addView(pv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(270)))
        }

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        catalog = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(24))
        }
        scroll.addView(catalog)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        buildCatalog()
    }

    // ---------------------------------------------------------------- model

    private fun readModel() {
        val l = QuickConfig.load(this)
        rings.set(l)
        arcs.clear()
        arcs.addAll(l.arcs)
    }

    private fun persist() {
        QuickConfig.save(this, rings.toLayout(arcs))
    }

    private fun placed(id: String): Boolean = rings.locate(id) >= 0 || id in arcs

    private fun refreshAll() {
        readModel()
        buildCatalog()
        preview?.invalidate()
    }

    private fun tapTile(id: String) {
        if (pickRing != NO_PICK) {
            QuickConfig.place(this, id, pickRing, pickIndex)
            finish()
            return
        }
        if (placed(id)) QuickConfig.removeId(this, id) else QuickConfig.place(this, id)
        refreshAll()
    }

    private fun tapArc(id: String) {
        if (id in arcs) QuickConfig.removeId(this, id) else QuickConfig.addArc(this, id)
        refreshAll()
    }

    // ---------------------------------------------------------------- list

    private fun iconButton(res: Int, click: () -> Unit): View {
        val v = ImageView(this)
        v.setImageResource(res)
        v.setColorFilter(Color.WHITE)
        v.scaleType = ImageView.ScaleType.CENTER_INSIDE
        v.setPadding(dp(12), dp(12), dp(12), dp(12))
        v.setOnClickListener { click() }
        return v
    }

    private fun cell(res: Int, label: String, active: Boolean, drawable: Drawable? = null, click: () -> Unit): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { click() }
        }
        val img = ImageView(this).apply {
            if (drawable != null) {
                setImageDrawable(drawable)
            } else {
                setImageResource(res)
                setColorFilter(if (active) Color.BLACK else Color.WHITE)
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (active) accent else Color.parseColor("#1C1C1E"))
            }
        }
        c.addView(img, LinearLayout.LayoutParams(dp(46), dp(46)))
        c.addView(TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.parseColor("#8E8E93"))
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(4), 0, 0)
        })
        return c
    }

    private fun header(text: String) {
        catalog.addView(TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(dp(4), dp(14), 0, dp(2))
        })
    }

    private class Item(val id: String, val icon: Int, val drawable: Drawable?, val label: String, val arc: Boolean = false)

    private fun grid(items: List<Item>) {
        val cols = 4
        var row: LinearLayout? = null
        for ((i, t) in items.withIndex()) {
            if (i % cols == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                catalog.addView(row)
            }
            val on = placed(t.id)
            val d = t.drawable?.constantState?.newDrawable()?.mutate()
            if (d != null && on) d.setTint(Color.BLACK)
            row!!.addView(
                cell(t.icon, t.label, on, d) { if (t.arc) tapArc(t.id) else tapTile(t.id) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        val rem = items.size % cols
        if (rem != 0) for (k in rem until cols) row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    }

    private fun arcIcon(id: String): Int = when (id) {
        "brightness" -> R.drawable.ic_sun
        "media_volume" -> R.drawable.ic_volume
        "ring_volume" -> R.drawable.ic_ring
        "notification_volume" -> R.drawable.ic_bell
        "call_volume" -> R.drawable.ic_phone
        else -> R.drawable.ic_alarm
    }

    private fun buildCatalog() {
        catalog.removeAllViews()
        val customRing = pickRing == 2
        val showTiles = !customRing
        val showCustom = pickRing == NO_PICK || customRing
        if (showTiles) {
            val native = Tiles.ALL
            grid(native.map { Item(it, Tiles.staticIcon(it), null, Tiles.label(it)) })
            val sys = QsCatalog.systems(this)
            if (sys.isNotEmpty()) {
                header("System")
                grid(sys.map { Item(it.id, QsCatalog.iconRes(it.id), null, it.label) })
            }
        }
        if (showCustom) {
            val cus = QsCatalog.customs(this)
            if (cus.isNotEmpty()) {
                header("Custom")
                grid(cus.map { Item(it.id, R.drawable.ic_sliders, QsCatalog.drawable(this, it), it.label) })
            }
            // a custom tile placed earlier but no longer installed stays removable
            val gone = rings.ring[2].filter { id -> cus.none { it.id == id } }
            if (gone.isNotEmpty() && pickRing == NO_PICK) {
                grid(gone.map { Item(it, R.drawable.ic_sliders, null, QsCatalog.label(it)) })
            }
        }
        if (pickRing == NO_PICK) {
            header("Sliders")
            grid(Arcs.ALL.map { Item(it, arcIcon(it), null, Arcs.label(it), true) })
        }
    }

    // ---------------------------------------------------------------- preview

    private inner class PreviewView(ctx: Context) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val icons = HashMap<Int, Drawable>()
        private val cusIcons = HashMap<String, Drawable?>()
        private val pv = QuickRings()
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

        private var cx = 0f
        private var cy = 0f
        private var radius = 0f
        private val rr = FloatArray(3)
        private val sr = FloatArray(3)

        // gesture
        private var downX = 0f
        private var downY = 0f
        private var pendId: String? = null
        private var pendBadge = false
        private var pendArc = -1
        private var dragId: String? = null
        private var fr = 0
        private var fi = 0
        private var hovR = -1
        private var hovJ = -1
        private var fx = 0f
        private var fy = 0f

        private fun icon(res: Int): Drawable? {
            var dr = icons[res]
            if (dr == null) {
                dr = context.getDrawable(res)?.mutate() ?: return null
                icons[res] = dr
            }
            return dr
        }

        private fun drawGlyph(c: Canvas, res: Int, x: Float, y: Float, half: Float, color: Int) {
            val dr = icon(res) ?: return
            dr.setTint(color)
            dr.setBounds((x - half).toInt(), (y - half).toInt(), (x + half).toInt(), (y + half).toInt())
            dr.draw(c)
        }

        private fun drawTile(c: Canvas, id: String, x: Float, y: Float, r: Float) {
            p.style = Paint.Style.FILL
            p.color = Color.parseColor("#1C1C1E")
            c.drawCircle(x, y, r, p)
            val half = r * 0.5f
            if (!QsCatalog.isCustom(id)) {
                drawGlyph(c, Tiles.staticIcon(id), x, y, half, Color.WHITE)
                return
            }
            val dr = cusIcons.getOrPut(id) { QsCatalog.entry(context, id)?.let { QsCatalog.drawable(context, it) } } ?: return
            dr.setBounds((x - half).toInt(), (y - half).toInt(), (x + half).toInt(), (y + half).toInt())
            dr.draw(c)
        }

        private fun hasCus(): Boolean = rings.ring[2].isNotEmpty() || QsCatalog.customs(context).isNotEmpty()

        private fun geometry() {
            cx = width - 14 * d
            cy = height - 14 * d
            radius = minOf(cx - 8 * d, cy - 8 * d)
            val two = arcs.size >= 2
            if (hasCus()) {
                rr[0] = radius * if (two) 0.23f else 0.38f
                rr[1] = radius * if (two) 0.40f else 0.56f
                rr[2] = radius * if (two) 0.57f else 0.73f
            } else {
                rr[0] = radius * if (two) 0.38f else 0.42f
                rr[1] = radius * if (two) 0.60f else 0.66f
                rr[2] = 0f
            }
            sr[0] = 15 * d
            sr[1] = 15 * d
            sr[2] = 13 * d
        }

        private fun angle(j: Int, n: Int): Float = if (n <= 1) 45f else 12f + j * (66f / (n - 1))

        private fun sx(r: Int, j: Int): Float =
            cx - (rr[r] * Math.cos(Math.toRadians(angle(j, QuickRings.CAP[r]).toDouble()))).toFloat()

        private fun sy(r: Int, j: Int): Float =
            cy - (rr[r] * Math.sin(Math.toRadians(angle(j, QuickRings.CAP[r]).toDouble()))).toFloat()

        private fun arcR(k: Int): Float = radius * if (arcs.size >= 2) (if (k == 0) 0.88f else 0.78f) else 0.88f

        private fun badgeX(x: Float, r: Float) = x - r * 0.72f
        private fun badgeY(y: Float, r: Float) = y - r * 0.72f

        private fun drawBadge(c: Canvas, x: Float, y: Float) {
            p.style = Paint.Style.FILL
            p.color = Color.BLACK
            c.drawCircle(x, y, 9.5f * d, p)
            p.color = Color.parseColor("#3A3A3C")
            c.drawCircle(x, y, 8f * d, p)
            drawGlyph(c, R.drawable.ic_x, x, y, 4.2f * d, Color.WHITE)
        }

        override fun onDraw(c: Canvas) {
            geometry()
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1f * d
            p.color = Color.parseColor("#2C2C2E")
            rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            c.drawArc(rect, 180f, 90f, false, p)

            p.strokeCap = Paint.Cap.ROUND
            for ((k, id) in arcs.withIndex()) {
                val r = arcR(k)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 5f * d
                p.color = Color.parseColor("#2C2C2E")
                rect.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(rect, 180f, 90f, false, p)
                p.color = accent
                c.drawArc(rect, 180f, 52f, false, p)
                val bx = cx - r
                val by = cy - 14 * d
                p.style = Paint.Style.FILL
                p.color = Color.parseColor("#1C1C1E")
                c.drawCircle(bx, by, 11 * d, p)
                drawGlyph(c, arcIcon(id), bx, by, 7 * d, Color.WHITE)
                drawBadge(c, bx - 8 * d, by - 8 * d)
            }
            p.strokeCap = Paint.Cap.BUTT

            val shown = if (dragId != null) pv else rings
            for (r in 0..2) {
                if (r == 2 && !hasCus()) continue
                for (j in 0 until QuickRings.CAP[r]) {
                    val x = sx(r, j)
                    val y = sy(r, j)
                    val id = if (j < shown.ring[r].size) shown.ring[r][j] else null
                    if (id == null || id == dragId) {
                        p.style = Paint.Style.STROKE
                        p.strokeWidth = (if (id != null) 2f else 1.2f) * d
                        p.color = if (id != null) accent else Color.parseColor("#3A3A3C")
                        c.drawCircle(x, y, sr[r], p)
                    } else {
                        drawTile(c, id, x, y, sr[r])
                        drawBadge(c, badgeX(x, sr[r]), badgeY(y, sr[r]))
                    }
                }
            }
            val di = dragId
            if (di != null) {
                c.save()
                c.scale(1.15f, 1.15f, fx, fy)
                drawTile(c, di, fx, fy, sr[if (QsCatalog.isCustom(di)) 2 else 1])
                c.restore()
            }
        }

        private fun near(x: Float, y: Float, px: Float, py: Float, r: Float): Boolean {
            val dx = x - px
            val dy = y - py
            return dx * dx + dy * dy <= r * r
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            geometry()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    pendId = null
                    pendBadge = false
                    pendArc = -1
                    dragId = null
                    parent.requestDisallowInterceptTouchEvent(true)
                    val bh = 16 * d
                    for (k in arcs.indices) {
                        val r = arcR(k)
                        if (near(e.x, e.y, cx - r - 8 * d, cy - 14 * d - 8 * d, bh)) {
                            pendArc = k
                            return true
                        }
                    }
                    var bestD = Float.MAX_VALUE
                    for (r in 0..2) {
                        if (r == 2 && !hasCus()) continue
                        for (j in 0 until rings.ring[r].size) {
                            val x = sx(r, j)
                            val y = sy(r, j)
                            if (near(e.x, e.y, badgeX(x, sr[r]), badgeY(y, sr[r]), bh)) {
                                pendId = rings.ring[r][j]
                                pendBadge = true
                                return true
                            }
                        }
                    }
                    for (r in 0..2) {
                        if (r == 2 && !hasCus()) continue
                        for (j in 0 until rings.ring[r].size) {
                            val dx = e.x - sx(r, j)
                            val dy = e.y - sy(r, j)
                            val dd = dx * dx + dy * dy
                            val hr = maxOf(sr[r] + 4 * d, 22 * d)
                            if (dd <= hr * hr && dd < bestD) {
                                bestD = dd
                                pendId = rings.ring[r][j]
                            }
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val id = pendId
                    if (dragId == null && id != null && !pendBadge) {
                        val dx = e.x - downX
                        val dy = e.y - downY
                        if (dx * dx + dy * dy > slop * slop) {
                            val loc = rings.locate(id)
                            if (loc >= 0) {
                                dragId = id
                                fr = loc shr 4
                                fi = loc and 15
                                hovR = fr
                                hovJ = fi
                                pv.copyFrom(rings)
                            }
                        }
                    }
                    val di = dragId
                    if (di != null) {
                        fx = e.x
                        fy = e.y
                        val custom = QsCatalog.isCustom(di)
                        var bd = Float.MAX_VALUE
                        var br = -1
                        var bj = -1
                        for (r in 0..2) {
                            if ((r == 2) != custom || (r == 2 && !hasCus())) continue
                            for (j in 0 until QuickRings.CAP[r]) {
                                val dx = e.x - sx(r, j)
                                val dy = e.y - sy(r, j)
                                val dd = dx * dx + dy * dy
                                if (dd < bd) {
                                    bd = dd
                                    br = r
                                    bj = j
                                }
                            }
                        }
                        if (br >= 0 && (br != hovR || bj != hovJ)) {
                            hovR = br
                            hovJ = bj
                            rings.moved(fr, fi, br, bj, pv)
                        }
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val di = dragId
                    val id = pendId
                    if (di != null) {
                        dragId = null
                        if (hovR != fr || hovJ != fi) {
                            rings.copyFrom(pv)
                            persist()
                        }
                        refreshAll()
                    } else if (pendArc >= 0) {
                        if (near(e.x, e.y, downX, downY, slop * 2)) {
                            QuickConfig.removeId(this@QuickEditorActivity, arcs[pendArc])
                            refreshAll()
                        }
                    } else if (id != null && pendBadge && near(e.x, e.y, downX, downY, slop * 2)) {
                        QuickConfig.removeId(this@QuickEditorActivity, id)
                        refreshAll()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragId = null
                    invalidate()
                    return true
                }
            }
            return true
        }
    }

    companion object {
        const val EXTRA_RING = "pick_ring"
        const val EXTRA_INDEX = "pick_index"
        private const val NO_PICK = -9
    }
}


exec
"C:\\WINDOWS\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" -Command '$p='"'app/src/main/java/com/dynedge/MainActivity.kt'; Get-Content "'$p; Get-Content '"'app/src/main/java/com/dynedge/FlatSlider.kt'; rg -n 'KEY_|add|header|section|TextView|Switch|slider|window|live|reset' app/src/main/java/com/dynedge/MainActivity.kt" in D:\VS\PROJECTS\FREE WORK\dynamic-edge
 succeeded in 817ms:
package com.dynedge

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Setup and settings screen. Framework Views built in code, pure black, flat rows. */
class MainActivity : Activity() {

    private class Perm(val label: String, val ok: () -> Boolean, val intent: () -> Intent)

    private var firstInSection = true
    private var showAll = false
    private lateinit var setupBox: LinearLayout
    private lateinit var setupCount: TextView
    private val sliders = ArrayList<FlatSlider>()
    private val toggles = ArrayList<FlatToggle>()
    private val previews = ArrayList<IdlePreview>()
    private lateinit var appsBox: LinearLayout
    private lateinit var scroll: ScrollView

    private val white = Color.WHITE
    private val grey = Color.parseColor("#8E8E93")
    private val divider = Color.parseColor("#1C1C1E")
    private val red = Color.parseColor("#C7C7CC") // action text: neutral light grey (settings chrome has no accent)
    private val chrome = Prefs.CHROME
    private lateinit var statusRow: View

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun edit() = Prefs.prefs(this).edit()

    private var hookMode = false

    private fun showRoot(s: View) {
        setContentView(s)
    }

    /**
     * adb test hook, no UI: am start -n com.dynedge/.MainActivity --es set_prefs "flare=12.0;fill=grooves;rim=false"
     * [--es preview idle|open|none]. Types: true/false Boolean, integer Int, number with a dot Float, "#RRGGBB"
     * or 0xAARRGGBB Int (colour), else String; idle_dp / open_dp / flare are always stored as Float.
     */
    private fun runHook(): Boolean {
        val sp = intent.getStringExtra("set_prefs")
        val pv = intent.getStringExtra("preview")
        if (intent.getBooleanExtra("dump_prefs", false)) {
            // adb test hook: logcat -s DynEdgePrefs prints every pref as key=value (restorable through set_prefs)
            for ((k, v) in Prefs.prefs(this).all) android.util.Log.i("DynEdgePrefs", "$k=$v")
            return true
        }
        if (sp == null && pv == null) return false
        val e = edit()
        if (sp != null) {
            for (part in sp.split(";")) {
                val i = part.indexOf('=')
                if (i <= 0) continue
                val k = part.substring(0, i).trim()
                val v = part.substring(i + 1).trim()
                when {
                    k == Prefs.KEY_IDLE_DP || k == Prefs.KEY_OPEN_DP || k == Prefs.KEY_FLARE ->
                        e.putFloat(k, v.toFloatOrNull() ?: continue)
                    v == "true" || v == "false" -> e.putBoolean(k, v == "true")
                    v.startsWith("#") -> e.putInt(k, try { Color.parseColor(v) } catch (x: Exception) { continue })
                    v.startsWith("0x") -> e.putInt(k, v.substring(2).toLongOrNull(16)?.toInt() ?: continue)
                    v.toIntOrNull() != null -> e.putInt(k, v.toInt())
                    v.contains('.') && v.toFloatOrNull() != null -> e.putFloat(k, v.toFloat())
                    else -> e.putString(k, v)
                }
            }
        }
        if (pv != null) e.putString(Prefs.KEY_PREVIEW, if (pv == "idle" || pv == "open") pv else Prefs.PREVIEW_NONE)
        e.commit()
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (runHook()) {
            hookMode = true
            finish()
            return
        }
        // adb hook: am start -n com.dynedge/.MainActivity --ez white_wallpaper true
        if (intent.getBooleanExtra("white_wallpaper", false)) setWhiteWallpaper()
        // adb hook: push an image to Android/data/com.dynedge/files/<name>, then
        // am start -n com.dynedge/.MainActivity --es wallpaper_file <name>
        intent.getStringExtra("wallpaper_file")?.let { name ->
            try {
                val f = java.io.File(getExternalFilesDir(null), name)
                java.io.FileInputStream(f).use {
                    android.app.WallpaperManager.getInstance(this)
                        .setStream(it, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
                }
            } catch (e: Exception) {
                android.util.Log.w("DynamicEdge", "wallpaper_file failed", e)
            }
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.setDecorFitsSystemWindows(false)
        showRoot(build())
    }

    private fun build(): ScrollView {
        sliders.clear(); toggles.clear(); previews.clear()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.addView(text("Dynamic Edge", 22f, white, Typeface.DEFAULT_BOLD).apply {
            setPadding(dp(16), dp(20), dp(16), dp(0))
        })

        statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            visibility = View.GONE
            addView(text("Overlay stopped", 15f, white, Typeface.DEFAULT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("Battery", 14f, grey, Typeface.DEFAULT).apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(44)
                minimumWidth = dp(64)
                isClickable = true
                background = ripple()
                setOnClickListener { open { appDetails() } }
            })
        }
        content.addView(statusRow, matchWrap())

        // SIZE (first, so the live preview does not cover the slider being dragged)
        header(content, "SIZE")
        addSlider(content, "Corner size", Prefs.IDLE_MIN, Prefs.IDLE_MAX, Prefs.idleDp(this), { "${it.toInt()} dp" },
            Prefs.PREVIEW_IDLE, { Prefs.setIdleDp(this, it) })
        addSlider(content, "Open size", Prefs.OPEN_MIN, Prefs.OPEN_MAX, Prefs.openDp(this), { "${it.toInt()} dp" },
            Prefs.PREVIEW_OPEN, { Prefs.setOpenDp(this, it) })

        // WINDOWS (on/off + order; long-press a row and drag)
        header(content, "WINDOWS")
        addWindows(content)

        // EDGE (layout editor, clipboard history, live activities)
        header(content, "EDGE")
        content.addView(simpleRow("Quick layout", ">", grey) {
            open { Intent(this, QuickEditorActivity::class.java) }
        })
        sep(content)
        content.addView(simpleRow("Clipboard", ">", grey) {
            open { Intent(this, ClipboardActivity::class.java) }
        })
        sep(content)
        addSwitch(content, "Live activities", Prefs.live(this)) { edit().putBoolean(Prefs.KEY_LIVE, it).apply() }

        // LOOKS
        header(content, "LOOKS")
        addSwatches(content)
        addColorRow(content, Prefs.BASES, Prefs.base(this), Prefs.KEY_BASE, true)
        addSlider(content, "Corner gradient", 0f, 100f, Prefs.gradient(this).toFloat(), { "${it.toInt()}%" },
            Prefs.PREVIEW_OPEN, { edit().putInt(Prefs.KEY_GRADIENT, it.toInt()).apply(); previews.forEach { p -> p.gradient = it.toInt() } })
        addSwitch(content, "Border", Prefs.rim(this)) { edit().putBoolean(Prefs.KEY_RIM, it).apply() }
        addSwitch(content, "Glow", Prefs.glow(this)) { edit().putBoolean(Prefs.KEY_GLOW, it).apply() }
        addPicker(content, IdlePreview.FILL, Prefs.FILLS, Prefs.fill(this), Prefs.KEY_FILL)
        addSlider(content, "Flare", 0f, Prefs.FLARE_MAX, Prefs.flareDp(this), { "${it.toInt()} dp" },
            Prefs.PREVIEW_IDLE, { edit().putFloat(Prefs.KEY_FLARE, it).apply() })
        addPicker(content, IdlePreview.IDLE, Prefs.IDLE_STYLES, Prefs.idleStyle(this), Prefs.KEY_IDLE_STYLE)
        addSwitch(content, "Music visualizer", Prefs.visualizer(this)) { edit().putBoolean(Prefs.KEY_VISUALIZER, it).apply() }
        addPicker(content, IdlePreview.VIZ, Prefs.VIZ_STYLES, Prefs.vizStyle(this), Prefs.KEY_VIZ_STYLE)
        val bounceBox = arrayOfNulls<View>(1)
        addSwitch(content, "Beat reaction", Prefs.beat(this)) {
            edit().putBoolean(Prefs.KEY_BEAT, it).apply()
            bounceBox[0]?.visibility = if (it) View.VISIBLE else View.GONE
        }
        bounceBox[0] = addSlider(content, "Beat bounce", 0f, 100f, Prefs.beatAmount(this).toFloat(), { "${it.toInt()}%" },
            Prefs.PREVIEW_IDLE, { edit().putInt(Prefs.KEY_BEAT_AMOUNT, it.toInt()).apply() })
        bounceBox[0]?.visibility = if (Prefs.beat(this)) View.VISIBLE else View.GONE
        addSlider(content, "Opacity", Prefs.OPACITY_MIN.toFloat(), 100f, Prefs.opacityPct(this).toFloat(), { "${it.toInt()}%" },
            Prefs.PREVIEW_OPEN, { edit().putInt(Prefs.KEY_OPACITY, it.toInt()).apply() })

        // FEEL
        header(content, "FEEL")
        addSlider(content, "Speed", 0f, 100f, Prefs.speed(this).toFloat(), { "${it.toInt()}%" },
            null, { edit().putInt(Prefs.KEY_SPEED, it.toInt()).apply() })
        addSlider(content, "Bounce", 0f, 100f, Prefs.bounce(this).toFloat(), { "${it.toInt()}%" },
            null, { edit().putInt(Prefs.KEY_BOUNCE, it.toInt()).apply() })
        addSlider(content, "Swipe sensitivity", 0f, 100f, Prefs.swipeSens(this).toFloat(), { "${it.toInt()}%" },
            null, { edit().putInt(Prefs.KEY_SWIPE_SENS, it.toInt()).apply() })
        addSwitch(content, "Haptics", Prefs.haptics(this)) { edit().putBoolean(Prefs.KEY_HAPTICS, it).apply() }
        sep(content)
        content.addView(text("Reset looks", 15f, red, Typeface.DEFAULT).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            minimumHeight = dp(44)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            background = ripple()
            setOnClickListener {
                Prefs.resetLooks(this@MainActivity)
                val y = scroll.scrollY
                val s = build()
                showRoot(s)
                s.post { s.scrollTo(0, y) }
                buildApps()
                refreshSetup()
            }
        })

        // APPS
        header(content, "APPS")
        appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(appsBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // SETUP (last)
        setupCount = text("", 11f, grey, Typeface.DEFAULT_BOLD)
        header(content, "SETUP", setupCount)
        setupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(setupBox, matchWrap())
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(24)))

        scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }
        return scroll
    }

    override fun onPause() {
        super.onPause()
        if (hookMode) return
        edit().putString(Prefs.KEY_PREVIEW, Prefs.PREVIEW_NONE).apply()
    }

    override fun onResume() {
        super.onResume()
        if (hookMode) return
        refreshSetup()
        buildApps()
        refreshStatus()
        scroll.postDelayed({ refreshStatus() }, 1500L) // the service may still be binding
    }

    /** One short row when the overlay is switched on but the service is not running (e.g. stopped by battery saving). */
    private fun refreshStatus() {
        statusRow.visibility = if (isOverlayServiceEnabled() && !EdgeService.connected) View.VISIBLE else View.GONE
    }

    private fun perms(): List<Perm> = listOf(
        Perm("Overlay service", { isOverlayServiceEnabled() }, { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) }),
        Perm("Media access", { isMediaListenerEnabled() }, { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }),
        Perm("Do Not Disturb access", { isDndGranted() }, { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }),
        Perm("Modify system settings", { Settings.System.canWrite(this) },
            { Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")) }),
        Perm("Assistant corner swipe off", { !isAssistSwipeOn() }, { Intent(Settings.ACTION_SETTINGS) }),
        Perm("Microphone", { granted("android.permission.RECORD_AUDIO") }, { appDetails() }),
        Perm("Usage access", { RecentApps.hasAccess(this) }, { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) }),
        Perm("Secure settings", { granted("android.permission.WRITE_SECURE_SETTINGS") }, { appDetails() })
    )

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    private fun refreshSetup() {
        setupBox.removeAllViews()
        val all = perms()
        val missing = all.filter { p -> !(try { p.ok() } catch (e: Exception) { false }) }
        val accent = chrome
        if (missing.isEmpty()) {
            setupCount.text = ""
            val row = simpleRow("Setup", "Done", Color.parseColor("#30D158")) {
                showAll = !showAll
                refreshSetup()
            }
            setupBox.addView(row, matchWrap())
            if (showAll) {
                for (p in all) {
                    setupBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
                    setupBox.addView(simpleRow(p.label, "On", white) { open(p.intent) }, matchWrap())
                }
            }
            return
        }
        setupCount.text = "${missing.size} missing"
        for ((i, p) in missing.withIndex()) {
            if (i > 0) setupBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            setupBox.addView(simpleRow(p.label, "Fix", accent) { open(p.intent) }, matchWrap())
        }
    }

    private fun open(intent: () -> Intent) {
        try {
            startActivity(intent())
        } catch (e: Exception) {
        }
    }

    private fun simpleRow(label: String, state: String, stateColor: Int, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            isClickable = true
            background = ripple()
            addView(text(label, 15f, white, Typeface.DEFAULT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(state, 14f, stateColor, Typeface.DEFAULT))
            setOnClickListener { onClick() }
        }

    private fun buildApps() {
        appsBox.removeAllViews()
        val pm = packageManager
        val list = Prefs.apps(this).toMutableList()
        val shown = ArrayList<ComponentName>()
        for (cn in list) {
            val info = try {
                pm.getActivityInfo(cn, 0)
            } catch (e: Exception) {
                continue
            }
            shown.add(cn)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(16), 0, dp(4), 0)
            }
            val icon = ImageView(this).apply {
                try {
                    setImageDrawable(info.loadIcon(pm))
                } catch (e: Exception) {
                }
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
            val name = text(info.loadLabel(pm).toString(), 15f, white, Typeface.DEFAULT).apply {
                setPadding(dp(12), 0, 0, 0)
            }
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // removal only through this explicit 44 dp icon, never by tapping the row
            val rm = ImageView(this).apply {
                setImageResource(R.drawable.ic_x)
                setColorFilter(grey)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(11), dp(11), dp(11), dp(11))
                isClickable = true
                background = ripple()
                contentDescription = "Remove"
                setOnClickListener {
                    shown.remove(cn)
                    Prefs.setApps(this@MainActivity, shown)
                    buildApps()
                }
            }
            row.addView(rm, LinearLayout.LayoutParams(dp(44), dp(44)))
            if (appsBox.childCount > 0) appsBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            appsBox.addView(row, matchWrap())
        }
        if (shown.size < Prefs.MAX_APPS) {
            val add = text("Add app", 15f, red, Typeface.DEFAULT).apply {
                minimumHeight = dp(44)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(6), dp(16), dp(6))
                isClickable = true
                background = ripple()
                setOnClickListener {
                    // persist the cleaned list so removed packages do not linger
                    Prefs.setApps(this@MainActivity, shown)
                    startActivity(Intent(this@MainActivity, AppPickerActivity::class.java))
                }
            }
            if (appsBox.childCount > 0) appsBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            appsBox.addView(add, matchWrap())
        }
    }

    private fun ripple() = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, ColorDrawable(Color.WHITE))

    private fun line(): View = View(this).apply { setBackgroundColor(divider) }

    private fun text(s: String, sp: Float, color: Int, face: Typeface): TextView = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = face
    }

    private fun header(parent: LinearLayout, s: String, right: TextView? = null) {
        firstInSection = true
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(4))
        }
        row.addView(text(s.uppercase(), 11f, grey, Typeface.DEFAULT_BOLD).apply { letterSpacing = 0.1f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (right != null) row.addView(right)
        parent.addView(row, matchWrap())
    }

    /** Divider between rows inside a section (not before the first). */
    private fun sep(parent: LinearLayout) {
        if (!firstInSection) parent.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        firstInSection = false
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    // sliders and toggles are grey chrome; only the look previews follow the overlay accent
    private fun applyAccent(c: Int) {
        previews.forEach { it.accent = c }
    }

    private fun addSlider(
        parent: LinearLayout, label: String, min: Float, max: Float, value: Float,
        fmt: (Float) -> String, preview: String?, save: (Float) -> Unit
    ): View {
        sep(parent)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(box, matchWrap())
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val valueView = text(fmt(value), 14f, grey, Typeface.DEFAULT)
        head.addView(text(label, 15f, white, Typeface.DEFAULT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(valueView)
        box.addView(head, matchWrap())

        val s = FlatSlider(this).apply {
            this.min = min
            this.max = max
            this.value = value
            accent = chrome
            setPadding(dp(16), 0, dp(16), 0)
            onStart = { if (preview != null) edit().putString(Prefs.KEY_PREVIEW, preview).apply() }
            onChange = { v -> valueView.text = fmt(v); save(v) }
            onStop = { if (preview != null) edit().putString(Prefs.KEY_PREVIEW, Prefs.PREVIEW_NONE).apply() }
        }
        sliders.add(s)
        box.addView(s, matchWrap())
        return box
    }

    private fun addSwitch(parent: LinearLayout, label: String, on: Boolean, save: (Boolean) -> Unit) {
        sep(parent)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        row.addView(text(label, 15f, white, Typeface.DEFAULT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val t = FlatToggle(this).apply {
            accent = chrome
            checked = on
            onToggle = save
        }
        toggles.add(t)
        row.addView(t)
        parent.addView(row, matchWrap())
    }

    private fun swatch(color: Int, selected: Boolean, outlined: Boolean): android.graphics.drawable.Drawable {
        val g = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            when {
                selected -> setStroke(dp(2), Color.parseColor("#E5E5EA"))
                outlined -> setStroke(dp(1), Color.parseColor("#48484A"))
                else -> setStroke(0, Color.TRANSPARENT)
            }
        }
        // 28 dp dot centred in a 44 dp cell
        return android.graphics.drawable.LayerDrawable(arrayOf<android.graphics.drawable.Drawable>(g)).apply {
            setLayerSize(0, dp(28), dp(28))
            setLayerGravity(0, Gravity.CENTER)
        }
    }

    /** A row of colour cells (each at least 44 dp wide and tall); the choice is stored under [key]. */
    private fun addColorRow(parent: LinearLayout, colors: IntArray, cur: Int, key: String, outlined: Boolean, onPick: (Int) -> Unit = {}) {
        sep(parent)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        val cells = ArrayList<View>()
        fun mark(sel: Int) {
            for ((i, v) in cells.withIndex()) v.background = swatch(colors[i], colors[i] == sel, outlined)
        }
        for (c in colors) {
            val v = View(this).apply {
                isClickable = true
                setOnClickListener {
                    edit().putInt(key, c).apply()
                    mark(c)
                    onPick(c)
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            cells.add(v)
            row.addView(v, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        mark(cur or (0xFF shl 24))
        parent.addView(row, matchWrap())
    }

    private fun addSwatches(parent: LinearLayout) {
        addColorRow(parent, Prefs.ACCENTS, Prefs.accent(this) or (0xFF shl 24), Prefs.KEY_ACCENT, false) { applyAccent(it) }
    }

    /** Mini bean previews (no labels) in rows of cells at least 44 dp square; the selected one gets a grey ring. */
    private fun addPicker(parent: LinearLayout, kind: Int, ids: List<String>, cur: String, key: String) {
        sep(parent)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        val availDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density - 32f
        val gap = 4f
        val maxPer = ((availDp + gap) / (44f + gap)).toInt().coerceAtLeast(1)
        val rows = (ids.size + maxPer - 1) / maxPer
        val per = (ids.size + rows - 1) / rows
        val size = ((availDp - gap * (per - 1)) / per).coerceIn(44f, 56f)
        val mine = ArrayList<IdlePreview>()
        var idx = 0
        for (rIdx in 0 until rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            var n = 0
            while (n < per && idx < ids.size) {
                val id = ids[idx]
                val p = IdlePreview(this, id, kind).apply {
                    sizeDp = size
                    accent = Prefs.accent(this@MainActivity)
                    chrome = this@MainActivity.chrome
                    gradient = Prefs.gradient(this@MainActivity)
                    selectedStyle = id == cur
                    isClickable = true
                    setOnClickListener {
                        edit().putString(key, id).apply()
                        mine.forEach { it.selectedStyle = it === this }
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                mine.add(p)
                previews.add(p)
                if (n > 0) row.addView(View(this), LinearLayout.LayoutParams(dp(gap.toInt()), 1))
                row.addView(p)
                idx++
                n++
            }
            box.addView(row, matchWrap())
        }
        parent.addView(box, matchWrap())
    }

    // ---------------------------------------------------------------- windows list

    private var winEntries = ArrayList<WindowsConfig.Entry>()
    private val winRows = ArrayList<View>()
    private lateinit var winBox: LinearLayout

    private fun addWindows(parent: LinearLayout) {
        winEntries = ArrayList(WindowsConfig.load(this))
        winBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(winBox, matchWrap())
        buildWindowRows()
    }

    private fun buildWindowRows() {
        winBox.removeAllViews()
        winRows.clear()
        val rowH = dp(48)
        val accent = chrome
        for (e in winEntries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(16), 0)
                tag = e.id
            }
            val grip = ImageView(this).apply {
                setImageResource(R.drawable.ic_grip)
                setColorFilter(grey)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(10), dp(12), dp(10), dp(12))
                contentDescription = "Reorder"
            }
            row.addView(grip, LinearLayout.LayoutParams(dp(44), rowH))
            val icon = ImageView(this).apply {
                setImageResource(WindowsConfig.icon(e.id))
                setColorFilter(if (e.on) white else grey)
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
            val label = text(WindowsConfig.label(e.id), 15f, if (e.on) white else grey, Typeface.DEFAULT).apply {
                setPadding(dp(14), 0, 0, 0)
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val t = FlatToggle(this).apply {
                this.accent = accent
                checked = e.on
                onToggle = { on ->
                    val idx = winEntries.indexOfFirst { it.id == e.id }
                    if (idx >= 0) winEntries[idx] = WindowsConfig.Entry(e.id, on)
                    icon.setColorFilter(if (on) white else grey)
                    label.setTextColor(if (on) white else grey)
                    WindowsConfig.save(this@MainActivity, winEntries)
                }
            }
            toggles.add(t)
            row.addView(t)
            attachDrag(grip, row)
            winBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH))
            winRows.add(row)
        }
    }

    /** Reordering starts on the grip handle only (so the toggle and the row never compete with it). */
    private fun attachDrag(handle: View, row: View) {
        val rowH = dp(48)
        var orig = 0
        var downY = 0f
        var target = 0
        handle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    orig = winRows.indexOf(row)
                    if (orig < 0) return@setOnTouchListener false
                    target = orig
                    downY = e.rawY
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    row.parent?.requestDisallowInterceptTouchEvent(true)
                    row.setBackgroundColor(divider)
                    row.elevation = dp(2).toFloat()
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val last = winRows.size - 1
                    val dy = (e.rawY - downY).coerceIn(-orig * rowH.toFloat(), (last - orig) * rowH.toFloat())
                    row.translationY = dy
                    val t = Math.round(orig + dy / rowH).coerceIn(0, last)
                    if (t != target) {
                        target = t
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    }
                    for ((i, r) in winRows.withIndex()) {
                        if (r === row) continue
                        r.translationY = when {
                            i in (orig + 1)..target -> -rowH.toFloat()
                            i in target until orig -> rowH.toFloat()
                            else -> 0f
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (target != orig) {
                        val moved = winEntries.removeAt(orig)
                        winEntries.add(target, moved)
                        WindowsConfig.save(this, winEntries)
                    }
                    buildWindowRows()
                    true
                }
                else -> true
            }
        }
    }


    private fun isOverlayServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val cn = ComponentName(this, EdgeService::class.java)
        return enabled.contains(cn.flattenToString()) || enabled.contains(cn.flattenToShortString())
    }

    private fun isMediaListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(packageName)
    }

    private fun isDndGranted(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        return nm.isNotificationPolicyAccessGranted
    }

    private fun isAssistSwipeOn(): Boolean =
        Settings.Secure.getInt(contentResolver, "navigation_google_assist", 0) != 0

    private fun setWhiteWallpaper() {
        try {
            val bmp = android.graphics.Bitmap.createBitmap(108, 240, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            android.app.WallpaperManager.getInstance(this)
                .setBitmap(bmp, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
            bmp.recycle()
        } catch (e: Exception) {
            android.util.Log.w("DynamicEdge", "wallpaper failed", e)
        }
    }
}
package com.dynedge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/** Knobless flat slider: 6 dp rounded track with an accent fill. Drag or tap anywhere on it. */
class FlatSlider(ctx: Context) : View(ctx) {
    var min = 0f
    var max = 100f
    var accent = Color.WHITE
        set(v) { field = v; invalidate() }
    var onStart: () -> Unit = {}
    var onChange: (Float) -> Unit = {}
    var onStop: () -> Unit = {}

    var value = 0f
        set(v) { field = v.coerceIn(min, max); invalidate() }

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2C2C2E") }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var dragging = false

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (44 * d + 0.5f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val th = 6 * d
        val cy = height / 2f
        val l = paddingLeft.toFloat()
        val r = (width - paddingRight).toFloat()
        rect.set(l, cy - th / 2, r, cy + th / 2)
        c.drawRoundRect(rect, th / 2, th / 2, track)
        val f = if (max > min) (value - min) / (max - min) else 0f
        val fr = l + (r - l) * f
        fill.color = accent
        rect.set(l, cy - th / 2, maxOf(fr, l + th), cy + th / 2)
        c.drawRoundRect(rect, th / 2, th / 2, fill)
    }

    private fun setFromX(x: Float) {
        val l = paddingLeft.toFloat()
        val r = (width - paddingRight).toFloat()
        val f = ((x - l) / (r - l)).coerceIn(0f, 1f)
        val nv = Math.round(min + (max - min) * f).toFloat()
        if (nv != value) {
            value = nv
            onChange(nv)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                onStart()
                setFromX(e.x)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) setFromX(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                if (e.actionMasked == MotionEvent.ACTION_UP) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                onStop()
            }
        }
        return true
    }
}

/** Small flat on/off toggle: rounded track, round thumb. */
class FlatToggle(ctx: Context) : View(ctx) {
    var accent = Color.WHITE
        set(v) { field = v; invalidate() }
    var checked = false
        set(v) { field = v; invalidate() }
    var onToggle: (Boolean) -> Unit = {}

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isClickable = true
        setOnClickListener {
            checked = !checked
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            onToggle(checked)
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension((46 * d).toInt(), (44 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        // 28 dp visible switch centred in the 44 dp touch cell
        val h = 28 * d
        val top = (height - h) / 2f
        p.color = if (checked) accent else Color.parseColor("#3A3A3C")
        rect.set(0f, top, width.toFloat(), top + h)
        c.drawRoundRect(rect, h / 2, h / 2, p)
        p.color = if (checked) Color.BLACK else Color.parseColor("#8E8E93")
        val r = h / 2 - 4 * d
        val cx = if (checked) width - h / 2 else h / 2
        c.drawCircle(cx, top + h / 2, r, p)
    }
}


/**
 * Mini bean preview (quarter circle, tip at the bottom-right) of one idle design, fill style or visualizer
 * style. The decoration is drawn by [Looks], the same code the widget uses.
 */
class IdlePreview(ctx: Context, private val style: String, private val kind: Int = IDLE) : View(ctx) {
    companion object {
        const val IDLE = 0
        const val FILL = 1
        const val VIZ = 2
    }

    var accent = Color.WHITE
        set(v) { field = v; invalidate() }
    var gradient = 60
        set(v) { field = v; invalidate() }
    var chrome = Color.GRAY
        set(v) { field = v; invalidate() }
    var selectedStyle = false
        set(v) { field = v; invalidate() }
    var sizeDp = 44f
        set(v) { field = v; requestLayout() }

    private val d = resources.displayMetrics.density
    private val path = Path()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val looks = Looks()
    private val levels = floatArrayOf(0.9f, 0.55f, 0.75f, 0.4f)
    private var gradShader: Shader? = null
    private var gradKey = 0L

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val s = (sizeDp * d + 0.5f).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = w * 0.86f
        val x0 = w - s
        val y0 = h - s
        path.rewind()
        path.moveTo(x0, h)
        path.cubicTo(x0, h - s * 0.5523f, w - s * 0.5523f, y0, w, y0)
        path.lineTo(w, h)
        path.close()
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Color.BLACK
        c.drawPath(path, p)

        val fillId = if (kind == FILL) style else "gradient"
        if (fillId == "gradient") {
            val a = (gradient * 2.2f).toInt().coerceIn(0, 255)
            val key = (a.toLong() shl 40) or (accent.toLong() and 0xFFFFFFFFL) or (s.toLong() shl 48)
            if (gradShader == null || key != gradKey) {
                gradKey = key
                val warm = Color.argb(a, Color.red(accent), Color.green(accent), Color.blue(accent))
                gradShader = RadialGradient(w, h, s, intArrayOf(warm, Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            }
            p.shader = gradShader
            c.drawPath(path, p)
            p.shader = null
        }
        looks.accent = accent
        val k = s / 48f
        if (kind == FILL && fillId == "dots") {
            c.save()
            c.clipPath(path)
            c.translate(w, h)
            looks.drawPattern(c, fillId, s * 1.05f, k, 3.2f, 0f)
            c.restore()
        }

        // outline: accent ring when selected
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.BUTT
        p.strokeWidth = if (selectedStyle) 2f * d else 1f * d
        p.color = if (selectedStyle) chrome else Color.parseColor("#3A3A3C")
        c.drawPath(path, p)

        c.save()
        c.translate(w, h)
        when (kind) {
            IDLE -> looks.drawIdle(c, style, s, 1f, 0f)
            VIZ -> looks.drawViz(c, style, s, false, levels, 1f, k)
        }
        c.restore()
    }
}
27:import android.widget.TextView
37:    private lateinit var setupCount: TextView
38:    private val sliders = ArrayList<FlatSlider>()
82:                    k == Prefs.KEY_IDLE_DP || k == Prefs.KEY_OPEN_DP || k == Prefs.KEY_FLARE ->
93:        if (pv != null) e.putString(Prefs.KEY_PREVIEW, if (pv == "idle" || pv == "open") pv else Prefs.PREVIEW_NONE)
120:        window.statusBarColor = Color.BLACK
121:        window.navigationBarColor = Color.BLACK
122:        window.setDecorFitsSystemWindows(false)
127:        sliders.clear(); toggles.clear(); previews.clear()
130:        content.addView(text("Dynamic Edge", 22f, white, Typeface.DEFAULT_BOLD).apply {
131:            setPadding(dp(16), dp(20), dp(16), dp(0))
138:            setPadding(dp(16), dp(6), dp(16), dp(6))
140:            addView(text("Overlay stopped", 15f, white, Typeface.DEFAULT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
141:            addView(text("Battery", 14f, grey, Typeface.DEFAULT).apply {
150:        content.addView(statusRow, matchWrap())
152:        // SIZE (first, so the live preview does not cover the slider being dragged)
153:        header(content, "SIZE")
154:        addSlider(content, "Corner size", Prefs.IDLE_MIN, Prefs.IDLE_MAX, Prefs.idleDp(this), { "${it.toInt()} dp" },
156:        addSlider(content, "Open size", Prefs.OPEN_MIN, Prefs.OPEN_MAX, Prefs.openDp(this), { "${it.toInt()} dp" },
160:        header(content, "WINDOWS")
161:        addWindows(content)
163:        // EDGE (layout editor, clipboard history, live activities)
164:        header(content, "EDGE")
165:        content.addView(simpleRow("Quick layout", ">", grey) {
169:        content.addView(simpleRow("Clipboard", ">", grey) {
173:        addSwitch(content, "Live activities", Prefs.live(this)) { edit().putBoolean(Prefs.KEY_LIVE, it).apply() }
176:        header(content, "LOOKS")
177:        addSwatches(content)
178:        addColorRow(content, Prefs.BASES, Prefs.base(this), Prefs.KEY_BASE, true)
179:        addSlider(content, "Corner gradient", 0f, 100f, Prefs.gradient(this).toFloat(), { "${it.toInt()}%" },
180:            Prefs.PREVIEW_OPEN, { edit().putInt(Prefs.KEY_GRADIENT, it.toInt()).apply(); previews.forEach { p -> p.gradient = it.toInt() } })
181:        addSwitch(content, "Border", Prefs.rim(this)) { edit().putBoolean(Prefs.KEY_RIM, it).apply() }
182:        addSwitch(content, "Glow", Prefs.glow(this)) { edit().putBoolean(Prefs.KEY_GLOW, it).apply() }
183:        addPicker(content, IdlePreview.FILL, Prefs.FILLS, Prefs.fill(this), Prefs.KEY_FILL)
184:        addSlider(content, "Flare", 0f, Prefs.FLARE_MAX, Prefs.flareDp(this), { "${it.toInt()} dp" },
185:            Prefs.PREVIEW_IDLE, { edit().putFloat(Prefs.KEY_FLARE, it).apply() })
186:        addPicker(content, IdlePreview.IDLE, Prefs.IDLE_STYLES, Prefs.idleStyle(this), Prefs.KEY_IDLE_STYLE)
187:        addSwitch(content, "Music visualizer", Prefs.visualizer(this)) { edit().putBoolean(Prefs.KEY_VISUALIZER, it).apply() }
188:        addPicker(content, IdlePreview.VIZ, Prefs.VIZ_STYLES, Prefs.vizStyle(this), Prefs.KEY_VIZ_STYLE)
190:        addSwitch(content, "Beat reaction", Prefs.beat(this)) {
191:            edit().putBoolean(Prefs.KEY_BEAT, it).apply()
194:        bounceBox[0] = addSlider(content, "Beat bounce", 0f, 100f, Prefs.beatAmount(this).toFloat(), { "${it.toInt()}%" },
195:            Prefs.PREVIEW_IDLE, { edit().putInt(Prefs.KEY_BEAT_AMOUNT, it.toInt()).apply() })
197:        addSlider(content, "Opacity", Prefs.OPACITY_MIN.toFloat(), 100f, Prefs.opacityPct(this).toFloat(), { "${it.toInt()}%" },
198:            Prefs.PREVIEW_OPEN, { edit().putInt(Prefs.KEY_OPACITY, it.toInt()).apply() })
201:        header(content, "FEEL")
202:        addSlider(content, "Speed", 0f, 100f, Prefs.speed(this).toFloat(), { "${it.toInt()}%" },
203:            null, { edit().putInt(Prefs.KEY_SPEED, it.toInt()).apply() })
204:        addSlider(content, "Bounce", 0f, 100f, Prefs.bounce(this).toFloat(), { "${it.toInt()}%" },
205:            null, { edit().putInt(Prefs.KEY_BOUNCE, it.toInt()).apply() })
206:        addSlider(content, "Swipe sensitivity", 0f, 100f, Prefs.swipeSens(this).toFloat(), { "${it.toInt()}%" },
207:            null, { edit().putInt(Prefs.KEY_SWIPE_SENS, it.toInt()).apply() })
208:        addSwitch(content, "Haptics", Prefs.haptics(this)) { edit().putBoolean(Prefs.KEY_HAPTICS, it).apply() }
210:        content.addView(text("Reset looks", 15f, red, Typeface.DEFAULT).apply {
211:            setPadding(dp(16), dp(8), dp(16), dp(8))
217:                Prefs.resetLooks(this@MainActivity)
228:        header(content, "APPS")
230:        content.addView(appsBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
234:        header(content, "SETUP", setupCount)
236:        content.addView(setupBox, matchWrap())
237:        content.addView(View(this), LinearLayout.LayoutParams(1, dp(24)))
242:            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
245:                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
255:        edit().putString(Prefs.KEY_PREVIEW, Prefs.PREVIEW_NONE).apply()
299:            setupBox.addView(row, matchWrap())
302:                    setupBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
303:                    setupBox.addView(simpleRow(p.label, "On", white) { open(p.intent) }, matchWrap())
310:            if (i > 0) setupBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
311:            setupBox.addView(simpleRow(p.label, "Fix", accent) { open(p.intent) }, matchWrap())
327:            setPadding(dp(16), dp(6), dp(16), dp(6))
330:            addView(text(label, 15f, white, Typeface.DEFAULT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
331:            addView(text(state, 14f, stateColor, Typeface.DEFAULT))
346:            shown.add(cn)
351:                setPadding(dp(16), 0, dp(4), 0)
359:            row.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
361:                setPadding(dp(12), 0, 0, 0)
363:            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
369:                setPadding(dp(11), dp(11), dp(11), dp(11))
379:            row.addView(rm, LinearLayout.LayoutParams(dp(44), dp(44)))
380:            if (appsBox.childCount > 0) appsBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
381:            appsBox.addView(row, matchWrap())
384:            val add = text("Add app", 15f, red, Typeface.DEFAULT).apply {
387:                setPadding(dp(16), dp(6), dp(16), dp(6))
396:            if (appsBox.childCount > 0) appsBox.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
397:            appsBox.addView(add, matchWrap())
405:    private fun text(s: String, sp: Float, color: Int, face: Typeface): TextView = TextView(this).apply {
412:    private fun header(parent: LinearLayout, s: String, right: TextView? = null) {
417:            setPadding(dp(16), dp(20), dp(16), dp(4))
419:        row.addView(text(s.uppercase(), 11f, grey, Typeface.DEFAULT_BOLD).apply { letterSpacing = 0.1f },
421:        if (right != null) row.addView(right)
422:        parent.addView(row, matchWrap())
425:    /** Divider between rows inside a section (not before the first). */
427:        if (!firstInSection) parent.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
433:    // sliders and toggles are grey chrome; only the look previews follow the overlay accent
438:    private fun addSlider(
444:        parent.addView(box, matchWrap())
447:            setPadding(dp(16), dp(8), dp(16), 0)
450:        head.addView(text(label, 15f, white, Typeface.DEFAULT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
451:        head.addView(valueView)
452:        box.addView(head, matchWrap())
459:            setPadding(dp(16), 0, dp(16), 0)
460:            onStart = { if (preview != null) edit().putString(Prefs.KEY_PREVIEW, preview).apply() }
462:            onStop = { if (preview != null) edit().putString(Prefs.KEY_PREVIEW, Prefs.PREVIEW_NONE).apply() }
464:        sliders.add(s)
465:        box.addView(s, matchWrap())
469:    private fun addSwitch(parent: LinearLayout, label: String, on: Boolean, save: (Boolean) -> Unit) {
475:            setPadding(dp(16), dp(4), dp(16), dp(4))
477:        row.addView(text(label, 15f, white, Typeface.DEFAULT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
483:        toggles.add(t)
484:        row.addView(t)
485:        parent.addView(row, matchWrap())
506:    private fun addColorRow(parent: LinearLayout, colors: IntArray, cur: Int, key: String, outlined: Boolean, onPick: (Int) -> Unit = {}) {
511:            setPadding(dp(4), 0, dp(4), 0)
527:            cells.add(v)
528:            row.addView(v, LinearLayout.LayoutParams(0, dp(44), 1f))
531:        parent.addView(row, matchWrap())
534:    private fun addSwatches(parent: LinearLayout) {
535:        addColorRow(parent, Prefs.ACCENTS, Prefs.accent(this) or (0xFF shl 24), Prefs.KEY_ACCENT, false) { applyAccent(it) }
539:    private fun addPicker(parent: LinearLayout, kind: Int, ids: List<String>, cur: String, key: String) {
543:            setPadding(dp(16), dp(4), dp(16), dp(4))
574:                mine.add(p)
575:                previews.add(p)
576:                if (n > 0) row.addView(View(this), LinearLayout.LayoutParams(dp(gap.toInt()), 1))
577:                row.addView(p)
581:            box.addView(row, matchWrap())
583:        parent.addView(box, matchWrap())
586:    // ---------------------------------------------------------------- windows list
592:    private fun addWindows(parent: LinearLayout) {
595:        parent.addView(winBox, matchWrap())
608:                setPadding(dp(4), 0, dp(16), 0)
615:                setPadding(dp(10), dp(12), dp(10), dp(12))
618:            row.addView(grip, LinearLayout.LayoutParams(dp(44), rowH))
623:            row.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
625:                setPadding(dp(14), 0, 0, 0)
627:            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
639:            toggles.add(t)
640:            row.addView(t)
642:            winBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH))
643:            winRows.add(row)
689:                        winEntries.add(target, moved)

codex
## 1. Information architecture

Use a **home list of categories that open focused subpages**. The current screen stacks unrelated controls into one long scroll, so it is hard to see what belongs together. Keep a small live preview at the top of the home page, then show these categories as flat, full-width rows with Lucide icons, concise descriptions, and a current-value summary. Each row should be at least 56 dp high.

- **Layout**
  - Corner size and open size
  - Windows: display order and on/off state; keep long-press-and-drag reordering
  - Quick layout editor
- **Appearance**
  - Accent colour for settings controls
  - Edge fill: solid or gradient
  - Edge solid colour; gradient colours, on/off and strength
  - Rim, glow, flare, opacity
  - Idle design, visualizer and visualizer style
  - Beat reaction and beat bounce
- **Behavior**
  - Speed, bounce, swipe sensitivity, haptics
- **Content**
  - Apps and app picker
  - Clipboard history
  - Live activities
- **Setup**
  - Permission and service rows, with missing requirements first and a completion status

Place **Reset appearance** at the bottom of Appearance, with an undoable confirmation. Keep it separate from the general setup/status area so it cannot be confused with a permission action.

This puts frequent choices on a concise home screen and groups related adjustments together. Use a sticky top bar with back navigation on subpages; avoid sticky category chips, which would consume space and compete with the controls.

## 2. Visual system

Use a neutral dark-grey palette throughout settings; reserve the selected accent for interactive state and emphasis.

| Role | Colour |
|---|---|
| App background | `#202124` |
| Raised surface, such as preview well or picker panel | `#292A2D` |
| Subtle divider | `#3A3B3F` |
| Primary text | `#F4F4F5` |
| Secondary text | `#A1A1AA` |
| Disabled text/control | `#68696E` |
| Accent | User-selected colour, default `#FF4B3E` |

Use accent on selected swatches, active slider fill, enabled toggle tracks, selected navigation, focus rings, and preview highlights. Unselected controls use neutral greys. Check foreground contrast against the chosen accent; use a dark thumb/icon on light accents and a light one on dark accents.

- **Type:** page title 24 sp/medium; section heading 14 sp/medium; row title 16 sp/regular; supporting/value text 13–14 sp.
- **Spacing:** 24 dp page margins; 24 dp between sections; 12 dp between a row label and its control; 8 dp within compact controls.
- **Rows:** 56 dp minimum for settings rows, 48 dp minimum for sliders and swatches; 44 dp minimum hit area for every action. Use full-width flat rows with hairline dividers, no nested cards or boxes.
- **Controls:** 4 dp slider track with a 24 dp thumb and accent fill; standard 48×28 dp switch track centered in a 56 dp row; selectable chips/swatches use an accent outline and checkmark. Avoid tiny icon-only actions; give them a 44 dp hit target and a content description.
- **Icons:** Lucide-style line icons, consistent 24 dp sizing and stroke weight; no emoji.

Keep raised surfaces limited to clear functional areas such as the preview well and picker—not as containers around each setting.

## 3. Edge colour picker

In Appearance, show a compact **Edge fill** selector with **Solid** and **Gradient** choices and a live edge thumbnail. The selected mode reveals its controls.

**Solid mode:** show a large current-colour swatch that opens a full-screen or bottom-sheet picker. Include a hue/saturation/value field, hue strip, numeric HEX input, and recent colours. Provide curated presets below the custom picker. Validate HEX input and update the preview as the user drags or types.

**Gradient mode:** provide **Gradient on/off**, two editable colour stops by default, and an **Add stop** action if the renderer supports multiple stops. Each stop opens the same full picker; presets remain available. Show the gradient direction in a small preview. A **Strength** slider controls gradient intensity, with a visible percentage. Keep solid colour and gradient stops as separate preferences so changing one does not overwrite the other.

Accent colour is a separate setting for the settings UI and edge accents. Label it clearly so it is not mistaken for the edge’s base/fill colour.

## 4. Live preview

Keep a compact, persistent preview at the top of the home page and Appearance page. Draw the actual quarter-circle edge shape with the current fill, rim, flare, glow, opacity and idle design. Put it in a grey preview well so the shape remains visible for dark colours; do not make the preview well black.

Provide **Idle** and **Open** preview states as segmented controls. Selecting either previews that state in place; it must not launch the overlay or cover a control being adjusted. For interactive appearance changes, update the preview immediately and persist the value when the user finishes the gesture. For settings that cannot be represented accurately in a static preview—such as live music response—show a subtle simulated state and label it “Preview.”

## 5. Prioritized implementation plan

1. **Theme and reusable components:** update `MainActivity.kt` theme colours, system bars, page scaffolding, row spacing, and accent propagation. Refactor `FlatSlider.kt` so track, thumb, and touch target match the visual system; apply the same shared styling to toggles and selection controls.
2. **Navigation and grouping:** replace the single long settings stack in `MainActivity.kt` with the category home and subpages. Move each existing preference into the IA above; retain existing preference keys and behavior where possible.
3. **Colour model and picker:** extend `Prefs.kt` with distinct gradient enablement and colour-stop preferences, keeping existing edge base and accent preferences distinct. Add a reusable custom picker with HSV/HEX entry and presets; keep swatch selection available as the quick path.
4. **Preview:** extract or adapt the drawing currently used by `IdlePreview` in `FlatSlider.kt` into a shared preview component that reads current values and supports Idle/Open states. Remove preview overlays that can obstruct controls.
5. **Subpages and polish:** restyle `QuickEditorActivity.kt`, `AppPickerActivity`, and `ClipboardActivity` to use the same grey surfaces, accent, typography, row heights, and Lucide icons. Keep app removal and clipboard actions at accessible hit sizes.
6. **Settings mapping and cleanup:** organize window controls and setup rows; put Reset appearance in Appearance and ensure its reset scope is clear. Review all labels for brevity and consistent terminology.

This is a read-only design specification; no files were changed.
tokens used
40,398
## 1. Information architecture

Use a **home list of categories that open focused subpages**. The current screen stacks unrelated controls into one long scroll, so it is hard to see what belongs together. Keep a small live preview at the top of the home page, then show these categories as flat, full-width rows with Lucide icons, concise descriptions, and a current-value summary. Each row should be at least 56 dp high.

- **Layout**
  - Corner size and open size
  - Windows: display order and on/off state; keep long-press-and-drag reordering
  - Quick layout editor
- **Appearance**
  - Accent colour for settings controls
  - Edge fill: solid or gradient
  - Edge solid colour; gradient colours, on/off and strength
  - Rim, glow, flare, opacity
  - Idle design, visualizer and visualizer style
  - Beat reaction and beat bounce
- **Behavior**
  - Speed, bounce, swipe sensitivity, haptics
- **Content**
  - Apps and app picker
  - Clipboard history
  - Live activities
- **Setup**
  - Permission and service rows, with missing requirements first and a completion status

Place **Reset appearance** at the bottom of Appearance, with an undoable confirmation. Keep it separate from the general setup/status area so it cannot be confused with a permission action.

This puts frequent choices on a concise home screen and groups related adjustments together. Use a sticky top bar with back navigation on subpages; avoid sticky category chips, which would consume space and compete with the controls.

## 2. Visual system

Use a neutral dark-grey palette throughout settings; reserve the selected accent for interactive state and emphasis.

| Role | Colour |
|---|---|
| App background | `#202124` |
| Raised surface, such as preview well or picker panel | `#292A2D` |
| Subtle divider | `#3A3B3F` |
| Primary text | `#F4F4F5` |
| Secondary text | `#A1A1AA` |
| Disabled text/control | `#68696E` |
| Accent | User-selected colour, default `#FF4B3E` |

Use accent on selected swatches, active slider fill, enabled toggle tracks, selected navigation, focus rings, and preview highlights. Unselected controls use neutral greys. Check foreground contrast against the chosen accent; use a dark thumb/icon on light accents and a light one on dark accents.

- **Type:** page title 24 sp/medium; section heading 14 sp/medium; row title 16 sp/regular; supporting/value text 13–14 sp.
- **Spacing:** 24 dp page margins; 24 dp between sections; 12 dp between a row label and its control; 8 dp within compact controls.
- **Rows:** 56 dp minimum for settings rows, 48 dp minimum for sliders and swatches; 44 dp minimum hit area for every action. Use full-width flat rows with hairline dividers, no nested cards or boxes.
- **Controls:** 4 dp slider track with a 24 dp thumb and accent fill; standard 48×28 dp switch track centered in a 56 dp row; selectable chips/swatches use an accent outline and checkmark. Avoid tiny icon-only actions; give them a 44 dp hit target and a content description.
- **Icons:** Lucide-style line icons, consistent 24 dp sizing and stroke weight; no emoji.

Keep raised surfaces limited to clear functional areas such as the preview well and picker—not as containers around each setting.

## 3. Edge colour picker

In Appearance, show a compact **Edge fill** selector with **Solid** and **Gradient** choices and a live edge thumbnail. The selected mode reveals its controls.

**Solid mode:** show a large current-colour swatch that opens a full-screen or bottom-sheet picker. Include a hue/saturation/value field, hue strip, numeric HEX input, and recent colours. Provide curated presets below the custom picker. Validate HEX input and update the preview as the user drags or types.

**Gradient mode:** provide **Gradient on/off**, two editable colour stops by default, and an **Add stop** action if the renderer supports multiple stops. Each stop opens the same full picker; presets remain available. Show the gradient direction in a small preview. A **Strength** slider controls gradient intensity, with a visible percentage. Keep solid colour and gradient stops as separate preferences so changing one does not overwrite the other.

Accent colour is a separate setting for the settings UI and edge accents. Label it clearly so it is not mistaken for the edge’s base/fill colour.

## 4. Live preview

Keep a compact, persistent preview at the top of the home page and Appearance page. Draw the actual quarter-circle edge shape with the current fill, rim, flare, glow, opacity and idle design. Put it in a grey preview well so the shape remains visible for dark colours; do not make the preview well black.

Provide **Idle** and **Open** preview states as segmented controls. Selecting either previews that state in place; it must not launch the overlay or cover a control being adjusted. For interactive appearance changes, update the preview immediately and persist the value when the user finishes the gesture. For settings that cannot be represented accurately in a static preview—such as live music response—show a subtle simulated state and label it “Preview.”

## 5. Prioritized implementation plan

1. **Theme and reusable components:** update `MainActivity.kt` theme colours, system bars, page scaffolding, row spacing, and accent propagation. Refactor `FlatSlider.kt` so track, thumb, and touch target match the visual system; apply the same shared styling to toggles and selection controls.
2. **Navigation and grouping:** replace the single long settings stack in `MainActivity.kt` with the category home and subpages. Move each existing preference into the IA above; retain existing preference keys and behavior where possible.
3. **Colour model and picker:** extend `Prefs.kt` with distinct gradient enablement and colour-stop preferences, keeping existing edge base and accent preferences distinct. Add a reusable custom picker with HSV/HEX entry and presets; keep swatch selection available as the quick path.
4. **Preview:** extract or adapt the drawing currently used by `IdlePreview` in `FlatSlider.kt` into a shared preview component that reads current values and supports Idle/Open states. Remove preview overlays that can obstruct controls.
5. **Subpages and polish:** restyle `QuickEditorActivity.kt`, `AppPickerActivity`, and `ClipboardActivity` to use the same grey surfaces, accent, typography, row heights, and Lucide icons. Keep app removal and clipboard actions at accessible hit sizes.
6. **Settings mapping and cleanup:** organize window controls and setup rows; put Reset appearance in Appearance and ensure its reset scope is clear. Review all labels for brevity and consistent terminology.

This is a read-only design specification; no files were changed.
