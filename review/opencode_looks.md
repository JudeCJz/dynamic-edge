# Dynamic Edge — Looks & Motion Report

## 1. Why the current idle designs fail (blunt)
| Style | Code (`drawIdle`) | Failure |
|---|---|---|
| `plain` (0) | draws nothing | Not a design; reads as a dead black bite missing pixels. Zero affordance/signature. |
| `dot` (1) | accent circle `r=2dp` at `(-0.30*idle,-0.30*idle)` | 4 dp speck on ~48 dp = 0.7% area. Off-centroid, no alignment to the curve, looks like a stuck pixel/notification artifact, not intent. |
| `arc` (2) | cream stroke `1.4dp`, `r=0.70*idle`, sweep 66° from 192° | Thin, low-contrast (#F2EDE4 @85%) hairline floats with no relation to rim or centroid; feels like debug geometry, disappears on warm wallpapers. |
| `battery` (3) | track/arc `2.6dp` `r=0.66*idle` 70° + bolt | Track is a washed 22% white ghost; data arc competes with (absent) rim language; bolt drawn at centroid but scaled by `u=0.07*idle` (~3.4 dp) — illegible. Functional but visually cluttered. |
| `clock` (4) | 10sp bold white at centroid | Text at ~10sp on a curved 48 dp wedge = cramped, anti-aliased mush; ignores polar form, reads like a misaligned widget label. |

**Systemic:** marks are placed ad-hoc (0.30/0.66/0.70 factors) with no shared grid, no optical centering on the quarter-disc centroid `(cx=cy=-0.4244*idle)`, inconsistent alpha/colour (cream vs white vs accent), and zero material/edge logic. The bean is “black blob + sticker”.

## 2. 8 new idle designs (48 dp bean, origin = screen corner, +x left, +y up into screen; angle `a` 0° along bottom edge → 90° along right edge)
| # | Name | Exact visual (polar `r` from corner, `a` deg) | Motion (cheap) | Conveys |
|---|---|---|---|---|
| 1 | **Glyph Notch** (Nothing-ish) | 3 radial ticks centered on centroid arc: at `r=0.58*idle`, angles 35/45/55°, length 5 dp inward (stroke 1.8 dp, round cap), color `#FFFFFF@90%`, accent on center tick only (`accent@100%`). | Static. | Signature/brand mark. |
| 2 | **Island Pill** | Filled capsule along `a=45°`: center `(r=0.46*idle,a=45°)`, size 10×3.2 dp, corner radius 1.6 dp, `accent@95%`, 0.8 dp `#000@40%` inner-bottom shadow drawn as offset darker rect. | Static; scale to 1.04 on press (existing press spring). | Affordance/tap target. |
| 3 | **Orbit Dot** | Track: ring sector `r=0.62*idle`, `a=18°..72°`, stroke 1.2 dp `#FFF@18%`. Indicator: filled dot `d=3.2 dp` at `a=18°+54°*state` on that arc, `accent@100%`. Default state=0.5. | Event-driven: animate `state` 0→1 over 300 ms on open/close (reuse page/spring). No idle frames. | Subtle state/progress hint. |
| 4 | **Twin Slits** (Braun) | Two concentric arc slits: `r=0.54/0.68*idle`, each `a=28°..62°`, stroke 1.2 dp, `#FFF@55%` (inner) / `accent@70%` (outer), square caps. Precise 14 dp gap. | Static. | Instrument feel, premium restraint. |
| 5 | **Halo Core** | Soft core: filled circle at centroid `(r=0.424*idle,a=45°)` `d=6 dp` `accent@35%`; crisp core `d=2.4 dp` `#FFF@95%`. | Static (core drawn over glow paint already). | Focal point, calm beacon. |
| 6 | **Meter Arc** (watch) | Background arc `r=0.64*idle`, `a=20°..70°`, 2.0 dp `#FFF@16%`. Value arc same radius, `a=20°..20°+50°*v`, 2.0 dp `accent@90%`. `v`=battery or 1.0. | Event-driven on battery change broadcast only. | Battery/status at a glance. |
| 7 | **TE Grid** | 2×2 dot matrix centered on centroid: spacing 4.0 dp, dots `d=1.6 dp` at offsets (±2,±2) dp rotated 45° to align with `a=45°`; colors `#FFF@80%, accent@80%, #FFF@30%, accent@30%`. | Static. | Playful-technical identity. |
| 8 | **Eclipse** | Disc `r_disc=0.22*idle` centered at `(r=0.44*idle,a=40°)` filled `#FFF@92%`; “shadow” disc same size at `(r=0.44*idle,a=50°)` filled shape base (draw over) → 1.6 dp accent crescent remains. | Static. | Light/material metaphor, iconic silhouette. |

*All use centroid-relative placement, ≤3 primitives, AA on, no per-frame work.*

## 3. 8 shape fill styles (replace/complement gradient), legible under white icons
| # | Style | Exact cheap Canvas recipe | Accent reaction | Perf | Ship? |
|---|---|---|---|---|---|
| 1 | **Concentric Grooves** | Pre-render once per size to layer/Bitmap: loop `r=12dp..R step 8dp`, `drawArc(oval(r),180,90)` stroke 0.8 dp `#FFF@6%` + 0.8 dp `#000@18%` offset +0.8 dp in r (highlight/shadow). | Grooves stay neutral; base tint = `mix(#000, warm(accent), strength)`. | Drawn once on geometry change; 0/frame. | **Yes** |
| 2 | **Polar Dot Matrix** | `BitmapShader` tile 8×8 dp: clear + dot `d=1.2 dp` center `#FFF@5%`. Paint with shader; `drawPath` clipped to shape in unit space. | Tile neutral; modulate dot alpha `4%..8%` by gradient strength. | 1 shader sample/pixel; cached. | **Yes** |
| 3 | **Radial Sunburst** | 36 lines from corner: for `i` in 0..36 `a= i*2.5°`, `drawLine((6dp,a),(R,a))` stroke 0.6 dp alternating `#FFF@4%/#FFF@0%`. Path built once per size. | Every 6th line `accent@8%`. | One path per size, 0/frame. | **Yes** |
| 4 | **Brushed Metal** | `BitmapShader` of 2×64 px tile: horizontal noise-ish streaks (prebaked: random alpha 2–7% white per row). Rotate shader -45° via local matrix once. | Multiply tint via base warm color. | 1 sample/pixel. | No (fights accent warmth) |
| 5 | **Carbon Weave** | `BitmapShader` 8×8 dp: two 4 dp diagonal quads `#FFF@5%`/`#FFF@2%` (twill). | Neutral weave; base provides color. | 1 sample/pixel. | **Yes** |
| 6 | **Frosted Tint** | Keep radial gradient but lift stops: add top-layer `drawPath` fill `#FFF@4%` + inner rim stroke 1 dp `#FFF@10%` at `r=R-1dp`. | Strong accent shows through warm core. | 2 draws/frame (cheap). | No (too close to current) |
| 7 | **Topo Lines** | 5–6 irregular concentric-like arcs: `r = 30+22*i + 3*sin(3a+i) dp`, stroke 0.7 dp `#FFF@5%`. Precompute paths per size. | One contour `accent@10%`. | Paths once per size. | No (busy at 48 dp) |
| 8 | **Noise Grain** | `BitmapShader` 32×32 px blue-noise tile `#FFF` alpha 3–5%, `drawPath` over gradient with `PorterDuff.Mode.SCREEN` (or just alpha). | Neutral. | 1 extra sample/pixel; static. | No (banding fix only, subtle) |

**Ship:** 1 Grooves + 2 Polar Dots + 3 Sunburst + 5 Carbon (all tile/path-cached, zero per-frame alloc, <0.2 ms overdraw on 440 dpi).

## 4. Music beat reaction (premium, not tacky)
- **Input:** `viz.levels[0]=bass, [1]=low-mid, [2]=mid, [3]=high` (0..1). `energy = 0.5*bass + 0.3*mid1 + 0.2*mid2` clamped 0..1.
- **Smoothing:** attack `beat += (energy-beat)*0.40` when rising; release `beat += (energy-beat)*0.12` when falling (per tick, ~30 Hz). One float, no history alloc.
- **Idle (closed) bean:** scale `1.00..1.04` (max +1.9 dp) via existing press/transform multiply; rim alpha `base .. base+0.18*beat`; glow alpha `base .. base+0.22*beat`; if Halo/Glyph, core/tick brightness `+20%*beat`. **No position bounce.**
- **Open panel:** outer media ring stroke `5..8 dp` & alpha `0.38..0.55` with `beat` (already near current) but cap at 0.55; art disc scale `1.00..1.03` on bass only (`0.5*bass`); section crossfade/rotation untouched. **Limits:** scale ≤1.04 idle / ≤1.03 art, alpha delta ≤0.25, glow radius unchanged. Disable when `!playing`.

## 4b. 6 music visualizer styles (radial quarter-circle, 30 fps max while playing)
| # | Name | Geometry & mapping | Smoothing | Cost |
|---|---|---|---|---|
| 1 | **Rim Spectrum** (idle+open) | 12 bars as arc segments on `r=0.82*R` (idle: `R=idle`) , `a = 12° + i*5.5°`, each sweep 3.8°, radial thickness `1.5dp + 5dp*band[i%6]`, stroke round-cap, color `#FFF@80%` / accent on peaks. Open: same at `r=R_open-12dp`. | 6 bands, `v += (t-v)*0.35` up / `*0.14` down, 30 fps tick. | 12 `drawArc`, 0 alloc. |
| 2 | **Radial Rays** (idle) | 8 rays from `r=0.30*idle` to `0.30*idle + (0.08..0.38)*idle*band[i%4]` at `a=18°+i*7.7°`, stroke 1.4 dp, accent/white alt. | 4 bands, 0.4/0.12 smoothing. | 8 lines. |
| 3 | **Ripple Rings** (open edge) | 3 concentric arcs at `r=R-10, R-18, R-26 dp`, `a=10°..80°`, stroke `(1+2*band[i]) dp`, alpha `0.15+0.55*band[i]`. | 3 bands (bass/mid/high), 0.3/0.1. | 3 arcs. |
| 4 | **Dot-Matrix Meter** (idle) | 4 radial columns at `a=30,40,50,60°`, each 4 dots outward from `r=0.36*idle` step 4.5 dp, dot `d=1.8 dp`; lit count = `ceil(4*band)`, lit=`accent/#FFF`, unlit=`#FFF@12%`. | 4 bands, 0.35/0.15. | 16 circles. |
| 5 | **Rim Waveform** (open) | Polyline along `r = (R-14dp) + 4dp*wave(a)` sampled 24 pts, `a=8°..82°`, `wave` = interpolated 8-band values, stroke 1.6 dp `accent@75%`. | 8 bands, 0.3/0.12; reuse one Path. | 1 path, 24 pts. |
| 6 | **Liquid Bulge** (idle) | Modulate shape radius: sample 16 angles, `r(a)=idle*(1 + 0.035*bass*sin^2 weight near a=45°)`, rebuild `path` each viz tick (playing only) else static cached. | bass only, 0.45/0.15. | Path rebuild @30 fps only while playing (acceptable). |

## 5. Panel design system (short)
- **Spacing scale (dp):** 4 / 8 / 12 / 16 / 24 (base 4). Ring gaps ≥8 dp, edge inset ≥10 dp from curve.
- **Icon sizes:** primary 24 dp viewport (stroke 2, round caps/joins), secondary 20 dp, glyph 18 dp; touch target ≥44 dp (`itemHit`).
- **Stroke widths:** hairline 0.8, standard 1.2, emphasis 1.8–2.0, arc/slider track 3.0, progress 3–5 dp. Round caps on meters.
- **Accent use:** only active/on state, primary progress/value, focus dot, and ≤1 signature mark per idle style. Never large filled backgrounds except active toggle disc.
- **Typography on arcs:** title 14sp medium `#FFF`, artist 12sp regular `#8E8E93`, meta/time 10sp `#8E8E93`. Max arc text = 80% of arc length, `TextUtils.TruncateAt.END`, centered offset; no text <10sp except debug-off. All text upright-readable (avoid bottom-half inversion).