package com.trucdecomptable.ollamachat.data.tools

import com.trucdecomptable.ollamachat.data.ollama.ToolDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Executes the built-in tools exposed to the model. Each tool returns a
 * text result that is fed back to the model as a role="tool" message.
 */
object ToolExecutor {

    val nativeToolDefs: List<ToolDef> = listOf(
        ToolDef(
            name = "web_search",
            description = "Recherche sur le web (actualités, informations à jour). Utilise Wikipedia si aucune clé Brave n'est configurée.",
            parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"La requête de recherche"}},"required":["query"]}""",
        ),
        ToolDef(
            name = "fetch_url",
            description = "Lit le contenu d'une page web à partir de son URL et le retourne en texte.",
            parametersJson = """{"type":"object","properties":{"url":{"type":"string","description":"URL complète http(s) de la page"}},"required":["url"]}""",
        ),
        ToolDef(
            name = "get_current_time",
            description = "Retourne la date et l'heure actuelles du téléphone.",
            parametersJson = """{"type":"object","properties":{},"required":[]}""",
        ),
        ToolDef(
            name = "calculate",
            description = "Calcule une expression arithmétique (+, -, *, /, ^, parenthèses).",
            parametersJson = """{"type":"object","properties":{"expression":{"type":"string","description":"Expression arithmétique"}},"required":["expression"]}""",
        ),
        ToolDef(
            name = "get_weather",
            description = "Retourne la météo actuelle d'une ville (température, vent).",
            parametersJson = """{"type":"object","properties":{"city":{"type":"string","description":"Nom de la ville"}},"required":["city"]}""",
        ),
        ToolDef(
            name = "save_memory",
            description = "Enregistre un fait durable dans la mémoire persistante (préférence, information personnelle, décision). Ces faits seront rappelés dans toutes les conversations.",
            parametersJson = """{"type":"object","properties":{"content":{"type":"string","description":"Le fait à mémoriser, concis"}},"required":["content"]}""",
        ),
    )

    suspend fun execute(name: String, argumentsJson: String, braveApiKey: String = ""): String =
        withContext(Dispatchers.IO) {
            try {
                val args = JSONObject(argumentsJson)
                when (name) {
                    "web_search" -> {
                        val q = args.optString("query", "")
                        val items = com.trucdecomptable.ollamachat.data.web.WebSearchClient.search(q, braveApiKey)
                            .getOrElse { return@withContext "Erreur de recherche : ${it.message}" }
                        if (items.isEmpty()) "Aucun résultat pour « $q »"
                        else items.take(5).joinToString("\n") { "${it.title} — ${it.url}\n${it.snippet}" }
                    }
                    "fetch_url" -> {
                        val url = args.optString("url", "")
                        com.trucdecomptable.ollamachat.data.web.UrlFetcher.fetch(url)
                            .getOrElse { "Erreur de lecture : ${it.message}" }
                    }
                    "get_current_time" -> SimpleDateFormat(
                        "EEEE d MMMM yyyy, HH:mm",
                        Locale.FRENCH
                    ).format(Date())
                    "calculate" -> {
                        val expr = args.optString("expression", "").trim()
                        val result = Calculator.evaluate(expr)
                        "$expr = $result"
                    }
                    "get_weather" -> {
                        val city = args.optString("city", "")
                        Weather.getWeather(city)
                    }
                    else -> "Outil inconnu : $name"
                }
            } catch (e: Exception) {
                "Erreur d'exécution de $name : ${e.message ?: "inconnue"}"
            }
        }
}

/** Small safe arithmetic evaluator (no eval / reflection). */
object Calculator {

    fun evaluate(expression: String): Double {
        val tokens = expression.replace(" ", "")
        if (tokens.isEmpty() || !tokens.all { it.isDigit() || "+-*/^().".contains(it) }) {
            throw IllegalArgumentException("Expression invalide")
        }
        val parser = Parser(tokens)
        val value = parser.parseExpression()
        if (!parser.atEnd()) throw IllegalArgumentException("Expression invalide")
        if (value.isNaN() || value.isInfinite()) throw IllegalArgumentException("Résultat non défini")
        return round3(value)
    }

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0

    private class Parser(private val input: String) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= input.length

        private fun peek(): Char = if (pos < input.length) input[pos] else '\u0000'

        private fun consume(): Char = input[pos++]

        fun parseExpression(): Double {
            var value = parseTerm()
            while (peek() == '+' || peek() == '-') {
                val op = consume()
                val rhs = parseTerm()
                value = if (op == '+') value + rhs else value - rhs
            }
            return value
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (peek() == '*' || peek() == '/') {
                val op = consume()
                val rhs = parseFactor()
                value = if (op == '*') value * rhs else {
                    if (rhs == 0.0) throw IllegalArgumentException("Division par zéro")
                    value / rhs
                }
            }
            return value
        }

        private fun parseFactor(): Double {
            if (peek() == '-') {
                consume()
                return -parseFactor()
            }
            return parsePower()
        }

        private fun parsePower(): Double {
            val base = parsePrimary()
            if (peek() == '^') {
                consume()
                val exp = parseFactor()
                return Math.pow(base, exp)
            }
            return base
        }

        private fun parsePrimary(): Double {
            return when (peek()) {
                '(' -> {
                    consume()
                    val v = parseExpression()
                    if (peek() != ')') throw IllegalArgumentException("Parenthèse manquante")
                    consume()
                    v
                }
                else -> parseNumber()
            }
        }

        private fun parseNumber(): Double {
            val start = pos
            while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("Nombre attendu")
            return input.substring(start, pos).toDoubleOrNull()
                ?: throw IllegalArgumentException("Nombre invalide")
        }
    }
}

/** Weather via Open-Meteo (free, no key) + Nominatim geocoding. */
object Weather {

    fun getWeather(city: String): String {
        val geo = geocode(city) ?: return "Ville introuvable : $city"
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${geo.first}&longitude=${geo.second}" +
            "&current_weather=true&timezone=auto"
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", "OllamaChat/1.0").get().build()
        okHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return "Erreur météo (HTTP ${resp.code})"
            val json = JSONObject(resp.body?.string() ?: "{}")
            val cw = json.optJSONObject("current_weather") ?: return "Pas de données météo"
            val temp = cw.optDouble("temperature", Double.NaN)
            val wind = cw.optDouble("windspeed", Double.NaN)
            val code = cw.optInt("weathercode", -1)
            return "Météo à $city : ${temp}°C, vent ${wind} km/h, code ${code} (0=clair, 3=nuageux, 61=pluie)"
        }
    }

    private val okHttp by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun geocode(city: String): Pair<Double, Double>? {
        return try {
            val url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" +
                java.net.URLEncoder.encode(city, "UTF-8")
            val req = okhttp3.Request.Builder().url(url)
                .header("User-Agent", "OllamaChat/1.0")
                .header("Accept-Language", "fr")
                .get().build()
            okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val arr = org.json.JSONArray(resp.body?.string() ?: "[]")
                if (arr.length() == 0) return null
                val item = arr.getJSONObject(0)
                val lat = item.optDouble("lat", Double.NaN)
                val lon = item.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) null else lat to lon
            }
        } catch (_: Exception) {
            null
        }
    }
}
