package com.dynedge

import android.content.Context
import android.content.Intent

/**
 * Curated Settings destinations for shortcut tiles ([ShortcutStore]).
 *
 * Each [Entry] is an intent action (string literal, so hidden AOSP actions can be listed too), an optional highlight
 * key (AOSP Settings scrolls to and highlights that preference when it is passed as the fragment args key), search
 * keywords and a default icon name ([ShortcutIcons]). Entries whose intent does not resolve on this phone are hidden
 * ([available]). To add one: append a line to [ALL] with a unique id; keep the keywords lowercase.
 */
internal object ShortcutCatalog {

    class Entry(
        val id: String,
        val label: String,
        val keywords: String,
        val action: String,
        val hl: String? = null,
        val icon: String = "settings"
    ) {
        private val hay = (label + " " + keywords).lowercase()

        fun matches(tokens: List<String>): Boolean = tokens.all { hay.contains(it) }

        fun intent(): Intent {
            val i = Intent(action)
            hl?.let {
                i.putExtra(":settings:fragment_args_key", it)
                i.putExtra(":settings:show_fragment_args", android.os.Bundle().apply { putString(":settings:fragment_args_key", it) })
            }
            return i
        }
    }

    private const val DEV = "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"
    private const val INFO = "android.settings.DEVICE_INFO_SETTINGS"
    private const val DISPLAY = "android.settings.DISPLAY_SETTINGS"
    private const val SOUND = "android.settings.SOUND_SETTINGS"

    private fun e(id: String, label: String, keywords: String, action: String, hl: String? = null, icon: String = "settings") =
        Entry(id, label, keywords, action, hl, icon)

