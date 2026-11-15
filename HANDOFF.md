# Dynamic Edge - handoff (2026-11-10, 23:30)

Android corner-widget overlay, package `com.dynedge`, project `D:\VS\PROJECTS\FREE WORK\dynamic-edge`.
A bean in the bottom-right screen corner opens into a quarter-circle radial panel with swipeable windows.
Phone: vivo I2202 (Android 14, 1080x2400, 440 dpi, 120 Hz, gesture nav) over USB adb.
Launcher: `com.android.launcher3`. App icons are System or Monochrome only (icon-pack support was removed).

Read first: `.agents/skills/dynamic-edge/SKILL.md` (architecture, rules, runbook, history), then
`review/SPEC_v1_4.md` (user requests 1-31 with exact behaviour), `review/LOOKS_DECISION.md`,
`review/ui_claude.md` (the settings design the user chose), `review/sysui_qs_drawables.txt` (vivo SystemUI
tile icon names).

## Rules from the user (always)
- No emojis anywhere (code, comments, UI, docs); Lucide-style icons; flat UI (no cards, no boxes in boxes);
  minimal text.
- Settings app: grey background (never black), every interactive element in the user's accent colour.
- The lead plans and verifies; subagents write the code. Give each subagent a scoped brief with named files,
  and never let two writers edit the same files at the same time.
- Test on the phone with adb, but the user games on it: check `adb shell dumpsys window | grep mCurrentFocus`
  first and stay away while a game or the user is in front. Restore anything you change (brightness, location,
  Quick layout, dark mode, prefs) - dump prefs first (see hooks below).
- NEVER `adb shell am force-stop com.dynedge`: it kills the accessibility service (overlay disappears). If it
  happens, rerun `tools\install.ps1 -NoBuild`.
- Toggling Location from adb triggers Google consent / "No location access" dialogs: use the dialog's Close
  button, not BACK (BACK cancels the change).
- Never kill all `codex.exe` processes.
- Gemini (`agy`) was out of quota until ~11-12; OpenCode works only with `deepseek/deepseek-v4-pro`
  (Copilot models unavailable, Google key invalid); `codex exec --skip-git-repo-check -s read-only` works.

## Build / install
```
cd "D:\VS\PROJECTS\FREE WORK\dynamic-edge"
.\gradlew.bat assembleRelease -q
powershell -ExecutionPolicy Bypass -File tools\install.ps1 -NoBuild
```
install.ps1 installs and grants everything (accessibility, notification listener, DND, WRITE_SETTINGS,
WRITE_SECURE_SETTINGS, RECORD_AUDIO, GET_USAGE_STATS, Doze whitelist). Wait ~5 s after install.
Git Bash: prefix adb commands with device paths with `MSYS_NO_PATHCONV=1`.

### adb test hooks (MainActivity.runHook)
```
adb shell "am start -n com.dynedge/.MainActivity --es set_prefs 'fill=dots;grad_on=true;gradient=70'"
adb shell am start -n com.dynedge/.MainActivity --ez dump_prefs true   # then: adb logcat -d -s DynEdgePrefs
adb shell "am start -n com.dynedge/.MainActivity --es set_prefs 'x=0' --es preview open"   # idle|open|none
```
Quote the whole set_prefs value (the device shell eats `;`). JSON values (quick_layout) work inside single quotes.
The hook briefly opens MainActivity - press HOME before screenshots.

## Logo (pixel art)
- Editable source: `tools/logo/logo_pixel_24.png` (24x24). Original reference: `tools/logo/logo_original.png`.
- Regenerate all icons: `python tools/logo/pixlogo.py app/src/main/res` (writes mipmap-*/ic_launcher*.png incl.
  adaptive foreground + monochrome, and `res/drawable-nodpi/logo_pixel.png` shown next to the Home title).
- Preview: `tools/logo/logo_preview_16x.png`.

## State of the code (built + installed 23:05, except the in-progress subagent below)
Overlay:
- Windows are data-driven (WindowsConfig): QUICK, MEDIA, CLIPBOARD, APPS, VOLUME, TIMER, NOTIFICATIONS, DEVICE,
  each in its own Win*.kt renderer; EdgeView is the host (shape, springs, paging, layers, gestures).
