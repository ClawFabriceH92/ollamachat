package com.trucdecomptable.ollamachat

import android.app.Application
import com.trucdecomptable.ollamachat.data.db.AppDatabase
import com.trucdecomptable.ollamachat.data.db.ImageStore
import com.trucdecomptable.ollamachat.data.ollama.OllamaClient
import com.trucdecomptable.ollamachat.data.prefs.SettingsRepository
import com.trucdecomptable.ollamachat.data.repo.ChatRepository
import com.trucdecomptable.ollamachat.service.GenerationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OllamaChatApp : Application() {

    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // One-time maintenance: pull pre-v1.3 inline images out of the database.
        scope.launch { ImageStore.migrateLegacyImages(this@OllamaChatApp, container.database) }

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
    val database: AppDatabase = AppDatabase.get(app)
    val settings: SettingsRepository = SettingsRepository(app)
    val ollamaClient: OllamaClient = OllamaClient()
    val chatRepository: ChatRepository = ChatRepository(database, settings, ollamaClient)
}
