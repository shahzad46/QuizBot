package com.hobby.quizbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Browser
import android.view.accessibility.AccessibilityNodeInfo

/**
 * "Get new keys", as two separate steps the user starts one after the other:
 *
 * 1. COLLECT (switch "1. Collect keys") - open nusukkey.xyz in the browser once and read every
 *    code on the page (featured code + "More codes" list), tapping "Show 20 new codes" until no
 *    new codes appear. The codes are saved on the phone, then QuizBot shows a notification
 *    "N keys collected" and switches itself off.
 * 2. ENTER (switch "2. Enter keys") - the user opens Nusuk's "Your keys" page themselves
 *    ("Add code to get new key"). As soon as the code box and "Check the code" are on screen,
 *    QuizBot types the saved codes one by one and presses "Check the code".
 *      Congratulations -> key added, tap "Add another code to get a key", next code
 *      anything else   -> next code
 *    Stops when the saved codes run out or after 10 failures in a row.
 */
class KeyCollector(private val svc: QuizService) {

    private enum class Mode { NONE, COLLECT, ENTER }
    private enum class Phase { SUBMIT, WAIT_RESULT }

    private var mode = Mode.NONE
    private var startedAt = 0L

    // collect
    private var noGrowth = 0
    private var lastMore = 0L
    private var lastOpenSite = 0L
    private var found = 0

    // enter
    private var phase = Phase.SUBMIT
    private var current: String? = null
    private var tried = 0
    private var added = 0
    private var failsInRow = 0
    private var lastNav = 0L
    private var submittedAt = 0L
    private var resultAt = 0L
    private var onPageSince = 0L
    private var waitHintAt = 0L

    private val prefs get() = svc.prefs

    fun reset() {
        if (mode == Mode.NONE) return
        mode = Mode.NONE
        noGrowth = 0; lastMore = 0L; found = 0
        phase = Phase.SUBMIT; current = null
        tried = 0; added = 0; failsInRow = 0
        onPageSince = 0L; waitHintAt = 0L
    }

    // ================================================================ 1. collect codes

    fun tickCollect() {
        val now = System.currentTimeMillis()
        if (mode != Mode.COLLECT) {
            reset()
            mode = Mode.COLLECT; startedAt = now
            svc.log("Collect keys: opening nusukkey.xyz (${storedCodes(prefs).size} keys already saved)...")
            openSite()
            return
        }
        val wins = svc.visibleRoots()
        val browser = wins.firstOrNull { (pkg, root) ->
            pkg != NUSUK_PKG && svc.collect(root, includeHidden = true).any { CODE_RE.matches(it.text.trim()) }
        }?.second
        if (browser == null) {
            if (now - lastOpenSite > 10_000 && now - startedAt < 30_000) openSite()
            if (now - startedAt > 45_000) {
                if (found > 0) finishCollect("the page closed") else stopCollect("Couldn't read any codes from nusukkey.xyz")
            }
            return
        }
        val nodes = svc.collect(browser, includeHidden = true)
        val stored = storedCodes(prefs)
        val used = usedCodes()
        val fresh = nodes.map { it.text.trim() }
            .filter { CODE_RE.matches(it) && it !in used }
            .distinct()
            .filter { it !in stored }
        if (fresh.isNotEmpty()) {
            saveCodes((stored + fresh).takeLast(MAX_STORED))
            found += fresh.size
            svc.log("Collected $found new codes (${storedCodes(prefs).size} saved)")
        }
        if (now - startedAt > MAX_COLLECT_MS) { finishCollect("time limit"); return }

        // Ask the site for more codes until it stops giving new ones.
        if (now - lastMore > 3000) {
            if (fresh.isEmpty() && lastMore != 0L) noGrowth++ else noGrowth = 0
            if (noGrowth >= 4) {
                if (storedCodes(prefs).isEmpty()) stopCollect("The site showed no new codes")
                else finishCollect("no more new codes on the page")
                return
            }
            lastMore = now
            val more = nodes.firstOrNull { it.norm.startsWith("show 20 new codes") || it.norm.startsWith("show new codes") }
            if (more != null) {
                more.node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                svc.click(more)
            }
        }
    }

