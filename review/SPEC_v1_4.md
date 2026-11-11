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
13. System gesture conflict: the Android back gesture on the right edge must not fire where the idle bean (incl.
    flares) is, nor where the open panel touches the right edge. Use setSystemGestureExclusionRects on the overlay
    for exactly those rects, updated as the shape changes (idle, opening, open). Android caps exclusion at 200 dp of
    height per edge: prioritise the bean and the lowest 200 dp. NOTE: the bottom-edge home/recents swipe cannot be
    excluded by any app API (platform rule); do not pretend otherwise. Mitigate: the bean's touch target may extend
    a few dp upward/left so presses start inside the overlay rather than on the bottom-edge gesture strip.
14. Accidental close while switching windows + paging is hard. Lead observation on device: a horizontal swipe across
    QUICK grabbed the brightness arc instead of paging. Rework gesture classification in EdgeView:
    - Arc sliders capture ONLY if the touch DOWN lands on that arc's band (track radius +/- 14 dp, within its angular
      span) or on its badge; any other down in the panel is a page-swipe candidate even if it later crosses an arc.
    - Page swipe: angular motion about the corner; commit on angle >= 10 deg OR angular fling velocity above a
      threshold (use VelocityTracker); rubber-band at ends; the swipe may start on top of tiles (tap is cancelled
      once movement > touch slop).
    - Close: never from a page swipe. Outside-tap closes only on a clean tap (down+up outside, movement < slop,
      < 300 ms) and not when the down is within 24 dp outside the shape edge (likely a missed swipe). Swipe-to-close
      (if any exists) only for a clearly radial inward fling toward the corner (radial:tangential >= 2.5:1 and
      velocity high). X tap still closes.
15. Arc symmetry: when a window has several concentric arcs/sliders (QUICK two-arc layout, VOLUME, DEVICE, TIMER
    dial), each arc must be symmetric about the 45 deg diagonal: equal angular inset at the bottom end and the right
    end (inset measured as arc length so outer and inner arcs look evenly trimmed), badges/labels mirrored
    consistently. No arc may run closer to one screen edge than the other.
16. APPS window top ribbon: the top of APPS feels empty. Add a small ribbon (one thin arc row near the outer edge,
    above the app grid) of recently used/background apps (UsageStatsManager, last ~2 h, max 5, excluding launcher,
    us and pinned apps already shown; PACKAGE_USAGE_STATS granted via `appops set com.dynedge GET_USAGE_STATS allow`
    in install.ps1, graceful empty state without it). Small icons (~28 dp). Tap = open that app; long-press = stop
    it: ActivityManager.killBackgroundProcesses (KILL_BACKGROUND_PROCESSES permission) and remove from the ribbon,
    with a short haptic. Be honest in code comments: Android only lets us kill background processes, not force-stop.
17. Timer on the idle bean: when a timer started from the TIMER window is running, the closed bean shows it as a
    live activity: a thin accent countdown ring/arc on the bean plus remaining "M:SS" (or minutes only when small),
    ticking once per second only while a timer runs. TimerControl records end time (elapsedRealtime-based, persisted
    in Prefs so it survives restarts); clears at zero (brief pulse) or if cancelled via the clock app notification
    disappearing (NotifFeed/LiveBus if available). Same for other live activities already supported: verify they
    show on the idle bean.
18. Fills: keep only "gradient", "solid", "dots". Remove "grooves", "sunburst", "carbon" (code, prefs list, pickers;
    stored removed values map to "gradient").
19. Idle designs: keep only "none" and "glyph". Remove pill, orbit, slits, halo, meter, grid, eclipse (stored removed
    values map to "glyph"; old v1.3 mapping updated accordingly). While the visualizer is drawing on the bean (setting
    on and media playing), no idle design is drawn - only the visualizer.
20. Visualizers: keep "bars", "spectrum", "rays", "wave". Remove "meter", "ripple", "liquid" (stored removed values map
    to "spectrum"). "bars" must be tilted to follow the circle like the others (radial bars along the bean's arc, not
    vertical).
21. "Beat bounce" slider does not work properly: verify the amount actually scales the idle bean bounce from 0 (none)
    to 100 (+12%), live, with the spring; fix whatever breaks it.
