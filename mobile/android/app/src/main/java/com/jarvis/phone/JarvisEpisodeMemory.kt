package com.jarvis.phone

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale
import kotlin.math.min

/**
 * Long-lived episodic memory for JARVIS BRAIN.
 *
 * Dialogues are stored in the app's private SQLite database instead of a tiny
 * SharedPreferences JSON ring buffer. Retrieval combines lexical relevance,
 * importance, recency and past accesses. This is intentionally local-only.
 */
class JarvisEpisodeMemory(context: Context) {

    data class Episode(
        val id: Long,
        val createdAt: Long,
        val userText: String,
        val assistantText: String,
        val importance: Double,
        val accessCount: Int
    )

    private val helper = Db(context.applicationContext)

    fun remember(
        userText: String,
        assistantText: String,
        createdAt: Long = System.currentTimeMillis()
    ) {
        val user = userText.trim().take(MAX_USER_CHARS)
        val assistant = assistantText.trim().take(MAX_ASSISTANT_CHARS)
        if (user.isBlank() || assistant.isBlank()) return

        val values = ContentValues().apply {
            put("created_at", createdAt)
            put("user_text", user)
            put("assistant_text", assistant)
            put("importance", importanceFor(user))
            put("access_count", 0)
            put("last_accessed_at", 0L)
        }
        helper.writableDatabase.insert("episodes", null, values)
        pruneIfNeeded()
    }

    fun recent(limit: Int = 20): List<Episode> {
        val safeLimit = limit.coerceIn(1, 100)
        val rows = mutableListOf<Episode>()
        helper.readableDatabase.query(
            "episodes",
            COLUMNS,
            null,
            null,
            null,
            null,
            "created_at DESC, id DESC",
            safeLimit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) rows += cursor.toEpisode()
        }
        rows.reverse()
        return rows
    }

    fun relevant(query: String, limit: Int = 8): List<Episode> {
        val queryTokens = tokens(query)
        if (queryTokens.isEmpty()) return recent(limit)

        val candidates = mutableListOf<Episode>()
        helper.readableDatabase.query(
            "episodes",
            COLUMNS,
            null,
            null,
            null,
            null,
            "created_at DESC, id DESC",
            RETRIEVAL_CANDIDATES.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) candidates += cursor.toEpisode()
        }

        val now = System.currentTimeMillis()
        val ranked = candidates.mapNotNull { episode ->
            val episodeTokens = tokens(episode.userText + " " + episode.assistantText)
            val overlap = queryTokens.count { it in episodeTokens }
            if (overlap == 0 && episode.importance < 0.88) return@mapNotNull null

            val ageMs = (now - episode.createdAt).coerceAtLeast(0L)
            val recency = when {
                ageMs < DAY_MS -> 2.0
                ageMs < 7L * DAY_MS -> 1.2
                ageMs < 30L * DAY_MS -> 0.7
                ageMs < 180L * DAY_MS -> 0.3
                else -> 0.0
            }
            val accessBonus = min(episode.accessCount, 12) * 0.08
            val score = overlap * 4.0 + episode.importance * 2.0 + recency + accessBonus
            episode to score
        }
            .sortedWith(
                compareByDescending<Pair<Episode, Double>> { it.second }
                    .thenByDescending { it.first.createdAt }
            )
            .take(limit.coerceIn(1, 20))
            .map { it.first }

        if (ranked.isNotEmpty()) touch(ranked.map { it.id })
        return ranked
    }

    fun count(): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM episodes", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    fun clear(): Int {
        val count = count()
        helper.writableDatabase.delete("episodes", null, null)
        return count
    }

    private fun touch(ids: List<Long>) {
        if (ids.isEmpty()) return
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            ids.forEach { id ->
                db.execSQL(
                    "UPDATE episodes SET access_count = access_count + 1, last_accessed_at = ? WHERE id = ?",
                    arrayOf(now, id)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun pruneIfNeeded() {
        val db = helper.writableDatabase
        val count = db.rawQuery("SELECT COUNT(*) FROM episodes", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        if (count <= MAX_EPISODES) return

        val removeCount = count - TARGET_AFTER_PRUNE
        db.execSQL(
            """
            DELETE FROM episodes
            WHERE id IN (
                SELECT id FROM episodes
                ORDER BY importance ASC, access_count ASC, created_at ASC
                LIMIT ?
            )
            """.trimIndent(),
            arrayOf(removeCount)
        )
    }

    private fun importanceFor(text: String): Double {
        val n = normalize(text)
        val high = listOf(
            "запомни", "это важно", "не забудь", "меня зовут", "я живу", "я работаю",
            "я учусь", "я люблю", "я не люблю", "я предпочитаю", "мне нравится",
            "мне не нравится", "у меня", "мой ", "моя ", "мое ", "моё ", "мои "
        )
        val low = listOf(
            "открой ", "включи ", "выключи ", "позвони ", "пауза", "следующий трек",
            "громкость", "фонарик"
        )
        return when {
            high.any { n.contains(it) } -> 0.95
            low.any { n.startsWith(it) || n.contains(it) } -> 0.30
            text.contains("?") -> 0.55
            else -> 0.65
        }
    }

    private fun android.database.Cursor.toEpisode(): Episode = Episode(
        id = getLong(getColumnIndexOrThrow("id")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        userText = getString(getColumnIndexOrThrow("user_text")).orEmpty(),
        assistantText = getString(getColumnIndexOrThrow("assistant_text")).orEmpty(),
        importance = getDouble(getColumnIndexOrThrow("importance")),
        accessCount = getInt(getColumnIndexOrThrow("access_count"))
    )

    private fun tokens(text: String): Set<String> =
        normalize(text)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .asSequence()
            .filter { it.length >= 3 }
            .filterNot { it in STOP_WORDS }
            .toSet()

    private fun normalize(text: String): String =
        text.lowercase(Locale("ru", "RU"))
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")
            .trim()

    private class Db(context: Context) :
        SQLiteOpenHelper(context, "jarvis_brain_memory.db", null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE episodes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at INTEGER NOT NULL,
                    user_text TEXT NOT NULL,
                    assistant_text TEXT NOT NULL,
                    importance REAL NOT NULL DEFAULT 0.5,
                    access_count INTEGER NOT NULL DEFAULT 0,
                    last_accessed_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_episodes_created ON episodes(created_at DESC)")
            db.execSQL("CREATE INDEX idx_episodes_importance ON episodes(importance DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Version 1. Future migrations must preserve user memories.
        }
    }

    companion object {
        private const val MAX_USER_CHARS = 4_000
        private const val MAX_ASSISTANT_CHARS = 8_000
        private const val RETRIEVAL_CANDIDATES = 1_000
        private const val MAX_EPISODES = 5_000
        private const val TARGET_AFTER_PRUNE = 4_500
        private const val DAY_MS = 24L * 60L * 60L * 1000L

        private val COLUMNS = arrayOf(
            "id", "created_at", "user_text", "assistant_text", "importance", "access_count"
        )

        private val STOP_WORDS = setOf(
            "что", "как", "это", "этот", "эта", "эти", "мне", "меня", "про", "для",
            "или", "так", "там", "тут", "где", "когда", "какой", "какая", "какие",
            "сейчас", "тебя", "твой", "твоя", "твои", "наш", "наша", "наши",
            "был", "была", "были", "есть", "будет"
        )
    }
}
