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
 * Plays the Nusuk door quiz through Android's accessibility API (no Developer options).
 *
 * Loop:  Choose a door -> "Do you want to use your key?" -> Open the door
 *        -> question: tap the known answer -> Check your answer
 *        -> "Correct! ..." -> Play again -> (repeat)
 *
 * Unknown question: it waits for you to tap the answer and press Check yourself;
 * if the result says "Correct", your answer is saved to the answer bank.
 * It stops when keys reach 0, the answer limit is hit, or a screen it doesn't recognise
 * stays up for a while.
 */
class QuizService : AccessibilityService() {

    private data class TextNode(val text: String, val norm: String, val rect: Rect, val node: AccessibilityNodeInfo)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bank: AnswerBank
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var lastSignature: String? = null
    private var answeredThisRun = 0
    private var doorIndex = 0
    private var lastAction = ""
    private var lastActionAt = 0L
    private var unknownSince = 0L
    private var lastResultLogged: String? = null

    private var pendingUnknown: Pair<String, List<String>>? = null   // question waiting for your tap
    private var pendingLearn: Pair<String, String>? = null           // (question, option you tapped)
    private var lastBotQuestion: String? = null

    // Poll the screen regularly instead of waiting for it to "settle":
    // Nusuk animates constantly, so a settle-based wait never fires.
    private val tick = object : Runnable {
        override fun run() {
            try {
                if (prefs.getBoolean(KEY_ENABLED, false) || prefs.getBoolean(KEY_DUMP, false)) scanScreen()
                else { answeredThisRun = 0; lastSignature = null }
            } catch (e: Exception) {
                log("Error: ${e.javaClass.simpleName}: ${e.message}")
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        bank = AnswerBank(this)
        log("Service connected. Answer bank: ${bank.size} entries (${bank.learnedCount} learned).")
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
        updateAnswers()
    }

    /** Fetch the latest answer list from GitHub in the background. */
    private fun updateAnswers() {
        Thread {
            val msg = try {
                val n = AnswerBank(this).downloadLatest()
                "Answer list updated from GitHub: $n entries."
            } catch (e: Exception) {
                "Couldn't update answers from GitHub (${e.message}); using the saved list."
            }
            handler.post { log(msg); bank.reloadIfChanged() }
        }.start()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName == packageName) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return

        // Remember which option you tapped on an unknown question.
        val (q, opts) = pendingUnknown ?: return
        val clicked = clickedText(event)
        val i = opts.indexOfFirst { AnswerBank.norm(it) == AnswerBank.norm(clicked) }
        if (i >= 0) {
            pendingLearn = q to opts[i]
            log("You chose: ${opts[i]} (saved if the result is Correct)")
        }
    }

    private fun clickedText(event: AccessibilityEvent): String {
        event.text.joinToString(" ").trim().takeIf { it.isNotEmpty() }?.let { return it }
        event.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val src = event.source ?: return ""
        return collect(src).firstOrNull()?.text ?: ""
    }

    // --------------------------------------------------------------- screen logic

    private fun scanScreen() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg == packageName || IGNORED_PACKAGES.any { pkg.startsWith(it) }) return
        val nodes = collect(root)
        if (nodes.isEmpty()) return

        if (prefs.getBoolean(KEY_DUMP, false)) {
            prefs.edit().putBoolean(KEY_DUMP, false).apply()
            dump(pkg, nodes)
            toast("Screen dumped to QuizBot log")
        }
        if (!prefs.getBoolean(KEY_ENABLED, false)) return

        val keys = keyCount(nodes)

        // 1) "Do you want to use your key?" sheet
        nodes.firstOrNull { it.norm == "open the door" }?.let { btn ->
            if (stopIfDone(keys)) return
            act("open-door") { log("Opening the door (keys left: ${keys ?: "?"})"); click(btn) }
            return
        }

        // 2) Question screen
        val check = nodes.firstOrNull { it.norm in CHECK_WORDS }
        if (check != null) { handleQuestion(nodes, check); return }

        // 3) Result screen ("Correct! ..." / "Play again")
        val playAgain = nodes.firstOrNull { it.norm in PLAY_AGAIN_WORDS }
        if (playAgain != null) { handleResult(nodes, playAgain, keys); return }

        // 4) "Choose a door"
        val header = nodes.firstOrNull { it.norm == "choose a door" }
        if (header != null) { handleDoors(nodes, header, keys); return }

