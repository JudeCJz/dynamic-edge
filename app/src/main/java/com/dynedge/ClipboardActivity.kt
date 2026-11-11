package com.dynedge

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/** Full clipboard history: search, tap to copy, long-press for pin / delete. */
class ClipboardActivity : Activity() {

    private lateinit var store: ClipStore
    private lateinit var listView: ListView
    private var shown: List<ClipStore.Clip> = emptyList()
    private var query = ""
    private var accent = Color.WHITE
    private val refresh: () -> Unit = { reload() }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Skin.BG
        window.navigationBarColor = Skin.BG
        window.setDecorFitsSystemWindows(false)
        accent = Skin.accentUi(Prefs.accent(this))
        store = ClipStore.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Skin.BG)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }

        root.addView(topBar("Clipboard") { finish() }, lp(MATCH, WRAP))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Skin.PAD), 0, dp(4), 0)
        }
        val search = EditText(this).apply {
            hint = "Search"
            setHintTextColor(Skin.TEXT_3)
            setTextColor(Skin.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setSingleLine(true)
            background = null
            setPadding(0, dp(12), dp(8), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    reload()
                }
            })
        }
        header.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "Clear"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(accent)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            minimumHeight = dp(48)
            isClickable = true
            background = Skin.ripple(this@ClipboardActivity)
            setOnClickListener { store.clearUnpinned() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(View(this).apply { setBackgroundColor(accent) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply {
            leftMargin = dp(Skin.PAD)
            rightMargin = dp(Skin.PAD)
        })

        // setting for the edge's floating popup: close it after a clip is copied
        val closeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(Skin.PAD), 0, dp(Skin.PAD), 0)
            isClickable = true
            background = Skin.ripple(this@ClipboardActivity)
        }
        closeRow.addView(TextView(this).apply {
            text = "Close popup after copying"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Skin.TEXT_2)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val closeCheck = android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_check) }
        fun paintCheck() = closeCheck.setColorFilter(if (Prefs.clipClose(this)) accent else Skin.TRACK)
        paintCheck()
        closeRow.addView(closeCheck, LinearLayout.LayoutParams(dp(20), dp(20)))
        closeRow.setOnClickListener {
            Prefs.prefs(this).edit().putBoolean(Prefs.KEY_CLIP_CLOSE, !Prefs.clipClose(this)).apply()
            paintCheck()
        }
        root.addView(closeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        listView = ListView(this).apply {
            setBackgroundColor(Skin.BG)
            divider = null
            dividerHeight = 0
            selector = Skin.ripple(this@ClipboardActivity)
            clipToPadding = false
            setPadding(0, 0, 0, dp((Prefs.idleDp(this@ClipboardActivity) + 24f).toInt()))
            adapter = ClipAdapter()
            setOnItemClickListener { _, _, pos, _ -> copy(shown[pos]) }
            setOnItemLongClickListener { _, _, pos, _ ->
                menu(shown[pos])
                true
            }
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        reload()
    }

    override fun onResume() {
        super.onResume()
        store.addListener(refresh)
        ClipPaste.capture(this)
        reload()
    }

    override fun onPause() {
        super.onPause()
        store.removeListener(refresh)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ClipPaste.capture(this)
    }

    private fun reload() {
        val all = store.list()
        shown = if (query.isEmpty()) all else all.filter { it.text.contains(query, ignoreCase = true) }
        (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    private fun copy(c: ClipStore.Clip) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("clip", c.text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
        }
    }

    private fun menu(c: ClipStore.Clip) {
        val items = arrayOf(if (c.pinned) "Unpin" else "Pin", "Delete")
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setItems(items) { _, which ->
                if (which == 0) store.pin(c.id, !c.pinned) else store.delete(c.id)
            }
            .create()
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(ColorDrawable(Skin.BG)) }
        dialog.show()
    }

    private inner class ClipAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = shown[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row: LinearLayout
            val tick: View
            val text: TextView
            if (convertView == null) {
                row = LinearLayout(this@ClipboardActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(56)
                    setPadding(0, dp(12), dp(Skin.PAD), dp(12))
                }
                tick = View(this@ClipboardActivity)
                text = TextView(this@ClipboardActivity).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(Skin.TEXT)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                row.addView(tick, LinearLayout.LayoutParams(dp(3), dp(28)).apply {
                    leftMargin = dp(8)
                    rightMargin = dp(9)
                })
                row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.tag = arrayOf(tick, text)
            } else {
                row = convertView as LinearLayout
                @Suppress("UNCHECKED_CAST")
                val t = row.tag as Array<View>
                tick = t[0]
                text = t[1] as TextView
            }
            val c = shown[position]
            text.text = c.text
            tick.setBackgroundColor(if (c.pinned) accent else Color.TRANSPARENT)
            return row
        }
    }
}
