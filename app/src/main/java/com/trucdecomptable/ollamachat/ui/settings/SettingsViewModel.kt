package com.trucdecomptable.ollamachat.ui.settings

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trucdecomptable.ollamachat.AppContainer
import com.trucdecomptable.ollamachat.R
import com.trucdecomptable.ollamachat.data.backup.BackupArchive
import com.trucdecomptable.ollamachat.data.backup.BackupCrypto
import com.trucdecomptable.ollamachat.data.mcp.McpClient
import com.trucdecomptable.ollamachat.data.ollama.ModelInfo
import com.trucdecomptable.ollamachat.data.ollama.NetworkScanner
import com.trucdecomptable.ollamachat.data.prefs.McpServer
import com.trucdecomptable.ollamachat.data.repo.TurboProfile
import com.trucdecomptable.ollamachat.util.PinUtils
import com.trucdecomptable.ollamachat.ui.chat.UiMessage
import com.trucdecomptable.ollamachat.ui.chat.uiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val baseUrl: String = "",
    val model: String = "",
    val temperature: Double = 0.7,
    val topP: Double = 0.9,
    val topK: Int = 40,
    val numPredict: Int = 4096,
    val numCtx: Int = 16384,
    val keepAlive: String = "5m",
    val streaming: Boolean = true,
    val defaultSystemPrompt: String = "",
    val theme: String = "system",
    val lockEnabled: Boolean = false,
    val lockOnBackground: Boolean = true,
    val hasPin: Boolean = false,
    val braveApiKey: String = "",
    val contextCompactEnabled: Boolean = true,
    val mcpServers: List<McpServer> = emptyList(),
    val thinkEnabled: Boolean = false,
    val toolsEnabled: Boolean = true,
    val dynamicColor: Boolean = false,
    val turboEnabled: Boolean = false,
    val turboModel: String = "",
    val defaultEphemeralMinutes: Int = 0,
    val models: List<ModelInfo> = emptyList(),
    val testing: Boolean = false,
    val testResult: String? = null,
    val testOk: Boolean? = null,
    val pinMessage: UiMessage? = null,
    val scanning: Boolean = false,
    val scanResults: List<NetworkScanner.ScanResult> = emptyList(),
    val backupBusy: Boolean = false,
    val backupMessage: UiMessage? = null,
    val pullingModel: String? = null,
    val pullProgress: Float = 0f,
)