        // Anything else: if it stays for ~10 s, log it once so it can be taught.
        if (unknownSince == 0L) unknownSince = System.currentTimeMillis()
        else if (System.currentTimeMillis() - unknownSince > 10_000) {
            unknownSince = Long.MAX_VALUE / 2
            log("Waiting on a screen I don't recognise:")
            dump(pkg, nodes)
        }
        return
    }

    private fun handleQuestion(nodes: List<TextNode>, check: TextNode) {
        unknownSince = 0L
        bank.reloadIfChanged()
        val above = nodes.filter { it.rect.bottom <= check.rect.top + 5 }
        val question = above.filter { it.text.contains("?") }.maxByOrNull { it.text.length }
            ?: above.filter { it.text.length > 20 }.maxByOrNull { it.text.length }
            ?: return
        val options = above
            .filter { it.rect.top >= question.rect.bottom - 5 && it !== question }
            .filter { it.text.length <= 90 && NOT_OPTIONS.none { p -> it.norm.startsWith(p) } }
            .distinctBy { it.norm }
        if (options.size < 2) return

        val signature = question.norm + "|" + options.joinToString("|") { it.norm }
        if (signature == lastSignature) return          // already handled this question
        lastSignature = signature
        pendingUnknown = null
        pendingLearn = null
        lastBotQuestion = null

        val optTexts = options.map { it.text }
        val hit = bank.answer(question.text, optTexts)
        if (hit == null) {
            pendingUnknown = question.text to optTexts
            log("UNKNOWN: \"${question.text}\"\n   options: ${optTexts.joinToString(" | ")}\n   -> tap the answer and Check yourself; it's saved if Correct.")
            toast("QuizBot: new question - answer it yourself")
            return
        }

        val (idx, matched) = hit
        log("Q: \"${question.text}\"\n   -> ${optTexts[idx]}  (bank: $matched)")
        lastBotQuestion = question.text
        click(options[idx])
        answeredThisRun++
        handler.postDelayed({
            val btn = rootInActiveWindow?.let { r -> collect(r).firstOrNull { it.norm in CHECK_WORDS } }
            if (btn != null) click(btn) else log("Couldn't find 'Check your answer' after selecting.")
        }, 1000)
    }

    private fun handleResult(nodes: List<TextNode>, playAgain: TextNode, keys: Int?) {
        unknownSince = 0L
        val headline = nodes.firstOrNull { it.norm.startsWith("correct") || it.norm.startsWith("wrong") ||
                it.norm.startsWith("incorrect") || it.norm.contains("not correct") || it.norm.startsWith("unfortunately") }
        val correct = headline?.norm?.startsWith("correct") == true
        val resultKey = (lastSignature ?: "") + (headline?.norm ?: "")
        if (resultKey != lastResultLogged) {
            lastResultLogged = resultKey
            log(when {
                correct -> "Result: CORRECT"
                headline != null -> "Result: \"${headline.text}\""
                else -> "Result screen (no headline found)"
            })
            pendingLearn?.let { (q, a) ->
                if (correct) { bank.learn(q, a); log("LEARNED: \"$q\" -> $a") }
                else log("Not saved (answer wasn't confirmed correct): $a")
            }
            if (!correct && lastBotQuestion != null) {
                log("The bank's answer was marked wrong - screen saved below so it can be fixed:")
                dump(rootInActiveWindow?.packageName?.toString() ?: "", nodes)
            }
            pendingLearn = null
            pendingUnknown = null
        }
        if (stopIfDone(keys)) return
        act("play-again") { log("Play again"); lastSignature = null; click(playAgain) }
    }

    private fun handleDoors(nodes: List<TextNode>, header: TextNode, keys: Int?) {
        unknownSince = 0L
        if (stopIfDone(keys)) return
        val doors = nodes.filter {
            it.rect.top > header.rect.bottom && it.norm !in TAB_WORDS && it.norm != "start the challenge"
        }.distinctBy { it.norm }
        if (doors.isEmpty()) return
        val door = doors[doorIndex % doors.size]
        act("door") {
            doorIndex++
            log("Choosing door: ${door.text}")
            click(door)
        }
    }

    /** Stop when out of keys or the answer limit is reached. Returns true if stopped. */
    private fun stopIfDone(keys: Int?): Boolean {
        val max = prefs.getInt(KEY_MAX, 30)
        val reason = when {
            keys == 0 -> "No keys left"
            answeredThisRun >= max -> "Answered $answeredThisRun (limit $max)"
            else -> null
        } ?: return false
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        log("STOPPED: $reason. Bot switched off.")
        toast("QuizBot stopped: $reason")
        return true
    }

    /** Run an action unless the same one ran in the last few seconds (screens take time to change). */
    private fun act(name: String, block: () -> Unit) {
        val now = System.currentTimeMillis()
        if (name == lastAction && now - lastActionAt < 3500) return
        lastAction = name
        lastActionAt = now
        handler.postDelayed(block, 500)
    }

    /** The key counter is the small number in the top bar. */
    private fun keyCount(nodes: List<TextNode>): Int? =
        nodes.firstOrNull { it.rect.top < 200 && it.text.trim().matches(Regex("\\d{1,3}")) }?.text?.trim()?.toIntOrNull()

    private fun dump(pkg: String, nodes: List<TextNode>) {
        log("SCREEN DUMP ($pkg), ${nodes.size} text items:\n" +
            nodes.joinToString("\n") { "   • \"${it.text}\" ${it.rect.toShortString()}" })
    }

    // ---------------------------------------------------------------- helpers

    private fun collect(root: AccessibilityNodeInfo): List<TextNode> {
        val out = mutableListOf<TextNode>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40) return
            if (n.isVisibleToUser) {
                val t = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
                if (t.isNotEmpty() && t != "Close sheet") {
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
        private const val TICK_MS = 1200L

        private val IGNORED_PACKAGES = listOf(
            "com.android.systemui", "com.miui.home", "com.mi.android.globallauncher",
            "com.android.launcher", "com.google.android.apps.nexuslauncher",
            "com.google.android.inputmethod", "com.android.inputmethod", "com.miui.securitycenter",
            "com.android.settings", "com.android.permissioncontroller"
        )
        private val NOT_OPTIONS = listOf("your key can unlock", "choose the right answer")
        private val CHECK_WORDS = setOf("check your answer", "check answer", "submit", "submit answer")
        private val PLAY_AGAIN_WORDS = setOf("play again", "try again", "next question", "next", "continue")
        private val TAB_WORDS = setOf("play", "winners", "keys", "share", "help")
    }
}
