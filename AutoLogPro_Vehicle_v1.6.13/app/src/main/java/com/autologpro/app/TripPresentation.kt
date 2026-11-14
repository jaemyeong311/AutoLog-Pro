package com.autologpro.app

/** Show short real-world trips in seconds instead of truncating every trip under a minute to 0분. */
internal object TripPresentation {
    fun duration(startAtMillis: Long, endAtMillis: Long?): String {
        if (endAtMillis == null) return "진행 중"
        val seconds = ((endAtMillis - startAtMillis).coerceAtLeast(0L)) / 1000L
        if (seconds < 60L) return "${seconds}초"
        val minutes = seconds / 60L
        val remainder = seconds % 60L
        return if (remainder == 0L) "${minutes}분" else "${minutes}분 ${remainder}초"
    }
}