22. Settings app theme: the whole settings app (MainActivity, QuickEditorActivity, AppPickerActivity,
    ClipboardActivity) uses a neutral grey theme - grey toggles, slider tracks/thumbs, selection rings, headers,
    buttons; no accent colour in the settings chrome. Overlay accent unchanged.
23. Base colour: let the user pick the solid base colour of the edge shape (Prefs KEY_BASE, Int colour, default
    black) with a row of swatches (black + a few dark greys/tints, >= 44 dp) in LOOKS. Used by every fill.
24. Gradient off means OFF: when fill is not "gradient" (or gradient strength is 0), the shape is the pure base
    colour plus its pattern - no corner gradient tint and no beat "core" gradient glow. Do not add a new setting
    for this.
25. The light preview backdrop must be drawn by the OVERLAY itself (behind the shape, in the overlay window, only
    while KEY_PREVIEW is idle/open), not by MainActivity, so it is not clipped by the activity bounds / nav bar.
    Remove the MainActivity backdrop.
26. Window swipe is too sensitive: lower default sensitivity and add a "Swipe sensitivity" slider (Prefs
    KEY_SWIPE_SENS 0..100, default 35) in FEEL that scales the commit angle (e.g. 18 deg at 0 .. 6 deg at 100) and
    the fling velocity threshold accordingly. Touch slop for starting a page swipe also grows when sensitivity is low.
27. DEVICE window: add RAM and storage icons (Lucide memory-stick and hard-drive style) at their arc starts; the
    battery percentage and temperature text must be laid out along the arcs/tilted to the circle like the rest of
    the radial UI, and nothing may overlap or clip through other elements.
28. Open/close animation: the edge flares (left end on the bottom edge, top end on the right edge) must stay
    consistent through the whole open/close animation. User sees 3 separate elements stretching independently
    (likely flare fillets, the shape body and the rim/pattern/backdrop scaled with different transforms), so they
    do not line up mid-animation. Every frame must build fill, flares, rim, pattern clip and visualizer from the
    SAME live geometry (no separate scale transform on one layer while another is rebuilt); flare radius
    interpolates smoothly with the open progress; verify by reasoning at progress 0, 0.25, 0.5, 0.75, 1 and during
    spring overshoot.
29. Start lines in the open panel: sliders/arcs and their icons/badges must start on a consistent line (same
    angular inset from the bottom edge, same start angle across concentric arcs, icons aligned on one radial line
    at the arc starts), especially in the DEVICE (system) window. Ties in with request 15 (symmetry) and 27.
30. Quick tile editor: offer EVERY quick settings tile the phone has. Sources: Settings.Secure "sysui_qs_tiles"
    (this vivo: wifi,cell,bt,rotation,flashlight,vibrate_mode,silent_mode,airplane,hotspot,smart_short,controls,night,
    dark,battery,custom(...),monster_mode,location,screen_record,dnd,calculator,split_screen,scanner,game_mode,
    upnp_server,vivo_share) plus all third-party TileServices from PackageManager.queryIntentServices(
    TileService.ACTION_QS_TILE) (label + icon from the service info). Tiles we already implement natively keep the
    native action. Every other tile works through the accessibility service: expand quick settings
    (performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)), find the tile node by its label/content description
    (scroll QS pages if needed), click it, then collapse (GLOBAL_ACTION_BACK) - fast, no visible lingering where
    possible; long-press = open the tile's long-press target the same way. State for those tiles is read from the
    node's checked/stateDescription when the panel was last seen (show neutral if unknown). Be honest in code: it
    depends on the OEM panel and may fail; fail silently with a short haptic. Editor shows them in a flat grouped
    list (native first, then system, then apps), icons Lucide-style for native ones, the app's own tile icon for
    third-party ones; minimal text.
    Addendum (user): the third-party / custom tiles go in their OWN separate "Custom" group in the editor and in
    their own row on the QUICK panel (a dedicated ring/row for custom tiles), never mixed with the native/system
    tiles.
31. TIMER window when a timer is running (same tracked end time as request 17): show the running timer instead of
    the creation dial - a countdown arc (remaining / total) on the dial ring, the large remaining "M:SS" ticking
    1 Hz while the window is visible, a cancel/stop control (opens the clock app's timers via
    TimerControl.openTimers, since the clock app owns the timer) and a "+1 min" chip if practical (start a new
    timer for remaining+60 s only if the clock app API allows replacing; otherwise omit). When it reaches zero or is
    cleared, the window returns to the creation dial.
