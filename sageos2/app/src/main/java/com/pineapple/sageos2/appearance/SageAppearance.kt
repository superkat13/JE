package com.pineapple.sageos2.appearance

enum class SageAppearanceMode(val storedValue: String) {
    DARK("dark"),
    DIM("dim"),
    BLACK("black");

    fun next(): SageAppearanceMode = when (this) {
        DARK -> DIM
        DIM -> BLACK
        BLACK -> DARK
    }

    fun displayName(): String = name.lowercase().replaceFirstChar(Char::uppercase)

    companion object {
        fun fromStored(value: String?): SageAppearanceMode = entries
            .firstOrNull { it.storedValue == value }
            ?: DARK
    }
}

data class SageAppearanceSnapshot(
    val mode: SageAppearanceMode,
    val backgroundUri: String,
    val backgroundIntensity: Int
)
