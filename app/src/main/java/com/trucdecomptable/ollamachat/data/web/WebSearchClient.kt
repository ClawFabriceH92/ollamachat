package com.trucdecomptable.ollamachat.data.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Web search used to augment the model's answers.
 *
 * Two backends:
 *  - Brave Search API when a (free) API key is configured — real web search.
 *  - Wikipedia API otherwise — reliable, no key, but encyclopedic only.
 */
object WebSearchClient {

    data class SearchItem(val title: String, val url: String, val snippet: String)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun search(query: String, braveApiKey: String): Result<List<SearchItem>> =
        withContext(Dispatchers.IO) {
            try {
                val results = if (braveApiKey.isNotBlank()) searchBrave(query, braveApiKey)
                else searchWikipedia(query)
                Result.success(results)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun searchBrave(query: String, apiKey: String): List<SearchItem> {
        val url = "https://api.search.brave.com/res/v1/web/search?q=" +
            java.net.URLEncoder.encode(query, "UTF-8") + "&count=5"
        val req = Request.Builder()
            .url(url)
            .header("X-Subscription-Token", apiKey)
            .header("Accept", "application/json")
            .header("User-Agent", "OllamaChat/1.0 (Android)")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Brave HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: "{}")
            val web = json.optJSONObject("web") ?: JSONObject()
            val arr = web.optJSONArray("results") ?: JSONArray()
            return (0 until arr.length()).map { i ->
                val item = arr.getJSONObject(i)
                SearchItem(
                    title = item.optString("title", ""),
                    url = item.optString("url", ""),
                    snippet = item.optString("description", ""),
                )
            }
        }
    }

    private fun searchWikipedia(query: String): List<SearchItem> {
        val url = "https://fr.wikipedia.org/w/api.php?action=query&list=search&format=json" +
            "&srlimit=5&srsearch=" + java.net.URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "OllamaChat/1.0 (Android)")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Wikipedia HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: "{}")
            val arr = json.optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
            return (0 until arr.length()).map { i ->
                val item = arr.getJSONObject(i)
                val title = item.optString("title", "")
                SearchItem(
                    title = title,
                    url = "https://fr.wikipedia.org/wiki/" +
                        java.net.URLEncoder.encode(title.replace(' ', '_'), "UTF-8"),
                    snippet = item.optString("snippet", "")
                        .replace(Regex("<[^>]+>"), "")
                        .replace("&quot;", "\""),
                )
            }
        }
    }
}
