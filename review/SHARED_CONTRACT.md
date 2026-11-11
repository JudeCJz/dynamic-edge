# Dynamic Edge - shared contract for the theme + window rework

Written by the lead (OpenCode) on 2026-11-11. Three agents work in parallel on isolated copies of
`dynamic-edge`. This file is the single source of truth for the shared parts. **If you are an agent:
read this file completely before writing any code.**

## Ground rules (from the user, non-negotiable)

- No emojis anywhere (code, comments, UI, docs). Lucide-style icons only. Flat UI (no cards, no boxes
  in boxes). Minimal text.
- Settings app: grey background (never black), every interactive element in the user's accent colour.
- Zero work while idle: no Choreographer frames, no timers, no broadcast receivers when the edge is
  closed. No per-frame allocations in the draw/animation path (reuse Path, Paint, RectF, Region).
- No AndroidX, no Compose, no AppCompat, no XML layouts. Framework APIs only.
- `targetSdk = 28`, `minSdk = 30`, Java 17, Kotlin jvmToolchain 17.
- Release build is minified with R8 (`isMinifyEnabled = true`). Keep the code reflection-free.
- The **lead plans and verifies; subagents write the code**. Never edit a file that is not in your
  ownership list (below). If you need a change in someone else's file, write the change into
  `PATCHES.md` in your working copy and say so in your final report.

## Worktree layout

| Agent | Working copy | Owns |
|---|---|---|
| A (themes) | `C:\Users\judey\AppData\Local\Temp\opencode\wt\themes` | `app/.../Theme.kt` (new), `app/.../ThemeStore.kt` (new), `app/.../Looks.kt`, `app/.../EdgePaint.kt`, `app/.../EdgeView.kt` (theme parts only) |
| B (windows) | `C:\Users\judey\AppData\Local\Temp\opencode\wt\windows` | `app/.../WinDesign.kt` (new), `app/.../WindowsConfig.kt`, `app/.../WindowEditorActivity.kt` (new), `app/.../WindowsEditorView.kt` (new) |
| C (apps window) | `C:\Users\judey\AppData\Local\Temp\opencode\wt\apps` | `app/.../WinApps.kt`, `app/.../WinAppsRevamp.kt` (new) |

The lead owns and merges: `Prefs.kt`, `Studio.kt`, `StudioViews.kt`, `AndroidManifest.xml`,
`HANDOFF.md`, `SKILL.md`, `tools/canvas/dyn_edge_canvas.html`.

## The JSON schema (shared by the canvas and the app)

This is the format the Dyn Edge Canvas exports and the app imports. It already exists in
`tools/canvas/dyn_edge_canvas.html` - the canvas is the source of truth, do not invent a new format.

```
{
  "format": "dyn-edge-canvas",
  "version": 2,
  "themes": [ Theme, ... ],
  "windows": [ Window, ... ],
  "idleArts": [ IdleArt, ... ]
}
```

### Theme
```
{
  "id": "thm.lava", "cid": "thm.lava", "name": "Molten Lava",
  "description": "...",
  "collection": "" | "dot",
  "base": "#140400",                  // hex "#RRGGBB"
  "accent": "#FF6A1F",
  "palette": null | { "fg": "#FFFFFF", "onFg": "#000000", "dim": "#707176",
                      "surface": "#2C2D31", "track": "#45464C" },
  "gradient": { "on": true, "pct": 35, "inner": null, "outer": null },
  "fill": { "type": "flat"|"dots"|"dotgrid"|"stops"|"sweep"|"noise"|"scanlines"|"paper",
            "stops": ["#FF8A2A", ...], "alpha": 1, "angle": 0,
            "pitchDp": 4, "dotDp": 1.1, "color": "#FFFFFF" },
  "glow":  { "on": true, "strength": 1, "color": null },
  "rim":   { "style": "line"|"none"|"neon"|"gradient"|"dual", "widthDp": 1,
             "color": null, "color2": null },
  "opacityPct": 100,
  "idle":  { "art": "inherit"|"none"|"glyph"|"bars"|"spectrum"|"rays"|"wave"|"<artId>" },
  "motion": { "stiffnessScale": 1.075, "openDamping": 0.808 },
  "effect": { "type": "none"|"lava"|"aurora"|"holo"|"crt"|"chrome"|"nebula"|"circuit"|
              "neonRain"|"frost"|"ink"|"matrix"|"glitch"|"synthwave",
              "speed": 1, "intensity": 0.8, "density": 1,
              "colorA": "#FFFFFF", "colorB": "#888888", "tilt": 0, "autoTilt": true,
              "idleAnim": false, "bloom": 0, "onEvent": "none" },
  "status": "pending"|"approved"|"changes"|"rejected",
  "notes": ""
}
```

