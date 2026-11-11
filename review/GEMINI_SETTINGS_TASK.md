Edit ONLY app/src/main/java/com/dynedge/MainActivity.kt (and FlatSlider.kt only if needed) in this Android project (Kotlin, framework Views only, no AndroidX). Do not touch any other file. No emojis anywhere.

Goal (from the app owner): "Properly categorize the permissions and adb commands in the app, since they are not required by users who have done it already, sectionize the things there, and make it compact."

Do this:
1. Group the screen into clear compact sections in this order: SIZE, LOOKS, FEEL, APPS, then SETUP at the very bottom.
2. SETUP replaces the current PERMISSIONS rows and ADB block:
   - Compute each permission's state (same checks the file already uses: overlay/accessibility service, media/notification access, Do Not Disturb access, modify system settings, assistant corner swipe off, plus RECORD_AUDIO granted via checkSelfPermission, plus WRITE_SECURE_SETTINGS granted via checkSelfPermission).
   - If ALL are granted: SETUP shows a single compact row "Setup" with a small green check (draw it, or a short "Done" in #30D158) and nothing else. Tapping it expands the full list (for re-checking), tapping again collapses.
   - If some are missing: show ONLY the missing ones as rows (label + "Fix" in accent colour; tap opens the matching settings screen as now), with a row counter like "2 missing" in the section header, and one collapsed "ADB" row under them that expands to show ONLY the adb commands needed for the missing items (map each permission to its adb line; the accessibility one also needs the accessibility_enabled line). Long-press on the commands block copies them to the clipboard.
   - Re-evaluate in onResume.
3. Compact: reduce vertical padding of rows (about 44 dp min row height, 16 dp side padding), section headers 11sp uppercase grey with 20 dp top spacing, no explanatory sentences anywhere, no boxes or cards, 1 px #1C1C1E dividers only between rows inside a section.
4. Keep every existing behaviour: live size preview (KEY_PREVIEW), sliders, accent swatches, idle previews, toggles, apps list and picker, the white_wallpaper and wallpaper_file adb hooks in onCreate, onPause clearing KEY_PREVIEW.
5. Then build from the project root with: ./gradlew.bat assembleRelease -q   and fix any compile errors you introduced in MainActivity.kt. If the build fails only because of EdgeView.kt or EdgeService.kt (another agent is editing them), ignore those errors and say so.
Report what you changed in a few lines.
