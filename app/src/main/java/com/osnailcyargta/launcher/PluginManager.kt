package com.osnailcyargta.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.luaj.vm2.*
import org.luaj.vm2.lib.*
import org.luaj.vm2.lib.jse.JsePlatform
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

const val LAUNCHER_API_VERSION = 1

enum class HookEvent {
    ON_COMMAND, ON_KEYPRESS, ON_OPEN_SETTING, ON_PRINT,
    ON_ERROR, ON_BOOT, ON_APP_LAUNCH, ON_APP_INSTALL, ON_APP_UNINSTALL
}

data class PluginInfo(
    val name: String, val version: String, val apiVersion: Int,
    val author: String, val description: String, val dir: File,
    var enabled: Boolean = true, var errorMsg: String = ""
)
data class PluginCommand(val name: String, val pluginName: String, val handler: LuaFunction)
data class PluginSetting(
    val label: String, val pluginName: String,
    val defaultValue: String, var currentValue: String, val handler: LuaFunction
)
data class PluginHook(val event: HookEvent, val pluginName: String, val handler: LuaFunction)

class PluginManager(private val context: Context) {

    val plugins  = mutableListOf<PluginInfo>()
    val commands = mutableMapOf<String, PluginCommand>()
    val settings = mutableListOf<PluginSetting>()
    val variables = mutableMapOf<String, String>()

    private val hooks = mutableMapOf<HookEvent, MutableList<PluginHook>>()
    private val pluginsDir = File(context.filesDir, "plugins")
    private val mainHandler = Handler(Looper.getMainLooper())

    var terminalCallback:  ((String, String) -> Unit)? = null
    var colorCallback:     ((String, String) -> Unit)? = null
    var showImageCallback: ((File) -> Unit)? = null
    var hideImageCallback: (() -> Unit)? = null
    var showVideoCallback: ((File) -> Unit)? = null
    var hideVideoCallback: (() -> Unit)? = null

    // Per-plugin SoundPool instances (keyed by pluginName)
    private val soundPools   = mutableMapOf<String, SoundPool>()
    private val soundIds     = mutableMapOf<String, Int>()
    private val soundLoaded  = mutableMapOf<String, Boolean>()

    // Per-plugin typewriter pending runnables
    private val typewriterJobs = mutableMapOf<String, MutableList<Runnable>>()

    fun init() {
        pluginsDir.mkdirs()
        HookEvent.values().forEach { hooks[it] = mutableListOf() }
        loadAllPlugins()
    }

    // ── HOOK SYSTEM ───────────────────────────────────────────────────────────

    fun fireHook(event: HookEvent, arg: String = "") {
        hooks[event]?.forEach { hook ->
            try { hook.handler.call(LuaValue.valueOf(arg)) }
            catch (e: Exception) { Log.e("Plugin", "hook [${hook.pluginName}] ${event.name}: ${e.message}") }
        }
    }

    // ── INSTALL ───────────────────────────────────────────────────────────────

