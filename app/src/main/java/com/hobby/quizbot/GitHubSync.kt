package com.hobby.quizbot

import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One common list of learned answers in the GitHub repo: learned/shared.json.
 * Any phone with a token (Contents: read & write on the repo) can add to it:
 *   - entries are matched by normalised question text, so a question is never added twice;
 *   - a new answer wording for a known question is added to that entry's answers;
 *   - questions already in the main answer list are skipped;
 *   - if two phones save at the same moment, GitHub rejects the older save and the phone
 *     re-reads the list and retries, so nothing is lost.
 * Downloading works without a token (the repo is public).
 * Blocking - call from a background thread.
 */
object GitHubSync {

    private const val OWNER = "shahzad46"
    private const val REPO = "QuizBot"
    private const val FILE = "learned/shared.json"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/contents"

    fun deviceName(): String =
        "${Build.MANUFACTURER}-${Build.MODEL}".lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    data class UploadResult(val added: Int, val updated: Int, val total: Int)

    /** Merge this phone's learned answers into the common list. */
    fun upload(token: String, local: JSONArray, alreadyKnown: (String) -> Boolean): UploadResult {
        repeat(5) {
            val (code, text) = request("GET", "$API/$FILE?ref=main", token, null)
            if (code == 401 || code == 403) throw IllegalStateException("GitHub refused the token (HTTP $code)")
            val sha = if (code == 200) JSONObject(text).optString("sha") else null
            val list = if (code == 200) parseContent(text) else JSONArray()

            val byKey = LinkedHashMap<String, JSONObject>()
            for (i in 0 until list.length()) {
                val e = list.optJSONObject(i) ?: continue
                val q = e.optJSONArray("keys")?.optString(0) ?: continue
                byKey.getOrPut(AnswerBank.norm(q)) { e }
            }
            var added = 0; var updated = 0
            for (i in 0 until local.length()) {
                val e = local.optJSONObject(i) ?: continue
                val q = e.optJSONArray("keys")?.optString(0) ?: continue
                val answers = e.optJSONArray("a") ?: continue
                if (alreadyKnown(q)) continue
                val k = AnswerBank.norm(q)
                val existing = byKey[k]
                if (existing == null) {
                    byKey[k] = JSONObject().put("keys", JSONArray().put(q)).put("a", answers)
                        .put("from", deviceName())
                    added++
                } else {
                    val have = existing.optJSONArray("a") ?: JSONArray().also { existing.put("a", it) }
                    val haveNorm = (0 until have.length()).map { AnswerBank.norm(have.optString(it)) }.toMutableSet()
                    for (j in 0 until answers.length()) {
                        val a = answers.optString(j)
                        if (haveNorm.add(AnswerBank.norm(a))) { have.put(a); updated++ }
                    }
                }
            }
            if (added == 0 && updated == 0) return UploadResult(0, 0, byKey.size)

            val out = JSONArray(); byKey.values.forEach { out.put(it) }
            val body = JSONObject()
                .put("message", "QuizBot: $added new, $updated updated from ${deviceName()}")
                .put("content", Base64.encodeToString(out.toString(1).toByteArray(), Base64.NO_WRAP))
                .put("branch", "main")
            if (!sha.isNullOrEmpty()) body.put("sha", sha)
            val (pc, pt) = request("PUT", "$API/$FILE", token, body.toString())
            when {
                pc in 200..299 -> return UploadResult(added, updated, byKey.size)
                pc == 409 || pc == 422 -> Thread.sleep(800)          // another phone saved first: re-read and retry
                pc == 401 || pc == 403 -> throw IllegalStateException("GitHub refused the token (HTTP $pc) - it needs Contents: Read and write on QuizBot")
                else -> throw IllegalStateException("GitHub upload failed (HTTP $pc) ${pt.take(120)}")
            }
        }
        throw IllegalStateException("GitHub kept changing while saving; will try again next time")
    }

    /** The common list (empty if nobody has shared anything yet). */
    fun download(token: String?): JSONArray {
        val (code, text) = request("GET", "$API/$FILE?ref=main", token, null)
        if (code == 404) return JSONArray()
        if (code !in 200..299) throw IllegalStateException("GitHub download failed (HTTP $code)")
        return parseContent(text)
    }

    private fun parseContent(apiJson: String): JSONArray {
        val content = String(Base64.decode(JSONObject(apiJson).optString("content"), Base64.DEFAULT))
        return try { JSONArray(content) } catch (e: Exception) { JSONArray() }
    }

    private fun request(method: String, url: String, token: String?, body: String?): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "QuizBot")
        if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer ${token.trim()}")
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }
}
