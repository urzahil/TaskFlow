package rocks.urzahil.taskflow.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.JournalMode
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import rocks.urzahil.taskflow.data.model.CategoryEntity
import rocks.urzahil.taskflow.data.model.SubtaskCompletionEntity
import rocks.urzahil.taskflow.data.model.SubtaskEntity
import rocks.urzahil.taskflow.data.model.SubtaskExclusionEntity
import rocks.urzahil.taskflow.data.model.TaskCompletionEntity
import rocks.urzahil.taskflow.data.model.TaskEntity
import rocks.urzahil.taskflow.data.model.TaskOccurrenceExclusionEntity

@Database(
    entities = [
        TaskEntity::class,
        TaskCompletionEntity::class,
        CategoryEntity::class,
        TaskOccurrenceExclusionEntity::class,
        SubtaskEntity::class,
        SubtaskCompletionEntity::class,
        SubtaskExclusionEntity::class
    ],
    version = 7,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS categories (
                        name TEXT NOT NULL PRIMARY KEY,
                        colorHex INTEGER NOT NULL,
                        iconName TEXT NOT NULL,
                        isDefault INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_task_completions_date ON task_completions (date)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN recurrenceDaysOfWeek TEXT DEFAULT NULL"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS task_occurrence_exclusions (
                        taskId INTEGER NOT NULL,
                        date TEXT NOT NULL,
                        PRIMARY KEY (taskId, date)
                    )
                """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_task_occurrence_exclusions_date ON task_occurrence_exclusions (date)")
            }
        }

        /**
         * Adds foreign keys (ON DELETE CASCADE) from completions and occurrence exclusions to
         * tasks. Rows pointing at tasks that no longer exist are dropped during the copy.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE task_completions_new (
                        taskId INTEGER NOT NULL,
                        date TEXT NOT NULL,
                        completedAt INTEGER NOT NULL,
                        PRIMARY KEY (taskId, date),
                        FOREIGN KEY (taskId) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO task_completions_new (taskId, date, completedAt)
                    SELECT taskId, date, completedAt FROM task_completions
                    WHERE taskId IN (SELECT id FROM tasks)
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE task_completions")
                db.execSQL("ALTER TABLE task_completions_new RENAME TO task_completions")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_task_completions_date ON task_completions (date)"
                )

                db.execSQL(
                    """
                    CREATE TABLE task_occurrence_exclusions_new (
                        taskId INTEGER NOT NULL,
                        date TEXT NOT NULL,
                        PRIMARY KEY (taskId, date),
                        FOREIGN KEY (taskId) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO task_occurrence_exclusions_new (taskId, date)
                    SELECT taskId, date FROM task_occurrence_exclusions
                    WHERE taskId IN (SELECT id FROM tasks)
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE task_occurrence_exclusions")
                db.execSQL(
                    "ALTER TABLE task_occurrence_exclusions_new RENAME TO task_occurrence_exclusions"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_task_occurrence_exclusions_date ON task_occurrence_exclusions (date)"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN sharedSubtasks INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS subtasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        taskId INTEGER NOT NULL,
                        date TEXT,
                        title TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        FOREIGN KEY (taskId) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_subtasks_taskId ON subtasks (taskId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS subtask_completions (
                        subtaskId INTEGER NOT NULL,
                        date TEXT NOT NULL,
                        completedAt INTEGER NOT NULL,
                        PRIMARY KEY (subtaskId, date),
                        FOREIGN KEY (subtaskId) REFERENCES subtasks(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS subtask_exclusions (
                        subtaskId INTEGER NOT NULL,
                        date TEXT NOT NULL,
                        PRIMARY KEY (subtaskId, date),
                        FOREIGN KEY (subtaskId) REFERENCES subtasks(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "taskflow_database"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7
                    )
                    // Keep the database a single self-contained file (no -wal/-shm) so Auto Backup
                    // can never capture an inconsistent set of files.
                    .setJournalMode(JournalMode.TRUNCATE)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
