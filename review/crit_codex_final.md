codex
## Review findings

### Critical

1. **[EdgeService.kt:241–247] Attach retries stop after five failures.** If the accessibility overlay token is unavailable for more than about 13 seconds, the service silently gives up and the bean stays gone. Retry while the service is connected, reset the retry state after success, and log/report the terminal failure so it can recover or be diagnosed.

2. **[EdgeService.kt:325–356] Teardown can leave the service partially alive.** It clears the view and removes the overlay, but does not clear `wm`, and cleanup is split across broad `Throwable` catches that hide failures. Make teardown idempotent with `try/finally`, remove all callbacks/listeners even if one cleanup step fails, and reset all attachment state. Also handle reconnection after `onUnbind` so `attach()` can rebuild a clean window.

3. **[EdgeView.kt:591–603] Hiding can leave a stale alpha animation.** `setHidden()` starts `ViewPropertyAnimator` fades, but doesn’t cancel an in-flight fade. A quick IME/keyguard transition can therefore finish with alpha zero while `hidden == false`, making the overlay invisible. Cancel the existing animator before setting the target alpha, and set visibility/alpha deterministically when the state changes.

4. **[EdgeView.kt:1562–1575] Beat scaling can draw outside the overlay window.** The bean pulse and press scale increase `dw`/`dh`, but `maxOpenWidthPx` and `maxOpenHeightPx` only reserve `rad + flare`; the service adds a fixed 24 dp pad. The live shape, rim, or glow can be clipped at the window boundary, especially during drag overshoot or at the largest configured size. Size the window for the maximum reachable spring/press/beat scale plus glow and flare, or clamp the drawn extent to its bounds.

5. **[EdgeView.kt:827–860] “Zero work while idle” is violated whenever music plays.** The visualizer continuously schedules a callback every 25 ms, even when the panel is closed. At 120 Hz that still wakes the UI thread about 40 times a second to animate the idle bean. Use a bounded-rate callback only while the idle visualizer needs to animate, stop it promptly when playback stops, and verify the audio source is released on every detach/stop path.

6. **[EdgeService.kt:251–255] IME detection only runs when an accessibility event arrives.** If the service misses `TYPE_WINDOWS_CHANGED` during startup or rebinding, the overlay can remain touchable over the keyboard. Recheck visible windows on service connection, screen changes, and window changes; make the query resilient to an unavailable/throwing `windows` property.

7. **[EdgeView.kt:648–658] Detach stops the frame loop but does not stop the other active sources.** The method removes some callbacks and marks layers dirty, but leaves `vizRunning`, listeners, `sourcesStarted`, and window sources potentially active if a detach occurs outside the service’s full teardown path. Stop/reconcile all sources and reset attachment state in `onDetachedFromWindow`; restart them deliberately on attach.

8. **[EdgeService.kt:139–150, 293–307] Window update failures are swallowed.** A failed resize or focusability update leaves the service believing the window is correctly configured. That can leave a clipped or focusable overlay, or break clipboard capture without any signal. Catch expected window exceptions specifically, log them, and recover by removing/re-adding the view or retrying the update.

9. **[EdgeService.kt:166–214] `attach()` catches every `Throwable`, including programming and resource failures, then retries blindly.** This masks the real failure and makes repeated bad states look like transient token races. Catch documented window exceptions, report unexpected failures, and only retry failures known to be temporary.

10. **[EdgeService.kt:166–247] The service has no defense against an OEM force-stopping the app.** A force-stop by vivo’s background manager (`com.vivo.abe`) is outside the service’s ability to recover from; Android suppresses restarts until the user launches or re-enables the app. Explain that limitation in setup/status UI and provide a direct path to the relevant battery/background settings where available. Do not claim a receiver or retry loop can survive a force-stop.

### Reported black-window / paging bug

