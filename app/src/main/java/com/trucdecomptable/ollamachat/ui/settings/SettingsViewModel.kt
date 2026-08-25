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
    val braveApiKey: String = "",
    val models: List<ModelInfo> = emptyList(),
    val testing: Boolean = false,
    val testResult: String? = null,
    val testOk: Boolean? = null,
    val pinMessage: String? = null,
    val scanning: Boolean = false,
    val scanResults: List<com.trucdecomptable.ollamachat.data.ollama.NetworkScanner.ScanResult> = emptyList(),
)

/** Transient (non-persisted) UI state merged into the settings snapshot. */
private data class Transient(
    val models: List<ModelInfo> = emptyList(),
    val testing: Boolean = false,
    val testResult: String? = null,
    val testOk: Boolean? = null,
    val pinMessage: String? = null,
    val scanning: Boolean = false,
    val scanResults: List<com.trucdecomptable.ollamachat.data.ollama.NetworkScanner.ScanResult> = emptyList(),
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val settings = container.settings
    private val transient = MutableStateFlow(Transient())

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.snapshot,
        transient,
    ) { snap, tr ->
        SettingsUiState(
            baseUrl = snap.baseUrl,
            model = snap.model,
            temperature = snap.temperature,
            topP = snap.topP,
            topK = snap.topK,
            numPredict = snap.numPredict,
            numCtx = snap.numCtx,
            keepAlive = snap.keepAlive,
            streaming = snap.streaming,
            defaultSystemPrompt = snap.defaultSystemPrompt,
            theme = snap.theme,
            lockEnabled = snap.lockEnabled,
            lockOnBackground = snap.lockOnBackground,
            braveApiKey = snap.braveApiKey,
            models = tr.models,
            testing = tr.testing,
            testResult = tr.testResult,
            testOk = tr.testOk,
            pinMessage = tr.pinMessage,
            scanning = tr.scanning,
            scanResults = tr.scanResults,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    init {
        viewModelScope.launch {
            if (uiState.value.baseUrl.isNotBlank()) refreshModels()
        }
    }

    fun onBaseUrlChange(v: String) {
        transient.value = transient.value.copy(testResult = null, testOk = null)
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

    fun onBraveApiKeyChange(v: String) {
        viewModelScope.launch { settings.setBraveApiKey(v) }
    }

    fun onFirstLaunchDone() {
        viewModelScope.launch { settings.setFirstLaunchDone(true) }
    }

    fun testConnection(urlFromField: String) {
        val url = urlFromField.trim()
        if (url.isEmpty()) {
            transient.value = transient.value.copy(testResult = "Adresse vide", testOk = false)
            return
        }
        viewModelScope.launch {
            transient.value = transient.value.copy(testing = true, testResult = null, testOk = null)
            val result = container.ollamaClient.testConnection(url)
            if (result.isSuccess) {
                val models = container.ollamaClient.listModels(url).getOrNull().orEmpty()
                transient.value = transient.value.copy(
                    testing = false,
                    models = models,
                    testResult = "Connexion OK — ${models.size} modèle(s) disponible(s)",
                    testOk = true,
                )
            } else {
                transient.value = transient.value.copy(
                    testing = false,
                    testResult = "Échec : ${result.exceptionOrNull()?.message}",
                    testOk = false,
                )
            }
        }
    }

    /** Scans the local network for Ollama servers and stores the results. */
    fun scanNetwork() {
        if (transient.value.scanning) return
        viewModelScope.launch {
            transient.value = transient.value.copy(scanning = true, scanResults = emptyList(), testResult = null, testOk = null)
            val outcome = com.trucdecomptable.ollamachat.data.ollama.NetworkScanner.scanForOllama()
            val subnets = outcome.scannedSubnets.joinToString(", ")
            val message = when {
                outcome.results.isNotEmpty() ->
                    "${outcome.results.size} serveur(s) trouvé(s) — touchez pour sélectionner"
                subnets.isEmpty() ->
                    "Aucune interface réseau détectée — vérifie le WiFi / VPN"
                else ->
                    "Aucun serveur trouvé sur ${subnets} — essayez l'adresse manuelle"
            }
            transient.value = transient.value.copy(
                scanning = false,
                scanResults = outcome.results,
                testResult = message,
                testOk = null,
            )
        }
    }

    /** Applies a scan result: saves the URL and runs the connection test. */
    fun applyScanResult(result: com.trucdecomptable.ollamachat.data.ollama.NetworkScanner.ScanResult) {
        viewModelScope.launch { settings.setBaseUrl(result.baseUrl) }
        testConnection(result.baseUrl)
    }

    fun refreshModels() {
        viewModelScope.launch {
            val url = uiState.value.baseUrl
            if (url.isBlank()) return@launch
            container.ollamaClient.listModels(url).onSuccess { models ->
                transient.value = transient.value.copy(models = models)
            }
        }
    }

    fun changePin(oldPin: String, newPin: String) {
        if (!PinUtils.isValidPin(newPin)) {
            transient.value = transient.value.copy(pinMessage = "Le nouveau PIN doit avoir 4 chiffres")
            return
        }
        viewModelScope.launch {
            val currentHash = settings.pinHash.first()
            if (PinUtils.hash(oldPin) != currentHash) {
                transient.value = transient.value.copy(pinMessage = "Ancien PIN incorrect")
                return@launch
            }
            settings.setPinHash(PinUtils.hash(newPin))
            transient.value = transient.value.copy(pinMessage = "PIN modifié ✅")
        }
    }

    fun consumePinMessage() {
        transient.value = transient.value.copy(pinMessage = null)
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(container) as T
    }
}
