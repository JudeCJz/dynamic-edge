# Dynamic Edge v1.3 spec (lead-merged from user requests + Codex/OpenCode reviews + islands.md)

Read first: .agents/skills/dynamic-edge/SKILL.md, review/codex.md, review/opencode.md, review/islands.md (section 3).
Rules: Kotlin, framework only (no AndroidX), minSdk 30, targetSdk 28, no emojis anywhere, no per-frame allocations,
section RenderNode layers re-recorded only when dirty, zero idle work except what is listed, minimal text in UI.

## File ownership
- Agent ENGINE owns: EdgeView.kt, EdgeService.kt, accessibility xml (res/xml/edge_service.xml).
- Agent PLATFORM owns: new files below + MediaListener.kt + QuickToggles.kt + Brightness.kt + Prefs.kt (additions only)
  + AndroidManifest.xml (activities) + new drawables.
- Nobody touches MainActivity.kt (the lead adds the entry rows afterwards).

## Contracts (PLATFORM implements, ENGINE consumes; exact signatures)

```kotlin
package com.dynedge

// ---------------- QuickConfig.kt
object QuickConfig {
    const val KEY = "quick_layout"                 // stored in Prefs.prefs(ctx), JSON string (org.json)
    const val INNER_MAX = 4; const val MIDDLE_MAX = 5; const val ARCS_MAX = 2
    data class Layout(val inner: List<String>, val middle: List<String>, val arcs: List<String>)
    val DEFAULT = Layout(
        inner = listOf("rotate", "assist", "vibrate"),
        middle = listOf("wifi", "bluetooth", "torch", "dnd"),
        arcs = listOf("brightness"))
    fun load(ctx: Context): Layout                 // invalid ids dropped, sizes clamped, falls back to DEFAULT
    fun save(ctx: Context, l: Layout)
    fun reset(ctx: Context)
}

// ---------------- Tiles.kt  (wraps QuickToggles; adds global-action tiles)
class Tiles(ctx: Context, onChange: () -> Unit) {
    var service: android.accessibilityservice.AccessibilityService?  // set by EdgeService, needed for global actions
    fun start(); fun stop()                        // listeners only while open
    fun isToggle(id: String): Boolean              // has on/off state
    fun isOn(id: String): Boolean
    fun isAvailable(id: String): Boolean
    fun iconRes(id: String): Int                   // current-state icon
    fun perform(id: String): Boolean               // true => caller collapses first (activity launch / global action)
    fun openSettings(id: String): Boolean          // long-press
    companion object {
        val ALL: List<String>                       // catalog order for the editor
        fun label(id: String): String               // 1-2 words, editor only
        fun staticIcon(id: String): Int             // icon for editor/catalog
        fun needsCollapseDelay(id: String): Boolean // screenshot: perform ~350 ms after the panel is closed
    }
}
// Catalog ids: wifi bluetooth torch dnd rotate vibrate assist battery_saver caffeine screenshot lock
//              notifications quick_settings recents power_menu
// battery_saver: Settings.Global "low_power" 0/1 (WRITE_SECURE_SETTINGS), fallback ACTION_BATTERY_SAVER_SETTINGS.
// caffeine: Settings.System SCREEN_OFF_TIMEOUT -> 30 min, remembering and restoring the previous value (stored in prefs).
// screenshot/lock/notifications/quick_settings/recents/power_menu: service.performGlobalAction(GLOBAL_ACTION_*).

// ---------------- Arcs.kt  (outer-ring sliders)
class Arcs(ctx: Context, onChange: () -> Unit) {
    fun start(); fun stop()
    fun level(id: String): Float                   // 0..1
    fun setLevel(id: String, f: Float)             // caller throttles to <= 60 Hz while dragging
    fun commit(id: String)                         // on release (final write)
    fun iconRes(id: String): Int
    fun iconActive(id: String): Boolean            // brightness: auto on ; volumes: muted
    fun tapIcon(id: String)                        // brightness: toggle auto ; volume: mute/unmute (remember level)
    companion object { val ALL = listOf("brightness", "media_volume", "ring_volume", "alarm_volume") ; fun label(id: String): String }
}
// Brightness must NOT silently leave auto off: dragging switches to manual (that is expected, like the system shade),
// but the sun icon shows the state clearly and tapping it restores auto. Never reread a stale value right after the
// final write (keep the dragged value as truth for 500 ms).
// Volumes: AudioManager STREAM_MUSIC / STREAM_RING / STREAM_ALARM, setStreamVolume(flags 0), observe via
// ContentObserver on Settings.System.CONTENT_URI (notifyForDescendants) + "android.media.VOLUME_CHANGED_ACTION".

// ---------------- ClipStore.kt
class ClipStore private constructor(ctx: Context) {
    data class Clip(val id: Long, val text: String, val time: Long, val pinned: Boolean)
    fun list(): List<Clip>                         // pinned first (newest first), then unpinned newest first
    fun add(text: String): Boolean                 // trims; ignores blank/duplicate-of-newest; dedupe moves to top;
                                                   // max 60 unpinned; max text 20k chars; returns true if changed
    fun pin(id: Long, pinned: Boolean); fun delete(id: Long); fun clearUnpinned()
    fun addListener(l: () -> Unit); fun removeListener(l: () -> Unit)
    companion object { fun get(ctx: Context): ClipStore }   // process singleton, file filesDir/clips.json, async-safe writes
}

// ---------------- ClipPaste.kt
object ClipPaste {
    /** Reads the current primary clip into the store. Only works while this app has focus (see ENGINE notes). */
    fun capture(ctx: Context): Boolean
    /** Puts text on the clipboard and pastes it into the focused editable field of the active app.
     *  Strategy: setPrimaryClip; find input-focused node across service.windows (findFocus(FOCUS_INPUT)),
     *  must be editable; performAction(ACTION_PASTE); fallback ACTION_SET_TEXT inserting at the selection
     *  (text before selStart + clip + text after selEnd). Returns false if no editable focused field (then the text
     *  is still on the clipboard; caller shows a short "Copied" state). */
    fun paste(service: android.accessibilityservice.AccessibilityService, text: String): Boolean
}

// ---------------- LiveBus.kt (live activities, fed by MediaListener + EdgeService)
data class LiveActivity(
    val key: String, val kind: Kind, val title: String,       // title: very short (timer "4:32", "Downloading", caller name)
    val progress: Float,                                      // 0..1, or -1 for none, or -2 indeterminate
    val chronoBase: Long,                                     // wall-clock ms for chronometer, 0 = none
    val countDown: Boolean,
    val iconRes: Int, val ongoing: Boolean,                   // ongoing = stays until removed; else transient
    val contentIntent: android.app.PendingIntent?,
    val answer: android.app.PendingIntent?, val decline: android.app.PendingIntent?) {
    enum class Kind { CALL, TIMER, PROGRESS, CHARGING, MEDIA }
}
object LiveBus {
    fun put(a: LiveActivity); fun remove(key: String)
    fun top(): LiveActivity?; fun second(): LiveActivity?     // priority CALL > TIMER > PROGRESS > CHARGING > MEDIA, then newest
    var onChange: (() -> Unit)?                               // main thread
}
// MediaListener (NotificationListenerService) classifies notifications (onListenerConnected: scan
// activeNotifications; onNotificationPosted/Removed): CALL = category call (ongoing) with answer/decline actions found
// by action title/semantic action; TIMER = clock-app notifications with EXTRA_SHOW_CHRONOMETER / usesChronometer
// (countDown via EXTRA_CHRONOMETER_COUNT_DOWN); PROGRESS = EXTRA_PROGRESS_MAX > 0 && ongoing. Ignore our own package.
// Prefs.KEY_LIVE (Boolean, default true) gates it all.
// EdgeService posts CHARGING (transient, 3.5 s) on ACTION_POWER_CONNECTED with battery %, and ENGINE posts MEDIA
// (transient 3 s) on track change while closed.
```