11. **[EdgeView.kt:1644–1676] The page layer clips each rotated window to an unrotated square RenderNode.** `setClipToBounds(true)` clips the display list to the node’s original `s × s` bounds; then the node rotates around its far corner. During a backward swipe, the previous page’s quarter-circle content rotates into areas outside that square and gets hard-clipped. That is the strongest match for “goes black” during paging or on a vertical swipe: a large part of the incoming page disappears at the layer boundary. Record each window into a larger node with a centered origin and enough bounds for the full rotation plus touch/content extent, or remove node clipping and give the node sufficient bounds. Keep the pivot and translation in the expanded coordinate space.

12. **[EdgeView.kt:1657–1659] The comment and recovery logic misunderstand skipped RenderNodes.** Skipping `drawRenderNode()` when `pageFade` is near zero does not discard the display list. The “re-record when it comes back” claim is false; a stale or invalid node can remain stale indefinitely because `hasDisplayList()` stays true. Invalidate the node explicitly whenever its content inputs change, and don’t treat invisibility as display-list loss.

13. **[EdgeView.kt:1687–1690] `pageFade()` reaches zero at 0.85 pages, abruptly hiding content before a page settles.** This can leave a black-looking gap if the page spring passes through the region where both page layers have low alpha, particularly when the target changes direction. Crossfade the two nearest pages with a normalized pair of weights that always sum to one, and clamp the other six pages to zero.

14. **[EdgeView.kt:1493–1516] A swipe can leave the page spring with no useful frame updates after a frame stall.** The spring timestep is capped at 1/30 second; after a long pause it advances by only that capped interval and may take a long time to settle. Use elapsed-time-aware integration with bounded substeps or snap safely after a large stall, and ensure the frame callback is removed/reset on every cancel/detach path.

15. **[EdgeView.kt:1553–1557] Collapse alpha is updated by subtracting a fixed amount per frame.** `contentAlpha - dt / 0.09f` is frame-rate dependent and can produce a mismatch between shape progress and page opacity across 60/90/120 Hz devices. Compute collapse alpha from elapsed collapse time or a normalized spring/progress value. Keep the same mapping for RenderNode and direct drawing.

### Performance and rendering

16. **[Win.kt:130–150] `addBand()` allocates `RectF`, arrays, and `Path` objects every layout.** Relayout happens during size/flare changes and configuration changes. Cache the band paths per window and mutate/reuse them; preallocate the rects and path slots.

17. **[EdgeView.kt:1571–1583] `buildLivePath()` rebuilds and transforms multiple paths whenever animated geometry changes.** `fillPath()` rewinds and reconstructs the shape, `rimPath` is rebuilt, and `path.transform()` writes into `unitPath` during the animation. That is per-frame geometry work and allocation pressure can still occur inside framework path operations. Keep reusable geometry buffers, update only the path whose geometry changed, and profile at 120 Hz with maximum flare and beat enabled.

18. **[EdgeView.kt:1632–1641] The media beat ring is explicitly the halo the spec says to remove.** It allocates extra compositing and draws a pulsing ring around the art, contrary to the v1.4 direction to make the fill react instead. Delete this drawing path and put beat response into cached fill shader parameters.

19. **[EdgeView.kt:268–284, 713–735] Fill shaders are rebuilt on every looks change and cannot react to beat.** The v1.4 spec requires gradient radius, brightness, accent mix, drift, sparkle, and pattern tint to react to bass/mids/highs. Current code only builds a static radial gradient. Add cached styles and mutate shader local matrices/paint parameters without rebuilding shader objects per frame.

20. **[EdgeView.kt:1618–1626] The hardware and software render paths do not match.** Hardware uses RenderNodes and scales by `min(wS,hS)/rad`; software clips to the shape, uses a different scale formula, and replays all window drawing directly. This can produce visibly different content placement/visibility and makes black-page reports harder to reproduce. Use one transform and clipping contract across both paths.

21. **[EdgeView.kt:1651–1654] RenderNodes are never released when the view is finally released.** `release()` stops sources but does not discard or null the nodes, while `onDetachedFromWindow()` only marks them dirty. Discard the display lists and clear node references during final release; retain them only across ordinary transient detach if the same view will return.

22. **[EdgeView.kt:856–860] The visualizer invalidates the entire view at roughly 40 Hz.** Even when closed, every tick redraws the shape, idle decoration, and any live activity. Invalidate only the bean bounds while closed, and only update the open rim when the active window requires it.

