package com.dynedge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.MediaStore

object Prefs {
    const val NAME = "edge"
    const val KEY_IDLE_DP = "idle_dp"
    const val KEY_OPEN_DP = "open_dp"
    const val KEY_APPS = "apps"
    const val IDLE_MIN = 32f
    const val IDLE_MAX = 80f
    const val IDLE_DEF = 48f
    const val OPEN_MIN = 170f
    const val OPEN_MAX = 300f
    const val OPEN_DEF = 216f
    const val MAX_APPS = 12

    // ---------------------------------------------------------------- per-window options (window editor)
    /** Every per-window key starts with this prefix: EdgeService re-lays the windows out when one changes. */
    const val WIN_PREFIX = "w_"
    const val KEY_APPS_ICON = "w_apps_icon"        // Float dp, icon size on the APPS ring (32..48, default 40)
    const val KEY_APPS_GAP = "w_apps_gap"          // Float dp, space between icons and between rings (4..20, default 8)
    const val KEY_APPS_RECENTS = "w_apps_recents"  // Boolean, recent-apps ribbon on the APPS window (default true)
    const val KEY_MEDIA_ART = "w_media_art"        // Boolean, album art in the MEDIA circle (default true)
    const val KEY_MEDIA_SKIP = "w_media_skip"      // Boolean, previous / next buttons (default true)
    const val KEY_MEDIA_TEXT = "w_media_text"      // Boolean, title and artist arcs (default true)
    const val KEY_MEDIA_SEEK = "w_media_seek"      // Boolean, progress / seek arc and times (default true)
    const val KEY_CLIP_ROWS = "w_clip_rows"        // Int 1..4, clips shown in the CLIPBOARD window (default 4)
    const val KEY_NOTIF_ROWS = "w_notif_rows"      // Int 1..4, notifications in the ALERTS window (default 4)
    const val KEY_NOTIF_BODY = "w_notif_body"      // Boolean, grey body text after the title (default true)
    const val KEY_NOTIF_ICONS = "w_notif_icons"    // Boolean, app icons in front of each row (default true)
    const val KEY_TIMER_P = "w_timer_p"            // Int 1..60, + index 0..3: the four TIMER preset chips
    val TIMER_DEF = intArrayOf(1, 5, 10, 25)
    const val KEY_DEV_BAT = "w_dev_bat"            // Boolean, battery arc + percentage (default true)
    const val KEY_DEV_RAM = "w_dev_ram"            // Boolean, RAM arc (default true)
    const val KEY_DEV_DISK = "w_dev_disk"          // Boolean, storage arc (default true)
    const val KEY_DEV_TEMP = "w_dev_temp"          // Boolean, battery temperature (default true)
    const val APPS_ICON_MIN = 32f
    const val APPS_ICON_MAX = 48f
    const val APPS_ICON_DEF = 40f
    const val APPS_GAP_MIN = 4f
    const val APPS_GAP_MAX = 20f
    const val APPS_GAP_DEF = 8f

    fun appsIconDp(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_APPS_ICON, APPS_ICON_DEF).coerceIn(APPS_ICON_MIN, APPS_ICON_MAX)
    fun appsGapDp(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_APPS_GAP, APPS_GAP_DEF).coerceIn(APPS_GAP_MIN, APPS_GAP_MAX)
    fun flag(ctx: Context, key: String): Boolean = prefs(ctx).getBoolean(key, true)
    fun rows(ctx: Context, key: String): Int = prefs(ctx).getInt(key, 4).coerceIn(1, 4)
    fun timerPreset(ctx: Context, i: Int): Int = prefs(ctx).getInt(KEY_TIMER_P + i, TIMER_DEF[i]).coerceIn(1, 60)

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun idleDp(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_IDLE_DP, IDLE_DEF).coerceIn(IDLE_MIN, IDLE_MAX)

    fun openDp(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_OPEN_DP, OPEN_DEF).coerceIn(OPEN_MIN, OPEN_MAX)

    /**
     * Grab area (dp): an invisible square, anchored at the bottom-right corner, that is touchable while the
     * edge is closed. A swipe that starts just above / left of the bean goes to the edge, not the app below.
     */
    const val KEY_GRAB = "grab"
    const val GRAB_MIN = 48f
    const val GRAB_MAX = 120f
    const val GRAB_DEF = 72f
    fun grabDp(ctx: Context): Float = prefs(ctx).getFloat(KEY_GRAB, GRAB_DEF).coerceIn(GRAB_MIN, GRAB_MAX)
    fun setGrabDp(ctx: Context, v: Float) {
        prefs(ctx).edit().putFloat(KEY_GRAB, v.coerceIn(GRAB_MIN, GRAB_MAX)).apply()
    }

