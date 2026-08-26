package com.trucdecomptable.ollamachat.data.db

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import com.trucdecomptable.ollamachat.util.DiagnosticLog

/**
 * Images live as files on app-private storage, never inside the database.
 *
 * A base64 photo is ~1.4x the file size and a single row bigger than SQLite's
 * 2 MB CursorWindow makes every read of that conversation throw, so the bytes
 * are kept on disk and only re-encoded when a request is actually sent.
 */
object ImageStore {

    /** Longest edge kept when re-encoding: enough for vision models, ~10x smaller. */
    const val MAX_DIMENSION = 1280
    private const val JPEG_QUALITY = 85
    private const val DIR = "images"

    fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** Downscales [bytes] and stores them; returns the absolute path, or null on failure. */
    fun save(context: Context, bytes: ByteArray): String? = try {
        val encoded = downscale(bytes)
        val file = File(dir(context), "${UUID.randomUUID()}.jpg")
        file.writeBytes(encoded)
        file.absolutePath
    } catch (_: Exception) {
        null
    }

    /** Stores [bytes] verbatim (already-processed images, e.g. from a backup). */
    fun saveBytes(context: Context, bytes: ByteArray, extension: String = "jpg"): String? = try {
        val file = File(dir(context), "${UUID.randomUUID()}.$extension")
        file.writeBytes(bytes)
        file.absolutePath
    } catch (_: Exception) {
        null
    }

    fun readBase64(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return try {
            val file = File(path)
            if (!file.exists()) null
            else Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        try {
            File(path).delete()
        } catch (_: Exception) {
            // Best effort — a leftover file is harmless.
        }
    }

    /**
     * Re-encodes an image so its longest edge is at most [MAX_DIMENSION].
     * Returns the original bytes when they cannot be decoded (e.g. an exotic
     * format the model may still understand).
     */
    fun downscale(bytes: ByteArray, maxDimension: Int = MAX_DIMENSION): ByteArray {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return bytes

            // Cheap power-of-two subsample first, exact scale afterwards.
            var sample = 1
            while (longest / (sample * 2) >= maxDimension) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return bytes

            val scaled = scaleTo(decoded, maxDimension)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            val result = out.toByteArray()
            if (result.isEmpty()) bytes else result
        } catch (_: Throwable) {
            // OutOfMemoryError included: fall back to the original bytes.
            bytes
        }
    }

    private fun scaleTo(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /**
     * Moves pre-v1.3 inline base64 images out of the database.
     *
     * The blob is read back in slices with `substr` — selecting the whole
     * column is exactly what blows past the CursorWindow on these rows.
     */
    suspend fun migrateLegacyImages(context: Context, database: AppDatabase) =
        withContext(Dispatchers.IO) {
            try {
                val db = database.openHelper.writableDatabase
                val ids = mutableListOf<Long>()
                db.query("SELECT id FROM messages WHERE imageBase64 IS NOT NULL AND imagePath IS NULL")
                    .use { cursor -> while (cursor.moveToNext()) ids.add(cursor.getLong(0)) }
                ids.forEach { id -> migrateOne(context, db, id) }
            } catch (e: Exception) {
                // Never let maintenance break app start.
                DiagnosticLog.record("images/migration", e)
            }
        }

    private fun migrateOne(context: Context, db: SupportSQLiteDatabase, id: Long) {
        try {
            val length = db.query(
                "SELECT length(imageBase64) FROM messages WHERE id = ?",
                arrayOf<Any>(id),
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            if (length <= 0) {
                db.execSQL("UPDATE messages SET imageBase64 = NULL WHERE id = ?", arrayOf<Any>(id))
                return
            }

            val chunk = 256 * 1024
            val builder = StringBuilder(length)
            var offset = 1 // SQLite substr is 1-indexed
            while (offset <= length) {
                val piece = db.query(
                    "SELECT substr(imageBase64, ?, ?) FROM messages WHERE id = ?",
                    arrayOf<Any>(offset, chunk, id),
                ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: break
                builder.append(piece)
                offset += chunk
            }

            val bytes = Base64.decode(builder.toString(), Base64.DEFAULT)
            val path = save(context, bytes)
            if (path != null) {
                db.execSQL(
                    "UPDATE messages SET imagePath = ?, imageBase64 = NULL WHERE id = ?",
                    arrayOf<Any>(path, id),
                )
            } else {
                db.execSQL("UPDATE messages SET imageBase64 = NULL WHERE id = ?", arrayOf<Any>(id))
            }
        } catch (_: Throwable) {
            try {
                // A row we cannot rescue is still a row we must stop crashing on.
                db.execSQL("UPDATE messages SET imageBase64 = NULL WHERE id = ?", arrayOf<Any>(id))
            } catch (_: Exception) {
            }
        }
    }
}
