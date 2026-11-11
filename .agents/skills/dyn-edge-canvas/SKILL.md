---
name: dyn-edge-canvas
description: Dyn Edge Canvas - standalone single-file HTML design and review tool for Dynamic Edge (the Android corner widget). Windows home, window designer on rings/angles, themes (fills, animated effects, rims, palettes), per-window idle arts, live events, and an owner review flow (Pending / Approved / Changes / Rejected + notes). Exports a versioned dp/degree JSON (schema v2, reads v1) that maps to Win*.kt, Looks.kt, EdgePaint.kt and EdgeView.drawLive. How to open it, the schema, every element / fill / effect with its Kotlin mapping and cost, and how the lead imports approved designs.
---

# Dyn Edge Canvas

File: `D:\VS\PROJECTS\FREE WORK\dynamic-edge\tools\canvas\dyn_edge_canvas.html` (one file, inline CSS/JS,
vanilla Canvas 2D, no build). It does not touch `app/`. Parent skill for the widget itself:
`.agents/skills/dynamic-edge/SKILL.md`.

Roles: the owner reviews and approves designs here; the lead exports "For import" and builds the approved ones in
the app (procedure at the end).

## Open it
- Serve it (recommended; file:// works too but some embedded previews open it as a data: URL with no storage):
  `cd "D:\VS\PROJECTS\FREE WORK\dynamic-edge\tools\canvas"; python -m http.server 8791` then
  `http://127.0.0.1:8791/dyn_edge_canvas.html`. On a phone: the same server bound to the LAN IP.
- Lucide icons load from `https://unpkg.com/lucide@0.460.0/dist/umd/lucide.min.js` (every icon name used by the
  catalogue was checked against that build); offline the canvas uses ~25 built-in Lucide-style icons.
- Autosave: `localStorage` keys `dyn-edge-canvas/doc` and `dyn-edge-canvas/ui`, 350 ms after every change, plus the
  Save button on the Windows home. file:// and http://127.0.0.1 are different origins (separate autosaves).
  A v1 autosave is upgraded on load: the review catalogue (themes, current / reworked / new / Dot windows, idle
  arts) is added once; deleting a catalogue item later is permanent (Templates > "Add missing catalogue items"
  brings missing ones back).

## UI map
- Header tabs: **Windows** (home, always the first screen), Window, Themes, Review, Look, Idle, Events; Templates
  menu, Undo / Redo (Ctrl+Z, Ctrl+Y), Import, Export, PNG, interface accent (UI only).
- **Windows home**: grid of every window with a live thumbnail (drawn by the stage renderer, refreshed 3 per frame),
  name (rename inline), on / off switch (= shown in the widget), status dot, collection tag, own-idle tag; actions:
  drag grip (pointer drag, works on touch) to reorder = page order, Edit (opens it on the stage), Duplicate, Delete;
  "New window", Save, Export. Collection filter: All / Current / Reworked / New / Dot / Glyph / Custom.
- **Window**: collection filter (the stage pages only through the filtered "deck"; page dots become a "3 / 15"
  counter above 9 pages), windows list with status dots, Review fields (status, collection, base app window,
  purpose, data sources, app support, surface panel / popup, app labels, notes), **Idle for this window**
  (None = global idle, pick an art, New idle art = creates a pixel art bound to this window and opens the Idle
  editor with a "Done" banner, Preview on the bean), Add element (24 types), Inspector (every field incl.
  `opacity` and `data` = what the element needs from the app), element list, snapping. Stage: drag elements (snap
  to ring step, to rings used by other elements, to angle step; Alt bypasses), arc items have end handles, arrows
  nudge, Delete, Ctrl+D, swipe empty stage to page.
- **Themes**: gallery of themes (thumbnail = the demo window drawn in that theme), selected theme: description,
  computed app support (existing / needs new code + reasons) and cost (low / medium / high + the per-frame work),
  "Use in every tab" (doc.activeTheme), "Copy basics to Look", duplicate, delete; Preview (Showcase loop idle ->
  open -> idle -> event, demo window, event, Play event, Trigger effect, tilt slider for Holo / Chrome, also in the
  stage bar); Identity, Colour + content palette, Fill (+ EdgePaint gradient), Effect, Rim and glow, Idle and motion,
  Review. Tapping the stage in Themes also triggers the theme's event reaction.
- **Review**: every theme and window with status buttons (Pending / Approved / Changes / Rejected), notes, an Open
  button (opens it on the stage for editing), filters by status (with counts) and kind (Themes, Current, Reworked,
  New, Dot / Glyph, Custom), List or Gallery view, "Export for import". Priority items (`top: true`, the three Apps
  reworks) sort first, then themes, then windows by collection.
- Look / Idle / Events: as before (device presets, shape sizes, Look colours; pixel idle-art editor with frames,
  onion skin, presets compare; live events with timeline, ring, glyph, motion in / out). The Idle tab edits the
  window-bound art when opened from "Idle for this window".

## Geometry used by the tool (same as the app)
- Frame: origin = the screen's bottom-right corner, shape toward -x / -y. Polar angle a in degrees:
  0 = along the bottom edge, 90 = along the right edge. `x = -r cos a`, `y = -r sin a`. Arc start = `180 + a`.
- Shape: port of `EdgeGeom.build/buildFit`; rim = the same path inset 0.5 dp (dual rim adds an inset 2.8 dp path).
- Look fill (existing app): base, EdgePaint radial stops 0 / .38 / .72 / 1 at radius `max(w,h) * 1.15`; glow =
  accent radial .533 / .188 / 0 at 0 / .38 / .85 scaled by g; dots 0.6 dp on an 8 dp lattice, white .105; rim 1 dp
  mix(white, accent, g) alpha .10 + .65 g. g = max(progress * .4, drag .5, event glow x alpha).
- Springs: Spring.kt closed form; open 420 / 300 x stiffnessScale, damping openDamping (+.04 height); close 520 /
  600, .92. Content alpha = smoothstep(.55, .95, p). Pages rotate by `(deckIndex - pagePos) * 70` deg, glide
  250-340 ms, crossfade done by 45 %; long jumps glide from the neighbour slot.
- Catalogue replicas are computed at R = 216 dp (the app default) with the Kotlin formulas ported literally
  (`slotAngle`, `slotRadius`, `place` clamp `R - 6 - itemR`, `ArcSet.layout` badge / start angles, WinDevice badge
  row, WinMedia title / artist / seek radii ...); radii are stored as fractions of R.

## JSON schema `dyn-edge-canvas` version 2
Units: lengths dp, angles degrees, text sp, time ms; element radii `r` are a FRACTION of `look.openDp`.
Colour tokens: `accent`, `white` (= theme palette fg), `grey` (= dim), `fill` (= surface), `track`, `black`,
`green`, `red`, or `"#RRGGBB"`. Readers ignore unknown keys and reject `version > 2`. Version 1 files import
unchanged (no themes; windows get `group: custom`, `status: pending`).

```jsonc
{
  "format": "dyn-edge-canvas", "version": 2,
  "subset": "approved+changes",          // only in "Export for import"
  "units": { ... }, "device": { ... },    // as v1 (preview only)
  "look": { ... },                        // as v1: idleDp, openDp, flareDp, base, accent, gradient, fill, rim, glow, opacityPct, motion
  "themes": [ /* Theme */ ], "activeTheme": "thm_x" | null,
  "windows": [ /* Window */ ],            // order = page order
  "idleArts": [ /* IdleArt (v1) */ ], "idleActive": "art_x",
  "events": [ /* Event (v1) */ ]          // empty in "Export for import"
}
```

### Window
```jsonc
{ "id": "win_x", "cid": "rw.apps.split",          // cid = catalogue id ("" for user-made)
  "name": "Apps - Pinned + Recent", "on": true,
  "group": "current | reworked | new | dot | custom", "base": "apps",   // app window id it replaces / new id
  "purpose": "one line", "data": "APIs / permissions", "appSupport": "existing | needs new code",
  "surface": "panel | popup",                       // popup: the edge stays idle and the elements float (clipboard card)
  "appLabels": false, "top": false,                 // labels under app icons; priority in Review
  "idle": { "art": "art_x" | null },                 // own idle art (null = global)
  "status": "pending | approved | changes | rejected", "notes": "",
  "elements": [ /* Element */ ] }
```
Every element has `id`, `type`, `opacity` (0..1) and `data` (what it needs from the app).

### Element types
v1 types (unchanged; Kotlin mapping in "Turning an export into app code"): `tile`, `slider` (+ v2 `strokeDp` 0 =
5 / 9 dp, `knobDp`, `knobColor`), `badge`, `arcText`, `text`, `app` (+ `disc` false = the bitmap's own shape like
WinApps, `ring` colour token = suggestion / per-app colour ring, `badgeText` count, `recency` 0..1 time-ago dot,
-1 off), `ring`, `image` (+ `placeholder` music / initials / none, `spin` deg/s, `grooves`), `button` (+ style
`none` = icon only).

New types (all buildable with android.graphics; costs are per drawn frame; windows only draw while open and are
recorded into the section RenderNode, so static ones cost nothing after recording):

| type | fields | Kotlin (Canvas) | cost |
|---|---|---|---|
| `arcList` | `r step a0 a1 items max sizeSp weight color subSp subColor lead(none/dot/icon/app) leadDp leadIcon pinMark highlight action` | rows k at `(r + k step) R`: one Path per row (`Geo.arcPath`), `TextUtils.ellipsize` once on data change, `drawTextOnPath(title, path, leadOff, 0)`, detail after `measureText + 8 dp` if > 28 dp left. Lead disc at `a0 + (lead/2 + 1)/rr` rad on `rr + 5 dp` (as WinNotifications). Pin = accent 2.2 dp line at `a0 - 3.5` from `rr - 3` to `rr + 13` (WinClipboard). Items syntax: one per line, `*` = pinned, `[lucide-icon]` prefix, ` \| ` splits title / detail. | low |
| `chip` | `r a text icon sizeSp weight shape(circle/pill) sizeDp style action` | circle: `drawCircle` + centred `drawText` (WinTimer chips); pill: `drawRoundRect` radius = half height, width = text + icon + 1.4 h | low |
| `waveform` | `r a0 a1 count heightDp barDp value color rest live seed action` | precomputed FloatArray of radial segments, two `drawLines` calls (played / rest). live = IdleVisualizer levels (RECORD_AUDIO) while open, else static amplitudes | low (static) / medium (live, 30 fps) |
| `sparkline` | `r a0 a1 heightDp values area dot color` | Path built on data change (`r + norm(v) * height` per point), area closed back along the baseline arc, fill alpha .18 | low |
| `dial` | `r a0 a1 ticks major lenDp majorLenDp value color knob labels labelSp action` | tick endpoints precomputed; `drawLines` twice (lit / unlit); labels `drawText` at `r - majorLen - 8`; knob circle; drag like WinTimer `capture / dragTo` | low |
| `toggle` | `r a wDp hDp icon label sub on action` | `drawRoundRect` (accent when on) + icon drawable + one or two `drawText` | low |
| `donut` | `r a sizeDp thicknessDp values colors gapDeg text sub sizeSp` | `drawArc` per segment (round caps, gap), centre text | low |
| `band` | `r a0 a1 widthDp color text textColor sizeSp action` | `drawArc` stroke `widthDp`, round caps inside the span; text on an arc path at `r - .36 size` | low |
| `gauge` | `r a sizeDp mode(compass/level/speed/clock) value value2 live color text sub` | `canvas.rotate` + `drawLine` ticks; sensors via SensorManager (`TYPE_ROTATION_VECTOR`, `TYPE_ACCELEROMETER`) registered only in `onShown(true)`, invalidate at UI rate | medium while visible |
| `dotText` / `dotArc` | `r a / r a0 a1, text pitchDp dotScale color ghost align` | own 5 x 7 dot font (table `DOTF` in the HTML; no brand font): precompute dot centres per string, `drawPoints` with ROUND cap and strokeWidth = dot size (lit), second call for ghost dots. dotArc: same with each glyph rotated along the arc (baseline = r, tops outward) | low |
| `dotGrid` | `r a0 a1 cols rows pitchDp dotScale mode(bars/spectrum/level) value live seed color peak ghost` | polar dot lattice centres precomputed; per frame decide lit rows per column from levels; `drawPoints` (lit, peak, ghost) | low (static) / medium (live) |
| `glyphStrip` | `r a0 a1 segments gapDeg widthDp value mode(progress/pattern/chase) pattern color glow action` | per segment `drawArc` x 3 widths (glow layers .12 / .25 / 1 alpha) or one BlurMaskFilter layer; head segment in accent; chase animates only while visible | low / medium (chase) |
| `folder` | `r a sizeDp label colors action` | rounded square + 4 mini icon bitmaps (2 x 2) + label | low |
| `popup` | `r a wDp hDp cornerDp title items sizeSp action` | the floating clipboard card: a separate small overlay view (or a second RenderNode drawn above the bean) shown after the panel collapses; rows = ClipStore, row icons copy / pin / delete | low |

### Theme
```jsonc
{ "id": "thm_x", "cid": "thm.lava", "name": "Molten Lava", "description": "...", "collection": "" | "dot",
  "base": "#140400", "accent": "#FF6A1F",
  "palette": null | { "fg": "#FFFFFF", "onFg": "#000000", "dim": "#8E8E93", "surface": "#1C1C1E", "track": "#2C2C2E" },
  "gradient": { "on": false, "pct": 35, "inner": null, "outer": null },          // EdgePaint (existing)
  "fill": { "type": "flat | dots | dotgrid | stops | sweep | noise | scanlines | paper",
            "stops": ["#..", ...], "alpha": 1, "angle": 0, "pitchDp": 5, "dotDp": 1.1, "color": "#FFFFFF" },
  "glow": { "on": true, "strength": 1, "color": null },
  "rim": { "style": "line | neon | dual | gradient | none", "widthDp": 1, "color": null, "color2": null },
  "opacityPct": 100,
  "idle": { "art": "inherit | none | glyph | bars | spectrum | rays | wave | pulse | orbit | <art id>" },
  "motion": { "stiffnessScale": 1.075, "openDamping": 0.808 },
  "effect": { "type": "none | lava | aurora | holo | crt | chrome | nebula | circuit | neonRain | frost | ink | matrix | glitch | synthwave | biolum | glyph",
              "speed": 1, "intensity": .8, "density": 1, "colorA": "#..", "colorB": "#..", "tilt": 0, "autoTilt": true,
              "idleAnim": false, "bloom": 0, "onEvent": "none | flash | ripple | glitch | glyph" },
  "status": "pending", "notes": "",
  "computed": { "appSupport": { "level": "existing | needs new code", "reasons": [] }, "cost": "low | medium | high" } }  // export only, ignored on import
```
Draw order (tool and app): base -> EdgePaint gradient (if on) -> fill -> effect (clipped to the shape) -> glow ->
rim; content; then overlay effects (CRT scanlines, event flash / ripple). The theme's palette recolours the content
tokens (white / grey / fill / track) - needed for the light themes. Effects run only while open or during an event
(zero work at rest) unless `idleAnim` is true (then an opt-in frame loop like the visualizer).

**App support rule** (computed in the tool): "existing" only if fill is flat / dots, effect none, onEvent none,
no bloom, rim line or none with default colour / 1 dp, glow default colour / strength 1, no palette, idle art in
the existing Looks set. Anything else lists its reasons ("needs new code").

Fills (Kotlin, all built once per size, zero allocation per frame): `dotgrid` = Looks.ensurePattern with pitch /
dot / colour params; `stops` = RadialGradient(colors[]); `sweep` = SweepGradient + Matrix rotate; `noise` = 64 px
ALPHA_8 noise BitmapShader REPEAT scaled to 16 dp; `scanlines` = 1 x 2 dp tile; `paper` = noise + fibres baked.
Effects with cost: lava (medium: 4 RadialGradient blobs via setLocalMatrix + ~24 embers, PorterDuff ADD), aurora
(medium: 5 ribbon paths x 3 widths), holo (low: SweepGradient rotated by tilt + LinearGradient sheen; tilt =
TYPE_GAME_ROTATION_VECTOR while open), crt (medium: scanlines over content + RenderEffect blur bloom on the
section node, API 31+), chrome (low: 7-stop LinearGradient translated by time / tilt), nebula (medium: ~70
drawPoints stars + 3 clouds), circuit (medium: cached trace Path + PathMeasure.getSegment pulses), neonRain
(medium: ~40 drawLines), frost (low; real blur needs Window.setBackgroundBlurRadius API 31), ink (low: baked
Bitmap), matrix (high: ~300 glyphs - use a glyph atlas + drawBitmap), glitch (medium during events only: the
section RenderNode drawn in clipped strips with offsets + red / cyan rim copies), synthwave (medium: sun gradient
clipped by stripes + ~22 grid lines), biolum (medium: ~26 halo dots), glyph (low: 9 rim segments, chase on
events). Rims: neon = 3 strokes (6 / 3 / 1.3 w, alpha .07 / .16 / .9) + white core; dual = neon on two insets;
gradient = LinearGradient stroke.

### IdleArt and per-window idle
IdleArt is unchanged from v1 (pixels frames with chars 0 / 1 / 2 accent / 3 grey, or a preset). A window's
`idle.art` overrides the global idle art; a theme's `idle.art` sits between (window > theme > global).
App use: when the panel closes, the bean shows the idle art of the last visible window if it has one; while a
window's live state runs (timer counting, media playing) its idle art shows even if another page was last. Store
it per window id in Prefs (e.g. `idle_art_<windowId>` = the art JSON) and draw with the same Looks idle path
(pixel cells precomputed per size, frame loop only for multi-frame arts and only when opted in).

### Event
Unchanged from v1 (see the Events tab): kind, scale, color, glyph, text, ring, actions, durationMs, pulse, glow,
in / out motion. Themes react to events via `effect.onEvent`.

## Review statuses
- **Pending** (default for every new or catalogue item), **Approved** (build as is), **Changes** (build after the
  owner's notes are applied), **Rejected** (do not build). Notes are free text per theme / window.
- Export > "For import (Approved + Changes)" = `subset: "approved+changes"`: only those themes and windows (with
  notes and status), the idle arts they reference, look + device; events empty. "Full document" = everything.

## Import procedure for the lead
1. Get the owner's "Export for import" JSON (`dyn-edge-approved.json`); keep it in the repo under `design/`.
2. For each window: read `status` (`changes` -> apply `notes` first), `base`, `purpose`, `data`, `appSupport`.
   - `base` is an existing id (`quick`, `media`, `clipboard`, `apps`, `volume`, `timer`, `notifications`,
     `device`): rework that Win*.kt in place (keep its data / touch code, swap layout + draw to the elements).
   - New ids: new `Win<Name>.kt` registered in `WindowsConfig.ALL / label / icon`, EdgeView's factory and
     `WindowSketch`.
   - `layout(hw, brad)`: `rad = h.rad`; point elements `place(i, r * rad, a, dp(size))`; arc elements build their
     Paths / FloatArrays here (never in draw). `draw(c, a)`: tokens -> `EdgeView.C_FILL / C_TRACK / C_GREY /
     h.accent` (or the theme palette); icons = Lucide VectorDrawables (24 viewport, stroke 2). `hit()` with
     `beginHit / consider / bestHit`; `capture / dragTo` for sliders, dials, waveforms, scroll arcs.
   - `element.data` lists what to bind; `element.action` names the tap / long-press behaviour.
   - `surface: popup` (Clipboard popup): collapse the panel, then show the card anchored at the element's centre.
   - `idle.art`: store per window (see per-window idle).
3. For each theme: if `computed.appSupport.level` is "existing", map to Prefs directly (base, accent, grad_on,
   gradient %, grad1 inner, grad2 outer, fill flat / dots, rim, glow, opacity, stiffness / damping). Otherwise
   implement the listed reasons in `EdgePaint` (fills), `Looks` (effects / idle), EdgeView rim / glow paints, and a
   content palette passed to the windows; respect the cost notes (effects only while open or in events).
4. Verify on the vivo / Nothing device against a PNG from the tool (header PNG button) at the same open size.

## Catalogue (Review content, all Pending by default)
- **Themes (20)**: Classic Ember, Pure OLED Minimal, Midnight Dots (these 3 = existing app code), Molten Lava,
  Aurora, Holo Foil, CRT Phosphor, Liquid Chrome, Deep Nebula, Circuit Board, Neon Tokyo, Frosted Glass, Ink Paper
  (light), Matrix Rain (high cost), Glitch, Sunset Synthwave, Bioluminescent; Dot / Glyph: Dot Matrix, Glyph,
  Monochrome Paper (light).
- **Current (8)**: Quick, Media, Clipboard, Apps, Volume, Timer, Alerts, Device (faithful replicas at R = 216).
- **Reworked (15)**: Apps - Pinned + Recent, Apps - Scroll arc, Apps - Folders (priority, top of Review), Quick -
  Pills, Quick - Scenes, Media - Waveform, Media - Vinyl, Clipboard - Smart rows, Clipboard - Last clip, Clipboard
  - Popup (after tap), Volume - Labelled + ringer, Timer - Tick dial, Timer - Running, Alerts - Grouped, Device -
  Health.
- **New (15)**: Focus dial, Stopwatch + laps, Calendar - next 3, Weather radial, Network speed, Screen time,
  Favourite contacts, Voice memo, Compass + level, Calculator dial, Battery health, Now playing lyrics, Smart home,
  Clipboard translator, Step counter.
- **Dot / Glyph (6)**: Dot visualiser, Dot clock, Glyph progress, Dot battery + weather, Glyph composer, Dot quick
  tiles. Idle arts: Dot visualiser, Dot clock, Glyph pulse (+ Countdown arc for the timer windows, Bars (media)).
- Custom (3): the v1 templates Focus, Weather, Now playing.

## Testing done (2026-11-11)
- `node --check` on the extracted script: OK. Served with `python -m http.server 8791`, checked in the Claude
  browser pane: no console errors; every window (47) and theme (20) drawn on the stage without exceptions; all 24
  element types added and drawn; all Lucide names used exist in 0.460.0; full export -> normalize gives the same
  document (canonical compare) and normalize is idempotent; v1 file imports (no themes); "Export for import"
  contains only Approved / Changes items; import Replace via the dialog round-trips (47 windows, 20 themes,
  statuses kept); per-window idle (New idle art -> paint -> Done -> shown on the bean); autosave survives reload;
  home drag reorder; Themes showcase loop phases (idle, open, idle, event); Review list / gallery / statuses;
  375 px width: no horizontal scroll on Windows, Review, Window, Themes.
- Not verified: real-time animation smoothness (the preview pane throttled frames, so loops were stepped by
  script), Safari / iOS, phone-size touch drag on the home grid, the Nothing corner radius (estimate).

## Rules
No emojis anywhere (UI, code, comments). Lucide icons only (stroke style). Flat UI: grey #26262A chrome, no
cards in boxes; interactive controls use the interface accent (header colour picker). The dot-matrix font is an
original 5 x 7 design; no brand fonts, logos or trademarks.