    /** Boolean: draw the idle grab square on the overlay, so the touch area is visible while adjusting it. */
    const val KEY_TOUCH_SHOW = "touch_show"
    fun touchShow(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_TOUCH_SHOW, false)

    fun setIdleDp(ctx: Context, v: Float) {
        prefs(ctx).edit().putFloat(KEY_IDLE_DP, v.coerceIn(IDLE_MIN, IDLE_MAX)).apply()
    }

    fun setOpenDp(ctx: Context, v: Float) {
        prefs(ctx).edit().putFloat(KEY_OPEN_DP, v.coerceIn(OPEN_MIN, OPEN_MAX)).apply()
    }

    // ---------------------------------------------------------------- looks and feel
    const val KEY_ACCENT = "accent"            // Int ARGB
    const val KEY_GRADIENT = "gradient"        // Int 0..100, warmth/strength of the corner gradient
    const val KEY_GLOW = "glow"                // Boolean, accent glow on touch / open
    const val KEY_SPEED = "speed"              // Int 0..100, 0 = slow and fluid, 100 = snappy
    const val KEY_BOUNCE = "bounce"            // Int 0..100, 0 = no overshoot, 100 = very bouncy
    const val KEY_HAPTICS = "haptics"          // Boolean
    const val KEY_IDLE_STYLE = "idle_style"    // String, locked to "glyph"
    const val KEY_VISUALIZER = "visualizer"    // Boolean, music bars on the idle bean while playing
    const val KEY_PREVIEW = "preview"          // String "", "idle" or "open"; transient, set by the settings screen

    const val KEY_LIVE = "live"                // Boolean, live activities (locked on)
    @Suppress("UNUSED_PARAMETER")
    fun live(ctx: Context): Boolean = true

    val ACCENTS = intArrayOf(
        0xFFFF4B3E.toInt(), // red (default)
        0xFFFF9F0A.toInt(), // orange
        0xFFFFD60A.toInt(), // yellow
        0xFF30D158.toInt(), // green
        0xFF0A84FF.toInt(), // blue
        0xFFBF5AF2.toInt(), // purple
        0xFFFF375F.toInt(), // pink
        0xFFF2EDE4.toInt()  // cream / white
    )
    const val FILL_FLAT = "flat"
    const val FILL_DOTS = "dots"
    val VIZ_STYLES = listOf("bars", "spectrum", "rays", "wave", "dots")
    const val PREVIEW_NONE = ""
    const val PREVIEW_IDLE = "idle"
    const val PREVIEW_OPEN = "open"

    fun accent(ctx: Context): Int = prefs(ctx).getInt(KEY_ACCENT, ACCENTS[0])
    // The look is black and white plus the accent: these options are locked (no control sets them).
    @Suppress("UNUSED_PARAMETER")
    fun gradient(ctx: Context): Int = 0
    fun glow(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_GLOW, true)
    fun speed(ctx: Context): Int = prefs(ctx).getInt(KEY_SPEED, 50).coerceIn(0, 100)
    @Suppress("UNUSED_PARAMETER")
    fun bounce(ctx: Context): Int = 100
    @Suppress("UNUSED_PARAMETER")
    fun haptics(ctx: Context): Boolean = true
    @Suppress("UNUSED_PARAMETER")
    fun idleStyle(ctx: Context): String = "glyph"

    const val KEY_FLARE = "flare"              // Float dp 0..28, edge fillets where the shape meets the screen edges
    const val FLARE_MAX = 28f
    const val FLARE_DEF = 10f
    fun flareDp(ctx: Context): Float = prefs(ctx).getFloat(KEY_FLARE, FLARE_DEF).coerceIn(0f, FLARE_MAX)

    const val KEY_FILL = "fill"                // String, one of FILLS
    /**
     * The fill pattern only. Legacy ids ("gradient", "solid") had no pattern and map to [FILL_FLAT];
     * the gradient is now its own switch ([KEY_GRAD_ON]) and is painted under whatever pattern is set.
     */
    @Suppress("UNUSED_PARAMETER")
    fun fill(ctx: Context): String = FILL_FLAT

