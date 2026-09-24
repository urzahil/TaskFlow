package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.drive.GoogleDriveBackupManager
import com.example.data.model.CategoryEntity
import com.example.data.model.TaskCompletionEntity
import com.example.data.model.TaskEntity
import com.example.data.repository.TaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
    private lateinit var repository: TaskRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        repository = TaskRepository(db.taskDao())
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
    fun testParseLegacyBackupWithoutCategoriesExtractsTaskCategories() {
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
        // Backward compatibility: extracts category from tasks and ensures default
        assertEquals(1, parsed.categories.size)
        assertEquals("Work", parsed.categories[0].name)
        assertTrue(parsed.categories[0].isDefault)
    }

    @Test
    fun testClearAndRestoreAllRemovesDefaultCategoriesAndSampleTasks() = runBlocking {
        // Seed default categories and sample tasks
        repository.seedInitialCategoriesIfEmpty()
        repository.seedInitialTasksIfEmpty()

        val initialCats = repository.allCategories.first()
        assertTrue(initialCats.size >= 7)
        val initialTasks = repository.allTasks.first()
        assertTrue(initialTasks.isNotEmpty())

        // Perform clear and restore with only custom backup data
        val restoredCategories = listOf(
            CategoryEntity(name = "ClientProjects", colorHex = 0xFFEF4444, iconName = "work", isDefault = true),
            CategoryEntity(name = "PersonalGrowth", colorHex = 0xFF8B5CF6, iconName = "study", isDefault = false)
        )
        val restoredTasks = listOf(
            TaskEntity(
                id = 100L,
                title = "Restored User Task",
                category = "ClientProjects",
                colorHex = 0xFFEF4444,
                startDate = "2026-09-24"
            )
        )

        repository.clearAndRestoreAll(restoredTasks, emptyList(), restoredCategories)

        val finalCats = repository.allCategories.first()
        assertEquals(2, finalCats.size)
        assertTrue(finalCats.any { it.name == "ClientProjects" })
        assertTrue(finalCats.any { it.name == "PersonalGrowth" })
        // Default categories like Fitness, Health, Home, etc. must not exist
        assertFalse(finalCats.any { it.name == "Fitness" })
        assertFalse(finalCats.any { it.name == "Health" })

        val finalTasks = repository.allTasks.first()
        assertEquals(1, finalTasks.size)
        assertEquals("Restored User Task", finalTasks[0].title)
        // Sample tasks like "Morning hydration & vitamins" must be gone
        assertFalse(finalTasks.any { it.title.contains("hydration") })
    }
}
