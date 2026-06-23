package com.osnailcyargta.launcher

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import org.json.JSONObject
import org.luaj.vm2.*
import org.luaj.vm2.lib.*
import org.luaj.vm2.lib.jse.JsePlatform
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

const val LAUNCHER_API_VERSION = 1

// All supported hook events
enum class HookEvent {
    ON_COMMAND,
    ON_KEYPRESS,
    ON_OPEN_SETTING,
    ON_PRINT,
    ON_ERROR,
    ON_BOOT,
    ON_APP_LAUNCH,
    ON_APP_INSTALL,
    ON_APP_UNINSTALL
}

data class PluginInfo(
    val name: String,
    val version: String,
    val apiVersion: Int,
    val author: String,
    val description: String,
    val dir: File,
    var enabled: Boolean = true,
    var errorMsg: String = ""
)

data class PluginCommand(
    val name: String,
    val pluginName: String,
    val handler: LuaFunction
)

data class PluginSetting(
    val label: String,
    val pluginName: String,
    val defaultValue: String,
    var currentValue: String,
    val handler: LuaFunction
)

data class PluginHook(
    val event: HookEvent,
    val pluginName: String,
    val handler: LuaFunction
)

class PluginManager(private val context: Context) {

    val plugins  = mutableListOf<PluginInfo>()
    val commands = mutableMapOf<String, PluginCommand>()
    val settings = mutableListOf<PluginSetting>()

    // event -> list of hooks
    private val hooks = mutableMapOf<HookEvent, MutableList<PluginHook>>()

    val variables = mutableMapOf<String, String>()

    private val pluginsDir = File(context.filesDir, "plugins")
    private var terminalCallback: ((String, String) -> Unit)? = null
    private var colorCallback: ((String, String) -> Unit)? = null
    private var mediaPlayer: MediaPlayer? = null

    fun setTerminalCallback(cb: (text: String, type: String) -> Unit) { terminalCallback = cb }
    fun setColorCallback(cb: (element: String, hex: String) -> Unit)  { colorCallback = cb }

    fun init() {
        pluginsDir.mkdirs()
        HookEvent.values().forEach { hooks[it] = mutableListOf() }
        loadAllPlugins()
    }

    // ── FIRE HOOK ─────────────────────────────────────────────────────────────

    fun fireHook(event: HookEvent, arg: String = "") {
        val list = hooks[event] ?: return
        for (hook in list) {
            try {
                hook.handler.call(LuaValue.valueOf(arg))
            } catch (e: Exception) {
                Log.e("PluginManager", "hook error [${hook.pluginName}] ${event.name}: ${e.message}")
            }
        }
    }

    // ── INSTALL ───────────────────────────────────────────────────────────────

