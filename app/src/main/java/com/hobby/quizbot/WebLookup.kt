package com.hobby.quizbot

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Free web lookup, no API key, for "Which surah has the verse '...'" questions:
 * search the verse's words in English Quran translations (api.alquran.cloud) and match
 * the surah name to an option. (A Wikipedia-based lookup for other questions was tested
 * and was too often confidently wrong, so it isn't used.)
 * Returns null when it can't tell, so the question is left to the user.
 * Blocking - call from a background thread.
 */
object WebLookup {

    data class Result(val index: Int, val how: String)

    private const val UA = "QuizBot/1.0 (personal hobby app; Android)"

    fun answer(question: String, options: List<String>): Result? = verseQuestion(question, options)

    // ------------------------------------------------------------------ Quran verses

    private val QUOTE = Regex("[“\"]([^”\"]{8,})[”\"]")

    private fun verseQuestion(question: String, options: List<String>): Result? {
        val quote = QUOTE.findAll(question).map { it.groupValues[1] }.maxByOrNull { it.length } ?: return null
        if (!question.lowercase().let { it.contains("surah") || it.contains("surat") || it.contains("verse") }) return null
        val words = quote.lowercase().replace(Regex("[^a-z' ]"), " ").split(" ").filter { it.isNotBlank() }
        if (words.size < 3) return null

        // Phrases to try: the whole quote, then overlapping 4-word and 3-word windows.
        val phrases = LinkedHashSet<String>()
        phrases += words.joinToString(" ")
        for (n in listOf(4, 3)) for (i in 0..words.size - n) phrases += words.subList(i, i + n).joinToString(" ")

        val votes = HashMap<String, Double>()   // surah englishName -> weight
        var calls = 0
        for (p in phrases) {
            if (calls >= 8) break
            if (p.split(" ").count { it !in STOP } < 2) continue
            calls++
            val surahs = quranSearch(p) ?: continue
            if (surahs.isEmpty() || surahs.size > 25) continue
            val w = p.split(" ").size.toDouble() / surahs.distinct().size
            for (s in surahs.distinct()) votes[s] = (votes[s] ?: 0.0) + w
            if (p == phrases.first() && surahs.distinct().size == 1) break   // exact full-quote hit
        }
        // Best-voted surah that matches one of the options.
        for ((surah, _) in votes.entries.sortedByDescending { it.value }) {
            val idx = options.indexOfFirst { sameSurah(it, surah) }
            if (idx >= 0) return Result(idx, "Quran search: $surah")
        }
        return null
    }

    /** englishName of the surah for every verse matching the phrase, or null on error. */
    private fun quranSearch(phrase: String): List<String>? {
        val url = "https://api.alquran.cloud/v1/search/" + URLEncoder.encode(phrase, "UTF-8").replace("+", "%20") + "/all/en"
        val text = get(url) ?: return null
        val obj = JSONObject(text)
        if (obj.optInt("code") != 200) return emptyList()
        val matches = obj.optJSONObject("data")?.optJSONArray("matches") ?: return emptyList()
        return (0 until matches.length()).mapNotNull { matches.getJSONObject(it).optJSONObject("surah")?.optString("englishName") }
    }

    private fun surahKey(s: String): String {
        var t = s.lowercase().replace(Regex("\\(.*?\\)"), " ").trim()
        t = t.replace(Regex("^(surah|surat|sura)\\s+"), "")
        t = t.replace(Regex("^(al|an|ar|as|at|ash|ad|adh|az|ath|ad)[-\\s’'‘]+"), "")
        t = t.replace(Regex("[^a-z]"), "")
        return t.replace(Regex("(.)\\1+"), "$1")      // Ahzaab -> Ahzab, Taa-Haa -> Taha
    }

    private fun sameSurah(option: String, englishName: String): Boolean {
        val a = surahKey(option); val b = surahKey(englishName)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return a.length >= 4 && b.length >= 4 && levenshtein(a, b) <= 1
    }

    // ------------------------------------------------------------------ helpers

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", UA)
        return try {
            if (conn.responseCode in 200..299 || conn.responseCode == 404)
                (if (conn.responseCode == 404) conn.errorStream else conn.inputStream)?.bufferedReader()?.use { it.readText() }
            else null
        } catch (e: Exception) { null } finally { conn.disconnect() }
    }

    private fun tokens(s: String) = AnswerBank.norm(s).split(" ").filter { it.isNotBlank() }

    private fun levenshtein(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    private val STOP = setOf(
        "the", "a", "an", "of", "in", "on", "at", "to", "for", "by", "with", "and", "or", "is", "are", "was", "were",
        "be", "been", "what", "which", "who", "whom", "whose", "where", "when", "why", "how", "many", "much", "did",
        "does", "do", "his", "her", "their", "its", "it", "this", "that", "these", "those", "as", "from", "into",
        "upon", "him", "he", "she", "they", "them", "we", "you", "i", "me", "my", "our", "your", "not", "no",
        "following", "among", "called", "name", "named", "known", "pbuh", "peace", "god", "allah", "say", "said",
        "surah", "surat", "verse", "verses", "quran", "holy", "noble", "s", "may", "pleased"
    )
}
