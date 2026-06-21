package net.sbo.mod.utils.events.impl.diana

import net.sbo.mod.utils.math.SboVec

enum class DianaTargetSource {
    ARROW_GUESS,
    SPADE_GUESS,
    RARE_MOB
}

class DianaTargetDetectedEvent(
    val source: DianaTargetSource,
    val pos: SboVec?
)
