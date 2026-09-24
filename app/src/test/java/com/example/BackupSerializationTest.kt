package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.drive.GoogleDriveBackupManager
import com.example.data.model.CategoryEntity
import com.example.data.model.TaskCompletionEntity
import com.example.data.model.TaskEntity
import com.example.data.repository.TaskRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupSerializationTest {

    private lateinit var backupManager: GoogleDriveBackupManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val repository = TaskRepository(db.taskDao())
        backupManager = GoogleDriveBackupManager(context, repository)
    }

    @Test
    fun testExportAndParseBackupIncludesCustomCategories() {
        val tasks = listOf(
            TaskEntity(
                id = 1L,
                title = "Test Task",
                category = "CustomProjects",
                colorHex = 0xFF10B981,
                startDate = "2026-09-24"
            )
        )
        val completions = listOf(
            TaskCompletionEntity(
                taskId = 1L,
                date = "2026-09-24"
            )
        )
        val categories = listOf(
            CategoryEntity(name = "General", colorHex = 0xFF3B82F6, iconName = "general", isDefault = true),
            CategoryEntity(name = "CustomProjects", colorHex = 0xFF10B981, iconName = "work", isDefault = false),
            CategoryEntity(name = "SideHustle", colorHex = 0xFFF59E0B, iconName = "fitness", isDefault = false)
        )

        val json = backupManager.exportBackupJson(tasks, completions, categories)

        // Verify JSON contains categories
        assertTrue(json.contains("\"categories\""))
        assertTrue(json.contains("\"CustomProjects\""))
        assertTrue(json.contains("\"SideHustle\""))

        // Parse backup
        val parsed = backupManager.parseBackupJson(json)

        assertEquals(1, parsed.tasks.size)
        assertEquals("Test Task", parsed.tasks[0].title)
        assertEquals("CustomProjects", parsed.tasks[0].category)

        assertEquals(1, parsed.completions.size)
        assertEquals(1L, parsed.completions[0].taskId)

        assertEquals(3, parsed.categories.size)
        val customProjects = parsed.categories.firstOrNull { it.name == "CustomProjects" }
        assertTrue(customProjects != null)
        assertEquals(0xFF10B981, customProjects!!.colorHex)
        assertEquals("work", customProjects.iconName)
        assertFalse(customProjects.isDefault)

        val sideHustle = parsed.categories.firstOrNull { it.name == "SideHustle" }
        assertTrue(sideHustle != null)
        assertEquals(0xFFF59E0B, sideHustle!!.colorHex)
        assertEquals("fitness", sideHustle.iconName)
        assertFalse(sideHustle.isDefault)
    }

    @Test
    fun testParseLegacyBackupWithoutCategories() {
        val legacyJson = """
            {
                "version": 1,
                "app": "TaskFlow",
                "timestamp": 1695500000000,
                "tasks": [
                    {
                        "id": 10,
                        "title": "Legacy Task",
                        "description": "Legacy",
                        "category": "Work",
                        "priority": "High",
                        "colorHex": 4282098422,
                        "isRecurring": false,
                        "recurrenceDays": 1,
                        "startDate": "2026-09-24",
                        "createdAt": 1695500000000
                    }
                ],
                "completions": []
            }
        """.trimIndent()

        val parsed = backupManager.parseBackupJson(legacyJson)
        assertEquals(1, parsed.tasks.size)
        assertEquals("Legacy Task", parsed.tasks[0].title)
        assertTrue(parsed.categories.isEmpty())
    }
}
