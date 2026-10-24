package com.josh.learn

import kotlin.math.max
import kotlin.math.roundToInt

/** Simple SM-2 spaced repetition. Grades: AGAIN=1, HARD=3, GOOD=4, EASY=5. */
object Sm2 {
    enum class Grade(val q: Int, val label: String) { AGAIN(1, "Again"), HARD(3, "Hard"), GOOD(4, "Good"), EASY(5, "Easy") }
    const val DAY = 86_400_000L
    const val AGAIN_DELAY = 10 * 60_000L

    data class Result(val ef: Double, val intervalDays: Int, val reps: Int, val due: Long, val lapses: Int)

    fun next(ef: Double, intervalDays: Int, reps: Int, lapses: Int, grade: Grade, now: Long): Result {
        val q = grade.q
        val newEf = max(1.3, ef + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)))
        if (q < 3) return Result(newEf, 0, 0, now + AGAIN_DELAY, lapses + 1)
        val interval = when (reps) {
            0 -> if (grade == Grade.EASY) 2 else 1
            1 -> if (grade == Grade.HARD) 3 else if (grade == Grade.EASY) 8 else 6
            else -> {
                val base = intervalDays.coerceAtLeast(1) * (if (grade == Grade.HARD) 1.2 else newEf)
                (if (grade == Grade.EASY) base * 1.3 else base).roundToInt().coerceAtLeast(intervalDays + 1)
            }
        }
        return Result(newEf, interval, reps + 1, now + interval * DAY, lapses)
    }

    fun apply(c: Flashcard, grade: Grade, now: Long = System.currentTimeMillis()) {
        val r = next(c.ef, c.intervalDays, c.reps, c.lapses, grade, now)
        c.ef = r.ef; c.intervalDays = r.intervalDays; c.reps = r.reps; c.due = r.due; c.lapses = r.lapses
    }

    /** Short label like "10m", "1d", "6d", "3mo" for the grade buttons. */
    fun preview(c: Flashcard, grade: Grade): String {
        val r = next(c.ef, c.intervalDays, c.reps, c.lapses, grade, 0)
        return when {
            r.intervalDays == 0 -> "10m"
            r.intervalDays < 31 -> "${r.intervalDays}d"
            r.intervalDays < 365 -> "${r.intervalDays / 30}mo"
            else -> "%.1fy".format(r.intervalDays / 365.0)
        }
    }
}
