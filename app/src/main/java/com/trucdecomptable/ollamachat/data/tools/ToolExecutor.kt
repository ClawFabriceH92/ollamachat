package com.trucdecomptable.ollamachat.data.tools

import com.trucdecomptable.ollamachat.data.ollama.ToolDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

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
            description = "Calcule une expression arithmétique : + - * / ^ (puissance), parenthèses, " +
                "% en suffixe (15% = 0.15). Point décimal, sans séparateur de milliers : 1234.56.",
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

    /** A search's text for the model, and the addresses its results point to. */
    data class SearchOutcome(val text: String, val urls: List<String> = emptyList())

    /**
     * Only [SearchOutcome.urls] may be trusted afterwards: they come from the
     * search service. The text also carries what the model wrote into its
     * query ("Aucun résultat pour « … »", an error message), and an address
     * planted there would have been fetched unasked.
     */
    suspend fun webSearch(query: String, braveApiKey: String): SearchOutcome =
        withContext(Dispatchers.IO) {
            try {
                val items = com.trucdecomptable.ollamachat.data.web.WebSearchClient.search(query, braveApiKey)
                    .getOrElse { return@withContext SearchOutcome("Erreur de recherche : ${it.message}") }
                if (items.isEmpty()) {
                    SearchOutcome("Aucun résultat pour « $query »")
                } else {
                    val shown = items.take(5)
                    SearchOutcome(
                        text = shown.joinToString("\n") { "${it.title} — ${it.url}\n${it.snippet}" },
                        urls = shown.map { it.url.trim() },
                    )
                }
            } catch (e: Exception) {
                SearchOutcome("Erreur d'exécution de web_search : ${e.message ?: "inconnue"}")
            }
        }

    /**
     * [allowPrivateFetch] lets fetch_url reach the LAN; the repository grants
     * it only for an address the owner typed.
     */
    suspend fun execute(
        name: String,
        argumentsJson: String,
        braveApiKey: String = "",
        allowPrivateFetch: Boolean = false,
    ): String =
        withContext(Dispatchers.IO) {
            try {
                val args = JSONObject(argumentsJson)
                when (name) {
                    "web_search" -> webSearch(args.optString("query", ""), braveApiKey).text
                    "fetch_url" -> {
                        val url = args.optString("url", "")
                        com.trucdecomptable.ollamachat.data.web.UrlFetcher.fetch(url, allowPrivate = allowPrivateFetch)
                            .getOrElse { "Erreur de lecture : ${it.message}" }
                    }
                    "get_current_time" -> SimpleDateFormat(
                        "EEEE d MMMM yyyy, HH:mm",
                        Locale.FRENCH
                    ).format(Date())
                    "calculate" -> {
                        val expr = args.optString("expression", "").trim()
                        "$expr = ${Calculator.format(Calculator.evaluate(expr))}"
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

    /**
     * The raw value, unrounded. It used to be rounded to three decimals here,
     * which turned a monthly rate such as 1/3000 into 0, and through
     * Math.round, which saturates past 9.2e15.
     */
    fun evaluate(expression: String): Double {
        val tokens = normalize(expression)
        if (tokens.isEmpty() || !tokens.all { it.isDigit() || "+-*/^().%".contains(it) }) {
            throw IllegalArgumentException("Expression invalide")
        }
        val parser = Parser(tokens)
        val value = parser.parseExpression()
        if (!parser.atEnd()) throw IllegalArgumentException("Expression invalide")
        if (value.isNaN() || value.isInfinite()) throw IllegalArgumentException("Résultat non défini")
        return value
    }

    /**
     * Fifteen significant digits, what a double holds exactly, written out in
     * full: "$value" printed 12345678.9 as 1.23456789E7, and a model copies
     * what it reads.
     */
    fun format(value: Double): String {
        if (value == 0.0) return "0"
        val text = BigDecimal.valueOf(value)
            .round(MathContext(15, RoundingMode.HALF_EVEN))
            .stripTrailingZeros()
            .toPlainString()
        return if (abs(value) >= 1e15) "$text (au-delà de 15 chiffres, précision non garantie)" else text
    }

    /**
     * Spaces of every kind go, and a decimal comma becomes a point when it
     * cannot be anything else. "1,234" stays refused: as a French decimal it
     * is 1.234, as an English thousands separator 1234, and guessing wrong
     * would answer silently with a figure a thousand times off.
     */
    private fun normalize(expression: String): String {
        val compact = expression.filterNot { it.isWhitespace() || it == '\u00A0' || it == '\u202F' }
        if (',' !in compact) return compact
        val ambiguous = '.' in compact || THOUSANDS.containsMatchIn(compact)
        if (ambiguous) {
            throw IllegalArgumentException("Utilise le point décimal, sans séparateur de milliers : 1234.56")
        }
        return compact.replace(',', '.')
    }

    /** A comma followed by exactly three digits reads as a thousands separator. */
    private val THOUSANDS = Regex(",\\d{3}(?!\\d)")

    private class Parser(private val input: String) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= input.length

        private fun peek(): Char = if (pos < input.length) input[pos] else '\u0000'

        private fun consume(): Char = input[pos++]

        fun parseExpression(): Double {
            var value = parseTerm()
            while (peek() == '+' || peek() == '-') {
                val op = consume()
                val start = pos
                val rhs = parseTerm()
                // "1000+20%" reads as 1200 to a person and as 1000.2 here:
                // refused rather than answered silently with either. Both
                // readings agree when what precedes is 1, as in the usual
                // "prix*(1+20%)", which stays accepted.
                val term = unwrapped(input.substring(start, pos))
                if (value != 1.0 && term.endsWith('%') && term.none { it == '*' || it == '/' }) {
                    throw IllegalArgumentException(
                        "Pourcentage ambigu : écris 1000*1.2 pour ajouter 20 %, ou 1000*20% pour 20 % de 1000"
                    )
                }
                value = if (op == '+') value + rhs else value - rhs
            }
            return value
        }

        /** [term] without the parentheses that enclose all of it: "(20%)" is still "20%". */
        private fun unwrapped(term: String): String {
            var inner = term
            while (inner.length >= 2 && inner.first() == '(' && closingOf(inner) == inner.lastIndex) {
                inner = inner.substring(1, inner.lastIndex)
            }
            return inner
        }

        /** Index of the parenthesis closing the one at index 0, or -1. */
        private fun closingOf(text: String): Int {
            var depth = 0
            text.forEachIndexed { i, c ->
                if (c == '(') depth++
                if (c == ')' && --depth == 0) return i
            }
            return -1
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
            var base = parsePrimary()
            // A percentage is a hundredth: 15% is 0.15, 200*15% is 30.
            while (peek() == '%') {
                consume()
                base /= 100.0
            }
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
