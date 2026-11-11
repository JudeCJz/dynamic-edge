# Settings redesign spec (Claude Plan agent, 2026-11-10) - summary of key decisions

IA: Home list of 7 categories opening in-activity sub-pages (Look, Size, Windows, Apps, Live, Feel, Permissions);
max depth 2 (+ colour picker page). Home: title, 200 dp hero preview (tap toggles idle/open), status row only when
needed, 72 dp category rows (Lucide icon, title, short summary, chevron).

Look page (sticky 168 dp in-app preview): Colour (accent swatches + Custom; Edge colour row -> picker), Gradient
(switch; Strength, Inner colour [Auto = follows accent], Outer colour [Edge = linked]), Surface (Opacity, Texture
dots switch), Details (Border, Glow, Corner glyph switch), Reset look with 4 s Undo snackbar.
Size page: Corner size, Open size, Flare - uses the REAL overlay preview; sliders placed high so the open shape never
covers them. Windows page (grip, icon, label, switch; Quick layout, Clipboard history). Apps page (rows with 44 dp X,
Add app). Live page (Live activities; Visualizer + 4 animated style cells; Beat reaction + Beat bounce). Feel page
(Speed, Bounce, Swipe sensitivity, Haptics, Reset feel). Permissions page (needed first with Fix, granted with check).

Visual system: bg #232427, surface #2C2D31, surfaceHigh #3A3B40, divider #34353A, text #F1F1F3 / #A6A7AD / #707176,
ripple white 10%. accentUi = user accent lightened until >= 3:1 on bg; onAccent #141416 or white by luminance.
Accent only for state (section labels, switch on, slider active, selection rings, text buttons, needed status).
Type: 26/20 sp titles, 13 sp sentence-case accent section labels, 16 sp rows, 14 sp values, 12 sp captions.
Spacing 4..32 grid, 20 dp side margin, no cards/borders, no row dividers; page bottom padding = idleDp + 24 dp.
Rows: nav 56 dp, home 72 dp, switch 56 dp (whole row toggles), slider 76 dp (label+value line, 44 dp strip), swatch
cells >= 44 dp with 32 dp dots. Switch 52x32 track; slider 4 dp track, 20->24 dp thumb, default tick, haptics.

Colour model: KEY_BASE (edge solid), KEY_GRAD_ON, KEY_GRADIENT strength, KEY_GRAD_INNER (0 = Auto), KEY_GRAD_OUTER
(0 = Edge), KEY_PATTERN none|dots (replaces KEY_FILL, one-time migration), KEY_RECENT_COLORS.
Shader: outer = OUTER?:base; inner = INNER?:mix(#2E1B1A, accent, .25); core = mix(outer, inner, s); stops
[core, mix(outer,core,.55), mix(outer,core,.24), outer] at [0,.38,.72,1]. Gradient + dots allowed together.
Picker page: preview 140 dp, target tabs Accent/Edge/Inner/Outer, SV plane 168 dp (ComposeShader), hue bar 44 dp
strip, hex field + before/after chip, presets per target, recents row, caption for light edges. Writes throttled
to 50 ms during drag; in-app preview updates from view state every frame.

Preview: in-app EdgePreviewView (same drawing code as the overlay) on Home/Look/Live/picker; real overlay
KEY_PREVIEW only on the Size page. EdgeService prefs listener must include every new key.
Plan: P0 UiTheme.kt + restyled controls + MainActivity page stack + page files + Lucide icons; P1 colour features;
P2 in-app preview; P3 QuickEditor/Clipboard/AppPicker restyle, accessibility, device verification.
