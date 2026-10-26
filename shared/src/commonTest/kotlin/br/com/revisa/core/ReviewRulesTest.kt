package br.com.revisa.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ReviewRulesTest {
    @Test
    fun appliesTheConfirmedScoringRule() {
        assertEquals(7, ReviewRules.nextScore(5, Grade.WRONG))
        assertEquals(6, ReviewRules.nextScore(5, Grade.DOUBT))
        assertEquals(4, ReviewRules.nextScore(5, Grade.CORRECT))
    }

    @Test
    fun successiveMistakesInAnySessionKeepIncreasingTheScoreByTwo() {
        var score = 0
        repeat(3) { score = ReviewRules.nextScore(score, Grade.WRONG) }
        assertEquals(6, score)
        score = ReviewRules.nextScore(score, Grade.DOUBT)
        score = ReviewRules.nextScore(score, Grade.CORRECT)
        assertEquals(6, score)
    }

    @Test
    fun keepsScoresWithinTheirValidRange() {
        assertEquals(0, ReviewRules.nextScore(0, Grade.CORRECT))
        assertEquals(0, ReviewRules.nextScore(1, Grade.CORRECT))
        assertEquals(Int.MAX_VALUE, ReviewRules.nextScore(Int.MAX_VALUE, Grade.WRONG))
        assertFailsWith<IllegalArgumentException> { ReviewRules.nextScore(-1, Grade.WRONG) }
    }

    @Test
    fun firstStartSchedulesSevenDaysLater() {
        val now = 1_700_000_000_000L
        assertEquals(
            ReviewSchedule(now, now + 7 * ReviewRules.DAY_MILLIS, 0),
            ReviewRules.beginGroup(now, null)
        )
    }

    @Test
    fun startingEarlyDoesNotPostponeTheReview() {
        val original = ReviewRules.beginGroup(0, null)
        assertSame(original, ReviewRules.beginGroup(original.nextReviewAtMillis - 1, original))
        assertSame(original, ReviewRules.beginGroup(0, original))
    }

    @Test
    fun startingAtTheDueTimeSchedulesFifteenDaysLater() {
        val original = ReviewRules.beginGroup(0, null)
        val next = ReviewRules.beginGroup(original.nextReviewAtMillis, original)
        assertEquals(22 * ReviewRules.DAY_MILLIS, next.nextReviewAtMillis)
        assertEquals(1, next.reviewCount)
        assertEquals(0L, next.startedAtMillis)
    }

    @Test
    fun lateReviewUsesTheActualStartTimeThenKeepsTheFifteenDayCycle() {
        val original = ReviewRules.beginGroup(0, null)
        val lateStart = 12 * ReviewRules.DAY_MILLIS
        val next = ReviewRules.beginGroup(lateStart, original)
        assertEquals(27 * ReviewRules.DAY_MILLIS, next.nextReviewAtMillis)
        val third = ReviewRules.beginGroup(next.nextReviewAtMillis, next)
        assertEquals(42 * ReviewRules.DAY_MILLIS, third.nextReviewAtMillis)
        assertEquals(2, third.reviewCount)
    }

    @Test
    fun rejectsInvalidOrOverflowingDates() {
        assertFailsWith<IllegalArgumentException> { ReviewRules.beginGroup(Long.MAX_VALUE, null) }
        assertFailsWith<IllegalArgumentException> { ReviewSchedule(2, 1, 0) }
        assertFailsWith<IllegalArgumentException> { ReviewSchedule(0, 1, -1) }
    }
}