- Black-window bug fixed (re-record RenderNode when `dirty || !hasDisplayList()`), attach retries (BadToken),
  Doze whitelist; vivo `com.vivo.abe` can still force-stop the app (user should allow high background power).
- Looks: fills flat|dots + gradient (on/off, strength, two colours with 0 = Auto/Edge sentinels via EdgePaint),
  idle glyph none|glyph, visualizers bars|spectrum|rays|wave, flares, rim, opacity, beat bounce (onset swell,
  fluid spring - verified visible on device).
- Paging: dead zone, finger-to-page ratio, commit angle and fling threshold all scale with Swipe sensitivity
  (default 35, less sensitive); PageAnim follows the finger with smoothing and settles critically damped.
  Not yet tried by hand at different slider values.
- QUICK tiles: real vivo SystemUI icons via TileIcons (verified: hotspot, location, cellular, torch); Wi-Fi,
  mobile data, location, hotspot, airplane, NFC, dark mode act through QsDriver (clicks the real QS tile, closes
  the shade with DISMISS_NOTIFICATION_SHADE). Verified: Dark theme and Location toggle. Custom third-party tiles
  live in their own ring. In-panel edit mode was REMOVED by user request; rearranging only in the settings
  Quick layout editor.
- App icons: IconTheme (System or Monochrome; Apps window editor toggle) used by APPS, recent ribbon,
  notifications.
- Timer: one timer in the clock app. Edge start fires ACTION_SET_TIMER (skip UI); the vivo clock notification
  (channel "timers", custom view, no stop action, `when` = post time) is linked; countdown read from its
  Chronometer. Verified: clock-started timer shows 4:56 on the bean and clears when deleted in the clock app.
  Stop on the edge opens the clock's timers (no stop action exists) and clears when the notification goes.
Settings (chosen design, review/ui_claude.md):
- Home with hero preview + category rows (Look, Size, Windows, Apps, Live, Feel, Permissions); sub-pages via
  PageStack (PageHome/PageLook/PageSize/PageLive/PageWindows, Ui.kt, Accordion.kt, Perms.kt); grey Skin.kt,
  ColorPicker (edge colour + gradient colours), SavedLooks, sliders only drag on horizontal intent.

## Last fix pass (done, installed 23:25)
Verified on the phone: Home categories are drop-downs (one open at a time, sub-rows with summaries open the
page scrolled to that section); category pages are flat; the preview now draws a stage with the edge in its
corner; "Themed" is now "Monochrome" and the App icons choice is also on the Apps page.
Built but not checked on screen: DEVICE badges on one line with the battery badge, AppPickerActivity icons.

## Status 2026-11-16 00:32 (Claude, v1.5.0 installed)
- v1.4.0 crashed on opening settings: Studio.LONG_ACTIONS was a stored val declared after init{} (null while the page
  was built). Now a getter. Rule: Studio builds its page in init{}, so no stored properties below it.
- Added since v1.2.0: long-press actions (Prefs.LA_*), Long press drop-down, Live test section (LiveTest.kt, "test:"
  LiveBus keys, demo music via DemoBeat), My tiles delete X, tray hides TileSupport.usable == false, overlay skips them,
  Quick/App rings angular margins, floating = equal gap (position slider removed), permissions banner, colour presets +
  saved colours, true pixel mode (no-AA layer + full-res pixel-font text replay), glass mode (Glass.kt; real blur
  unavailable on vivo I2202: isCrossWindowBlurEnabled false, so fallback frosted look).
- Checked on phone: settings opens, glass and pixel modes draw, no crash. Rest is the user's testing.

## Status 2026-11-15 23:56 (Claude, v1.2.0 installed, phone 1080x2400)
Built by Sonnet subagents, lead-reviewed, installed; only the settings page and idle bean were seen on screen (no crash).
- Live bean (timer/download/charging) no longer dimmed (fade was tied to open progress); LIVE_SCALE 1.8, bigger icon/text.
- Notifications: app name first, rows from the outermost ring inward, adaptive pitch, max 5 (Prefs.rows(..., max)).
- MediaBridge "ghost": last track (prefs file media_last + filesDir/media_last.png) stays after the player closes;
  play/tap launches the app and autoplays (session play, else explicit MEDIA_BUTTON broadcast).