    /** Gradient on/off. When on, the gradient is painted under any fill pattern. */
    const val KEY_GRAD_ON = "grad_on"
    @Suppress("UNUSED_PARAMETER")
    fun gradOn(ctx: Context): Boolean = false

    const val KEY_VIZ_STYLE = "viz_style"      // String, one of VIZ_STYLES
    fun vizStyle(ctx: Context): String =
        prefs(ctx).getString(KEY_VIZ_STYLE, "spectrum")?.takeIf { it in VIZ_STYLES } ?: "spectrum" // removed ids -> spectrum
    @Suppress("UNUSED_PARAMETER")
    fun visualizer(ctx: Context): Boolean = true
    fun preview(ctx: Context): String = prefs(ctx).getString(KEY_PREVIEW, PREVIEW_NONE) ?: PREVIEW_NONE

    /** Stiffness multiplier from KEY_SPEED: 0.55 (fluid) .. 1.6 (snappy). */
    fun stiffnessScale(ctx: Context): Float = 0.55f + 1.05f * speed(ctx) / 100f
    /** Damping ratio for open motion from KEY_BOUNCE: 0.98 (none) .. 0.55 (bouncy). */
    fun openDamping(ctx: Context): Float = 0.98f - 0.43f * bounce(ctx) / 100f

    /**
     * The editable looks-and-feel settings a saved look stores. Windows, apps and sizes are not in here, and
     * nothing else is ever restored (see [restore]), so a removed option cannot come back from an old look.
     */
    val LOOK_KEYS = arrayOf(KEY_ACCENT, KEY_GLOW, KEY_OPACITY, KEY_FLARE, KEY_VIZ_STYLE, KEY_SPEED)

    /** Save the current values of [keys] so they can be put back (used by Undo). */
    fun snapshot(ctx: Context, keys: Array<String> = LOOK_KEYS): Map<String, Any?> {
        val sp = prefs(ctx)
        val all = sp.all
        val out = LinkedHashMap<String, Any?>()
        for (k in keys) if (all.containsKey(k)) out[k] = all[k]
        return out
    }

