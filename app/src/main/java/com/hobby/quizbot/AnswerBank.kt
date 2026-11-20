package com.hobby.quizbot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * Offline answer bank.
 *  - assets/answers.json : built-in entries  {"keys": [phrases], "a": [answer variants]}
 *  - files/learned.json  : entries learned from your own taps on unknown questions
 * A question matches an entry when it contains one of the entry's key phrases
 * (longest matching phrase wins). An answer is only used if it matches one of the
 * options actually on screen, so a wrong lookup can't tap a random option.
 */
class AnswerBank(private val ctx: Context) {

    data class Entry(val keys: List<String>, val answers: List<String>, val learned: Boolean)

    private val entries = mutableListOf<Entry>()
    private val learnedFile get() = File(ctx.filesDir, "learned.json")

    init { reload() }

    fun reload() {
        entries.clear()
        val builtIn = ctx.assets.open("answers.json").bufferedReader().use { it.readText() }
        parse(builtIn, learned = false)
        if (learnedFile.exists()) parse(learnedFile.readText(), learned = true)
    }

    val size get() = entries.size
    val learnedCount get() = entries.count { it.learned }

    private fun parse(json: String, learned: Boolean) {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val keys = o.getJSONArray("keys").let { k -> (0 until k.length()).map { norm(k.getString(it)) } }
            val ans = o.getJSONArray("a").let { a -> (0 until a.length()).map { a.getString(it) } }
            entries.add(Entry(keys.filter { it.isNotBlank() }, ans, learned))
        }
    }

    /** Returns the index of the option to tap, or null if unknown / ambiguous. */
    fun answer(question: String, options: List<String>): Pair<Int, String>? {
        val q = norm(question)
        // Rank entries by the longest key phrase found in the question; learned entries
        // store the whole question, so they naturally win when the exact question repeats.
        val ranked = entries.mapNotNull { e ->
            val best = e.keys.filter { q.contains(it) }.maxOfOrNull { it.length }
            best?.let { e to it }
        }.sortedByDescending { it.second }

        for ((entry, _) in ranked) {
            for (variant in entry.answers) {
                val idx = matchOption(variant, options)
                if (idx != null) return idx to variant
            }
        }
        return null
    }

    /** Save the option you tapped for a question the bank didn't know. */
    fun learn(question: String, answer: String) {
        val arr = if (learnedFile.exists()) JSONArray(learnedFile.readText()) else JSONArray()
        val key = norm(question)
        // replace an existing learned entry for the same question
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (norm(o.getJSONArray("keys").getString(0)) != key) out.put(o)
        }
        out.put(JSONObject().put("keys", JSONArray().put(question)).put("a", JSONArray().put(answer)))
        learnedFile.writeText(out.toString(1))
        reload()
    }

    fun learnedJson(): String = if (learnedFile.exists()) learnedFile.readText() else "[]"

    companion object {
        private val ARTICLES = setOf("al", "an", "ar", "as", "at", "ash", "ad", "az", "surah", "surat", "sura", "the")

        fun norm(s: String): String {
            val noMarks = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            return noMarks.lowercase()
                .replace(Regex("[’‘'`ʿʾ\"“”]"), "")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
        }

        private fun tokens(s: String): List<String> {
            val t = norm(s).split(" ").filter { it.isNotBlank() }
            return t.filterIndexed { i, w -> !(i == 0 && w in ARTICLES && t.size > 1) }
        }

        /**
         * Find which option matches one answer variant.
         * exact (ignoring "Al-", spaces, hyphens) beats "variant inside option" beats
         * "option inside variant". Ambiguous ties return null.
         */
        fun matchOption(variant: String, options: List<String>): Int? {
            val v = tokens(variant)
            if (v.isEmpty()) return null
            val vc = v.joinToString("")
            val scores = options.map { opt ->
                val o = tokens(opt)
                when {
                    o.isEmpty() -> 0
                    o.joinToString("") == vc -> 3
                    o.containsAll(v) -> 2
                    v.containsAll(o) -> 1
                    else -> 0
                }
            }
            val top = scores.maxOrNull() ?: 0
            if (top == 0) return null
            val idx = scores.indices.filter { scores[it] == top }
            return if (idx.size == 1) idx[0] else null
        }
    }
}
