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

    data class TextNode(val text: String, val norm: String, val rect: Rect, val node: AccessibilityNodeInfo)

    val handler = Handler(Looper.getMainLooper())
    private lateinit var bank: AnswerBank
    private val collector by lazy { KeyCollector(this) }
    val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var lastSignature: String? = null
    private var answeredThisRun = 0
    private var doorIndex = 0
    private var lastAction = ""
    private var lastActionAt = 0L
    private var unknownSince = 0L
    private var lastResultLogged: String? = null

    private var pendingUnknown: Pair<String, List<String>>? = null   // question waiting for your tap
    private var pendingRects: List<Rect> = emptyList()               // where its options are on screen
    private var pendingLearn: Pair<String, String>? = null           // (question, option you tapped)
    private var pendingCheckRect: Rect? = null                       // "Check your answer" button
    private var baseFingerprints: List<String> = emptyList()         // how each option looked before your tap
    private var tapFrozen = false                                    // you pressed Check: stop updating
    private var lastEventIdx = -1                                    // option the latest screen change was in
    private val fingerprintCheck = Runnable { checkFingerprints(); requestShot() }
    private var pendingSince = 0L                                    // when the unknown question appeared
    private var optionBoxes: List<Rect> = emptyList()                // each option's button on screen
    private var baseShots: List<IntArray>? = null                    // option pixels before your tap
    private var shotPending = false
    private var lastShotAt = 0L
    private var shotFailLogged = false
    private var lastBotQuestion: String? = null

    // Poll the screen regularly instead of waiting for it to "settle":
    // Nusuk animates constantly, so a settle-based wait never fires.
    private val tick = object : Runnable {
        override fun run() {
            lastTick = System.currentTimeMillis()
            try {
                if (prefs.getBoolean(KEY_COLLECT, false)) collector.tick()
                else {
                    collector.reset()
                    if (prefs.getBoolean(KEY_ENABLED, false) || prefs.getBoolean(KEY_DUMP, false)) scanScreen()
                    else { answeredThisRun = 0; lastSignature = null }
                }
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
        lastTick = 0L
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName == packageName) return
        val (q, opts) = pendingUnknown ?: return
        if (tapFrozen) return

        val type = event.eventType
        val r = event.source?.let { src -> Rect().also { src.getBoundsInScreen(it) } }

        // A tap or change on "Check your answer" means your choice is final.
        val check = pendingCheckRect
        if (r != null && check != null && Rect.intersects(r, check) && r.height() <= check.height() * 3 &&
            (type == AccessibilityEvent.TYPE_VIEW_CLICKED || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
            checkFingerprints()
            // Only a real tap on Check makes the choice final (the button also redraws when it becomes active).
            if (type == AccessibilityEvent.TYPE_VIEW_CLICKED && pendingLearn != null) tapFrozen = true
            return
        }

        // Which option did this event happen in? (by text, else by position)
        var i = -1
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clicked = clickedText(event)
            i = opts.indexOfFirst { AnswerBank.norm(it) == AnswerBank.norm(clicked) }
        }
        if (i < 0 && r != null && r.height() < 600) {
            i = pendingRects.indexOfFirst { o ->
                Rect(o.left - 60, o.top - 40, o.right + 60, o.bottom + 40).contains(r.centerX(), r.centerY())
            }
        }
        when (type) {
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_SELECTED ->
                if (i >= 0) choose(q, opts[i], "tap")
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                if (i >= 0) lastEventIdx = i
                // The option you tapped usually redraws (new colour/border): compare how options look now.
                handler.removeCallbacks(fingerprintCheck)
                handler.postDelayed(fingerprintCheck, 150)
            }
        }
    }

    private fun choose(q: String, option: String, how: String) {
        if (pendingLearn?.second == option) return
        pendingLearn = q to option
        log("You chose: $option ($how; saved if the result is Correct)")
    }

    // ---- Screenshot-based detection: the option you tap is drawn in a different colour.

    private fun boxOf(n: AccessibilityNodeInfo): Rect {
        var b: AccessibilityNodeInfo? = n
        var up = 0
        while (b != null && !b.isClickable && up < 4) { b = b.parent; up++ }
        return Rect().also { (if (b != null && b.isClickable) b else n).getBoundsInScreen(it) }
    }

    private fun requestShot() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        if (shotPending || pendingUnknown == null || tapFrozen || optionBoxes.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastShotAt < 400) return
        shotPending = true
        lastShotAt = now
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                shotPending = false
                try {
                    val hw = android.graphics.Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val bmp = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    hw?.recycle()
                    if (bmp != null) { analyseShot(bmp); bmp.recycle() }
                } catch (e: Exception) {
                    if (!shotFailLogged) { shotFailLogged = true; log("Screenshot read failed: ${e.message}") }
                }
            }
            override fun onFailure(errorCode: Int) {
                shotPending = false
                if (!shotFailLogged) { shotFailLogged = true; log("Screenshot not allowed (error $errorCode) - can't see which option you tap.") }
            }
        })
    }

    private fun sample(bmp: android.graphics.Bitmap, r: Rect): IntArray {
        val left = r.left.coerceIn(0, bmp.width - 1); val right = r.right.coerceIn(left + 1, bmp.width)
        val top = r.top.coerceIn(0, bmp.height - 1); val bottom = r.bottom.coerceIn(top + 1, bmp.height)
        val out = ArrayList<Int>()
        var y = top
        while (y < bottom) { var x = left; while (x < right) { out.add(bmp.getPixel(x, y)); x += 3 }; y += 3 }
        return out.toIntArray()
    }

    private fun changedFraction(a: IntArray, b: IntArray): Double {
        if (a.isEmpty() || a.size != b.size) return 0.0
        var n = 0
        for (i in a.indices) {
            val p = a[i]; val q = b[i]
            val d = Math.abs(((p shr 16) and 255) - ((q shr 16) and 255)) +
                    Math.abs(((p shr 8) and 255) - ((q shr 8) and 255)) + Math.abs((p and 255) - (q and 255))
            if (d > 60) n++
        }
        return n.toDouble() / a.size
    }

    private fun analyseShot(bmp: android.graphics.Bitmap) {
        val (q, opts) = pendingUnknown ?: return
        if (tapFrozen || optionBoxes.size != opts.size) return
        val cur = optionBoxes.map { sample(bmp, it) }
        val base = baseShots
        if (base == null || System.currentTimeMillis() - pendingSince < 1500) { baseShots = cur; return }
        val frac = cur.indices.map { changedFraction(base[it], cur[it]) }
        val order = frac.indices.sortedByDescending { frac[it] }
        val best = order[0]; val second = order[1]
        // Several options changed at once = the result sheet is covering them: keep the choice made before.
        if (frac.count { it > 0.15 } >= 2) { if (pendingLearn != null) tapFrozen = true; return }
        if (frac[best] > 0.02 && frac[best] > 2.5 * frac[second]) choose(q, opts[best], "screen colours")
    }

    /** What an option looks like to accessibility: state flags, size and children of its button. */
    private fun fingerprint(n: AccessibilityNodeInfo?): String {
        var b = n
        var up = 0
        while (b != null && !b.isClickable && up < 4) { b = b.parent; up++ }
        val box = b ?: n ?: return ""
        val sb = StringBuilder()
        fun walk(x: AccessibilityNodeInfo?, d: Int) {
            if (x == null || d > 3) return
            val r = Rect().also { x.getBoundsInScreen(it) }
            sb.append(x.className).append(x.isSelected).append(x.isChecked).append(x.isEnabled)
                .append(if (android.os.Build.VERSION.SDK_INT >= 30) x.stateDescription else "")
                .append(x.contentDescription).append(r.width()).append('x').append(r.height())
                .append('#').append(x.childCount).append('|')
            for (k in 0 until x.childCount) walk(x.getChild(k), d + 1)
        }
        walk(box, 0)
        return sb.toString()
    }

    /** Compare options now vs. before your tap; exactly one changed => that's your choice. */
    private fun checkFingerprints() {
        val (q, opts) = pendingUnknown ?: return
        if (tapFrozen || baseFingerprints.size != opts.size) return
        val root = rootInActiveWindow ?: return
        val nodes = collect(root)
        val now = opts.map { o -> fingerprint(nodes.firstOrNull { it.norm == AnswerBank.norm(o) }?.node) }
        if (now.any { it.isEmpty() }) return            // question no longer on screen
        if (System.currentTimeMillis() - pendingSince < 1500) { baseFingerprints = now; return }  // still animating in
        val changed = opts.indices.filter { now[it] != baseFingerprints[it] }
        when {
            changed.size == 1 -> choose(q, opts[changed[0]], "screen change")
            changed.size > 1 && lastEventIdx in changed -> choose(q, opts[lastEventIdx], "screen change")
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

        // 2) Result screen ("Correct! ..." / "Play again") - checked first, it can sit on top of the question
        val playAgain = nodes.firstOrNull { it.norm in PLAY_AGAIN_WORDS }
        if (playAgain != null) { handleResult(nodes, playAgain, keys); return }

        // 3) Question screen
        val check = nodes.firstOrNull { it.norm in CHECK_WORDS }
        if (check != null) { handleQuestion(nodes, check); return }

        // 4) "Choose a door"
        val header = nodes.firstOrNull { it.norm == "choose a door" }
        if (header != null) { handleDoors(nodes, header, keys); return }

        // 5) "Your keys" page (e.g. after adding codes): go to the doors if there are keys
        if (nodes.any { it.norm == "check the code" }) {
            unknownSince = 0L
            if (stopIfDone(keys)) return
            nodes.lastOrNull { it.norm == "play" }?.let { tab -> act("play-tab") { click(tab) } }
            return
        }

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
        if (signature == lastSignature) {               // already handled this question
            pendingUnknown?.let { (q, _) ->
                options.firstOrNull { isSelected(it.node) }?.let { sel -> choose(q, sel.text, "selected") }
                    ?: checkFingerprints()
                requestShot()
            }
            return
        }
        lastSignature = signature
        pendingUnknown = null
        pendingLearn = null
        lastBotQuestion = null

        val optTexts = options.map { it.text }
        val hit = bank.answer(question.text, optTexts)
        if (hit == null) {
            pendingUnknown = question.text to optTexts
            pendingRects = options.map { Rect(it.rect) }
            pendingCheckRect = Rect(check.rect)
            baseFingerprints = options.map { fingerprint(it.node) }
            tapFrozen = false
            lastEventIdx = -1
            pendingSince = System.currentTimeMillis()
            optionBoxes = options.map { boxOf(it.node) }
            baseShots = null
            handler.postDelayed({ requestShot() }, 1600)
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
            if (pendingLearn == null && pendingUnknown != null)
                log("Couldn't tell which option you tapped, so this answer wasn't saved.")
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
            tapFrozen = false
            baseFingerprints = emptyList()
            optionBoxes = emptyList()
            baseShots = null
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
        nodes.firstOrNull { it.rect.top < resources.displayMetrics.heightPixels * 0.12 && it.text.trim().matches(Regex("\\d{1,3}")) }
            ?.text?.trim()?.toIntOrNull()

    fun dump(pkg: String, nodes: List<TextNode>) {
        log("SCREEN DUMP ($pkg), ${nodes.size} text items:\n" +
            nodes.joinToString("\n") { "   • \"${it.text}\" ${it.rect.toShortString()}" })
    }

    // ---------------------------------------------------------------- helpers

    /** Root nodes of all on-screen app windows (both apps in split screen), minus system UI and QuizBot. */
    fun visibleRoots(): List<Pair<String, AccessibilityNodeInfo>> {
        val roots = windows.mapNotNull { w -> w.root?.let { r -> (r.packageName?.toString() ?: "") to r } }
            .ifEmpty { listOfNotNull(rootInActiveWindow?.let { (it.packageName?.toString() ?: "") to it }) }
        return roots.filter { (pkg, _) -> pkg != packageName && IGNORED_PACKAGES.none { pkg.startsWith(it) } }
    }

    fun collect(root: AccessibilityNodeInfo, includeHidden: Boolean = false): List<TextNode> {
        val out = mutableListOf<TextNode>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60) return
            if (includeHidden || n.isVisibleToUser) {
                val t = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
                if (t.isNotEmpty() && t != "Close sheet") {
                    val r = Rect().also { n.getBoundsInScreen(it) }
                    if (includeHidden || (r.width() > 0 && r.height() > 0)) out.add(TextNode(t, AnswerBank.norm(t), r, n))
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
    }

    /** True if the option (or the button around it) is marked selected/checked. */
    private fun isSelected(n: AccessibilityNodeInfo?): Boolean {
        var x = n; var depth = 0
        while (x != null && depth < 4) {
            if (x.isSelected || x.isChecked) return true
            x = x.parent; depth++
        }
        return false
    }

    /** Click via the accessibility tree; fall back to a real tap gesture at the centre. */
    fun click(t: TextNode) {
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

    fun log(msg: String) = BotLog.add(this, msg)
    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val PREFS = "quizbot"
        const val KEY_ENABLED = "enabled"
        const val KEY_DUMP = "dump_next"
        const val KEY_MAX = "max_answers"
        const val KEY_COLLECT = "collect_keys"
        const val KEY_COLLECT_TARGET = "collect_target"
        private const val TICK_MS = 1200L

        /** Heartbeat read by the main screen: proves the service is really running, not just ticked in Settings. */
        @Volatile var lastTick = 0L
        fun isAlive() = System.currentTimeMillis() - lastTick < 5_000

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
