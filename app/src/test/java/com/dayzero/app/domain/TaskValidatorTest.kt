package com.dayzero.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskValidatorTest {
    private val now = 1_000_000L
    private fun d(title: String = "x", start: Long = now, deadline: Long = now + 60_000, dur: Int = 30) =
        TaskDraft(title, Priority.NORMAL, start, deadline, dur, null)

    @Test fun validDraft_hasNoErrors() = assertTrue(TaskValidator.validate(d(), now, true).isEmpty())

    @Test fun blankTitle_rejected() {
        assertEquals(listOf(TaskError.TITLE_EMPTY), TaskValidator.validate(d(title = "   "), now, true))
    }

    @Test fun longTitle_rejected() {
        assertTrue(TaskError.TITLE_TOO_LONG in TaskValidator.validate(d(title = "a".repeat(101)), now, true))
    }

    @Test fun deadlineMustBeAfterStart() {
        assertTrue(TaskError.DEADLINE_BEFORE_START in TaskValidator.validate(d(deadline = now), now, true))
    }

    @Test fun pastDeadline_onlyRejectedWhenRequired() {
        val past = d(start = now - 2_000, deadline = now - 1_000)
        assertTrue(TaskError.DEADLINE_IN_PAST in TaskValidator.validate(past, now, true))
        assertTrue(TaskValidator.validate(past, now, false).isEmpty())
    }

    @Test fun durationBounds() {
        assertTrue(TaskError.DURATION_INVALID in TaskValidator.validate(d(dur = 0), now, true))
        assertTrue(TaskError.DURATION_INVALID in TaskValidator.validate(d(dur = 1441), now, true))
        assertTrue(TaskValidator.validate(d(dur = 1440), now, true).isEmpty())
    }
}
