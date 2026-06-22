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

// Current launcher plugin API version - bump this on breaking changes
const val LAUNCHER_API_VERSION = 1

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

class PluginManager(private val context: Context) {

    val plugins = mutableListOf<PluginInfo>()
    val commands = mutableMapOf<String, PluginCommand>()   // cmdName -> PluginCommand
    val settings = mutableListOf<PluginSetting>()
    val variables = mutableMapOf<String, String>()

    private val pluginsDir = File(context.filesDir, "plugins")
    private var terminalCallback: ((String, String) -> Unit)? = null
    private var colorCallback: ((String, String) -> Unit)? = null
    private var mediaPlayer: MediaPlayer? = null

    fun setTerminalCallback(cb: (text: String, type: String) -> Unit) {
        terminalCallback = cb
    }

    fun setColorCallback(cb: (element: String, hex: String) -> Unit) {
        colorCallback = cb
    }

    fun init() {
        pluginsDir.mkdirs()
        loadAllPlugins()
    }

    // ── INSTALL ───────────────────────────────────────────────────────────────

    fun installFromZip(zipFile: File): Pair<Boolean, String> {
        return try {
            // Read manifest first
            var manifestJson: String? = null
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "manifest.json") {
                        manifestJson = zis.readBytes().toString(Charsets.UTF_8)
                        break
                    }
                    entry = zis.nextEntry
                }
            }

            if (manifestJson == null) return Pair(false, "manifest.json not found in plugin")

            val manifest = JSONObject(manifestJson!!)
            val name = manifest.optString("name", "").trim()
            val apiVer = manifest.optInt("api_version", -1)

            if (name.isEmpty()) return Pair(false, "plugin name is empty in manifest")
            if (apiVer == -1) return Pair(false, "api_version missing in manifest")
            if (apiVer != LAUNCHER_API_VERSION) return Pair(false, "API version mismatch: plugin requires API $apiVer, launcher is API $LAUNCHER_API_VERSION")

            // Extract to plugins dir
            val pluginDir = File(pluginsDir, name)
            pluginDir.mkdirs()

            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(pluginDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> zis.copyTo(out) }
                    }
                    entry = zis.nextEntry
                }
            }

            // Reload plugins
            loadAllPlugins()
            Pair(true, "plugin '$name' installed.")
        } catch (e: Exception) {
            Pair(false, "install error: ${e.message}")
        }
    }

    // ── LOAD ALL ──────────────────────────────────────────────────────────────

    fun loadAllPlugins() {
        plugins.clear()
        commands.entries.removeAll { it.value.pluginName != "" }
        settings.removeAll { true }

        val dirs = pluginsDir.listFiles { f -> f.isDirectory } ?: return
        for (dir in dirs) {
            loadPlugin(dir)
        }
    }

    private fun loadPlugin(dir: File) {
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.exists()) return

        val manifest = try { JSONObject(manifestFile.readText()) } catch (e: Exception) {
            plugins.add(PluginInfo("?", "?", -1, "?", "?", dir, false, "bad manifest: ${e.message}"))
            return
        }

        val name        = manifest.optString("name", dir.name)
        val version     = manifest.optString("version", "?")
        val apiVer      = manifest.optInt("api_version", -1)
        val author      = manifest.optString("author", "unknown")
        val description = manifest.optString("description", "")

        // API version check
        if (apiVer != LAUNCHER_API_VERSION) {
            plugins.add(PluginInfo(name, version, apiVer, author, description, dir, false,
                "API version mismatch: plugin=$apiVer launcher=$LAUNCHER_API_VERSION"))
            return
        }

        val luaFile = File(dir, "main.lua")
        if (!luaFile.exists()) {
            plugins.add(PluginInfo(name, version, apiVer, author, description, dir, false, "main.lua not found"))
            return
        }

        // Execute Lua
        try {
            val globals = JsePlatform.standardGlobals()
            injectLauncherAPI(globals, name, dir)
            globals.load(luaFile.readText()).call()
            plugins.add(PluginInfo(name, version, apiVer, author, description, dir, true))
        } catch (e: Exception) {
            plugins.add(PluginInfo(name, version, apiVer, author, description, dir, false, "lua error: ${e.message}"))
        }
    }

    // ── LUA API ───────────────────────────────────────────────────────────────

    private fun injectLauncherAPI(globals: Globals, pluginName: String, pluginDir: File) {
        val api = LuaTable()

        // Launcher.registerCommand("name", function(args) end)
        api.set("registerCommand", object : TwoArgFunction() {
            override fun call(cmdName: LuaValue, handler: LuaValue): LuaValue {
                val name = cmdName.tojstring().lowercase().trim()
                if (handler is LuaFunction) {
                    commands[name] = PluginCommand(name, pluginName, handler)
                }
                return LuaValue.NIL
            }
        })

        // Launcher.registerSetting("label", "default", function(newVal) end)
        api.set("registerSetting", object : ThreeArgFunction() {
            override fun call(label: LuaValue, default_: LuaValue, handler: LuaValue): LuaValue {
                if (handler is LuaFunction) {
                    val lbl = label.tojstring()
                    val def = default_.tojstring()
                    // Load persisted value
                    val prefs = context.getSharedPreferences("plugin_$pluginName", Context.MODE_PRIVATE)
                    val cur = prefs.getString("setting_$lbl", def) ?: def
                    settings.add(PluginSetting(lbl, pluginName, def, cur, handler))
                }
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

        // Launcher.setVar("key", "value")
        api.set("setVar", object : TwoArgFunction() {
            override fun call(key: LuaValue, value: LuaValue): LuaValue {
                variables["$pluginName.${key.tojstring()}"] = value.tojstring()
                return LuaValue.NIL
            }
        })

        // Launcher.getVar("key") -> string
        api.set("getVar", object : OneArgFunction() {
            override fun call(key: LuaValue): LuaValue {
                val v = variables["$pluginName.${key.tojstring()}"] ?: ""
                return LuaValue.valueOf(v)
            }
        })

        // Launcher.playSound("assets/ding.mp3")
        api.set("playSound", object : OneArgFunction() {
            override fun call(path: LuaValue): LuaValue {
                try {
                    val f = File(pluginDir, path.tojstring())
                    if (f.exists()) {
                        mediaPlayer?.release()
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(f.absolutePath)
                            prepare()
                            start()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PluginManager", "playSound error: ${e.message}")
                }
                return LuaValue.NIL
            }
        })

        // Launcher.setColor("error", "#ff0000")
        api.set("setColor", object : TwoArgFunction() {
            override fun call(element: LuaValue, hex: LuaValue): LuaValue {
                colorCallback?.invoke(element.tojstring(), hex.tojstring())
                return LuaValue.NIL
            }
        })

        // Launcher.apiVersion() -> int
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
            args.forEachIndexed { i, a -> luaArgs.set(i + 1, LuaValue.valueOf(a)) }
            cmd.handler.call(luaArgs)
        } catch (e: Exception) {
            terminalCallback?.invoke("plugin error [${cmd.pluginName}]: ${e.message}", "error")
        }
        return true
    }

    // ── DELETE PLUGIN ─────────────────────────────────────────────────────────

    fun deletePlugin(name: String): Pair<Boolean, String> {
        val plugin = plugins.find { it.name.equals(name, ignoreCase = true) }
            ?: return Pair(false, "plugin not found: $name")
        plugin.dir.deleteRecursively()
        loadAllPlugins()
        return Pair(true, "plugin '${plugin.name}' deleted.")
    }

    fun release() {
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
