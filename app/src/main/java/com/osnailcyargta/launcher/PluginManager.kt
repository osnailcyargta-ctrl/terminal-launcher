package com.osnailcyargta.launcher

import android.content.Context
import android.media.AudioAttributes
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

const val LAUNCHER_API_VERSION = 2

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

    private val hooks          = mutableMapOf<HookEvent, MutableList<PluginHook>>()
    private val pluginsDir     = File(context.filesDir, "plugins")
    private val mainHandler    = Handler(Looper.getMainLooper())
    private val soundPools     = mutableMapOf<String, SoundPool>()
    private val soundIds       = mutableMapOf<String, Int>()
    private val soundLoaded    = mutableMapOf<String, Boolean>()
    private val typewriterJobs = mutableMapOf<String, MutableList<Runnable>>()

    // Callbacks set by MainActivity
    var terminalCallback:  ((String, String) -> Unit)? = null
    var colorCallback:     ((String, String) -> Unit)? = null
    var showImageCallback: ((File, Long) -> Unit)?     = null
    var hideImageCallback: (() -> Unit)?               = null
    var showVideoCallback: ((File, Boolean) -> Unit)?  = null
    var hideVideoCallback: (() -> Unit)?               = null

    fun init() {
        pluginsDir.mkdirs()
        HookEvent.values().forEach { hooks[it] = mutableListOf() }
        loadAllPlugins()
    }

    // ── HOOKS ─────────────────────────────────────────────────────────────────

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
            val name   = manifest.optString("name", "").trim()
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

        // registerCommand
        api.set("registerCommand", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                if (b is LuaFunction) commands[a.tojstring().lowercase().trim()] = PluginCommand(a.tojstring(), pluginName, b)
                return LuaValue.NIL
            }
        })

        // registerSetting
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

        // on(event, fn)
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

        // print(text, type)
        api.set("print", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                mainHandler.post { terminalCallback?.invoke(a.tojstring(), b.tojstring()) }
                return LuaValue.NIL
            }
        })

        // setVar / getVar
        api.set("setVar", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                context.getSharedPreferences("pluginvars_$pluginName", Context.MODE_PRIVATE)
                    .edit().putString(a.tojstring(), b.tojstring()).apply()
                return LuaValue.NIL
            }
        })
        api.set("getVar", object : OneArgFunction() {
            override fun call(a: LuaValue): LuaValue {
                val v = context.getSharedPreferences("pluginvars_$pluginName", Context.MODE_PRIVATE)
                    .getString(a.tojstring(), "") ?: ""
                return LuaValue.valueOf(v)
            }
        })

        // setPref / getPref (alias, same as setVar/getVar but named clearly)
        api.set("setPref", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                context.getSharedPreferences("pluginpref_$pluginName", Context.MODE_PRIVATE)
                    .edit().putString(a.tojstring(), b.tojstring()).apply()
                return LuaValue.NIL
            }
        })
        api.set("getPref", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                val def = if (b.isnil()) "" else b.tojstring()
                val v = context.getSharedPreferences("pluginpref_$pluginName", Context.MODE_PRIVATE)
                    .getString(a.tojstring(), def) ?: def
                return LuaValue.valueOf(v)
            }
        })

        // setColor(element, hex)
        api.set("setColor", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                mainHandler.post { colorCallback?.invoke(a.tojstring(), b.tojstring()) }
                return LuaValue.NIL
            }
        })

        // showImage(file, autoHideMs?)
        api.set("showImage", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                val file = File(pluginDir, a.tojstring())
                val ms = if (b.isnil()) 0L else b.tolong()
                if (file.exists()) mainHandler.post { showImageCallback?.invoke(file, ms) }
                else mainHandler.post { terminalCallback?.invoke("showImage: not found: ${a.tojstring()}", "error") }
                return LuaValue.NIL
            }
        })

        // hideImage()
        api.set("hideImage", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                mainHandler.post { hideImageCallback?.invoke() }
                return LuaValue.NIL
            }
        })

        // showVideo(file, loop?)
        api.set("showVideo", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                val file = File(pluginDir, a.tojstring())
                val loop = !b.isnil() && b.toboolean()
                if (file.exists()) mainHandler.post { showVideoCallback?.invoke(file, loop) }
                else mainHandler.post { terminalCallback?.invoke("showVideo: not found: ${a.tojstring()}", "error") }
                return LuaValue.NIL
            }
        })

        // hideVideo()
        api.set("hideVideo", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                mainHandler.post { hideVideoCallback?.invoke() }
                return LuaValue.NIL
            }
        })

        // toast(msg)
        api.set("toast", object : OneArgFunction() {
            override fun call(a: LuaValue): LuaValue {
                val msg = a.tojstring()
                mainHandler.post { android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show() }
                return LuaValue.NIL
            }
        })

        // vibrate(ms)
        api.set("vibrate", object : OneArgFunction() {
            override fun call(a: LuaValue): LuaValue {
                val ms = a.tolong()
                try {
                    val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        vib.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION") vib.vibrate(ms)
                    }
                } catch (_: Exception) {}
                return LuaValue.NIL
            }
        })

        // delay(ms, fn)
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

        // random(min, max)
        api.set("random", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                return LuaValue.valueOf((a.toint()..b.toint()).random())
            }
        })

        // loadSound(path) -> handle
        api.set("loadSound", object : OneArgFunction() {
            override fun call(a: LuaValue): LuaValue {
                return try {
                    val file = File(pluginDir, a.tojstring())
                    if (!file.exists()) return LuaValue.valueOf("error:not_found")
                    val key = "$pluginName::${a.tojstring()}"
                    if (soundIds.containsKey(key)) return LuaValue.valueOf(key)
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    val sp = SoundPool.Builder().setMaxStreams(20).setAudioAttributes(attrs).build()
                    soundLoaded[key] = false
                    sp.setOnLoadCompleteListener { _, _, status -> soundLoaded[key] = status == 0 }
                    soundIds[key] = sp.load(file.absolutePath, 1)
                    soundPools[key] = sp
                    LuaValue.valueOf(key)
                } catch (e: Exception) { LuaValue.valueOf("error:${e.message}") }
            }
        })

        // playSound(handle)
        api.set("playSound", object : OneArgFunction() {
            override fun call(a: LuaValue): LuaValue {
                val key = a.tojstring()
                if (soundLoaded[key] == true) soundPools[key]?.play(soundIds[key]!!, 1f, 1f, 1, 0, 1f)
                return LuaValue.NIL
            }
        })

        // stopSounds(handle)
        api.set("stopSounds", object : OneArgFunction() {
            override fun call(a: LuaValue): LuaValue {
                val key = a.tojstring()
                soundPools[key]?.autoPause()
                soundPools[key]?.autoResume()
                return LuaValue.NIL
            }
        })

        // http(url, method, headers, body) -> {code, body}
        api.set("http", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                return try {
                    val urlStr  = args.arg(1).tojstring()
                    val method  = args.arg(2).optjstring("GET").uppercase()
                    val headers = args.arg(3)
                    val bodyArg = args.arg(4)
                    val body    = if (bodyArg.isnil()) null else bodyArg.tojstring()
                    val conn = (URL(urlStr).openConnection() as HttpURLConnection).also {
                        it.requestMethod = method
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

        // typewrite(text, delayMs, onChar, onWord, onDone)
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
                    val idx = i
                    val r = Runnable {
                        wordBuf.append(ch)
                        try { onCharFn?.call(LuaValue.valueOf(ch.toString())) } catch (_: Exception) {}
                        val isEnd = idx == text.length - 1
                        if (ch == ' ' || ch == '\n' || isEnd) {
                            val word = wordBuf.toString().trim()
                            if (word.isNotEmpty()) try { onWordFn?.call(LuaValue.valueOf(word)) } catch (_: Exception) {}
                            wordBuf.clear()
                        }
                        if (isEnd) try { onDoneFn?.call() } catch (_: Exception) {}
                    }
                    typewriterJobs[pluginName]?.add(r)
                    mainHandler.postDelayed(r, idx * delayMs)
                }
                return LuaValue.NIL
            }
        })

        // cancelTypewrite()
        api.set("cancelTypewrite", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                typewriterJobs[pluginName]?.forEach { mainHandler.removeCallbacks(it) }
                typewriterJobs[pluginName]?.clear()
                return LuaValue.NIL
            }
        })

        // apiVersion()
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
