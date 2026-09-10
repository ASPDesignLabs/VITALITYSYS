package com.snakesan.vitalitysys.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// --- ENTITIES ---

@Entity(tableName = "system_logs")
data class SystemLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val type: String,
    val value: Int,
    val note: String? = null
)

@Entity(tableName = "daily_stats")
data class DailyStats(
    @PrimaryKey val dayId: Int,
    val nutrientCount: Int = 0,
    val hydrationCount: Int = 0,
    // Comma-joined set of completed dose/hygiene-task keys for this day
    // (see SysConfig.doseKey / hygieneKey and encodeKeySet/decodeKeySet).
    // Replaces the old single medsTaken/hygieneDone booleans now that each
    // protocol can hold any number of user-defined reminders.
    val completedKeys: String = "",
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "notification_audit")
data class NotificationAudit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val notificationId: Int,
    val protocolType: String,
    val timestampIssued: Long,
    val timestampInteracted: Long? = null,
    val timestampFulfilled: Long? = null,
    val interactionType: String? = null, // "CLICKED", "DISMISSED", "IGNORED"
    val finalStatus: String? = null // "SUCCESS", "CRITICAL_DELAY", "ABANDONED"
)

data class ComplianceMetrics(
    val protocolType: String,
    val totalAlerts: Int,
    val successfulAlerts: Int,
    val ignoredAlerts: Int,
    val dismissedAlerts: Int
)

// --- DAO ---

@Dao
interface SystemDao {
    @Insert
    suspend fun insertLog(log: SystemLog)

    @Query("SELECT * FROM system_logs WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    fun getLogsInRange(startTime: Long, endTime: Long): Flow<List<SystemLog>>

    @Query("SELECT * FROM system_logs WHERE type = 'PAIN' AND timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    fun getPainLogs(startTime: Long, endTime: Long): Flow<List<SystemLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setDailyStats(stats: DailyStats)

    @Query("SELECT * FROM daily_stats WHERE dayId = :dayId LIMIT 1")
    suspend fun getDailyStats(dayId: Int): DailyStats?

    @Query("DELETE FROM system_logs WHERE timestamp >= :startTime")
    suspend fun deleteLogsSince(startTime: Long)

    @Query("DELETE FROM system_logs WHERE timestamp BETWEEN :startTime AND :endTime")
    suspend fun deleteLogsInWindow(startTime: Long, endTime: Long)

    @Query("DELETE FROM system_logs")
    suspend fun nukeAllLogs()

    // AUDIT DAO METHODS
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudit(audit: NotificationAudit): Long

    @Update
    suspend fun updateAudit(audit: NotificationAudit)

    @Query("SELECT * FROM notification_audit WHERE notificationId = :notifId ORDER BY id DESC LIMIT 1")
    suspend fun getAuditByNotificationId(notifId: Int): NotificationAudit?

    @Query("""
        SELECT protocolType, 
               COUNT(*) as totalAlerts,
               SUM(CASE WHEN finalStatus = 'SUCCESS' THEN 1 ELSE 0 END) as successfulAlerts,
               SUM(CASE WHEN interactionType = 'IGNORED' THEN 1 ELSE 0 END) as ignoredAlerts,
               SUM(CASE WHEN interactionType = 'DISMISSED' THEN 1 ELSE 0 END) as dismissedAlerts
        FROM notification_audit 
        GROUP BY protocolType
    """)
    fun getComplianceMetrics(): Flow<List<ComplianceMetrics>>

    @Query("SELECT * FROM notification_audit ORDER BY timestampIssued DESC LIMIT 100")
    fun getRecentAudits(): Flow<List<NotificationAudit>>
}

// --- MIGRATIONS ---

// v3 -> v4: daily_stats swaps its single medsTaken/hygieneDone booleans for
// a completedKeys column (a set of per-medication-dose / per-hygiene-task
// keys), since Chemistry and Maintenance now support any number of
// user-defined reminders instead of one fixed slot each. There's no
// meaningful way to map an old boolean onto specific new item keys (those
// items didn't exist yet), so historical completedKeys start blank; counts
// and timestamps carry over untouched, and system_logs / notification_audit
// (the actual history/audit trail) are left completely alone.
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS daily_stats_new (
                dayId INTEGER NOT NULL PRIMARY KEY,
                nutrientCount INTEGER NOT NULL DEFAULT 0,
                hydrationCount INTEGER NOT NULL DEFAULT 0,
                completedKeys TEXT NOT NULL DEFAULT '',
                lastUpdated INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO daily_stats_new (dayId, nutrientCount, hydrationCount, completedKeys, lastUpdated)
            SELECT dayId, nutrientCount, hydrationCount, '', lastUpdated FROM daily_stats
            """.trimIndent()
        )
        db.execSQL("DROP TABLE daily_stats")
        db.execSQL("ALTER TABLE daily_stats_new RENAME TO daily_stats")
    }
}

// --- DATABASE ---

@Database(
    entities = [SystemLog::class, DailyStats::class, NotificationAudit::class],
    version = 4
)
abstract class VitalityDatabase : RoomDatabase() {
    abstract fun systemDao(): SystemDao

    companion object {
        @Volatile private var INSTANCE: VitalityDatabase? = null

        fun getDatabase(context: Context): VitalityDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context, VitalityDatabase::class.java, "vitality_blackbox.db")
                    .addMigrations(MIGRATION_3_4)
                    // Only pre-migration-tracked versions fall back to a
                    // destructive reset; v3 -> v4 always goes through the
                    // real migration above so system_logs/audit history
                    // survives the upgrade.
                    .fallbackToDestructiveMigrationFrom(1, 2)
                    .build().also { INSTANCE = it }
            }
        }
    }
}