23. **[EdgeView.kt:1562–1570] Beat pulse uses the visualizer’s lowest band as “bass” with fixed intensity.** There is no user-configurable `KEY_BEAT_AMOUNT`, and the spec’s 0–100 bounce control is absent from the renderer path. Apply the configured amount, clamp to the requested maximum, and separate bass energy from the other bands.

24. **[EdgeView.kt:863–881] Idle bars use only four coarse bands and draw accents by index.** The selected visualizer styles (`spectrum`, `rays`, `ripple`, `meter`, `wave`, `liquid`) are not implemented, and the idle shape has no fill pulse. Implement the styles as cached, low-cost paths/shaders and make the settings preview use the same renderer.

### Gestures and touch

25. **[EdgeView.kt:1785–1792] `addMove()` mutates the caller’s `MotionEvent`.** Temporarily changing its coordinates is fragile: exceptions or future early returns can leave the event modified, and pointer history is transformed inconsistently. Copy the event for `VelocityTracker`, feed raw coordinates through the copy, and recycle it in `finally`.

26. **[EdgeView.kt:1829–1838] Every DOWN rebuilds the hit region from `path`, but does not first guarantee that `path` reflects the current window size and shape.** A touch immediately after resize/beat/press can be tested against stale geometry. Call the same shape update used by drawing before hit testing, then use the published region consistently.

27. **[EdgeView.kt:1936–1952] Gesture classification uses `max(-dx,-dy)` for drag-to-open.** A horizontal leftward movement can expand the bean even when the user meant to page or interact with content, and diagonal gestures are classified by whichever component barely crosses slop first. Lock the gesture axis after a clear directional threshold; keep idle open restricted to intentional up-left motion.

28. **[EdgeView.kt:2050–2084] Tap dispatch does not verify the pointer is still inside the original hit target’s actual hit shape in all cases.** The final `hitItem()` comparison checks only item ID and window, so overlapping 44 dp hit circles can turn a drag-off one item and release over another into an accidental action if IDs collide. Store the original control identity and require a small movement threshold plus release-inside-original-target.

29. **[EdgeView.kt:1836–1838] Touch handling rebuilds the region from the draw path, not the published shape geometry.** During animation the input region and drawn shape can diverge. Update one shared geometry snapshot per frame and use it for drawing, hit testing, and touch region publication.

30. **[EdgeService.kt:182–196] The window uses `FLAG_LAYOUT_NO_LIMITS` and a large fixed-size square.** The large square receives input near the bottom/right screen edges and relies on hidden insets clipping. Combined with the whole-view touch handler, this is vulnerable to OEM gesture/nav-bar interception and accidental outside events. Verify the actual insets and gesture exclusion rectangle on the target device; keep the touchable region tight and do not claim full edge-drag reliability.

31. **[EdgeView.kt:1806–1808] `ACTION_OUTSIDE` returns false after starting collapse.** Returning false can cause inconsistent dispatch behavior for the same outside sequence. Consume the event once the overlay handles it.

32. **[EdgeView.kt:1865–1887] Controls are not hit-tested while content is still fading in.** A tap during the last half of the open animation is ignored except as a drag. Either make controls interactive as soon as their alpha is useful, or visibly indicate that the panel is still transitioning and defer taps intentionally.

### Lifecycle, sources, and data

33. **[EdgeView.kt:146–156, 180–216] `reloadWindows()` starts every enabled window source as soon as the overlay is attached.** The contract says expensive listeners and per-window sources should run only when needed; device stats are scoped correctly with `onShown`, but the other windows start even while closed and all enabled windows start together. Start shared listeners only when the panel opens and window-specific sources only for the visible page.

34. **[EdgeView.kt:180–216] Disabling the current window can select an unexpected page.** It falls back to `lastPageId`, which may be disabled or no longer in the enabled order, then defaults to index zero. Preserve the nearest surviving page or deterministically select QUICK, update `lastPageId`, and keep `shownWin` synchronized.

