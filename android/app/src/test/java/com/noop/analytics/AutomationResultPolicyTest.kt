package com.noop.analytics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #92 automation-result gates. Same cases as Swift `AutomationResultPolicyTests`. */
class AutomationResultPolicyTest {

    @Test fun resultSuccessOncePerDayFailureOnceUntilSuccess() {
        val r = AutomationResultPolicy::shouldNotifyResult
        assertTrue(r(true, true, "2026-06-08", "2026-06-07", false))
        assertFalse(r(true, true, "2026-06-08", "2026-06-08", false))
        assertTrue(r(true, true, "2026-06-08", "2026-06-08", true))
        assertTrue(r(true, false, "2026-06-08", null, false))
        assertFalse(r(true, false, "2026-06-08", null, true))
        assertFalse(r(false, false, "2026-06-08", null, false))
    }

    @Test fun cycleReadingOnlyForANewSettledPhase() {
        val c = AutomationResultPolicy::shouldNotifyCycleReading
        assertTrue(c(true, "luteal", "follicular"))
        assertFalse(c(true, "luteal", "luteal"))
        assertFalse(c(true, "learning", null))
        assertFalse(c(true, "unknown", null))
        assertFalse(c(true, null, null))
        assertFalse(c(false, "luteal", null))
    }
}
