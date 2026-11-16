package com.dynedge

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast

/**
 * The actions a gesture on the closed bean can run. One list serves both long press and double tap; each gesture
 * keeps its own tile / app choice in prefs. Ids of the long-press actions are the stored pref values, so they never change.
 */
object EdgeActions {
    const val HIDE = "hide"
    const val SETTINGS = "settings"
    const val NOTIFICATIONS = "notifications"
    const val QUICK_SETTINGS = "quick_settings"
    const val RECENTS = "recents"
    const val LOCK = "lock"
    const val SCREENSHOT = "screenshot"
    const val POWER = "power"
    const val TORCH = "torch"
    const val ASSISTANT = "assistant"
    const val TILE = "tile"
    const val NONE = "none"
    const val PREV_APP = "prev_app"
    const val BACK = "back"
    const val HOME = "home"
    const val SPLIT = "split"
    const val PLAY_PAUSE = "play_pause"
    const val NEXT_TRACK = "next_track"
    const val PREV_TRACK = "prev_track"
    const val CAMERA = "camera"
    const val RINGER = "ringer"
    const val DND = "dnd"
    const val ROTATION = "rotation"
    const val CLIPBOARD = "clipboard"
    const val OPEN_APP = "open_app"

    const val KEY_DOUBLE_ACTION = "double_action"
    const val KEY_DOUBLE_TILE = "double_tile"
    const val KEY_LONG_APP = "long_app"
    const val KEY_DOUBLE_APP = "double_app"

    /** id, label, icon: shown in this order in the pickers. */
    val ALL: List<Triple<String, String, Int>> get() = listOf(
        Triple(HIDE, "Hide", R.drawable.ic_eye),
        Triple(PREV_APP, "Previous app", R.drawable.ic_undo),
        Triple(BACK, "Back", R.drawable.ic_act_back),
        Triple(HOME, "Home", R.drawable.ic_home),
        Triple(RECENTS, "Recents", R.drawable.ic_layers),
        Triple(SPLIT, "Split screen", R.drawable.ic_columns),
        Triple(NOTIFICATIONS, "Notifications", R.drawable.ic_bell),
        Triple(QUICK_SETTINGS, "Quick settings", R.drawable.ic_sliders),
        Triple(SETTINGS, "Settings", R.drawable.ic_settings),
        Triple(LOCK, "Lock", R.drawable.ic_lock),
        Triple(SCREENSHOT, "Screenshot", R.drawable.ic_screenshot),
        Triple(POWER, "Power menu", R.drawable.ic_power),
        Triple(TORCH, "Torch", R.drawable.ic_flashlight),
        Triple(ASSISTANT, "Assistant", R.drawable.ic_sparkles),
        Triple(PLAY_PAUSE, "Play / pause", R.drawable.ic_play),
        Triple(NEXT_TRACK, "Next track", R.drawable.ic_skip_forward),
        Triple(PREV_TRACK, "Previous track", R.drawable.ic_skip_back),
        Triple(CAMERA, "Camera", R.drawable.ic_camera),
        Triple(RINGER, "Ringer", R.drawable.ic_vibrate),
        Triple(DND, "Do not disturb", R.drawable.ic_moon),
        Triple(ROTATION, "Rotation lock", R.drawable.ic_rotate_lock),
        Triple(CLIPBOARD, "Clipboard", R.drawable.ic_clipboard),
        Triple(OPEN_APP, "Open app", R.drawable.ic_act_app),
        Triple(TILE, "Toggle", R.drawable.ic_toggle),
        Triple(NONE, "Nothing", R.drawable.ic_x)
    )

    val IDS: Set<String> get() = ALL.map { it.first }.toSet()

    fun doubleAction(ctx: Context): String {
        val p = Prefs.prefs(ctx)
        val v = p.getString(KEY_DOUBLE_ACTION, null)
        if (v == null) {
            // migration: the old switch, when explicitly off, means no double tap
            return if (p.contains(EdgeExtras.KEY_DOUBLE_TAP_BACK) && !p.getBoolean(EdgeExtras.KEY_DOUBLE_TAP_BACK, true)) NONE else PREV_APP
        }
        return if (v in IDS) v else PREV_APP
    }

