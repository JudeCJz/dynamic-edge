package com.dynedge

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification

/** Turns navigation, ride / delivery, determinate-progress and OTP notifications into live activities. */
object NotifLive {
    const val KEY_NAV = "live_nav"
    const val KEY_RIDES = "live_rides"
    const val KEY_PROGRESS = "live_progress"
    const val KEY_OTP = "live_otp"

    private const val OTP_LIFETIME_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())
    private val otpExpiry = HashMap<String, Long>()

    private val NAV_PACKAGES = hashSetOf(
        "com.google.android.apps.maps", "com.waze", "com.here.app.maps", "net.osmand", "net.osmand.plus",
        "app.organicmaps", "com.mapquest.android.ace", "com.sygic.aura", "com.tomtom.gplay.navapp",
        "com.mapmyindia.app.mapmyindia", "com.mmi.maps", "com.apple.android.maps"
    )
    private val RIDE_PACKAGES = hashSetOf("com.ubercab", "com.olacabs.customer", "com.rapido.passenger")
    private val DELIVERY_PACKAGES = hashSetOf(
        "in.swiggy.android", "com.application.zomato", "com.grofers.customerapp", "com.zeptoconsumerapp",
        "in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping", "com.flipkart.android"
    )

    private val DIST = Regex("""(\d+(?:[.,]\d+)?)\s?(km|mi|m|ft|yd)\b""", RegexOption.IGNORE_CASE)
    private val ETA_MIN = Regex("""(\d{1,3})\s?(?:min|mins|minute|minutes)\b""", RegexOption.IGNORE_CASE)
    private val ETA_AT = Regex(
        """(?:arriv\w*|reach\w*|deliver\w*|eta)\s+(?:at|by|:)?\s*(\d{1,2}[:.]\d{2}\s?(?:am|pm)?)""",
        RegexOption.IGNORE_CASE
    )
    private val STATUS_WORDS = Regex(
        """arriv|deliver|driver|captain|partner|on the way|on its way|out for|pickup|pick-up|trip|ride""",
        RegexOption.IGNORE_CASE
    )
    private val OTP_KEYWORD = Regex(
        """otp|code|verification|verify|passcode|pin\b|is your|one[- ]?time|password""", RegexOption.IGNORE_CASE
    )
    private val OTP_AFTER_WORD = Regex(
        """(?:otp|code|passcode|pin|password)\D{0,25}?(?<![\d.,])(\d{4,8})(?![\d]|[.,]\d)""", RegexOption.IGNORE_CASE
    )
    private val OTP_BEFORE_WORD = Regex(
        """(?<![\d.,])(\d{4,8})(?!\d|[.,]\d)\D{0,12}?(?:is your|is the|otp|code)""", RegexOption.IGNORE_CASE
    )

    fun classify(ctx: Context, sbn: StatusBarNotification, key: String): LiveActivity? {
        val n = sbn.notification ?: return null
        val ex = n.extras ?: return null
        val pkg = sbn.packageName
        if (pkg == ctx.packageName) return null
        val prefs = Prefs.prefs(ctx)
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()

        if (pkg in NAV_PACKAGES && prefs.getBoolean(KEY_NAV, true)) {
            nav(key, n, ongoing, title, text)?.let { return it }
        }
        if ((pkg in RIDE_PACKAGES || pkg in DELIVERY_PACKAGES || isFuzzyDelivery(pkg)) &&
            prefs.getBoolean(KEY_RIDES, true)
        ) {
            ride(key, n, pkg, ongoing, title, text, big)?.let { return it }
        }
        if (prefs.getBoolean(KEY_PROGRESS, true)) {
            progress(ctx, key, n, pkg, ongoing, title)?.let { return it }
        }
        if (!ongoing && prefs.getBoolean(KEY_OTP, true)) {
            otp(ctx, key, n, title, text, big)?.let { return it }
        }
        return null
    }

    private fun isFuzzyDelivery(pkg: String): Boolean =
        pkg.contains("rapido") || pkg.contains("blinkit") || pkg.contains("zepto")

    private fun nav(key: String, n: Notification, ongoing: Boolean, title: String, text: String): LiveActivity? {
        if (!ongoing && n.category != Notification.CATEGORY_NAVIGATION) return null
        if (title.isEmpty() && text.isEmpty()) return null
        val m = DIST.find(title) ?: DIST.find(text)
        val chip = m?.let { it.groupValues[1] + " " + it.groupValues[2].lowercase() }.orEmpty()
        var instr = if (m != null && title.contains(m.value)) title.replace(m.value, "").trim(' ', '-', ',', '.', ':') else title
        if (instr.isEmpty()) instr = text
        if (instr.isEmpty()) instr = "Navigation"
        return LiveActivity(
            key, LiveActivity.Kind.PROGRESS, instr.take(18), -1f, 0L, false,
            R.drawable.ic_navigation, true, n.contentIntent, null, null,
            chip = chip.take(7), tapOpens = true
        )
    }

    private fun ride(
        key: String, n: Notification, pkg: String, ongoing: Boolean,
        title: String, text: String, big: String
    ): LiveActivity? {
        val all = "$title $text $big"
        var chip = ""
        ETA_MIN.find(all)?.let { chip = it.groupValues[1] + " min" }
        if (chip.isEmpty()) ETA_AT.find(all)?.let { chip = it.groupValues[1].trim() }
        if (!ongoing && (chip.isEmpty() || !STATUS_WORDS.containsMatchIn(all))) return null
        if (title.isEmpty() && text.isEmpty()) return null
        val ex = n.extras
        val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val indet = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val f = if (max > 0 && !indet) (ex.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / max).coerceIn(0f, 1f) else -1f
        val isRide = pkg in RIDE_PACKAGES || pkg.contains("rapido")
        val status = title.ifEmpty { text }
        return LiveActivity(
            key, LiveActivity.Kind.PROGRESS, status.take(18), f, 0L, false,
            if (isRide) R.drawable.ic_car else R.drawable.ic_package, true, n.contentIntent, null, null,
            chip = chip.take(7), tapOpens = true
        )
    }

    private fun progress(
        ctx: Context, key: String, n: Notification, pkg: String, ongoing: Boolean, title: String
    ): LiveActivity? {
        val fgs = n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        if (!ongoing && !fgs) return null
        val c = n.category
        if (c == Notification.CATEGORY_TRANSPORT || c == Notification.CATEGORY_CALL ||
            c == Notification.CATEGORY_ALARM || c == "timer" || c == "stopwatch") return null
        val ex = n.extras
        if (ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null
        val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        if (max <= 0 || ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) return null
        val f = (ex.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / max).coerceIn(0f, 1f)
        val label = if (title.isNotEmpty()) title else try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            "Progress"
        }
        return LiveActivity(
            key, LiveActivity.Kind.PROGRESS, label.take(18), f, 0L, false,
            R.drawable.ic_download, true, n.contentIntent, null, null,
            chip = (f * 100f).toInt().toString() + "%", tapOpens = true
        )
    }

    private fun otp(
        ctx: Context, key: String, n: Notification, title: String, text: String, big: String
    ): LiveActivity? {
        val all = "$title $text $big"
        if (!OTP_KEYWORD.containsMatchIn(all)) return null
        val code = (OTP_AFTER_WORD.find(all) ?: OTP_BEFORE_WORD.find(all))
            ?.groupValues?.get(1) ?: return null

        val now = System.currentTimeMillis()
        val exp = otpExpiry[key] ?: (now + OTP_LIFETIME_MS).also {
            otpExpiry[key] = it
            if (otpExpiry.size > 64) otpExpiry.entries.removeAll { e -> e.value < now - 600_000L }
            main.postDelayed({ LiveBus.remove(key) }, OTP_LIFETIME_MS)
        }
        if (now >= exp) return null

        val i = Intent(ctx, OtpReceiver::class.java)
            .putExtra(OtpReceiver.EXTRA_CODE, code)
            .putExtra(OtpReceiver.EXTRA_KEY, key)
        val pi = PendingIntent.getBroadcast(
            ctx, key.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return LiveActivity(
            // ongoing: the bean's 4 s auto-remove must not cut it short; NotifLive removes it after 60 s
            key, LiveActivity.Kind.PROGRESS, "Code", -1f, 0L, false,
            R.drawable.ic_key, true, pi, null, null,
            chip = code.take(8), tapOpens = true
        )
    }
}
