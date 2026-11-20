package com.hobby.quizbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Watches the screen through Android's accessibility API (no Developer options needed).
 *
 * Quiz screen  = a question, its options, and a "Check your answer" button.
 *   known question   -> tap the right option, then "Check your answer"
 *   unknown question -> pause, let you tap; your tap is saved to the answer bank
 * Result screen (right after we checked) -> tap Next / Continue / OK.
 */
class QuizService : AccessibilityService() {

    private data class TextNode(val text: String, val norm: String, val rect: Rect, val node: AccessibilityNodeInfo)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bank: AnswerBank
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var lastSignature: String? = null
    private var checkedAt = 0L                  // when we last pressed "Check your answer"
    private var answeredThisRun = 0
    private var pendingUnknown: Pair<String, List<String>>? = null  // question waiting for your tap

    private val scan = Runnable { scanScreen() }

    override fun onServiceConnected() {
        bank = AnswerBank(this)
        log("Service connected. Answer bank: ${bank.size} entries (${bank.learnedCount} learned).")
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName == packageName) return

        // Learn from your own tap while an unknown question is pending.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            pendingUnknown?.let { (q, opts) ->
                val clicked = event.text.joinToString(" ").ifBlank { event.contentDescription?.toString() ?: "" }
                val i = opts.indexOfFirst { AnswerBank.norm(it) == AnswerBank.norm(clicked) }
                if (i >= 0) {
                    bank.learn(q, opts[i])
                    pendingUnknown = null
                    log("LEARNED: \"$q\" -> ${opts[i]}")
                }
            }
            return
        }

        if (!prefs.getBoolean(KEY_ENABLED, false)) answeredThisRun = 0   // new run next time it's switched on
        if (!prefs.getBoolean(KEY_ENABLED, false) && !prefs.getBoolean(KEY_DUMP, false)) return
        handler.removeCallbacks(scan)
        handler.postDelayed(scan, 600)   // wait for the screen to settle
    }

    // --------------------------------------------------------------- screen logic

    private fun scanScreen() {
        val root = rootInActiveWindow ?: return
        // Ignore our own screen, the launcher, notification shade and keyboard:
        // keep waiting (and keep the dump request pending) until another app is in front.
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg == packageName || IGNORED_PACKAGES.any { pkg.startsWith(it) }) return
        val nodes = collect(root)

        if (prefs.getBoolean(KEY_DUMP, false)) {
            prefs.edit().putBoolean(KEY_DUMP, false).apply()
            log("SCREEN DUMP (${root.packageName}), ${nodes.size} text items:\n" +
                nodes.joinToString("\n") { "   • \"${it.text}\" ${it.rect.toShortString()}" })
            toast("Screen dumped to QuizBot log")
        }
        if (!prefs.getBoolean(KEY_ENABLED, false)) return

        val check = nodes.firstOrNull { it.norm in CHECK_WORDS }
        if (check == null) {
            handleResultScreen(nodes)
            return
        }

        // Question = the longest text above the check button (prefer one with "?").
        val above = nodes.filter { it.rect.bottom <= check.rect.top + 5 }
        val question = above.filter { it.text.contains("?") }.maxByOrNull { it.text.length }
            ?: above.filter { it.text.length > 20 }.maxByOrNull { it.text.length }
            ?: return
        val options = above
            .filter { it.rect.top >= question.rect.bottom - 5 && it !== question }
            .distinctBy { it.norm }
        if (options.size < 2) return

        val signature = question.norm + "|" + options.joinToString("|") { it.norm }
        if (signature == lastSignature) return          // already handled this screen
        lastSignature = signature
        pendingUnknown = null

        val max = prefs.getInt(KEY_MAX, 30)
        if (answeredThisRun >= max) {
            log("Reached the limit of $max answers. Turn the bot off and on to start a new run.")
            toast("QuizBot: limit reached")
            return
        }

        val optTexts = options.map { it.text }
        val hit = bank.answer(question.text, optTexts)
        if (hit == null) {
            pendingUnknown = question.text to optTexts
            log("UNKNOWN: \"${question.text}\"\n   options: ${optTexts.joinToString(" | ")}\n   -> tap the answer yourself; it will be learned.")
            toast("QuizBot: unknown question - tap the answer yourself")
            return
        }

        val (idx, matched) = hit
        log("Q: \"${question.text}\"\n   -> ${optTexts[idx]}  (bank: $matched)")
        click(options[idx])
        answeredThisRun++
        handler.postDelayed({
            // re-find the button: the UI may have redrawn after selecting an option
            val btn = rootInActiveWindow?.let { r -> collect(r).firstOrNull { it.norm in CHECK_WORDS } }
            if (btn != null) {
                click(btn)
                checkedAt = System.currentTimeMillis()
            } else log("Couldn't find the Check button after selecting.")
        }, 900)
    }

    private fun handleResultScreen(nodes: List<TextNode>) {
        if (System.currentTimeMillis() - checkedAt > 20_000) return   // only right after we answered
        val next = nodes.firstOrNull { it.norm in NEXT_WORDS } ?: return
        log("Result screen -> tapping \"${next.text}\"")
        checkedAt = 0
        lastSignature = null
        handler.postDelayed({ click(next) }, 1200)
    }

    // ---------------------------------------------------------------- helpers

    private fun collect(root: AccessibilityNodeInfo): List<TextNode> {
        val out = mutableListOf<TextNode>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40) return
            if (n.isVisibleToUser) {
                val t = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) {
                    val r = Rect().also { n.getBoundsInScreen(it) }
                    if (r.width() > 0 && r.height() > 0) out.add(TextNode(t, AnswerBank.norm(t), r, n))
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
    }

    /** Click via the accessibility tree; fall back to a real tap gesture at the centre. */
    private fun click(t: TextNode) {
        var n: AccessibilityNodeInfo? = t.node
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        tap(t.rect.exactCenterX(), t.rect.exactCenterY())
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun log(msg: String) = BotLog.add(this, msg)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val PREFS = "quizbot"
        const val KEY_ENABLED = "enabled"
        const val KEY_DUMP = "dump_next"
        const val KEY_MAX = "max_answers"

        private val IGNORED_PACKAGES = listOf(
            "com.android.systemui", "com.miui.home", "com.mi.android.globallauncher",
            "com.android.launcher", "com.google.android.apps.nexuslauncher",
            "com.google.android.inputmethod", "com.android.inputmethod", "com.miui.securitycenter",
            "com.android.settings", "com.android.permissioncontroller"
        )
        private val CHECK_WORDS = setOf("check your answer", "check answer", "submit", "submit answer")
        private val NEXT_WORDS = setOf("next", "next question", "continue", "ok", "got it", "done")
    }
}