    val ALL: List<Entry> = listOf(
        // ---- about phone
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

        // ---- developer options
        e("developer", "Developer options", "developer dev options debug", DEV, null, "terminal"),
        e("usb_debugging", "USB debugging", "adb developer debug usb", DEV, "enable_adb", "bug"),
        e("wireless_debugging", "Wireless debugging", "adb wifi developer pair", DEV, "toggle_adb_wireless", "wifi"),
        e("oem_unlock", "OEM unlocking", "bootloader unlock developer", DEV, "oem_unlock_enable", "lock"),
        e("stay_awake", "Stay awake", "screen charging developer", DEV, "keep_screen_on", "sun"),
        e("pointer_location", "Pointer location", "touch developer debug", DEV, "pointer_location", "search"),
        e("anim_scale", "Window animation scale", "animation speed developer", DEV, "window_animation_scale", "zap"),
        e("usb_config", "Default USB configuration", "usb mode file transfer developer", DEV, "select_usb_configuration", "link"),
        e("bg_limit", "Background process limit", "developer processes", DEV, "app_process_limit", "cpu"),
        e("dont_keep", "Do not keep activities", "developer destroy", DEV, "immediate_destroy_activities", "wrench"),
        e("bugreport", "Take bug report", "developer log", DEV, "bugreport", "bug"),

        // ---- network
        e("wifi", "Wi-Fi", "wireless internet network", "android.settings.WIFI_SETTINGS", null, "wifi"),
        e("internet", "Internet", "network provider mobile wifi", "android.settings.NETWORK_PROVIDER_SETTINGS", null, "globe"),
        e("wireless", "Network and internet", "wireless networks connections", "android.settings.WIRELESS_SETTINGS", null, "globe"),
        e("bluetooth", "Bluetooth", "pair devices headphones", "android.settings.BLUETOOTH_SETTINGS", null, "bluetooth"),
        e("airplane", "Airplane mode", "flight mode radio", "android.settings.AIRPLANE_MODE_SETTINGS", null, "globe"),
        e("mobile_network", "Mobile network", "cellular roaming sim data", "android.settings.DATA_ROAMING_SETTINGS", null, "globe"),
        e("operators", "Network operators", "carrier network select", "android.settings.NETWORK_OPERATOR_SETTINGS", null, "globe"),
        e("data_usage", "Data usage", "mobile data cellular usage limit", "android.settings.DATA_USAGE_SETTINGS", null, "download"),
        e("hotspot", "Hotspot and tethering", "tether wifi hotspot usb tethering portable", "android.settings.TETHER_SETTINGS", null, "wifi"),
        e("vpn", "VPN", "virtual private network", "android.settings.VPN_SETTINGS", null, "shield"),
        e("nfc", "NFC", "near field contactless tap", "android.settings.NFC_SETTINGS", null, "smartphone"),
        e("nfc_payment", "Contactless payments", "nfc tap and pay wallet", "android.settings.NFC_PAYMENT_SETTINGS", null, "smartphone"),
        e("cast", "Cast", "screen mirror chromecast display", "android.settings.CAST_SETTINGS", null, "play"),
        e("wifi_ip", "Wi-Fi IP settings", "static ip dns", "android.settings.WIFI_IP_SETTINGS", null, "wifi"),

        // ---- battery
        e("battery", "Battery usage", "battery power usage drain", "android.intent.action.POWER_USAGE_SUMMARY", null, "battery"),
        e("battery_saver", "Battery saver", "power saving low power", "android.settings.BATTERY_SAVER_SETTINGS", null, "battery"),
        e("battery_opt", "Battery optimisation", "ignore battery optimization doze unrestricted", "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS", null, "battery"),

        // ---- display
        e("display", "Display", "screen settings", DISPLAY, null, "sun"),
        e("brightness", "Brightness", "screen light level", DISPLAY, "brightness", "sun"),
        e("adaptive_brightness", "Adaptive brightness", "auto brightness", DISPLAY, "auto_brightness", "sun"),
        e("dark_mode", "Dark mode", "dark theme night ui", DISPLAY, "dark_ui_mode", "moon"),
        e("night_light", "Night light", "blue light filter eye comfort", "android.settings.NIGHT_DISPLAY_SETTINGS", null, "moon"),
        e("screen_timeout", "Screen timeout", "sleep display off", DISPLAY, "screen_timeout", "clock"),
        e("auto_rotate", "Auto-rotate", "rotation orientation", DISPLAY, "auto_rotate", "smartphone"),
        e("font_size", "Font size", "text size display size", DISPLAY, "font_size", "search"),
        e("screen_saver", "Screen saver", "daydream dream", "android.settings.DREAM_SETTINGS", null, "moon"),
        e("wallpaper", "Wallpaper", "background home screen", "android.intent.action.SET_WALLPAPER", null, "home"),

        // ---- sound and notifications
        e("sound", "Sound", "volume ringtone vibration", SOUND, null, "bell"),
        e("ringtone", "Phone ringtone", "ring tone call", SOUND, "phone_ringtone", "bell"),
        e("notification_sound", "Notification sound", "default alert tone", SOUND, "notification_ringtone", "bell"),
        e("dnd", "Do not disturb", "dnd silent zen priority", "android.settings.ZEN_MODE_SETTINGS", null, "moon"),
        e("dnd_access", "Do not disturb access", "apps allowed dnd policy", "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS", null, "moon"),
        e("notifications", "App notifications", "notifications all apps manage", "android.settings.ALL_APPS_NOTIFICATION_SETTINGS", null, "bell"),
        e("notification_access", "Notification access", "notification listener apps read", "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS", null, "bell"),
        e("notification_history", "Notification history", "past notifications log", "android.settings.NOTIFICATION_HISTORY", null, "clock"),

        // ---- apps and permissions
        e("apps", "Apps", "applications installed manage", "android.settings.APPLICATION_SETTINGS", null, "smartphone"),
        e("all_apps", "All apps", "manage applications list", "android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS", null, "smartphone"),
        e("default_apps", "Default apps", "browser sms dialer defaults", "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", null, "smartphone"),
        e("default_home", "Default home app", "launcher home", "android.settings.HOME_SETTINGS", null, "home"),
        e("assistant", "Assistant and voice input", "voice assistant default", "android.settings.VOICE_INPUT_SETTINGS", null, "search"),
        e("usage_access", "Usage access", "app usage stats permission", "android.settings.USAGE_ACCESS_SETTINGS", null, "clock"),
        e("overlay", "Display over other apps", "overlay draw permission system alert window", "android.settings.action.MANAGE_OVERLAY_PERMISSION", null, "smartphone"),
        e("write_settings", "Modify system settings", "write settings permission", "android.settings.action.MANAGE_WRITE_SETTINGS", null, "wrench"),
        e("unknown_sources", "Install unknown apps", "sideload unknown sources apk", "android.settings.MANAGE_UNKNOWN_APP_SOURCES", null, "download"),
        e("all_files", "All files access", "manage external storage permission", "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION", null, "folder"),
        e("exact_alarm", "Alarms and reminders", "schedule exact alarm permission", "android.settings.REQUEST_SCHEDULE_EXACT_ALARM", null, "clock"),

        // ---- security, privacy, location
        e("security", "Security", "screen lock fingerprint face unlock", "android.settings.SECURITY_SETTINGS", null, "lock"),
        e("privacy", "Privacy", "permissions dashboard ads", "android.settings.PRIVACY_SETTINGS", null, "shield"),
        e("location", "Location", "gps position", "android.settings.LOCATION_SOURCE_SETTINGS", null, "map-pin"),
        e("accounts", "Accounts and sync", "sync google account", "android.settings.SYNC_SETTINGS", null, "user"),
        e("add_account", "Add account", "sign in google email", "android.settings.ADD_ACCOUNT_SETTINGS", null, "user"),
        e("users", "Users", "multiple users guest profile", "android.settings.USER_SETTINGS", null, "user"),

        // ---- accessibility
        e("accessibility", "Accessibility", "talkback magnification services", "android.settings.ACCESSIBILITY_SETTINGS", null, "user"),
        e("captions", "Captions", "subtitles accessibility", "android.settings.CAPTIONING_SETTINGS", null, "message"),
        e("color_inversion", "Color inversion", "invert colours accessibility", "android.settings.COLOR_INVERSION_SETTINGS", null, "sun"),
        e("color_correction", "Color correction", "colour blind accessibility", "android.settings.COLOR_CORRECTION_SETTINGS", null, "sun"),
        e("hearing_aids", "Hearing devices", "hearing aid accessibility", "android.settings.HEARING_AID_SETTINGS", null, "bluetooth"),

        // ---- system
        e("date_time", "Date and time", "clock timezone", "android.settings.DATE_SETTINGS", null, "clock"),
        e("language", "Language", "locale region", "android.settings.LOCALE_SETTINGS", null, "globe"),
        e("keyboard", "Keyboard", "input method on-screen", "android.settings.INPUT_METHOD_SETTINGS", null, "mail"),
        e("physical_keyboard", "Physical keyboard", "hardware keyboard", "android.settings.HARD_KEYBOARD_SETTINGS", null, "mail"),
        e("dictionary", "Personal dictionary", "user dictionary spell", "android.settings.USER_DICTIONARY_SETTINGS", null, "file"),
        e("storage", "Storage", "internal storage space files", "android.settings.INTERNAL_STORAGE_SETTINGS", null, "hard-drive"),
        e("sd_card", "Memory card", "sd card external storage", "android.settings.MEMORY_CARD_SETTINGS", null, "hard-drive"),
        e("free_space", "Free up space", "clean storage manage", "android.os.storage.action.MANAGE_STORAGE", null, "hard-drive"),
        e("print", "Printing", "printer", "android.settings.ACTION_PRINT_SETTINGS", null, "file"),
        e("search_settings", "Search settings", "find setting", "android.search.action.SEARCH_SETTINGS", null, "search"),
        e("settings", "Settings", "main settings home", "android.settings.SETTINGS", null, "settings")
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

    /** Entries matching every word of [query] (all entries for an empty query). */
    fun search(ctx: Context, query: String): List<Entry> {
        val tokens = query.lowercase().split(' ', '-', '_').filter { it.isNotEmpty() }
        val all = available(ctx)
        return if (tokens.isEmpty()) all else all.filter { it.matches(tokens) }
    }
}
