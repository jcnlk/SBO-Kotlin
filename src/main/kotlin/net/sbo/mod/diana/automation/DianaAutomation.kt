package net.sbo.mod.diana.automation

import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.core.BlockPos
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.sbo.mod.SBOKotlin
import net.sbo.mod.diana.DianaMobDetect
import net.sbo.mod.diana.burrows.BurrowDetector
import net.sbo.mod.settings.categories.Diana
import net.sbo.mod.utils.events.annotations.SboEvent
import net.sbo.mod.utils.events.impl.diana.DianaTargetDetectedEvent
import net.sbo.mod.utils.events.impl.diana.DianaTargetSource
import net.sbo.mod.utils.events.impl.game.PlayerInteractEvent
import net.sbo.mod.utils.events.impl.game.TickEvent
import net.sbo.mod.utils.events.impl.game.WorldChangeEvent
import net.sbo.mod.utils.game.World
import net.sbo.mod.utils.game.ItemLookup
import net.sbo.mod.utils.events.DianaEvents
import net.sbo.mod.utils.math.SboVec
import net.sbo.mod.utils.waypoint.WaypointManager
import java.time.Duration

object DianaAutomation {
    private val arrowGuessSettleTime = Duration.ofMillis(50)
    private val preciseGuessSettleTime = Duration.ofMillis(100)
    private val targetLifetime = Duration.ofSeconds(3)
    private val spadeResultTimeout = Duration.ofMillis(1200)

    private data class PendingTarget(
        val source: DianaTargetSource,
        val pos: SboVec,
        val updatedAtNs: Long,
        val stableSinceNs: Long = updatedAtNs
    )

    private data class SpadeFallback(
        val requestedAtNs: Long
    )

    private data class WarpedTarget(
        val source: DianaTargetSource,
        val pos: SboVec,
        val warpedAtNs: Long
    )

    private var pendingTarget: PendingTarget? = null
    private var spadeFallback: SpadeFallback? = null
    private var lastAirSpadeUseNs = 0L
    private var lastAutoUseSpadeNs = 0L
    private var spadeReadyAtNs = 0L
    private var lastWarpedTarget: WarpedTarget? = null
    private data class MiningSpadeSwitch(val block: BlockPos, val previousSlot: Int, val spadeSlot: Int)
    private var miningSpadeSwitch: MiningSpadeSwitch? = null
    private var pendingMiningCompletion: MiningSpadeSwitch? = null
    private var miningCycleBlock: BlockPos? = null
    private var automationTick = 0L
    private var pendingSlotRestore: SpadeSlotRestore? = null
    private val combatBurrows = mutableMapOf<BlockPos, MobDigPause>()

    @SboEvent
    fun onTargetDetected(event: DianaTargetDetectedEvent) {
        val client = SBOKotlin.mc
        val level = client.level ?: return
        client.execute {
            if (client.level === level) handleTargetDetected(event)
        }
    }

    private fun handleTargetDetected(event: DianaTargetDetectedEvent) {
        if (World.getWorld() != "Hub") return
        if (DianaMobDetect.hasActiveOwnRareMob()) {
            pendingTarget = null
            spadeFallback = null
            return
        }
        if (Diana.autoWarp && pendingTarget?.source == DianaTargetSource.RARE_MOB &&
            event.source != DianaTargetSource.RARE_MOB) return
        if (event.source == DianaTargetSource.ARROW_GUESS && event.pos == null) {
            if (Diana.autoWarp && WaypointManager.getBestGuess() != null) {
                BurrowDetector.pendingUseSpadeTitle = null
                onBurrowCompleted()
            } else {
                handleSpadeRequest()
            }
            return
        }
        if (!Diana.autoWarp) return

        when (event.source) {
            DianaTargetSource.RARE_MOB -> {
                spadeFallback = null
                lastAirSpadeUseNs = 0L
                queueTarget(event.source, event.pos ?: return)
            }
            DianaTargetSource.ARROW_GUESS -> queueTarget(event.source, event.pos ?: return)
            DianaTargetSource.SPADE_GUESS -> handleSpadeGuess(event.pos ?: return)
        }
        tryPendingWarp(System.nanoTime())
    }

