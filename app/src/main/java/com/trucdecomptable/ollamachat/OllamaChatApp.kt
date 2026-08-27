package com.trucdecomptable.ollamachat

import android.app.Application
import com.trucdecomptable.ollamachat.data.backup.BackupManager
import com.trucdecomptable.ollamachat.data.db.AppDatabase
import com.trucdecomptable.ollamachat.data.db.ImageStore
import com.trucdecomptable.ollamachat.data.ollama.ConnectionMonitor
import com.trucdecomptable.ollamachat.data.ollama.OllamaClient
import com.trucdecomptable.ollamachat.data.prefs.SettingsRepository
import com.trucdecomptable.ollamachat.data.repo.ChatRepository
import com.trucdecomptable.ollamachat.service.GenerationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PURGE_INTERVAL_MS = 30_000L

class OllamaChatApp : Application() {

    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // One-time maintenance: pull pre-v1.3 inline images out of the database.
        scope.launch { ImageStore.migrateLegacyImages(this@OllamaChatApp, container.database) }

        // Ephemeral conversations are swept at start and then on a ticker.
        // Deletion therefore happens while the app runs, not in the
        // background — stated plainly in the settings rather than implied.
        scope.launch {
            while (true) {
                runCatching { container.chatRepository.purgeExpired() }
                delay(PURGE_INTERVAL_MS)
            }
        }

        // Hold a foreground service for as long as a model is generating, so
        // backgrounding the app does not cost the user their answer.
        scope.launch {
            container.chatRepository.isSendingFlow.collect { sending ->
                if (sending) GenerationService.start(this@OllamaChatApp)
                else GenerationService.stop(this@OllamaChatApp)
            }
        }
    }
}

/** Simple manual DI container — no Hilt, keeps the build light. */
class AppContainer(app: Application) {
    /** Outlives every ViewModel: the connection status is shared by all screens. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase = AppDatabase.get(app)
    val settings: SettingsRepository = SettingsRepository(app)
    val ollamaClient: OllamaClient = OllamaClient()
    val chatRepository: ChatRepository = ChatRepository(database, settings, ollamaClient)
    val backupManager: BackupManager = BackupManager(app, database, BuildConfig.VERSION_NAME)
    val connectionMonitor: ConnectionMonitor = ConnectionMonitor(settings.baseUrl, ollamaClient, scope)
}
