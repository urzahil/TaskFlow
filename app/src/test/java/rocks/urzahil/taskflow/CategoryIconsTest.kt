package rocks.urzahil.taskflow

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MedicalServices
import rocks.urzahil.taskflow.ui.model.CategoryIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryIconsTest {

    @Test
    fun suggestionsMatchWholeWords() {
        assertEquals("home", CategoryIcons.suggestIconForName("Home"))
        assertEquals("study", CategoryIcons.suggestIconForName("Education & Books"))
        assertEquals("work", CategoryIcons.suggestIconForName("Meetings at work"))
        assertEquals("personal", CategoryIcons.suggestIconForName("Me time"))
        assertEquals("pets", CategoryIcons.suggestIconForName("Cats"))
        assertEquals("general", CategoryIcons.suggestIconForName("Category"))
    }

    @Test
    fun suggestionsPreferFitnessOverWork() {
        assertEquals("fitness", CategoryIcons.suggestIconForName("Workout"))
        assertEquals("health", CategoryIcons.suggestIconForName("Medical"))
    }

    @Test
    fun everySuggestedIconIsPickable() {
        val pickable = CategoryIcons.AVAILABLE_ICONS.map { it.first }.toSet()
        listOf(
            "Workout", "Office", "Self", "Doctor", "House", "School", "Groceries", "Bank",
            "Trip", "Dog", "Design", "Songs", "Goals", "Important", "Love", "Ideas", "Misc"
        ).forEach { name ->
            val icon = CategoryIcons.suggestIconForName(name)
            assertTrue("$name suggested unknown icon $icon", icon in pickable)
        }
    }

    @Test
    fun healthHasItsOwnIcon() {
        assertEquals(Icons.Default.MedicalServices, CategoryIcons.getIcon("health"))
    }
}
