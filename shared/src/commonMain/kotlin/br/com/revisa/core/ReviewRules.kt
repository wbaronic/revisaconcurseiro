package br.com.revisa.core

enum class Grade { CORRECT, WRONG, DOUBT }

data class ReviewSchedule(
    val startedAtMillis: Long,
    val nextReviewAtMillis: Long,
    /** Number of due reviews started after the initial study session. */
    val reviewCount: Int
) {
    init {
        require(nextReviewAtMillis >= startedAtMillis) { "A próxima revisão não pode anteceder o início do estudo." }
        require(reviewCount >= 0) { "A quantidade de revisões não pode ser negativa." }
    }
}

object ReviewRules {
    const val DAY_MILLIS = 86_400_000L
    const val FIRST_REVIEW_DELAY_MILLIS = 7 * DAY_MILLIS
    const val NEXT_REVIEW_DELAY_MILLIS = 15 * DAY_MILLIS

    /**
     * Applies the same scoring rule in ordinary study and in card review.
     * Cards with a positive score belong in the review queue, highest first.
     */
    fun nextScore(current: Int, grade: Grade): Int {
        require(current >= 0) { "A pontuação atual não pode ser negativa." }
        val delta = when (grade) {
            Grade.CORRECT -> -1L
            Grade.WRONG -> 2L
            Grade.DOUBT -> 1L
        }
        return (current.toLong() + delta).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Call when a group session starts. An early extra session preserves the
     * due date; a due (or overdue) session schedules 15 days from this start.
     * Use server time and persist the returned value atomically on the server.
     */
    fun beginGroup(nowMillis: Long, previous: ReviewSchedule?): ReviewSchedule {
        if (previous != null && nowMillis < previous.nextReviewAtMillis) return previous

        val delay = if (previous == null) FIRST_REVIEW_DELAY_MILLIS else NEXT_REVIEW_DELAY_MILLIS
        require(nowMillis <= Long.MAX_VALUE - delay) { "A data informada está fora do intervalo permitido." }
        require(previous == null || previous.reviewCount < Int.MAX_VALUE) {
            "A quantidade de revisões está fora do intervalo permitido."
        }
        return ReviewSchedule(
            startedAtMillis = previous?.startedAtMillis ?: nowMillis,
            nextReviewAtMillis = nowMillis + delay,
            reviewCount = previous?.reviewCount?.plus(1) ?: 0
        )
    }
}
