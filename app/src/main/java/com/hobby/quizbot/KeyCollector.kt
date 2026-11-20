package com.hobby.quizbot

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Browser
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Collects extra keys the way a person would:
 *   browser (nusukkey.xyz): read the "Next code for you" code, tap Copy
 *   Nusuk "Your keys":      type the code into "Add code to get new key", tap "Check the code"
 *   "Congratulations"       -> count it, tap "Add another code to get a key", get the next code
 *
 * Works with the two apps side by side (split screen) or by switching between them.
 * Stops at the target, or after 3 codes in a row are not accepted.
 */
class KeyCollector(private val svc: QuizService) {

    private enum class Phase { NEED_CODE, SUBMIT, WAIT_RESULT }

    private var phase = Phase.NEED_CODE
    private var phaseAt = 0L
    private var pending: String? = null
    private var added = 0
    private var failsInRow = 0
    private var reloads = 0
    private var lastOpenSite = 0L
    private var lastLaunchNusuk = 0L
    private var lastNav = 0L
    private var submittedAt = 0L

    private val prefs get() = svc.prefs

    fun reset() {
        phase = Phase.NEED_CODE; phaseAt = 0L; pending = null
        added = 0; failsInRow = 0; reloads = 0
    }

    fun tick() {
        val now = System.currentTimeMillis()
        val target = prefs.getInt(QuizService.KEY_COLLECT_TARGET, 5)
        val wins = svc.visibleRoots()
        val nusuk = wins.firstOrNull { it.first == NUSUK_PKG }?.second
        val browser = wins.firstOrNull { (pkg, root) ->
            pkg != NUSUK_PKG && svc.collect(root).any { CODE_RE.matches(it.text.trim()) }
        }?.second

        when (phase) {
            Phase.NEED_CODE -> {
                if (browser == null) {
                    if (now - lastOpenSite > 7000) openSite()
                    return
                }
                val nodes = svc.collect(browser)
                val label = nodes.firstOrNull { it.norm.startsWith("next code for you") }
                val codeNode = nodes.filter { CODE_RE.matches(it.text.trim()) }
                    .firstOrNull { label == null || it.rect.top >= label.rect.top } ?: return
                val code = codeNode.text.trim()
                if (code in usedCodes()) {
                    if (reloads >= 5) { stop("The site keeps showing codes already tried"); return }
                    if (now - lastOpenSite > 5000) { reloads++; openSite() }
                    return
                }
                reloads = 0
                pending = code
                svc.log("Key code from site: $code")
                // Tap the site's own Copy button for this code (counts the use on the site).
                nodes.filter { it.norm == "copy" && it.rect.top >= codeNode.rect.top - 10 }
                    .minByOrNull { it.rect.top - codeNode.rect.top }?.let { svc.click(it) }
                setPhase(Phase.SUBMIT)
                if (nusuk == null) launchNusuk()
            }

            Phase.SUBMIT -> {
                if (nusuk == null) {
                    if (now - lastLaunchNusuk > 6000) launchNusuk()
                } else {
                    val nodes = svc.collect(nusuk)
                    val field = findEditable(nusuk)
                    val onKeysPage = nodes.any { it.norm == "check the code" }
                    when {
                        field != null && onKeysPage -> {
                            val code = pending ?: run { setPhase(Phase.NEED_CODE); return }
                            val args = Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
                            }
                            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                            svc.handler.postDelayed({
                                svc.visibleRoots().firstOrNull { it.first == NUSUK_PKG }?.second?.let { r ->
                                    svc.collect(r).firstOrNull { it.norm == "check the code" }?.let { svc.click(it) }
                                }
                            }, 900)
                            submittedAt = now
                            setPhase(Phase.WAIT_RESULT)
                        }
                        nodes.any { it.norm.startsWith("add another code") } -> navOnce(nodes) { it.norm.startsWith("add another code") }
                        nodes.any { it.norm == "keys" } -> navOnce(nodes) { it.norm == "keys" }
                        nodes.any { it.norm.startsWith("start the challenge") && it.rect.height() < 200 } ->
                            navOnce(nodes) { it.norm.startsWith("start the challenge") }
                    }
                }
                if (now - phaseAt > 30_000) {
                    svc.log("Couldn't reach Nusuk's 'Your keys' page in 30 s.")
                    nusuk?.let { svc.dump(NUSUK_PKG, svc.collect(it)) }
                    stop("Stuck finding the code box")
                }
            }

            Phase.WAIT_RESULT -> {
                val nodes = nusuk?.let { svc.collect(it) } ?: emptyList()
                val ok = nodes.any { it.norm.startsWith("congratulations") || it.norm.startsWith("you got a new key") }
                val code = pending ?: ""
                if (ok) {
                    markUsed(code)
                    added++
                    failsInRow = 0
                    pending = null
                    svc.log("KEY ADDED with $code ($added/$target)")
                    if (added >= target) {
                        stop("Added $added keys")
                        return
                    }
                    nodes.firstOrNull { it.norm.startsWith("add another code") }?.let { svc.click(it) }
                    setPhase(Phase.NEED_CODE)
                    svc.handler.postDelayed({ openSite() }, 1200)
                } else if (now - submittedAt > 9000) {
                    markUsed(code)
                    failsInRow++
                    pending = null
                    svc.log("Code $code was not accepted. What Nusuk showed:")
                    svc.dump(NUSUK_PKG, nodes)
                    if (failsInRow >= 3) { stop("3 codes in a row not accepted (daily limit?)"); return }
                    setPhase(Phase.NEED_CODE)
                    openSite()
                }
            }
        }
    }

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
        val s = usedCodes().toMutableList().apply { add(code) }.takeLast(1000).toSet()
        prefs.edit().putStringSet(KEY_USED, s).apply()
    }

    private fun stop(reason: String) {
        prefs.edit().putBoolean(QuizService.KEY_COLLECT, false).apply()
        svc.log("KEY COLLECTION STOPPED: $reason. Total added this run: $added.")
        svc.toast("QuizBot: $reason")
        reset()
    }

    companion object {
        const val NUSUK_PKG = "com.moh.nusukapp"
        const val SITE_URL = "https://www.nusukkey.xyz/"
        private const val KEY_USED = "used_codes"
        val CODE_RE = Regex("NG-[A-Za-z0-9]{5,14}")
    }
}
