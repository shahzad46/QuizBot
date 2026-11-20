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

        button("Dump the next screen to the log (diagnostics)") {
            prefs.edit().putBoolean(QuizService.KEY_DUMP, true).apply()
            Toast.makeText(this, "Now switch to the quiz screen", Toast.LENGTH_LONG).show()
        }
        button("Refresh log") { refresh() }
        button("Clear log") { BotLog.clear(this); refresh() }
        button("Show learned answers") {
            logView.text = AnswerBank(this).learnedJson()
        }

        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root, MATCH_PARENT, WRAP_CONTENT) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, QuizService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun refresh() {
        val bank = AnswerBank(this)
        status.text = buildString {
            append(if (serviceEnabled()) "✅ Accessibility service ON" else "❌ Accessibility service OFF - do step 1")
            append("\nBot: ").append(if (prefs.getBoolean(QuizService.KEY_ENABLED, false)) "RUNNING" else "stopped")
            append("\nAnswer bank: ${bank.size} entries (${bank.learnedCount} learned from you)")
        }
        logView.text = BotLog.read(this)
    }
}
