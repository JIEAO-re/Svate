package com.immersive.ui.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// ============================================================
// Entities
// ============================================================

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val summary: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_auto_title") val isAutoTitle: Boolean = true,
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val position: Int = 0,
)

@Entity(
    tableName = "agent_memory",
    indices = [
        Index(value = ["target_app", "last_used"]),
        Index(value = ["goal_norm", "target_app"], unique = true),
    ],
)
data class AgentMemoryEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val goal: String,
    @ColumnInfo(name = "goal_norm") val goalNorm: String,
    @ColumnInfo(name = "target_app") val targetApp: String,
    @ColumnInfo(name = "steps_json") val stepsJson: String,
    @ColumnInfo(name = "success_count") val successCount: Int = 1,
    @ColumnInfo(name = "last_used") val lastUsed: Long = System.currentTimeMillis(),
)

// ============================================================
// DAOs
// ============================================================

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY created_at DESC")
    suspend fun allSessions(): List<SessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY position ASC")
    suspend fun forSession(sessionId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(msgs: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE session_id = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Dao
interface AgentMemoryDao {
    @Query(
        """
        SELECT * FROM agent_memory
        WHERE goal_norm = :goalNorm AND target_app = :targetApp
        LIMIT 1
        """,
    )
    suspend fun findByGoalAndApp(goalNorm: String, targetApp: String): AgentMemoryEntity?

    @Query(
        """
        SELECT * FROM agent_memory
        WHERE target_app = :targetApp
        ORDER BY last_used DESC
        LIMIT :limit
        """,
    )
    suspend fun findByTargetApp(targetApp: String, limit: Int = 100): List<AgentMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: AgentMemoryEntity)

    @Query(
        """
        DELETE FROM agent_memory
        WHERE rowId NOT IN (
          SELECT rowId FROM agent_memory
          ORDER BY last_used DESC
          LIMIT :maxRows
        )
        """,
    )
    suspend fun trimToMaxRows(maxRows: Int)
}

// ============================================================
// Database
// ============================================================

@Database(
    entities = [SessionEntity::class, MessageEntity::class, AgentMemoryEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun agentMemoryDao(): AgentMemoryDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_memory` (
                      `rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      `goal` TEXT NOT NULL,
                      `goal_norm` TEXT NOT NULL,
                      `target_app` TEXT NOT NULL,
                      `steps_json` TEXT NOT NULL,
                      `success_count` INTEGER NOT NULL,
                      `last_used` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_memory_target_app_last_used` ON `agent_memory` (`target_app`, `last_used`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_memory_goal_norm_target_app` ON `agent_memory` (`goal_norm`, `target_app`)",
                )
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "svate_db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
