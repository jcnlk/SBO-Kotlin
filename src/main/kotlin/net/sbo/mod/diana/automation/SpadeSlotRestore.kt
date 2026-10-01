package net.sbo.mod.diana.automation

internal class SpadeSlotRestore(val previousSlot: Int, val spadeSlot: Int, tick: Long) {
    enum class Action { WAIT, RETRY, COMPLETE, CANCEL }

    private var lastAttemptTick = tick
    private var selectedSinceTick: Long? = null

    fun advance(selectedSlot: Int, tick: Long): Action {
        if (selectedSlot != previousSlot && selectedSlot != spadeSlot) return Action.CANCEL
        if (tick <= lastAttemptTick) return Action.WAIT
        if (selectedSlot == previousSlot) {
            val since = selectedSinceTick
            if (since == null) selectedSinceTick = tick
            return if (since != null && tick > since) Action.COMPLETE else Action.WAIT
        }
        selectedSinceTick = null
        lastAttemptTick = tick
        return Action.RETRY
    }
}
