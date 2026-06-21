package net.sbo.mod.diana.automation

import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.world.InteractionHand
import net.sbo.mod.SBOKotlin
import net.sbo.mod.settings.categories.Diana
import net.sbo.mod.utils.events.annotations.SboEvent
import net.sbo.mod.utils.events.impl.diana.DianaTargetDetectedEvent
import net.sbo.mod.utils.events.impl.diana.DianaTargetSource
import net.sbo.mod.utils.events.impl.game.PlayerInteractEvent
import net.sbo.mod.utils.events.impl.game.TickEvent
import net.sbo.mod.utils.events.impl.game.WorldChangeEvent
import net.sbo.mod.utils.game.World
import net.sbo.mod.utils.math.SboVec
import net.sbo.mod.utils.waypoint.WaypointManager
import java.time.Duration

object DianaAutomation {
    private val arrowGuessSettleTime = Duration.ofMillis(100)
    private val preciseGuessSettleTime = Duration.ofMillis(250)
    private val targetLifetime = Duration.ofSeconds(3)
    private val spadeResultTimeout = Duration.ofMillis(1200)

    private data class PendingTarget(
        val source: DianaTargetSource,
        val pos: SboVec,
        val updatedAtNs: Long
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
    private var lastWarpedTarget: WarpedTarget? = null

    @SboEvent
    fun onTargetDetected(event: DianaTargetDetectedEvent) {
        if (World.getWorld() != "Hub") return
        if (Diana.autoWarp && pendingTarget?.source == DianaTargetSource.RARE_MOB &&
            event.source != DianaTargetSource.RARE_MOB) return
        if (event.source == DianaTargetSource.ARROW_GUESS && event.pos == null) {
            handleFailedArrowGuess()
            return
        }
        if (!Diana.autoWarp) return

        when (event.source) {
            DianaTargetSource.RARE_MOB -> {
                spadeFallback = null
                lastAirSpadeUseNs = 0L
                queueTarget(event.source, event.pos ?: return)
            }
            DianaTargetSource.ARROW_GUESS -> handleArrowGuess(event)
            DianaTargetSource.SPADE_GUESS -> handleSpadeGuess(event.pos ?: return)
        }
    }

    private fun handleArrowGuess(event: DianaTargetDetectedEvent) {
        queueTarget(DianaTargetSource.ARROW_GUESS, event.pos ?: return)
    }

    private fun handleFailedArrowGuess() {
        if (!Diana.autoUseSpade || !Diana.spadeGuess) return
        if (useSpade() && Diana.autoWarp) {
            spadeFallback = SpadeFallback(System.nanoTime())
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
        pendingTarget = PendingTarget(source, pos, now)
    }

    @SboEvent
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action != "useItem") return
        if (!event.player.mainHandItem.hoverName.string.contains("Spade")) return
        lastAirSpadeUseNs = System.nanoTime()
    }

    @SboEvent
    fun onTick(event: TickEvent) {
        if (!Diana.autoWarp || World.getWorld() != "Hub") {
            pendingTarget = null
            spadeFallback = null
            return
        }

        val now = System.nanoTime()
        handleSpadeTimeout(now)

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
        if (now - target.updatedAtNs < settleTime.toNanos()) return

        val lastWarp = lastWarpedTarget
        if (lastWarp?.source == target.source && lastWarp.pos == target.pos &&
            now - lastWarp.warpedAtNs <= targetLifetime.toNanos()) {
            pendingTarget = null
            spadeFallback = null
            return
        }
        if (WaypointManager.tryWarp) return

        when (target.source) {
            DianaTargetSource.RARE_MOB -> WaypointManager.warpToInq()
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
        if (World.getWorld() != "Hub") return false

        val now = System.nanoTime()
        if (now - lastAutoUseSpadeNs < Duration.ofMillis(750).toNanos()) return false

        val client = SBOKotlin.mc
        if (client.screen != null) return false
        val player = client.player ?: return false
        val gameMode = client.gameMode ?: return false
        val inventory = player.inventory
        val spadeSlot = (0..8).firstOrNull { slot ->
            val stack = inventory.getItem(slot)
            !stack.isEmpty && stack.hoverName.string.contains("Spade")
        } ?: return false

        if (inventory.selectedSlot != spadeSlot) {
            inventory.setSelectedSlot(spadeSlot)
            player.connection.send(ServerboundSetCarriedItemPacket(spadeSlot))
        }

        lastAutoUseSpadeNs = now
        lastAirSpadeUseNs = now
        gameMode.useItem(player, InteractionHand.MAIN_HAND)
        player.swing(InteractionHand.MAIN_HAND)
        return true
    }

    @SboEvent
    fun onWorldChange(event: WorldChangeEvent) {
        pendingTarget = null
        spadeFallback = null
        lastAirSpadeUseNs = 0L
        lastWarpedTarget = null
    }
}
