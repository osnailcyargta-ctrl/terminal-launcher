package com.osnailcyargta.launcher

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar

data class AppInfo(val label: String, val packageName: String)

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var termOutput: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var etInput: EditText
    private lateinit var tvClock: TextView
    private lateinit var tvStatus: TextView

    private var allApps: List<AppInfo> = emptyList()
    private val handler = Handler(Looper.getMainLooper())

    // Theme colors - persisted
    private var bgColor  = Color.parseColor("#0a0a0a")
    private var txtColor = Color.parseColor("#00ff88")

    // command history
    private val history = mutableListOf<String>()
    private var histIdx = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs    = getSharedPreferences("lp", MODE_PRIVATE)
        bgColor  = prefs.getInt("bg",  Color.parseColor("#0a0a0a"))
        txtColor = prefs.getInt("txt", Color.parseColor("#00ff88"))

        setContentView(R.layout.activity_main)

        termOutput = findViewById(R.id.termOutput)
        scrollView = findViewById(R.id.scrollView)
        etInput    = findViewById(R.id.etInput)
        tvClock    = findViewById(R.id.tvClock)
        tvStatus   = findViewById(R.id.tvStatus)

        applyTheme()
        startClock()
        setupInput()
        boot()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        loadApps()
    }

    // ── THEME ────────────────────────────────────────────────────────────────

    private fun applyTheme() {
        val root = findViewById<View>(R.id.root)
        root.setBackgroundColor(bgColor)
        window.statusBarColor     = bgColor
        window.navigationBarColor = bgColor

        tvClock.setTextColor(dim(txtColor, 0.9f))
        tvStatus.setTextColor(dim(txtColor, 0.4f))
        etInput.setTextColor(txtColor)
        etInput.setHintTextColor(dim(txtColor, 0.25f))

        val promptArrow = findViewById<TextView>(R.id.tvPrompt)
        promptArrow.setTextColor(dim(txtColor, 0.35f))

        val inputBar = findViewById<View>(R.id.inputBar)
        inputBar.setBackgroundColor(darken(bgColor, 6))

        val divider = findViewById<View>(R.id.divider)
        divider.setBackgroundColor(dim(txtColor, 0.1f))
    }

    // ── CLOCK ────────────────────────────────────────────────────────────────

    private fun startClock() {
        val tick = object : Runnable {
            override fun run() {
                val c = Calendar.getInstance()
                val h = c.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
                val m = c.get(Calendar.MINUTE).toString().padStart(2, '0')
                tvClock.text = "$h:$m"
                handler.postDelayed(this, 10_000)
            }
        }
        handler.post(tick)
    }

    // ── BOOT ─────────────────────────────────────────────────────────────────

    private fun boot() {
        val lines = listOf(
            "──────────────────────────────" to "system",
            "  PERSONAL LAUNCHER  v3.0"     to "system",
            "──────────────────────────────" to "system",
        )
        lines.forEachIndexed { i, (txt, cls) ->
            handler.postDelayed({ printLine(txt, cls) }, i * 60L)
        }
        handler.postDelayed({ printBlank() }, lines.size * 60L)
    }

    // ── APP LOADING ───────────────────────────────────────────────────────────

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val apps = installed
                .filter { ai ->
                    ai.packageName != packageName &&
                    pm.getLaunchIntentForPackage(ai.packageName) != null
                }
                .map { ai -> AppInfo(pm.getApplicationLabel(ai).toString(), ai.packageName) }
                .sortedBy { it.label.lowercase() }

            allApps = apps
            runOnUiThread {
                tvStatus.text = "${apps.size} apps installed"
            }
        }.start()
    }

    // ── INPUT ─────────────────────────────────────────────────────────────────

    private fun setupInput() {
        etInput.setOnEditorActionListener { _, _, _ ->
            val raw = etInput.text.toString()
            etInput.setText("")
            histIdx = -1
            if (raw.isNotBlank()) {
                history.add(0, raw)
                handleCommand(raw.trim())
            }
            true
        }

        // Arrow up/down history
        etInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (histIdx < history.size - 1) { histIdx++; etInput.setText(history[histIdx]) }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (histIdx > 0) { histIdx--; etInput.setText(history[histIdx]) }
                        else { histIdx = -1; etInput.setText("") }
                        true
                    }
                    else -> false
                }
            } else false
        }
    }

    // ── COMMAND HANDLER ───────────────────────────────────────────────────────

    private fun handleCommand(raw: String) {
        printPrompt(raw)
        val lower = raw.lowercase()

        when {
            lower == "help" -> cmdHelp()
            lower == "list" -> cmdList()
            lower == "clear" || lower == "cls" -> { termOutput.removeAllViews(); return }
            lower == "open launcher.setting" -> cmdSettings()
            lower.startsWith("run ") -> {
                val name = raw.substring(4).trim()
                if (name.isEmpty()) printLine("usage: run <app name>", "warn")
                else cmdRun(name)
            }
            lower.startsWith("delete ") -> {
                val name = raw.substring(7).trim()
                if (name.isEmpty()) printLine("usage: delete <app name>", "warn")
                else cmdDelete(name)
            }
            else -> printLine("unknown: $raw", "error")
        }
        printBlank()
        scrollBottom()
    }

    private fun cmdHelp() {
        printLine("commands:", "info")
        printLine("  run <app>              launch app", "output")
        printLine("  delete <app>           uninstall app", "output")
        printLine("  list                   show all installed apps", "output")
        printLine("  open launcher.setting  open settings", "output")
        printLine("  clear                  clear terminal", "output")
        printLine("  help                   show this", "output")
    }

    private fun cmdList() {
        if (allApps.isEmpty()) { printLine("no apps found.", "output"); return }
        printLine("${allApps.size} apps installed:", "info")
        allApps.forEachIndexed { i, app ->
            val num = (i + 1).toString().padStart(3)
            printLine("$num  ${app.label}", "app")
        }
    }

    private fun cmdRun(name: String) {
        val app = findApp(name)
        if (app == null) { printLine("not found: $name", "error"); return }
        val intent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent != null) {
            printLine("launching ${app.label}...", "output")
            handler.postDelayed({
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                printLine("${app.label} opened.", "success")
                scrollBottom()
            }, 300)
        } else {
            printLine("cannot launch ${app.label}", "error")
        }
    }

    private fun cmdDelete(name: String) {
        val app = findApp(name)
        if (app == null) { printLine("not found: $name", "error"); return }
        printLine("uninstalling ${app.label}...", "warn")
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun cmdSettings() {
        printLine("opening settings...", "output")
        scrollBottom()
        handler.postDelayed({ showSettingsOverlay() }, 200)
    }

    private fun findApp(name: String): AppInfo? {
        val lower = name.lowercase()
        return allApps.find { it.label.equals(name, ignoreCase = true) }
            ?: allApps.find { it.label.lowercase().startsWith(lower) }
            ?: allApps.find { it.label.lowercase().contains(lower) }
            ?: allApps.find { it.packageName.lowercase().contains(lower) }
    }

    // ── TERMINAL PRINT ────────────────────────────────────────────────────────

    private fun printLine(text: String, type: String = "output") {
        val tv = makeTermTV()
        tv.text = text
        tv.setTextColor(colorForType(type))
        if (type == "system") tv.alpha = 0.5f
        termOutput.addView(tv)
    }

    private fun printPrompt(cmd: String) {
        val tv = makeTermTV()
        val full = "❯ $cmd"
        val ss = SpannableString(full)
        ss.setSpan(ForegroundColorSpan(dim(txtColor, 0.35f)), 0, 2, 0)
        ss.setSpan(ForegroundColorSpan(txtColor), 2, full.length, 0)
        tv.text = ss
        termOutput.addView(tv)
    }

    private fun printBlank() {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (8 * resources.displayMetrics.density).toInt())
        termOutput.addView(v)
    }

    private fun makeTermTV(): TextView {
        val tv = TextView(this)
        tv.typeface = Typeface.MONOSPACE
        tv.textSize = 13f
        tv.setLineSpacing(2f, 1f)
        tv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return tv
    }

    private fun colorForType(type: String): Int = when (type) {
        "success" -> txtColor
        "error"   -> Color.parseColor("#ff4455")
        "warn"    -> Color.parseColor("#ffaa00")
        "info"    -> Color.parseColor("#44aaff")
        "app"     -> txtColor
        "system"  -> dim(txtColor, 0.4f)
        else      -> dim(txtColor, 0.55f)
    }

    private fun scrollBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    // ── SETTINGS OVERLAY ──────────────────────────────────────────────────────

    private fun showSettingsOverlay() {
        val dp = resources.displayMetrics.density
        val dp8 = (8 * dp).toInt()
        val dp12 = (12 * dp).toInt()
        val dp16 = (16 * dp).toInt()

        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(Color.parseColor("#CC000000"))

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val cardBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(darken(bgColor, 12))
            cornerRadius = 10f * dp
            setStroke((1 * dp).toInt(), dim(txtColor, 0.25f))
        }
        card.background = cardBg
        card.setPadding(0, 0, 0, dp8)

        // header
        val header = TextView(this).apply {
            text = "launcher.setting"
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(dim(txtColor, 0.5f))
            setPadding(dp16, dp12, dp16, dp8)
        }
        card.addView(header)
        card.addView(makeDivider(dp))

        // rows
        fun settingRow(label: String, value: String, onClick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp16, dp12, dp16, dp12)
                setOnClickListener { onClick() }
                isClickable = true
                isFocusable = true
            }
            row.addView(TextView(this).apply {
                text = label
                typeface = Typeface.MONOSPACE
                textSize = 10f
                setTextColor(dim(txtColor, 0.35f))
            })
            row.addView(TextView(this).apply {
                text = value
                typeface = Typeface.MONOSPACE
                textSize = 13f
                setTextColor(txtColor)
                setPadding(0, (3 * dp).toInt(), 0, 0)
            })
            card.addView(row)
            card.addView(makeDivider(dp))
        }

        settingRow("change terminal background color", colorHex(bgColor)) {
            dismissOverlay(overlay)
            showColorPicker("bg", bgColor) { c ->
                bgColor = c
                prefs.edit().putInt("bg", c).apply()
                applyTheme()
                printLine("bg color → ${colorHex(c)}", "success")
                printBlank(); scrollBottom()
            }
        }

        settingRow("change text color", colorHex(txtColor)) {
            dismissOverlay(overlay)
            showColorPicker("txt", txtColor) { c ->
                txtColor = c
                prefs.edit().putInt("txt", c).apply()
                applyTheme()
                printLine("text color → ${colorHex(c)}", "success")
                printBlank(); scrollBottom()
            }
        }

        // close btn
        val close = TextView(this).apply {
            text = "close"
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(dim(txtColor, 0.4f))
            gravity = Gravity.CENTER
            setPadding(dp16, dp12, dp16, dp8)
            setOnClickListener { dismissOverlay(overlay) }
        }
        card.addView(close)

        val lp = FrameLayout.LayoutParams((290 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER
        overlay.addView(card, lp)
        overlay.setOnClickListener { dismissOverlay(overlay) }

        val root = window.decorView as ViewGroup
        root.addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // animate in
        card.alpha = 0f; card.translationY = 24f * dp
        card.animate().alpha(1f).translationY(0f).setDuration(200)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    // ── COLOR PICKER OVERLAY ──────────────────────────────────────────────────

    private fun showColorPicker(key: String, current: Int, onApply: (Int) -> Unit) {
        val dp = resources.displayMetrics.density
        val dp8  = (8  * dp).toInt()
        val dp12 = (12 * dp).toInt()
        val dp16 = (16 * dp).toInt()
        val dp4  = (4  * dp).toInt()

        val hsv = FloatArray(3)
        Color.colorToHSV(current, hsv)
        var picked = current

        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(Color.parseColor("#CC000000"))

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(darken(bgColor, 12))
            cornerRadius = 10f * dp
            setStroke((1 * dp).toInt(), dim(txtColor, 0.25f))
        }
        card.setPadding(dp16, dp12, dp16, dp12)

        // title
        card.addView(TextView(this).apply {
            text = if (key == "bg") "background color" else "text color"
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(dim(txtColor, 0.5f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp8
            layoutParams = lp
        })

        // preview swatch
        val previewBg = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 6f * dp
            setColor(picked)
        }
        val preview = View(this).apply {
            background = previewBg
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (44 * dp).toInt())
            lp.bottomMargin = dp8
            layoutParams = lp
        }
        card.addView(preview)

        // hex input
        val hexEt = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(txtColor)
            setHintTextColor(dim(txtColor, 0.3f))
            hint = "#rrggbb"
            setText(colorHex(picked))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(darken(bgColor, 18))
                cornerRadius = 4f * dp
                setStroke((1 * dp).toInt(), dim(txtColor, 0.2f))
            }
            setPadding(dp8, dp8, dp8, dp8)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp8
            layoutParams = lp
        }
        card.addView(hexEt)

        // HSV sliders
        fun addSlider(label: String, initProgress: Int, max: Int, onChange: (Int) -> Unit): SeekBar {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp4
                layoutParams = lp
            }
            row.addView(TextView(this).apply {
                text = label
                typeface = Typeface.MONOSPACE
                textSize = 10f
                setTextColor(dim(txtColor, 0.4f))
                layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            val sb = SeekBar(this).apply {
                this.max = max
                progress = initProgress
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                progressDrawable?.setColorFilter(txtColor, android.graphics.PorterDuff.Mode.SRC_IN)
                thumb?.setColorFilter(txtColor, android.graphics.PorterDuff.Mode.SRC_IN)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            row.addView(sb)
            card.addView(row)
            return sb
        }

        var updating = false

        val hSb = addSlider("H", hsv[0].toInt(), 360) { p ->
            if (updating) return@addSlider
            hsv[0] = p.toFloat()
            picked = Color.HSVToColor(hsv)
            previewBg.setColor(picked)
            updating = true; hexEt.setText(colorHex(picked)); updating = false
        }
        val sSb = addSlider("S", (hsv[1] * 100).toInt(), 100) { p ->
            if (updating) return@addSlider
            hsv[1] = p / 100f
            picked = Color.HSVToColor(hsv)
            previewBg.setColor(picked)
            updating = true; hexEt.setText(colorHex(picked)); updating = false
        }
        val vSb = addSlider("V", (hsv[2] * 100).toInt(), 100) { p ->
            if (updating) return@addSlider
            hsv[2] = p / 100f
            picked = Color.HSVToColor(hsv)
            previewBg.setColor(picked)
            updating = true; hexEt.setText(colorHex(picked)); updating = false
        }

        hexEt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                try {
                    val hex = s.toString().trim()
                    if (hex.matches(Regex("#[0-9a-fA-F]{6}"))) {
                        val c = Color.parseColor(hex)
                        Color.colorToHSV(c, hsv)
                        updating = true
                        hSb.progress = hsv[0].toInt()
                        sSb.progress = (hsv[1] * 100).toInt()
                        vSb.progress = (hsv[2] * 100).toInt()
                        updating = false
                        picked = c
                        previewBg.setColor(picked)
                    }
                } catch (_: Exception) {}
            }
        })

        // preset swatches
        val presets = listOf(
            "#00ff88","#00ffcc","#00ccff","#4488ff",
            "#ff4455","#ffaa00","#ffffff","#aaaaaa",
            "#ff6ec7","#7c3aed","#0a0a0a","#111111"
        )
        val swatchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp4; lp.bottomMargin = dp4
            layoutParams = lp
        }
        presets.forEach { hex ->
            val sw = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor(hex))
                    cornerRadius = 3f * dp
                }
                val sz = (28 * dp).toInt()
                val lp = LinearLayout.LayoutParams(sz, sz)
                lp.marginEnd = (4 * dp).toInt()
                layoutParams = lp
                setOnClickListener {
                    val c = Color.parseColor(hex)
                    Color.colorToHSV(c, hsv)
                    updating = true
                    hSb.progress = hsv[0].toInt()
                    sSb.progress = (hsv[1] * 100).toInt()
                    vSb.progress = (hsv[2] * 100).toInt()
                    updating = false
                    picked = c
                    previewBg.setColor(picked)
                    hexEt.setText(hex)
                }
            }
            swatchRow.addView(sw)
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(swatchRow)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp8
            layoutParams = lp
        }
        card.addView(scroll)

        // buttons
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp8
            layoutParams = lp
        }
        fun btn(label: String, color: Int, action: () -> Unit): TextView = TextView(this).apply {
            text = label
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(color)
            setPadding(dp16, dp8, dp8, dp8)
            setOnClickListener { action() }
        }
        btnRow.addView(btn("cancel", dim(txtColor, 0.4f)) { dismissOverlay(overlay) })
        btnRow.addView(btn("apply",  txtColor) {
            dismissOverlay(overlay)
            onApply(picked)
        })
        card.addView(btnRow)

        val lp = FrameLayout.LayoutParams((310 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER
        overlay.addView(card, lp)
        overlay.setOnClickListener { } // block tap-through

        (window.decorView as ViewGroup).addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        card.alpha = 0f; card.scaleX = 0.9f; card.scaleY = 0.9f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private fun dismissOverlay(overlay: View) {
        overlay.animate().alpha(0f).setDuration(120).withEndAction {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }.start()
    }

    private fun makeDivider(dp: Float): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt())
        v.setBackgroundColor(dim(txtColor, 0.12f))
        return v
    }

    private fun dim(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun darken(color: Int, amt: Int): Int = Color.rgb(
        (Color.red(color)   + amt).coerceIn(0, 255),
        (Color.green(color) + amt).coerceIn(0, 255),
        (Color.blue(color)  + amt).coerceIn(0, 255)
    )

    private fun colorHex(color: Int) = String.format("#%06X", 0xFFFFFF and color)

    override fun onBackPressed() { /* swallow */ }
}
