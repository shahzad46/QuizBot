package com.hobby.quizbot

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks Google's Gemini (free API key from aistudio.google.com) to pick the right option,
 * with "Grounding with Google Search" switched on so it checks the web first.
 * Blocking - call from a background thread.
 */
object GeminiHelper {

    /** Tried in order; the first one the key can use is remembered. */
    val MODELS = listOf("gemini-flash-latest", "gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-2.5-flash")

    data class Result(val index: Int, val model: String, val raw: String)

    fun ask(key: String, question: String, options: List<String>, preferredModel: String?): Result {
        val prompt = buildString {
            append("This is a multiple-choice question from an Islamic knowledge quiz ")
            append("(Quran, hadith, seerah, Hajj and Umrah, Makkah and Madinah).\n")
            append("Search the web to confirm the answer, then reply with ONLY the number of the correct option.\n\n")
            append("Question: ").append(question).append("\n")
            options.forEachIndexed { i, o -> append(i + 1).append(") ").append(o).append("\n") }
        }
        val order = listOfNotNull(preferredModel) + MODELS.filter { it != preferredModel }
        var lastError = "no model available"
        for (model in order) {
            try {
                val text = call(key, model, prompt)
                val n = Regex("\\b([1-9])\\b").find(text)?.groupValues?.get(1)?.toInt()
                    ?: throw IllegalStateException("no option number in reply: ${text.take(80)}")
                if (n < 1 || n > options.size) throw IllegalStateException("option $n out of range")
                return Result(n - 1, model, text.trim())
            } catch (e: ModelUnavailable) {
                lastError = "$model: ${e.message}"     // try the next model
            }
        }
        throw IllegalStateException(lastError)
    }

    private class ModelUnavailable(msg: String) : Exception(msg)

    private fun call(key: String, model: String, prompt: String): String {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 45_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", key)
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 400 && text.contains("API key", ignoreCase = true))
                throw IllegalStateException("the API key was rejected - check it in QuizBot")
            if (code == 403) throw IllegalStateException("the API key isn't allowed to use Gemini (HTTP 403)")
            // Model missing, not on the free tier, or over its free quota: try the next one.
            if (code == 404 || code == 429 || code == 400 || code >= 500) throw ModelUnavailable("HTTP $code")
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val parts = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
        } finally {
            conn.disconnect()
        }
    }
}
