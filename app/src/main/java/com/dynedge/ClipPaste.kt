package com.dynedge

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

object ClipPaste {

    /** Reads the current primary clip into the store. Only works while this app has focus. */
    fun capture(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
            val clip = cm.primaryClip ?: return false
            if (clip.itemCount == 0) return false
            val text = clip.getItemAt(0).coerceToText(ctx)?.toString() ?: return false
            ClipStore.get(ctx).add(text)
        } catch (e: Exception) {
            false
        }
    }

    /** Copies text and pastes it into the focused editable field. False if there is none. */
    fun paste(service: AccessibilityService, text: String): Boolean {
        try {
            val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("clip", text))
        } catch (e: Exception) {
            return false
        }
        val node = findEditable(service) ?: return false
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
        return setText(node, text)
    }

    /** True when some window has a focused editable field that [paste] could fill. */
    fun hasEditable(service: AccessibilityService): Boolean = try {
        findEditable(service) != null
    } catch (e: Exception) {
        false
    }

    private fun findEditable(service: AccessibilityService): AccessibilityNodeInfo? {
        try {
            val windows = service.windows
            if (windows != null) {
                for (w in windows) {
                    val root = w.root ?: continue
                    val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (f != null && f.isEditable) return f
                }
            }
        } catch (e: Exception) {
        }
        try {
            val root = service.rootInActiveWindow
            val f = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null && f.isEditable) return f
        } catch (e: Exception) {
        }
        return null
    }

    private fun setText(node: AccessibilityNodeInfo, clip: String): Boolean {
        val existing = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
        var s = node.textSelectionStart
        var e = node.textSelectionEnd
        if (s < 0 || e < 0) {
            s = existing.length
            e = existing.length
        }
        if (s > e) {
            val t = s
            s = e
            e = t
        }
        s = s.coerceIn(0, existing.length)
        e = e.coerceIn(0, existing.length)
        val out = existing.substring(0, s) + clip + existing.substring(e)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, out)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val sel = Bundle()
            val caret = s + clip.length
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return ok
    }
}
