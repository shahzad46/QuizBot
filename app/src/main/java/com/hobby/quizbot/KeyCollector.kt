package com.hobby.quizbot

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Browser
import android.view.accessibility.AccessibilityNodeInfo

/**
 * "Get new keys", in two stages:
 *
 * 1. GATHER - open nusukkey.xyz in the browser and read the codes on the page
 *    (featured code + "More codes" list), tapping "Show 20 new codes" until it has
 *    the number you asked for (default 50).
 * 2. TRY    - switch to Nusuk's "Your keys" page and enter the codes one by one:
 *    type into "Add code to get new key" -> "Check the code".
 *      Congratulations -> key added, tap "Add another code to get a key", next code
 *      anything else   -> next code
 *    Stops when the list is used up or after 10 failures in a row.
 */
class KeyCollector(private val svc: QuizService) {

    private enum class Phase { GATHER, SUBMIT, WAIT_RESULT }

    private var phase = Phase.GATHER
    private var phaseAt = 0L
    private val codes = ArrayDeque<String>()
    private val seen = mutableSetOf<String>()
    private var current: String? = null
    private var tried = 0
    private var added = 0
    private var failsInRow = 0
    private var noGrowth = 0
    private var lastMore = 0L
    private var lastOpenSite = 0L
    private var lastLaunchNusuk = 0L
    private var lastNav = 0L
    private var submittedAt = 0L
    private var started = false

    private val prefs get() = svc.prefs

    fun reset() {
        if (!started) return
        started = false
        phase = Phase.GATHER; phaseAt = 0L
        codes.clear(); seen.clear(); current = null
        tried = 0; added = 0; failsInRow = 0; noGrowth = 0
    }

    fun tick() {
        val now = System.currentTimeMillis()
        if (!started) {
            started = true
            setPhase(Phase.GATHER)
            svc.log("Get new keys: gathering ${goal()} codes from nusukkey.xyz...")
            openSite()
            return
        }
        val wins = svc.visibleRoots()
        val nusuk = wins.firstOrNull { it.first == NUSUK_PKG }?.second

        when (phase) {
            Phase.GATHER -> gather(now, wins)
            Phase.SUBMIT -> submit(now, nusuk)
            Phase.WAIT_RESULT -> waitResult(now, nusuk)
        }
    }

    // ------------------------------------------------------------- 1. gather codes

    private fun gather(now: Long, wins: List<Pair<String, AccessibilityNodeInfo>>) {
        val browser = wins.firstOrNull { (pkg, root) ->
            pkg != NUSUK_PKG && svc.collect(root, includeHidden = true).any { CODE_RE.matches(it.text.trim()) }
        }?.second
        if (browser == null) {
            if (now - lastOpenSite > 8000) openSite()
            if (now - phaseAt > 45_000) stop("Couldn't read codes from the site")
            return
        }
        val nodes = svc.collect(browser, includeHidden = true)
        val before = codes.size
        for (n in nodes) {
            val c = n.text.trim()
            if (CODE_RE.matches(c) && c !in seen && c !in usedCodes() && codes.size < goal()) {
                seen += c
                codes.addLast(c)
            }
        }
        if (codes.size > before) svc.log("Gathered ${codes.size}/${goal()} codes")

        if (codes.size >= goal()) { startTrying(); return }

        // Ask the site for more codes.
        if (now - lastMore > 3000) {
            if (codes.size == before && lastMore != 0L) noGrowth++ else noGrowth = 0
            if (noGrowth >= 4) {
                if (codes.isEmpty()) stop("The site showed no new codes") else startTrying()
                return
            }
            lastMore = now
            val more = nodes.firstOrNull { it.norm.startsWith("show 20 new codes") || it.norm.startsWith("show new codes") }
            if (more != null) {
                more.node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                svc.click(more)
            } else openSite()   // no button: a reload gives a fresh set
        }
    }

    private fun startTrying() {
        svc.log("Gathered ${codes.size} codes. Switching to Nusuk to try them.")
        setPhase(Phase.SUBMIT)
        launchNusuk()
    }

    // ------------------------------------------------------------- 2. try codes in Nusuk

