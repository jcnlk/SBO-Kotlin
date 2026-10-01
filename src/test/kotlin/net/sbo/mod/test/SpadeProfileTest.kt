package net.sbo.mod.test

import net.sbo.mod.diana.automation.SpadeProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpadeProfileTest {
    @Test
    fun `each spade uses its own length and cooldown`() {
        assertEquals(SpadeProfile(4, 3_000_000_000), SpadeProfile.fromItem("ANCESTRAL_SPADE", "Ancestral Spade", emptyList()))
        assertEquals(SpadeProfile(6, 2_000_000_000), SpadeProfile.fromItem("ARCHAIC_SPADE", "Archaic Spade", emptyList()))
        assertEquals(SpadeProfile(8, 1_000_000_000), SpadeProfile.fromItem("DEIFIC_SPADE", "Deific Spade", emptyList()))
    }

    @Test
    fun `erudite bonus is not doubled when tooltip already includes it`() {
        for (displayed in listOf(8, 10)) {
            assertEquals(SpadeProfile(10, 1_000_000_000), SpadeProfile.fromItem(
                "DEIFIC_SPADE", "§6Erudite Deific Spade",
                listOf("§7Maximum Burrow Chain Length: §a$displayed", "§7Cooldown: §a1s")
            ))
        }
    }

    @Test
    fun `recognizes reforged name without id and updated tooltip cooldown`() {
        assertEquals(SpadeProfile(8, 1_500_000_000), SpadeProfile.fromItem(
            "", "Erudite Archaic Spade", listOf("Cooldown: 1.5s")
        ))
    }

    @Test
    fun `does not choose arbitrary items named spade`() {
        assertNull(SpadeProfile.fromItem("OTHER_ITEM", "Deific Spade", emptyList()))
        assertNull(SpadeProfile.fromItem("", "Spade", emptyList()))
    }
}
