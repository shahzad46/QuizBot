package com.hobby.quizbot

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Control panel: enable the service, start/stop the bot, dump a screen, read the log. */
class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences(QuizService.PREFS, Context.MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var toggle: Switch
    private lateinit var collectToggle: Switch
    private lateinit var enterToggle: Switch
    private lateinit var storedView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun button(label: String, onClick: () -> Unit) =
            Button(this).apply { text = label; setOnClickListener { onClick() } }.also { root.addView(it) }

        status = TextView(this).apply { textSize = 15f }
        root.addView(status)

        button("1. Open Accessibility settings (turn on \"QuizBot auto-answer\")") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        toggle = Switch(this).apply {
            text = "2. Bot running (auto-answer in the quiz)"
            textSize = 16f
            isChecked = prefs.getBoolean(QuizService.KEY_ENABLED, false)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(QuizService.KEY_ENABLED, on).apply()
                BotLog.add(this@MainActivity, if (on) "Bot ON" else "Bot OFF")
                refresh()
            }
        }
        root.addView(toggle)

        val maxRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        maxRow.addView(TextView(this).apply { text = "Stop after this many answers: " })
        val maxEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt(QuizService.KEY_MAX, 30).toString())
            setOnFocusChangeListener { _, has ->
                if (!has) text.toString().toIntOrNull()?.let { prefs.edit().putInt(QuizService.KEY_MAX, it).apply() }
            }
        }
        maxRow.addView(maxEdit, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(maxRow)

        // ---- Get new keys from nusukkey.xyz, in two steps
        root.addView(TextView(this).apply {
            text = "\nGet new keys (codes from nusukkey.xyz)"
            textSize = 16f
        })
        storedView = TextView(this).apply { textSize = 14f }
        root.addView(storedView)
        collectToggle = Switch(this).apply {
            text = "1. Collect keys (opens nusukkey.xyz)"
            textSize = 16f
            isChecked = prefs.getBoolean(QuizService.KEY_COLLECT, false)
            setOnCheckedChangeListener { _, on ->
                if (on == prefs.getBoolean(QuizService.KEY_COLLECT, false)) return@setOnCheckedChangeListener
                val e = prefs.edit().putBoolean(QuizService.KEY_COLLECT, on)
                if (on) e.putBoolean(QuizService.KEY_ENTER, false)   // only one step at a time
                e.apply()
                BotLog.add(this@MainActivity, if (on) "Collect keys ON" else "Collect keys OFF")
                refresh()
            }
        }
        root.addView(collectToggle)
        enterToggle = Switch(this).apply {
            text = "2. Enter keys in Nusuk"
            textSize = 16f
            isChecked = prefs.getBoolean(QuizService.KEY_ENTER, false)
            setOnCheckedChangeListener { _, on ->
                if (on == prefs.getBoolean(QuizService.KEY_ENTER, false)) return@setOnCheckedChangeListener
                val e = prefs.edit().putBoolean(QuizService.KEY_ENTER, on)
                if (on) e.putBoolean(QuizService.KEY_COLLECT, false)   // only one step at a time
                e.apply()
                BotLog.add(this@MainActivity, if (on) "Enter keys ON (${KeyCollector.storedCodes(prefs).size} saved keys)" else "Enter keys OFF")
                refresh()
            }
        }
        root.addView(enterToggle)
        button("Clear saved keys") {
            KeyCollector.saveCodes(prefs, emptyList())
            BotLog.add(this, "Saved keys cleared")
            refresh()
        }
        root.addView(TextView(this).apply {
            textSize = 12f
            text = "Step 1: QuizBot opens nusukkey.xyz, collects every code it can find and tells you when it's done. " +
                "Step 2: turn on 'Enter keys', then open Nusuk > Your keys > 'Add code to get new key' yourself. " +
                "QuizBot types the saved codes one by one and presses 'Check the code' " +
                "(stops after 10 failures in a row). Tried codes are removed from the saved list.\n"
        })

        button("Dump the next screen to the log (diagnostics)") {
            prefs.edit().putBoolean(QuizService.KEY_DUMP, true).apply()
            Toast.makeText(this, "Now switch to the quiz screen", Toast.LENGTH_LONG).show()
        }
        // ---- Share learned answers between phones via GitHub
        root.addView(TextView(this).apply {
            text = "\nCommon list of learned answers on GitHub (any phone)"
            textSize = 16f
        })
        val tokenEdit = EditText(this).apply {
            hint = "GitHub token (Contents: read & write on QuizBot)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(prefs.getString(QuizService.KEY_GH_TOKEN, ""))
        }
        root.addView(tokenEdit)
        button("Save token & sync now") {
            val token = tokenEdit.text.toString().trim()
            prefs.edit().putString(QuizService.KEY_GH_TOKEN, token).apply()
            Toast.makeText(this, "Syncing with GitHub...", Toast.LENGTH_SHORT).show()
            Thread {
                val msg = try {
                    val bank = AnswerBank(this)
                    val n = bank.downloadLatest()
                    bank.reload()
                    val up = if (token.isNotEmpty()) {
                        val r = GitHubSync.upload(token, org.json.JSONArray(bank.learnedJson())) { bank.inMainList(it) }
                        "added ${r.added} new + ${r.updated} updated from this phone to the common list; "
                    } else "no token, upload skipped; "
                    "Sync done: " + up + "downloaded $n list entries + ${bank.sharedCount} shared" +
                        (bank.sharedError?.let { " (shared download failed: $it)" } ?: "")
                } catch (e: Exception) { "Sync failed: ${e.message}" }
                runOnUiThread { BotLog.add(this, msg); Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); refresh() }
            }.start()
        }

        button("Update answers from GitHub") {
            Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
            Thread {
                val msg = try { "Answer list updated: ${AnswerBank(this).downloadLatest()} entries." }
                          catch (e: Exception) { "Update failed: ${e.message}" }
                runOnUiThread {
                    BotLog.add(this, msg)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    refresh()
                }
            }.start()
        }
        button("Refresh log") { refresh() }
        button("Clear log") { BotLog.clear(this); refresh() }
        button("Show learned answers") {
            logView.text = AnswerBank(this).learnedJson()
        }

        root.addView(TextView(this).apply {
            textSize = 12f
            text = "Xiaomi/Redmi: so the service isn't killed, set Settings > Apps > QuizBot > Autostart ON " +
                "and Battery saver > No restrictions, and lock QuizBot in Recents (long-press its card > lock)."
        })
        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root, MATCH_PARENT, WRAP_CONTENT) })

        // Android 13+: ask once so "keys collected" can show as a notification.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private val statusTicker = object : Runnable {
        override fun run() { refresh(includeLog = false); status.postDelayed(this, 2000) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        status.removeCallbacks(statusTicker)
        status.post(statusTicker)
    }

    override fun onPause() {
        status.removeCallbacks(statusTicker)
        super.onPause()
    }

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, QuizService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun refresh(includeLog: Boolean = true) {
        val bank = AnswerBank(this)
        status.text = buildString {
            append(when {
                serviceEnabled() && QuizService.isAlive() -> "✅ Service running"
                serviceEnabled() -> "⚠️ ON in Settings but NOT running.\n   Fix: Accessibility > QuizBot auto-answer > OFF then ON.\n   Also allow Autostart for QuizBot (see below)."
                else -> "❌ Accessibility service OFF - do step 1"
            })
            append("\nBot: ").append(if (prefs.getBoolean(QuizService.KEY_ENABLED, false)) "RUNNING" else "stopped")
            append("\nAnswer bank: ${bank.size} entries (${bank.learnedCount} learned from you)")
        }
        if (includeLog) logView.text = BotLog.read(this)
        val running = prefs.getBoolean(QuizService.KEY_ENABLED, false)
        if (toggle.isChecked != running) toggle.isChecked = running   // bot may have stopped itself
        val collecting = prefs.getBoolean(QuizService.KEY_COLLECT, false)
        if (collectToggle.isChecked != collecting) collectToggle.isChecked = collecting
        val entering = prefs.getBoolean(QuizService.KEY_ENTER, false)
        if (enterToggle.isChecked != entering) enterToggle.isChecked = entering
        storedView.text = "Saved keys ready to enter: ${KeyCollector.storedCodes(prefs).size}"
    }
}