    fun installFromZip(zipFile: File): Pair<Boolean, String> {
        return try {
            var manifestJson: String? = null
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "manifest.json") {
                        manifestJson = zis.readBytes().toString(Charsets.UTF_8); break
                    }
                    entry = zis.nextEntry
                }
            }
            if (manifestJson == null) return Pair(false, "manifest.json not found")

            val manifest = JSONObject(manifestJson!!)
            val name   = manifest.optString("name", "").trim()
            val apiVer = manifest.optInt("api_version", -1)

            if (name.isEmpty()) return Pair(false, "plugin name empty in manifest")
            if (apiVer == -1)   return Pair(false, "api_version missing in manifest")
            if (apiVer != LAUNCHER_API_VERSION)
                return Pair(false, "API mismatch: plugin=$apiVer launcher=$LAUNCHER_API_VERSION")

            val pluginDir = File(pluginsDir, name)
            pluginDir.mkdirs()

            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(pluginDir, entry.name)
                    if (entry.isDirectory) out.mkdirs()
                    else { out.parentFile?.mkdirs(); out.outputStream().use { o -> zis.copyTo(o) } }
                    entry = zis.nextEntry
                }
            }
            loadAllPlugins()
            Pair(true, "plugin '$name' installed.")
        } catch (e: Exception) {
            Pair(false, "install error: ${e.message}")
        }
    }

    // ── LOAD ──────────────────────────────────────────────────────────────────

    fun loadAllPlugins() {
        plugins.clear()
        commands.clear()
        settings.clear()
        HookEvent.values().forEach { hooks[it]?.clear() }

        val dirs = pluginsDir.listFiles { f -> f.isDirectory } ?: return
        for (dir in dirs) loadPlugin(dir)
    }

    private fun loadPlugin(dir: File) {
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.exists()) return

        val manifest = try { JSONObject(manifestFile.readText()) } catch (e: Exception) {
            plugins.add(PluginInfo("?","?", -1,"?","?", dir, false, "bad manifest: ${e.message}")); return
        }

        val name   = manifest.optString("name", dir.name)
        val ver    = manifest.optString("version", "?")
        val apiVer = manifest.optInt("api_version", -1)
        val author = manifest.optString("author", "unknown")
        val desc   = manifest.optString("description", "")

        if (apiVer != LAUNCHER_API_VERSION) {
            plugins.add(PluginInfo(name, ver, apiVer, author, desc, dir, false,
                "API mismatch: plugin=$apiVer launcher=$LAUNCHER_API_VERSION"))
            return
        }

        val luaFile = File(dir, "main.lua")
        if (!luaFile.exists()) {
            plugins.add(PluginInfo(name, ver, apiVer, author, desc, dir, false, "main.lua not found")); return
        }

        try {
            val globals = JsePlatform.standardGlobals()
            injectAPI(globals, name, dir)
            globals.load(luaFile.readText()).call()
            plugins.add(PluginInfo(name, ver, apiVer, author, desc, dir, true))
        } catch (e: Exception) {
            plugins.add(PluginInfo(name, ver, apiVer, author, desc, dir, false, "lua error: ${e.message}"))
        }
    }

    // ── LUA API ───────────────────────────────────────────────────────────────

    private fun injectAPI(globals: Globals, pluginName: String, pluginDir: File) {
        val api = LuaTable()

        // Launcher.registerCommand("name", function(args) end)
        api.set("registerCommand", object : TwoArgFunction() {
            override fun call(cmdName: LuaValue, handler: LuaValue): LuaValue {
                val name = cmdName.tojstring().lowercase().trim()
                if (handler is LuaFunction) commands[name] = PluginCommand(name, pluginName, handler)
                return LuaValue.NIL
            }
        })

        // Launcher.registerSetting("label", "default", function(newVal) end)
        api.set("registerSetting", object : ThreeArgFunction() {
            override fun call(label: LuaValue, default_: LuaValue, handler: LuaValue): LuaValue {
                if (handler is LuaFunction) {
                    val lbl = label.tojstring()
                    val def = default_.tojstring()
                    val prefs = context.getSharedPreferences("plugin_$pluginName", Context.MODE_PRIVATE)
                    val cur = prefs.getString("setting_$lbl", def) ?: def
                    settings.add(PluginSetting(lbl, pluginName, def, cur, handler))
                }
                return LuaValue.NIL
            }
        })

        // Launcher.on("event_name", function(arg) end)
        api.set("on", object : TwoArgFunction() {
            override fun call(eventName: LuaValue, handler: LuaValue): LuaValue {
                if (handler !is LuaFunction) return LuaValue.NIL
                val event = when (eventName.tojstring().lowercase().trim()) {
                    "on_command"       -> HookEvent.ON_COMMAND
                    "on_keypress"      -> HookEvent.ON_KEYPRESS
                    "on_open_setting"  -> HookEvent.ON_OPEN_SETTING
                    "on_print"         -> HookEvent.ON_PRINT
                    "on_error"         -> HookEvent.ON_ERROR
                    "on_boot"          -> HookEvent.ON_BOOT
                    "on_app_launch"    -> HookEvent.ON_APP_LAUNCH
                    "on_app_install"   -> HookEvent.ON_APP_INSTALL
                    "on_app_uninstall" -> HookEvent.ON_APP_UNINSTALL
                    else -> { Log.w("Plugin", "unknown event: ${eventName.tojstring()}"); return LuaValue.NIL }
                }
                hooks[event]?.add(PluginHook(event, pluginName, handler))
                return LuaValue.NIL
            }
        })

        // Launcher.print("text", "type")
        api.set("print", object : TwoArgFunction() {
            override fun call(text: LuaValue, type: LuaValue): LuaValue {
                terminalCallback?.invoke(text.tojstring(), type.tojstring())
                return LuaValue.NIL
            }
        })

        // Launcher.setVar / getVar
        api.set("setVar", object : TwoArgFunction() {
            override fun call(key: LuaValue, value: LuaValue): LuaValue {
                variables["$pluginName.${key.tojstring()}"] = value.tojstring(); return LuaValue.NIL
            }
        })
        api.set("getVar", object : OneArgFunction() {
            override fun call(key: LuaValue): LuaValue =
                LuaValue.valueOf(variables["$pluginName.${key.tojstring()}"] ?: "")
        })

        // Launcher.playSound("assets/ding.mp3")
        api.set("playSound", object : OneArgFunction() {
            override fun call(path: LuaValue): LuaValue {
                try {
                    val f = File(pluginDir, path.tojstring())
                    if (f.exists()) {
                        mediaPlayer?.release()
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(f.absolutePath); prepare(); start()
                        }
                    } else Log.w("Plugin", "sound file not found: ${f.absolutePath}")
                } catch (e: Exception) { Log.e("Plugin", "playSound: ${e.message}") }
                return LuaValue.NIL
            }
        })

        // Launcher.setColor("error", "#ff0000")
        api.set("setColor", object : TwoArgFunction() {
            override fun call(element: LuaValue, hex: LuaValue): LuaValue {
                colorCallback?.invoke(element.tojstring(), hex.tojstring()); return LuaValue.NIL
            }
        })

        // Launcher.apiVersion()
        api.set("apiVersion", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(LAUNCHER_API_VERSION)
        })


        // ── TIMER / DELAY ─────────────────────────────────────────────────────
        // Launcher.delay(ms, fn) — run fn after ms milliseconds on main thread
        api.set("delay", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                val ms = a.tolong()
                if (b is LuaFunction) {
                    val fn = b
                    mainHandler.postDelayed({ try { fn.call() } catch (_: Exception) {} }, ms)
                }
                return LuaValue.NIL
            }
        })

        // ── IMAGE OVERLAY ─────────────────────────────────────────────────────
        // Launcher.showImage("assets/img.png", autoHideMs?)
        // autoHideMs = 0 means manual hide only
        api.set("showImage", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                val file = File(pluginDir, a.tojstring())
                val autoHide = if (b.isnil()) 0L else b.tolong()
                if (file.exists()) mainHandler.post { showImageCallback?.invoke(file, autoHide) }
                else mainHandler.post { terminalCallback?.invoke("showImage: not found: ${a.tojstring()}", "error") }
                return LuaValue.NIL
            }
        })

        // Launcher.hideImage()
        api.set("hideImage", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                mainHandler.post { hideImageCallback?.invoke() }
                return LuaValue.NIL
            }
        })

        // ── VIDEO OVERLAY ─────────────────────────────────────────────────────
        // Launcher.showVideo("assets/vid.mp4", loop?)
        api.set("showVideo", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                val file = File(pluginDir, a.tojstring())
                val loop = !b.isnil() && b.toboolean()
                if (file.exists()) mainHandler.post { showVideoCallback?.invoke(file, loop) }
                else mainHandler.post { terminalCallback?.invoke("showVideo: not found: ${a.tojstring()}", "error") }
                return LuaValue.NIL
            }
        })

        // Launcher.hideVideo()
        api.set("hideVideo", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                mainHandler.post { hideVideoCallback?.invoke() }
                return LuaValue.NIL
            }
        })

        // ── HTTP ──────────────────────────────────────────────────────────────
        // Launcher.http(url, method, headersTable, body) -> {code, body}
        api.set("http", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                return try {
                    val urlStr  = args.arg(1).tojstring()
                    val method  = args.arg(2).optjstring("GET").uppercase()
                    val headers = args.arg(3)
                    val bodyArg = args.arg(4)
                    val body    = if (bodyArg.isnil()) null else bodyArg.tojstring()

                    val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).also {
                        it.requestMethod  = method
                        it.connectTimeout = 15000
                        it.readTimeout    = 30000
                    }
                    if (headers is LuaTable) {
                        var k = headers.next(LuaValue.NIL)
                        while (!k.arg1().isnil()) {
                            conn.setRequestProperty(k.arg1().tojstring(), k.arg(2).tojstring())
                            k = headers.next(k.arg1())
                        }
                    }
                    if (body != null) { conn.doOutput = true; conn.outputStream.use { it.write(body.toByteArray()) } }
                    val code = conn.responseCode
                    val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
                    conn.disconnect()
                    val result = LuaTable()
                    result.set("code", LuaValue.valueOf(code))
                    result.set("body", LuaValue.valueOf(resp))
                    result
                } catch (e: Exception) {
                    val result = LuaTable()
                    result.set("code", LuaValue.valueOf(-1))
                    result.set("body", LuaValue.valueOf(e.message ?: "error"))
                    result
                }
            }
        })

        // ── TOAST ─────────────────────────────────────────────────────────────
        // Launcher.toast("message")
        api.set("toast", object : OneArgFunction() {
            override fun call(a: LuaValue): LuaValue {
                val msg = a.tojstring()
                mainHandler.post { android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show() }
                return LuaValue.NIL
            }
        })

        // ── VIBRATE ───────────────────────────────────────────────────────────
        // Launcher.vibrate(ms)
        api.set("vibrate", object : OneArgFunction() {
            override fun call(a: LuaValue): LuaValue {
                val ms = a.tolong()
                try {
                    val vib = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        vib.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION") vib.vibrate(ms)
                    }
                } catch (_: Exception) {}
                return LuaValue.NIL
            }
        })

        // ── TYPEWRITER ────────────────────────────────────────────────────────
        // Launcher.typewrite(text, delayMs, onChar, onWord, onDone)
        api.set("typewrite", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val text     = args.arg(1).tojstring()
                val delayMs  = args.arg(2).optlong(50)
                val onCharFn = args.arg(3).takeIf { it is LuaFunction } as? LuaFunction
                val onWordFn = args.arg(4).takeIf { it is LuaFunction } as? LuaFunction
                val onDoneFn = args.arg(5).takeIf { it is LuaFunction } as? LuaFunction

                typewriterJobs[pluginName]?.forEach { mainHandler.removeCallbacks(it) }
                typewriterJobs[pluginName] = mutableListOf()

                var wordBuf = StringBuilder()
                for ((i, ch) in text.withIndex()) {
                    val r = Runnable {
                        wordBuf.append(ch)
                        try { onCharFn?.call(LuaValue.valueOf(ch.toString())) } catch (_: Exception) {}
                        val isEnd = i == text.length - 1
                        if (ch == ' ' || ch == '
' || isEnd) {
                            val word = wordBuf.toString().trim()
                            if (word.isNotEmpty()) try { onWordFn?.call(LuaValue.valueOf(word)) } catch (_: Exception) {}
                            wordBuf.clear()
                        }
                        if (isEnd) try { onDoneFn?.call() } catch (_: Exception) {}
                    }
                    typewriterJobs[pluginName]?.add(r)
                    mainHandler.postDelayed(r, i * delayMs)
                }
                return LuaValue.NIL
            }
        })

        // Launcher.cancelTypewrite()
        api.set("cancelTypewrite", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                typewriterJobs[pluginName]?.forEach { mainHandler.removeCallbacks(it) }
                typewriterJobs[pluginName]?.clear()
                return LuaValue.NIL
            }
        })

        // ── PREFS (plugin-scoped persistent storage) ──────────────────────────
        // Launcher.setPref("key", "value")
        api.set("setPref", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                context.getSharedPreferences("plugin_$pluginName", android.content.Context.MODE_PRIVATE)
                    .edit().putString(a.tojstring(), b.tojstring()).apply()
                return LuaValue.NIL
            }
        })

        // Launcher.getPref("key", "default") -> string
        api.set("getPref", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                val def = if (b.isnil()) "" else b.tojstring()
                val v = context.getSharedPreferences("plugin_$pluginName", android.content.Context.MODE_PRIVATE)
                    .getString(a.tojstring(), def) ?: def
                return LuaValue.valueOf(v)
            }
        })

        // ── RANDOM ────────────────────────────────────────────────────────────
        // Launcher.random(min, max) -> int
        api.set("random", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                val min = a.toint(); val max = b.toint()
                return LuaValue.valueOf((min..max).random())
            }
        })

        // ── API VERSION ───────────────────────────────────────────────────────
        api.set("apiVersion", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(LAUNCHER_API_VERSION)
        })

        globals.set("Launcher", api)
    }

    // ── EXECUTE PLUGIN COMMAND ────────────────────────────────────────────────

    fun executeCommand(cmdName: String, args: List<String>): Boolean {
        val cmd = commands[cmdName.lowercase()] ?: return false
        try {
            val luaArgs = LuaTable()
            args.forEachIndexed { i, a -> luaArgs.set(i+1, LuaValue.valueOf(a)) }
            cmd.handler.call(luaArgs)
        } catch (e: Exception) {
            terminalCallback?.invoke("plugin error [${cmd.pluginName}]: ${e.message}", "error")
        }
        return true
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    fun deletePlugin(name: String): Pair<Boolean, String> {
        val plugin = plugins.find { it.name.equals(name, ignoreCase = true) }
            ?: return Pair(false, "plugin not found: $name")
        plugin.dir.deleteRecursively()
        loadAllPlugins()
        return Pair(true, "plugin '${plugin.name}' deleted.")
    }

    fun release() { mediaPlayer?.release(); mediaPlayer = null }
}


// ── EXTENSION: RICH PLUGIN API ────────────────────────────────────────────────
// Injected separately so base PluginManager stays clean