    fun installFromZip(zipFile: File): Pair<Boolean, String> {
        return try {
            var manifestJson: String? = null
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "manifest.json") { manifestJson = zis.readBytes().toString(Charsets.UTF_8); break }
                    entry = zis.nextEntry
                }
            }
            if (manifestJson == null) return Pair(false, "manifest.json not found")
            val manifest = JSONObject(manifestJson!!)
            val name   = manifest.optString("name","").trim()
            val apiVer = manifest.optInt("api_version", -1)
            if (name.isEmpty()) return Pair(false, "plugin name empty")
            if (apiVer == -1)   return Pair(false, "api_version missing")
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
        } catch (e: Exception) { Pair(false, "install error: ${e.message}") }
    }

    // ── LOAD ──────────────────────────────────────────────────────────────────

    fun loadAllPlugins() {
        plugins.clear(); commands.clear(); settings.clear()
        HookEvent.values().forEach { hooks[it]?.clear() }
        pluginsDir.listFiles { f -> f.isDirectory }?.forEach { loadPlugin(it) }
    }

    private fun loadPlugin(dir: File) {
        val mf = File(dir, "manifest.json")
        if (!mf.exists()) return
        val manifest = try { JSONObject(mf.readText()) } catch (e: Exception) {
            plugins.add(PluginInfo("?","?", -1,"?","?", dir, false, "bad manifest: ${e.message}")); return
        }
        val name   = manifest.optString("name", dir.name)
        val ver    = manifest.optString("version", "?")
        val apiVer = manifest.optInt("api_version", -1)
        val author = manifest.optString("author", "unknown")
        val desc   = manifest.optString("description", "")

        if (apiVer != LAUNCHER_API_VERSION) {
            plugins.add(PluginInfo(name, ver, apiVer, author, desc, dir, false,
                "API mismatch: plugin=$apiVer launcher=$LAUNCHER_API_VERSION")); return
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

        // Launcher.registerCommand("name", fn)
        api.set("registerCommand", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                if (b is LuaFunction) commands[a.tojstring().lowercase().trim()] = PluginCommand(a.tojstring(), pluginName, b)
                return LuaValue.NIL
            }
        })

        // Launcher.registerSetting("label", "default", fn)
        api.set("registerSetting", object : ThreeArgFunction() {
            override fun call(a: LuaValue, b: LuaValue, c: LuaValue): LuaValue {
                if (c is LuaFunction) {
                    val lbl = a.tojstring(); val def = b.tojstring()
                    val prefs = context.getSharedPreferences("plugin_$pluginName", Context.MODE_PRIVATE)
                    val cur = prefs.getString("setting_$lbl", def) ?: def
                    settings.add(PluginSetting(lbl, pluginName, def, cur, c))
                }
                return LuaValue.NIL
            }
        })

        // Launcher.on("event", fn)
        api.set("on", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                if (b !is LuaFunction) return LuaValue.NIL
                val event = when (a.tojstring().lowercase()) {
                    "on_command"       -> HookEvent.ON_COMMAND
                    "on_keypress"      -> HookEvent.ON_KEYPRESS
                    "on_open_setting"  -> HookEvent.ON_OPEN_SETTING
                    "on_print"         -> HookEvent.ON_PRINT
                    "on_error"         -> HookEvent.ON_ERROR
                    "on_boot"          -> HookEvent.ON_BOOT
                    "on_app_launch"    -> HookEvent.ON_APP_LAUNCH
                    "on_app_install"   -> HookEvent.ON_APP_INSTALL
                    "on_app_uninstall" -> HookEvent.ON_APP_UNINSTALL
                    else -> return LuaValue.NIL
                }
                hooks[event]?.add(PluginHook(event, pluginName, b))
                return LuaValue.NIL
            }
        })

        // Launcher.print("text", "type")
        api.set("print", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                mainHandler.post { terminalCallback?.invoke(a.tojstring(), b.tojstring()) }
                return LuaValue.NIL
            }
        })

        // Launcher.setVar / getVar
        api.set("setVar", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                variables["$pluginName.${a.tojstring()}"] = b.tojstring(); return LuaValue.NIL
            }
        })
        api.set("getVar", object : OneArgFunction() {
            override fun call(a: LuaValue): LuaValue =
                LuaValue.valueOf(variables["$pluginName.${a.tojstring()}"] ?: "")
        })

        // Launcher.setColor("element", "#hex")
        api.set("setColor", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                mainHandler.post { colorCallback?.invoke(a.tojstring(), b.tojstring()) }
                return LuaValue.NIL
            }
        })

        // ── GENERAL HTTP ──────────────────────────────────────────────────────
        // Launcher.http(url, method, headersTable, body) -> {code, body}
        // Called from background thread in Lua; blocks until response
        api.set("http", object : LuaFunction() {
            override fun call(a: LuaValue, b: LuaValue, c: LuaValue, d: LuaValue): LuaValue {
                return try {
                    val urlStr  = a.tojstring()
                    val method  = b.tojstring().uppercase()
                    val headers = c  // LuaTable or NIL
                    val body    = if (d.isnil()) null else d.tojstring()

                    val url  = URL(urlStr)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod  = method
                    conn.connectTimeout = 15000
                    conn.readTimeout    = 30000

                    // Apply headers from Lua table
                    if (headers is LuaTable) {
                        var k = headers.next(LuaValue.NIL)
                        while (!k.arg1().isnil()) {
                            conn.setRequestProperty(k.arg1().tojstring(), k.arg(2).tojstring())
                            k = headers.next(k.arg1())
                        }
                    }

                    if (body != null) {
                        conn.doOutput = true
                        conn.outputStream.use { it.write(body.toByteArray()) }
                    }

                    val code       = conn.responseCode
                    val respStream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val respBody   = respStream?.bufferedReader()?.readText() ?: ""
                    conn.disconnect()

                    // Return {code=N, body="..."}
                    val result = LuaTable()
                    result.set("code", LuaValue.valueOf(code))
                    result.set("body", LuaValue.valueOf(respBody))
                    result
                } catch (e: Exception) {
                    val result = LuaTable()
                    result.set("code", LuaValue.valueOf(-1))
                    result.set("body", LuaValue.valueOf(e.message ?: "error"))
                    result
                }
            }
            // 4-arg variant
            override fun invoke(args: Varargs): Varargs = call(
                args.arg(1), args.arg(2), args.arg(3), args.arg(4)
            )
        })

        // ── SOUND ─────────────────────────────────────────────────────────────
        // Launcher.loadSound("assets/file.ogg")  -> soundHandle (string key)
        api.set("loadSound", object : OneArgFunction() {
            override fun call(a: LuaValue): LuaValue {
                return try {
                    val file = File(pluginDir, a.tojstring())
                    if (!file.exists()) return LuaValue.valueOf("error:file_not_found")
                    val key = "$pluginName::${a.tojstring()}"
                    if (soundIds.containsKey(key)) return LuaValue.valueOf(key) // already loaded

                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    val sp = SoundPool.Builder().setMaxStreams(20).setAudioAttributes(attrs).build()
                    soundLoaded[key] = false
                    sp.setOnLoadCompleteListener { _, _, status -> soundLoaded[key] = status == 0 }
                    val sid = sp.load(file.absolutePath, 1)
                    soundPools[key] = sp
                    soundIds[key]   = sid
                    LuaValue.valueOf(key)
                } catch (e: Exception) { LuaValue.valueOf("error:${e.message}") }
            }
        })

        // Launcher.playSound(soundHandle)  -> plays the sound (stackable)
        api.set("playSound", object : OneArgFunction() {
            override fun call(a: LuaValue): LuaValue {
                val key = a.tojstring()
                val sp  = soundPools[key] ?: return LuaValue.NIL
                val sid = soundIds[key]   ?: return LuaValue.NIL
                if (soundLoaded[key] == true) sp.play(sid, 1f, 1f, 1, 0, 1f)
                return LuaValue.NIL
            }
        })

        // Launcher.stopSounds(soundHandle)  -> stop all instances of this sound
        api.set("stopSounds", object : OneArgFunction() {
            override fun call(a: LuaValue): LuaValue {
                val key = a.tojstring()
                soundPools[key]?.autoPause()
                soundPools[key]?.autoResume()
                return LuaValue.NIL
            }
        })

        // ── IMAGE & VIDEO ─────────────────────────────────────────────────────────

        // Launcher.showImage("assets/asep.png")
        api.set("showImage", object : OneArgFunction() {
            override fun call(a: LuaValue): LuaValue {
                val file = File(pluginDir, a.tojstring())
                if (file.exists()) mainHandler.post { showImageCallback?.invoke(file) }
                else mainHandler.post { terminalCallback?.invoke("showImage: file not found: ${a.tojstring()}", "error") }
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

        // Launcher.showVideo("assets/video.mp4")
        api.set("showVideo", object : OneArgFunction() {
            override fun call(a: LuaValue): LuaValue {
                val file = File(pluginDir, a.tojstring())
                if (file.exists()) mainHandler.post { showVideoCallback?.invoke(file) }
                else mainHandler.post { terminalCallback?.invoke("showVideo: file not found: ${a.tojstring()}", "error") }
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

        // ── TYPEWRITER ────────────────────────────────────────────────────────
        // Launcher.typewrite(text, delayMs, onChar_fn, onWord_fn, onDone_fn)
        // onChar_fn(char) called per character on main thread
        // onWord_fn(word) called per word on main thread
        // onDone_fn() called when done
        api.set("typewrite", object : LuaFunction() {
            override fun invoke(args: Varargs): Varargs {
                val text        = args.arg(1).tojstring()
                val delayMs     = args.arg(2).optlong(50)
                val onCharFn    = args.arg(3).takeIf { it is LuaFunction } as? LuaFunction
                val onWordFn    = args.arg(4).takeIf { it is LuaFunction } as? LuaFunction
                val onDoneFn    = args.arg(5).takeIf { it is LuaFunction } as? LuaFunction

                // Cancel previous typewriter for this plugin
                typewriterJobs[pluginName]?.forEach { mainHandler.removeCallbacks(it) }
                typewriterJobs[pluginName] = mutableListOf()

                var wordBuf = StringBuilder()
                var lineBuf = StringBuilder()

                for ((i, ch) in text.withIndex()) {
                    val delay = i * delayMs
                    val r = Runnable {
                        lineBuf.append(ch)
                        wordBuf.append(ch)

                        // Fire onChar
                        try { onCharFn?.call(LuaValue.valueOf(ch.toString())) } catch (_: Exception) {}

                        // Fire onWord on space/newline/end
                        val isEnd = i == text.length - 1
                        if (ch == ' ' || ch == '\n' || isEnd) {
                            val word = wordBuf.toString().trim()
                            if (word.isNotEmpty()) {
                                try { onWordFn?.call(LuaValue.valueOf(word)) } catch (_: Exception) {}
                            }
                            wordBuf.clear()
                        }

                        // Fire onDone at end
                        if (isEnd) {
                            try { onDoneFn?.call() } catch (_: Exception) {}
                        }
                    }
                    typewriterJobs[pluginName]?.add(r)
                    mainHandler.postDelayed(r, delay)
                }
                return LuaValue.NIL
            }
        })

        // Launcher.cancelTypewrite() -> cancel pending typewriter for this plugin
        api.set("cancelTypewrite", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                typewriterJobs[pluginName]?.forEach { mainHandler.removeCallbacks(it) }
                typewriterJobs[pluginName]?.clear()
                return LuaValue.NIL
            }
        })

        // Launcher.apiVersion()
        api.set("apiVersion", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(LAUNCHER_API_VERSION)
        })

        globals.set("Launcher", api)
    }

    // ── EXECUTE COMMAND ───────────────────────────────────────────────────────

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

    fun release() {
        soundPools.values.forEach { it.release() }
        soundPools.clear(); soundIds.clear(); soundLoaded.clear()
        typewriterJobs.values.forEach { list -> list.forEach { mainHandler.removeCallbacks(it) } }
        typewriterJobs.clear()
    }
}
