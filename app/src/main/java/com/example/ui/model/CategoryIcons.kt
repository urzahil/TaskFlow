package com.example.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

object CategoryIcons {
    val AVAILABLE_ICONS = listOf(
        "general" to Icons.Default.CalendarMonth,
        "work" to Icons.Default.Work,
        "personal" to Icons.Default.Person,
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

    fun getIcon(iconName: String): ImageVector {
        if (iconName == "health") return Icons.Default.Pets
        return AVAILABLE_ICONS.firstOrNull { it.first == iconName }?.second ?: Icons.Default.Bookmark
    }

    fun suggestIconForName(categoryName: String): String {
        val lower = categoryName.lowercase().trim()
        return when {
            lower.contains("work") || lower.contains("job") || lower.contains("office") -> "work"
            lower.contains("person") || lower.contains("me") || lower.contains("self") -> "personal"
            lower.contains("health") || lower.contains("med") || lower.contains("doctor") || lower.contains("spa") -> "health"
            lower.contains("fit") || lower.contains("gym") || lower.contains("workout") || lower.contains("sport") -> "fitness"
            lower.contains("home") || lower.contains("house") || lower.contains("chores") -> "home"
            lower.contains("study") || lower.contains("learn") || lower.contains("school") || lower.contains("book") -> "study"
            lower.contains("shop") || lower.contains("buy") || lower.contains("grocery") -> "shopping"
            lower.contains("money") || lower.contains("finance") || lower.contains("bank") || lower.contains("bill") -> "finance"
            lower.contains("travel") || lower.contains("trip") || lower.contains("flight") || lower.contains("vacation") -> "travel"
            lower.contains("pet") || lower.contains("dog") || lower.contains("cat") -> "pets"
            lower.contains("art") || lower.contains("design") || lower.contains("create") -> "creative"
            lower.contains("music") || lower.contains("song") -> "music"
            lower.contains("urgent") || lower.contains("goal") || lower.contains("flag") -> "flag"
            lower.contains("star") || lower.contains("important") -> "star"
            lower.contains("heart") || lower.contains("love") -> "heart"
            lower.contains("idea") -> "idea"
            else -> "general"
        }
    }
}