### Window
```
{
  "id": "win.custom", "cid": "new.focus", "name": "Focus",
  "on": true,
  "group": "current"|"reworked"|"new"|"dot"|"custom",
  "base": "quick"|"media"|"clipboard"|"apps"|"volume"|"timer"|"notifications"|"device"|"",
  "purpose": "...", "data": "...", "appSupport": "existing"|"needs new code",
  "surface": "panel"|"popup", "appLabels": false, "top": false,
  "idle": { "art": null },
  "status": "pending"|"approved"|"changes"|"rejected",
  "notes": "",
  "elements": [ Element, ... ]
}
```

### Element
```
{ "id": "el.x", "type": "<see TYPES below>", "r": 0.6, "a": 45, "sizeDp": 23,
  "icon": "wifi", "text": "...", "action": "...", "data": "...", "opacity": 1,
  ...type specific fields... }
```
Element `type` values (from the canvas `TYPES` table): `tile`, `slider`, `badge`, `arcText`, `text`,
`app`, `ring`, `image`, `button`, `arcList`, `chip`, `waveform`, `sparkline`, `dial`, `toggle`,
`donut`, `band`, `gauge`, `dotText`, `dotArc`, `dotGrid`, `glyphStrip`, `folder`, `popup`.

Polar convention (identical in the app): origin = the screen's bottom-right corner, the shape lies
toward -x/-y. Angle `a` in degrees: 0 = along the bottom edge, 90 = along the right edge.
`x = -r * cos(a)`, `y = -r * sin(a)`. `r` is a fraction of the open radius (1.0 = the open edge).
Arc elements use `a0`/`a1` (start/end angle) instead of `a`. Canvas/Android arc start angle = `180 + a`.

## App-side storage keys (lead adds these to `Prefs.kt` - agents only READ them)

```
KEY_THEME_JSON   = "theme_json"    // String, the active theme's JSON (a Theme object)
KEY_THEMES_JSON  = "themes_json"   // String, JSON array of saved themes
KEY_CUSTOM_WINS  = "custom_wins"   // String, JSON array of user-created windows
KEY_APP_GRID     = "app_grid"      // String, JSON object { cols, rows, cells: { slot: "pkg/cls" } }
KEY_RECENTS_STYLE = "recents_style"// String, "off" | "icons" | "labels" | "dots"
KEY_RECENTS_PLACE = "recents_place"// String, "inner" | "outer" | "hidden"
KEY_RECENTS_COUNT = "recents_count"// Int, how many recent apps the ribbon shows
KEY_MEDIA_BLOCKED = "media_blocked"// StringSet, packages never used as a media source
```

## The APPS grid geometry (lead-defined - the editor AND the window MUST use exactly this)

The user drew the apps window: square app icons on a grid inside the quarter panel, with no text at all.
Both the editor (the grid picker) and the APPS window must place a cell the same way, or the preview will
not match the overlay.

```
R      = the open radius in dp (Prefs.openDp(ctx); 216 dp on the phone)
COLS   = 4, ROWS = 4
cell   = 0.17 * R                              (dp)
icon   = 0.13 * R                              (dp, the side of the SQUARE icon)

cell (col, row), col 0..3 left to right, row 0..3 top to bottom:
    x = -(col + 0.5) * cell                   panel-local dp, the corner is the origin
    y = -(row + 0.5) * cell
    r = hypot(x, y)                            the radius fraction is r / R
    a = degrees(atan2(-y, -x))                 0 = along the bottom edge, 90 = along the right edge
```
The farthest cell centre is 0.84 R from the corner, its icon corner 0.93 R - so every icon is inside the
quarter disc. The icons are SQUARES (rounded, corner radius 20 % of the side), not circles.

The grid is stored in Prefs.KEY_APP_GRID as:
`{"cols":4,"rows":4,"cells":{"0":"com.example/.Activity","5":"com.other/.Main"}}`
where the key is `row * cols + col` and the value is a flattened ComponentName.

## Round 2 - the user's later asks (added 2026-11-11)

The user asked for three more things after round 1 started:

1. **No big preview in the app.** The Studio screen (Studio.kt) must NOT show the large
   `EdgePreviewView` canvas. Remove the whole preview section - the stage view, the idle/open
   toggle (`modeSeg`), the replay button (`playBtn`) and the space they took. The tabs and the
   panel pager must fill the screen instead.
   **CRITICAL:** the stage also drives the music-tab thumbnails (`stage.beat.levels`) and the
   Look-tab "preview" behaviour. Removing the canvas must not break: the visualizer style
   thumbnails must still animate, and the small `EdgeThumb` previews must still work. Read
   `Studio.kt` and `StudioViews.kt` fully before changing anything.
