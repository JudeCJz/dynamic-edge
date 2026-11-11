# Dynamic Edge Review

## 1. Brutal UI/visual critique (4/10)
- **Hierarchy/legibility:** Radial layout is clever but information-dense. Media title/artist on arcs (`EdgeView.kt:688-693,1384-1387`) are hard to scan and clip/ellipsize unpredictably; time labels sit as tiny 10sp text near the progress arc (`1388-1404`). QUICK icons rely on memory with no labels, so affordance is weak.
- **Spacing/touch targets:** Inner/middle controls are ~40dp visual discs in places (`layoutQuick` brad ~20dp radius, `layoutMedia` prev/next min 18dp radius) which is borderline for thumb accuracy on a corner widget; outer brightness thumb is 8dp radius (`1334`). Bands/tiles (`bandsOn` default off, `Prefs.kt:43,69`) mean most states look flat but also undifferentiated.
- **Quarter-circle layout/X/indicator:** The X anchored to the idle-bean centroid (`layoutAll` `xc = min(0.4244f*idle, 0.16f*rad)`, `636-646`) is spatially logical but easy to miss and competes with page dots placed in the same gap (`drawDots` `rd = max(rad*0.26f, xd + 26dp)`, `1226-1258`). Dots as a tiny arc are subtle to the point of invisibility; page identity is not obvious.
- **Idle states:** Default idle style is `dot` (`Prefs.kt:75`) which can read as a dead pixel/smudge; visualizer/clock/battery variants risk clutter on a 48dp bean (`EdgeView.kt:364-419` init state suggests many modes). Compared to `shot_old_full_version`, compactness improved, but polish still feels prototype-grade.

## 2. Interaction/feel critique (5/10)
- **Gestures/conflicts:** Overloaded semantics: tap idle to open, drag idle to stretch-open, horizontal drag to page, vertical/down drag to close (`handleDown/Move/Up`, `1516-1762`). Paging and close compete (`1616-1631` chooses by axis dominance), making accidental page switches/closes likely in a corner grip.
- **Discoverability:** Long-press opens settings/player (`1538-1540,1789-1798`) and art tap toggles play while long-press opens player (`1765-1777`) — powerful but invisible. Brightness is a thin arc hit zone with 18dp tolerance (`1559-1567`) that can steal intent from paging.
- **Physics:** Spring split (width leads, height lags) and rubber-banding are good ideas (`wS/hS`, `991-999,1666-1695`), but velocity handoff/thresholds (`thr dp(500), progress 0.33/0.67`, `1697-1727`) can feel inconsistent between expand and shrink. Listener startup is deferred/post-delayed on gesture start (`786-791,987-988,1522-1523,1642-1643`) which can make first-open state feel stale.

## 3. Efficiency + smoothness problems (ranked)
1. **Per-gesture/service wakeups while "idle":** 1s tick while playing and expanded on media (`tickRunnable`, `354-362,829-831,1068`) keeps invalidating/drawing; visualizer/idle services and receivers add background work when enabled (`setScreenOn/syncIdleServices`, `500-504,538-543`). *Fix:* event-driven progress updates, throttle to state changes, and hard-stop all nonessential work when collapsed/screen off.
2. **Broad invalidation/dirtying:** Many state changes mark whole sections dirty or call `invalidate()` (`refreshToggles/Apps/Media` set section dirty, numerous `invalidate()` in touch/slider paths, `812-916,1577-1579`). *Fix:* dirty specific regions/nodes only, coalesce slider/media updates to frame callbacks.
3. **Work on critical path:** `ensureSources()` + binder/listener registration happens on down and via delayed runnable (`1522-1523,766-791`), risking jank at interaction start. *Fix:* prewarm cheap state snapshots, or start sources asynchronously off the first frame and animate placeholders.
4. **Allocation/churn risks:** Ellipsized strings/formatting and art shader/matrix updates happen on refresh (`881-916,848-861`), and paths/shaders are mostly reused but section recording still redraws commands when dirty. *Fix:* cache formatted text by width/content, avoid repeated object churn in refresh, keep dirty flags minimal.
5. **Layer/window behavior:** RenderNodes are good (`1146-1185`), but window resizes and region updates occur on transitions (`EdgeService` large/idle window swaps, `169-197`) — cheap but can cause compositor hiccups. *Fix:* minimize resize frequency and ensure region changes only when geometry actually changes.

## 4. Bugs / risks in code
- **Host contract mismatch:** `EdgeService` implements/calls `onNeedLargeWindow()` (`EdgeService.kt:169-182`) but `EdgeView.Host` only declares `onSettled` and `onRegionChanged` (`EdgeView.kt:56-62`). This will not compile/apply as intended unless changed elsewhere.
- **"Arc slider jumps to zero" risk:** Slider hit/apply uses current `pageS.value` frame and local polar conversion (`1561-1579`); during paging/settle or edge touches, angle math can clamp to 0 and `setLevel` fires immediately. Needs stable gesture capture/radius+angle hysteresis and no writes until intentionally dragging.
- **Unused/half-applied seek state:** `seekActive/seekFrac` exist (`279-281`) but no touch handling seeks media progress; progress arc is display-only (`1390-1404`) — feature gap/confusing code.
- **State assumptions:** `bAuto`/`ID_SUN` exist but QUICK layout/draw use only `ID_ASSIST` and slider (`636,679,1310-1342`) — likely incomplete theming/customization wiring. `performHapticFeedback` ignores `hapticsOn` in places (e.g. expand/long press paths use direct calls, `1000,1794`).

## 5. Feature ideas (top 10, ranked)
1. **QUICK section editor (best ROI):** Visual radial slot editor in settings: pick a **ring band** (inner/mid/outer) + **angular slot index** + item from a **tile catalog** (toggles, app intents, system shortcuts). Keep geometry polar (`ringId, slotId -> radius, angle`) so it stays quarter-circle. Allow swapping outer control between **arc sliders** (brightness/media/ring/notification) via a typed binding; persist as ordered slot config, render generically.
2. **Clipboard section on quarter circle:** Ring of recent clips as arc segments (newest near thumb), tap to preview, long-press to pin/delete, center action = **Paste**. With AccessibilityService, inject paste into focused editable nodes; show history UI locally. *Honest limits:* cannot silently read/write clipboard in all contexts/background on modern Android, cannot force paste into non-editable/secure fields, and some OEMs restrict automation.
3. **One-handed paging affordances:** Edge swipe hints, haptic detents per page, and a larger invisible hit gutter for paging vs close.
4. **Customizable sliders:** Multiple concentric arcs with distinct thickness/color and conflict-free capture zones.
5. **Notification peek section:** Last N heads-up items with dismiss/open (needs Notification Listener access already present).
6. **Quick app actions/search:** App shortcuts (long-press actions) and a tiny radial search for 4-6 pinned actions.
7. **Context rules:** Auto-open section by state (media playing, headset, charging) with explicit user rules.
8. **Idle glance modes:** Minimal, battery ring, clock — but strictly single-purpose and low-update.
9. **Gesture tuning:** Sensitivity, open direction bias, and disable-drag-to-open option.
10. **Profiles/themes export:** Save/load radial layouts and accent/physics presets.

## 6. The single most important change
**Ship a stable, generic QUICK-layout engine (slot model + input capture/state machine) before adding features.** Right now layout, hit-testing, and actions are hardcoded and fragile (`EdgeView.kt` large monolith, fixed arrays/ids/sections). A data-driven polar slot system with robust gesture arbitration and no slider-jump regressions is the foundation for customization, clipboard, and premium feel without tanking smoothness.
