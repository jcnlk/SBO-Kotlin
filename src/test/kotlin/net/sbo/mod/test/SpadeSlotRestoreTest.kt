package net.sbo.mod.test

import net.sbo.mod.diana.automation.SpadeSlotRestore
import net.sbo.mod.diana.automation.SpadeSlotRestore.Action
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpadeSlotRestoreTest {
    @Test
    fun `rejected restore retains original slot and retries next tick`() {
        val restore = SpadeSlotRestore(0, 2, 10)
        assertEquals(Action.WAIT, restore.advance(2, 10))
        assertEquals(Action.RETRY, restore.advance(2, 11))
        assertEquals(Action.WAIT, restore.advance(2, 11))
        assertEquals(Action.RETRY, restore.advance(2, 12))
        assertEquals(Action.WAIT, restore.advance(0, 13))
        assertEquals(Action.COMPLETE, restore.advance(0, 14))
    }

    @Test
    fun `delayed rollback is retried before forgetting previous slot`() {
        val restore = SpadeSlotRestore(0, 2, 10)
        assertEquals(Action.RETRY, restore.advance(2, 11))
        assertEquals(Action.WAIT, restore.advance(0, 12))
        assertEquals(Action.RETRY, restore.advance(2, 13))
        assertEquals(Action.WAIT, restore.advance(0, 14))
        assertEquals(Action.COMPLETE, restore.advance(0, 15))
    }

    @Test
    fun `manual selection of another slot cancels restore`() {
        assertEquals(Action.CANCEL, SpadeSlotRestore(0, 2, 10).advance(5, 11))
    }
}
