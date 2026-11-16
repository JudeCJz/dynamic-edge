package com.dynedge

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Curated destinations for shortcut tiles ([ShortcutStore]): Settings pages and a few useful app actions.
 *
 * Each [Entry] is an intent action (string literal, so hidden AOSP actions can be listed too), an optional highlight
 * key (AOSP Settings scrolls to and highlights that preference when it is passed as the fragment args key), search
 * keywords, a default icon name ([ShortcutIcons]) and a group (the picker's section header). Entries whose intent does
 * not resolve on this phone are hidden ([available]). To add one: append a line to a [group] block in [ALL] with a
 * unique id; keep the keywords lowercase.
 *
 * The action string stored in a tile may also be a special form, built by [build]:
 * - `dynedge:cat:<CATEGORY>`: the default app for an app category (Intent.makeMainSelectorActivity), e.g. calculator.
 * - `dynedge:data:<ACTION>|<uri>|<mime>`: an action with optional data and mime type (either may be empty).
 * - `dynedge:self:<ACTION>`: a Settings page for this app, passing its package as an extra.
 * - `dynedge:selfdata:<ACTION>`: the same but passing `package:<this app>` as the intent data.
 * Everything else is a plain action string. Old stored tiles keep working because plain actions are unchanged.
 */
internal object ShortcutCatalog {

    private const val SELF_PKG = "com.dynedge"
    private const val P = "dynedge:"

    class Entry(
        val id: String,
        val label: String,
        val keywords: String,
        val action: String,
        val hl: String? = null,
        val icon: String = "settings"
    ) {
        /** The picker section this entry is listed under. */
        var group: String = ""

        private val hay = (label + " " + keywords).lowercase()

        fun matches(tokens: List<String>): Boolean = tokens.all { hay.contains(it) }

        fun intent(): Intent = build(action, hl)
    }

    /** Builds the intent for a stored/cataloged action string (plain action, or one of the special forms above). */
    fun build(action: String, hl: String?): Intent {
        val i: Intent = when {
            action.startsWith(P + "cat:") ->
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, action.removePrefix(P + "cat:"))
            action.startsWith(P + "data:") -> {
                val parts = action.removePrefix(P + "data:").split('|')
                val it = Intent(parts[0])
                val uri = parts.getOrNull(1).orEmpty()
                val mime = parts.getOrNull(2).orEmpty()
                when {
                    uri.isNotEmpty() && mime.isNotEmpty() -> it.setDataAndType(Uri.parse(uri), mime)
                    uri.isNotEmpty() -> it.data = Uri.parse(uri)
                    mime.isNotEmpty() -> it.type = mime
                }
                it
            }
            action.startsWith(P + "selfdata:") ->
                Intent(action.removePrefix(P + "selfdata:"), Uri.fromParts("package", SELF_PKG, null))
            action.startsWith(P + "self:") ->
                Intent(action.removePrefix(P + "self:"))
                    .putExtra("android.provider.extra.APP_PACKAGE", SELF_PKG)
                    .putExtra("app_package", SELF_PKG)
            else -> Intent(action)
        }
        if (hl != null) {
            i.putExtra(":settings:fragment_args_key", hl)
            i.putExtra(":settings:show_fragment_args", android.os.Bundle().apply { putString(":settings:fragment_args_key", hl) })
        }
        return i
    }

    private const val DEV = "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"
    private const val INFO = "android.settings.DEVICE_INFO_SETTINGS"
    private const val DISPLAY = "android.settings.DISPLAY_SETTINGS"
    private const val SOUND = "android.settings.SOUND_SETTINGS"
    private const val ACC = "android.settings.ACCESSIBILITY_SETTINGS"

    private fun e(id: String, label: String, keywords: String, action: String, hl: String? = null, icon: String = "settings") =
        Entry(id, label, keywords, action, hl, icon)

    private fun cat(c: String) = P + "cat:android.intent.category." + c

    private fun group(name: String, vararg es: Entry): List<Entry> {
        for (x in es) x.group = name
        return es.toList()
    }

    val ALL: List<Entry> =
        group("About phone",
            e("about", "About phone", "device info model android version software information", INFO, null, "smartphone"),
            e("build_number", "Build number", "build number tap seven times developer unlock", INFO, "build_number", "hash"),
            e("android_version", "Android version", "software info release os", INFO, "firmware_version", "smartphone"),
            e("security_patch", "Security patch level", "security update patch android", INFO, "security_key", "shield"),
            e("device_name", "Device name", "rename phone hostname", INFO, "device_name", "smartphone"),
            e("device_model", "Model and hardware", "model hardware serial", INFO, "device_model", "cpu"),
            e("imei", "IMEI", "sim phone number serial", INFO, "imei_info", "hash"),
            e("baseband", "Baseband version", "modem radio", INFO, "base_band", "cpu"),
            e("kernel", "Kernel version", "linux", INFO, "kernel_version", "cpu"),
            e("phone_status", "Status", "ip address mac wifi address uptime bluetooth address", INFO, "status_info", "info"),
            e("system_update", "System update", "software update ota upgrade", "android.settings.SYSTEM_UPDATE_SETTINGS", null, "download"),
            e("regulatory", "Regulatory labels", "regulatory info certification fcc ce", "android.settings.SHOW_REGULATORY_INFO", null, "info"),
            e("webview", "WebView implementation", "webview chrome system browser engine", "android.settings.WEBVIEW_SETTINGS", null, "globe")
        ) + group("Developer options",
            e("developer", "Developer options", "developer dev options debug", DEV, null, "terminal"),
            e("usb_debugging", "USB debugging", "adb developer debug usb", DEV, "enable_adb", "bug"),
            e("wireless_debugging", "Wireless debugging", "adb wifi developer pair", DEV, "toggle_adb_wireless", "wifi"),
            e("oem_unlock", "OEM unlocking", "bootloader unlock developer", DEV, "oem_unlock_enable", "lock"),
            e("stay_awake", "Stay awake", "screen charging developer", DEV, "keep_screen_on", "sun"),
            e("pointer_location", "Pointer location", "touch developer debug", DEV, "pointer_location", "search"),
            e("show_touches", "Show taps", "show touches developer debug", DEV, "show_touches", "search"),
            e("layout_bounds", "Show layout bounds", "developer debug ui bounds", DEV, "debug_layout", "grid"),
            e("anim_scale", "Window animation scale", "animation speed developer", DEV, "window_animation_scale", "zap"),
            e("transition_scale", "Transition animation scale", "animation speed developer", DEV, "transition_animation_scale", "zap"),
            e("animator_scale", "Animator duration scale", "animation speed developer", DEV, "animator_duration_scale", "zap"),
            e("usb_config", "Default USB configuration", "usb mode file transfer developer", DEV, "select_usb_configuration", "link"),
            e("bg_limit", "Background process limit", "developer processes", DEV, "app_process_limit", "cpu"),
            e("dont_keep", "Do not keep activities", "developer destroy", DEV, "immediate_destroy_activities", "wrench"),
            e("mock_location", "Mock location app", "fake gps developer", DEV, "mock_location", "map-pin"),
            e("force_resize", "Force activities resizable", "developer multi window split", DEV, "force_resizable_activities", "grid"),
            e("freeform", "Freeform windows", "developer desktop mode windows", DEV, "enable_freeform_support", "grid"),
            e("force_rtl", "Force right-to-left layout", "developer rtl direction", DEV, "force_rtl_layout_all_locales", "type"),
            e("dev_tiles", "Quick settings developer tiles", "developer tiles quick settings", DEV, "quick_settings_tiles", "grid"),
            e("bugreport", "Take bug report", "developer log", DEV, "bugreport", "bug")
        ) + group("Network",
            e("wifi", "Wi-Fi", "wireless internet network", "android.settings.WIFI_SETTINGS", null, "wifi"),
            e("wifi_saved", "Saved Wi-Fi networks", "known remembered wifi passwords", "android.settings.WIFI_SAVED_NETWORK_SETTINGS", null, "wifi"),
            e("wifi_ip", "Wi-Fi IP settings", "static ip dns", "android.settings.WIFI_IP_SETTINGS", null, "wifi"),
            e("internet", "Internet", "network provider mobile wifi", "android.settings.NETWORK_PROVIDER_SETTINGS", null, "globe"),
            e("internet_panel", "Internet panel", "quick toggle wifi mobile data popup", "android.settings.panel.action.INTERNET_CONNECTIVITY", null, "wifi"),
            e("wireless", "Network and internet", "wireless networks connections", "android.settings.WIRELESS_SETTINGS", null, "globe"),
            e("bluetooth", "Bluetooth", "pair devices headphones", "android.settings.BLUETOOTH_SETTINGS", null, "bluetooth"),
            e("airplane", "Airplane mode", "flight mode radio", "android.settings.AIRPLANE_MODE_SETTINGS", null, "plane"),
            e("mobile_network", "Mobile network", "cellular roaming sim data", "android.settings.DATA_ROAMING_SETTINGS", null, "globe"),
            e("operators", "Network operators", "carrier network select", "android.settings.NETWORK_OPERATOR_SETTINGS", null, "globe"),
            e("esim", "SIMs and eSIM", "sim profiles esim carrier manage", "android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS", null, "card"),
            e("data_usage", "Data usage", "mobile data cellular usage limit", "android.settings.DATA_USAGE_SETTINGS", null, "download"),
            e("hotspot", "Hotspot and tethering", "tether wifi hotspot usb tethering portable", "android.settings.TETHER_SETTINGS", null, "wifi"),
            e("vpn", "VPN", "virtual private network", "android.settings.VPN_SETTINGS", null, "shield"),
            e("nfc", "NFC", "near field contactless tap", "android.settings.NFC_SETTINGS", null, "smartphone"),
            e("nfc_sharing", "NFC sharing", "android beam share nfc", "android.settings.NFCSHARING_SETTINGS", null, "smartphone"),
            e("nfc_payment", "Contactless payments", "nfc tap and pay wallet default payment app", "android.settings.NFC_PAYMENT_SETTINGS", null, "wallet"),
            e("cast", "Cast", "screen mirror chromecast display", "android.settings.CAST_SETTINGS", null, "cast")
        ) + group("Battery",
            e("battery", "Battery usage", "battery power usage drain", "android.intent.action.POWER_USAGE_SUMMARY", null, "battery"),
            e("battery_saver", "Battery saver", "power saving low power", "android.settings.BATTERY_SAVER_SETTINGS", null, "battery"),
            e("battery_opt", "Battery optimisation", "ignore battery optimization doze unrestricted", "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS", null, "battery")
        ) + group("Display",
            e("display", "Display", "screen settings", DISPLAY, null, "sun"),
            e("brightness", "Brightness", "screen light level", DISPLAY, "brightness", "sun"),
            e("adaptive_brightness", "Adaptive brightness", "auto brightness", DISPLAY, "auto_brightness", "sun"),
            e("dark_mode", "Dark mode", "dark theme night ui", DISPLAY, "dark_ui_mode", "moon"),
            e("dark_schedule", "Dark theme schedule", "dark mode sunset automatic", "android.settings.DARK_THEME_SETTINGS", null, "moon"),
            e("night_light", "Night light", "blue light filter eye comfort", "android.settings.NIGHT_DISPLAY_SETTINGS", null, "moon"),
            e("extra_dim", "Extra dim", "reduce bright colors dimmer", "android.settings.REDUCE_BRIGHT_COLORS_SETTINGS", null, "contrast"),
            e("screen_timeout", "Screen timeout", "sleep display off", DISPLAY, "screen_timeout", "clock"),
            e("auto_rotate", "Auto-rotate", "rotation orientation", DISPLAY, "auto_rotate", "rotate"),
            e("font_size", "Font size", "text size display size", DISPLAY, "font_size", "type"),
            e("refresh_rate", "Smooth display", "refresh rate 90hz 120hz", DISPLAY, "peak_refresh_rate", "zap"),
            e("screen_saver", "Screen saver", "daydream dream", "android.settings.DREAM_SETTINGS", null, "moon"),
            e("wallpaper", "Wallpaper", "background home screen", "android.intent.action.SET_WALLPAPER", null, "image")
        ) + group("Sound and notifications",
            e("sound", "Sound", "volume ringtone vibration", SOUND, null, "volume"),
            e("volume_panel", "Volume panel", "volume sliders media ring alarm popup", "android.settings.panel.action.VOLUME", null, "volume"),
            e("media_volume", "Media volume", "music video volume", SOUND, "media_volume", "volume"),
            e("ring_volume", "Ring volume", "ringer volume", SOUND, "ring_volume", "volume"),
            e("alarm_volume", "Alarm volume", "alarm loudness", SOUND, "alarm_volume", "alarm"),
            e("vibration", "Vibrate for calls", "vibrate when ringing haptic", SOUND, "vibrate_when_ringing", "bell"),
            e("ringtone", "Phone ringtone", "ring tone call", SOUND, "phone_ringtone", "bell"),
            e("notification_sound", "Notification sound", "default alert tone", SOUND, "notification_ringtone", "bell"),
            e("alarm_sound", "Default alarm sound", "alarm ringtone tone", SOUND, "alarm_ringtone", "alarm"),
            e("dnd", "Do not disturb", "dnd silent zen priority", "android.settings.ZEN_MODE_SETTINGS", null, "moon"),
            e("dnd_priority", "Do not disturb priority", "dnd allowed calls messages people zen", "android.settings.ZEN_MODE_PRIORITY_SETTINGS", null, "moon"),
            e("dnd_schedules", "Do not disturb schedules", "dnd automatic rules bedtime zen", "android.settings.ZEN_MODE_AUTOMATION_SETTINGS", null, "moon"),
            e("dnd_access", "Do not disturb access", "apps allowed dnd policy", "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS", null, "moon"),
            e("notifications", "App notifications", "notifications all apps manage", "android.settings.ALL_APPS_NOTIFICATION_SETTINGS", null, "bell"),
            e("notification_access", "Notification access", "notification listener apps read", "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS", null, "bell"),
            e("notification_history", "Notification history", "past notifications log", "android.settings.NOTIFICATION_HISTORY", null, "clock"),
            e("fullscreen_intent", "Full screen notifications", "full screen intent permission calls alarms", "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", null, "bell")
        ) + group("Apps and permissions",
            e("apps", "Apps", "applications installed manage", "android.settings.APPLICATION_SETTINGS", null, "smartphone"),
            e("all_apps", "All apps", "manage applications list", "android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS", null, "grid"),
            e("default_apps", "Default apps", "browser sms dialer defaults", "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", null, "smartphone"),
            e("default_home", "Default home app", "launcher home", "android.settings.HOME_SETTINGS", null, "home"),
            e("open_links", "Open by default", "supported links domain urls app links", "android.settings.MANAGE_DOMAIN_URLS", null, "link"),
            e("assistant", "Assistant and voice input", "voice assistant default", "android.settings.VOICE_INPUT_SETTINGS", null, "mic"),
            e("permission_manager", "Permission manager", "app permissions camera microphone location contacts", "android.intent.action.MANAGE_PERMISSIONS", null, "shield"),
            e("permission_usage", "Permission usage", "privacy dashboard recent access", "android.intent.action.REVIEW_PERMISSION_USAGE", null, "eye"),
            e("permission_history", "Permission history", "privacy timeline who accessed", "android.intent.action.REVIEW_PERMISSION_HISTORY", null, "clock"),
            e("usage_access", "Usage access", "app usage stats permission", "android.settings.USAGE_ACCESS_SETTINGS", null, "clock"),
            e("overlay", "Display over other apps", "overlay draw permission system alert window", "android.settings.action.MANAGE_OVERLAY_PERMISSION", null, "smartphone"),
            e("write_settings", "Modify system settings", "write settings permission", "android.settings.action.MANAGE_WRITE_SETTINGS", null, "wrench"),
            e("unknown_sources", "Install unknown apps", "sideload unknown sources apk", "android.settings.MANAGE_UNKNOWN_APP_SOURCES", null, "download"),
            e("all_files", "All files access", "manage external storage permission", "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION", null, "folder"),
            e("media_management", "Media management apps", "modify delete media permission", "android.settings.REQUEST_MANAGE_MEDIA", null, "image"),
            e("exact_alarm", "Alarms and reminders", "schedule exact alarm permission", "android.settings.REQUEST_SCHEDULE_EXACT_ALARM", null, "alarm"),
            e("autofill_credentials", "Passwords, passkeys and autofill", "credential provider autofill password manager", "android.settings.CREDENTIAL_PROVIDER", null, "key")
        ) + group("Security, privacy and location",
            e("security", "Security", "screen lock fingerprint face unlock", "android.settings.SECURITY_SETTINGS", null, "lock"),
            e("screen_lock", "Set screen lock", "pin pattern password lock screen", "android.app.action.SET_NEW_PASSWORD", null, "lock"),
            e("biometric", "Add biometrics", "fingerprint face unlock enroll biometric", "android.settings.BIOMETRIC_ENROLL", null, "key"),
            e("fingerprint", "Add fingerprint", "fingerprint enroll unlock", "android.settings.FINGERPRINT_ENROLL", null, "key"),
            e("privacy", "Privacy", "permissions dashboard ads", "android.settings.PRIVACY_SETTINGS", null, "shield"),
            e("location", "Location", "gps position", "android.settings.LOCATION_SOURCE_SETTINGS", null, "map-pin"),
            e("wallet_settings", "Wallet quick access", "quick access wallet cards payment", "android.settings.QUICK_ACCESS_WALLET_SETTINGS", null, "wallet"),
            e("accounts", "Accounts and sync", "sync google account", "android.settings.SYNC_SETTINGS", null, "user"),
            e("add_account", "Add account", "sign in google email", "android.settings.ADD_ACCOUNT_SETTINGS", null, "user"),
            e("users", "Users", "multiple users guest profile", "android.settings.USER_SETTINGS", null, "user")
        ) + group("Accessibility",
            e("accessibility", "Accessibility", "talkback magnification services", ACC, null, "access"),
            e("captions", "Captions", "subtitles accessibility", "android.settings.CAPTIONING_SETTINGS", null, "message"),
            e("text_reading", "Text and reading options", "font display size bold text accessibility", "android.settings.TEXT_READING_SETTINGS", null, "type"),
            e("color_inversion", "Color inversion", "invert colours accessibility", "android.settings.COLOR_INVERSION_SETTINGS", null, "contrast"),
            e("color_correction", "Color correction", "colour blind accessibility", "android.settings.COLOR_CORRECTION_SETTINGS", null, "eye"),
            e("hearing_aids", "Hearing devices", "hearing aid accessibility", "android.settings.HEARING_AID_SETTINGS", null, "bluetooth")
        ) + group("System",
            e("date_time", "Date and time", "clock timezone", "android.settings.DATE_SETTINGS", null, "clock"),
            e("language", "Language", "locale region", "android.settings.LOCALE_SETTINGS", null, "globe"),
            e("keyboard", "Keyboard", "input method on-screen", "android.settings.INPUT_METHOD_SETTINGS", null, "keyboard"),
            e("keyboard_subtypes", "Keyboard languages", "input method subtypes layouts", "android.settings.INPUT_METHOD_SUBTYPE_SETTINGS", null, "keyboard"),
            e("physical_keyboard", "Physical keyboard", "hardware keyboard", "android.settings.HARD_KEYBOARD_SETTINGS", null, "keyboard"),
            e("dictionary", "Personal dictionary", "user dictionary spell", "android.settings.USER_DICTIONARY_SETTINGS", null, "file"),
            e("storage", "Storage", "internal storage space files", "android.settings.INTERNAL_STORAGE_SETTINGS", null, "hard-drive"),
            e("sd_card", "Memory card", "sd card external storage", "android.settings.MEMORY_CARD_SETTINGS", null, "hard-drive"),
            e("free_space", "Free up space", "clean storage manage", "android.os.storage.action.MANAGE_STORAGE", null, "hard-drive"),
            e("print", "Printing", "printer", "android.settings.ACTION_PRINT_SETTINGS", null, "printer"),
            e("search_settings", "Search settings", "find setting", "android.search.action.SEARCH_SETTINGS", null, "search"),
            e("settings", "Settings", "main settings home", "android.settings.SETTINGS", null, "settings")
        ) + group("Quick actions",
            e("alarms", "Alarms", "alarm clock wake up list", "android.intent.action.SHOW_ALARMS", null, "alarm"),
            e("new_alarm", "New alarm", "set alarm create wake", "android.intent.action.SET_ALARM", null, "alarm"),
            e("timers", "Timers", "timer list countdown", "android.intent.action.SHOW_TIMERS", null, "timer"),
            e("new_timer", "New timer", "set timer countdown", "android.intent.action.SET_TIMER", null, "timer"),
            e("calendar", "Calendar", "agenda events schedule", cat("APP_CALENDAR"), null, "calendar"),
            e("calendar_today", "Calendar today", "agenda events now", P + "data:android.intent.action.VIEW|content://com.android.calendar/time|", null, "calendar"),
            e("new_event", "New calendar event", "add event schedule create", P + "data:android.intent.action.INSERT||vnd.android.cursor.dir/event", null, "calendar"),
            e("contacts", "Contacts", "people address book", cat("APP_CONTACTS"), null, "user"),
            e("new_contact", "New contact", "add person create", P + "data:android.intent.action.INSERT||vnd.android.cursor.dir/contact", null, "user"),
            e("dialer", "Phone dialer", "call keypad dial", "android.intent.action.DIAL", null, "phone"),
            e("messages", "Messages", "sms text messaging", cat("APP_MESSAGING"), null, "message"),
            e("camera", "Camera", "take photo picture", "android.media.action.STILL_IMAGE_CAMERA", null, "camera"),
            e("camera_video", "Record video", "camera video camcorder", "android.media.action.VIDEO_CAMERA", null, "video"),
            e("gallery", "Gallery", "photos pictures images albums", cat("APP_GALLERY"), null, "image"),
            e("music", "Music", "player songs audio", cat("APP_MUSIC"), null, "music"),
            e("maps", "Maps", "navigation directions location", cat("APP_MAPS"), null, "map"),
            e("email", "Email", "mail inbox", cat("APP_EMAIL"), null, "mail"),
            e("browser", "Browser", "web internet chrome", cat("APP_BROWSER"), null, "compass"),
            e("calculator", "Calculator", "math calc", cat("APP_CALCULATOR"), null, "calculator"),
            e("weather", "Weather", "forecast temperature", cat("APP_WEATHER"), null, "sun"),
            e("play_store", "App store", "play store market install apps", cat("APP_MARKET"), null, "download"),
            e("web_search", "Web search", "google search find", "android.intent.action.WEB_SEARCH", null, "search"),
            e("voice_search", "Voice search", "speak search microphone", "android.speech.action.WEB_SEARCH", null, "mic"),
            e("voice_command", "Voice command", "assistant speak", "android.intent.action.VOICE_COMMAND", null, "mic"),
            e("downloads", "Downloads", "downloaded files", "android.intent.action.VIEW_DOWNLOADS", null, "download"),
            e("files", "Files", "file manager storage browse documents", P + "data:android.provider.action.BROWSE|content://com.android.externalstorage.documents/root/primary|vnd.android.document/root", null, "folder")
        ) + group("Dyn Edge",
            e("self_info", "Dyn Edge app info", "dyn edge dynedge this app permissions storage", P + "selfdata:android.settings.APPLICATION_DETAILS_SETTINGS", null, "info"),
            e("self_notifications", "Dyn Edge notifications", "dyn edge dynedge this app alerts", P + "self:android.settings.APP_NOTIFICATION_SETTINGS", null, "bell"),
            e("self_overlay", "Dyn Edge overlay permission", "dyn edge dynedge display over other apps", P + "selfdata:android.settings.action.MANAGE_OVERLAY_PERMISSION", null, "smartphone"),
            e("self_write_settings", "Dyn Edge system settings permission", "dyn edge dynedge modify system settings", P + "selfdata:android.settings.action.MANAGE_WRITE_SETTINGS", null, "wrench"),
            e("self_locale", "Dyn Edge language", "dyn edge dynedge app language locale", P + "selfdata:android.settings.APP_LOCALE_SETTINGS", null, "globe")
        )

    @Volatile private var resolved: List<Entry>? = null

    /** The entries whose intent resolves on this phone (computed once per process). */
    fun available(ctx: Context): List<Entry> {
        resolved?.let { return it }
        val pm = ctx.packageManager
        val out = ALL.filter {
            try {
                pm.resolveActivity(it.intent(), 0) != null
            } catch (e: Throwable) {
                false
            }
        }
        resolved = out
        return out
    }

    fun byId(ctx: Context, id: String): Entry? = available(ctx).firstOrNull { it.id == id }

    /** Entries matching every word of [query] (all entries for an empty query), grouped in catalog order. */
    fun search(ctx: Context, query: String): List<Entry> {
        val tokens = query.lowercase().split(' ', '-', '_').filter { it.isNotEmpty() }
        val all = available(ctx)
        return if (tokens.isEmpty()) all else all.filter { it.matches(tokens) }
    }
}
