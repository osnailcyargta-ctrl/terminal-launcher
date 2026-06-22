package com.osnailcyargta.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class GroqPlugin(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var soundPool: SoundPool? = null
    private var soundId: Int = 0
    private var soundLoaded = false

    // All pending typewriter runnables — cancel these on new ask
    private val pendingRunnables = mutableListOf<Runnable>()

    // Callback to print chars to terminal
    var onChar:  ((String) -> Unit)? = null
    var onPrint: ((String, String) -> Unit)? = null

    fun loadSound(mp3File: File) {
        soundPool?.release()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(20) // allow stacking up to 20 simultaneous sounds
            .setAudioAttributes(attrs)
            .build()
        soundPool?.setOnLoadCompleteListener { _, _, status ->
            soundLoaded = status == 0
        }
        soundId = soundPool?.load(mp3File.absolutePath, 1) ?: 0
    }

    fun ask(apiKey: String, prompt: String) {
        // Cancel all pending typewriter tasks
        cancelPending()

        Thread {
            try {
                val response = callGroq(apiKey, prompt)
                if (response == null) {
                    handler.post { onPrint?.invoke("groq: no response", "error") }
                    return@Thread
                }
                typewriterPrint(response)
            } catch (e: Exception) {
                handler.post { onPrint?.invoke("groq error: ${e.message}", "error") }
            }
        }.start()
    }

    private fun callGroq(apiKey: String, prompt: String): String? {
        val url = URL("https://api.groq.com/openai/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 30000

        val body = JSONObject().apply {
            put("model", "llama3-8b-8192")
            put("max_tokens", 512)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }.toString()

        conn.outputStream.use { it.write(body.toByteArray()) }

        val code = conn.responseCode
        val stream = if (code == 200) conn.inputStream else conn.errorStream
        val raw = stream.bufferedReader().readText()
        conn.disconnect()

        if (code != 200) {
            val errMsg = try { JSONObject(raw).optString("error", raw) } catch (_: Exception) { raw }
            handler.post { onPrint?.invoke("groq error $code: $errMsg", "error") }
            return null
        }

        return try {
            JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: Exception) {
            null
        }
    }

    private fun typewriterPrint(text: String) {
        val delayPerChar = 50L  // 0.05s per char
        var lineBuffer = StringBuilder()
        var charIndex = 0
        var wordBuffer = StringBuilder()

        // Print char by char
        for ((i, ch) in text.withIndex()) {
            val delay = i * delayPerChar

            val r = Runnable {
                lineBuffer.append(ch)
                wordBuffer.append(ch)

                // Play sound per word (on space or end of text)
                if (ch == ' ' || ch == '\n' || i == text.length - 1) {
                    if (wordBuffer.isNotBlank()) {
                        playSound()
                        wordBuffer.clear()
                    }
                }

                // Print line on newline or end
                if (ch == '\n' || i == text.length - 1) {
                    val line = lineBuffer.toString().trimEnd('\n')
                    if (line.isNotEmpty()) onPrint?.invoke(line, "output")
                    lineBuffer.clear()
                }
            }

            pendingRunnables.add(r)
            handler.postDelayed(r, delay)
        }
    }

    private fun playSound() {
        if (soundLoaded && soundId != 0) {
            // volume, priority, loop(-1=loop,0=once), rate
            soundPool?.play(soundId, 1f, 1f, 1, 0, 1f)
        }
    }

    private fun cancelPending() {
        for (r in pendingRunnables) handler.removeCallbacks(r)
        pendingRunnables.clear()
        // Also stop all currently playing sounds
        soundPool?.autoPause()
        soundPool?.autoResume() // resume for next use
    }

    fun release() {
        cancelPending()
        soundPool?.release()
        soundPool = null
    }
}