2. **Every setting needs a visible text label** (the slider name, etc). Right now the sliders
   are icon-only with the number appearing only while dragging. Each slider / toggle row must
   show a short text caption (e.g. "Corner size", "Opacity", "Border").
3. **The media window must never pick up Nothing's "Essential News".**
   `com.nothing.hearthstone` publishes a `PODCAST_SESSION` ("Essential News" widget) and
   `MediaBridge.select()` falls back to `list.firstOrNull()` when nothing is *playing*, so the
   news widget becomes the media source. The media window must follow the user's music only.
4. **An app-placement grid.** A settings screen where the user can place apps on a grid
   (which app in which cell), and choose how the recent-apps ribbon looks and where it sits.

## The lead's integration points (already agreed - just call them)

Agent A (themes) must expose exactly this API so the lead can wire the UI:

```kotlin
// in Theme.kt
internal data class Theme(
    val id: String, val cid: String, val name: String, val description: String,
    val collection: String, val base: Int, val accent: Int,
    val palette: IntArray?,              // fg, onFg, dim, surface, track (ARGB) or null
    val gradOn: Boolean, val gradPct: Int, val gradInner: Int, val gradOuter: Int,  // inner/outer 0 = Auto/Edge
    val fillType: String, val fillStops: IntArray, val fillAlpha: Float,
    val fillPitchDp: Float, val fillDotDp: Float, val fillColor: Int,
    val glowOn: Boolean, val glowStrength: Float, val glowColor: Int,
    val rimStyle: String, val rimWidthDp: Float, val rimColor: Int, val rimColor2: Int,
    val opacityPct: Int,
    val idleArt: String,
    val stiffScale: Float, val openDamping: Float,
    val effectType: String, val effectSpeed: Float, val effectIntensity: Float,
    val effectDensity: Float, val effectColorA: Int, val effectColorB: Int,
    val effectTilt: Float, val effectAutoTilt: Boolean, val effectIdleAnim: Boolean,
    val effectBloom: Float, val effectOnEvent: String,
    val status: String, val notes: String
) {
    companion object {
        fun fromJson(ctx: Context, json: String): Theme?   // parse one theme object
        fun toJson(t: Theme): String
    }
}

// in ThemeStore.kt
internal object ThemeStore {
    fun active(ctx: Context): Theme?          // the applied theme, or null
    fun setActive(ctx: Context, t: Theme?)     // write to Prefs.KEY_THEME_JSON, notify listeners
    fun saved(ctx: Context): List<Theme>       // KEY_THEMES_JSON
    fun save(ctx: Context, list: List<Theme>)
    fun importJson(ctx: Context, json: String): Int   // returns how many themes were imported
    fun builtIn(): List<Theme>                // the 20 catalogue themes, ported
    fun support(t: Theme): List<String>       // which parts need new code (mirrors canvas themeSupport)
}
```

Agent B (windows) must expose:

```kotlin
// in WinDesign.kt
internal class WinDesign(h: EdgeView, val spec: WindowSpec) : Win(h, spec.id, spec.elementCount)

// in WindowsConfig.kt  (add to the existing object; do not change existing members' behaviour)
internal data class WindowSpec(
    val id: String, val name: String, val base: String, val on: Boolean,
    val elements: List<WinElement>, val idleArt: String, val notes: String
)
internal data class WinElement(
    val type: String, val r: Float, val a: Float, val a0: Float, val a1: Float,
    val sizeDp: Float, val text: String, val icon: String, val action: String,
    val color: Int, val style: String, val value: Float
)
```
`WindowsConfig` keeps its existing `ALL` / `load` / `enabled` / `save` / `label` / `icon` behaviour
exactly as it is (the built-in windows must keep working). Add:
```kotlin
fun custom(ctx: Context): List<WindowSpec>     // KEY_CUSTOM_WINS
fun saveCustom(ctx: Context, list: List<WindowSpec>)
fun enabledWithCustom(ctx: Context): List<String>  // built-ins + custom ids, in order
```

## Definition of done (all agents)

1. `.\gradlew.bat assembleRelease -q` succeeds in your working copy (exit 0).
2. No emoji in anything you wrote.
3. You did not modify any file outside your ownership list. If you had to, you wrote a `PATCHES.md`.
4. Your final report states: files changed, the public API you added, what you verified, what you
   could NOT verify (e.g. on-device behaviour), and any assumption you made.