- Ring radius sliders: w_quick_ring_in/out (Quick editor tray), w_apps_ring_in/out (Apps editor); AppRing.scale.
- Visualizer: polls getFft every vsync, max capture size, one smoothing stage, raw-bass onset `kick`, 60/180 ms delay line.
- Shortcut tiles: ShortcutStore/ShortcutCatalog/ShortcutPickerActivity, spec sc:<uuid>, prefs quick_shortcuts (see SKILL.md).
- IconTheme: every icon normalised to one shape (icon_corner %), round/glyph icons re-plated, icon_pixel pixelate.
- Body colour (KEY_BASE) with ink flip on light bodies; floating edge (float_on, float_gap), position (edge_pos),
  left side (side_left, canvas mirrored with text/icons un-mirrored), long-press hide (hide_s).
- Pixel mode (pixel_mode, pixel_size; bottom of settings): PixelMode.kt, res/font/pixel.ttf from tools/pixelfont/make_font.py,
  overlay drawn via EdgeView.pixelHook -> PixelLayer (software canvas path), icons pixelated.
NOT hand-tested: all of the above on the phone. Tune IconTheme thresholds (coverage 0.7, zoom 1.08) and viz WEIGHT/EDGE_HZ if needed.

## Status 2026-11-11 15:40 (OpenCode, Nothing phone) - READ THIS FIRST
- **The settings screen is ONE scrolling page** with category headings (Look, Shape, Windows, Music, Motion,
  Apps, More). The tab bar and the horizontal pager are gone. Every setting has a visible text caption
  (sliders show their name, toggles show their name under the icon). The big `EdgePreviewView` canvas at the
  top of the settings is GONE - the user removed it. The music thumbnails still animate (a `BeatDriver` runs
  the 118 BPM demo beat while the settings screen is up).
- **The look is black and white plus the accent.** No themes, no edge colour, no gradient, no gradient
  strength, no texture chooser - `Prefs.migrateLook()` clears those once (LOOKS_V 3). The accent swatch row
  is the only colour control.
- **The APPS window is being redrawn** as square icons on a 4x4 grid (see "The APPS grid geometry" in
  review/SHARED_CONTRACT.md), with NO text at all - no "APPS", no "RECENT", no app names.
- **New: user-made windows.** `WindowsConfig.custom()` / `saveCustom()` (Prefs.KEY_CUSTOM_WINS),
  `WinDesign` renders any window from a spec, `WindowEditorActivity` is the editor. Patch applied:
  `EdgeView.make()` falls through to WinDesign, `reloadWindows()` uses `enabledWithCustom()` and drops the
  cached WinDesign windows, EdgeService reloads on KEY_CUSTOM_WINS, the manifest has .WindowEditorActivity.
- **New: a Nothing dot-matrix media window** (`WinNothingMedia`, id "nothingmedia", registered in
  WindowsConfig.ALL + EdgeView.make) with a 5x7 dot font and a 16x16 dot grid visualiser.
- **The media window must not follow Nothing's "Essential News"**: `com.nothing.hearthstone` publishes a
  PODCAST_SESSION and MediaBridge used to fall back to "the first session". Fixed with a blocked-package
  list (Prefs.KEY_MEDIA_BLOCKED / DEFAULT_MEDIA_BLOCKED).
- **The Dyn Edge Canvas design tool works again** (tools/canvas/dyn_edge_canvas.html): it had CSS for a
  Home tab but no JavaScript, and booted on a tab that did not exist. It now has Home (browse the 20 themes
  and 47 windows, mark them approved) and a Themes tab (edit a theme, showcase it on the stage).
  Verified in headless Chrome: all 6 tabs render, all 20 themes and 47 windows draw, no console errors.
