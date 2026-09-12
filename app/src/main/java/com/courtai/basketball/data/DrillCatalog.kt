package com.courtai.basketball.data

data class Drill(
    val id: String,
    val title: String,
    val description: String,
    val targetMakes: Int,
    val timeLimitSec: Int,
    val category: String
) {
    val meta: String
        get() = when {
            timeLimitSec > 0 && targetMakes > 0 -> "$targetMakes makes · ${timeLimitSec}s"
            targetMakes > 0 -> "$targetMakes makes · no time limit"
            timeLimitSec > 0 -> "Freestyle · ${timeLimitSec}s"
            else -> "Freestyle · open session"
        }
}

object DrillCatalog {
    val all = listOf(
        Drill("freestyle", "Freestyle", "Open shooting with auto make/miss tracking.", 0, 0, "Core"),
        Drill("free_throws", "Free Throws", "Stand at the line. Focus on routine and follow-through.", 10, 0, "Form"),
        Drill("catch_shoot", "Catch & Shoot", "Receive and release quickly from mid-range.", 15, 120, "Scoring"),
        Drill("corner_threes", "Corner Threes", "Work both corners. Track deep range accuracy.", 12, 180, "Range"),
        Drill("speed_shooting", "Speed Shooting", "As many quality looks as possible before time runs out.", 0, 60, "Conditioning"),
        Drill("around_world", "Around the World", "Hit spots around the arc in sequence.", 8, 0, "Challenge"),
        Drill("midrange_mix", "Mid-Range Mix", "Elbows and wings. Balance power and touch.", 20, 240, "Scoring"),
        Drill("clutch_closes", "Clutch Closer", "Last 30 seconds mindset. Make under pressure.", 5, 30, "Mental")
    )

    fun byId(id: String): Drill = all.firstOrNull { it.id == id } ?: all.first()
}

data class WorkoutPlan(
    val id: String,
    val title: String,
    val description: String,
    val blocks: List<Drill>
)

object WorkoutCatalog {
    val all = listOf(
        WorkoutPlan(
            id = "form_foundation",
            title = "Form Foundation",
            description = "Warm form work then free throws.",
            blocks = listOf(
                DrillCatalog.byId("freestyle"),
                DrillCatalog.byId("free_throws")
            )
        ),
        WorkoutPlan(
            id = "scorer_circuit",
            title = "Scorer Circuit",
            description = "Catch-and-shoot into corner threes.",
            blocks = listOf(
                DrillCatalog.byId("catch_shoot"),
                DrillCatalog.byId("corner_threes"),
                DrillCatalog.byId("clutch_closes")
            )
        ),
        WorkoutPlan(
            id = "conditioning_burn",
            title = "Conditioning Burn",
            description = "Speed shooting with mid-range volume.",
            blocks = listOf(
                DrillCatalog.byId("speed_shooting"),
                DrillCatalog.byId("midrange_mix")
            )
        )
    )
}