35. **[NotifFeed.kt:19–27, 32–45] Notification state can retain dismissed items indefinitely.** `publish()` depends on `MediaListener` being alive and the callback only notifies the view; dismiss does not remove the item optimistically. Remove it from the feed immediately, request a refreshed active list, and make listener loss clear or mark the feed stale.

36. **[NotifFeed.kt:55–69] App icons are loaded synchronously on the UI thread.** A notification refresh can call `PackageManager.getApplicationIcon()` and rasterize a bitmap during view refresh, causing jank. Load/cache icons off the UI thread and publish the completed result on main.

37. **[WinTimer.kt:137–148] Dial drag invalidates on every MotionEvent and triggers haptics for every value divisible by five.** Moving across a five-minute boundary can trigger repeated ticks on neighboring values and high-rate redraws. Haptic only when crossing a five-minute bucket boundary, and skip invalidation when the snapped minute did not change.

38. **[Arcs.kt:34–72] Receiver and observer registration failures are silently ignored.** The volume UI can then appear frozen while the user assumes system values are live. Track registration state separately, report unsupported streams, and refresh values on window visibility/resume.

### UI/UX and visual design

39. **[EdgeView.kt:1759–1780] The close control has a filled disc behind the X.** The v1.4 request explicitly requires a subtle glyph with no disc. Remove the fill and keep the existing ≥44 dp hit target.

40. **[EdgeView.kt:1719–1757] Page indicators are bars, not dots, and their spacing/arc is centered on a radius derived from X.** This does not meet the requested evenly spaced arc concentric with the X center. Place discrete indicators on the specified common center/radius, with a clear active state.

41. **[EdgeView.kt:1206–1234] The flare is not implemented as a reliable concave fillet.** The path begins outside the view (`ox - f`, `bottom`) while the service’s fixed pad may not include it; the arc directions are also not validated against the required inward “ear” geometry. Construct the two fillets explicitly, include their extents in window sizing and touch region, and visually verify at idle/open extremes.

42. **[EdgeView.kt:723–728] Idle styles collapse into four crude cases.** `glyph`, `pill`, `orbit`, `slits`, `halo`, `meter`, `grid`, and `eclipse` are not rendered distinctly; multiple choices map to the same branch. Implement each selected design with consistent Lucide-style strokes and update the preview renderer.

43. **[MainActivity.kt:111–123, 125–131] Settings controls are grouped inconsistently and omit requested options.** Beat bounce is under FEEL instead of next to Beat reaction, and there is no opacity control at all. Add `KEY_BEAT_AMOUNT` and `KEY_OPACITY`, show the bounce slider only when beat reaction is enabled, and apply opacity to the whole overlay.

44. **[MainActivity.kt:503–547] Window rows are 48 dp high but their toggles and reorder behavior compete for the same touch area.** Long-press drag starts on the whole row, including the toggle; reordering can begin when the user meant to toggle. Add a dedicated drag handle or up/down controls, preserve 48 dp targets, and expose order changes clearly.

45. **[MainActivity.kt:110–123] The settings screen exposes raw visual configuration without enough hierarchy.** A long stack of unlabeled preview pickers and switches makes “LOOKS” a dump of controls; the no-label mini previews are not self-describing. Keep the mandated minimal text, but add short section labels for idle design, fill, and visualizer, and show a selected style name/accessibility description.

46. **[WinApps.kt:104–110] App shortcuts use a white disc behind every icon.** The result is inconsistent with the monochrome, flat panel and creates the “box in a box” appearance the design rules prohibit. Render icons cleanly without a disc; use a restrained outline only for focus/press.

47. **[WinQuick.kt:129–136, WinTimer.kt:97–105] Many controls are small visual targets even when their hit targets are larger.** QUICK’s ring controls and TIMER’s preset chips can look like tiny dots with invisible large hit areas. Use clear 44 dp visual affordances where the layout permits, and show focus/pressed state without relying on a faint ring.

48. **[WinNotifications.kt:94–97, 120–150] Notification text is flattened, truncated, and drawn on arcs.** Titles and body text are difficult to scan and can become unreadable when rotated; tapping a row also opens the intent even if the notification content is malformed. Use upright text in compact arc-aligned rows, keep title/body hierarchy, and handle cancelled `PendingIntent`s explicitly.