    fun tile(ctx: Context, double: Boolean): String =
        if (double) Prefs.prefs(ctx).getString(KEY_DOUBLE_TILE, "torch") ?: "torch" else Prefs.longTile(ctx)

    fun tileKey(double: Boolean) = if (double) KEY_DOUBLE_TILE else Prefs.KEY_LONG_TILE
    fun appKey(double: Boolean) = if (double) KEY_DOUBLE_APP else KEY_LONG_APP
    fun actionKey(double: Boolean) = if (double) KEY_DOUBLE_ACTION else Prefs.KEY_LONG_ACTION

    fun action(ctx: Context, double: Boolean): String = if (double) doubleAction(ctx) else Prefs.longAction(ctx)

    fun app(ctx: Context, double: Boolean): ComponentName? =
        Prefs.prefs(ctx).getString(appKey(double), null)?.let { ComponentName.unflattenFromString(it) }

    fun appLabel(ctx: Context, double: Boolean): String {
        val cn = app(ctx, double) ?: return "Choose app"
        return try {
            ctx.packageManager.getActivityInfo(cn, 0).loadLabel(ctx.packageManager).toString()
        } catch (e: Exception) {
            "Choose app"
        }
    }

    private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    /** Runs [id] for the gesture ([double] picks which tile / app pref applies). */
    fun run(v: EdgeView, id: String, double: Boolean) {
        val c = v.context
        val h = v.host
        when (id) {
            HIDE -> v.hideForAwhile()
            SETTINGS -> try {
                c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
            NOTIFICATIONS -> h?.performAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            QUICK_SETTINGS -> h?.performAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            RECENTS -> h?.performAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            LOCK -> h?.performAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            SCREENSHOT -> h?.performAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            POWER -> h?.performAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            TORCH -> v.toggleTorchOnce()
            ASSISTANT -> AssistControl.launch(c)
            TILE -> v.runLongTile(tile(c, double))
            PREV_APP -> v.switchToPreviousApp()
            BACK -> h?.performAction(AccessibilityService.GLOBAL_ACTION_BACK)
            HOME -> h?.performAction(AccessibilityService.GLOBAL_ACTION_HOME)
            SPLIT -> h?.performAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            PLAY_PAUSE -> media(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            NEXT_TRACK -> media(c, KeyEvent.KEYCODE_MEDIA_NEXT)
            PREV_TRACK -> media(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            CAMERA -> try {
                c.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                toast(c, "No camera app")
            }
            RINGER -> cycleRinger(c)
            DND -> toggleDnd(c)
            ROTATION -> toggleRotation(c)
            CLIPBOARD -> v.openClipboard()
            OPEN_APP -> {
                val cn = app(c, double)
                if (cn == null) {
                    toast(c, "Choose an app in Dyn Edge settings")
                } else try {
                    c.startActivity(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    )
                } catch (e: Exception) {
                    toast(c, "Could not open the app")
                }
            }
        }
    }

    private fun media(c: Context, code: Int) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        } catch (e: Exception) {
        }
    }

    private fun dndGranted(c: Context): Boolean =
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.isNotificationPolicyAccessGranted ?: false

    /** sound, vibrate, silent, sound; silent needs Do Not Disturb access, without it vibrate goes back to sound. */
    private fun cycleRinger(c: Context) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            am.ringerMode = when (am.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                AudioManager.RINGER_MODE_VIBRATE ->
                    if (dndGranted(c)) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
                else -> AudioManager.RINGER_MODE_NORMAL
            }
        } catch (e: Exception) {
            toast(c, "Ringer change blocked")
        }
    }

    private fun toggleDnd(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (!nm.isNotificationPolicyAccessGranted) {
            toast(c, "Allow Do Not Disturb access for Dyn Edge first")
            return
        }
        try {
            nm.setInterruptionFilter(
                if (nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL)
                    NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY
            )
        } catch (e: Exception) {
        }
    }

    private fun toggleRotation(c: Context) {
        if (!Settings.System.canWrite(c)) {
            toast(c, "Allow modify system settings for Dyn Edge first")
            return
        }
        try {
            val on = Settings.System.getInt(c.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
            Settings.System.putInt(c.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 0 else 1)
            toast(c, if (on) "Rotation locked" else "Auto-rotate on")
        } catch (e: Exception) {
        }
    }
}
