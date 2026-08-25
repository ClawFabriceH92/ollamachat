package com.trucdecomptable.ollamachat

import android.app.Application
import com.trucdecomptable.ollamachat.data.db.AppDatabase
import com.trucdecomptable.ollamachat.data.ollama.OllamaClient
import com.trucdecomptable.ollamachat.data.prefs.SettingsRepository
import com.trucdecomptable.ollamachat.data.repo.ChatRepository

class OllamaChatApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Simple manual DI container — no Hilt, keeps the build light. */
class AppContainer(app: Application) {
    val database: AppDatabase = AppDatabase.get(app)
    val settings: SettingsRepository = SettingsRepository(app)
    val ollamaClient: OllamaClient = OllamaClient()
    val chatRepository: ChatRepository = ChatRepository(database, settings, ollamaClient)
}
