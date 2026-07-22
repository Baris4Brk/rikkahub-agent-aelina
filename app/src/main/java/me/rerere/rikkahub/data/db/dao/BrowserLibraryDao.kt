package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.BrowserBookmarkEntity
import me.rerere.rikkahub.data.db.entity.BrowserHistoryEntity

@Dao
abstract class BrowserLibraryDao {
    @Query("SELECT * FROM browser_bookmarks ORDER BY updated_at_ms DESC")
    abstract fun observeBookmarks(): Flow<List<BrowserBookmarkEntity>>

    @Query("SELECT * FROM browser_history ORDER BY visited_at_ms DESC LIMIT 2000")
    abstract fun observeHistory(): Flow<List<BrowserHistoryEntity>>

    @Query("SELECT * FROM browser_bookmarks WHERE normalized_url = :normalizedUrl LIMIT 1")
    abstract suspend fun findBookmark(normalizedUrl: String): BrowserBookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertBookmark(bookmark: BrowserBookmarkEntity): Long

    @Query("DELETE FROM browser_bookmarks WHERE normalized_url = :normalizedUrl")
    abstract suspend fun deleteBookmark(normalizedUrl: String)

    @Query("SELECT * FROM browser_history WHERE normalized_url = :normalizedUrl ORDER BY visited_at_ms DESC LIMIT 1")
    abstract suspend fun latestHistory(normalizedUrl: String): BrowserHistoryEntity?

    @Insert
    abstract suspend fun insertHistory(history: BrowserHistoryEntity): Long

    @Query("UPDATE browser_history SET url = :url, title = :title, visited_at_ms = :visitedAtMs WHERE id = :id")
    abstract suspend fun updateHistory(id: Long, url: String, title: String, visitedAtMs: Long)

    @Query("DELETE FROM browser_history WHERE id = :id")
    abstract suspend fun deleteHistory(id: Long)

    @Query("DELETE FROM browser_history")
    abstract suspend fun clearHistory()

    @Query("DELETE FROM browser_history WHERE id NOT IN (SELECT id FROM browser_history ORDER BY visited_at_ms DESC LIMIT 2000)")
    abstract suspend fun trimHistory()

    @Transaction
    open suspend fun recordHistory(history: BrowserHistoryEntity, dedupeWindowMs: Long = 30_000L) {
        val latest = latestHistory(history.normalizedUrl)
        if (latest != null && history.visitedAtMs - latest.visitedAtMs <= dedupeWindowMs) {
            updateHistory(latest.id, history.url, history.title, history.visitedAtMs)
        } else {
            insertHistory(history)
        }
        trimHistory()
    }
}
