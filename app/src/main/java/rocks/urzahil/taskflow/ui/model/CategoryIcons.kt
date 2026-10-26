package rocks.urzahil.taskflow.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

object CategoryIcons {
    val AVAILABLE_ICONS = listOf(
        "general" to Icons.Default.CalendarMonth,
        "work" to Icons.Default.Work,
        "personal" to Icons.Default.Person,
        "health" to Icons.Default.MedicalServices,
        "pets" to Icons.Default.Pets,
        "fitness" to Icons.Default.FitnessCenter,
        "home" to Icons.Default.Home,
        "study" to Icons.Default.School,
        "shopping" to Icons.Default.ShoppingCart,
        "finance" to Icons.Default.Bookmark,
        "star" to Icons.Default.Star,
        "heart" to Icons.Default.Favorite,
        "idea" to Icons.Default.Lightbulb,
        "travel" to Icons.Default.Flight,
        "creative" to Icons.Default.Palette,
        "music" to Icons.Default.MusicNote,
        "flag" to Icons.Default.Flag
    )

    private val ICONS_BY_NAME = AVAILABLE_ICONS.toMap()

    val PALETTE_COLORS = listOf(
        0xFF3B82F6, // Blue
        0xFF2563EB, // Dark Blue
        0xFF0D9488, // Teal
        0xFF10B981, // Emerald
        0xFF059669, // Green
        0xFFF59E0B, // Amber
        0xFFEA580C, // Orange
        0xFFEF4444, // Red
        0xFFEC4899, // Pink
        0xFF8B5CF6, // Purple
        0xFF6366F1, // Indigo
        0xFF06B6D4  // Cyan
    )

    fun getIcon(iconName: String): ImageVector = ICONS_BY_NAME[iconName] ?: Icons.Default.Bookmark

    /** Keywords per icon, checked in order; the first icon with a matching word wins. */
    private val SUGGESTIONS = listOf(
        "fitness" to listOf("fitness", "gym", "workout", "sport", "exercise"),
        "work" to listOf("work", "job", "office", "career"),
        "personal" to listOf("personal", "person", "self", "me"),
        "health" to listOf("health", "medic", "doctor", "spa"),
        "home" to listOf("home", "house", "chore"),
        "study" to listOf("study", "learn", "school", "book"),
        "shopping" to listOf("shop", "buy", "grocer"),
        "finance" to listOf("money", "financ", "bank", "bill"),
        "travel" to listOf("travel", "trip", "flight", "vacation"),
        "pets" to listOf("pet", "dog", "cat"),
        "creative" to listOf("art", "design", "creat"),
        "music" to listOf("music", "song"),
        "flag" to listOf("urgent", "goal", "flag"),
        "star" to listOf("star", "important"),
        "heart" to listOf("heart", "love"),
        "idea" to listOf("idea")
    )

    /**
     * Picks an icon for a category name by matching whole words, so "Home" is not taken for
     * "me" and "Education" is not taken for "cat". Keywords of three letters or fewer must match
     * a word exactly (or its plural); longer ones match the start of a word.
     */
    fun suggestIconForName(categoryName: String): String {
        val words = categoryName.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        fun matches(word: String, keyword: String) =
            if (keyword.length <= 3) word == keyword || word == keyword + "s"
            else word.startsWith(keyword)
        return SUGGESTIONS.firstOrNull { (_, keywords) ->
            words.any { word -> keywords.any { matches(word, it) } }
        }?.first ?: "general"
    }
}
