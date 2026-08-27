package com.trucdecomptable.ollamachat.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.trucdecomptable.ollamachat.security.SecretVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** App settings persisted via DataStore. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val MODEL = stringPreferencesKey("model")
        val TEMPERATURE = doublePreferencesKey("temperature")
        val TOP_P = doublePreferencesKey("top_p")
        val TOP_K = intPreferencesKey("top_k")
        val NUM_PREDICT = intPreferencesKey("num_predict")
        val NUM_CTX = intPreferencesKey("num_ctx")
        val KEEP_ALIVE = stringPreferencesKey("keep_alive")
        val STREAMING = booleanPreferencesKey("streaming")
        val DEFAULT_SYSTEM_PROMPT = stringPreferencesKey("default_system_prompt")
        val THEME = stringPreferencesKey("theme") // system | light | dark
        val FIRST_LAUNCH_DONE = booleanPreferencesKey("first_launch_done")
        val LAST_CONVERSATION_ID = longPreferencesKey("last_conversation_id")
        val VISION_DETECTED = booleanPreferencesKey("vision_detected")
        val LOCK_ENABLED = booleanPreferencesKey("lock_enabled")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val LOCK_ON_BACKGROUND = booleanPreferencesKey("lock_on_background")
        val LOCK_FAILED_ATTEMPTS = intPreferencesKey("lock_failed_attempts")
        val LOCK_UNTIL = longPreferencesKey("lock_until")
        val BRAVE_API_KEY = stringPreferencesKey("brave_api_key")
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")
        val CONTEXT_COMPACT_ENABLED = booleanPreferencesKey("context_compact_enabled")
        val THINK_ENABLED = booleanPreferencesKey("think_enabled")
        val TOOLS_ENABLED = booleanPreferencesKey("tools_enabled")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val TURBO_ENABLED = booleanPreferencesKey("turbo_enabled")
        val TURBO_MODEL = stringPreferencesKey("turbo_model")
        val SKIPPED_UPDATE = stringPreferencesKey("skipped_update")
        val DEFAULT_EPHEMERAL = intPreferencesKey("default_ephemeral_minutes")
    }

    val defaults = Defaults()

    class Defaults {
        val baseUrl: String = ""
        val model: String = ""
        val temperature: Double = 0.7
        val topP: Double = 0.9
        val topK: Int = 40
        val numPredict: Int = 4096

        /**
         * 8 k was tight enough that a working conversation got compacted after
         * a handful of long turns. Every model still supported today handles
         * 16 k, and the cost falls on the server: a bigger window means a
         * bigger KV cache in RAM (or VRAM), so a small machine may want to put
         * this back down — the field says so.
         *
         * Only installs that never touched the field move: a value chosen in
         * the settings is stored and wins over this.
         */
        val numCtx: Int = 16384
        val keepAlive: String = "5m"
        val streaming: Boolean = true
        val defaultSystemPrompt: String =
            "Tu es un assistant utile, concis et précis. Réponds en français sauf demande contraire."
        val theme: String = "system"
        val lockEnabled: Boolean = false
        val lockOnBackground: Boolean = true
        val braveApiKey: String = ""
        val contextCompactEnabled: Boolean = true
        val thinkEnabled: Boolean = false
        val toolsEnabled: Boolean = true
        val dynamicColor: Boolean = false
        val turboEnabled: Boolean = false
        val turboModel: String = ""
        val defaultEphemeralMinutes: Int = 0
    }

    // --- flows ---
    val baseUrl: Flow<String> = context.dataStore.data.map { it[Keys.BASE_URL] ?: defaults.baseUrl }
    val model: Flow<String> = context.dataStore.data.map { it[Keys.MODEL] ?: defaults.model }
    val temperature: Flow<Double> = context.dataStore.data.map { it[Keys.TEMPERATURE] ?: defaults.temperature }
    val topP: Flow<Double> = context.dataStore.data.map { it[Keys.TOP_P] ?: defaults.topP }
    val topK: Flow<Int> = context.dataStore.data.map { it[Keys.TOP_K] ?: defaults.topK }
    val numPredict: Flow<Int> = context.dataStore.data.map { it[Keys.NUM_PREDICT] ?: defaults.numPredict }
    val numCtx: Flow<Int> = context.dataStore.data.map { it[Keys.NUM_CTX] ?: defaults.numCtx }
    val keepAlive: Flow<String> = context.dataStore.data.map { it[Keys.KEEP_ALIVE] ?: defaults.keepAlive }
    val streaming: Flow<Boolean> = context.dataStore.data.map { it[Keys.STREAMING] ?: defaults.streaming }
    val defaultSystemPrompt: Flow<String> =
        context.dataStore.data.map { it[Keys.DEFAULT_SYSTEM_PROMPT] ?: defaults.defaultSystemPrompt }
    val theme: Flow<String> = context.dataStore.data.map { it[Keys.THEME] ?: defaults.theme }
    val firstLaunchDone: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.FIRST_LAUNCH_DONE] ?: false }
    val lastConversationId: Flow<Long> =
        context.dataStore.data.map { it[Keys.LAST_CONVERSATION_ID] ?: -1L }
    val visionDetected: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.VISION_DETECTED] ?: false }
    val lockEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.LOCK_ENABLED] ?: defaults.lockEnabled }

    /**
     * Empty until the user actually sets a PIN. It used to default to the hash
     * of "0000", which unlocked any install where the lock was turned on
     * without choosing a code.
     */
    val pinHash: Flow<String> = context.dataStore.data.map { it[Keys.PIN_HASH].orEmpty() }
    val lockOnBackground: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.LOCK_ON_BACKGROUND] ?: defaults.lockOnBackground }
    val braveApiKey: Flow<String> =
        context.dataStore.data.map { SecretVault.decrypt(it[Keys.BRAVE_API_KEY].orEmpty()) }
    val contextCompactEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.CONTEXT_COMPACT_ENABLED] ?: defaults.contextCompactEnabled }
    val thinkEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.THINK_ENABLED] ?: defaults.thinkEnabled }
    val toolsEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.TOOLS_ENABLED] ?: defaults.toolsEnabled }
    val dynamicColor: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor }
    val turboEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.TURBO_ENABLED] ?: defaults.turboEnabled }
    val turboModel: Flow<String> =
        context.dataStore.data.map { it[Keys.TURBO_MODEL] ?: defaults.turboModel }
    val skippedUpdate: Flow<String> =
        context.dataStore.data.map { it[Keys.SKIPPED_UPDATE].orEmpty() }
    val defaultEphemeralMinutes: Flow<Int> =
        context.dataStore.data.map { it[Keys.DEFAULT_EPHEMERAL] ?: defaults.defaultEphemeralMinutes }
    val mcpServers: Flow<List<McpServer>> =
        context.dataStore.data.map { parseMcpServers(it[Keys.MCP_SERVERS]) }
    val lockConfig: Flow<LockConfig> =
        context.dataStore.data.map { p ->
            LockConfig(
                enabled = p[Keys.LOCK_ENABLED] ?: defaults.lockEnabled,
                pinHash = p[Keys.PIN_HASH].orEmpty(),
                lockOnBackground = p[Keys.LOCK_ON_BACKGROUND] ?: defaults.lockOnBackground,
                failedAttempts = p[Keys.LOCK_FAILED_ATTEMPTS] ?: 0,
                lockedUntil = p[Keys.LOCK_UNTIL] ?: 0L,
            )
        }

    /** One-shot snapshot of every persisted setting (single DataStore read). */
    val snapshot: Flow<SettingsSnapshot> = context.dataStore.data.map { p ->
        SettingsSnapshot(
            baseUrl = p[Keys.BASE_URL] ?: defaults.baseUrl,
            model = p[Keys.MODEL] ?: defaults.model,
            temperature = p[Keys.TEMPERATURE] ?: defaults.temperature,
            topP = p[Keys.TOP_P] ?: defaults.topP,
            topK = p[Keys.TOP_K] ?: defaults.topK,
            numPredict = p[Keys.NUM_PREDICT] ?: defaults.numPredict,
            numCtx = p[Keys.NUM_CTX] ?: defaults.numCtx,
            keepAlive = p[Keys.KEEP_ALIVE] ?: defaults.keepAlive,
            streaming = p[Keys.STREAMING] ?: defaults.streaming,
            defaultSystemPrompt = p[Keys.DEFAULT_SYSTEM_PROMPT] ?: defaults.defaultSystemPrompt,
            theme = p[Keys.THEME] ?: defaults.theme,
            lockEnabled = p[Keys.LOCK_ENABLED] ?: defaults.lockEnabled,
            lockOnBackground = p[Keys.LOCK_ON_BACKGROUND] ?: defaults.lockOnBackground,
            hasPin = !p[Keys.PIN_HASH].isNullOrBlank(),
            braveApiKey = SecretVault.decrypt(p[Keys.BRAVE_API_KEY].orEmpty()),
            contextCompactEnabled = p[Keys.CONTEXT_COMPACT_ENABLED] ?: defaults.contextCompactEnabled,
            mcpServers = parseMcpServers(p[Keys.MCP_SERVERS]),
            thinkEnabled = p[Keys.THINK_ENABLED] ?: defaults.thinkEnabled,
            toolsEnabled = p[Keys.TOOLS_ENABLED] ?: defaults.toolsEnabled,
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
            turboEnabled = p[Keys.TURBO_ENABLED] ?: defaults.turboEnabled,
            turboModel = p[Keys.TURBO_MODEL] ?: defaults.turboModel,
            defaultEphemeralMinutes = p[Keys.DEFAULT_EPHEMERAL] ?: defaults.defaultEphemeralMinutes,
        )
    }

    suspend fun setBaseUrl(v: String) = context.dataStore.edit { it[Keys.BASE_URL] = v }
    suspend fun setModel(v: String) = context.dataStore.edit { it[Keys.MODEL] = v }
    suspend fun setTemperature(v: Double) = context.dataStore.edit { it[Keys.TEMPERATURE] = v }
    suspend fun setTopP(v: Double) = context.dataStore.edit { it[Keys.TOP_P] = v }
    suspend fun setTopK(v: Int) = context.dataStore.edit { it[Keys.TOP_K] = v }
    suspend fun setNumPredict(v: Int) = context.dataStore.edit { it[Keys.NUM_PREDICT] = v }
    suspend fun setNumCtx(v: Int) = context.dataStore.edit { it[Keys.NUM_CTX] = v }
    suspend fun setKeepAlive(v: String) = context.dataStore.edit { it[Keys.KEEP_ALIVE] = v }
    suspend fun setStreaming(v: Boolean) = context.dataStore.edit { it[Keys.STREAMING] = v }
    suspend fun setDefaultSystemPrompt(v: String) = context.dataStore.edit { it[Keys.DEFAULT_SYSTEM_PROMPT] = v }
    suspend fun setTheme(v: String) = context.dataStore.edit { it[Keys.THEME] = v }
    suspend fun setFirstLaunchDone(v: Boolean = true) = context.dataStore.edit { it[Keys.FIRST_LAUNCH_DONE] = v }
    suspend fun setLastConversationId(v: Long) = context.dataStore.edit { it[Keys.LAST_CONVERSATION_ID] = v }
    suspend fun setVisionDetected(v: Boolean) = context.dataStore.edit { it[Keys.VISION_DETECTED] = v }
    suspend fun setLockEnabled(v: Boolean) = context.dataStore.edit { it[Keys.LOCK_ENABLED] = v }
    suspend fun setPinHash(v: String) = context.dataStore.edit { it[Keys.PIN_HASH] = v }
    suspend fun setLockOnBackground(v: Boolean) = context.dataStore.edit { it[Keys.LOCK_ON_BACKGROUND] = v }
    suspend fun setContextCompactEnabled(v: Boolean) = context.dataStore.edit { it[Keys.CONTEXT_COMPACT_ENABLED] = v }
    suspend fun setThinkEnabled(v: Boolean) = context.dataStore.edit { it[Keys.THINK_ENABLED] = v }
    suspend fun setToolsEnabled(v: Boolean) = context.dataStore.edit { it[Keys.TOOLS_ENABLED] = v }
    suspend fun setDynamicColor(v: Boolean) = context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = v }
    suspend fun setTurboEnabled(v: Boolean) = context.dataStore.edit { it[Keys.TURBO_ENABLED] = v }
    suspend fun setTurboModel(v: String) = context.dataStore.edit { it[Keys.TURBO_MODEL] = v }
    suspend fun setSkippedUpdate(v: String) = context.dataStore.edit { it[Keys.SKIPPED_UPDATE] = v }
    suspend fun setDefaultEphemeralMinutes(v: Int) = context.dataStore.edit { it[Keys.DEFAULT_EPHEMERAL] = v }

    /** Encrypted at rest with an Android Keystore key. */
    suspend fun setBraveApiKey(v: String) =
        context.dataStore.edit { it[Keys.BRAVE_API_KEY] = SecretVault.encrypt(v) }

    suspend fun setMcpServers(servers: List<McpServer>) =
        context.dataStore.edit { it[Keys.MCP_SERVERS] = serializeMcpServers(servers) }

    // --- brute-force throttling on the PIN pad ---

    /** Records a wrong PIN and returns the timestamp until which entry is blocked. */
    suspend fun recordFailedUnlock(): Long {
        var until = 0L
        context.dataStore.edit { p ->
            val attempts = (p[Keys.LOCK_FAILED_ATTEMPTS] ?: 0) + 1
            p[Keys.LOCK_FAILED_ATTEMPTS] = attempts
            until = System.currentTimeMillis() + LockoutPolicy.lockoutMillis(attempts)
            p[Keys.LOCK_UNTIL] = until
        }
        return until
    }

    suspend fun clearFailedUnlocks() = context.dataStore.edit { p ->
        p[Keys.LOCK_FAILED_ATTEMPTS] = 0
        p[Keys.LOCK_UNTIL] = 0L
    }

}

