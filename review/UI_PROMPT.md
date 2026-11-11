You are a senior Android product designer + engineer. Project: Dynamic Edge (folder dynamic-edge, package com.dynedge),
a corner overlay widget. Its SETTINGS app (MainActivity.kt + FlatSlider.kt + QuickEditorActivity.kt, AppPickerActivity,
ClipboardActivity; prefs in Prefs.kt, WindowsConfig.kt) is "a jumbled mess" per the user. Read .agents/skills/dynamic-edge/SKILL.md,
Prefs.kt, MainActivity.kt, FlatSlider.kt, QuickEditorActivity.kt. Do NOT edit files. Output a concrete redesign spec:

User requirements (must all be met):
- App background must NOT be black: a grey background (pick exact hex values for light-ish dark grey surfaces);
  app elements (toggles, sliders, selections, headers) use the user's chosen theme/accent colour.
- User can set: a custom solid colour for the edge, custom gradient colour(s) for the edge gradient, and gradient on/off/
  strength. Full custom colour picking (hue/sat/value or a compact picker), plus presets. No emojis; Lucide icons.
- Proper categorisation and visual hierarchy; flat UI (no boxes inside boxes); minimal text; >= 44 dp targets.
- Existing settings to organise (see Prefs/MainActivity): sizes, windows (order/on/off), quick layout editor, clipboard,
  apps, live activities, setup/permissions rows, accent, fill style, flare, idle design, visualizer, beat reaction,
  beat bounce, opacity, base colour, rim, glow, speed, bounce, haptics, swipe sensitivity, reset.

Deliver: (1) information architecture: top-level structure (single scroll with sticky category chips? or a home list
of categories opening sub-pages?) with every setting placed, and why; (2) visual system: exact colours (bg, surface,
divider, text primary/secondary, accent usage), type scale, spacing, row anatomy, control styles; (3) the colour
picker design for edge solid colour + gradient colours; (4) how the live preview of the edge works inside settings;
(5) a prioritised implementation plan with file-level changes. Be specific and opinionated.
