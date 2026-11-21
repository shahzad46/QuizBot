package com.hobby.quizbot

import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shares learned answers between phones through the GitHub repo:
 *   upload   -> learned/<this-phone>.json   (needs a token with Contents: read & write on the repo)
 *   download -> every learned/*.json, merged (works without a token; the repo is public)
 * Each phone writes only its own file, so phones never overwrite each other.
 * Blocking - call from a background thread.
 */
object GitHubSync {

    private const val OWNER = "shahzad46"
    private const val REPO = "QuizBot"
    private const val DIR = "learned"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/contents"

    fun deviceName(): String =
        "${Build.MANUFACTURER}-${Build.MODEL}".lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** Replace this phone's file in the repo with its current learned answers. */
    fun upload(token: String, learnedJson: String) {
        val path = "$DIR/${deviceName()}.json"
        val existing = request("GET", "$API/$path?ref=main", token, null)
        val sha = if (existing.first == 200) JSONObject(existing.second).optString("sha") else null
        val body = JSONObject()
            .put("message", "Learned answers from ${deviceName()}")
            .put("content", Base64.encodeToString(learnedJson.toByteArray(), Base64.NO_WRAP))
            .put("branch", "main")
        if (!sha.isNullOrEmpty()) body.put("sha", sha)
        val (code, text) = request("PUT", "$API/$path", token, body.toString())
        if (code == 401 || code == 403) throw IllegalStateException("GitHub refused the token (HTTP $code) - check it has Contents: Read and write on QuizBot")
        if (code !in 200..299) throw IllegalStateException("GitHub upload failed (HTTP $code) ${text.take(120)}")
    }

    /** All phones' learned answers merged into one JSON array (empty if none yet). */
    fun downloadAll(token: String?): JSONArray {
        val merged = JSONArray()
        val (code, text) = request("GET", "$API/$DIR?ref=main", token, null)
        if (code == 404) return merged
        if (code !in 200..299) throw IllegalStateException("GitHub list failed (HTTP $code)")
        val files = JSONArray(text)
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            if (!f.optString("name").endsWith(".json")) continue
            val (c, body) = request("GET", "$API/${f.getString("path")}?ref=main", token, null)
            if (c !in 200..299) continue
            val content = String(Base64.decode(JSONObject(body).optString("content"), Base64.DEFAULT))
            val arr = try { JSONArray(content) } catch (e: Exception) { continue }
            for (k in 0 until arr.length()) merged.put(arr.get(k))
        }
        return merged
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
