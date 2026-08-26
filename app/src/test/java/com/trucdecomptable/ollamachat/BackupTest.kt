package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.backup.BackupArchive
import com.trucdecomptable.ollamachat.data.backup.BackupConversation
import com.trucdecomptable.ollamachat.data.backup.BackupCrypto
import com.trucdecomptable.ollamachat.data.backup.BackupMemory
import com.trucdecomptable.ollamachat.data.backup.BackupMessage
import com.trucdecomptable.ollamachat.data.backup.BackupPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    private val secret = "correct horse battery".toCharArray()

    @Test
    fun `a round trip returns the exact bytes`() {
        val plain = "conversations privées 🔒".toByteArray()
        val container = BackupCrypto.encrypt(plain, secret)
        assertArrayEquals(plain, BackupCrypto.decrypt(container, secret))
    }

    @Test
    fun `the plaintext never appears in the container`() {
        val plain = "un secret bien identifiable".toByteArray()
        val container = BackupCrypto.encrypt(plain, secret)
        assertFalse(String(container, Charsets.ISO_8859_1).contains("identifiable"))
    }

    @Test
    fun `the same input twice gives different containers`() {
        val plain = "idem".toByteArray()
        assertFalse(
            BackupCrypto.encrypt(plain, secret).contentEquals(BackupCrypto.encrypt(plain, secret))
        )
    }

    @Test
    fun `a wrong passphrase is rejected, not silently mangled`() {
        val container = BackupCrypto.encrypt("données".toByteArray(), secret)
        assertThrows(BackupCrypto.WrongPassphraseException::class.java) {
            BackupCrypto.decrypt(container, "mauvaise phrase".toCharArray())
        }
    }

    @Test
    fun `a tampered container is rejected`() {
        val container = BackupCrypto.encrypt("données".toByteArray(), secret)
        container[container.size - 1] = (container[container.size - 1] + 1).toByte()
        assertThrows(BackupCrypto.WrongPassphraseException::class.java) {
            BackupCrypto.decrypt(container, secret)
        }
    }

    @Test
    fun `a tampered salt is rejected too`() {
        val container = BackupCrypto.encrypt("données".toByteArray(), secret)
        container[6] = (container[6] + 1).toByte() // inside the salt
        assertThrows(BackupCrypto.WrongPassphraseException::class.java) {
            BackupCrypto.decrypt(container, secret)
        }
    }

    @Test
    fun `a foreign file is reported as not a backup`() {
        assertThrows(BackupCrypto.NotABackupException::class.java) {
            BackupCrypto.decrypt("juste du texte quelconque".toByteArray(), secret)
        }
        assertThrows(BackupCrypto.NotABackupException::class.java) {
            BackupCrypto.decrypt(ByteArray(3), secret)
        }
        assertFalse(BackupCrypto.isBackup("nope".toByteArray()))
        assertTrue(BackupCrypto.isBackup(BackupCrypto.encrypt(ByteArray(1), secret)))
    }
}

class BackupArchiveTest {

    private fun payload() = BackupPayload(
        exportedAt = 1_700_000_000_000,
        appVersion = "1.4.0",
        conversations = listOf(
            BackupConversation(
                ref = 7,
                title = "Recette",
                systemPrompt = null,
                model = "qwen3",
                createdAt = 1,
                updatedAt = 2,
                archived = true,
            )
        ),
        messages = listOf(
            BackupMessage(
                conversationRef = 7,
                role = "user",
                content = "Bonjour « accentué »",
                contentType = "image",
                imageNames = listOf("photo.jpg"),
                createdAt = 3,
                stats = null,
                thinking = "hmm",
                toolName = null,
                excludedFromContext = true,
            )
        ),
        memories = listOf(BackupMemory("aime le café", 4, 5)),
    )

    @Test
    fun `the archive round trips payload and images`() {
        val images = mapOf("photo.jpg" to byteArrayOf(1, 2, 3))
        val (back, restored) = BackupArchive.read(BackupArchive.write(payload(), images))

        assertEquals(1, back.conversations.size)
        assertEquals("Recette", back.conversations[0].title)
        assertTrue(back.conversations[0].archived)
        assertNull(back.conversations[0].systemPrompt)

        assertEquals("Bonjour « accentué »", back.messages[0].content)
        assertEquals(listOf("photo.jpg"), back.messages[0].imageNames)
        assertEquals("hmm", back.messages[0].thinking)
        assertTrue(back.messages[0].excludedFromContext)

        assertEquals("aime le café", back.memories[0].content)
        assertArrayEquals(byteArrayOf(1, 2, 3), restored["photo.jpg"])
    }

    @Test
    fun `an archive written before multi-image support still reads`() {
        // Older exports carry a single "imageName"; both shapes must decode.
        val json = BackupArchive.encode(payload())
            .replace("\"imageNames\":[\"photo.jpg\"]", "\"imageName\":\"photo.jpg\"")
        assertEquals(listOf("photo.jpg"), BackupArchive.decode(json).messages[0].imageNames)
    }

    @Test
    fun `a message with several images round trips`() {
        val many = payload().let { base ->
            base.copy(messages = listOf(base.messages[0].copy(imageNames = listOf("a.jpg", "b.jpg"))))
        }
        val (back, _) = BackupArchive.read(
            BackupArchive.write(many, mapOf("a.jpg" to byteArrayOf(1), "b.jpg" to byteArrayOf(2)))
        )
        assertEquals(listOf("a.jpg", "b.jpg"), back.messages[0].imageNames)
    }

    @Test
    fun `image entries cannot escape their folder`() {
        val raw = BackupArchive.write(
            payload(),
            mapOf("../../evil.jpg" to byteArrayOf(9)),
        )
        val (_, images) = BackupArchive.read(raw)
        assertEquals(setOf("evil.jpg"), images.keys)
    }

    @Test
    fun `a future format version is refused explicitly`() {
        val json = BackupArchive.encode(payload().copy(version = 99))
        val error = assertThrows(BackupArchive.UnsupportedVersionException::class.java) {
            BackupArchive.decode(json)
        }
        assertEquals(99, error.found)
    }

    @Test
    fun `garbage is refused as a corrupt archive`() {
        assertThrows(BackupArchive.CorruptArchiveException::class.java) {
            BackupArchive.read("pas un zip".toByteArray())
        }
    }
}
