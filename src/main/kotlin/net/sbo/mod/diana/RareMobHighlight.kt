package net.sbo.mod.diana

import net.minecraft.client.multiplayer.ClientLevel
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.world.entity.player.Player
import net.sbo.mod.SBOKotlin.mc
import net.sbo.mod.diana.DianaMobDetect.RareDianaMob
import net.sbo.mod.settings.categories.Diana
import net.sbo.mod.utils.accessors.isSboGlowing
import net.sbo.mod.utils.accessors.setSboGlowColor
import net.sbo.mod.utils.events.Register
import net.sbo.mod.utils.events.annotations.SboEvent
import net.sbo.mod.utils.events.impl.entity.EntityLoadEvent
import net.sbo.mod.utils.events.impl.entity.EntityUnloadEvent
import net.sbo.mod.utils.render.RenderUtils3D
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap

/**
 * Highlights rare Diana mobs by making them glow and optionally rendering tracers.
 *
 * This object listens for entity load and unload events to track rare mobs.
 * On every 4th tick, it updates their glow state based on the current setting.
 */
object RareMobHighlight {
    private val rareMobs = ConcurrentHashMap.newKeySet<Player>()

    fun init() {
        Register.onTick(4) {
            val world = mc.level ?: return@onTick
            world.checkMobGlow()
        }
        WorldRenderEvents.BEFORE_TRANSLUCENT.register { context ->
            if (!Diana.RareMobTracers) return@register

            val world = mc.level ?: return@register
            val color = Color(Diana.HighlightColor, true)
            val colorComponents = floatArrayOf(
                color.red / 255f,
                color.green / 255f,
                color.blue / 255f
            )

            rareMobs.forEach { mob ->
                if (mob.isAlive && mob.level() == world) {
                    RenderUtils3D.drawTracer(
                        context,
                        mob.boundingBox.center,
                        colorComponents,
                        2f,
                        color.alpha / 255f
                    )
                }
            }
        }
    }

    @SboEvent
    fun onEntityLoad(event: EntityLoadEvent) {
        if (event.entity is Player) {
            if (event.entity.uuid.version() == 4) return
            if (RareDianaMob.entries.any { event.entity.name.string.contains(it.display, ignoreCase = true) }) {
                rareMobs.add(event.entity)
            }
        }
    }

    @SboEvent
    fun onEntityUnload(event: EntityUnloadEvent) {
        if (event.entity is Player) {
            if (rareMobs.contains(event.entity)) {
                event.entity.isSboGlowing = false
                rareMobs.remove(event.entity)
            }
        }
    }

    private fun ClientLevel.checkMobGlow() {
        val iterator = rareMobs.iterator()
        while (iterator.hasNext()) {
            val mob = iterator.next()

            if (!mob.isAlive || mob.level() != this) {
                mob.isSboGlowing = false
                iterator.remove()
                continue
            }

            val isVisible = !Diana.HighlightDepthCheck ||
                (mc.player?.hasLineOfSight(mob) == true && !mob.isInvisible)
            if (Diana.HighlightRareMobs && isVisible) {
                mob.isSboGlowing = true
                mob.setSboGlowColor(Color(Diana.HighlightColor))
            } else {
                mob.isSboGlowing = false
            }
        }
    }
}