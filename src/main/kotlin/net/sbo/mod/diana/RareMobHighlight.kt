package net.sbo.mod.diana

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.sbo.mod.SBOKotlin.mc
import net.sbo.mod.diana.DianaMobDetect.RareDianaMob
import net.sbo.mod.settings.categories.Diana
import net.sbo.mod.settings.categories.Customization
import net.sbo.mod.utils.accessors.isSboGlowing
import net.sbo.mod.utils.accessors.setSboGlowColor
import net.sbo.mod.utils.events.Register
import net.sbo.mod.utils.events.annotations.SboEvent
import net.sbo.mod.utils.events.impl.entity.EntityLoadEvent
import net.sbo.mod.utils.events.impl.entity.EntityUnloadEvent
import net.sbo.mod.utils.render.RenderUtils3D
import java.awt.Color
import java.util.HashMap
import java.util.concurrent.TimeUnit
//#if MC > 26.1
//$$ import net.minecraft.util.ARGB
//$$ import net.azureaaron.renderchest.api.CustomGlowCallback
//$$ import net.azureaaron.renderchest.api.GlowConstants
//#endif

/**
 * Highlights rare Diana mobs by making them glow and optionally rendering tracers.
 *
 * This object listens for entity load and unload events to track rare mobs.
 *
 * On 26.2, it uses the RenderChest library to make the glow happen.
 *
 * On 26.1.2, every 4th tick, it checks if the rare mobs should be glowing based on simple line of sight
 * visibility and settings, and updates their glow state accordingly with our in-house mixin.
 */
object RareMobHighlight {
    private val NAME_CHECK_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(1L)

    private val rareMobs = mutableMapOf<LivingEntity, RareDianaMob>()
    private val pendingMobs = mutableMapOf<LivingEntity, Long>()

    fun init() {
        Register.onTick(4) {
            val world = mc.level ?: return@onTick
            world.checkMobGlow()
        }

        // Adapted from https://github.com/hannibal002/SkyHanni/blob/5bbf2d1cc23a58553d7602a29d1a90f4eb94e3d7/src/main/java/at/hannibal2/skyhanni/mixins/hooks/RenderLivingEntityHelper.kt#L36

        //#if MC > 26.1
        //$$ CustomGlowCallback.EVENT.register { entity, state ->
        //$$    val type = rareMobs[entity] ?: return@register GlowConstants.NO_GLOW
        //$$    if (!Diana.HighlightRareMobs || entity.isInvisible) return@register GlowConstants.NO_GLOW
        //$$
        //$$    val color = ARGB.opaque(type.glowColor())
        //$$    if (Diana.HighlightDepthCheck) {
        //$$        color
        //$$    } else {
        //$$        // Vanilla outlines render through walls; RenderChest custom glow uses scene depth.
        //$$        state.outlineColor = color
        //$$        GlowConstants.NO_GLOW
        //$$    }
        //$$ }
        //#endif

        LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            if (!Diana.RareMobTracers) return@register

            val world = mc.level ?: return@register
            rareMobs.forEach { (mob, type) ->
                if (!mob.isAlive || mob.level() != world) return@forEach

                val color = Color(type.glowColor(), true)
                RenderUtils3D.drawTracer(
                    context,
                    mob.boundingBox.center,
                    floatArrayOf(color.red / 255f, color.green / 255f, color.blue / 255f),
                    2f,
                    color.alpha / 255f
                )
            }
        }
    }

    @SboEvent
    fun onEntityLoad(event: EntityLoadEvent) {
        val mob = event.entity
        if (mob is LivingEntity) {
            if (event.entity is Player && event.entity.uuid.version() == 4) return

            val rare = RareDianaMob.fromName(mob.name.string)
            if (rare != null) {
                rareMobs[mob] = rare
            } else {
                pendingMobs[mob] = System.nanoTime()
            }
        }
    }

    @SboEvent
    fun onEntityUnload(event: EntityUnloadEvent) {
        if (event.entity is LivingEntity) {
            pendingMobs.remove(event.entity)
            if (rareMobs.remove(event.entity) != null) {
                event.entity.isSboGlowing = false
            }
        }
    }

    private fun ClientLevel.checkMobGlow() {
        val pendingIterator = pendingMobs.entries.iterator()
        val now = System.nanoTime()

        while (pendingIterator.hasNext()) {
            val (mob, spawnTime) = pendingIterator.next()

            if (!mob.isAlive || mob.level() != this) {
                pendingIterator.remove()
                continue
            }

            val rare = RareDianaMob.fromName(mob.name.string)
            if (rare != null) {
                rareMobs[mob] = rare
                pendingIterator.remove()
            } else if (now - spawnTime > NAME_CHECK_TIMEOUT_NS) {
                pendingIterator.remove()
            }
        }

        val iterator = rareMobs.entries.iterator()
        while (iterator.hasNext()) {
            val (mob, type) = iterator.next()

            if (!mob.isAlive || mob.level() != this) {
                mob.isSboGlowing = false
                iterator.remove()
                continue
            }

            //#if MC < 26.2
            val isVisible = !mob.isInvisible &&
                (!Diana.HighlightDepthCheck || mc.player?.hasLineOfSight(mob) == true)
            if (Diana.HighlightRareMobs && isVisible) {
                mob.isSboGlowing = true
                mob.setSboGlowColor(Color(type.glowColor()))
            } else {
                mob.isSboGlowing = false
            }
            //#endif
        }
    }

    private fun RareDianaMob.glowColor(): Int = when (this) {
        RareDianaMob.KING -> Customization.KingMinosGlowColor
        RareDianaMob.INQ -> Customization.MinosInquisitorGlowColor
        RareDianaMob.MANTI -> Customization.ManticoreGlowColor
        RareDianaMob.SPHINX -> Customization.SphinxGlowColor
    }
}