    private fun finishCollect(why: String) {
        val n = storedCodes(prefs).size
        prefs.edit().putBoolean(QuizService.KEY_COLLECT, false).apply()
        svc.log("KEYS COLLECTED: $found new, $n saved ($why). Now open Nusuk > Your keys > " +
            "Add code to get new key, and turn on '2. Enter keys'.")
        notifyUser("$n keys collected",
            "Turn on '2. Enter keys' in QuizBot, then open Nusuk's Your keys page.")
        reset()
        openQuizBot()
    }

    private fun stopCollect(reason: String) {
        prefs.edit().putBoolean(QuizService.KEY_COLLECT, false).apply()
        svc.log("COLLECT KEYS STOPPED: $reason.")
        notifyUser("Collecting keys failed", reason)
        reset()
    }

    // ================================================================ 2. enter codes in Nusuk

    fun tickEnter() {
        val now = System.currentTimeMillis()
        if (mode != Mode.ENTER) {
            reset()
            mode = Mode.ENTER; startedAt = now; waitHintAt = now
            val n = storedCodes(prefs).size
            if (n == 0) { stopEnter("No saved keys. Run '1. Collect keys' first"); return }
            svc.log("Enter keys: $n keys ready. Open Nusuk > Your keys > 'Add code to get new key'.")
            svc.toast("QuizBot: open Nusuk's Your keys page")
            return
        }
        val nusuk = svc.visibleRoots().firstOrNull { it.first == NUSUK_PKG }?.second
        when (phase) {
            Phase.SUBMIT -> submit(now, nusuk)
            Phase.WAIT_RESULT -> waitResult(now, nusuk)
        }
    }

    private fun submit(now: Long, nusuk: AccessibilityNodeInfo?) {
        val nodes = nusuk?.let { svc.collect(it) } ?: emptyList()
        val field = nusuk?.let { findEditable(it) }
        val onKeysPage = field != null && nodes.any { it.norm == "check the code" }
        val congrats = nodes.any { isSuccess(it) }

        if (!onKeysPage || congrats) {
            onPageSince = 0L
            // After a key was added Nusuk shows "Add another code to get a key": tap it.
            if (nodes.any { it.norm.startsWith("add another code") }) {
                navOnce(nodes) { it.norm.startsWith("add another code") }
            } else if (now - waitHintAt > 60_000) {
                waitHintAt = now
                svc.log("Enter keys: waiting for Nusuk's 'Add code to get new key' page " +
                    "(${storedCodes(prefs).size} keys left).")
            }
            if (now - maxOf(startedAt, resultAt) > MAX_WAIT_MS) stopEnter("Nusuk's code page didn't appear for 10 minutes")
            return
        }
        if (onPageSince == 0L) onPageSince = now
        if (now - onPageSince < 1000 || now - resultAt < 1500) return   // let the page finish appearing

        val code = current ?: storedCodes(prefs).firstOrNull()
            ?: run { stopEnter("All saved keys tried"); return }
        current = code
        typeCode(field!!, code)
        svc.handler.postDelayed({ pressCheck(code) }, 900)
        submittedAt = now
        tried++
        phase = Phase.WAIT_RESULT
    }

