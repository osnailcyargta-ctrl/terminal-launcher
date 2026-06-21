package com.osnailcyargta.launcher

import android.animation.*
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class AppInfo(val label: String, val packageName: String, val icon: android.graphics.drawable.Drawable)

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var rv: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var tvOutput: TextView
    private lateinit var tvClock: TextView
    private var allApps: List<AppInfo> = emptyList()
    private var filteredApps: List<AppInfo> = emptyList()
    private lateinit var adapter: AppAdapter
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // Colors
    private var bgColor = Color.parseColor("#0a0a0a")
    private var textColor = Color.parseColor("#00ff88")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher_prefs", MODE_PRIVATE)
        bgColor = prefs.getInt("bg_color", Color.parseColor("#0a0a0a"))
        textColor = prefs.getInt("text_color", Color.parseColor("#00ff88"))

        setContentView(R.layout.activity_main)
        applyTheme()

        rv = findViewById(R.id.rv)
        etInput = findViewById(R.id.etInput)
        tvOutput = findViewById(R.id.tvOutput)
        tvClock = findViewById(R.id.tvClock)

        adapter = AppAdapter(
            onLaunch = { launchApp(it) },
            onLongPress = { app, anchor -> showAppMenu(app, anchor) }
        )
        rv.layoutManager = GridLayoutManager(this, 4)
        rv.adapter = adapter

        startClock()
        loadApps()
        setupInput()
    }

    override fun onResume() {
        super.onResume()
        loadApps()
    }

    private fun applyTheme() {
        val root = window.decorView.findViewById<View>(android.R.id.content)
        root?.setBackgroundColor(bgColor)
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor
    }

    private fun startClock() {
        val tick = object : Runnable {
            override fun run() {
                val now = java.util.Calendar.getInstance()
                val h = now.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
                val m = now.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
                tvClock.text = "$h:$m"
                handler.postDelayed(this, 10_000)
            }
        }
        handler.post(tick)
    }

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val flags = if (android.os.Build.VERSION.SDK_INT >= 33)
                PackageManager.GET_META_DATA.toLong()
            else 0L

            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val myPkg = packageName

            val apps = installed
                .filter { ai ->
                    ai.packageName != myPkg &&
                    pm.getLaunchIntentForPackage(ai.packageName) != null
                }
                .map { ai ->
                    AppInfo(
                        label = pm.getApplicationLabel(ai).toString(),
                        packageName = ai.packageName,
                        icon = pm.getApplicationIcon(ai)
                    )
                }
                .sortedBy { it.label.lowercase() }

            allApps = apps
            filteredApps = apps

            runOnUiThread {
                adapter.submitList(filteredApps)
                tvOutput.text = "${filteredApps.size} apps"
                applyColorsToViews()
            }
        }.start()
    }

    private fun applyColorsToViews() {
        tvClock.setTextColor(textColor)
        tvOutput.setTextColor(adjustAlpha(textColor, 0.5f))
        etInput.setTextColor(textColor)
        etInput.setHintTextColor(adjustAlpha(textColor, 0.3f))
        val bg = etInput.background
        if (bg != null) bg.setColorFilter(adjustAlpha(textColor, 0.3f), android.graphics.PorterDuff.Mode.SRC_IN)
        val root = window.decorView.findViewById<View>(android.R.id.content)
        root?.setBackgroundColor(bgColor)
        adapter.setTextColor(textColor)
    }

    private fun setupInput() {
        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                filterApps(query)
            }
        })

        etInput.setOnEditorActionListener { v, actionId, event ->
            val txt = etInput.text.toString().trim()
            handleCommand(txt)
            true
        }
    }

    private fun filterApps(query: String) {
        filteredApps = if (query.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
            }
        }
        adapter.submitList(filteredApps)
        tvOutput.text = "${filteredApps.size} apps"
    }

    private fun handleCommand(raw: String) {
        val lower = raw.lowercase().trim()
        when {
            lower == "open launcher.setting" -> showSettings()
            lower == "help" -> showHelp()
            lower == "list" -> showList()
            lower == "clear" || lower == "cls" -> etInput.setText("")
            lower.startsWith("run ") -> {
                val name = raw.substring(4).trim()
                val app = allApps.find { it.label.equals(name, ignoreCase = true) }
                if (app != null) launchApp(app) else showToast("App not found: $name")
                etInput.setText("")
            }
            lower.startsWith("delete ") -> {
                val name = raw.substring(7).trim()
                val app = allApps.find { it.label.equals(name, ignoreCase = true) }
                if (app != null) uninstallApp(app) else showToast("App not found: $name")
                etInput.setText("")
            }
        }
    }

    private fun launchApp(app: AppInfo) {
        val intent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            showToast("Cannot launch ${app.label}")
        }
    }

    private fun uninstallApp(app: AppInfo) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun showAppMenu(app: AppInfo, anchor: View) {
        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(Color.parseColor("#99000000"))
        overlay.id = android.R.id.custom

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val cardBg = android.graphics.drawable.GradientDrawable()
        cardBg.setColor(adjustDark(bgColor, 20))
        cardBg.cornerRadius = 12f * resources.displayMetrics.density
        cardBg.setStroke((1 * resources.displayMetrics.density).toInt(), adjustAlpha(textColor, 0.3f))
        card.background = cardBg
        val dp8 = (8 * resources.displayMetrics.density).toInt()
        card.setPadding(dp8, dp8, dp8, dp8)

        val title = TextView(this)
        title.text = app.label
        title.setTextColor(textColor)
        title.textSize = 13f
        title.setPadding(dp8, dp8, dp8, dp8)
        card.addView(title)

        val divider = View(this)
        divider.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        divider.setBackgroundColor(adjustAlpha(textColor, 0.2f))
        card.addView(divider)

        fun menuRow(label: String, color: Int = textColor, action: () -> Unit): TextView {
            val tv = TextView(this)
            tv.text = label
            tv.setTextColor(color)
            tv.textSize = 14f
            tv.setPadding(dp8, dp8 + 4, dp8, dp8 + 4)
            tv.setOnClickListener {
                (overlay.parent as? ViewGroup)?.removeView(overlay)
                action()
            }
            return tv
        }

        card.addView(menuRow("▸  open") { launchApp(app) })
        card.addView(menuRow("✕  uninstall", Color.parseColor("#ff4455")) { uninstallApp(app) })
        card.addView(menuRow("✕  close", adjustAlpha(textColor, 0.5f)) {
            /* just close */
        })

        val cardLp = FrameLayout.LayoutParams(
            (220 * resources.displayMetrics.density).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        cardLp.gravity = Gravity.CENTER
        overlay.addView(card, cardLp)

        overlay.setOnClickListener { (overlay.parent as? ViewGroup)?.removeView(overlay) }

        val root = window.decorView as ViewGroup
        root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Animate in
        card.alpha = 0f
        card.scaleX = 0.85f
        card.scaleY = 0.85f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun showSettings() {
        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(Color.parseColor("#CC000000"))

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val dp = resources.displayMetrics.density
        val dp16 = (16 * dp).toInt()
        val dp8 = (8 * dp).toInt()
        val dp4 = (4 * dp).toInt()

        val cardBg = android.graphics.drawable.GradientDrawable()
        cardBg.setColor(adjustDark(bgColor, 15))
        cardBg.cornerRadius = 12f * dp
        cardBg.setStroke((1 * dp).toInt(), adjustAlpha(textColor, 0.3f))
        card.background = cardBg
        card.setPadding(0, 0, 0, dp8)

        // Header
        val header = TextView(this)
        header.text = "launcher.setting"
        header.setTextColor(adjustAlpha(textColor, 0.6f))
        header.textSize = 11f
        header.setPadding(dp16, dp16, dp16, dp8)
        card.addView(header)

        val divH = View(this)
        divH.setBackgroundColor(adjustAlpha(textColor, 0.2f))
        divH.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        card.addView(divH)

        // BG color row
        val bgRow = makeSetting(card, "terminal background color", colorToHex(bgColor), dp)
        bgRow.setOnClickListener {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            showColorPicker("bg_color", bgColor) { color ->
                bgColor = color
                prefs.edit().putInt("bg_color", color).apply()
                applyTheme()
                applyColorsToViews()
            }
        }

        val div1 = View(this)
        div1.setBackgroundColor(adjustAlpha(textColor, 0.1f))
        div1.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        card.addView(div1)

        // Text color row
        val textRow = makeSetting(card, "text color", colorToHex(textColor), dp)
        textRow.setOnClickListener {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            showColorPicker("text_color", textColor) { color ->
                textColor = color
                prefs.edit().putInt("text_color", color).apply()
                applyColorsToViews()
            }
        }

        val div2 = View(this)
        div2.setBackgroundColor(adjustAlpha(textColor, 0.1f))
        div2.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        card.addView(div2)

        // Close button
        val closeBtn = TextView(this)
        closeBtn.text = "close"
        closeBtn.setTextColor(adjustAlpha(textColor, 0.5f))
        closeBtn.textSize = 13f
        closeBtn.gravity = Gravity.CENTER
        closeBtn.setPadding(dp16, dp16, dp16, dp8)
        closeBtn.setOnClickListener { (overlay.parent as? ViewGroup)?.removeView(overlay) }
        card.addView(closeBtn)

        val cardLp = FrameLayout.LayoutParams(
            (300 * dp).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        cardLp.gravity = Gravity.CENTER
        overlay.addView(card, cardLp)
        overlay.setOnClickListener { (overlay.parent as? ViewGroup)?.removeView(overlay) }

        val root = window.decorView as ViewGroup
        root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        card.alpha = 0f
        card.translationY = 30f * dp
        card.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun makeSetting(parent: LinearLayout, label: String, value: String, dp: Float): LinearLayout {
        val dp8 = (8 * dp).toInt()
        val dp16 = (16 * dp).toInt()

        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(dp16, dp8 + 4, dp16, dp8 + 4)

        val lbl = TextView(this)
        lbl.text = label
        lbl.setTextColor(adjustAlpha(textColor, 0.4f))
        lbl.textSize = 11f
        row.addView(lbl)

        val val_ = TextView(this)
        val_.text = value
        val_.setTextColor(textColor)
        val_.textSize = 14f
        row.addView(val_)

        parent.addView(row)
        return row
    }

    private fun showColorPicker(prefKey: String, currentColor: Int, onPick: (Int) -> Unit) {
        val dp = resources.displayMetrics.density
        val dp8 = (8 * dp).toInt()
        val dp16 = (16 * dp).toInt()
        val dp4 = (4 * dp).toInt()

        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(Color.parseColor("#CC000000"))

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val cardBg = android.graphics.drawable.GradientDrawable()
        cardBg.setColor(adjustDark(bgColor, 15))
        cardBg.cornerRadius = 12f * dp
        cardBg.setStroke((1 * dp).toInt(), adjustAlpha(textColor, 0.3f))
        card.background = cardBg
        card.setPadding(dp16, dp8, dp16, dp8)

        val title = TextView(this)
        title.text = if (prefKey == "bg_color") "background color" else "text color"
        title.setTextColor(adjustAlpha(textColor, 0.6f))
        title.textSize = 11f
        title.setPadding(0, dp8, 0, dp8)
        card.addView(title)

        // Preview swatch
        val preview = View(this)
        val previewLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (48 * dp).toInt())
        previewLp.bottomMargin = dp8
        val previewBg = android.graphics.drawable.GradientDrawable()
        previewBg.cornerRadius = 6f * dp
        previewBg.setColor(currentColor)
        preview.background = previewBg
        card.addView(preview, previewLp)

        // HSV sliders
        val hsv = FloatArray(3)
        Color.colorToHSV(currentColor, hsv)
        var pickedColor = currentColor

        fun makeSlider(labelStr: String, init: Float, max: Float = 360f): Pair<TextView, SeekBar> {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val dp60 = (60 * dp).toInt()

            val lbl = TextView(this)
            lbl.text = labelStr
            lbl.setTextColor(adjustAlpha(textColor, 0.5f))
            lbl.textSize = 11f
            lbl.layoutParams = LinearLayout.LayoutParams(dp60, LinearLayout.LayoutParams.WRAP_CONTENT)
            row.addView(lbl)

            val sb = SeekBar(this)
            sb.max = max.toInt()
            sb.progress = (init * (max / 1f)).toInt()
            sb.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            sb.progressDrawable?.setColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_IN)
            sb.thumb?.setColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_IN)
            row.addView(sb)

            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp4
            card.addView(row, lp)
            return Pair(lbl, sb)
        }

        val (hLbl, hSb) = makeSlider("H", hsv[0] / 360f, 360f)
        val (sLbl, sSb) = makeSlider("S", hsv[1], 100f)
        val (vLbl, vSb) = makeSlider("V", hsv[2], 100f)

        // Preset colors
        val presets = listOf(
            "#00ff88", "#00ffcc", "#00ccff", "#4488ff",
            "#ff4455", "#ffaa00", "#ffffff", "#0a0a0a",
            "#1a1a2e", "#111111", "#ff6ec7", "#7c3aed"
        )

        val presetGrid = LinearLayout(this)
        presetGrid.orientation = LinearLayout.HORIZONTAL
        presetGrid.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val presetScroll = HorizontalScrollView(this)
        presetScroll.isHorizontalScrollBarEnabled = false
        presetScroll.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val presetRow = LinearLayout(this)
        presetRow.orientation = LinearLayout.HORIZONTAL
        val swatchSize = (36 * dp).toInt()
        val swatchMargin = (4 * dp).toInt()

        for (hex in presets) {
            val swatch = View(this)
            val swatchBg = android.graphics.drawable.GradientDrawable()
            swatchBg.setColor(Color.parseColor(hex))
            swatchBg.cornerRadius = 4f * dp
            swatch.background = swatchBg
            val swatchLp = LinearLayout.LayoutParams(swatchSize, swatchSize)
            swatchLp.marginEnd = swatchMargin
            swatch.layoutParams = swatchLp
            swatch.setOnClickListener {
                val c = Color.parseColor(hex)
                Color.colorToHSV(c, hsv)
                hSb.progress = hsv[0].toInt()
                sSb.progress = (hsv[1] * 100).toInt()
                vSb.progress = (hsv[2] * 100).toInt()
                pickedColor = c
                previewBg.setColor(pickedColor)
            }
            presetRow.addView(swatch)
        }

        presetScroll.addView(presetRow)
        card.addView(presetScroll)

        val lp8 = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp8.topMargin = dp8

        // Hex input
        val hexInput = EditText(this)
        hexInput.setTextColor(textColor)
        hexInput.setHintTextColor(adjustAlpha(textColor, 0.3f))
        hexInput.hint = "#000000"
        hexInput.textSize = 13f
        hexInput.setText(colorToHex(currentColor))
        card.addView(hexInput, lp8)

        // Slider listener
        val sliderChange = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                hsv[0] = hSb.progress.toFloat()
                hsv[1] = sSb.progress / 100f
                hsv[2] = vSb.progress / 100f
                pickedColor = Color.HSVToColor(hsv)
                previewBg.setColor(pickedColor)
                hexInput.setText(colorToHex(pickedColor))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        hSb.setOnSeekBarChangeListener(sliderChange)
        sSb.setOnSeekBarChangeListener(sliderChange)
        vSb.setOnSeekBarChangeListener(sliderChange)

        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                try {
                    val hex = s.toString().trim()
                    if (hex.matches(Regex("#[0-9a-fA-F]{6}"))) {
                        val c = Color.parseColor(hex)
                        Color.colorToHSV(c, hsv)
                        hSb.progress = hsv[0].toInt()
                        sSb.progress = (hsv[1] * 100).toInt()
                        vSb.progress = (hsv[2] * 100).toInt()
                        pickedColor = c
                        previewBg.setColor(pickedColor)
                    }
                } catch (e: Exception) {}
            }
        })

        // Buttons row
        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.gravity = Gravity.END
        val lp16 = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp16.topMargin = dp16

        val cancelBtn = makeTextBtn("cancel", adjustAlpha(textColor, 0.5f)) {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
        val applyBtn = makeTextBtn("apply", textColor) {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            onPick(pickedColor)
        }
        btnRow.addView(cancelBtn)
        btnRow.addView(applyBtn)
        card.addView(btnRow, lp16)

        val cardLp = FrameLayout.LayoutParams(
            (320 * dp).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        cardLp.gravity = Gravity.CENTER
        overlay.addView(card, cardLp)
        overlay.setOnClickListener { } // block passthrough

        val root = window.decorView as ViewGroup
        root.addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        card.alpha = 0f
        card.scaleX = 0.9f
        card.scaleY = 0.9f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun makeTextBtn(text: String, color: Int, onClick: () -> Unit): TextView {
        val dp = resources.displayMetrics.density
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(color)
        tv.textSize = 13f
        tv.setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun showHelp() {
        showToast("run <app>  |  delete <app>  |  list  |  open launcher.setting  |  clear")
    }

    private fun showList() {
        val names = allApps.joinToString("  ") { it.label }
        showToast(names.take(200))
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // Color helpers
    private fun adjustAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun adjustDark(color: Int, amt: Int): Int {
        return Color.rgb(
            (Color.red(color) + amt).coerceIn(0, 255),
            (Color.green(color) + amt).coerceIn(0, 255),
            (Color.blue(color) + amt).coerceIn(0, 255)
        )
    }

    private fun colorToHex(color: Int): String {
        return String.format("#%06X", 0xFFFFFF and color)
    }

    override fun onBackPressed() {
        // swallow back — launcher
    }
}
