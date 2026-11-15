package com.dynedge

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView

/**
 * Creates or edits a shortcut tile ([ShortcutStore]).
 *
 * Step 1 (search): one search field over three sources - the Settings catalog ([ShortcutCatalog]), the installed
 * launcher apps (open the app) and "App info: <name>" (the app's details screen). An empty query lists the catalog
 * and the apps. Step 2 (configure): pick one of the 40 Lucide icons or the target app's own icon, optionally name
 * the tile, then Save. Save writes the shortcut to prefs and returns [EXTRA_ID] as the result.
 *
 * [intent] with an id opens straight on step 2 for that shortcut (the tile's long-press on the overlay, or Edit in
 * the layout editor); "Change target" goes back to step 1.
 */
class ShortcutPickerActivity : Activity() {

    companion object {
        const val EXTRA_ID = "sc_id"
        private const val KIND_HEAD = "head"

        internal fun intent(c: Context, id: String?): Intent =
            Intent(c, ShortcutPickerActivity::class.java).apply { if (id != null) putExtra(EXTRA_ID, id) }
    }

    private class App(val label: String, val pkg: String, val cls: String) {
        val lower = label.lowercase()
    }

    /** One search result: a catalog entry, an app, or an app's info page. */
    private class Row(val kind: String, val title: String, val sub: String, val entry: ShortcutCatalog.Entry?, val app: App?)

    private var accent = 0
    private var editId: String? = null

    // the shortcut being built
    private var kind = ShortcutStore.KIND_SETTINGS
    private var action: String? = null
    private var pkg: String? = null
    private var cls: String? = null
    private var hl: String? = null
    private var target = ""
    private var iconName = "settings"
    private var appIcon = false
    private var labelText = ""

    private var apps: List<App> = emptyList()
    private var rows: List<Row> = emptyList()
    private var query = ""
    private var step = 0

    private lateinit var body: FrameLayout
    private lateinit var searchView: View
    private val adapter = Adapter()

    private val d: Float get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Skin.BG
        window.navigationBarColor = Skin.BG
        window.setDecorFitsSystemWindows(false)
        accent = accentUi()
        editId = intent.getStringExtra(EXTRA_ID)

        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(q, 0)
            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .sortedBy { it.lower }

