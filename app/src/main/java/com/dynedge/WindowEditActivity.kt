package com.dynedge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * The editor of one overlay window (extra [EXTRA_ID] = window id): a [Studio] page in that window's mode.
 * Also hosts the Settings page ([SettingsActivity] passes [Studio.MODE_SETTINGS]).
 */
open class WindowEditActivity : Activity() {

    private var studio: Studio? = null

    protected open fun mode(): String = intent.getStringExtra(EXTRA_ID)?.takeIf { it in WindowsConfig.ALL } ?: "apps"

    @Suppress("DEPRECATION") // same window setup as the other settings screens
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Skin.BG
        window.navigationBarColor = Skin.BG
        window.setDecorFitsSystemWindows(false)
        val s = Studio(this, mode())
        studio = s
        setContentView(s.root)
        PixelMode.apply(this)
    }

    override fun onResume() {
        super.onResume()
        studio?.onResume()
    }

    override fun onPause() {
        super.onPause()
        studio?.onPause()
    }

    override fun onDestroy() {
        studio?.onDestroy()
        studio = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "window"

        fun intent(ctx: Context, id: String): Intent = Intent(ctx, WindowEditActivity::class.java).putExtra(EXTRA_ID, id)
    }
}

/** The Settings page (gear in the main top bar): permissions, pause in apps, saved looks, resets. */
class SettingsActivity : WindowEditActivity() {
    override fun mode(): String = Studio.MODE_SETTINGS
}