    private fun submit(now: Long, nusuk: AccessibilityNodeInfo?) {
        if (nusuk == null) {
            if (now - lastLaunchNusuk > 6000) launchNusuk()
            if (now - phaseAt > 40_000) stop("Couldn't open Nusuk")
            return
        }
        val nodes = svc.collect(nusuk)
        val field = findEditable(nusuk)
        val onKeysPage = nodes.any { it.norm == "check the code" }
        when {
            field != null && onKeysPage -> {
                val code = current ?: codes.removeFirstOrNull()
                    ?: run { stop("All gathered codes tried"); return }
                current = code
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
                }
                field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                svc.handler.postDelayed({
                    svc.visibleRoots().firstOrNull { it.first == NUSUK_PKG }?.second?.let { r ->
                        svc.collect(r).firstOrNull { it.norm == "check the code" }?.let { svc.click(it) }
                    }
                }, 800)
                submittedAt = now
                tried++
                setPhase(Phase.WAIT_RESULT)
            }
            nodes.any { it.norm.startsWith("add another code") } -> navOnce(nodes) { it.norm.startsWith("add another code") }
            nodes.any { it.norm == "keys" } -> navOnce(nodes) { it.norm == "keys" }
            nodes.any { it.norm.startsWith("start the challenge") } -> navOnce(nodes) { it.norm.startsWith("start the challenge") }
        }
        if (now - phaseAt > 40_000) {
            svc.log("Couldn't reach Nusuk's 'Your keys' page.")
            svc.dump(NUSUK_PKG, nodes)
            stop("Stuck finding the code box")
        }
    }

    private fun waitResult(now: Long, nusuk: AccessibilityNodeInfo?) {
        val nodes = nusuk?.let { svc.collect(it) } ?: emptyList()
        val code = current ?: ""
        val ok = nodes.any { it.norm.startsWith("congratulations") || it.norm.startsWith("you got a new key") }
        val err = nodes.firstOrNull { n -> ERROR_WORDS.any { n.norm.contains(it) } }
        val elapsed = now - submittedAt

        when {
            ok -> {
                markUsed(code); current = null
                added++; failsInRow = 0
                svc.log("✔ $code -> key added ($added added, ${codes.size} codes left)")
                nodes.firstOrNull { it.norm.startsWith("add another code") }?.let { svc.click(it) }
                setPhase(Phase.SUBMIT)
            }
            (err != null && elapsed > 3000) || elapsed > 10_000 -> {
                markUsed(code); current = null
                failsInRow++
                svc.log("✘ $code -> ${err?.text ?: "no response"} ($failsInRow failed in a row)")
                if (failsInRow >= MAX_FAILS_IN_ROW) { stop("$MAX_FAILS_IN_ROW codes in a row failed"); return }
                setPhase(Phase.SUBMIT)
            }
        }
    }

    // ------------------------------------------------------------- helpers

    private fun goal() = prefs.getInt(QuizService.KEY_COLLECT_TARGET, 50).coerceIn(1, 500)

    private fun setPhase(p: Phase) { phase = p; phaseAt = System.currentTimeMillis() }

    private inline fun navOnce(nodes: List<QuizService.TextNode>, pick: (QuizService.TextNode) -> Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastNav < 3000) return
        nodes.firstOrNull(pick)?.let { lastNav = now; svc.click(it) }
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        fun walk(n: AccessibilityNodeInfo?, d: Int): AccessibilityNodeInfo? {
            if (n == null || d > 40) return null
            if (n.isEditable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)?.let { return it }
            return null
        }
        return walk(root, 0)
    }

    private fun openSite() {
        lastOpenSite = System.currentTimeMillis()
        try {
            // Same application id => the browser reuses one tab instead of opening a new one each time.
            svc.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SITE_URL))
                .putExtra(Browser.EXTRA_APPLICATION_ID, svc.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { svc.log("Couldn't open the browser: ${e.message}") }
    }

    private fun launchNusuk() {
        lastLaunchNusuk = System.currentTimeMillis()
        val i = svc.packageManager.getLaunchIntentForPackage(NUSUK_PKG)
        if (i == null) { svc.log("Nusuk app not found."); return }
        try { svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { svc.log("Couldn't open Nusuk: ${e.message}") }
    }

    private fun usedCodes(): Set<String> = prefs.getStringSet(KEY_USED, emptySet()) ?: emptySet()

    private fun markUsed(code: String) {
        if (code.isBlank()) return
        val s = usedCodes().toMutableList().apply { add(code) }.takeLast(3000).toSet()
        prefs.edit().putStringSet(KEY_USED, s).apply()
    }

    private fun stop(reason: String) {
        prefs.edit().putBoolean(QuizService.KEY_COLLECT, false).apply()
        svc.log("GET NEW KEYS FINISHED: $reason. Tried $tried, keys added $added.")
        svc.toast("QuizBot: $added keys added")
        reset()
    }

    companion object {
        const val NUSUK_PKG = "com.moh.nusukapp"
        const val SITE_URL = "https://www.nusukkey.xyz/"
        private const val KEY_USED = "used_codes"
        private const val MAX_FAILS_IN_ROW = 10
        val CODE_RE = Regex("NG-[A-Za-z0-9]{5,14}")
        private val ERROR_WORDS = listOf("already", "invalid", "not valid", "expired", "wrong", "not found",
            "error", "try again", "limit", "cannot", "can t", "unable")
    }
}