- **Backup before all of this:** _backups\dynamic-edge-2026-11-11-1345 (the project is NOT a git repo).
- Not verified on screen: the new APPS grid, the window editor, the dot media window, the theme removal's
  effect on the overlay look. The phone was busy during the build.

## Status 2026-11-11 evening
- Installed: clipboard popup (ClipPopup.kt), red support markers + Quick layout v2 (2 rings, drag-and-drop editor,
  QuickConfig v2 with v1 migration; backup of the old layout in review/quick_layout_backup_v1.json), outer
  ring gap from the slider raised to STROKE/2 + 11 dp. QR tile vanished from the user's layout while they were
  in the editor - asked the user whether they removed it.
- Dyn Edge Canvas (tools/canvas/dyn_edge_canvas.html, skill dyn-edge-canvas) now has: Windows home, 20 themes,
  8 current replicas, 15 reworks (Apps priority), 15 new windows, 6 Dot/Glyph windows, per-window idle, Review
  tab. NEXT: user reviews and sends "Export for import" JSON; lead imports approved items into the app
  (SKILL.md of dyn-edge-canvas has the procedure).

## Status 2026-11-11 12:45 (Claude, Nothing phone)
- Installed + verified on Nothing (Android 17): every QS tile path works. Direct: Wi-Fi, Bluetooth, torch, DND,
  rotation, Night Light, Extra Dim. Via the phone's real QS tile (QsDriver, swipes Nothing's Compose pager -
  needs canPerformGestures in edge_service.xml): NFC (page 2), ringer, battery saver (refused while charging
  is Nothing's own rule). Phones that have their own "ringer"/"battery" QS tile always use it (AudioManager
  silent = DND on Nothing; low_power write does nothing).
- Not live-tested on purpose: hotspot, mobile data, airplane (PC uses the hotspot). Not tested yet: location,
  dark mode, Glyphs, screen record, QR, Cast, controls, custom tiles.
- Pause in sensitive apps (A11yGate/PauseGate): user confirmed BHIM works (2026-11-11). Fixes: accessibility_enabled
  set to 0 when our removal empties the list; re-enable only when a real app (launcher entry or home) is in front,
  never for SystemUI biometric prompt / permission dialogs / gms. Earlier check: verified with BHIM - service removed within 1 s of launch, BHIM
  opened (fingerprint prompt), service back on after leaving. Default list = installed bank/UPI apps; edit in
  Studio > More > Pause in apps.
- Test gotcha: HOME does not close the panel, so tapping the bean on an open panel hits X. Use a pixel check
  (black at 1000,2250 = open) before tapping; QS-click tiles close the panel afterwards.
- A separate subagent was building tools/canvas/dyn_edge_canvas.html (Dyn Edge Canvas design tool) +
  .agents/skills/dyn-edge-canvas/SKILL.md - check it finished.

## Status 2026-11-11 00:15 (Claude, Nothing phone)
- Phone now: Nothing A069P, Android 17, 1260x2800 @480, launcher com.nothing.launcher. The user's PC is online
  THROUGH THE PHONE'S HOTSPOT: never toggle hotspot, mobile data or airplane mode when testing.
- Another AI rebuilt settings as "Studio" (Studio.kt, StudioViews.kt, WindowSketch.kt, EdgeGeom.kt,
  DirectToggles.kt). User has not yet said whether to keep it over the category-rows Home.
- Done + installed: grab zone (pref `grab`, 72 dp) stops the launcher drawer stealing drags; arcs/sliders thin
  at rest, thick while dragging; Wi-Fi tile icon fix. Verified on Nothing: bean, panel, Bluetooth toggle, Media,
  Apps, Device pages (badges in one row).
- Wi-Fi bug on Nothing: tile updated 2-3 s late so the user tapped twice (on then off). A subagent was making
  every tile really toggle (direct API where possible, else clicking the real QS tile; Android 16+ QS tiles
  have content-desc "<spec>, WxH, row R column C" - see review/nothing_qs_dump.xml) + optimistic tile state.
  A background job builds + installs when it finishes. NEXT: test every safe tile on the Nothing and restore it.

