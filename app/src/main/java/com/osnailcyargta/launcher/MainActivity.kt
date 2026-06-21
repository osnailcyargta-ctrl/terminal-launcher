package com.osnailcyargta.launcher

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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
    private lateinit var rootView: LinearLayout
    private lateinit var wallpaperView: ImageView

    private var allApps: List<AppInfo> = emptyList()
    private val handler = Handler(Looper.getMainLooper())

    private var bgColor   = Color.parseColor("#0a0a0a")
    private var txtColor  = Color.parseColor("#00ff88")
    private var wallpaperAlpha = 0.15f

    private val cmdHistory = mutableListOf<String>()
    private var histIdx = -1

    private var pinnedApps = mutableSetOf<String>()
    private var hiddenApps = mutableSetOf<String>()
    private var aliases    = mutableMapOf<String, String>()

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val wallpaperFile get() = File(filesDir, "wallpaper.jpg")

    companion object {
        const val REQ_PICK_IMAGE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("lp", MODE_PRIVATE)
        bgColor        = prefs.getInt("bg",  Color.parseColor("#0a0a0a"))
        txtColor       = prefs.getInt("txt", Color.parseColor("#00ff88"))
        wallpaperAlpha = prefs.getFloat("wp_alpha", 0.15f)
        loadPersistedData()

        setContentView(R.layout.activity_main)

        rootView     = findViewById(R.id.root)
        termOutput   = findViewById(R.id.termOutput)
        scrollView   = findViewById(R.id.scrollView)
        etInput      = findViewById(R.id.etInput)
        tvClock      = findViewById(R.id.tvClock)
        tvStatus     = findViewById(R.id.tvStatus)
        wallpaperView = findViewById(R.id.wallpaperView)

        scrollView.isFocusable = false
        scrollView.isFocusableInTouchMode = false
        termOutput.isFocusable = false
        termOutput.isFocusableInTouchMode = false
        scrollView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        applyTheme()
        loadWallpaper()
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
        val aj = prefs.getString("aliases", "{}") ?: "{}"
        try { val o = JSONObject(aj); o.keys().forEach { k -> aliases[k] = o.getString(k) } } catch (_: Exception) {}
    }

    private fun saveAliases() {
        val o = JSONObject(); aliases.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString("aliases", o.toString()).apply()
    }
    private fun savePinned() = prefs.edit().putStringSet("pinned", pinnedApps).apply()
    private fun saveHidden() = prefs.edit().putStringSet("hidden", hiddenApps).apply()

    // ── WALLPAPER ─────────────────────────────────────────────────────────────

    private fun loadWallpaper() {
        if (wallpaperFile.exists()) {
            try {
                val bmp = BitmapFactory.decodeFile(wallpaperFile.absolutePath)
                wallpaperView.setImageBitmap(bmp)
                wallpaperView.alpha = wallpaperAlpha
                wallpaperView.visibility = View.VISIBLE
            } catch (_: Exception) {}
        } else {
            wallpaperView.visibility = View.GONE
        }
    }

    private fun setWallpaperFromUri(uri: Uri) {
        try {
            val stream = contentResolver.openInputStream(uri) ?: return
            val bmp = BitmapFactory.decodeStream(stream)
            stream.close()
            // Scale down to save memory
            val maxSide = 1080
            val scaled = if (bmp.width > maxSide || bmp.height > maxSide) {
                val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            } else bmp
            FileOutputStream(wallpaperFile).use { fos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, fos)
            }
            wallpaperView.setImageBitmap(scaled)
            wallpaperView.alpha = wallpaperAlpha
            wallpaperView.visibility = View.VISIBLE
            printLine("wallpaper set.", "success")
            printBlank(); scrollBottom()
        } catch (e: Exception) {
            printLine("failed to set wallpaper: ${e.message}", "error")
            printBlank(); scrollBottom()
        }
    }

    private fun removeWallpaper() {
        wallpaperFile.delete()
        wallpaperView.setImageDrawable(null)
        wallpaperView.visibility = View.GONE
        printLine("wallpaper removed.", "success")
        printBlank(); scrollBottom()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_IMAGE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            setWallpaperFromUri(uri)
        }
    }

    // ── THEME ─────────────────────────────────────────────────────────────────

    private fun applyTheme() {
        rootView.setBackgroundColor(bgColor)
        window.statusBarColor     = bgColor
        window.navigationBarColor = bgColor
        tvClock.setTextColor(dim(txtColor, 0.9f))
        tvStatus.setTextColor(dim(txtColor, 0.4f))
        etInput.setTextColor(txtColor)
        etInput.setHintTextColor(dim(txtColor, 0.22f))
        findViewById<TextView>(R.id.tvPrompt).setTextColor(dim(txtColor, 0.35f))
        findViewById<View>(R.id.inputBar).setBackgroundColor(darken(bgColor, 6))
        findViewById<View>(R.id.divider).setBackgroundColor(dim(txtColor, 0.1f))
        findViewById<View>(R.id.statusBar)?.setBackgroundColor(darken(bgColor, 6))
        findViewById<View>(R.id.statusDivider)?.setBackgroundColor(dim(txtColor, 0.1f))
    }

    // ── CLOCK ─────────────────────────────────────────────────────────────────

    private fun startClock() {
        val tick = object : Runnable {
            override fun run() {
                val c = Calendar.getInstance()
                tvClock.text = "${c.get(Calendar.HOUR_OF_DAY).toString().padStart(2,'0')}:${c.get(Calendar.MINUTE).toString().padStart(2,'0')}"
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
                .filter { ai -> ai.packageName != packageName && pm.getLaunchIntentForPackage(ai.packageName) != null }
                .map { ai -> AppInfo(pm.getApplicationLabel(ai).toString(), ai.packageName) }
                .sortedWith(compareBy({ !pinnedApps.contains(it.packageName) }, { it.label.lowercase() }))
            allApps = apps
            runOnUiThread {
                tvStatus.text = "${apps.count { !hiddenApps.contains(it.packageName) }} apps installed"
            }
        }.start()
    }

    // ── INPUT ─────────────────────────────────────────────────────────────────

    private fun setupInput() {
        etInput.setOnEditorActionListener { _, _, _ ->
            val raw = etInput.text.toString()
            etInput.setText("")
            histIdx = -1
            if (raw.isNotBlank()) { cmdHistory.add(0, raw); handleCommand(raw.trim()) }
            etInput.requestFocus()
            true
        }
        etInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (histIdx < cmdHistory.size - 1) { histIdx++; etInput.setText(cmdHistory[histIdx]); etInput.setSelection(etInput.text.length) }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (histIdx > 0) { histIdx--; etInput.setText(cmdHistory[histIdx]); etInput.setSelection(etInput.text.length) }
                        else { histIdx = -1; etInput.setText("") }
                        true
                    }
                    else -> false
                }
            } else false
        }
        scrollView.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_UP) etInput.requestFocus(); false }
    }

    // ── COMMAND HANDLER ───────────────────────────────────────────────────────

    private fun handleCommand(raw: String) {
        val ts = "[${timeFmt.format(Date())}]"
        printPrompt(raw, ts)
        val lower = raw.lowercase().trim()

        // alias shortcut
        val firstWord = lower.split(" ")[0]
        if (aliases.containsKey(firstWord) && !lower.startsWith("alias") && !lower.startsWith("unalias")) {
            cmdRun(aliases[firstWord]!!); printBlank(); scrollBottom(); return
        }

        when {
            lower == "help"                    -> cmdHelp()
            lower == "list"                    -> cmdList()
            lower == "history"                 -> cmdHistoryCmd()
            lower == "vault"                   -> cmdVault()
            lower == "clear" || lower == "cls" -> { termOutput.removeAllViews(); etInput.requestFocus(); return }
            lower == "open launcher.setting" || lower == "setting" || lower == "settings" -> cmdSettings()
            lower == "alias list"              -> cmdAliasList()
            lower == "recent"                  -> cmdRecent()
            lower.startsWith("run ")           -> { val n = raw.substring(4).trim(); if (n.isEmpty()) printLine("usage: run <app>", "warn") else cmdRun(n) }
            lower.startsWith("delete ")        -> { val n = raw.substring(7).trim(); if (n.isEmpty()) printLine("usage: delete <app>", "warn") else cmdDelete(n) }
            lower.startsWith("pin ")           -> cmdPin(raw.substring(4).trim())
            lower.startsWith("hide ")          -> { val n = raw.substring(5).trim(); cmdHide(n) }
            lower.startsWith("unhide ")        -> { val n = raw.substring(7).trim(); cmdUnhide(n) }
            lower.startsWith("alias ") && !lower.startsWith("alias list") -> cmdAlias(raw.substring(6).trim())
            lower.startsWith("unalias ")       -> cmdUnalias(raw.substring(8).trim())
            lower.startsWith("info ")          -> cmdInfo(raw.substring(5).trim())
            lower.startsWith("search ")        -> cmdSearch(raw.substring(7).trim())
            lower.startsWith("print ")         -> cmdPrint(raw.substring(6).trim())
            else -> printLine("unknown: $raw", "error")
        }
        printBlank(); scrollBottom(); etInput.requestFocus()
    }

    // ── COMMANDS ──────────────────────────────────────────────────────────────

    private fun cmdHelp() {
        printLine("commands:", "info")
        printLine("  run <app>              launch app", "output")
        printLine("  delete <app>           uninstall app", "output")
        printLine("  list                   show all apps", "output")
        printLine("  search <kata>          filter apps", "output")
        printLine("  pin <app>              pin to top  ★", "output")
        printLine("  pin <app> off          unpin", "output")
        printLine("  alias <key> <app>      create shortcut", "output")
        printLine("  alias list             show aliases", "output")
        printLine("  unalias <key>          remove alias", "output")
        printLine("  info <app>             app details", "output")
        printLine("  recent                 recently opened", "output")
        printLine("  history                command history", "output")
        printLine("  setting                open settings", "output")
        printLine("  clear                  clear terminal", "output")
        printLine("  help                   show commands", "output")
    }

    private fun cmdList() {
        val visible = allApps.filter { !hiddenApps.contains(it.packageName) }
        if (visible.isEmpty()) { printLine("no apps found.", "output"); return }
        printLine("${visible.size} apps:", "info")
        visible.forEachIndexed { i, app ->
            val pin = if (pinnedApps.contains(app.packageName)) " ★" else ""
            printLine("${(i+1).toString().padStart(3)}  >_  ${app.label}$pin", "app")
        }
    }

    private fun cmdSearch(q: String) {
        val r = allApps.filter { !hiddenApps.contains(it.packageName) && (it.label.contains(q, true) || it.packageName.contains(q, true)) }
        if (r.isEmpty()) { printLine("no results for: $q", "warn"); return }
        printLine("${r.size} result(s):", "info")
        r.forEachIndexed { i, app -> printLine("${(i+1).toString().padStart(3)}  >_  ${app.label}", "app") }
    }

    private fun cmdRun(name: String) {
        val app = findApp(name) ?: run { printLine("not found: $name", "error"); return }
        val intent = packageManager.getLaunchIntentForPackage(app.packageName) ?: run { printLine("cannot launch ${app.label}", "error"); return }
        printLine("launching ${app.label}...", "output")
        val recents = getRecents().toMutableList().also { it.removeAll { p -> p == app.packageName }; it.add(0, app.packageName) }
        prefs.edit().putString("recents", recents.take(10).joinToString(",")).apply()
        handler.postDelayed({ intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent) }, 250)
    }

    private fun cmdDelete(name: String) {
        val app = findApp(name) ?: run { printLine("not found: $name", "error"); return }
        printLine("uninstalling ${app.label}...", "warn")
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")).also { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }

    private fun cmdPin(rest: String) {
        if (rest.lowercase().endsWith(" off")) {
            val app = findApp(rest.dropLast(4).trim()) ?: run { printLine("not found", "error"); return }
            pinnedApps.remove(app.packageName); savePinned(); loadApps()
            printLine("${app.label} unpinned.", "success")
        } else {
            val app = findApp(rest) ?: run { printLine("not found: $rest", "error"); return }
            pinnedApps.add(app.packageName); savePinned(); loadApps()
            printLine("${app.label} pinned ★", "success")
        }
    }

    private fun cmdHide(name: String) {
        val app = findApp(name) ?: run { printLine("not found: $name", "error"); return }
        hiddenApps.add(app.packageName); saveHidden(); loadApps()
        printLine("${app.label} hidden → vault", "success")
    }

    private fun cmdUnhide(name: String) {
        val app = allApps.find { it.label.equals(name, true) } ?: allApps.find { it.label.contains(name, true) }
            ?: run { printLine("not found: $name", "error"); return }
        hiddenApps.remove(app.packageName); saveHidden(); loadApps()
        printLine("${app.label} removed from vault.", "success")
    }

    private fun cmdVault() {
        val h = allApps.filter { hiddenApps.contains(it.packageName) }
        if (h.isEmpty()) { printLine("vault is empty.", "output"); return }
        printLine("vault [${h.size}]:", "warn")
        h.forEachIndexed { i, app -> printLine("${(i+1).toString().padStart(3)}  >_  ${app.label}", "app") }
        printLine("run <app> to open  |  unhide <app> to remove", "system")
    }

    private fun cmdAlias(rest: String) {
        val parts = rest.trim().split(" ", limit = 2)
        if (parts.size < 2) { printLine("usage: alias <key> <app>", "warn"); return }
        val app = findApp(parts[1].trim()) ?: run { printLine("app not found: ${parts[1]}", "error"); return }
        aliases[parts[0].lowercase()] = app.label; saveAliases()
        printLine("alias '${parts[0]}' → ${app.label}", "success")
    }

    private fun cmdUnalias(key: String) {
        if (!aliases.containsKey(key.lowercase())) { printLine("alias not found: $key", "error"); return }
        aliases.remove(key.lowercase()); saveAliases()
        printLine("alias '$key' removed.", "success")
    }

    private fun cmdAliasList() {
        if (aliases.isEmpty()) { printLine("no aliases.", "output"); return }
        printLine("aliases:", "info")
        aliases.forEach { (k, v) -> printLine("  $k  →  $v", "app") }
    }

    private fun cmdInfo(name: String) {
        val app = findApp(name) ?: run { printLine("not found: $name", "error"); return }
        try {
            val pi = packageManager.getPackageInfo(app.packageName, 0)
            val ai = packageManager.getApplicationInfo(app.packageName, 0)
            val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            val sizeMb = try { "%.1f MB".format(File(ai.sourceDir).length() / 1_048_576.0) } catch (_: Exception) { "?" }
            printLine("info: ${app.label}", "info")
            printLine("  package  : ${app.packageName}", "output")
            printLine("  version  : ${pi.versionName ?: "?"}", "output")
            printLine("  size     : $sizeMb", "output")
            printLine("  installed: ${sdf.format(Date(pi.firstInstallTime))}", "output")
            printLine("  updated  : ${sdf.format(Date(pi.lastUpdateTime))}", "output")
            if (pinnedApps.contains(app.packageName)) printLine("  pinned   : ★", "output")
            if (hiddenApps.contains(app.packageName)) printLine("  vault    : yes", "output")
        } catch (e: Exception) { printLine("error: ${e.message}", "error") }
    }

    private fun cmdRecent() {
        val pkgs = getRecents()
        if (pkgs.isEmpty()) { printLine("no recent apps.", "output"); return }
        printLine("recent:", "info")
        pkgs.forEachIndexed { i, pkg ->
            val label = allApps.find { it.packageName == pkg }?.label ?: pkg
            printLine("  ${i+1}.  >_  $label", "app")
        }
    }

    private fun cmdHistoryCmd() {
        if (cmdHistory.isEmpty()) { printLine("no history.", "output"); return }
        printLine("history:", "info")
        cmdHistory.take(20).forEachIndexed { i, cmd -> printLine("  ${(i+1).toString().padStart(3)}  $cmd", "output") }
    }

    // SECRET COMMAND — not in help
    private fun cmdPrint(args: String) {
        // parse: "text \N" or just "text"
        val countRegex = Regex("""\\(\d+)$""")
        val match = countRegex.find(args.trim())
        val count: Int
        val text: String
        if (match != null) {
            count = match.groupValues[1].toIntOrNull() ?: 1
            text  = args.substring(0, match.range.first).trim()
        } else {
            count = 1
            text  = args.trim()
        }
        if (text.isEmpty()) { printLine("usage: print <text> [\\N]", "warn"); return }

        // Hard cap: if count is huge, render as single TextView with \n to avoid OOM
        val MAX_VIEWS = 500
        if (count <= MAX_VIEWS) {
            repeat(count) { printLine(text, "app") }
        } else {
            // Render as one big TextView to avoid lag/crash
            val sb = StringBuilder(text.length * count + count)
            repeat(count) { sb.append(text).append('\n') }
            val tv = makeTV()
            tv.text = sb.toString().trimEnd()
            tv.setTextColor(colorForType("app"))
            termOutput.addView(tv)
        }
    }

    private fun cmdSettings() {
        printLine("opening settings...", "output"); scrollBottom()
        handler.postDelayed({ showSettingsOverlay() }, 150)
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private fun getRecents(): List<String> {
        val s = prefs.getString("recents", "") ?: ""
        return if (s.isEmpty()) emptyList() else s.split(",").filter { it.isNotBlank() }
    }

    private fun findApp(name: String): AppInfo? {
        val lower = name.lowercase()
        val aliasTarget = aliases[lower]
        if (aliasTarget != null)
            return allApps.find { it.label.equals(aliasTarget, true) } ?: allApps.find { it.label.contains(aliasTarget, true) }
        return allApps.find { it.label.equals(name, true) }
            ?: allApps.find { it.label.lowercase().startsWith(lower) }
            ?: allApps.find { it.label.lowercase().contains(lower) }
            ?: allApps.find { it.packageName.lowercase().contains(lower) }
    }

    // ── TERMINAL OUTPUT ───────────────────────────────────────────────────────

    private fun printLine(text: String, type: String = "output") {
        val tv = makeTV(); tv.text = text; tv.setTextColor(colorForType(type))
        if (type == "system") tv.alpha = 0.45f
        termOutput.addView(tv)
    }

    private fun printPrompt(cmd: String, ts: String) {
        val tv = makeTV()
        val full = "$ts ❯ $cmd"
        val ss = SpannableString(full)
        ss.setSpan(ForegroundColorSpan(dim(txtColor, 0.25f)), 0, ts.length, 0)
        ss.setSpan(ForegroundColorSpan(dim(txtColor, 0.35f)), ts.length + 1, ts.length + 3, 0)
        ss.setSpan(ForegroundColorSpan(txtColor), ts.length + 3, full.length, 0)
        tv.text = ss; termOutput.addView(tv)
    }

    private fun printBlank() {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (7 * resources.displayMetrics.density).toInt())
        termOutput.addView(v)
    }

    private fun makeTV() = TextView(this).apply {
        typeface = Typeface.MONOSPACE; textSize = 13f; setLineSpacing(2f, 1f)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        isFocusable = false; isFocusableInTouchMode = false
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

    private fun scrollBottom() { scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) } }

    // ── SETTINGS OVERLAY ──────────────────────────────────────────────────────

    private fun showSettingsOverlay() {
        val dp = resources.displayMetrics.density
        val dp8 = (8*dp).toInt(); val dp12 = (12*dp).toInt(); val dp16 = (16*dp).toInt()

        val overlay = FrameLayout(this).also { it.setBackgroundColor(Color.parseColor("#CC000000")) }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(darken(bgColor,12)); cornerRadius=10f*dp; setStroke((1*dp).toInt(), dim(txtColor,0.25f)) }
            setPadding(0, 0, 0, dp8)
        }

        card.addView(monoTV("launcher.setting", 11f, dim(txtColor,0.5f)).also { it.setPadding(dp16,dp12,dp16,dp8) })
        card.addView(makeDivider(dp))

        fun row(label: String, value: String, action: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp16,dp12,dp16,dp12)
                isClickable = true; isFocusable = true; setOnClickListener { action() }
            }
            r.addView(monoTV(label, 10f, dim(txtColor,0.35f)))
            r.addView(monoTV(value, 13f, txtColor).also { it.setPadding(0,(3*dp).toInt(),0,0) })
            card.addView(r); card.addView(makeDivider(dp))
        }

        row("change terminal background color", colorHex(bgColor)) {
            dismissOverlay(overlay)
            showColorPicker("bg", bgColor) { c ->
                bgColor = c; prefs.edit().putInt("bg", c).apply(); applyTheme()
                printLine("bg → ${colorHex(c)}", "success"); printBlank(); scrollBottom()
            }
        }
        row("change text color", colorHex(txtColor)) {
            dismissOverlay(overlay)
            showColorPicker("txt", txtColor) { c ->
                txtColor = c; prefs.edit().putInt("txt", c).apply(); applyTheme()
                printLine("text color → ${colorHex(c)}", "success"); printBlank(); scrollBottom()
            }
        }

        val wpLabel = if (wallpaperFile.exists()) "change wallpaper (tap to replace)" else "set wallpaper from gallery"
        row(wpLabel, if (wallpaperFile.exists()) "wallpaper active" else "none") {
            dismissOverlay(overlay)
            val intent = Intent(Intent.ACTION_PICK).also { it.type = "image/*" }
            startActivityForResult(intent, REQ_PICK_IMAGE)
        }

        if (wallpaperFile.exists()) {
            row("remove wallpaper", "restore solid background") {
                dismissOverlay(overlay); removeWallpaper()
            }

            // Wallpaper opacity slider row
            val opRow = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp16, dp12, dp16, dp12)
            }
            opRow.addView(monoTV("wallpaper opacity", 10f, dim(txtColor,0.35f)))
            val opSlider = SeekBar(this).apply {
                max = 100; progress = (wallpaperAlpha * 100).toInt()
                progressDrawable?.setColorFilter(txtColor, PorterDuff.Mode.SRC_IN)
                thumb?.setColorFilter(txtColor, PorterDuff.Mode.SRC_IN)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = (4*dp).toInt(); layoutParams = lp
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fu: Boolean) {
                        if (!fu) return
                        wallpaperAlpha = p / 100f
                        wallpaperView.alpha = wallpaperAlpha
                        prefs.edit().putFloat("wp_alpha", wallpaperAlpha).apply()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            opRow.addView(opSlider)
            card.addView(opRow)
            card.addView(makeDivider(dp))
        }

        card.addView(monoTV("close", 12f, dim(txtColor,0.4f)).apply {
            gravity = Gravity.CENTER; setPadding(dp16,dp12,dp16,dp8)
            setOnClickListener { dismissOverlay(overlay) }
        })

        val lp = FrameLayout.LayoutParams((300*dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER
        overlay.addView(card, lp)
        overlay.setOnClickListener { dismissOverlay(overlay) }
        (window.decorView as ViewGroup).addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        card.alpha = 0f; card.translationY = 24f*dp
        card.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
    }

    // ── COLOR PICKER ──────────────────────────────────────────────────────────

    private fun showColorPicker(key: String, current: Int, onApply: (Int) -> Unit) {
        val dp = resources.displayMetrics.density
        val dp4=(4*dp).toInt(); val dp8=(8*dp).toInt(); val dp12=(12*dp).toInt(); val dp16=(16*dp).toInt()
        val hsv = FloatArray(3); Color.colorToHSV(current, hsv)
        var picked = current; var updating = false

        val overlay = FrameLayout(this).also { it.setBackgroundColor(Color.parseColor("#CC000000")) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(darken(bgColor,12)); cornerRadius=10f*dp; setStroke((1*dp).toInt(), dim(txtColor,0.25f)) }
            setPadding(dp16, dp12, dp16, dp12)
        }

        card.addView(monoTV(if (key=="bg") "background color" else "text color", 11f, dim(txtColor,0.5f)).also {
            it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { lp -> lp.bottomMargin=dp8 }
        })

        val previewBg = GradientDrawable().apply { cornerRadius=6f*dp; setColor(picked) }
        card.addView(View(this).apply {
            background = previewBg
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (44*dp).toInt()).also { it.bottomMargin=dp8 }
        })

        val hexEt = EditText(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 13f
            setTextColor(txtColor); setHintTextColor(dim(txtColor,0.3f))
            hint = "#rrggbb"; setText(colorHex(picked))
            background = GradientDrawable().apply { setColor(darken(bgColor,18)); cornerRadius=4f*dp; setStroke((1*dp).toInt(), dim(txtColor,0.2f)) }
            setPadding(dp8,dp8,dp8,dp8)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin=dp4 }
        }
        card.addView(hexEt)

        fun mkSlider(lbl: String, init: Int, max: Int, cb: (Int) -> Unit): SeekBar {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin=dp4 }
            }
            row.addView(monoTV(lbl, 10f, dim(txtColor,0.4f)).also { it.layoutParams = LinearLayout.LayoutParams((34*dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT) })
            val sb = SeekBar(this).apply {
                this.max=max; progress=init
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

        val hSb = mkSlider("H", hsv[0].toInt(), 360) { p -> if(!updating){hsv[0]=p.toFloat();picked=Color.HSVToColor(hsv);previewBg.setColor(picked);updating=true;hexEt.setText(colorHex(picked));updating=false} }
        val sSb = mkSlider("S", (hsv[1]*100).toInt(), 100) { p -> if(!updating){hsv[1]=p/100f;picked=Color.HSVToColor(hsv);previewBg.setColor(picked);updating=true;hexEt.setText(colorHex(picked));updating=false} }
        val vSb = mkSlider("V", (hsv[2]*100).toInt(), 100) { p -> if(!updating){hsv[2]=p/100f;picked=Color.HSVToColor(hsv);previewBg.setColor(picked);updating=true;hexEt.setText(colorHex(picked));updating=false} }

        hexEt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                try {
                    val hex = s.toString().trim()
                    if (hex.matches(Regex("#[0-9a-fA-F]{6}"))) {
                        val c = Color.parseColor(hex); Color.colorToHSV(c, hsv)
                        updating=true; hSb.progress=hsv[0].toInt(); sSb.progress=(hsv[1]*100).toInt(); vSb.progress=(hsv[2]*100).toInt(); updating=false
                        picked=c; previewBg.setColor(picked)
                    }
                } catch (_: Exception) {}
            }
        })

        val presets = listOf("#00ff88","#00ffcc","#00ccff","#4488ff","#ff4455","#ffaa00","#ffffff","#aaaaaa","#ff6ec7","#7c3aed","#0a0a0a","#111111")
        val swRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presets.forEach { hex ->
            swRow.addView(View(this).apply {
                background = GradientDrawable().apply { setColor(Color.parseColor(hex)); cornerRadius=3f*dp }
                val sz=(28*dp).toInt()
                layoutParams = LinearLayout.LayoutParams(sz,sz).also { it.marginEnd=(4*dp).toInt() }
                setOnClickListener {
                    val c=Color.parseColor(hex); Color.colorToHSV(c,hsv)
                    updating=true; hSb.progress=hsv[0].toInt(); sSb.progress=(hsv[1]*100).toInt(); vSb.progress=(hsv[2]*100).toInt(); updating=false
                    picked=c; previewBg.setColor(picked); hexEt.setText(hex)
                }
            })
        }
        card.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=false; addView(swRow)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin=dp8 }
        })

        val btnRow = LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=Gravity.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin=dp8 }
        }
        btnRow.addView(monoTV("cancel", dim(txtColor,0.4f), 13f).apply { setPadding(dp16,dp8,dp8,dp8); setOnClickListener { dismissOverlay(overlay) } })
        btnRow.addView(monoTV("apply",  txtColor, 13f).apply { setPadding(dp16,dp8,dp8,dp8); setOnClickListener { dismissOverlay(overlay); onApply(picked) } })
        card.addView(btnRow)

        val lp = FrameLayout.LayoutParams((310*dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT); lp.gravity=Gravity.CENTER
        overlay.addView(card,lp); overlay.setOnClickListener {}
        (window.decorView as ViewGroup).addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        card.alpha=0f; card.scaleX=0.9f; card.scaleY=0.9f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
    }

    // ── UI HELPERS ────────────────────────────────────────────────────────────

    private fun monoTV(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text=text; typeface=Typeface.MONOSPACE; textSize=size; setTextColor(color)
        isFocusable=false; isFocusableInTouchMode=false
    }

    // overload with color+size swapped for btn convenience
    private fun monoTV(text: String, color: Int, size: Float) = monoTV(text, size, color)

    private fun dismissOverlay(overlay: View) {
        overlay.animate().alpha(0f).setDuration(120).withEndAction {
            (overlay.parent as? ViewGroup)?.removeView(overlay); etInput.requestFocus()
        }.start()
    }

    private fun makeDivider(dp: Float) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1*dp).toInt())
        setBackgroundColor(dim(txtColor, 0.12f))
    }

    private fun dim(color: Int, f: Float) = Color.argb((Color.alpha(color)*f).toInt().coerceIn(0,255), Color.red(color), Color.green(color), Color.blue(color))
    private fun darken(color: Int, amt: Int) = Color.rgb((Color.red(color)+amt).coerceIn(0,255),(Color.green(color)+amt).coerceIn(0,255),(Color.blue(color)+amt).coerceIn(0,255))
    private fun colorHex(color: Int) = String.format("#%06X", 0xFFFFFF and color)

    override fun onBackPressed() { /* swallow */ }
}
