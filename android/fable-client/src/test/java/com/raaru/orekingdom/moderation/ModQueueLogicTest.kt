package com.raaru.orekingdom.moderation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModQueueLogicTest {

    private fun snapshot() = ModSnapshotUi(
        body = "b", authorId = "u_1", channelId = "ch_general", createdAt = "2026-11-26T17:00:00Z",
    )

    @Test
    fun actionLadderWireValuesAreExact() {
        assertEquals(
            listOf("content_removed", "slowmode", "mute", "suspension", "ban"),
            ModAction.values().map { it.wire },
        )
        assertEquals(
            listOf(ModAction.RemoveContent, ModAction.Slowmode, ModAction.Mute, ModAction.Suspend, ModAction.Ban),
            ModAction.values().toList(),
        )
    }

    @Test
    fun noteRequiredForMuteAndAbove() {
        assertFalse(ModAction.RemoveContent.noteRequired)
        assertFalse(ModAction.Slowmode.noteRequired)
        assertTrue(ModAction.Mute.noteRequired)
        assertTrue(ModAction.Suspend.noteRequired)
        assertTrue(ModAction.Ban.noteRequired)
    }

    @Test
    fun pinnedReasonsAreScamAndIllegalOnly() {
        assertEquals(2, ModQueueCopy.PINNED_REASONS.size)
        assertTrue(ModQueueCopy.PINNED_REASONS.contains(ReportReason.Scam.wire))
        assertTrue(ModQueueCopy.PINNED_REASONS.contains(ReportReason.Illegal.wire))
    }

    @Test
    fun mutePresetsPairLabelsAndDurations() {
        assertEquals(listOf("1h", "24h", "7d"), ModQueueCopy.MUTE_PRESETS.map { it.first })
        assertEquals(listOf(3_600L, 86_400L, 604_800L), ModQueueCopy.MUTE_PRESETS.map { it.second })
    }

    @Test
    fun outcomesAreUpheldReducedOverturnedInOrder() {
        assertEquals(
            listOf(AppealState.Upheld, AppealState.Reduced, AppealState.Overturned),
            ModQueueCopy.OUTCOMES.map { it.first },
        )
    }

    @Test
    fun appealDefaultsToPending() {
        assertEquals(AppealState.Pending, ModAppealUi("s", "u_mod").state)
    }

    @Test
    fun queueStateDefaultsToLoadingAndEmpty() {
        val ui = ModQueueUiState(viewerId = "u_mod", modLoft = true)
        assertEquals(ModQueueStatus.Loading, ui.status)
        assertTrue(ui.items.isEmpty())
    }

    @Test
    fun snapshotContextDefaultsEmpty() {
        assertTrue(snapshot().context.isEmpty())
    }

    @Test
    fun itemDefaultsHaveNoClaimantAndNoAppeal() {
        val item = ModQueueItemUi("r_1", "open", "scam", 1, snapshot())
        assertNull(item.claimedBy)
        assertNull(item.appeal)
    }

    @Test
    fun reasonLabelMapsEveryWireAndFallsBackForUnknown() {
        assertEquals("Spam", reasonLabel("spam"))
        assertEquals("Abuse", reasonLabel("abuse"))
        assertEquals("Scam", reasonLabel("scam"))
        assertEquals("Illegal", reasonLabel("illegal"))
        assertEquals("Other", reasonLabel("other"))
        assertEquals(ModQueueCopy.REASON_UNKNOWN, reasonLabel("bogus"))
        assertEquals("Not specified", ModQueueCopy.REASON_UNKNOWN)
    }

    @Test
    fun reportReasonWireLabelPairsAreExact() {
        assertEquals(
            listOf("spam" to "Spam", "abuse" to "Abuse", "scam" to "Scam", "illegal" to "Illegal", "other" to "Other"),
            ReportReason.values().map { it.wire to it.label },
        )
    }

    @Test
    fun reportTargetKindWiresAreExact() {
        assertEquals(
            listOf("message", "thread", "profile", "spotlight", "commercial_post"),
            ReportTargetKind.values().map { it.wire },
        )
    }
}
