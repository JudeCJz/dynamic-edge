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
