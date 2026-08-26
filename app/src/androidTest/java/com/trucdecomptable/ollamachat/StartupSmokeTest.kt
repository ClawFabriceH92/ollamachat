package com.trucdecomptable.ollamachat

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trucdecomptable.ollamachat.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one thing only a real Android can answer: does the app actually start,
 * open its database and reach a usable screen?
 *
 * This is what makes an R8-minified build verifiable instead of hoped-for —
 * a stripped Room or PDFBox shows up here rather than on the user's phone.
 */
@RunWith(AndroidJUnit4::class)
class StartupSmokeTest {

    @Test
    fun theApplicationBuildsItsContainer() {
        val app = ApplicationProvider.getApplicationContext<OllamaChatApp>()
        assertNotNull(app.container.database)
        assertNotNull(app.container.settings)
        assertNotNull(app.container.ollamaClient)
        assertNotNull(app.container.chatRepository)
    }

    @Test
    fun theDatabaseOpensAndRoundTripsAConversation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.get(context)
        val id = db.conversationDao().insert(
            com.trucdecomptable.ollamachat.data.db.Conversation(title = "smoke")
        )
        db.messageDao().insert(
            com.trucdecomptable.ollamachat.data.db.Message(
                conversationId = id,
                role = "user",
                content = "bonjour",
            )
        )
        val messages = db.messageDao().listForConversation(id)
        assertEquals(1, messages.size)
        assertEquals("bonjour", messages.first().content)

        db.conversationDao().deleteById(id)
        assertTrue(db.messageDao().listForConversation(id).isEmpty())
    }

    @Test
    fun theMainActivityReachesResumed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertNotNull(activity) }
        }
    }
}