    private fun typeCode(field: AccessibilityNodeInfo, code: String) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Make sure the code is in the box (paste it if typing didn't stick), then press "Check the code". */
    private fun pressCheck(code: String) {
        val root = svc.visibleRoots().firstOrNull { it.first == NUSUK_PKG }?.second ?: return
        val field = findEditable(root)
        if (field != null) {
            field.refresh()
            if (field.text?.toString()?.trim() != code) {
                try {
                    val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("code", code))
                    field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val all = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, field.text?.length ?: 0)
                    }
                    field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, all)
                    field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    svc.log("Typing didn't stick, pasted $code instead")
                } catch (e: Exception) { svc.log("Couldn't paste the code: ${e.message}") }
            }
        }
        svc.handler.postDelayed({
            svc.visibleRoots().firstOrNull { it.first == NUSUK_PKG }?.second?.let { r ->
                svc.collect(r).firstOrNull { it.norm == "check the code" }?.let { svc.click(it) }
            }
        }, 400)
    }

    private fun waitResult(now: Long, nusuk: AccessibilityNodeInfo?) {
        val nodes = nusuk?.let { svc.collect(it) } ?: emptyList()
        val code = current ?: ""
        val ok = nodes.any { isSuccess(it) }
        val err = nodes.firstOrNull { n -> ERROR_WORDS.any { n.norm.contains(it) } }
        val elapsed = now - submittedAt

        when {
            ok -> {
                consume(code)
                added++; failsInRow = 0
                svc.log("✔ $code -> key added ($added added, ${storedCodes(prefs).size} keys left)")
                nodes.firstOrNull { it.norm.startsWith("add another code") }?.let { lastNav = now; svc.click(it) }
                nextCode(now)
            }
            (err != null && elapsed > 3500) || elapsed > 12_000 -> {
                consume(code)
                failsInRow++
                svc.log("✘ $code -> ${err?.text ?: "no response"} ($failsInRow failed in a row)")
                if (failsInRow >= MAX_FAILS_IN_ROW) { stopEnter("$MAX_FAILS_IN_ROW keys in a row failed"); return }
                nextCode(now)
            }
        }
    }

    private fun nextCode(now: Long) {
        current = null
        resultAt = now
        onPageSince = 0L
        phase = Phase.SUBMIT
    }

    private fun stopEnter(reason: String) {
        prefs.edit().putBoolean(QuizService.KEY_ENTER, false).apply()
        val left = storedCodes(prefs).size
        svc.log("ENTER KEYS FINISHED: $reason. Tried $tried, keys added $added, $left keys still saved.")
        notifyUser("$added keys added", "$reason. Tried $tried, $left keys still saved.")
        reset()
    }

    // ================================================================ helpers

    private fun isSuccess(n: QuizService.TextNode) =
        n.norm.startsWith("congratulations") || n.norm.startsWith("you got a new key")

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

    private fun openQuizBot() {
        try {
            svc.startActivity(Intent(svc, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (_: Exception) {}
    }

    private fun notifyUser(title: String, text: String) {
        svc.toast("QuizBot: $title")
        try {
            val nm = svc.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Get new keys", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(svc, 0,
                Intent(svc, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(svc, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("QuizBot: $title")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID, n)
        } catch (e: Exception) { svc.log("Couldn't show a notification: ${e.message}") }
    }

    private fun usedCodes(): Set<String> = prefs.getStringSet(KEY_USED, emptySet()) ?: emptySet()

    /** A code was tried: drop it from the saved list and remember it so it isn't collected again. */
    private fun consume(code: String) {
        if (code.isBlank()) return
        saveCodes(storedCodes(prefs).filter { it != code })
        val s = usedCodes().toMutableList().apply { add(code) }.takeLast(3000).toSet()
        prefs.edit().putStringSet(KEY_USED, s).apply()
    }

    private fun saveCodes(list: List<String>) = saveCodes(prefs, list)

    companion object {
        const val NUSUK_PKG = "com.moh.nusukapp"
        const val SITE_URL = "https://www.nusukkey.xyz/"
        private const val KEY_USED = "used_codes"
        private const val KEY_STORED = "stored_codes"
        private const val CHANNEL = "keys"
        private const val NOTIFICATION_ID = 7
        private const val MAX_FAILS_IN_ROW = 10
        private const val MAX_STORED = 500
        private const val MAX_COLLECT_MS = 4 * 60_000L
        private const val MAX_WAIT_MS = 10 * 60_000L
        val CODE_RE = Regex("NG-[A-Za-z0-9]{5,14}")
        private val ERROR_WORDS = listOf("already", "invalid", "not valid", "expired", "wrong", "not found",
            "error", "try again", "limit", "cannot", "can t", "unable")

        /** Codes collected in step 1 and not tried yet, oldest first. */
        fun storedCodes(prefs: SharedPreferences): List<String> =
            prefs.getString(KEY_STORED, "").orEmpty().split('\n').filter { it.isNotBlank() }

        fun saveCodes(prefs: SharedPreferences, list: List<String>) {
            prefs.edit().putString(KEY_STORED, list.joinToString("\n")).apply()
        }
    }
}
