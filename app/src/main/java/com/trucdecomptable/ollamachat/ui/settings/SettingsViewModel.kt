package com.trucdecomptable.ollamachat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trucdecomptable.ollamachat.AppContainer
import com.trucdecomptable.ollamachat.data.ollama.ModelInfo
import com.trucdecomptable.ollamachat.util.PinUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val baseUrl: String = "",
    val model: String = "",
    val temperature: Double = 0.7,
    val topP: Double = 0.9,
    val topK: Int = 40,
    val numPredict: Int = 512,
    val numCtx: Int = 4096,
    val keepAlive: String = "5m",
    val streaming: Boolean = true,
    val defaultSystemPrompt: String = "",
    val theme: String = "system",
    val lockEnabled: Boolean = false,
    val lockOnBackground: Boolean = true,
    val models: List<ModelInfo> = emptyList(),
    val testing: Boolean = false,
    val testResult: String? = null,
    val testOk: Boolean? = null,
    val pinMessage: String? = null,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val settings = container.settings
    private val models = MutableStateFlow<List<ModelInfo>>(emptyList())
    private val testing = MutableStateFlow(false)
    private val testResult = MutableStateFlow<String?>(null)
    private val testOk = MutableStateFlow<Boolean?>(null)
    private val pinMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.baseUrl,
        settings.model,
        settings.temperature,
        settings.topP,
        settings.topK,
        settings.numPredict,
        settings.numCtx,
        settings.keepAlive,
        settings.streaming,
        settings.defaultSystemPrompt,
        settings.theme,
        settings.lockEnabled,
        settings.lockOnBackground,
        models,
        testing,
        testResult,
        testOk,
        pinMessage,
    ) { url, model, temp, topP, topK, np, ctx, ka, stream, prompt, theme, lock, lockBg,
        mods, test, res, ok, pin ->
        SettingsUiState(
            baseUrl = url,
            model = model,
            temperature = temp,
            topP = topP,
            topK = topK,
            numPredict = np,
            numCtx = ctx,
            keepAlive = ka,
            streaming = stream,
            defaultSystemPrompt = prompt,
            theme = theme,
            lockEnabled = lock,
            lockOnBackground = lockBg,
            models = mods,
            testing = test,
            testResult = res,
            testOk = ok,
            pinMessage = pin,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    init {
        viewModelScope.launch {
            if (uiState.value.baseUrl.isNotBlank()) refreshModels()
        }
    }

    fun onBaseUrlChange(v: String) {
        testResult.value = null
        testOk.value = null
        viewModelScope.launch { settings.setBaseUrl(v) }
    }

    fun onModelChange(v: String) {
        viewModelScope.launch { settings.setModel(v) }
    }

    fun onTemperatureChange(v: Double) {
        viewModelScope.launch { settings.setTemperature(v) }
    }

    fun onTopPChange(v: Double) {
        viewModelScope.launch { settings.setTopP(v) }
    }

    fun onTopKChange(v: Int) {
        viewModelScope.launch { settings.setTopK(v) }
    }

    fun onNumPredictChange(v: Int) {
        viewModelScope.launch { settings.setNumPredict(v) }
    }

    fun onNumCtxChange(v: Int) {
        viewModelScope.launch { settings.setNumCtx(v) }
    }

    fun onKeepAliveChange(v: String) {
        viewModelScope.launch { settings.setKeepAlive(v) }
    }

    fun onStreamingChange(v: Boolean) {
        viewModelScope.launch { settings.setStreaming(v) }
    }

    fun onSystemPromptChange(v: String) {
        viewModelScope.launch { settings.setDefaultSystemPrompt(v) }
    }

    fun onThemeChange(v: String) {
        viewModelScope.launch { settings.setTheme(v) }
    }

    fun onLockEnabledChange(v: Boolean) {
        viewModelScope.launch { settings.setLockEnabled(v) }
    }

    fun onLockOnBackgroundChange(v: Boolean) {
        viewModelScope.launch { settings.setLockOnBackground(v) }
    }

    fun onFirstLaunchDone() {
        viewModelScope.launch { settings.setFirstLaunchDone(true) }
    }

    fun testConnection() {
        val url = uiState.value.baseUrl.trim()
        if (url.isEmpty()) {
            testResult.value = "Adresse vide"
            testOk.value = false
            return
        }
        viewModelScope.launch {
            testing.value = true
            testResult.value = null
            testOk.value = null
            val result = container.ollamaClient.testConnection(url)
            testing.value = false
            if (result.isSuccess) {
                val models = container.ollamaClient.listModels(url).getOrNull().orEmpty()
                this@SettingsViewModel.models.value = models
                testResult.value = "Connexion OK — ${models.size} modèle(s) disponible(s)"
                testOk.value = true
            } else {
                testResult.value = "Échec : ${result.exceptionOrNull()?.message}"
                testOk.value = false
            }
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            val url = uiState.value.baseUrl
            if (url.isBlank()) return@launch
            container.ollamaClient.listModels(url).onSuccess { models.value = it }
        }
    }

    fun changePin(oldPin: String, newPin: String) {
        if (!PinUtils.isValidPin(newPin)) {
            pinMessage.value = "Le nouveau PIN doit avoir 4 chiffres"
            return
        }
        viewModelScope.launch {
            val currentHash = settings.pinHash.first()
            if (PinUtils.hash(oldPin) != currentHash) {
                pinMessage.value = "Ancien PIN incorrect"
                return@launch
            }
            settings.setPinHash(PinUtils.hash(newPin))
            pinMessage.value = "PIN modifié ✅"
        }
    }

    fun consumePinMessage() {
        pinMessage.value = null
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(container) as T
    }
}