/** A configured MCP server (name + streamable HTTP endpoint). */
data class McpServer(
    val name: String,
    val url: String,
)

private fun parseMcpServers(json: String?): List<McpServer> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name", "").trim()
            val url = o.optString("url", "").trim()
            if (name.isEmpty() || url.isEmpty()) null else McpServer(name, url)
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun serializeMcpServers(servers: List<McpServer>): String {
    val arr = org.json.JSONArray()
    servers.forEach { s ->
        arr.put(
            org.json.JSONObject().apply {
                put("name", s.name)
                put("url", s.url)
            }
        )
    }
    return arr.toString()
}

/** Snapshot of the lock settings (computed once, used by MainActivity). */
data class LockConfig(
    val enabled: Boolean,
    val pinHash: String,
    val lockOnBackground: Boolean,
    val failedAttempts: Int = 0,
    val lockedUntil: Long = 0L,
) {
    /** The lock only guards anything once a PIN actually exists. */
    val active: Boolean get() = enabled && pinHash.isNotBlank()
}

/** Full persisted settings snapshot (single DataStore read). */
data class SettingsSnapshot(
    val baseUrl: String,
    val model: String,
    val temperature: Double,
    val topP: Double,
    val topK: Int,
    val numPredict: Int,
    val numCtx: Int,
    val keepAlive: String,
    val streaming: Boolean,
    val defaultSystemPrompt: String,
    val theme: String,
    val lockEnabled: Boolean,
    val lockOnBackground: Boolean,
    val hasPin: Boolean,
    val braveApiKey: String,
    val contextCompactEnabled: Boolean,
    val mcpServers: List<McpServer>,
    val thinkEnabled: Boolean,
    val toolsEnabled: Boolean,
    val dynamicColor: Boolean,
    val turboEnabled: Boolean,
    val turboModel: String,
    val defaultEphemeralMinutes: Int,
)
