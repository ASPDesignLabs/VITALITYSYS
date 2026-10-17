package com.snakesan.vitalitysys.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val finalStatus: String? = null, // "SUCCESS", "CRITICAL_DELAY", "ABANDONED"
    // Which specific dose/hygiene task this alert was about (see
    // SysConfig.doseKey / hygieneKey); "" for NUTRIENT/HYDRATION, which have
    // no sub-items. Lets repeated alerts for the same item be graded as one
    // task. null only on rows written before v5 — those can't be chained, so
    // they're graded one alert at a time (see VitalityMath.buildTasks).
    val itemKey: String? = null
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
    suspend fun insertLog(log: SystemLog): Long

    // A pain event is saved the moment it arrives and its description is
    // filled in afterwards, so these two let the description screen update
    // (and de-duplicate) the row it belongs to instead of inserting another.
    @Query("UPDATE system_logs SET note = :note WHERE id = :id")
    suspend fun updateLogNote(id: Long, note: String)

    @Query("SELECT id FROM system_logs WHERE type = 'PAIN' AND timestamp = :timestamp AND value = :value LIMIT 1")
    suspend fun findPainLogId(timestamp: Long, value: Int): Long?

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

    // Newest alert row for one specific dose/hygiene task.
    @Query("SELECT * FROM notification_audit WHERE notificationId = :notifId AND itemKey = :itemKey ORDER BY id DESC LIMIT 1")
    suspend fun getAuditForItem(notifId: Int, itemKey: String): NotificationAudit?

    // Newest alert row for a protocol that has no sub-items (or a pre-v5 row).
    @Query("SELECT * FROM notification_audit WHERE notificationId = :notifId AND (itemKey IS NULL OR itemKey = '') ORDER BY id DESC LIMIT 1")
    suspend fun getAuditWithoutItem(notifId: Int): NotificationAudit?

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

    // Everything issued in a window, for the PDF export. The in-app card only
    // sees the latest 100 rows (getRecentAudits), so a long report built from
    // that could silently omit older alerts.
    @Query("SELECT * FROM notification_audit WHERE timestampIssued BETWEEN :startTime AND :endTime ORDER BY timestampIssued ASC")
    suspend fun getAuditsInRange(startTime: Long, endTime: Long): List<NotificationAudit>
}

// Serializes the find-then-insert in savePainLog. A pain event can be written
// by more than one handler at nearly the same moment (the message handler,
// then the screen that opens for it), and without this both could see "no row
// yet" and insert one each.
private val painLogLock = Mutex()

// Writes a pain event's level, keyed by the watch's own timestamp: a
// re-delivered message, or a second handler for the same event, reuses the
// existing row instead of logging it twice. Returns that row's id.
suspend fun SystemDao.savePainLog(level: Int, loggedAt: Long): Long = painLogLock.withLock {
    findPainLogId(loggedAt, level) ?: insertLog(
        SystemLog(timestamp = loggedAt, type = "PAIN", value = level, note = "No details provided")
    )
}

// The alert row that a completion/skip of this protocol (and, for itemized
// ones, this exact item) belongs to. An itemized completion only ever matches
// its own item's row — completing one medication must never close out another
// medication's open alert. Un-itemized completions match the newest keyless
// row, so e.g. a watch "ALL CLEAR" tap can't close out a real dose alert.
suspend fun SystemDao.latestAuditFor(protocolId: Int, itemKey: String): NotificationAudit? =
    if (itemKey.isNotEmpty()) getAuditForItem(protocolId, itemKey) else getAuditWithoutItem(protocolId)

// --- MIGRATIONS ---

// v4 -> v5: notification_audit gains a nullable itemKey so repeated alerts
// for the same dose/hygiene task can be graded as one task. Purely additive:
// no rows are touched or rewritten, and existing ones keep a null itemKey,
// which marks them as pre-v5 (graded one alert at a time).
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notification_audit ADD COLUMN itemKey TEXT")
    }
}

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
    version = 5
)
abstract class VitalityDatabase : RoomDatabase() {
    abstract fun systemDao(): SystemDao

    companion object {
        @Volatile private var INSTANCE: VitalityDatabase? = null

        fun getDatabase(context: Context): VitalityDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context, VitalityDatabase::class.java, "vitality_blackbox.db")
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    // Only pre-migration-tracked versions fall back to a
                    // destructive reset; v3 -> v4 -> v5 always go through
                    // the real migrations above so system_logs/audit
                    // history survives the upgrade.
                    .fallbackToDestructiveMigrationFrom(1, 2)
                    .build().also { INSTANCE = it }
            }
        }
    }
}