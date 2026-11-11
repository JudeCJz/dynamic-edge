# Dynamic Edge — senior UI/UX + performance review

## 1. Brutal visual critique — **4/10**

The radial idea reads, but `shot_quick` looks like a dark pie chart with unrelated floating buttons. Faint wedge tiles, toggle pucks, red slider/glow, border, X and page markers all compete. The bands muddy the background; disable by default or use hairline separators. Red-brown gradient clashes with otherwise monochrome controls. Settings currently multiply choices (accent, rim, glow, bands: `MainActivity.kt:88-102`) without a coherent theme.

Everything is technically radial, but icons on round pucks inside pie slices do not read as one quarter-circle control surface. Brightness is an unlabeled outer arc with a tiny thumb. In `shot_media`, title/artist text follows tight diagonal arcs and competes with art and transport buttons; the empty state is awkward. `shot_apps` looks scattered, and offers no edit/remove cue. The X is a high-contrast dead-end; its dots are tiny and crowded beside it (`EdgeView.kt:1226-80`). Idle `dot` nearly disappears on white, while the black bean disappears on black (`shot_idle`); preview contrast on both. MainActivity is a long text-and-slider form (`MainActivity.kt:70-160`), contrary to “visual, almost no text.”

Hit areas have padding (`EdgeView.kt:1581-1600`), but X is only a 34 dp visual circle (`640-45`); make it visibly 48 dp. The widget is compact only while closed: open radius defaults to 216 dp, yielding a huge quarter-disk.

## 2. Interaction / feel — **5/10**

Horizontal pages, vertical-down close and diagonal resize compete. Paging requires horizontal dominance (`EdgeView.kt:1613-29`); diagonal swipes can feel ignored or resize unexpectedly. System corner/Home gestures also compete (skill). Add a clear page cue and gesture dead-zone; don’t intercept edge scrolling. X/dots need accessible labels. Slider has no value/auto-mode cue. Validate springs at 60/90/120 Hz and under load; responsive controls matter more than bounce.

The reported brightness jump is credible: on finger-up, code rereads persisted brightness immediately after the last write (`EdgeView.kt:1747-50`), which may lag. Keep the thumb at the final drag value until confirmed and throttle writes.

## 3. Efficiency + smoothness, ranked

1. **Settings write on drag:** `applySlider` calls `Settings.System` during movement (`EdgeView.kt:1569-78`; `Brightness.kt:60-78`), causing provider work/observer churn. Draw locally; coalesce writes to 30–60 Hz and commit on release.
2. **Continuous idle visualizer:** its Choreographer loop (`EdgeView.kt:364-402`) runs while idle music plays, contradicting zero-idle-frame guidance. Opt-in; stop on hidden/screen-off.
3. **Whole-view redraws:** state/progress/slider use `invalidate()` (`EdgeView.kt:811-31, 1578`). RenderNodes cache static sections (`1150-80`), but dynamic arcs still redraw the window. Separate dynamic overlay; schedule frames only for motion/progress/visualizer.
4. **Bitmap/layer retention:** three compositing RenderNodes while open (`EdgeView.kt:1154-80`); album-art replacement leaves old scaled bitmaps to GC (`MediaBridge.kt:276-96`); app icons allocate bitmaps on load (`AppShortcuts.kt:18-46`). Release/reuse and profile peak memory.
5. `addBand` creates arrays/Paths when layout rebuilds (`EdgeView.kt:596-17`), not per-frame. Spring trig/exp (`Spring.kt:30-65`) is less likely the bottleneck; profile before optimizing.

## 4. Bugs / risks

- Brightness can snap to stale level above. `wasAuto` is recorded but unused; manual slider movement can leave auto-brightness off (`Brightness.kt:17, 60-79`). Add explicit auto/manual control; don’t silently change mode.
- Assistant corner gesture is disabled at every service start without restore (`AssistControl.kt:19-30`). Make opt-in and restore only settings actually changed by this app.
- `TouchRegion` relies on hidden reflection and falls back to full-window touch (`TouchRegion.kt:88-123`); an OS change could intercept unrelated taps.
- Wi-Fi/Bluetooth use deprecated enable APIs (`QuickToggles.kt:182-207`). Target 28 is fragile across Android/OEM versions and app distribution. Permission/state failures need explicit UI, not optimistic toggles.
- IME, lock-screen, rotation and split-screen transitions need broader OEM testing (`EdgeService.kt:127-31`); skill says these are unverified.

## 5. Top 10 feature ideas

1. **QUICK editor:** radial preview plus tile catalog; drag into slots, reorder, remove, undo/reset. Persist `{tileId, ring, angle}`; snap to equal-angle slots per ring. Constrain ring radii by minimum touch size; overflow to pages rather than shrink icons.
2. **Replaceable control arcs:** choose brightness, media/ring/alarm volume, or none; allow 1–3 arcs with clear icons and separate hit zones. Use `AudioManager` stream volume APIs, respecting DND/ringer constraints. Expose auto-brightness separately.
3. **Clipboard page:** outer arc for recent snippets, center preview, long-press pin, swipe-delete, clear/privacy controls. Store selection in `ClipboardManager`, then request `ACTION_PASTE` on focused `AccessibilityNodeInfo`. Clipboard access is restricted/background-sensitive; secure fields, missing accessibility nodes, and apps rejecting paste will fail. Require deliberate accessibility enablement and disclose retention; never promise universal paste.
4. Edit/remove/reorder APPS shortcuts in the radial preview.
5. Accessible labels, scalable targets and switch/keyboard navigation.
6. Wallpaper-adaptive idle contrast, opacity and edge inset controls.
7. Per-tile long-press actions and permission/error states.
8. Media output picker where supported by platform APIs.
9. Profiles for Work, Driving and Night layouts.
10. Low-power mode disabling visualizer, glow and excess motion.

**Android limits:** `WRITE_SETTINGS` is user-approved special access for supported settings. Ordinary users generally cannot grant `WRITE_SECURE_SETTINGS` in Settings; ADB/root/device-owner/system privileges are not normal onboarding. Notification-listener access gives notification/media-session visibility, not arbitrary toggles. Accessibility does not grant blanket control of Wi-Fi, Bluetooth, GPS or mobile data. Use system panels/intents for restricted actions and disclose device/version variation.

## 6. Single most important change

Build the visual QUICK editor around a strict slot/ring model. It delivers the biggest unmet goal—customization—and makes a coherent radial layout possible before more controls are added.
