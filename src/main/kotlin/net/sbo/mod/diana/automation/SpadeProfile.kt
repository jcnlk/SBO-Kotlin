package net.sbo.mod.diana.automation

internal data class SpadeProfile(val chainLength: Int, val cooldownNs: Long) {
    companion object {
        fun fromItem(id: String, name: String, lore: List<String>): SpadeProfile? {
            fun String.clean() = replace(Regex("§."), "")
            val cleanName = name.clean()
            val base = when {
                id == "DEIFIC_SPADE" || id.isEmpty() && cleanName.endsWith("Deific Spade") -> 8 to 1L
                id == "ARCHAIC_SPADE" || id.isEmpty() && cleanName.endsWith("Archaic Spade") -> 6 to 2L
                id == "ANCESTRAL_SPADE" || id.isEmpty() && cleanName.endsWith("Ancestral Spade") -> 4 to 3L
                else -> return null
            }
            val lines = lore.map { it.clean() }
            val erudite = cleanName.startsWith("Erudite ") || lines.any { it == "Erudite Bonus" }
            val displayedLength = lines.firstNotNullOfOrNull {
                Regex("Maximum Burrow Chain Length: (\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull()
            } ?: 0
            val length = maxOf(displayedLength, base.first + if (erudite) 2 else 0)
            val cooldown = lines.firstNotNullOfOrNull {
                Regex("Cooldown: ([0-9]+(?:\\.[0-9]+)?)s").find(it)?.groupValues?.get(1)?.toDoubleOrNull()
            }?.takeIf { it > 0 && it.isFinite() } ?: base.second.toDouble()
            return SpadeProfile(length, (cooldown * 1_000_000_000).toLong())
        }
    }
}