## Status 2026-11-10 23:30 (after the subagent pass)
- Subagent items 1, 4, 5 verified on the phone: preview draws (idle bean on Home, open quarter in Look),
  "Monochrome" naming + App icons on Look and Apps pages, Home-only drop-downs that jump to a highlighted section.
- Item 2 (Wi-Fi): WifiInfo.networkId is always -1 without location permission -> now uses ConnectivityManager
  (ACCESS_NETWORK_STATE added). The vivo `vivo_ic_qs_wifi_N` glyph drew a grey wedge + x, so candidates now
  prefer `vivo_qs_wifi_signal_N`. BUILT, NOT INSTALLED (user was using the phone) - install and check the tile.
- Item 3 (DEVICE badges on one line): code is in WinDevice.layout, not yet seen on the phone.
- Logo: user redrew it (from logo_preview_16x.png, kept as logo_preview_16x_user_edit.png); now the 24 px source.
  Themed icon layer alpha follows brightness (light = full tint, grey glow = partial). Not yet seen on the phone.
- Careful: swiping across the open panel can hit the brightness arc (it turned auto-brightness off once;
  restored with `settings put system screen_brightness_mode 1`).

## Status 2026-11-10 23:45 (Claude session; installed 23:41, includes the 23:30 changes above)
- Settings replaced by a visual "studio": one screen, live canvas (same drawing code as the overlay; idle/open
  toggle; drag the rim to resize, drag the flare dot; number chip only while dragging; synthetic beat on Music,
  replay on Motion) above icon tabs Colour, Shape, Windows, Music, Motion, Apps, More. Files: Studio.kt,
  StudioViews.kt, EdgePreviewView.kt (canvas), EdgeGeom.kt (a COPY of EdgeView's outline code - keep in step by
  hand), WindowSketch.kt, FlatSlider.kt, Ui.kt. Old Page*/Accordion UI deleted (the Home drop-down notes above
  are obsolete). All 7 tabs screenshot-checked. Not hand-tested: rim/flare drags, window-card drag reorder,
  saved-look thumbnails, banner with overlay off. Nit: icon-style captions on the Apps tab truncate.
  The "Grab area" setting (KEY_GRAB) added at 23:23 by another writer was kept (Shape tab).
- Tiles no longer open the QS shade: DirectToggles.kt (`USE_QS_DRIVER = false`). Wi-Fi: setWifiEnabled,
  VERIFIED off/on silently, tile shows the plain connected glyph. Location (LOCATION_MODE write), dark mode
  (UiModeManager / ui_night_mode), airplane (flag + broadcast, reverts if it fails) - not tested. Mobile data ->
  Internet panel, NFC -> NFC panel, hotspot -> tether settings, custom tiles -> their preferences/app.
- App icons: full size, rounded square (28%), no disc; Monochrome = white glyph on #2C2D31 rounded square
  (checked on the Apps tab).
- Open bugs: paging forward from the MEDIA window failed in 3 adb swipes (QUICK -> MEDIA works) - check what
  MEDIA captures; one studio test tap changed the accent to red (restored #30D158) - check tab/swatch hit areas.
- TWO AIs edited this project at the same time tonight: coordinate before editing (one writer per file).

## Next steps
1. Fix the open bugs above; hand-test location / dark mode / airplane tiles (restore state; Google location
   dialogs: use Close, never BACK); build; install; verify with screenshots (crop bottom-right 704 px).
2. Hand-test swipe sensitivity at 0 / 35 / 100, the Quick layout editor (drag, X remove, tap add), the App
   icons choices, the custom colour pickers, and the first tap on the idle bean (one tap at 1030,2330 did not
   open it once - check the touch region).
3. Update SKILL.md status + memory; bump versionName (still 1.0.0 in app/build.gradle.kts).

## Review material
`review/codex.md`, `review/opencode.md`, `review/crit_codex_final.md`, `review/crit_deepseek.md` (full
critiques), `review/islands.md`, `review/opencode_looks.md`, `review/ui_codex.md`, `review/ui_deepseek.md`,
`review/ui_claude.md` (summary of the chosen design; Kimi's earlier copy of that file was overwritten).