49. **[MainActivity.kt:82–155] Settings has no clear explanation of service/background status.** The accessibility service may be enabled while vivo background management later force-stops the package. Show whether the service is connected and offer a direct OEM battery/background guidance entry, with a concise explanation that force-stop requires user relaunch.

### Code structure and contract mismatches

50. **[EdgeView.kt:723–741] Settings preferences are only partly wired into the engine.** `fill`, `vizStyle`, opacity, and beat amount are absent from `applyLooksNow()`. Updating those settings either does nothing or only changes a preview. Wire every preference through the service listener and invalidate only the affected cached layers.

51. **[EdgeService.kt:82–92] The preference listener omits several look keys.** It does not react to `KEY_FILL`, `KEY_VIZ_STYLE`, opacity, or beat amount; it handles flare separately but not as part of a consistent look update. Add all keys and apply changes atomically.

52. **[EdgeView.kt:128–137] Unknown window IDs silently become QUICK.** A bad config entry or future ID renders the wrong window while retaining the wrong ID/index semantics. Validate IDs in `WindowsConfig.load()` and fail visibly or skip unsupported entries.

53. **[WindowsConfig.kt:44–51] `save()` can write an empty or incomplete list.** The next `load()` may silently restore defaults, losing user order/on-off choices. Normalize every save to exactly one entry per known ID, preserve order, and enforce QUICK as the fallback enabled window.

54. **[EdgeView.kt:608–632] `release()` leaves RenderNode references and some animation state behind.** Reusing the same view after service reconnection can show stale nodes or alpha/spring state. Clear nodes and reset the frame, gesture, alpha, hidden, and paging state in final release.

55. **[EdgeService.kt:167–178] `AssistControl.disableCornerGesture()` runs synchronously during attach.** If secure settings access or OEM settings calls block/fail, overlay attachment is delayed or prevented. Isolate this optional system-setting work from window creation, catch/report expected failures, and keep the overlay attach path independent.

## Rework these first

1. Fix rotated RenderNode bounds/clipping and make page fades crossfade without a black gap.
2. Make attach/reconnect and teardown idempotent; keep retrying transient overlay-token failures.
3. Size the overlay for maximum beat/press/drag/flare bounds so no shape edge is clipped.
4. Cancel stale alpha animators when hiding/showing the overlay.
5. Stop the idle visualizer loop when it is not visibly needed; eliminate unnecessary full-view invalidation.
6. Remove the media halo and implement the specified music-reactive fill.
7. Implement the requested distinct idle/fill/visualizer styles and wire every preference end-to-end.
8. Fix gesture classification, event mutation, and shared draw/hit/touch geometry.
9. Remove the close-button disc, rebuild the page indicator around the X center, and make touch affordances legible.
10. Add opacity and beat-amount controls, improve window reordering affordance, and add honest service/OEM status guidance.
85,021
## Review findings

### Critical

1. **[EdgeService.kt:241–247] Attach retries stop after five failures.** If the accessibility overlay token is unavailable for more than about 13 seconds, the service silently gives up and the bean stays gone. Retry while the service is connected, reset the retry state after success, and log/report the terminal failure so it can recover or be diagnosed.

2. **[EdgeService.kt:325–356] Teardown can leave the service partially alive.** It clears the view and removes the overlay, but does not clear `wm`, and cleanup is split across broad `Throwable` catches that hide failures. Make teardown idempotent with `try/finally`, remove all callbacks/listeners even if one cleanup step fails, and reset all attachment state. Also handle reconnection after `onUnbind` so `attach()` can rebuild a clean window.

3. **[EdgeView.kt:591–603] Hiding can leave a stale alpha animation.** `setHidden()` starts `ViewPropertyAnimator` fades, but doesn’t cancel an in-flight fade. A quick IME/keyguard transition can therefore finish with alpha zero while `hidden == false`, making the overlay invisible. Cancel the existing animator before setting the target alpha, and set visibility/alpha deterministically when the state changes.

