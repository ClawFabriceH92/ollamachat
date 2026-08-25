package com.trucdecomptable.ollamachat.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.trucdecomptable.ollamachat.util.PinUtils
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
    }

    val defaults = Defaults()

    class Defaults {
        val baseUrl: String = ""
        val model: String = ""
        val temperature: Double = 0.7
        val topP: Double = 0.9
        val topK: Int = 40
        val numPredict: Int = 512
        val numCtx: Int = 4096
        val keepAlive: String = "5m"
        val streaming: Boolean = true
        val defaultSystemPrompt: String =
            "Tu es un assistant utile, concis et précis. Réponds en français sauf demande contraire."
        val theme: String = "system"
        val lockEnabled: Boolean = false
        val lockOnBackground: Boolean = true
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
    val pinHash: Flow<String> =
        context.dataStore.data.map { it[Keys.PIN_HASH] ?: PinUtils.hash("0000") }
    val lockOnBackground: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.LOCK_ON_BACKGROUND] ?: defaults.lockOnBackground }
    val lockConfig: Flow<LockConfig> =
        context.dataStore.data.map { p ->
            LockConfig(
                enabled = p[Keys.LOCK_ENABLED] ?: defaults.lockEnabled,
                pinHash = p[Keys.PIN_HASH] ?: PinUtils.hash("0000"),
                lockOnBackground = p[Keys.LOCK_ON_BACKGROUND] ?: defaults.lockOnBackground,
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
}

/** Snapshot of the lock settings (computed once, used by MainActivity). */
data class LockConfig(
    val enabled: Boolean,
    val pinHash: String,
    val lockOnBackground: Boolean,
)
