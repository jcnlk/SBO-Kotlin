package net.sbo.mod.test

import net.sbo.mod.diana.automation.MobDigPause
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MobDigPauseTest {
    @Test
    fun `Bagheera nameplates bind to lynx spawn and release after both die`() {
        val pause = MobDigPause(0, "Siamese Lynx")
        val names = mapOf(
            1771 to "[Lv15]  Default Bagheera 2,500/2,500❤",
            1772 to "[Lv15]  Default Bagheera 2,500/2,500❤",
            1773 to "[Lv325]  Empyrean Gaia Construct 3.5M/3.5M❤"
        )
        val matched = names.filterValues(pause::matchesName).keys
        assertEquals(setOf(1771, 1772), matched)
        assertTrue(pause.matchesName("[Lv15] Siamese Lynx 2,500/2,500❤"))
        assertFalse(MobDigPause(0, "Gaia Construct").matchesName(names.getValue(1771)))
        assertTrue(pause.update(matched, names.keys, 500_000_000))
        assertTrue(pause.update(emptySet(), setOf(1772, 1773), 2_000_000_000))
        assertFalse(pause.update(emptySet(), setOf(1773), 3_000_000_000))
    }

    @Test
    fun `repeated mining stays paused while spawned mob lives`() {
        val pause = MobDigPause(0)
        assertTrue(pause.update(setOf(1), setOf(1), 0))
        for (second in 1L..30L) {
            assertTrue(pause.update(emptySet(), setOf(1), second * 1_000_000_000))
        }
        assertFalse(pause.update(emptySet(), emptySet(), 31_000_000_000))
    }

    @Test
    fun `waits for both lynxes but does not adopt later unrelated mobs`() {
        val pause = MobDigPause(0)
        assertTrue(pause.update(setOf(1), setOf(1), 0))
        assertTrue(pause.update(setOf(1, 2), setOf(1, 2), 500_000_000))
        assertTrue(pause.update(setOf(2, 3), setOf(2, 3), 2_000_000_000))
        assertFalse(pause.update(setOf(3), setOf(3), 3_000_000_000))
    }

    @Test
    fun `missing spawn packets never count as a kill`() {
        val pause = MobDigPause(0)
        assertTrue(pause.update(emptySet(), emptySet(), 10_000_000_000))
        assertTrue(pause.update(setOf(7), setOf(7), 11_000_000_000))
        assertFalse(pause.update(emptySet(), emptySet(), 12_000_000_000))
    }
}
