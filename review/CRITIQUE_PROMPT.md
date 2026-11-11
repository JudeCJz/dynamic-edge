You are a harsh senior Android + UI reviewer. Project: Dynamic Edge (folder dynamic-edge, package com.dynedge), an
Android overlay (accessibility service) that shows a black bean in the bottom-right screen corner; tapping it opens a
quarter-circle radial panel with swipeable windows (QUICK, MEDIA, CLIPBOARD, APPS, and new VOLUME, TIMER,
NOTIFICATIONS, DEVICE). Read .agents/skills/dynamic-edge/SKILL.md, review/SPEC_v1_4.md, review/LOOKS_DECISION.md,
then the Kotlin sources in app/src/main/java/com/dynedge (EdgeView.kt and EdgeService.kt are the core; the code is
mid-refactor, some window code may be moving into Win*.kt files). Do NOT edit any file. Output only your review.

Criticize everything, ranked most severe first, each item with file:line, the concrete failure, and the fix:
1. Reliability: anything that can make the overlay disappear or crash (window add/remove, service lifecycle,
   BadTokenException in onServiceConnected, OEM background kills such as vivo com.vivo.abe force-stopping the app,
   receivers/listeners leaks, threading).
2. A reported bug: "windows go black when I swipe back and up" (panel content goes dark/black during or after paging
   back to a previous window, or on a vertical swipe). Find the most likely root cause in the paging / RenderNode
   section layers / pageFade / alpha / gesture code and say exactly how to fix it.
3. Rendering performance (per-frame allocations, invalidation, layers, jank at 120 Hz).
4. Gesture handling (tap vs drag vs page swipe vs arc drag, MotionEvent mutation, gesture exclusion, touch region).
5. UI/UX and visual design of the panel, windows and settings screen: hierarchy, legibility, touch targets, motion,
   consistency, anything that looks cheap. Rules the app must follow: no emojis, Lucide-style icons, flat UI (no boxes
   in boxes), minimal text.
6. Code structure and anything else that is bad.
End with a top-10 "rework these first" list. Be specific and blunt; no praise.
