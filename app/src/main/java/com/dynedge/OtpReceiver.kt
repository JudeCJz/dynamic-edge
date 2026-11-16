package com.dynedge

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Copies a one-time code (tapped on the bean) to the clipboard and drops its live entry. */
class OtpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra(EXTRA_CODE) ?: return
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Code", code))
            Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
        }
        intent.getStringExtra(EXTRA_KEY)?.let { LiveBus.remove(it) }
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_KEY = "key"
    }
}
