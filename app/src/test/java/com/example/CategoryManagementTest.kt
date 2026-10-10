package com.example

import com.example.data.model.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryManagementTest {

    @Test
    fun testCategoryDefaults() {
        val general = CategoryEntity("General", 0xFF3B82F6, "general", isDefault = true)
        assertTrue(general.isDefault)
        assertEquals("General", general.name)

        val work = CategoryEntity("Work", 0xFF2563EB, "work", isDefault = false)
        assertFalse(work.isDefault)
    }

    @Test
    fun testCategoryListOperations() {
        val categories = mutableListOf(
            CategoryEntity("General", 0xFF3B82F6, "general", isDefault = true),
            CategoryEntity("Work", 0xFF2563EB, "work", isDefault = false)
        )

        // Adding a new custom category
        val custom = CategoryEntity("Finance", 0xFF0D9488, "finance", isDefault = false)
        categories.add(custom)
        assertEquals(3, categories.size)
        assertTrue(categories.any { it.name == "Finance" })

        // Deleting custom category
        categories.removeAll { it.name == "Finance" }
        assertEquals(2, categories.size)
        assertFalse(categories.any { it.name == "Finance" })
    }

    @Test
    fun testCategoryEditOperation() {
        val categories = mutableListOf(
            CategoryEntity("General", 0xFF3B82F6, "general", isDefault = true),
            CategoryEntity("Work", 0xFF2563EB, "work", isDefault = false)
        )

        // Editing category color and icon
        val index = categories.indexOfFirst { it.name == "Work" }
        val updated = categories[index].copy(
            colorHex = 0xFFEF4444,
            iconName = "fitness"
        )
        categories[index] = updated

        assertEquals(0xFFEF4444, categories.first { it.name == "Work" }.colorHex)
        assertEquals("fitness", categories.first { it.name == "Work" }.iconName)

        // Renaming category
        val renamed = categories[index].copy(name = "Career")
        categories[index] = renamed
        assertTrue(categories.any { it.name == "Career" })
        assertFalse(categories.any { it.name == "Work" })
    }
}
