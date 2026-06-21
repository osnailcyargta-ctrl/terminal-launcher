package com.osnailcyargta.launcher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.*
import android.text.style.ForegroundColorSpan
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

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

    private var bgColor  = Color.parseColor("#0a0a0a")
    private var txtColor = Color.parseColor("#00ff88")

    private val cmdHistory = mutableListOf<String>()
    private var histIdx = -1

    // Persisted data
    private var pinnedApps  = mutableSetOf<String>()   // packageNames
    private var hiddenApps  = mutableSetOf<String>()   // packageNames
    private var aliases     = mutableMapOf<String, String>() // alias -> packageName or label
    private var vaultPass   = ""

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("lp", MODE_PRIVATE)
        bgColor   = prefs.getInt("bg",  Color.parseColor("#0a0a0a"))
        txtColor  = prefs.getInt("txt", Color.parseColor("#00ff88"))
        loadPersistedData()

        setContentView(R.layout.activity_main)

        termOutput = findViewById(R.id.termOutput)
        scrollView = findViewById(R.id.scrollView)
        etInput    = findViewById(R.id.etInput)
        tvClock    = findViewById(R.id.tvClock)
        tvStatus   = findViewById(R.id.tvStatus)

        // Fix: prevent ScrollView from stealing focus on Enter
        scrollView.isFocusable = false
        scrollView.isFocusableInTouchMode = false
        termOutput.isFocusable = false
        termOutput.isFocusableInTouchMode = false
        scrollView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        applyTheme()
        startClock()
        setupInput()
        boot()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        etInput.requestFocus()
    }

    // ── PERSIST ───────────────────────────────────────────────────────────────

    private fun loadPersistedData() {
        pinnedApps = prefs.getStringSet("pinned", mutableSetOf())!!.toMutableSet()
        hiddenApps = prefs.getStringSet("hidden", mutableSetOf())!!.toMutableSet()
        vaultPass  = prefs.getString("vpass", "") ?: ""

        val aliasJson = prefs.getString("aliases", "{}") ?: "{}"
        try {
            val obj = JSONObject(aliasJson)
            obj.keys().forEach { k -> aliases[k] = obj.getString(k) }
        } catch (_: Exception) {}
    }

    private fun saveAliases() {
        val obj = JSONObject()
        aliases.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString("aliases", obj.toString()).apply()
    }

    private fun savePinned() {
        prefs.edit().putStringSet("pinned", pinnedApps).apply()
    }

    private fun saveHidden() {
        prefs.edit().putStringSet("hidden", hiddenApps).apply()
    }

    // ── THEME ─────────────────────────────────────────────────────────────────

    private fun applyTheme() {
        findViewById<View>(R.id.root).setBackgroundColor(bgColor)
        window.statusBarColor     = bgColor
        window.navigationBarColor = bgColor

        tvClock.setTextColor(dim(txtColor, 0.9f))
        tvStatus.setTextColor(dim(txtColor, 0.4f))
        etInput.setTextColor(txtColor)
        etInput.setHintTextColor(dim(txtColor, 0.22f))
        findViewById<TextView>(R.id.tvPrompt).setTextColor(dim(txtColor, 0.35f))
        findViewById<View>(R.id.inputBar).setBackgroundColor(darken(bgColor, 6))
        findViewById<View>(R.id.divider).setBackgroundColor(dim(txtColor, 0.1f))
        val statusBar = findViewById<View>(R.id.statusBar)
        statusBar?.setBackgroundColor(darken(bgColor, 6))
        val statusDiv = findViewById<View>(R.id.statusDivider)
        statusDiv?.setBackgroundColor(dim(txtColor, 0.1f))
    }

    // ── CLOCK ─────────────────────────────────────────────────────────────────

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

    // ── BOOT ──────────────────────────────────────────────────────────────────

    private fun boot() {
        listOf(
            "──────────────────────────────" to "system",
            "  PERSONAL LAUNCHER  v4.0"     to "system",
            "──────────────────────────────" to "system",
        ).forEachIndexed { i, (t, c) ->
            handler.postDelayed({ printLine(t, c) }, i * 60L)
        }
        handler.postDelayed({ printBlank() }, 3 * 60L)
    }

    // ── APP LOADING ───────────────────────────────────────────────────────────

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { ai ->
                    ai.packageName != packageName &&
                    pm.getLaunchIntentForPackage(ai.packageName) != null
                }
                .map { ai -> AppInfo(pm.getApplicationLabel(ai).toString(), ai.packageName) }
                .sortedWith(compareBy(
                    { !pinnedApps.contains(it.packageName) },
                    { it.label.lowercase() }
                ))

            allApps = apps
            runOnUiThread {
                val visible = apps.count { !hiddenApps.contains(it.packageName) }
                tvStatus.text = "$visible apps installed"
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
                cmdHistory.add(0, raw)
                handleCommand(raw.trim())
            }
            // Keep focus on input, never let ScrollView grab it
            etInput.requestFocus()
            true
        }

        etInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (histIdx < cmdHistory.size - 1) {
                            histIdx++
                            etInput.setText(cmdHistory[histIdx])
                            etInput.setSelection(etInput.text.length)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (histIdx > 0) {
                            histIdx--
                            etInput.setText(cmdHistory[histIdx])
                            etInput.setSelection(etInput.text.length)
                        } else {
                            histIdx = -1
                            etInput.setText("")
                        }
                        true
                    }
                    else -> false
                }
            } else false
        }

        // Tap anywhere on terminal → refocus input
        scrollView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                etInput.requestFocus()
            }
            false
        }
    }

    // ── COMMAND HANDLER ───────────────────────────────────────────────────────

    private fun handleCommand(raw: String) {
        val ts = "[${timeFmt.format(Date())}]"
        printPrompt(raw, ts)

        val lower = raw.lowercase().trim()

        // Check alias first
        val firstWord = lower.split(" ")[0]
        if (aliases.containsKey(firstWord) && !lower.startsWith("alias") && !lower.startsWith("unalias")) {
            val resolved = aliases[firstWord]!!
            cmdRun(resolved)
            printBlank(); scrollBottom(); return
        }

        when {
            lower == "help"    -> cmdHelp()
            lower == "list"    -> cmdList()
            lower == "history" -> cmdHistory()
            lower == "vault"   -> cmdVault()
            lower == "clear" || lower == "cls" -> {
                termOutput.removeAllViews()
                etInput.requestFocus()
                return
            }
            lower == "open launcher.setting" -> cmdSettings()
            lower == "alias list" -> cmdAliasList()

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
            lower.startsWith("pin ") -> {
                val rest = raw.substring(4).trim()
                cmdPin(rest)
            }
            lower.startsWith("hide ") -> {
                val name = raw.substring(5).trim()
                cmdHide(name)
            }
            lower.startsWith("unhide ") -> {
                val name = raw.substring(7).trim()
                cmdUnhide(name)
            }
            lower.startsWith("alias ") && !lower.startsWith("alias list") -> {
                val rest = raw.substring(6).trim()
                cmdAlias(rest)
            }
            lower.startsWith("unalias ") -> {
                val name = raw.substring(8).trim()
                cmdUnalias(name)
            }
            lower.startsWith("info ") -> {
                val name = raw.substring(5).trim()
                cmdInfo(name)
            }
            lower.startsWith("search ") -> {
                val q = raw.substring(7).trim()
                cmdSearch(q)
            }
            lower == "recent" -> cmdRecent()

            else -> printLine("unknown: $raw", "error")
        }
        printBlank()
        scrollBottom()
        etInput.requestFocus()
    }

    // ── COMMANDS ──────────────────────────────────────────────────────────────

    private fun cmdHelp() {
        printLine("commands:", "info")
        printLine("  run <app>              launch app", "output")
        printLine("  delete <app>           uninstall app", "output")
        printLine("  list                   show all apps", "output")
        printLine("  search <kata>          filter apps", "output")
        printLine("  pin <app>              pin app to top", "output")
        printLine("  pin <app> off          unpin app", "output")
        printLine("  alias <key> <app>      create shortcut", "output")
        printLine("  alias list             show all aliases", "output")
        printLine("  unalias <key>          remove alias", "output")
        printLine("  info <app>             app details", "output")
        printLine("  recent                 recently opened", "output")
        printLine("  history                command history", "output")
        printLine("  open launcher.setting  settings", "output")
        printLine("  clear                  clear terminal", "output")
        printLine("  help                   show commands", "output")
    }

    private fun cmdList() {
        val visible = allApps.filter { !hiddenApps.contains(it.packageName) }
        if (visible.isEmpty()) { printLine("no apps found.", "output"); return }
        printLine("${visible.size} apps:", "info")
        visible.forEachIndexed { i, app ->
            val num  = (i + 1).toString().padStart(3)
            val pin  = if (pinnedApps.contains(app.packageName)) " ★" else ""
            printLine("$num  >_  ${app.label}$pin", "app")
        }
    }

    private fun cmdSearch(query: String) {
        val results = allApps.filter {
            !hiddenApps.contains(it.packageName) &&
            (it.label.contains(query, ignoreCase = true) ||
             it.packageName.contains(query, ignoreCase = true))
        }
        if (results.isEmpty()) { printLine("no results for: $query", "warn"); return }
        printLine("${results.size} result(s):", "info")
        results.forEachIndexed { i, app ->
            val num = (i + 1).toString().padStart(3)
            printLine("$num  >_  ${app.label}", "app")
        }
    }

    private fun cmdRun(name: String) {
        val app = findApp(name)
        if (app == null) { printLine("not found: $name", "error"); return }
        val intent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent != null) {
            printLine("launching ${app.label}...", "output")
            // Save to recent
            val recents = getRecents().toMutableList()
            recents.removeAll { it == app.packageName }
            recents.add(0, app.packageName)
            prefs.edit().putString("recents", recents.take(10).joinToString(",")).apply()

            handler.postDelayed({
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }, 250)
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

    private fun cmdPin(rest: String) {
        if (rest.lowercase().endsWith(" off")) {
            val name = rest.dropLast(4).trim()
            val app = findApp(name)
            if (app == null) { printLine("not found: $name", "error"); return }
            pinnedApps.remove(app.packageName)
            savePinned()
            loadApps()
            printLine("${app.label} unpinned.", "success")
        } else {
            val app = findApp(rest)
            if (app == null) { printLine("not found: $rest", "error"); return }
            pinnedApps.add(app.packageName)
            savePinned()
            loadApps()
            printLine("${app.label} pinned ★", "success")
        }
    }

    private fun cmdHide(name: String) {
        val app = findApp(name)
        if (app == null) { printLine("not found: $name", "error"); return }
        hiddenApps.add(app.packageName)
        saveHidden()
        loadApps()
        printLine("${app.label} hidden → vault", "success")
    }

    private fun cmdUnhide(name: String) {
        // search including hidden
        val app = allApps.find { it.label.equals(name, ignoreCase = true) }
            ?: allApps.find { it.label.lowercase().contains(name.lowercase()) }
        if (app == null) { printLine("not found: $name", "error"); return }
        hiddenApps.remove(app.packageName)
        saveHidden()
        loadApps()
        printLine("${app.label} removed from vault.", "success")
    }

    private fun cmdVault() {
        val hidden = allApps.filter { hiddenApps.contains(it.packageName) }
        if (hidden.isEmpty()) { printLine("vault is empty.", "output"); return }
        printLine("vault [${hidden.size}]:", "warn")
        hidden.forEachIndexed { i, app ->
            val num = (i + 1).toString().padStart(3)
            printLine("$num  >_  ${app.label}", "app")
        }
        printLine("use: run <app> to open, unhide <app> to remove from vault", "system")
    }

    private fun cmdAlias(rest: String) {
        val parts = rest.trim().split(" ", limit = 2)
        if (parts.size < 2) { printLine("usage: alias <key> <app name>", "warn"); return }
        val key     = parts[0].lowercase()
        val appName = parts[1].trim()
        val app = findApp(appName)
        if (app == null) { printLine("app not found: $appName", "error"); return }
        aliases[key] = app.label
        saveAliases()
        printLine("alias '$key' → ${app.label}", "success")
    }

    private fun cmdUnalias(key: String) {
        if (!aliases.containsKey(key.lowercase())) {
            printLine("alias not found: $key", "error"); return
        }
        aliases.remove(key.lowercase())
        saveAliases()
        printLine("alias '$key' removed.", "success")
    }

    private fun cmdAliasList() {
        if (aliases.isEmpty()) { printLine("no aliases set.", "output"); return }
        printLine("aliases:", "info")
        aliases.forEach { (k, v) -> printLine("  $k  →  $v", "app") }
    }

    private fun cmdInfo(name: String) {
        val app = findApp(name) ?: run {
            printLine("not found: $name", "error"); return
        }
        val pm = packageManager
        try {
            val pi = pm.getPackageInfo(app.packageName, 0)
            val ai = pm.getApplicationInfo(app.packageName, 0)
            val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            val installed = sdf.format(Date(pi.firstInstallTime))
            val updated   = sdf.format(Date(pi.lastUpdateTime))
            val sizeMb = try {
                val src = ai.sourceDir
                val bytes = java.io.File(src).length()
                "%.1f MB".format(bytes / 1_048_576.0)
            } catch (_: Exception) { "unknown" }

            printLine("info: ${app.label}", "info")
            printLine("  package  : ${app.packageName}", "output")
            printLine("  version  : ${pi.versionName ?: "?"}", "output")
            printLine("  size     : $sizeMb", "output")
            printLine("  installed: $installed", "output")
            printLine("  updated  : $updated", "output")
            val isPinned = pinnedApps.contains(app.packageName)
            val isHidden = hiddenApps.contains(app.packageName)
            if (isPinned) printLine("  pinned   : ★", "output")
            if (isHidden) printLine("  vault    : yes", "output")
        } catch (e: Exception) {
            printLine("could not get info: ${e.message}", "error")
        }
    }

    private fun cmdRecent() {
        val pkgs = getRecents()
        if (pkgs.isEmpty()) { printLine("no recent apps.", "output"); return }
        printLine("recent:", "info")
        pkgs.forEachIndexed { i, pkg ->
            val app = allApps.find { it.packageName == pkg }
            val label = app?.label ?: pkg
            printLine("  ${i + 1}.  >_  $label", "app")
        }
    }

    private fun cmdHistory() {
        if (cmdHistory.isEmpty()) { printLine("no history.", "output"); return }
        printLine("history:", "info")
        cmdHistory.take(20).forEachIndexed { i, cmd ->
            printLine("  ${(i + 1).toString().padStart(3)}  $cmd", "output")
        }
    }

    private fun cmdSettings() {
        printLine("opening settings...", "output")
        scrollBottom()
        handler.postDelayed({ showSettingsOverlay() }, 150)
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private fun getRecents(): List<String> {
        val s = prefs.getString("recents", "") ?: ""
        return if (s.isEmpty()) emptyList() else s.split(",").filter { it.isNotBlank() }
    }

    private fun findApp(name: String): AppInfo? {
        val lower = name.lowercase()
        // Check alias
        val aliasTarget = aliases[lower]
        if (aliasTarget != null) {
            return allApps.find { it.label.equals(aliasTarget, ignoreCase = true) }
                ?: allApps.find { it.label.lowercase().contains(aliasTarget.lowercase()) }
        }
        return allApps.find { it.label.equals(name, ignoreCase = true) }
            ?: allApps.find { it.label.lowercase().startsWith(lower) }
            ?: allApps.find { it.label.lowercase().contains(lower) }
            ?: allApps.find { it.packageName.lowercase().contains(lower) }
    }

    // ── TERMINAL OUTPUT ───────────────────────────────────────────────────────

    private fun printLine(text: String, type: String = "output") {
        val tv = makeTV()
        tv.text = text
        tv.setTextColor(colorForType(type))
        if (type == "system") tv.alpha = 0.45f
        termOutput.addView(tv)
    }

    private fun printPrompt(cmd: String, ts: String) {
        val tv = makeTV()
        val full = "$ts ❯ $cmd"
        val ss = SpannableString(full)
        // timestamp dim
        ss.setSpan(ForegroundColorSpan(dim(txtColor, 0.25f)), 0, ts.length, 0)
        // prompt symbol
        val pStart = ts.length + 1
        ss.setSpan(ForegroundColorSpan(dim(txtColor, 0.35f)), pStart, pStart + 2, 0)
        // command text
        ss.setSpan(ForegroundColorSpan(txtColor), pStart + 2, full.length, 0)
        tv.text = ss
        termOutput.addView(tv)
    }

    private fun printBlank() {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (7 * resources.displayMetrics.density).toInt()
        )
        termOutput.addView(v)
    }

    private fun makeTV(): TextView {
        val tv = TextView(this)
        tv.typeface = Typeface.MONOSPACE
        tv.textSize = 13f
        tv.setLineSpacing(2f, 1f)
        tv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        tv.isFocusable = false
        tv.isFocusableInTouchMode = false
        return tv
    }

    private fun colorForType(type: String) = when (type) {
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
        val dp  = resources.displayMetrics.density
        val dp8 = (8 * dp).toInt()
        val dp12 = (12 * dp).toInt()
        val dp16 = (16 * dp).toInt()

        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(Color.parseColor("#CC000000"))
        overlay.isFocusable = false
        overlay.isFocusableInTouchMode = false

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = GradientDrawable().apply {
            setColor(darken(bgColor, 12))
            cornerRadius = 10f * dp
            setStroke((1 * dp).toInt(), dim(txtColor, 0.25f))
        }
        card.setPadding(0, 0, 0, dp8)

        val header = monoTV("launcher.setting", 11f, dim(txtColor, 0.5f))
        header.setPadding(dp16, dp12, dp16, dp8)
        card.addView(header)
        card.addView(makeDivider(dp))

        fun row(label: String, value: String, action: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp16, dp12, dp16, dp12)
                isClickable = true; isFocusable = true
                setOnClickListener { action() }
            }
            r.addView(monoTV(label, 10f, dim(txtColor, 0.35f)))
            r.addView(monoTV(value, 13f, txtColor).also {
                it.setPadding(0, (3 * dp).toInt(), 0, 0)
            })
            card.addView(r)
            card.addView(makeDivider(dp))
        }

        row("change terminal background color", colorHex(bgColor)) {
            dismissOverlay(overlay)
            showColorPicker("bg", bgColor) { c ->
                bgColor = c; prefs.edit().putInt("bg", c).apply()
                applyTheme()
                printLine("bg → ${colorHex(c)}", "success")
                printBlank(); scrollBottom()
            }
        }
        row("change text color", colorHex(txtColor)) {
            dismissOverlay(overlay)
            showColorPicker("txt", txtColor) { c ->
                txtColor = c; prefs.edit().putInt("txt", c).apply()
                applyTheme()
                printLine("text color → ${colorHex(c)}", "success")
                printBlank(); scrollBottom()
            }
        }

        val close = monoTV("close", 12f, dim(txtColor, 0.4f))
        close.gravity = Gravity.CENTER
        close.setPadding(dp16, dp12, dp16, dp8)
        close.setOnClickListener { dismissOverlay(overlay) }
        card.addView(close)

        val lp = FrameLayout.LayoutParams((290 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER
        overlay.addView(card, lp)
        overlay.setOnClickListener { dismissOverlay(overlay) }

        (window.decorView as ViewGroup).addView(
            overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        card.alpha = 0f; card.translationY = 24f * dp
        card.animate().alpha(1f).translationY(0f).setDuration(200)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    // ── COLOR PICKER ──────────────────────────────────────────────────────────

    private fun showColorPicker(key: String, current: Int, onApply: (Int) -> Unit) {
        val dp = resources.displayMetrics.density
        val dp4 = (4 * dp).toInt(); val dp8 = (8 * dp).toInt()
        val dp12 = (12 * dp).toInt(); val dp16 = (16 * dp).toInt()

        val hsv = FloatArray(3)
        Color.colorToHSV(current, hsv)
        var picked = current
        var updating = false

        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(Color.parseColor("#CC000000"))

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = GradientDrawable().apply {
            setColor(darken(bgColor, 12)); cornerRadius = 10f * dp
            setStroke((1 * dp).toInt(), dim(txtColor, 0.25f))
        }
        card.setPadding(dp16, dp12, dp16, dp12)

        card.addView(monoTV(if (key == "bg") "background color" else "text color", 11f, dim(txtColor, 0.5f)).also {
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp8; it.layoutParams = lp
        })

        val previewBg = GradientDrawable().apply { cornerRadius = 6f * dp; setColor(picked) }
        val preview = View(this).apply {
            background = previewBg
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (44 * dp).toInt()).also {
                it.bottomMargin = dp8
            }
        }
        card.addView(preview)

        val hexEt = EditText(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 13f
            setTextColor(txtColor); setHintTextColor(dim(txtColor, 0.3f))
            hint = "#rrggbb"; setText(colorHex(picked))
            background = GradientDrawable().apply {
                setColor(darken(bgColor, 18)); cornerRadius = 4f * dp
                setStroke((1 * dp).toInt(), dim(txtColor, 0.2f))
            }
            setPadding(dp8, dp8, dp8, dp8)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = dp4
            }
        }
        card.addView(hexEt)

        fun mkSlider(lbl: String, init: Int, max: Int, cb: (Int) -> Unit): SeekBar {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp4 }
            }
            row.addView(monoTV(lbl, 10f, dim(txtColor, 0.4f)).also {
                it.layoutParams = LinearLayout.LayoutParams((34 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            val sb = SeekBar(this).apply {
                this.max = max; progress = init
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                progressDrawable?.setColorFilter(txtColor, PorterDuff.Mode.SRC_IN)
                thumb?.setColorFilter(txtColor, PorterDuff.Mode.SRC_IN)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fu: Boolean) { if (fu) cb(p) }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            row.addView(sb); card.addView(row); return sb
        }

        val hSb = mkSlider("H", hsv[0].toInt(), 360) { p ->
            if (updating) return@mkSlider
            hsv[0] = p.toFloat(); picked = Color.HSVToColor(hsv)
            previewBg.setColor(picked)
            updating = true; hexEt.setText(colorHex(picked)); updating = false
        }
        val sSb = mkSlider("S", (hsv[1] * 100).toInt(), 100) { p ->
            if (updating) return@mkSlider
            hsv[1] = p / 100f; picked = Color.HSVToColor(hsv)
            previewBg.setColor(picked)
            updating = true; hexEt.setText(colorHex(picked)); updating = false
        }
        val vSb = mkSlider("V", (hsv[2] * 100).toInt(), 100) { p ->
            if (updating) return@mkSlider
            hsv[2] = p / 100f; picked = Color.HSVToColor(hsv)
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
                        picked = c; previewBg.setColor(picked)
                    }
                } catch (_: Exception) {}
            }
        })

        // Preset swatches
        val presets = listOf("#00ff88","#00ffcc","#00ccff","#4488ff","#ff4455","#ffaa00","#ffffff","#aaaaaa","#ff6ec7","#7c3aed","#0a0a0a","#111111")
        val swatchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presets.forEach { hex ->
            swatchRow.addView(View(this).apply {
                background = GradientDrawable().apply { setColor(Color.parseColor(hex)); cornerRadius = 3f * dp }
                val sz = (28 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = (4 * dp).toInt() }
                setOnClickListener {
                    val c = Color.parseColor(hex)
                    Color.colorToHSV(c, hsv)
                    updating = true
                    hSb.progress = hsv[0].toInt()
                    sSb.progress = (hsv[1] * 100).toInt()
                    vSb.progress = (hsv[2] * 100).toInt()
                    updating = false
                    picked = c; previewBg.setColor(picked); hexEt.setText(hex)
                }
            })
        }
        card.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(swatchRow)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp8 }
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp8 }
        }
        fun btn(t: String, c: Int, a: () -> Unit) = monoTV(t, 13f, c).apply {
            setPadding(dp16, dp8, dp8, dp8); setOnClickListener { a() }
        }
        btnRow.addView(btn("cancel", dim(txtColor, 0.4f)) { dismissOverlay(overlay) })
        btnRow.addView(btn("apply",  txtColor) { dismissOverlay(overlay); onApply(picked) })
        card.addView(btnRow)

        val lp = FrameLayout.LayoutParams((310 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER
        overlay.addView(card, lp)
        overlay.setOnClickListener {}

        (window.decorView as ViewGroup).addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        card.alpha = 0f; card.scaleX = 0.9f; card.scaleY = 0.9f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
    }

    // ── UI HELPERS ────────────────────────────────────────────────────────────

    private fun monoTV(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text; typeface = Typeface.MONOSPACE; textSize = size
        setTextColor(color); isFocusable = false; isFocusableInTouchMode = false
    }

    private fun dismissOverlay(overlay: View) {
        overlay.animate().alpha(0f).setDuration(120).withEndAction {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            etInput.requestFocus()
        }.start()
    }

    private fun makeDivider(dp: Float) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt())
        setBackgroundColor(dim(txtColor, 0.12f))
    }

    private fun dim(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun darken(color: Int, amt: Int) = Color.rgb(
        (Color.red(color) + amt).coerceIn(0, 255),
        (Color.green(color) + amt).coerceIn(0, 255),
        (Color.blue(color) + amt).coerceIn(0, 255)
    )

    private fun colorHex(color: Int) = String.format("#%06X", 0xFFFFFF and color)

    override fun onBackPressed() { /* swallow */ }
}
