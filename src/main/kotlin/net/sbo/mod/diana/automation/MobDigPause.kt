package net.sbo.mod.diana.automation

internal class MobDigPause(val spawnedAtNs: Long, val mobName: String = "") {
    private val trackedIds = mutableSetOf<Int>()
    private var detectedMob = false
    fun debugState(): String = "name=$mobName detected=$detectedMob tracked=$trackedIds"

    fun matchesName(name: String): Boolean =
        name.contains(mobName, ignoreCase = true) ||
            mobName == "Siamese Lynx" && name.contains("Bagheera", ignoreCase = true) ||
            mobName == "King Minos" && name.contains("Hits")

    fun update(nearbyIds: Set<Int>, aliveIds: Set<Int>, now: Long): Boolean {
        if (!detectedMob || now - spawnedAtNs < 1_000_000_000L) {
            trackedIds.addAll(nearbyIds)
            if (trackedIds.isNotEmpty()) detectedMob = true
        }
        trackedIds.retainAll(aliveIds)
        return !detectedMob || trackedIds.isNotEmpty() || now - spawnedAtNs < 1_000_000_000L
    }
}