        body = FrameLayout(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Skin.BG)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
            addView(body, lp(MATCH, MATCH))
        }
        setContentView(root)
        PixelMode.apply(this)

        val existing = editId?.let { ShortcutStore.get(this, it) }
        if (existing != null) {
            kind = existing.kind
            action = existing.action
            pkg = existing.pkg
            cls = existing.cls
            hl = existing.hl
            target = existing.target
            iconName = existing.icon
            appIcon = existing.appIcon
            labelText = existing.label
            showConfig()
        } else {
            showSearch()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            step == 1 && searchBuilt -> showSearch()   // a new shortcut, or a changed target: back to the search
            step == 0 && editId != null -> showConfig() // editing: back to the shortcut
            else -> finish()
        }
    }

    private var searchBuilt = false

    // ---------------------------------------------------------------- step 1: search

    private fun showSearch() {
        step = 0
        body.removeAllViews()
        if (!searchBuilt) {
            searchView = buildSearch()
            searchBuilt = true
        }
        (searchView.parent as? ViewGroup)?.removeView(searchView)
        body.addView(searchView, FrameLayout.LayoutParams(MATCH, MATCH))
        runSearch()
    }

    private fun buildSearch(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(topBar(if (editId == null) "New shortcut" else "Change target") { onBackPressed() }, lp(MATCH, WRAP))
        col.addView(EditText(this).apply {
            hint = "Search settings and apps"
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
                    query = s?.toString()?.trim().orEmpty()
                    runSearch()
                }
            })
        }, lp(MATCH, WRAP))
        col.addView(View(this).apply { setBackgroundColor(accent) }, lp(MATCH, dp(2)).apply {
            leftMargin = dp(Skin.PAD)
            rightMargin = dp(Skin.PAD)
        })
        col.addView(ListView(this).apply {
            setBackgroundColor(Skin.BG)
            divider = null
            dividerHeight = 0
            selector = Skin.ripple(this@ShortcutPickerActivity)
            adapter = this@ShortcutPickerActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> rows.getOrNull(pos)?.let { if (it.kind != KIND_HEAD) pick(it) } }
        }, lp(MATCH, 0, 1f))
        return col
    }

    private fun runSearch() {
        val tokens = query.lowercase().split(' ', '-', '_').filter { it.isNotEmpty() }
        val out = ArrayList<Row>()
        val settings = ShortcutCatalog.search(this, query).take(60)
        if (settings.isNotEmpty()) out.add(Row(KIND_HEAD, "Settings", "", null, null))
        for (e in settings) out.add(Row(ShortcutStore.KIND_SETTINGS, e.label, "", e, null))
        val matched = apps.filter { x -> tokens.all { x.lower.contains(it) } }.take(80)
        if (matched.isNotEmpty()) out.add(Row(KIND_HEAD, "Apps", "", null, null))
        for (a in matched) out.add(Row(ShortcutStore.KIND_APP, a.label, "", null, a))
        if (tokens.isNotEmpty()) {
            val seen = HashSet<String>()
            val info = apps.filter { x -> seen.add(x.pkg) && tokens.all { ("app info " + x.lower).contains(it) } }.take(40)
            if (info.isNotEmpty()) out.add(Row(KIND_HEAD, "App info", "", null, null))
            for (a in info) out.add(Row(ShortcutStore.KIND_INFO, "App info: " + a.label, "", null, a))
        }
        rows = out
        adapter.notifyDataSetChanged()
    }

    private fun pick(r: Row) {
        kind = r.kind
        target = r.title
        labelText = ""
        val e = r.entry
        val a = r.app
        if (e != null) {
            action = e.action
            hl = e.hl
            pkg = null
            cls = null
            iconName = e.icon
            appIcon = false
        } else if (a != null) {
            action = null
            hl = null
            pkg = a.pkg
            cls = if (r.kind == ShortcutStore.KIND_APP) a.cls else null
            iconName = if (r.kind == ShortcutStore.KIND_INFO) "info" else "smartphone"
            appIcon = true
        }
        showConfig()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = if (rows[position].kind == KIND_HEAD) 1 else 0
        override fun isEnabled(position: Int): Boolean = rows[position].kind != KIND_HEAD

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            if (rows[position].kind == KIND_HEAD) {
                val h = convertView as? TextView ?: sectionLabel("")
                h.text = rows[position].title
                return h
            }
            val v = convertView as? LinearLayout ?: LinearLayout(this@ShortcutPickerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(Skin.PAD), dp(6), dp(Skin.PAD), dp(6))
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }, lp(dp(36), dp(36)).apply { rightMargin = dp(16) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(txt("", 16f, Skin.TEXT, false).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(txt("", 12f, Skin.TEXT_2, false).apply { maxLines = 1 })
                }, lp(0, WRAP, 1f))
            }
            val r = rows[position]
            val img = v.getChildAt(0) as ImageView
            val col = v.getChildAt(1) as LinearLayout
            (col.getChildAt(0) as TextView).text = r.title
            (col.getChildAt(1) as TextView).text = r.sub
            val a = r.app
            if (a != null && r.kind == ShortcutStore.KIND_APP) {
                img.clearColorFilter()
                img.setImageDrawable(IconTheme.drawable(this@ShortcutPickerActivity, IconTheme.icon(this@ShortcutPickerActivity, ComponentName(a.pkg, a.cls), dp(36))))
            } else if (a != null) {
                img.clearColorFilter()
                img.setImageDrawable(IconTheme.drawable(this@ShortcutPickerActivity, IconTheme.icon(this@ShortcutPickerActivity, a.pkg, dp(36))))
            } else {
                img.setImageResource(ShortcutIcons.res(r.entry?.icon))
                img.setColorFilter(Skin.TEXT_2)
            }
            return v
        }
    }

    // ---------------------------------------------------------------- step 2: icon and label

    private fun current(): ShortcutStore.Shortcut =
        ShortcutStore.Shortcut(editId ?: "sc:preview", kind, action, pkg, cls, hl, iconName, appIcon, labelText, target)

    private fun showConfig() {
        step = 1
        body.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = topBar(if (editId == null) "Shortcut" else "Edit shortcut") { onBackPressed() } as LinearLayout
        bar.addView(TextView(this).apply {
            text = "Save"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(accent)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(4), 0)
            minimumHeight = dp(48)
            isClickable = true
            background = Skin.ripple(this@ShortcutPickerActivity)
            setOnClickListener { save() }
        }, lp(WRAP, dp(48)))
        col.addView(bar, lp(MATCH, WRAP))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        scroll.addView(list)
        col.addView(scroll, lp(MATCH, 0, 1f))

        // preview chip + name field + target
        val chip = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(EdgeView.C_FILL)
            }
        }
        val name = EditText(this).apply {
            hint = target
            setText(labelText)
            setHintTextColor(Skin.TEXT_3)
            setTextColor(Skin.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            background = null
            setPadding(0, dp(6), 0, dp(6))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    labelText = s?.toString()?.trim().orEmpty()
                }
            })
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Skin.PAD), dp(8), dp(Skin.PAD), dp(8))
            addView(chip, lp(dp(64), dp(64)).apply { rightMargin = dp(16) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(name, lp(MATCH, WRAP))
                addView(View(context).apply { setBackgroundColor(accent) }, lp(MATCH, dp(2)))
                addView(txt(target, 12f, Skin.TEXT_2, false).apply { setPadding(0, dp(6), 0, 0); maxLines = 1 })
            }, lp(0, WRAP, 1f))
        }
        list.addView(head, lp(MATCH, WRAP))

        val change = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(Skin.PAD), 0, dp(Skin.PAD), 0)
            isClickable = true
            background = Skin.ripple(this@ShortcutPickerActivity)
            setOnClickListener { showSearch() }
            addView(icon(R.drawable.ic_search, accent, 20), lp(dp(20), dp(20)).apply { rightMargin = dp(14) })
            addView(txt("Change target", 15f, accent, false), lp(0, WRAP, 1f))
        }
        list.addView(change, lp(MATCH, WRAP))
        list.addView(sectionLabel("Icon"), lp(MATCH, WRAP))

        // icon grid: the target app's own icon first (apps and app info), then the 40 Lucide icons
        val cols = 6
        val cells = ArrayList<Pair<String, Int>>()
        if (current().hasApp) cells.add("@app" to 0)
        cells.addAll(ShortcutIcons.ALL)
        val views = HashMap<String, ImageView>()

        fun paintPreview() {
            val s = current()
            val bmp = if (appIcon && s.hasApp) ShortcutStore.appIcon(this, s, dp(64)) else null
            if (bmp != null) {
                chip.clearColorFilter()
                chip.setPadding(dp(8), dp(8), dp(8), dp(8))
                chip.setImageBitmap(bmp)
            } else {
                chip.setPadding(dp(16), dp(16), dp(16), dp(16))
                chip.setImageResource(ShortcutIcons.res(iconName))
                chip.setColorFilter(Color.WHITE)
            }
            for ((k, v) in views) {
                val sel = if (k == "@app") appIcon else (!appIcon && k == iconName)
                v.background = if (sel) GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x00000000)
                    setStroke(dp(2), accent)
                } else null
            }
        }

        var row: LinearLayout? = null
        for ((i, c) in cells.withIndex()) {
            if (i % cols == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(Skin.PAD - 6), 0, dp(Skin.PAD - 6), 0)
                }
                list.addView(row, lp(MATCH, WRAP))
            }
            val key = c.first
            val cell = FrameLayout(this)
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(10), dp(10), dp(10), dp(10))
                isClickable = true
                contentDescription = if (key == "@app") "App icon" else key
                if (key == "@app") {
                    val b = ShortcutStore.appIcon(this@ShortcutPickerActivity, current(), dp(48))
                    if (b != null) setImageBitmap(b)
                } else {
                    setImageResource(c.second)
                    setColorFilter(Skin.TEXT)
                }
                setOnClickListener {
                    if (key == "@app") appIcon = true else {
                        appIcon = false
                        iconName = key
                    }
                    paintPreview()
                }
            }
            views[key] = img
            cell.addView(img, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
            row!!.addView(cell, lp(0, dp(52), 1f))
        }
        val rem = cells.size % cols
        if (rem != 0) for (k in rem until cols) row!!.addView(View(this), lp(0, 1, 1f))

        paintPreview()
        body.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun save() {
        val id = editId ?: ShortcutStore.newId()
        val s = current().copy(id = id, label = labelText.ifEmpty { target })
        ShortcutStore.put(this, s)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ID, id))
        finish()
    }
}
