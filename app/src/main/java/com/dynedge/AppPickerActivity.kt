package com.dynedge

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Full-screen list of launchable apps.
 * - Default mode: picking one appends it to the shortcut list and closes.
 * - [EXTRA_MODE] = [MODE_PAUSE]: multi-select of the apps the overlay pauses in (Prefs.pauseApps). A search
 *   box filters; checked apps are listed first (ordered once on open, so rows do not jump while tapping);
 *   every tap is saved at once.
 */
class AppPickerActivity : Activity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_PAUSE = "pause"
        const val EXTRA_REPLACE_IDX = "replace_idx"

        fun pauseIntent(c: Context): Intent = Intent(c, AppPickerActivity::class.java).putExtra(EXTRA_MODE, MODE_PAUSE)
        fun replaceIntent(c: Context, idx: Int): Intent = Intent(c, AppPickerActivity::class.java).putExtra(EXTRA_REPLACE_IDX, idx)
    }

    private class Item(val label: String, val cn: ComponentName) {
        val lower = label.lowercase()
        val pkg: String get() = cn.packageName
    }

    private var all: List<Item> = emptyList()
    private var items: List<Item> = emptyList()
    private val cache = LruCache<String, Bitmap>(96)
    private var iconPx = 0
    private var multi = false
    private val chosen = HashSet<String>()
    private var accent = 0
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Skin.BG
        window.navigationBarColor = Skin.BG
        window.setDecorFitsSystemWindows(false)
        iconPx = dp(40)
        accent = accentUi()
        multi = intent.getStringExtra(EXTRA_MODE) == MODE_PAUSE
        if (multi) chosen.addAll(Prefs.pauseApps(this))

        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        all = pm.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != packageName }
            // one row per app in the multi-select (the set holds packages)
            .filter { !multi || seen.add(it.activityInfo.packageName) }
            .map { Item(it.loadLabel(pm).toString(), ComponentName(it.activityInfo.packageName, it.activityInfo.name)) }
            .sortedWith(
                if (multi) compareBy<Item>({ it.pkg !in chosen }, { it.lower })
                else compareBy<Item> { it.lower }
            )
        items = all

        val list = ListView(this).apply {
            setBackgroundColor(Skin.BG)
            divider = null
            dividerHeight = 0
            selector = Skin.ripple(this@AppPickerActivity)
            adapter = this@AppPickerActivity.adapter
            setOnItemClickListener { _, view, pos, _ -> pick(items[pos], view) }
            clipToPadding = false
            setPadding(0, 0, 0, dp((Prefs.idleDp(this@AppPickerActivity) + 24f).toInt()))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Skin.BG)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
            addView(topBar(if (multi) "Pause in apps" else "Add app") { finish() }, lp(MATCH, WRAP))
            if (multi) {
                addView(searchBox(), lp(MATCH, WRAP))
                addView(View(this@AppPickerActivity).apply { setBackgroundColor(accent) }, lp(MATCH, dp(2)).apply {
                    leftMargin = dp(Skin.PAD)
                    rightMargin = dp(Skin.PAD)
                })
            }
            addView(list, lp(MATCH, 0, 1f))
        }
        setContentView(root)
    }

    private fun searchBox(): View = EditText(this).apply {
        hint = "Search"
        setHintTextColor(Skin.TEXT_3)
        setTextColor(Skin.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setSingleLine(true)
        background = null
        setPadding(dp(Skin.PAD), dp(12), dp(Skin.PAD), dp(12))
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val f = s?.toString()?.trim()?.lowercase().orEmpty()
                items = if (f.isEmpty()) all else all.filter { it.lower.contains(f) || it.pkg.lowercase().contains(f) }
                adapter.notifyDataSetChanged()
            }
        })
    }

    private fun pick(item: Item, row: View?) {
        if (multi) {
            if (!chosen.remove(item.pkg)) chosen.add(item.pkg)
            Prefs.setPauseApps(this, chosen)
            if (row != null) bind(row, item) else adapter.notifyDataSetChanged()
            return
        }
        val cur = Prefs.apps(this)
        val replaceIdx = intent.getIntExtra(EXTRA_REPLACE_IDX, -1)
        if (replaceIdx in cur.indices) {
            val list = cur.toMutableList()
            list[replaceIdx] = item.cn
            Prefs.setApps(this, list)
            finish()
            return
        }
        if (item.cn !in cur && cur.size < Prefs.MAX_APPS) Prefs.setApps(this, cur + item.cn)
        finish()
    }

    @Suppress("UNCHECKED_CAST")
    private fun bind(row: View, item: Item) {
        val t = row.tag as Array<View>
        val icon = t[0] as ImageView
        val name = t[1] as TextView
        val check = t[2] as ImageView
        name.text = item.label
        var bmp = cache.get(item.cn.flattenToShortString())
        if (bmp == null) {
            bmp = try {
                IconTheme.icon(this, item.cn, iconPx)
            } catch (e: Exception) {
                null
            }
            if (bmp != null) cache.put(item.cn.flattenToShortString(), bmp)
        }
        icon.setImageBitmap(bmp)
        if (multi) {
            val on = item.pkg in chosen
            check.visibility = if (on) View.VISIBLE else View.INVISIBLE
            row.contentDescription = item.label + if (on) ", paused" else ", not paused"
        } else {
            check.visibility = View.GONE
            row.contentDescription = item.label
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LinearLayout(this@AppPickerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(Skin.PAD), dp(6), dp(Skin.PAD), dp(6))
                val icon = ImageView(this@AppPickerActivity)
                val name = TextView(this@AppPickerActivity).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(Skin.TEXT)
                    typeface = Typeface.DEFAULT
                    setPadding(dp(16), 0, dp(8), 0)
                    maxLines = 1
                }
                val check = ImageView(this@AppPickerActivity).apply {
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(accent)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
                addView(icon, LinearLayout.LayoutParams(dp(32), dp(32)))
                addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(check, LinearLayout.LayoutParams(dp(24), dp(24)))
                tag = arrayOf<View>(icon, name, check)
            }
            bind(row, items[position])
            return row
        }
    }
}
