package com.hobby.quizbot

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

        // ---- Key collection from nusukkey.xyz
        root.addView(TextView(this).apply {
            text = "\nGet new keys (codes from nusukkey.xyz)"
            textSize = 16f
        })
        val targetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        targetRow.addView(TextView(this).apply { text = "Codes to gather: " })
        val targetEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt(QuizService.KEY_COLLECT_TARGET, 50).toString())
            setOnFocusChangeListener { _, has ->
                if (!has) text.toString().toIntOrNull()?.let { prefs.edit().putInt(QuizService.KEY_COLLECT_TARGET, it).apply() }
            }
        }
        targetRow.addView(targetEdit, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(targetRow)
        collectToggle = Switch(this).apply {
            text = "Get new keys"
            textSize = 16f
            isChecked = prefs.getBoolean(QuizService.KEY_COLLECT, false)
            setOnCheckedChangeListener { _, on ->
                targetEdit.text.toString().toIntOrNull()?.let { prefs.edit().putInt(QuizService.KEY_COLLECT_TARGET, it).apply() }
                prefs.edit().putBoolean(QuizService.KEY_COLLECT, on).apply()
                BotLog.add(this@MainActivity, if (on) "Get new keys ON (gather ${prefs.getInt(QuizService.KEY_COLLECT_TARGET, 50)} codes)" else "Get new keys OFF")
            }
        }
        root.addView(collectToggle)
        root.addView(TextView(this).apply {
            textSize = 12f
            text = "QuizBot opens nusukkey.xyz, gathers the codes, then switches to Nusuk and tries them one by one " +
                "(stops after 10 failures in a row). Xiaomi: allow QuizBot 'Display pop-up windows while running in the background'. " +
                "If 'Bot running' is also on, it plays the quiz with the new keys afterwards.\n"
        })

        button("Dump the next screen to the log (diagnostics)") {
            prefs.edit().putBoolean(QuizService.KEY_DUMP, true).apply()
            Toast.makeText(this, "Now switch to the quiz screen", Toast.LENGTH_LONG).show()
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
    }
}