## ENGINE work (EdgeView/EdgeService)
1. QUICK becomes data-driven from QuickConfig + Tiles + Arcs: inner ring (r 0.42R) and middle ring (r 0.66R) slots
   spread evenly over a = 12..78 deg by count; up to 2 concentric outer arcs (r 0.88R and 0.78R when two; when there
   are two arcs the middle ring moves to 0.60R and inner to 0.38R). Each arc has its icon badge at the low end
   (tap = Arcs.tapIcon) and the same robust drag capture as the current brightness arc. Reload on QuickConfig.KEY change.
   Buttons min 44 dp touch target; visual 40 dp.
2. New section CLIPBOARD (order: QUICK, MEDIA, CLIPBOARD, APPS; remember last). Radial: up to 4 recent clips as arc
   rows (bands at r 0.36, 0.52, 0.68, 0.84 R), each row = one-line text drawn along its arc (ellipsized), pinned clips get
   an accent tick at the row start. Tap row = paste (collapse first, then ClipPaste.paste after ~120 ms so focus returns
   to the app); long-press row = pin/unpin; a small "more" badge at the outer end opens ClipboardActivity.
   Empty state: one grey clipboard icon.
   Capture: when the CLIPBOARD section becomes visible (and on open if it is the current section), make the overlay
   window focusable for one frame (clear FLAG_NOT_FOCUSABLE, updateViewLayout), call ClipPaste.capture(context) in
   onWindowFocusChanged(true), then restore FLAG_NOT_FOCUSABLE immediately. Keep it short to avoid stealing the IME.