    fun onSpadeRequested() {
        val client = SBOKotlin.mc
        val level = client.level ?: return
        client.execute {
            if (client.level !== level || World.getWorld() != "Hub") return@execute
            if (DianaMobDetect.hasActiveOwnRareMob() || WaypointManager.tryWarp) return@execute
            if (pendingTarget?.source == DianaTargetSource.RARE_MOB) return@execute
            if (BurrowDetector.pendingUseSpadeTitle != null) {
                if (Diana.autoWarp && WaypointManager.getBestGuess() != null) {
                    BurrowDetector.pendingUseSpadeTitle = null
                    onBurrowCompleted()
                } else {
                    handleSpadeRequest()
                }
            }
        }
    }

    fun onBurrowCompleted() {
        if (!Diana.autoWarp || World.getWorld() != "Hub") return
        if (DianaMobDetect.hasActiveOwnRareMob() || pendingTarget?.source == DianaTargetSource.RARE_MOB) return
        val next = WaypointManager.getBestGuess() ?: return
        queueTarget(DianaTargetSource.ARROW_GUESS, next.pos)
    }

    private fun handleSpadeRequest() {
        if (!Diana.autoUseSpade || !Diana.spadeGuess) return
        if (useSpade()) {
            pendingTarget = null
            spadeFallback = if (Diana.autoWarp) SpadeFallback(System.nanoTime()) else null
        }
    }

    private fun handleSpadeGuess(pos: SboVec) {
        val now = System.nanoTime()
        val followsAirUse = now - lastAirSpadeUseNs <= targetLifetime.toNanos()
        if (spadeFallback == null && !followsAirUse) return

        spadeFallback = null
        queueTarget(DianaTargetSource.SPADE_GUESS, pos, now)
    }

    private fun queueTarget(source: DianaTargetSource, pos: SboVec, now: Long = System.nanoTime()) {
        val previous = pendingTarget
        pendingTarget = if (previous?.source == source && previous.pos == pos) {
            previous.copy(updatedAtNs = now)
        } else {
            PendingTarget(source, pos.copy(), now)
        }
    }