    /** Put back a snapshot taken by [snapshot]. Keys outside [LOOK_KEYS] are skipped. */
    fun restore(ctx: Context, snap: Map<String, Any?>) {
        val e = prefs(ctx).edit()
        for ((k, v) in snap) {
            if (k !in LOOK_KEYS) continue
            when (v) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Float -> e.putFloat(k, v)
                is Long -> e.putLong(k, v)
                is String -> e.putString(k, v)
                else -> e.remove(k)
            }
        }
        e.apply()
    }

    const val KEY_BEAT = "beat"               // Boolean, whole edge reacts to music beats (default on)
    @Suppress("UNUSED_PARAMETER")
    fun beat(ctx: Context): Boolean = true

    const val KEY_BEAT_AMOUNT = "beat_amount"  // Int 0..100, idle bean bounce with the beat (100 = +12% scale)
    @Suppress("UNUSED_PARAMETER")
    fun beatAmount(ctx: Context): Int = 100

    const val KEY_OPACITY = "opacity"          // Int 30..100 %, alpha of the whole overlay
    const val OPACITY_MIN = 30
    fun opacityPct(ctx: Context): Int = prefs(ctx).getInt(KEY_OPACITY, 100).coerceIn(OPACITY_MIN, 100)

    const val KEY_BASE = "base"                // Int colour, solid base of the edge shape (default black)
    val BASES = intArrayOf(
        0xFF000000.toInt(), 0xFF111114.toInt(), 0xFF1C1C1E.toInt(), 0xFF1A1424.toInt(),
        0xFF0E1A24.toInt(), 0xFF1A2418.toInt(), 0xFF241414.toInt(), 0xFF2C2C2E.toInt()
    )
    @Suppress("UNUSED_PARAMETER")
    fun base(ctx: Context): Int = 0xFF000000.toInt()

    /** Gradient colour 1: the hot core of the edge shape. 0 = Auto (follows the accent). */
    const val KEY_GRAD1 = "grad1"
    /** Gradient colour 2: the outer band of the edge shape. 0 = Edge (follows the base colour). */
    const val KEY_GRAD2 = "grad2"
    @Suppress("UNUSED_PARAMETER")
    fun grad1(ctx: Context): Int = 0
    @Suppress("UNUSED_PARAMETER")
    fun grad2(ctx: Context): Int = 0

    /** Schema version of the looks settings; used to migrate the old fill semantics once. */
    const val KEY_LOOKS_V = "looks_v"
    const val LOOKS_V = 2

    /**
     * Migrates the old fill semantics: "gradient"/"solid"/"dots" used to double as the gradient switch.
     * Now [KEY_FILL] is only the texture and [KEY_GRAD_ON] is the gradient switch, so an existing install
     * keeps the exact look it had before the split.
     */
    fun migrate(ctx: Context) {
        val sp = prefs(ctx)
        if (sp.getInt(KEY_LOOKS_V, 0) >= LOOKS_V) return
        val e = sp.edit()
        when (sp.getString(KEY_FILL, "gradient")) {
            "dots" -> {
                e.putString(KEY_FILL, FILL_DOTS)
                e.putBoolean(KEY_GRAD_ON, false)
            }
            "solid" -> {
                e.putString(KEY_FILL, FILL_FLAT)
                e.putBoolean(KEY_GRAD_ON, false)
            }
            else -> {
                e.putString(KEY_FILL, FILL_FLAT)
                e.putBoolean(KEY_GRAD_ON, true)
            }
        }
        e.putInt(KEY_LOOKS_V, LOOKS_V).apply()
    }

    /**
     * The look is black and white plus the accent (2026-11-11): no themes, no edge colour, no gradient,
     * no texture and no windows editor. This migration clears whatever an earlier build had chosen.
     */
    private const val LOOKS_V2 = 6
    fun migrateLook(ctx: Context) {
        val sp = prefs(ctx)
        if (sp.getInt(KEY_LOOKS_V, 0) >= LOOKS_V2) return
        sp.edit()
            .putInt(KEY_BASE, 0xFF000000.toInt())
            .putBoolean(KEY_GRAD_ON, false)
            .putInt(KEY_GRADIENT, 0)
            .putInt(KEY_GRAD1, 0)
            .putInt(KEY_GRAD2, 0)
            .putString(KEY_FILL, FILL_FLAT)
            .putString(KEY_IDLE_STYLE, "glyph")
            .putBoolean(KEY_HAPTICS, true)
            .putBoolean(KEY_VISUALIZER, true)
            .putBoolean(KEY_BEAT, true)
            .putBoolean(KEY_LIVE, true)
            .putInt(KEY_BEAT_AMOUNT, 100)
            .putInt(KEY_SWIPE_SENS, 2)
            .remove("icon_source")
            .remove("windows")
            .remove("theme_json")
            .remove("themes_json")
            .remove("custom_wins")
            .putInt(KEY_LOOKS_V, LOOKS_V2)
            .apply()
    }

    /** Boolean, the clipboard popup closes after a clip is copied (default true). */
    const val KEY_CLIP_CLOSE = "clip_close"
    fun clipClose(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_CLIP_CLOSE, true)

    const val KEY_SWIPE_SENS = "swipe_sens"    // Int 0..100, window swipe sensitivity (default 2 = light)
    @Suppress("UNUSED_PARAMETER")
    fun swipeSens(ctx: Context): Int = 2

    // ---------------------------------------------------------------- apps layout
    /** Read only by the unused WinAppsRevamp prototype; the APPS window uses [KEY_APPS] + the w_apps_* keys. */
    const val KEY_APP_GRID = "app_grid"

    // ---------------------------------------------------------------- media
    /**
     * Packages that must never be picked up as the media source: system widgets and the launcher publish
     * their own media sessions (Nothing's "Essential News" widget plays news as a podcast session), and the
     * media window must follow the user's music instead.
     */
    const val KEY_MEDIA_BLOCKED = "media_blocked"   // StringSet of packages
    /** The packages the user blocked on the media screen. */
    fun mediaBlocked(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_MEDIA_BLOCKED, null) ?: DEFAULT_MEDIA_BLOCKED
    fun setMediaBlocked(ctx: Context, set: Set<String>) {
        prefs(ctx).edit().putStringSet(KEY_MEDIA_BLOCKED, HashSet(set)).apply()
    }

    /** System / launcher / widget packages that are never a music source. */
    val DEFAULT_MEDIA_BLOCKED: Set<String> = setOf(
        "com.nothing.hearthstone",   // Nothing Phone: the "Essential News" widget (podcast session)
        "com.nothing.launcher",
        "com.nothing.weather",
        "com.nothing.gallery",
        "com.nothing.clock",
        "com.nothing.notes",
        "com.android.systemui",
        "com.android.server.telecom",
        "com.android.bluetooth"
    )

    // ---------------------------------------------------------------- pause in sensitive apps
    const val KEY_PAUSE_ON = "pause_on"            // Boolean, master switch (default true)
    const val KEY_PAUSE_APPS = "pause_apps"        // StringSet of packages; absent = built-in default
    const val KEY_PAUSED_BY_GATE = "paused_by_gate" // Boolean, the gate turned the overlay off
    const val KEY_PAUSED_PKG = "paused_pkg"        // String, the app that caused the pause
    const val KEY_PAUSED_AT = "paused_at"          // Long, wall clock of the pause

    /** Banking / UPI / ID apps that refuse to run while a third-party accessibility service is on. */
    val SENSITIVE_APPS = arrayOf(
        "in.org.npci.upiapp", "com.phonepe.app", "net.one97.paytm", "com.google.android.apps.nbu.paisa.user",
        "com.sbi.lotusintouch", "com.sbi.SBIFreedomPlus", "com.snapwork.hdfc", "com.hdfcbank.payzapp",
        "com.csam.icici.bank.imobile", "com.icicibank.pockets", "com.axis.mobile", "com.msf.kbank.mobile",
        "com.bankofbaroda.mconnect", "com.Version1", "com.canarabank.mobility", "com.infrasoft.uboi",
        "com.idfcfirstbank.optimus", "com.dreamplug.androidapp", "com.epifi.paisa", "money.jupiter",
        "com.nextbillion.groww", "com.zerodha.kite3", "com.digilocker.android", "in.gov.uidai.mAadhaarPlus",
        "in.gov.uidai.facerd", "in.gov.uidai.pehchaan", "com.mobikwik_new", "com.freecharge.android",
        "com.yesbank", "com.indusind.mobile", "com.fss.indus", "com.unionbankofindia.vyom", "com.iob.mobile",
        "com.boi.mpay", "com.lcode.centralbankindia"
    )

    fun pauseOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PAUSE_ON, true)

    /** The paused packages: the user's set, or (never edited) the installed known / bank-looking apps. */
    fun pauseApps(ctx: Context): Set<String> {
        val s = prefs(ctx).getStringSet(KEY_PAUSE_APPS, null)
        return if (s != null) HashSet(s) else defaultPauseApps(ctx)
    }

    fun setPauseApps(ctx: Context, set: Set<String>) {
        prefs(ctx).edit().putStringSet(KEY_PAUSE_APPS, HashSet(set)).apply()
    }

    /** Installed apps from [SENSITIVE_APPS], plus any package name containing ".bank", "bank.", "upi" or ".npci". */
    fun defaultPauseApps(ctx: Context): Set<String> {
        val out = HashSet<String>()
        val known = SENSITIVE_APPS.toHashSet()
        try {
            for (ai in ctx.packageManager.getInstalledApplications(0)) {
                val p = ai.packageName
                if (p == ctx.packageName) continue
                val l = p.lowercase()
                if (p in known || l.contains(".bank") || l.contains("bank.") || l.contains("upi") || l.contains(".npci"))
                    out.add(p)
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun apps(ctx: Context): List<ComponentName> {
        val s = prefs(ctx).getString(KEY_APPS, null) ?: return defaultApps(ctx)
        val out = ArrayList<ComponentName>()
        for (part in s.split("|")) {
            if (part.isEmpty()) continue
            val c = ComponentName.unflattenFromString(part) ?: continue
            if (c !in out) out.add(c)
            if (out.size >= MAX_APPS) break
        }
        return out
    }

    fun setApps(ctx: Context, list: List<ComponentName>) {
        val s = list.distinct().take(MAX_APPS).joinToString("|") { it.flattenToString() }
        prefs(ctx).edit().putString(KEY_APPS, s).apply()
    }

    private fun defaultApps(ctx: Context): List<ComponentName> {
        val pm = ctx.packageManager
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY),
            Intent(android.provider.Settings.ACTION_SETTINGS)
        )
        val out = ArrayList<ComponentName>()
        for (i in intents) {
            val ai = try {
                pm.resolveActivity(i, 0)?.activityInfo
            } catch (e: Exception) {
                null
            } ?: continue
            if (ai.packageName == "android") continue
            val c = ComponentName(ai.packageName, ai.name)
            if (c !in out) out.add(c)
        }
        return out
    }
}