3. Live activities rendering: when LiveBus.top() != null and the widget is closed, the idle bean morphs (springs) to a
   live size (idle * 1.55) and shows: icon at the centroid, a thin accent progress arc along the curved rim
   (progress / chronometer countdown / indeterminate sweep), and the short title drawn along the rim arc; CALL uses
   green (#30D158) / red tints with two small answer/decline dots on the curve. Second activity = small dot on the curve.
   Transient ones auto-remove after their time. Tap live bean: CALL -> contentIntent; others -> open the panel
   (MEDIA section for MEDIA). Chronometer text updates once per second only while visible and screen on.
4. Review fixes: X visual 34 dp but 48 dp hit target; accent only on active/selected states (inactive controls
   monochrome); default gradient strength 35; media title/artist larger (14/12 sp) and placed on wider arcs; haptics
   only through the gated helper everywhere; progress tick only while visible.
5. Keep: twitch fix (fixed window), previews, idle styles, visualizer, page bars (now 4 bars for 4 sections), layers.

## PLATFORM work
Implement every contract above, plus:
- QuickEditorActivity.kt (manifest, non-exported): visual editor. Top: a large live preview of the quarter circle
  (draw the rings/slots/arcs like the widget, simplified) - tap a slot to select it (accent ring), then tap a tile from
  the catalog grid below to place it; tap a selected slot again to remove; "+" ghost slots at ring ends to add (respect
  maxes); arcs row: chips for each arc id, tap to add/remove (max 2). Reset button. Minimal text (labels only in the
  catalog, 10sp under icons). Saves via QuickConfig.save (the overlay updates live).
- ClipboardActivity.kt (manifest, non-exported): full history list (flat rows, monospace-free, 2-line preview,
  pinned first with accent tick), a search field at top, tap = copy to clipboard (Toast "Copied"), long-press = menu
  (Pin/Unpin, Delete), "Clear" (unpinned) action in the header. On resume, ClipPaste.capture(this) (activity has focus,
  so capture works here).
- Drawables (Lucide style, white stroke 2, 24 viewport): ic_battery_saver (battery + leaf or battery-low),
  ic_coffee, ic_screenshot (scan), ic_lock, ic_bell_ring or ic_panel_top (notifications), ic_sliders (quick
  settings), ic_layers (recents), ic_power, ic_volume, ic_volume_off, ic_ring (bell), ic_alarm, ic_clipboard,
  ic_pin, ic_phone, ic_phone_off, ic_timer, ic_download, ic_bolt.
- Prefs additions: KEY_LIVE.
Build with ./gradlew.bat assembleRelease -q. PLATFORM builds may fail on ENGINE's in-progress files: only fix your
own files; retry later.
