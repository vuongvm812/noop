package com.noop.ui

import com.noop.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The no-capture toast must not name a remedy the strap cannot reach (#1635).
 *
 * A live-HR-only 5/MG has no puffin channel and so no history sync, and the old copy told those users to
 * "let a 5/MG history sync run" - waiting for something that never arrives. Same failure mode as every
 * other confidently-wrong line this issue produced, just in a Toast. The copy now lives in string
 * resources, so the pure selector returns the resource id and the test pins WHICH message is chosen.
 */
class NoCaptureMsgTest {
    @Test
    fun `a live-HR-only strap is told the sync is unreachable, not pending`() {
        val res = LogExport.noCaptureMsgRes(
            whoop5Connected = true, captureEnabled = true, encryptedBond = false, sharingLog = false,
        )
        assertEquals(R.string.l10n_log_export_raw_capture_is_on_but_this_8e358ddd, res)
        assertNotEquals(R.string.l10n_log_export_raw_capture_is_on_let_a_f03d5686, res)
    }

    @Test
    fun `a properly bonded strap is still told to let a sync run`() {
        val res = LogExport.noCaptureMsgRes(
            whoop5Connected = true, captureEnabled = true, encryptedBond = true, sharingLog = false,
        )
        assertEquals(R.string.l10n_log_export_raw_capture_is_on_let_a_f03d5686, res)
    }

    @Test
    fun `turning the toggle on comes first, even when unbonded`() {
        // Ordering matters: with capture off, "turn it on" is the actionable step regardless of bond.
        val res = LogExport.noCaptureMsgRes(
            whoop5Connected = true, captureEnabled = false, encryptedBond = false, sharingLog = false,
        )
        assertEquals(R.string.l10n_log_export_no_raw_capture_yet_turn_on_fce08452, res)
    }

    @Test
    fun `a WHOOP 4 is never told about pairing at all`() {
        val res = LogExport.noCaptureMsgRes(
            whoop5Connected = false, captureEnabled = true, encryptedBond = false, sharingLog = true,
        )
        assertEquals(R.string.l10n_log_export_raw_capture_records_whoop_5_mg_faa25108, res)
    }
}