/** Transient (non-persisted) UI state merged into the settings snapshot. */
private data class Transient(
    val models: List<ModelInfo> = emptyList(),
    val testing: Boolean = false,
    val testResult: String? = null,
    val testOk: Boolean? = null,
    val pinMessage: UiMessage? = null,
    val scanning: Boolean = false,
    val scanResults: List<NetworkScanner.ScanResult> = emptyList(),
    val backupBusy: Boolean = false,
    val backupMessage: UiMessage? = null,
    val pullingModel: String? = null,
    val pullProgress: Float = 0f,
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
            hasPin = snap.hasPin,
            braveApiKey = snap.braveApiKey,
            contextCompactEnabled = snap.contextCompactEnabled,
            mcpServers = snap.mcpServers,
            thinkEnabled = snap.thinkEnabled,
            toolsEnabled = snap.toolsEnabled,
            dynamicColor = snap.dynamicColor,
            turboEnabled = snap.turboEnabled,
            turboModel = snap.turboModel,
            defaultEphemeralMinutes = snap.defaultEphemeralMinutes,
            models = tr.models,
            testing = tr.testing,
            testResult = tr.testResult,
            testOk = tr.testOk,
            pinMessage = tr.pinMessage,
            scanning = tr.scanning,
            scanResults = tr.scanResults,
            backupBusy = tr.backupBusy,
            backupMessage = tr.backupMessage,
            pullingModel = tr.pullingModel,
            pullProgress = tr.pullProgress,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    init {
        viewModelScope.launch {
            if (settings.baseUrl.first().isNotBlank()) refreshModels()
        }
    }

    fun onBaseUrlChange(v: String) {
        transient.update { it.copy(testResult = null, testOk = null) }
        viewModelScope.launch { settings.setBaseUrl(v) }
    }

    fun onModelChange(v: String) = launchSetting { settings.setModel(v) }
    fun onTemperatureChange(v: Double) = launchSetting { settings.setTemperature(v) }
    fun onTopPChange(v: Double) = launchSetting { settings.setTopP(v) }
    fun onTopKChange(v: Int) = launchSetting { settings.setTopK(v) }
    fun onNumPredictChange(v: Int) = launchSetting { settings.setNumPredict(v) }
    fun onNumCtxChange(v: Int) = launchSetting { settings.setNumCtx(v) }
    fun onKeepAliveChange(v: String) = launchSetting { settings.setKeepAlive(v) }
    fun onStreamingChange(v: Boolean) = launchSetting { settings.setStreaming(v) }
    fun onSystemPromptChange(v: String) = launchSetting { settings.setDefaultSystemPrompt(v) }
    fun onThemeChange(v: String) = launchSetting { settings.setTheme(v) }
    fun onLockOnBackgroundChange(v: Boolean) = launchSetting { settings.setLockOnBackground(v) }
    fun onBraveApiKeyChange(v: String) = launchSetting { settings.setBraveApiKey(v) }
    fun onContextCompactEnabledChange(v: Boolean) = launchSetting { settings.setContextCompactEnabled(v) }
    fun onThinkEnabledChange(v: Boolean) = launchSetting { settings.setThinkEnabled(v) }
    fun onToolsEnabledChange(v: Boolean) = launchSetting { settings.setToolsEnabled(v) }
    fun onDynamicColorChange(v: Boolean) = launchSetting { settings.setDynamicColor(v) }
    fun onTurboModelChange(v: String) = launchSetting { settings.setTurboModel(v.trim()) }
    fun onDefaultEphemeralChange(v: Int) = launchSetting { settings.setDefaultEphemeralMinutes(v) }
    fun onFirstLaunchDone() = launchSetting { settings.setFirstLaunchDone(true) }

    /** Enabling turbo also warms the model up, so the next message skips the load. */
    fun onTurboEnabledChange(enabled: Boolean) {
        viewModelScope.launch {
            settings.setTurboEnabled(enabled)
            if (!enabled) return@launch
            val url = settings.baseUrl.first()
            val model = settings.turboModel.first().ifBlank { settings.model.first() }
            if (url.isNotBlank() && model.isNotBlank()) {
                container.ollamaClient.warmUp(url, model, TurboProfile.KEEP_ALIVE)
            }
        }
    }

    /** Turning the lock on without a code would leave the app effectively open. */
    fun onLockEnabledChange(v: Boolean) {
        viewModelScope.launch {
            if (v && settings.pinHash.first().isBlank()) {
                transient.update { it.copy(pinMessage = UiMessage(R.string.settings_lock_needs_pin)) }
                return@launch
            }
            settings.setLockEnabled(v)
        }
    }

    fun addMcpServer(name: String, url: String) {
        val n = name.trim()
        val u = url.trim()
        if (n.isEmpty() || u.isEmpty()) return
        viewModelScope.launch {
            settings.setMcpServers(settings.mcpServers.first() + McpServer(n, u))
            // Sessions and tool lists are cached per server: a changed list
            // must not keep answering from the old one.
            McpClient.invalidate()
        }
    }

    fun removeMcpServer(server: McpServer) {
        viewModelScope.launch {
            settings.setMcpServers(settings.mcpServers.first().filter { it != server })
            McpClient.invalidate(server.url)
        }
    }

    fun testConnection(urlFromField: String) {
        val url = urlFromField.trim()
        if (url.isEmpty()) {
            transient.update { it.copy(testResult = null, testOk = false) }
            return
        }
        viewModelScope.launch {
            transient.update { it.copy(testing = true, testResult = null, testOk = null) }
            val result = container.ollamaClient.testConnection(url)
            if (result.isSuccess) {
                val models = container.ollamaClient.listModels(url).getOrNull().orEmpty()
                transient.update {
                    it.copy(
                        testing = false,
                        models = models,
                        testResult = "${models.size}",
                        testOk = true,
                    )
                }
            } else {
                transient.update {
                    it.copy(
                        testing = false,
                        testResult = result.exceptionOrNull()?.message.orEmpty(),
                        testOk = false,
                    )
                }
            }
        }
    }

    /** Scans the local network for Ollama servers and stores the results. */
    fun scanNetwork() {
        if (transient.value.scanning) return
        viewModelScope.launch {
            transient.update {
                it.copy(scanning = true, scanResults = emptyList(), testResult = null, testOk = null)
            }
            val outcome = NetworkScanner.scanForOllama()
            transient.update {
                it.copy(scanning = false, scanResults = outcome.results, testOk = null)
            }
            if (outcome.results.size == 1) applyScanResult(outcome.results.first())
        }
    }

    /** Applies a scan result: saves the URL and runs the connection test. */
    fun applyScanResult(result: NetworkScanner.ScanResult) {
        viewModelScope.launch { settings.setBaseUrl(result.baseUrl) }
        testConnection(result.baseUrl)
    }

    fun refreshModels() {
        viewModelScope.launch {
            val url = settings.baseUrl.first()
            if (url.isBlank()) return@launch
            container.ollamaClient.listModels(url).onSuccess { models ->
                transient.update { it.copy(models = models) }
            }
        }
    }

    /**
     * Sets or replaces the PIN. [oldPin] is only checked when one already
     * exists, so the first code can be chosen without knowing a default.
     */
    fun changePin(oldPin: String, newPin: String, confirmPin: String) {
        if (!PinUtils.isValidPin(newPin)) {
            pinMessage(R.string.pin_error_invalid)
            return
        }
        if (newPin != confirmPin) {
            pinMessage(R.string.pin_error_mismatch)
            return
        }
        viewModelScope.launch {
            val currentHash = settings.pinHash.first()
            if (currentHash.isNotBlank() && !PinUtils.verify(oldPin, currentHash)) {
                pinMessage(R.string.pin_error_wrong_old)
                return@launch
            }
            settings.setPinHash(PinUtils.hash(newPin))
            settings.clearFailedUnlocks()
            pinMessage(R.string.pin_changed)
        }
    }

    fun consumePinMessage() = transient.update { it.copy(pinMessage = null) }

    // --- encrypted export / import ---

    fun exportBackup(context: Context, uri: Uri, passphrase: String) {
        if (transient.value.backupBusy) return
        viewModelScope.launch {
            transient.update { it.copy(backupBusy = true, backupMessage = UiMessage(R.string.backup_working)) }
            val message = try {
                val bytes = container.backupManager.export(passphrase.toCharArray())
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("flux indisponible")
                UiMessage(R.string.backup_exported)
            } catch (e: Exception) {
                uiMessage(R.string.backup_export_failed, e.message)
            }
            transient.update { it.copy(backupBusy = false, backupMessage = message) }
        }
    }

    fun importBackup(context: Context, uri: Uri, passphrase: String) {
        if (transient.value.backupBusy) return
        viewModelScope.launch {
            transient.update { it.copy(backupBusy = true, backupMessage = UiMessage(R.string.backup_working)) }
            val message = try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("fichier illisible")
                val result = container.backupManager.import(bytes, passphrase.toCharArray())
                uiMessage(R.string.backup_imported, result.conversations, result.messages)
            } catch (e: BackupCrypto.WrongPassphraseException) {
                uiMessage(R.string.backup_import_failed, e.message)
            } catch (e: BackupCrypto.NotABackupException) {
                uiMessage(R.string.backup_import_failed, e.message)
            } catch (e: BackupArchive.UnsupportedVersionException) {
                uiMessage(R.string.backup_import_failed, e.message)
            } catch (e: Exception) {
                uiMessage(R.string.backup_import_failed, e.message)
            }
            transient.update { it.copy(backupBusy = false, backupMessage = message) }
        }
    }

    fun consumeBackupMessage() = transient.update { it.copy(backupMessage = null) }

    // --- server-side model management ---

    fun pullModel(name: String) {
        val model = name.trim()
        if (model.isEmpty() || transient.value.pullingModel != null) return
        viewModelScope.launch {
            val url = settings.baseUrl.first()
            transient.update { it.copy(pullingModel = model, pullProgress = 0f, backupMessage = null) }
            val result = container.ollamaClient.pullModel(url, model) { progress ->
                transient.update { it.copy(pullProgress = progress.fraction) }
            }
            transient.update {
                it.copy(
                    pullingModel = null,
                    pullProgress = 0f,
                    backupMessage = if (result.isSuccess) uiMessage(R.string.models_pulled, model)
                    else uiMessage(R.string.models_pull_failed, result.exceptionOrNull()?.message),
                )
            }
            if (result.isSuccess) refreshModels()
        }
    }

    fun deleteModel(name: String) {
        viewModelScope.launch {
            val url = settings.baseUrl.first()
            val result = container.ollamaClient.deleteModel(url, name)
            transient.update {
                it.copy(
                    backupMessage = if (result.isSuccess) uiMessage(R.string.models_deleted, name)
                    else uiMessage(R.string.models_delete_failed, result.exceptionOrNull()?.message),
                )
            }
            if (result.isSuccess) {
                // A deleted model must not stay selected.
                if (settings.model.first() == name) settings.setModel("")
                refreshModels()
            }
        }
    }

    private fun pinMessage(@StringRes resId: Int) =
        transient.update { it.copy(pinMessage = UiMessage(resId)) }

    private fun launchSetting(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(container) as T
    }
}
