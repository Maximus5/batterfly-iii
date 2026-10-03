package io.github.maximus5.ffnotifyimporter.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionControllerTest {

    @Test
    fun `cutoff is N days before now`() {
        val oneDayMillis = RetentionController.MILLIS_PER_DAY
        val now = 10 * oneDayMillis

        assertEquals(10 * oneDayMillis, RetentionController.cutoffMillis(now, retentionDays = 0))
        assertEquals(7 * oneDayMillis, RetentionController.cutoffMillis(now, retentionDays = 3))
        assertEquals(3 * oneDayMillis, RetentionController.cutoffMillis(now, retentionDays = 7))
    }
}