    @SboEvent
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action != "useItem" && event.action != "useBlock") return
        if (!event.player.mainHandItem.hoverName.string.contains("Spade")) return
        lastAirSpadeUseNs = System.nanoTime()
        if (event.action == "useItem") {
            val item = ItemLookup(event.player.mainHandItem)
            SpadeProfile.fromItem(item.sbId, item.displayName, item.loreList)?.let {
                spadeReadyAtNs = lastAirSpadeUseNs + it.cooldownNs
            }
        }
    }

    @SboEvent
    fun onTick(event: TickEvent) {
        automationTick++
        updateSlotRestore()
        if (SBOKotlin.mc.player != null && SpadeDiagnostics.snapshotDue()) {
            val player = SBOKotlin.mc.player!!
            val hit = SBOKotlin.mc.hitResult as? BlockHitResult
            val mobs = DianaMobDetect.livingMythologicalMobs().take(12).joinToString { "${it.id}:${it.customName?.string}@${it.blockPosition()}" }
            SpadeDiagnostics.log("STATE", "world=${World.getWorld()} attack=${SBOKotlin.mc.options.keyAttack.isDown} hit=${hit?.type}:${hit?.blockPos} slot=${player.inventory.selectedSlot} held=${ItemLookup(player.mainHandItem).sbId} autoSwitch=${Diana.autoSwitchSpade} autoUse=${Diana.autoUseSpade} mining=$miningSpadeSwitch completion=$pendingMiningCompletion cycle=$miningCycleBlock combat=${combatBurrows.mapValues { it.value.debugState() }} mobs=[$mobs]")
            if (SBOKotlin.mc.options.keyAttack.isDown || combatBurrows.isNotEmpty()) {
                val stands = SBOKotlin.mc.level?.entitiesForRendering()?.filterIsInstance<net.minecraft.world.entity.decoration.ArmorStand>()
                    ?.filter { it.customName != null && it.distanceToSqr(player) <= 24.0 * 24.0 }
                    ?.take(12)?.joinToString { "${it.id}:${it.customName?.string}@${it.blockPosition()} alive=${it.isAlive}" }
                SpadeDiagnostics.log("NEARBY_NAMEPLATES", stands.orEmpty())
            }
        }
        updateCombatBurrows()
        val completed = pendingMiningCompletion
        pendingMiningCompletion = null
        if (completed != null && completed === miningSpadeSwitch) {
            onBurrowDigConfirmed(SboVec(completed.block.x.toDouble(), completed.block.y.toDouble(), completed.block.z.toDouble()))
        }
        val hit = SBOKotlin.mc.hitResult
        val aimedBlock = (hit as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }?.blockPos
        if (!SBOKotlin.mc.options.keyAttack.isDown) miningCycleBlock = null
        if (aimedBlock != null && aimedBlock != miningCycleBlock) miningCycleBlock = null
        restoreMiningSlotIfNeeded(aimedBlock)
        if (World.getWorld() != "Hub" || DianaMobDetect.hasActiveOwnRareMob()) {
            pendingTarget = null
            spadeFallback = null
            return
        }

        if (BurrowDetector.pendingUseSpadeTitle != null &&
            pendingTarget?.source != DianaTargetSource.RARE_MOB && !WaypointManager.tryWarp) {
            onSpadeRequested()
        }

        if (!Diana.autoWarp) {
            pendingTarget = null
            spadeFallback = null
            return
        }

        val now = System.nanoTime()
        handleSpadeTimeout(now)
        tryPendingWarp(now)
    }

    private fun tryPendingWarp(now: Long) {
        val target = pendingTarget ?: return
        if (now - target.updatedAtNs > targetLifetime.toNanos()) {
            pendingTarget = null
            return
        }

        val settleTime = when (target.source) {
            DianaTargetSource.ARROW_GUESS -> arrowGuessSettleTime
            DianaTargetSource.SPADE_GUESS -> preciseGuessSettleTime
            DianaTargetSource.RARE_MOB -> Duration.ZERO
        }
        if (now - target.stableSinceNs < settleTime.toNanos()) return

        val lastWarp = lastWarpedTarget
        if (lastWarp?.source == target.source && lastWarp.pos == target.pos &&
            now - lastWarp.warpedAtNs <= targetLifetime.toNanos()) {
            pendingTarget = null
            spadeFallback = null
            return
        }
        if (WaypointManager.tryWarp) return

        when (target.source) {
            DianaTargetSource.RARE_MOB -> WaypointManager.warpToRareMob()
            DianaTargetSource.ARROW_GUESS,
            DianaTargetSource.SPADE_GUESS -> WaypointManager.warpToGuess()
        }
        pendingTarget = null
        spadeFallback = null

        if (WaypointManager.tryWarp) {
            lastWarpedTarget = WarpedTarget(target.source, target.pos, now)
        }
    }

    private fun handleSpadeTimeout(now: Long) {
        val fallback = spadeFallback ?: return
        if (now - fallback.requestedAtNs < spadeResultTimeout.toNanos()) return

        spadeFallback = null
    }

    private fun useSpade(): Boolean {
        if (pendingSlotRestore != null) return false
        if (World.getWorld() != "Hub") return false

        val now = System.nanoTime()
        if (now - lastAutoUseSpadeNs < Duration.ofMillis(750).toNanos()) return false
        if (now < spadeReadyAtNs) return false

        val client = SBOKotlin.mc
        if (SBOKotlin.mc.screen != null) return false
        val player = client.player ?: return false
        val gameMode = client.gameMode ?: return false
        val previousSlot = player.inventory.selectedSlot
        val miningSwitch = miningSpadeSwitch?.takeIf { it.spadeSlot == previousSlot }
        if (!selectBestSpade(player)) return false
        val abilitySlot = player.inventory.selectedSlot
        SpadeDiagnostics.log("ABILITY_SELECT", "previous=$previousSlot spade=$abilitySlot mining=$miningSwitch")
        miningSpadeSwitch = miningSwitch?.copy(spadeSlot = abilitySlot)

        try {
            lastAutoUseSpadeNs = now
            lastAirSpadeUseNs = now
            val pendingTitle = BurrowDetector.pendingUseSpadeTitle
            val previousReadyAtNs = spadeReadyAtNs
            if (gameMode.useItem(player, InteractionHand.MAIN_HAND) == InteractionResult.FAIL) {
                spadeReadyAtNs = previousReadyAtNs
                BurrowDetector.pendingUseSpadeTitle = pendingTitle
                return false
            }
            player.swing(InteractionHand.MAIN_HAND)
            return true
        } finally {
            if (miningSwitch == null && previousSlot != abilitySlot &&
                player.inventory.selectedSlot == abilitySlot) {
                queueSlotRestore(previousSlot, abilitySlot)
            }
        }
    }

    fun switchToSpadeForBurrow(pos: BlockPos) {
        SpadeDiagnostics.log("MINE_HOOK", "pos=$pos slot=${SBOKotlin.mc.player?.inventory?.selectedSlot} paused=${pos in combatBurrows} mining=$miningSpadeSwitch", true)
        restoreMiningSlotIfNeeded(pos)
        if (pendingSlotRestore != null) return
        if (pos in combatBurrows) {
            SpadeDiagnostics.log("MINE_BLOCKED_BY_MOB", "pos=$pos", true)
            restoreMiningSlotIfNeeded(null)
            return
        }
        if (!Diana.autoSwitchSpade || World.getWorld() != "Hub" || SBOKotlin.mc.screen != null) return
        if (SBOKotlin.mc.level?.getBlockState(pos)?.isAir != false) return
        val player = SBOKotlin.mc.player ?: return
        val held = ItemLookup(player.mainHandItem)
        if (SpadeProfile.fromItem(held.sbId, held.displayName, held.loreList) != null) return
        val target = SboVec(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
        val isBurrow = miningCycleBlock == pos || BurrowDetector.burrows.values.any { it.pos == target } ||
            listOf("burrow", "arrow", "guess", "subGuess").any {
                WaypointManager.getWaypointAt(target, it) != null
            }
        if (isBurrow) {
            val previousSlot = player.inventory.selectedSlot
            if (selectBestSpade(player)) {
                miningCycleBlock = pos.immutable()
                miningSpadeSwitch = MiningSpadeSwitch(pos.immutable(), previousSlot, player.inventory.selectedSlot)
                SpadeDiagnostics.log("MINING_SELECT", "$miningSpadeSwitch")
            }
        } else {
            SpadeDiagnostics.log("NOT_A_BURROW", "pos=$pos", true)
        }
    }

    private fun restoreMiningSlotIfNeeded(aimedBlock: BlockPos?) {
        val switched = miningSpadeSwitch ?: return
        val player = SBOKotlin.mc.player
        if (player == null || player.inventory.selectedSlot != switched.spadeSlot) {
            SpadeDiagnostics.log("RESTORE_CANCELLED", "selected=${player?.inventory?.selectedSlot} expected=$switched")
            miningSpadeSwitch = null
            return
        }
        if (Diana.autoSwitchSpade && aimedBlock == switched.block && World.getWorld() == "Hub") return
        miningSpadeSwitch = null
        SpadeDiagnostics.log("MINING_RESTORE", "aim=$aimedBlock switch=$switched")
        queueSlotRestore(switched.previousSlot, switched.spadeSlot)
    }

    private fun queueSlotRestore(previousSlot: Int, spadeSlot: Int) {
        if (previousSlot == spadeSlot || pendingSlotRestore != null) return
        pendingSlotRestore = SpadeSlotRestore(previousSlot, spadeSlot, automationTick)
        SpadeDiagnostics.log("RESTORE_QUEUED", "previous=$previousSlot spade=$spadeSlot tick=$automationTick")
    }

    private fun updateSlotRestore() {
        val restore = pendingSlotRestore ?: return
        val player = SBOKotlin.mc.player
        if (player == null) {
            pendingSlotRestore = null
            return
        }
        when (restore.advance(player.inventory.selectedSlot, automationTick)) {
            SpadeSlotRestore.Action.RETRY -> {
                player.inventory.setSelectedSlot(restore.previousSlot)
                player.connection.send(ServerboundSetCarriedItemPacket(restore.previousSlot))
                SpadeDiagnostics.log("RESTORE_ATTEMPT", "requested=${restore.previousSlot} actual=${player.inventory.selectedSlot} tick=$automationTick")
            }
            SpadeSlotRestore.Action.COMPLETE, SpadeSlotRestore.Action.CANCEL -> {
                SpadeDiagnostics.log("RESTORE_FINISHED", "requested=${restore.previousSlot} actual=${player.inventory.selectedSlot} tick=$automationTick")
                pendingSlotRestore = null
            }
            SpadeSlotRestore.Action.WAIT -> Unit
        }
    }

    fun onBurrowDigConfirmed(pos: SboVec) {
        SpadeDiagnostics.log("DIG_CONFIRM", "pos=$pos mining=$miningSpadeSwitch")
        val client = SBOKotlin.mc
        val level = client.level ?: return
        client.execute {
            if (client.level !== level) return@execute
            val switched = miningSpadeSwitch ?: return@execute
            val block = pos.roundLocationToBlock()
            if (switched.block.x.toDouble() != block.x || switched.block.y.toDouble() != block.y ||
                switched.block.z.toDouble() != block.z) return@execute
            restoreMiningSlotIfNeeded(null)
        }
    }

    fun onBurrowBlockBroken(pos: BlockPos) {
        SpadeDiagnostics.log("LOCAL_BREAK", "pos=$pos mining=$miningSpadeSwitch")
        pendingMiningCompletion = miningSpadeSwitch?.takeIf { it.block == pos }
    }

    fun onOwnMobSpawn(mob: String) {
        SpadeDiagnostics.log("OWN_MOB_SPAWN", "mob=$mob lastWaypoint=${DianaEvents.lastWaypointClicked} mining=$miningSpadeSwitch")
        val pos = DianaEvents.lastWaypointClicked ?: return
        val block = BlockPos(pos.x.toInt(), pos.y.toInt(), pos.z.toInt())
        combatBurrows[block] = MobDigPause(System.nanoTime(), mob.replace("Lynxes", "Lynx"))
        if (miningSpadeSwitch?.block == block) restoreMiningSlotIfNeeded(null)
        if (miningCycleBlock == block) miningCycleBlock = null
        updateCombatBurrows()
    }

    fun onMobBurrowCompleted(pos: SboVec) {
        SpadeDiagnostics.log("COMBAT_CLEAR_COMPLETED", "pos=$pos")
        combatBurrows.remove(BlockPos(pos.x.toInt(), pos.y.toInt(), pos.z.toInt()))
    }

    private fun updateCombatBurrows() {
        if (combatBurrows.isEmpty()) return
        val mobs = DianaMobDetect.livingMythologicalMobs()
        val aliveIds = mobs.mapTo(mutableSetOf()) { it.id }
        val now = System.nanoTime()
        combatBurrows.entries.removeAll { (pos, pause) ->
            val nearbyIds = mobs.filter {
                val name = it.customName?.string.orEmpty()
                pause.matchesName(name) && it.distanceToSqr(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5) <= 16.0 * 16.0
            }
                .mapTo(mutableSetOf()) { it.id }
            val expired = !pause.update(nearbyIds, aliveIds, now)
            if (expired) SpadeDiagnostics.log("COMBAT_CLEAR_DEAD", "pos=$pos ${pause.debugState()}")
            expired
        }
    }

    private fun selectBestSpade(player: LocalPlayer): Boolean {
        val inventory = player.inventory
        val spadeSlot = (0..8).mapNotNull { slot ->
            val stack = inventory.getItem(slot)
            if (stack.isEmpty) return@mapNotNull null
            val item = ItemLookup(stack)
            val profile = SpadeProfile.fromItem(item.sbId, item.displayName, item.loreList) ?: return@mapNotNull null
            slot to profile
        }.maxWithOrNull(compareBy<Pair<Int, SpadeProfile>> { it.second.chainLength }
            .thenBy { -it.second.cooldownNs }
            .thenBy { it.first == inventory.selectedSlot })?.first ?: return false

        if (inventory.selectedSlot != spadeSlot) {
            inventory.setSelectedSlot(spadeSlot)
            player.connection.send(ServerboundSetCarriedItemPacket(spadeSlot))
        }

        return inventory.selectedSlot == spadeSlot
    }

    @SboEvent
    fun onWorldChange(event: WorldChangeEvent) {
        SpadeDiagnostics.log("WORLD_RESET", "clearing mining and combat state")
        combatBurrows.clear()
        pendingSlotRestore = null
        pendingMiningCompletion = null
        miningCycleBlock = null
        miningSpadeSwitch = null
        BurrowDetector.pendingUseSpadeTitle = null
        pendingTarget = null
        spadeFallback = null
        lastAirSpadeUseNs = 0L
        spadeReadyAtNs = 0L
        lastWarpedTarget = null
    }
}
