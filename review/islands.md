# Dynamic island survey for Dynamic Edge

Star counts and licences were read from GitHub pages on 2026-11-10. Last-commit dates were not visible. Apple limits are from memory because the HIG page did not load. Closed apps were not inspected (APKMirror and Android Authority returned 403).

## 1. Comparison

| Name | FOSS? | How built | Unique | Weaknesses |
|---|---|---|---|---|
| [expressive-cutout](https://github.com/EvanKoe/expressive-cutout) (257 stars) | GPL-3.0 | Kotlin/Compose. Drawn through the **Accessibility service**. Reads the foreground package so tiles hide per app. Notification access. No INTERNET permission. Optional Shizuku. | Music, phone (answer/decline), and timer tiles mirrored from the clock app. Charging, headphone, USB and hotspot events. Privacy dots via Shizuku. Spring parameters are user-editable. | 21 open issues. API 29+. |
| [SmartIsland](https://github.com/agupta07505/SmartIsland) (466 stars, 387 commits) | GPL-3.0 | Kotlin/Compose/Hilt. Overlay plus notification access. The window type is not stated (unverified). | About 30 event types, each with a toggle. Four-direction gestures. Idle widgets (clock, weather, battery, net speed, calendar). Privacy dots. Notification history. Shizuku setup. | OEMs kill the service, so the README carries per-OEM tips. 57 issues. |
| [MaterialYou-Dynamic-Island](https://github.com/Angel-Studio/MaterialYou-Dynamic-Island) (308 stars) | No licence shown | Kotlin/Compose, API 31+. | Notification, media and charging popups. AMOLED theme. | The README says it is in a limited-activity phase. 27 issues. |
| [HyperBridge](https://github.com/D4vidDf/HyperBridge) (437 stars) | Apache-2.0 | Kotlin/Compose. NotificationListenerService plus a WidgetOverlayService. | Download progress ring with a green tick on completion. Call layout with timer. Google Maps and Waze turn-by-turn. Widgets on the island layer. Spoiler-word blocking. Shareable themes. | Aimed at Xiaomi HyperOS. Needs autostart and unrestricted battery. |
| [LiveBridge](https://github.com/appsfolder/livebridge) (1.2k stars) | GPL-3.0 | Flutter. Notification listener that re-posts notifications as Android 16 Live Updates. It is not an overlay. | Progress notifications turned into Live Updates. OTP extraction. Dictionary-based status detection (taxi, delivery). | Android 16+ only. OEM-dependent. Does not work on some Chinese ROMs. |
| [Android-Dynamic-Island](https://github.com/osamaalek/Android-Dynamic-Island) (17 stars) | Apache-2.0 | Kotlin, tiny. Write-up on [Medium](https://medium.com/@osamaalek/making-dynamic-island-app-for-android-f544d81db386). | Minimal reference. | 5 commits. |
| dynamicSpot (Jawomo) | Closed | Accessibility service (per [one listing](https://www.apkmirror.com/apk/jawomo/dynamicspot-multitasking/dynamicspot-multitasking-1-32-release/), unverified). | Live Activities for app shortcuts. Notification popup. Large feature set. | Not inspected. |
| OnePlus Live Alerts, Xiaomi HyperIsland, realme Mini Capsule, Samsung Now Bar | Closed | System-level status-bar chips. | Native integration. Google is adding the same idea as [Rich Ongoing Notifications / Live Updates](https://9to5google.com/2024/10/26/android-rich-ongoing-notifications-dynamic-island-prep/) in Android 16. | Details unverified. |

I found no corner-panel project that matches ours. Edge Screen and edge-gesture apps were not surveyed in depth.

Licence note: three of the six FOSS projects are GPL-3.0. Read them for ideas, but do not copy code unless Dynamic Edge is also GPL.

## 2. Why the good ones work

**Apple.** Apple's HIG page did not load, so the following is from memory. A Live Activity has compact (leading and trailing slots), minimal and expanded presentations. Minimal is used when two or more activities compete. Two activities are split into a pill plus a detached circle. Expanded opens on long-press. Limits are roughly 4 KB of state and about 8 hours of life. It feels good because the shape morphs with spring physics, and the black fill merges with the black cutout. The island is also unobtrusive at rest.

**Android mechanisms seen in the FOSS code:**
- **Event sources** are almost entirely NotificationListenerService. Media comes from notifications with a MediaStyle token. Calls come from CATEGORY_CALL. Timers are the clock app's chronometer or countdown notification. Progress comes from `EXTRA_PROGRESS`. Navigation comes from Maps and Waze notifications. System events (charging, headphones, USB) come from broadcasts.
- **State machine:** idle, then compact (a live icon plus a one-value summary), then expanded on tap or long-press, then collapse. Auto-collapse timers apply to transient events. Ongoing events (media, call, timer) stay compact until they end.
- **Priority:** call > timer/navigation > progress > media > transient notification. A higher priority preempts, and the lower one drops back to compact or minimal. The projects do not document this, so it is my inference.
- **Gestures:** swipe to shrink or dismiss, tap to expand or open the app, long-press to expand, and four-direction swipes in SmartIsland.
- **Per-app filtering and spring configurability** matter because users disagree about what is noisy.

## 3. Features to port, ranked by value/effort

All of these use the NotificationListenerService we already have. Call `getActiveNotifications()` at connect time so existing state is not missed.

1. **Live activity bean (framework).** This is the shared mechanism. The idle bean grows into a short arc-pill along the corner curve, with an icon at the inner end and one short value on the arc. It auto-collapses after about 4 s for transient events. Ongoing events stay until the source disappears. Tap opens the full quarter panel. Use `ValueAnimator` or `SpringAnimation` on the path radius so the shape morphs. Effort: M. Value: highest.
2. **Media mini.** Use `MediaSessionManager.getActiveSessions(ComponentName of the listener)`. Register a `MediaController.Callback`. Show album art as a dot and a thin progress arc along the corner curve. Tap toggles play/pause. Effort: S, because the media section already exists.
3. **Charging pulse.** Register for `ACTION_BATTERY_CHANGED` or `ACTION_POWER_CONNECTED`. Read `BatteryManager.BATTERY_PROPERTY_CAPACITY`. Show a fill arc with a percentage and collapse after 3 s. Effort: S.
4. **Calls.** Match notifications with `Notification.CATEGORY_CALL` or `CATEGORY_MISSED_CALL`. Read the action `PendingIntent`s for answer and decline (as expressive-cutout does). Show a small green or red arc with the timer. Effort: M.
5. **Timers and stopwatch.** Match the clock app notification. Read `when` plus `EXTRA_SHOW_CHRONOMETER`, or the countdown text. Show a draining ring. Effort: M. The format varies by OEM (unverified).
6. **Progress or download.** Read `EXTRA_PROGRESS`, `EXTRA_PROGRESS_MAX` and `EXTRA_PROGRESS_INDETERMINATE`. Show a ring that closes with a tick (the HyperBridge pattern). Effort: S.
7. **Volume or brightness HUD.** Register a ContentObserver on Settings.System and a `VOLUME_CHANGED_ACTION` receiver. The latter is an undocumented broadcast (unverified on recent Android). Show a short arc that fills. WRITE_SETTINGS covers brightness. Effort: M.
8. **Incoming notification peek.** Show the app icon and one short line (title only). Tap opens the notification's `contentIntent`. Needs a per-app allowlist and a privacy mode. Effort: M.
9. **Navigation.** Read the Maps notification title (distance and turn). Show an arrow and a distance. It is brittle and text parsing varies by app and language. Effort: M/L. Low priority.
10. **Privacy dots** (camera, mic). Needs AppOps callbacks or Shizuku/ADB, so skip it for now.
11. **Clipboard.** Not needed for the survey.

**Two simultaneous activities.** Copy Apple's split: the higher priority sits at the bean, and the second becomes a small dot just along the curve.

## 4. Pitfalls

- **Battery:** do not run animations while idle. Stop all tickers when the screen is off. Use event-driven listeners, not polling. Cancel animators on collapse. Keep the view hierarchy tiny.
- **Keyboard:** a corner overlay can sit above the IME. Use `TYPE_ACCESSIBILITY_OVERLAY` with `FLAG_NOT_FOCUSABLE`, and keep the touchable region minimal so the bean does not block the keyboard's send key.
- **Fullscreen video and games:** detect fullscreen with `WindowInsets` or via accessibility window changes. Hide or fade the bean. Offer a per-app hide list (expressive-cutout reads the foreground package for this).
- **Lock screen:** `TYPE_ACCESSIBILITY_OVERLAY` can draw above the keyguard. Hide notification text and call names by default, and show only an icon until unlock.
- **Policy:** Google Play restricts AccessibilityService use to accessibility purposes. Distribution outside Play (sideload, F-Droid) avoids this. We are adb-installed, so there is no issue.
- **Process death:** OEMs kill background services (SmartIsland and HyperBridge both document this). Offer a battery-exclusion prompt.
- **Notification parsing is brittle:** it varies by OEM and language. Prefer structured extras over text parsing, and fall back to a generic icon.
- **targetSdk 28** is fine for these APIs. Media sessions and `MediaStyle` extras work from API 21.
