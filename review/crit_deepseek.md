[0m
> build · deepseek-v4-pro
[0m→ [0mRead review/SPEC_v1_4.md 
[0m→ [0mRead review/LOOKS_DECISION.md 
[0m→ [0mRead .agents/skills/dynamic-edge/SKILL.md 
[0m✱ [0mGlob "app/src/main/java/com/dynedge/**/*.kt" [90m31 matches[0m
> build · deepseek-v4-pro
[0m→ [0mRead app/src/main/java/com/dynedge/EdgeView.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/EdgeService.kt 
> build · deepseek-v4-pro
[0m→ [0mRead app/src/main/java/com/dynedge/EdgeView.kt [90m[offset=1382][0m
> build · deepseek-v4-pro
[0m→ [0mRead app/src/main/java/com/dynedge/EdgeView.kt [90m[offset=2835][0m
> build · deepseek-v4-pro
[0m→ [0mRead app/src/main/java/com/dynedge/WinQuick.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/Win.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/WindowsConfig.kt 
> build · deepseek-v4-pro
[0m→ [0mRead app/src/main/java/com/dynedge/Spring.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/ArcSet.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/Arcs.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/TouchRegion.kt 
> build · deepseek-v4-pro
[0m→ [0mRead app/src/main/java/com/dynedge/IdleVisualizer.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/Tiles.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/IdleMediaWatch.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/LiveBus.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/MediaBridge.kt 
> build · deepseek-v4-pro
[0m→ [0mRead app/src/main/java/com/dynedge/MainActivity.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/QuickToggles.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/Prefs.kt 
> build · deepseek-v4-pro
[0m→ [0mRead app/src/main/java/com/dynedge/NotifFeed.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/ClipPaste.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/ClipStore.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/TimerControl.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/DeviceStats.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/Brightness.kt 
> build · deepseek-v4-pro
[0m→ [0mRead app/src/main/java/com/dynedge/AppShortcuts.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/AssistControl.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/QuickConfig.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/MediaListener.kt 
[0m→ [0mRead app/src/main/java/com/dynedge/FlatSlider.kt 
> build · deepseek-v4-pro
[0m✱ [0mGlob "app/src/main/AndroidManifest.xml" [90m1 match[0m
[0m✱ [0mGlob "app/build.gradle*" [90m1 match[0m
[0m✱ [0mGlob "gradle/libs.versions.toml" [90m0 matches[0m
> build · deepseek-v4-pro
[0m→ [0mRead app/src/main/AndroidManifest.xml 
[0m→ [0mRead app/build.gradle.kts 
> build · deepseek-v4-pro
[0m→ [0mRead app/src/main/res/xml/edge_service.xml 
[0m✱ [0mGlob "app/src/main/res/xml/*.xml" [90m1 match[0m
> build · deepseek-v4-pro
I've read the skill, spec, design decision, and all the Kotlin sources plus the manifest, gradle, and accessibility config. Here is the review.

---

# Dynamic Edge — review

Scope note up front: the repo is **mid-refactor and currently cannot compile**. `EdgeView.kt` is still the v1.3 monolith (`SECTIONS = 4`, hard-coded QUICK/MEDIA/CLIP/APPS, private everything), while `Win.kt`, `WinQuick.kt`, and `ArcSet.kt` already target a *new* `EdgeView` API (`h.fillP`, `h.arcPaint`, `h.tilePaint`, `h.ringPaint`, `h.accent`, `h.bandsOn`, `h.dp`, `h.rad`, `h.icon`, `h.warm`, `h.arcs`, `h.pressed`, `h.pressedWin`, `h.jumpTo`, `h.relayout`, `h.service`, `h.haptic`, `h.openClipboard`). None of those exist or are `private` in the `EdgeView` that's on disk. So nothing below about the "engine" compiles today — treat every Win/ArcSet reference as a compile error until the host is rewritten.

---

## 1. Reliability

**1.1 `addView` in `onServiceConnected` is unguarded → `BadTokenException` crashes the service.**
`EdgeService.kt:179` — `manager.addView(view, params)` is not wrapped, unlike every other WM call in the file (which are all `catch (e: Throwable)`). If the accessibility service is (re)bound while the display/WindowManager is not ready — which vivo's `com.vivo.abe` and other OEM "power savers" do when they kill and re-attach the process — this throws `WindowManager.BadTokenException` on the main thread, the exception propagates out of `onServiceConnected`, and the whole service dies with the widget gone. Every other window mutation here is swallowed; this one isn't.
Fix: wrap in `try { addView } catch (t) { edge = null; lp = null; return }` and re-create on the next bind; never let an exception escape `onServiceConnected`.

**1.2 No recovery from OEM background kill (vivo `com.vivo.abe`).**
`EdgeService` has no `onRebind`/re-create self-check and no user-facing "service died" signal. `onUnbind` (`EdgeService.kt:224`) calls `teardown()` and returns `super.onUnbind(intent)` → `false`, so once Funtouch force-stops the process the bean is gone until the user manually toggles accessibility or reboots. The `enabled_accessibility_services` setting survives, but the process does not.
Fix: on `onServiceConnected` (and on `ACTION_USER_PRESENT`) verify the overlay actually re-attached; if a bind happened without the window, surface a setup/restart prompt. Add a "Restart service" row in `MainActivity`, and an explicit setup note to disable vivo's background-power-optimization / add Dynamic Edge to the allowlist (`com.iqoo.secure` → background restrictions). There's no in-code fix for the kill itself, but silently dying is the actual bug.

**1.3 `density` is captured once and never invalidated → wrong geometry after a density/font/rotation change.**
`EdgeView.kt:70` `private val density = resources.displayMetrics.density` is a `val` bound at construction, and `rad`/`idle` (`EdgeView.kt:73-74`) are computed in `init`. `EdgeService.onConfigurationChanged` (`EdgeService.kt:214-222`) calls `collapseImmediately()` and `resizeWindow()`, but **never** `applySizes()`, so after any density change the widget renders and hit-tests at the stale pixel size (`maxOpenWidthPx` still returns the old `ceil(rad)`).
Fix: in `onConfigurationChanged`, call `applySizesFromPrefs()` (which recomputes `rad`/`idle` via `dp()`) and make `density` a getter (`get() = resources.displayMetrics.density`) instead of a `val`.

**1.4 Clipboard capture steals focus from the foreground app.**
`EdgeView.kt:1696-1704` `requestCapture()` → `host.setWindowFocusable(true)` (`EdgeService.kt:244-259`) makes an *accessibility overlay* focusable so `ClipPaste.capture()` can read the clipboard (`EdgeView.kt:683-694`). This closes the IME, can drop the current app's focus, and produces a visible focus flicker. On top of that, `setWindowFocusable` has a real race: if the focus change never lands within the 300 ms `captureTimeout`, the window is left focusable until the next focus event. This is the single most fragile interaction in the service.
Fix: read the clipboard without focus — use the `OnPrimaryClipChangedListener` on the service context or a tiny transparent `InputMethod`-style capture Activity; at minimum, add a hard guarantee that `FLAG_NOT_FOCUSABLE` is restored in a `finally`/timeout path.

**1.5 Receiver/lifecycle asymmetry: `onDetachedFromWindow` doesn't release idle services.**
`EdgeView.kt:672-681` `onDetachedFromWindow` removes callbacks but does **not** call `syncIdleServices()` / `mediaWatch.stop()` / `visualizer.stop()` / unregister `timeReceiver`/`powerReceiver`. It relies entirely on `release()` (`EdgeView.kt:633`) being called from `teardown()` *before* `removeView`. That ordering happens to hold today, but any future detach path (window manager removes the view on its own, or an OEM kills the surface) leaves the `Visualizer` (RECORD_AUDIO), the media-session listeners, and the broadcast receivers registered against a dead service context. `EdgeView` also registers `timeReceiver`/`powerReceiver` against `context` (the service), not the application context (`EdgeView.kt:739, 749`).
Fix: make `onDetachedFromWindow` do the full idle-source teardown; register all receivers/observers on `applicationContext`.

**1.6 `ClipStore` loads a potentially 1.2 MB JSON file on the main thread, and its executor is never shut down.**
`ClipStore.kt:30` `init { load() }` runs synchronously on the first `ClipStore.get()` — which happens on finger-down in `ensureSources()` (`EdgeView.kt:1495`) — and `load()` does `JSONArray(file.readText())` on the main thread (`ClipStore.kt:125-141`). 60 clips × 20 000 chars is a multi-MB parse = a jank spike exactly on the first expand. The single-thread executor (`ClipStore.kt:18`) is also never `shutdown()`, so the process holds a non-daemon worker forever.
Fix: move `load()` off the main thread (post to the IO executor and publish once ready), and make the executor daemon or shut it down on process death.

---

## 2. The reported bug: "windows go black when I swipe back and up"

Two independent defects in the exact code the report points at, both of which darken the panel during/after a page swipe. The first is the likely primary; the second amplifies "dark" into "black".

**Root cause A — the section alpha is `contentAlpha * pageFade(d)` for *every* window, so both neighbours dim to ~55% mid-swipe.**
`EdgeView.kt:2033` `n.setAlpha(a * fa)`, where `fa = pageFade(i - pos)` (`EdgeView.kt:2049-2052`). `pageFade` is `smoothstep(1 - |d|/0.85)`, i.e. **0.55 at |d| = 0.5**, and exactly 0 past 0.85. During a back-swipe (pos 1 → 0) the incoming window goes 0 → 0.55 → 1 while the outgoing goes 1 → 0.55 → 0; at the midpoint **both** render at ~55% opacity. The open panel's fill is essentially black — the radial gradient's warm core is a tiny radius relative to the expanded `rad` (`buildShapeShader`, `EdgeView.kt:197-205`, stretched `r = max(dw,dh)*1.15/GR` at `1950`) — so the tiles/text carry all the contrast, and cutting both to 55% over that fill reads as "the panel went dark/black" mid-swipe. The fast page spring (stiffness 380, damping 0.86) overshoots `pageS.value` after release, so the dip persists briefly "after" the swipe too.
Fix: keep the settled window fully opaque while it fades its neighbour:
```kotlin
val active = Math.round(pos).coerceIn(0, SECTIONS - 1)
val fa = if (i == active) 1f else pageFade(d)
```
(and clamp `pos` to `[0f, SECTIONS-1f]` before computing `d`, since `pageS` overshoots and `d` is fed to `setRotationZ` unclamped). Optionally use a one-sided fade so only the *outgoing* window dims.

**Root cause B — `hasOverlappingRendering() = false` lies, so HWUI composites the translucent layers without an alpha layer and they render black.**
`EdgeView.kt:660` `override fun hasOverlappingRendering(): Boolean = false`. This view demonstrably draws overlapping translucent content: the gradient + semi-transparent glow (`glowPaint`), the `setAlpha(a*fa)` compositing `RenderNode`s, and an animated view alpha in `setHidden` (`EdgeView.kt:621/623`). Returning `false` tells HWUI it can skip the offscreen/alpha layer for this view, and on a `TRANSLUCENT` overlay window the alpha'd section layers then blend against an un-cleared buffer — classic "black" during/after alpha animations. This is the amplifier that turns the 55% dim above into a hard black.
Fix: delete the override (default is `true`), or return `true`. It's a premature "optimization" that breaks the very thing the paging layers depend on.

Do **both**; A is the visible dim, B is the reason it's black instead of merely dim.

---

## 3. Rendering performance

- **Hot path is genuinely allocation-free** and that's the one thing done right: `buildLivePath()` caches the shape behind `lastDw/lastDh/lastW/lastH/lastTip` (`EdgeView.kt:1943`), shaders are built once and positioned with `setLocalMatrix` (`EdgeView.kt:197-225`), and section content is re-recorded only when `dirty` (`EdgeView.kt:2020-2029`). Settled-open posts no frame callbacks, so idle is truly idle.
- **But the per-frame animation path still does 4 springs in closed-form.** `Spring.step` (`Spring.kt:30-66`) runs `exp`/`cos`/`sin` (≈16 transcendentals/frame for 4 springs) on every `doFrame` at 120 Hz. It's "no allocation," but it's not cheap; the `allSettled` tolerances (`EdgeView.kt:1890-1892`, page 0.002 px / 0.05 px/s) drag the settle window out. Consider fixed-point or an explicit `Math.exp`-free substep for the fast open/close, or loosen the position epsilon.
- **`drawPageBars` + `drawClose` are redrawn on the main canvas every frame** during any transition (`EdgeView.kt:2040-2045`), outside the RenderNode cache. They're few ops, but they could live in their own cached node (page bars change with `pos`, so at least the close X — which only depends on `a` — is trivially cacheable).
- **`LiveBus.sorted` allocates a sorted list on every call** (`LiveBus.kt:47-55`), and `top()`/`second()` run on the main thread on every live change. It's not per-frame, but it's avoidable churn in a "zero allocation" codebase.
- **`IdleVisualizer` runs the FFT `reduce` on the capture thread and `tick` at 30 Hz while closed+playing** (`EdgeView.kt:828-841`), which is correct throttling; but `beat` is fed with a fixed 0.4 lerp (`EdgeView.kt:836`) that is frame-rate-independent only by accident — if the 25 ms delayed callback drifts (vivo drops to 60 Hz after motion), the beat smoothing changes speed. Derive `beat` from `dt`, not a magic 0.4.

---

## 4. Gesture handling

- **`addMove` mutates the framework-owned `MotionEvent`.** `EdgeView.kt:2348-2356` does `ev.setLocation(rx, ry)` → `t.addMovement(ev)` → `ev.setLocation(lx, ly)`. Mutating and restoring a `MotionEvent` the framework will recycle is a known crash/`IllegalStateException` risk on OEM builds and is the kind of "works on the test phone, breaks elsewhere" hack. The stated reason (window resizes mid-gesture) is real, but the fix is to feed raw coords without touching the event — e.g. keep a reusable `MotionEvent` obtained via `ev.copy()` is still an allocation, so instead use `VelocityTracker` and accept local coords with a translation, or track velocity manually from `rawX/rawY` samples.
- **A vertical up-swipe is a dead gesture.** `EdgeView.kt:2613-2619`: paging needs `abs(dx) > abs(dy)`, closing needs `dy > abs(dx) && dy > touchSlop`. A swipe up (`dy < 0`, `|dy| > |dx|`) matches *neither* branch, so `dragMode` stays 0 and the gesture is silently dropped. This is exactly the "and up" half of the report. Decide what up should do (close, or page) and give it a branch; don't leave a recognized-but-ignored gesture.
- **`systemGestureExclusionRects` blocks back-swipe in the whole `rad×rad` square, not the shape.** `EdgeView.kt:1769-1771` sets `exclRect` to the full bottom-right square while open. The touch region is the bean path only (`EdgeView.kt:1743-1761`), so back-swipe is blocked in the transparent cut-out where the app below *is* touchable — inconsistent. The exclusion should be the shape region (or at least a quarter-disc), not the bounding square.
- **Hit-test while a page is mid-transition returns -1 for everything.** `EdgeView.kt:2553-2555` `if (abs(pos - s) > 0.2f) return -1`; combined with `handleDown` gating items on `contentAlpha > 0.5f` (`EdgeView.kt:2428`), taps during a settling page are eaten. Acceptable, but there's no re-hit on settle, so a fast tap right after a swipe is lost.

---

## 5. UI/UX and visual design

- **The idle bean renders the wrong designs — and five of the nine render nothing.** `EdgeView.kt:708` sets `idleStyleId = Prefs.IDLE_STYLES.indexOf(...)` against the new list `[none, glyph, pill, orbit, slits, halo, meter, grid, eclipse]`, but `drawIdle` (`EdgeView.kt:863-909`) only handles indices **1, 2, 3, 4** with the *old* meanings (1 = dot, 2 = arc, 3 = battery arc, 4 = clock). So: selecting "glyph" shows a lone dot; "halo"/"grid"/"eclipse" show **nothing**; "meter"/"orbit"/"slits" are all wrong. Worse, `syncIdleServices` (`EdgeView.kt:735, 745`) registers the clock receiver when `idleStyleId == 4` (now "slits") and the battery receiver when `== 3` (now "orbit"), so picking "slits" draws a live clock and "orbit" draws a battery meter. The settings `IdlePreview` (`FlatSlider.kt:280-339`) draws all 8 correctly — so the picker lies about what the bean will look like. This is the most visible correctness bug in the current build.
- **`MainActivity` swatches/pickers violate the 44 dp touch rule.** Accent dots are 28×28 dp (`MainActivity.kt:455`), and `addPicker` coerces preview size down to 30 dp (`MainActivity.kt:474`). The spec (`LOOKS_DECISION.md`) explicitly requires ≥44 dp. `FlatSlider` is also only 32 dp tall (`FlatSlider.kt:35`).
- **"Remove" in the apps list is a whole-row tap** (`MainActivity.kt:312-317`): tapping anywhere on an app row deletes it, with no confirmation. The label sits right-aligned but the entire row is the delete target — a one-tap foot-gun.
- **The settings screen is one giant `build()`** (`MainActivity.kt:78-173`) that rebuilds the whole `ScrollView` on "Reset looks" (`MainActivity.kt:139-147`) and re-creates every row on every toggle drag (`buildWindowRows`, `MainActivity.kt:510-548`). It works, but the drag-to-reorder (`attachDrag`, `MainActivity.kt:550-606`) drives `translationY` by hand and re-lays-out all rows on `ACTION_UP`, so rows visibly snap. Fine for a v1, but it reads as unfinished.
- **The ADB dump row** (`MainActivity.kt:236-258`) drops raw `adb` shell commands into a monospace `TextView` inside the user-facing settings screen. That's a debug affordance leaking into the product UI.
- **"Clipboard" is a tile that silently jumps windows** (`WinQuick.kt:162-170`) — tap opens the CLIPBOARD window if enabled, else the full history Activity. Non-obvious double behavior with no affordance showing which will happen.
- **`RECORD_AUDIO` for a corner widget** (`AndroidManifest.xml:15`, granted for `IdleVisualizer`) is a trust-killer and isn't clearly explained in-app beyond a setup row; the synthetic fallback (`IdleVisualizer.kt:104-106`) means the bars work without it, so it should be opt-in and de-emphasized.

---

## 6. Code structure / other

- **`EdgeView.kt` is a 2,894-line god class** holding geometry, springs, rendering, touch, media, clipboard, apps, live activities, idle designs, the visualizer, and three broadcast receivers. The refactor (`Win.kt`) is the right idea but is incomplete and, as noted, doesn't compile; and its host contract is a public grab-bag (`fillP`, `arcPaint`, `tilePaint`, `ringPaint`, `warm`, `icon`, `pressed`, `pressedWin`, `jumpTo`, `relayout`, …) rather than a narrow interface — it doesn't actually reduce coupling, it just relocates the drawing code.
- **Massive duplication** introduced by the half-refactor: `drawBtn` (`EdgeView.kt:2143` vs `Win.kt:160`), `addBand` (`EdgeView.kt:1204` vs `Win.kt:130`), `arcPath`, `place`, `polar`, `slotAngle` all exist twice and will drift.
- **`SECTIONS = 4` is hard-coded** (`EdgeView.kt:2854`) while `WindowsConfig.ALL` already lists 8 (`WindowsConfig.kt:10`). No `WinMedia/WinClip/WinApps/WinVolume/WinTimer/WinNotifications/WinDevice` files exist. If the engine still runs today, it ignores four of the eight windows the settings screen advertises.
- **`enum ToggleId` (`QuickToggles.kt:22`) vs string ids in `Tiles`/`QuickConfig`** — the SKILL contract says enum, the code moved to strings; two parallel ID systems.
- **`versionName = "1.0.0"`, `versionCode = 1`** (`build.gradle.kts:15-16`) while the skill claims v1.4 in progress — nothing enforces that these stay honest.
- **`MediaListener.isClockApp` matches package names with `contains("clock"|"timer"|"alarm")`** (`MediaListener.kt:132-135`) — misclassifies unrelated apps and will drive the idle bean with false "timer" activities.

---

## Top 10 — rework these first

1. **Fix the black-on-swipe bug** — clamp `pos`, keep the active window at full alpha (`fa = if (i == active) 1f else pageFade(d)`), and delete `hasOverlappingRendering() = false`. (`EdgeView.kt:2033, 2049, 660`)
2. **Make the repo compile** — finish or revert the `EdgeView`↔`Win*` refactor; the new files reference an API that doesn't exist.
3. **Guard `addView` in `onServiceConnected`** and add OEM-kill recovery + setup guidance (`EdgeService.kt:179`).
4. **Fix the stale idle-style rendering** — map the 9 `IDLE_STYLES` correctly in `drawIdle`/`syncIdleServices`; stop showing a dot for "glyph" and nothing for halo/grid/eclipse.
5. **Fix stale `density`/`rad` on configuration change** — recompute sizes in `onConfigurationChanged`; make `density` a getter.
6. **Fix gesture dispatch** — add a vertical-up branch, and stop mutating the `MotionEvent` in `addMove`.
7. **Take `ClipStore` off the main thread** — load on the IO executor, shut the executor down, LRU the cache.
8. **Enforce 44 dp touch targets** (swatches, pickers, slider) and stop the apps-row "tap anywhere = Remove" foot-gun.
9. **Replace the focus-stealing clipboard capture** with a non-focus approach.
10. **Keep going on the `Win` refactor, but against a narrow `Host` interface** — not a public mutable field for every paint and scratch array.

The one thing genuinely done well is the RenderNode/zero-allocation drawing path and the spring model; almost everything built on top of it — the paging fade, the idle design mapping, the window lifecycle, and the half-finished refactor — is currently broken.
