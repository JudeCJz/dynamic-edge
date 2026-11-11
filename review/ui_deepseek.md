[0m
> build · deepseek-v4-pro
I'll start by exploring the project structure and reading the key files you mentioned.
[0m✱ [0mGlob "**/SKILL.md" [90m0 matches[0m
[0m✱ [0mGlob ".agents/**/*" [90m0 matches[0m
[0m✱ [0mGlob "**/*.kt" [90m44 matches[0m
> build · deepseek-v4-pro
[0m→ [0mRead . 
[0m$ [0mGet-ChildItem -Path "D:\VS\PROJECTS\FREE WORK\dynamic-edge" -Recurse -Force -Filter "SKILL.md" | Select-Object FullName; Get-ChildItem -Path "D:\VS\PROJECTS\FREE WORK\dynamic-edge\.agents" -Recurse -Force -ErrorAction SilentlyContinue | Select-Object FullName
FullName                                                                  
--------                                                                  
D:\VS\PROJECTS\FREE WORK\dynamic-edge\.agents\skills\dynamic-edge\SKILL.md
D:\VS\PROJECTS\FREE WORK\dynamic-edge\.agents\skills                      
D:\VS\PROJECTS\FREE WORK\dynamic-edge\.agents\skills\dynamic-edge         
D:\VS\PROJECTS\FREE WORK\dynamic-edge\.agents\skills\dynamic-edge\SKILL.md
> build · deepseek-v4-pro
[0m→ [0mRead .agents/skills/dynamic-edge/SKILL.md 
[0m→ [0mRead app/src/main/java/com/dynedge/FlatSlider.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/Prefs.kt 
> build · deepseek-v4-pro
[0m→ [0mRead app/src/main/java/com/dynedge/MainActivity.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/WindowsConfig.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/QuickEditorActivity.kt 
> build · deepseek-v4-pro
[0m→ [0mRead app/src/main/java/com/dynedge/AppPickerActivity.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/Looks.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/ClipboardActivity.kt 
[0m✱ [0mGlob "app/src/main/res/drawable/*.xml" [90m57 matches[0m
> build · deepseek-v4-pro
[0m→ [0mRead HANDOFF.md 
[0m✱ [0mGrep "base|gradient|accent|Accent|Gradient|BASE|KEY_BASE|KEY_GRADIENT" [90min app/src/main/java/com/dynedge/EdgeView.kt · 48 matches[0m
> build · deepseek-v4-pro
[0m→ [0mRead review 
> build · deepseek-v4-pro
[0m→ [0mRead review/opencode.md 
[0m→ [0mRead review/SPEC_v1_4.md 
> build · deepseek-v4-pro
I've read the skill file, `Prefs.kt`, `MainActivity.kt`, `FlatSlider.kt`, `QuickEditorActivity.kt`, plus `WindowsConfig.kt`, `AppPickerActivity.kt`, `ClipboardActivity.kt`, `Looks.kt`, the `review/` critique and `SPEC_v1_4.md`. Here is the concrete redesign spec.

---

# Dynamic Edge — Settings Redesign Spec

**One decision to flag first:** `SPEC_v1_4.md` item 22 said *"no accent colour in the settings chrome"* (neutral grey toggles/sliders/rings). Your new requirement reverses that — the accent now **is** the settings chrome. Everything below assumes the accent is used for toggles, sliders, selection rings and headers, with grey only for surface/text.

---

## 1. Information architecture

**Recommendation: a home list of categories opening sub-pages** (not one long scroll, not sticky chips).

Rationale: 27 settings + 3 external activities cannot live in one flat scroll without becoming the "jumbled mess" you're fixing. Sticky category chips on a single scroll still gives you one 3,000 dp page — you just index into it faster. A home list + sub-pages gives each setting a 48 dp row, real breathing room, and a clear "where do I find X?" mental map. It also matches the existing pattern (Quick layout / Clipboard / Apps are already separate activities).

### Home screen (MainActivity)

```
┌─────────────────────────────────────────────┐
│ Dynamic Edge                    ● Overlay on │  ← title + live status chip
│                                             │
│  [ live preview strip, ~120 dp ]            │  ← see §4
│                                             │
│  APPEARANCE                                 │  ← section header (accent, uppercase)
│  ▸ Colour                       #FF4B3E ·   │  ← icon + label + value summary + chevron
│  ▸ Style & idle                  Glyph ·    │
│  BEHAVIOUR                                  │
│  ▸ Size & motion                48 · 216    │
│  ▸ Windows                      4 on        │
│  CONTENT                                    │
│  ▸ Quick layout                 —           │
│  ▸ Clipboard                    n items     │
│  ▸ Apps                         4 apps      │
│  SYSTEM                                     │
│  ▸ Permissions                  Done / n ·  │
│                                             │
│  Reset all                                  │  ← footer text row (accent)
└─────────────────────────────────────────────┘
```

Category rows: 56 dp tall, Lucide icon (24 dp, accent-tinted) + label (15 sp) + value summary (14 sp secondary) + chevron.

### Sub-pages and where every setting goes

| Page | Settings | Notes |
|---|---|---|
| **Colour** | Accent, Base colour, **Gradient colour(s)**, **Gradient on/off + strength**, Fill style, Rim, Glow, Opacity | The "edge fill" page. Gradient lives here as its own group. |
| **Style & idle** | Idle design, Music visualizer + style, Beat reaction + Beat bounce, Live activities, **Flare** | The "closed bean" page. Flare moved here because it is an idle/open-outline property the user reads as styling. |
| **Size & motion** | Corner size (idle), Open size, Speed, Bounce, Swipe sensitivity, Haptics | Shape geometry + physics. |
| **Windows** | Order + on/off per window (8 windows) | Existing drag list, restyled. |
| **Quick layout** | — | Existing `QuickEditorActivity`, restyled. |
| **Clipboard** | — | Existing `ClipboardActivity`, restyled. |
| **Apps** | — | Existing `AppPickerActivity` + app list, restyled. |
| **Permissions** | All 8 permission rows, Live activities, Reset looks, Reset all | Setup + dangerous/irreversible actions grouped last. |

All 27 settings placed. Live activities intentionally sits in **Style & idle** (it changes what the *idle bean* shows), not in Permissions — the permissions page is for fix/risk rows only.

---

## 2. Visual system

### Exact colours (name: *Graphite*)

| Token | Hex | Use |
|---|---|---|
| `bg` | **`#2C2C2E`** | App background — clearly grey, never black. |
| `divider` | **`#48484A`** | 1 dp hairline between rows. |
| `track` | **`#3A3A3C`** | Slider track, off-toggle track, inactive selection ring, empty-state fill. |
| `textPrimary` | **`#F5F5F7`** | Row labels, title, values that are "on". |
| `textSecondary` | **`#A6A6AD`** | Value summaries, off-window labels, chevrons. |
| `textTertiary` | **`#6E6E73`** | Hints, captions. |
| `accent` | user accent (default **`#FF4B3E`**) | Toggles ON, slider fill, selection ring, section headers, action text, category icons. |
| `onAccent` | computed | Icon/text drawn on an accent fill: `#FFFFFF` when accent is dark, **`#0E0E0F`** when accent is light (e.g. cream `#F2EDE4`). |
| `previewBackdrop` | **`#C7C7CC`** | Light backdrop behind the live overlay shape during preview only. |

Compute `onAccent` with relative luminance `L = 0.299R + 0.587G + 0.114B`; if `L/255 > 0.5` use dark, else white. (Red/orange/green/blue/purple/pink → white; cream → dark.)

These are single flat surfaces — **no card boxes**. `divider` hairlines are the only separators.

### Type scale

| Role | Size / weight | Case / tracking |
|---|---|---|
| App title | 22 sp / semibold | normal |
| Section header | **12 sp / semibold** | **uppercase, +0.08 em** |
| Row label | 15 sp / regular | normal |
| Row value / summary | 14 sp / regular | normal (tabular for numbers) |
| Slider value | 14 sp / regular | tabular |
| Caption / hint | 12 sp / regular | normal |

### Spacing

- Horizontal margin: **16 dp** everywhere (rows, sliders, headers).
- Section header padding: top **24 dp**, bottom **8 dp**.
- Row min height: **48 dp** (touchable rows); **44 dp** is the floor for dense lists (per your ≥44 dp rule) — sliders and toggles already meet this.
- Divider: 1 dp, inset **16 dp** left (no-icon rows) / **56 dp** (icon rows).
- Slider track: **6 dp** tall, touch cell **44 dp** tall.
- Swatch cell: **44 dp** square, swatch dot **28 dp**.

### Row anatomy

- **Simple row** (navigation): `[icon 24dp] [label]  … [value 14sp secondary] [chevron]`, 48 dp.
- **Switch row**: `[label] … [FlatToggle 46×44]`, 48 dp.
- **Slider row**: label + live value on one line, knobless slider below.
- **Swatch / picker row**: label on top, a horizontal wrap/grid of 44 dp cells (28 dp swatches or mini bean previews).

### Control styles (all accent-driven)

- **FlatToggle** (restyle existing): OFF track `#3A3A3C` / thumb `#A6A6AD`; ON track `accent` / thumb `onAccent`. 28 dp visible switch centred in the 44 dp cell.
- **FlatSlider** (restyle): track `#3A3A3C`, fill `accent`. Keep it **knobless** (it's the flat signature); the fill's leading edge is the affordance and the live value text confirms position.
- **Selection ring**: active choice gets a 2 dp `accent` ring (currently grey) — this is what makes accent read as "selection".
- **Section headers**: accent-coloured uppercase labels (your explicit ask).
- **Action text** ("Fix", "Reset", "Add app", "Done"): `accent`. "Done" uses a small accent check glyph + `textSecondary`, so no green/red noise.

---

## 3. Colour picker design

One reusable **`ColorPickerActivity`** (framework-only, custom Views) is launched with extras (`title`, `initial`, `presets`, `requestCode`) and returns an ARGB `Int`. It is used by: **Accent**, **Base colour**, **Gradient start colour**, **Gradient end colour**.

### Layout (top → bottom)

1. **Header** — back chevron (48 dp), title (e.g. "Accent"), "Done" (accent, 48 dp).
2. **Large preview swatch** — a rounded square (~64 dp) showing the current colour, at top-left, with the hex value as caption.
3. **SV square** (saturation × value) — height `min(screenWidth, 280 dp)`:
   - Base layer = the pure hue colour.
   - Overlay 1 = white→transparent horizontal (saturation).
   - Overlay 2 = transparent→black vertical (value).
   - Crosshair = `onAccent` ring over a white ring at the current (s,v).
   - Drag anywhere = jump + drag; no per-frame allocation (reuse the two `LinearGradient` paints, `Path`, `RectF`).
4. **Hue bar** — 24 dp tall horizontal `LinearGradient` across red→yellow→green→cyan→blue→magenta→red; 24 dp thumb ring.
5. **Presets row** — the relevant `IntArray` (`ACCENTS` / `BASES` / gradient presets) as 44 dp cells; tapping jumps the whole picker. A trailing "custom" cell (Lucide `ic_sliders`/droplet on a rainbow disc) is the entry point that *reveals* the full HSV picker if you choose a collapsed variant.
6. **Recent colours row** (optional) — last 8 picked, persisted in `Prefs`.

Convert with `Color.HSVToColor`; store as ARGB int in `Prefs`.

### Gradient model (satisfies "custom gradient colour(s) + on/off + strength")

The edge gradient is radial from the corner (`core → base`). Give it **two explicit stops**:

- **Start colour** (core, near corner) — default `#FF4B3E`-warm.
- **End colour** (outer) — **defaults to the Base colour** and stays synced unless overridden.
- **Strength** slider (0–100) = how far the core blends toward the end (the existing `KEY_GRADIENT`).
- **On/off** switch — writes `KEY_GRADIENT_ON`; strength `0` also reads as off (keeps the SPEC_v1_4 "gradient off means off" rule intact).
- **Gradient presets** (6 two-stop combos): *Ember* (`#FF4B3E→#000`), *Sunset* (`#FF9F0A→#1A1424`), *Ocean* (`#0A84FF→#0E1A24`), *Aurora* (`#BF5AF2→#0E1A24`), *Mono* (`#8E8E93→#111114`), *Match accent* (accent→base). Presets set both stops + a suggested strength.

### New `Prefs.kt` keys

```kotlin
KEY_GRADIENT_ON      // Boolean, default true
KEY_GRADIENT_FROM    // Int ARGB, core colour (default = accent)
KEY_GRADIENT_TO      // Int ARGB, end colour (default = base, synced)
KEY_RECENT_COLORS    // String, "|"-joined ARGB ints, max 8
```

Existing `KEY_ACCENT` / `KEY_BASE` become fully custom-colourable (they already store Int ARGB; only the picker UI was preset-locked).

---

## 4. Live preview mechanism

There is **no fake mock preview** in settings. The real overlay *is* the preview — it floats above the grey settings background in the bottom-right corner.

1. **Passive mini-previews** stay: `IdlePreview` (in `FlatSlider.kt`) already renders idle/fill/visualizer styles with the *same* `Looks` code the widget uses. Restyle only: grey `bg`, `accent` selected ring. These are pickers, not the live preview.

2. **Live overlay preview** (the important one):
   - `EdgeService` already reloads on `SharedPreferences("edge")` change → extend that listener to call a single **`applyLooks()`** (accent, base, gradient from/to + strength, fill, rim, glow, opacity, idle style, viz style, flare) so *every* pref change is reflected in the floating widget immediately.
   - `MainActivity` writes `KEY_PREVIEW = "open"` while it is foreground (and `"idle"`/`"open"` transiently during slider drags as it does today), clearing it in `onPause`.
   - `EdgeView` draws the light `previewBackdrop` (`#C7C7CC`) **behind the shape, in the overlay window** (SPEC_v1_4 item 25) only while `KEY_PREVIEW` is set, so the dark edge is visible over any wallpaper.
   - A small **"Preview"** chip on the home header (and Colour/Style pages) forces `KEY_PREVIEW = "open"` so the edge stays expanded while the user browses.

   Because the settings background is now `#2C2C2E` (grey), the dark shape already contrasts — the backdrop is a fallback for light wallpapers, not the primary mechanism.

3. **Engine change** in `EdgeView.kt` (~lines 330–334): replace the hardcoded warm mix
   `warm = mixColor(0xFF2E1B1A, accent, 0.25f)` with `warm = Prefs.gradientFrom(c)`, and the stop sequence `core → base` becomes `gradientFrom → gradientTo`; `gradientActive = KEY_GRADIENT_ON && fillId == "gradient" && strength > 0`.

---

## 5. Prioritised implementation plan

Constraints honoured throughout: framework Views only, no AndroidX/Compose, no per-frame allocation, Lucide `VectorDrawable` icons, no emojis, ≥44 dp targets.

### P0 — Theme foundation (unblocks everything)
1. **`Theme.kt` (new)** — the `Graphite` tokens (§2) as constants + `dp()`/`sp()` helpers + `onAccent()` + a `statusBar/navBar` grey.
2. **`FlatSlider.kt`** — restyle `FlatToggle`/`FlatSlider`/`IdlePreview` to theme tokens; selection ring → accent; add an optional `valueColor`/accent setter already present.
3. **`Ui.kt` (new)** — shared builders (`row()`, `switchRow()`, `sliderRow()`, `swatchRow()`, `pickerRow()`, `header()`, `page(title, content)`), extracted from `MainActivity`'s private helpers so all four activities use one system.

### P1 — Information architecture (the actual "unjumbling")
4. **`SettingsPages.kt` (new)** — declarative builders for **Colour**, **Style & idle**, **Size & motion**, **Windows**, **Permissions** pages (each returns a `View`, reads/writes `Prefs`/`WindowsConfig`).
5. **`MainActivity.kt`** — rebuild as home list (§1) + preview strip + navigation into pages; move all section-building code out.

### P2 — Colour picker + gradient
6. **`Prefs.kt`** — add `KEY_GRADIENT_ON/_FROM/_TO`, `KEY_RECENT_COLORS`, gradient presets, helpers.
7. **`ColorPickerActivity.kt`** + **`ColorPickerView.kt` (new)** — HSV picker per §3, wired into Accent/Base/Gradient rows.

### P3 — Live preview (engine)
8. **`EdgeService.kt` / `EdgeView.kt`** — `applyLooks()` on pref change, gradient from/to, `previewBackdrop` drawn in-window.

### P4 — Theme the remaining activities
9. **`QuickEditorActivity.kt`**, **`AppPickerActivity.kt`**, **`ClipboardActivity.kt`** — swap hardcoded blacks/`#1C1C1E`/`#8E8E93` for `Theme` tokens; accent for active cells/actions.

### P5 — Reset + verification
10. Extend `Prefs.resetLooks()` to cover the new gradient keys; wire "Reset all" footer; verify on the vivo I2202 with `adb` + bottom-right screenshot crops (per `HANDOFF.md`).

**Sequence note:** P0 → P1 ship the visual overhaul and structure (the visible "not a mess anymore" milestone); P2 is the only *new feature* (custom colours); P3 is required for the preview to reflect gradient colours but not for the rest of the UI; P4 is cosmetic follow-through.

---

Want me to turn this into the actual implementation (starting with `Theme.kt` + `Ui.kt` + the `MainActivity` home list), or keep it as a written spec for now?
