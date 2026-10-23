package kr.ac.hit.networkprinterstudio

import org.junit.Assert.*
import org.junit.Test

class IppJobStatusTest {
    @Test fun abortedWithNoReasonIsStillAFailure() {
        val job = IppJobStatus.from(mapOf("job-state" to listOf(8), "job-state-reasons" to listOf("none"),
            "job-impressions-completed" to listOf(0)))
        assertTrue(job.terminal)
        assertTrue(job.hasError)
        assertEquals(0, job.impressionsCompleted)
        assertTrue(job.reasons.isEmpty())
    }

    @Test fun completedAndProcessingStoppedAreDifferent() {
        val completed = IppJobStatus.from(mapOf("job-state" to listOf(9),
            "job-state-reasons" to listOf("job-completed-successfully"), "job-impressions-completed" to listOf(1)))
        assertTrue(completed.terminal)
        assertFalse(completed.hasError)
        assertEquals(1, completed.impressionsCompleted)
        val stopped = IppJobStatus.from(mapOf("job-state" to listOf(6)))
        assertFalse(stopped.terminal)
        assertFalse(stopped.hasError)
    }

    @Test fun missingOrUnknownStateCannotBecomeSuccessOrAborted() {
        for (attrs in listOf(emptyMap(), mapOf("job-state" to listOf(0)), mapOf("job-state" to listOf("8")))) {
            assertTrue(runCatching { IppJobStatus.from(attrs) }.isFailure)
        }
    }

    @Test fun completedWithErrorsIsReportedAsSuch() {
        val job = IppJobStatus.from(mapOf("job-state" to listOf(9), "job-state-reasons" to listOf("completed-with-errors")))
        assertTrue(job.hasError)
        assertTrue(job.terminal)
    }
}
