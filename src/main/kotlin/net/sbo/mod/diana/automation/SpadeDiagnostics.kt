package net.sbo.mod.diana.automation

import net.fabricmc.loader.api.FabricLoader
import net.sbo.mod.SBOKotlin
import net.sbo.mod.utils.chat.Chat
import net.sbo.mod.utils.events.Register
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.time.Instant

object SpadeDiagnostics {
    private val path = FabricLoader.getInstance().gameDir.resolve("logs/sbo-spade-debug.log")
    private val lastEvents = mutableMapOf<String, Long>()
    private var nextSnapshotNs = 0L
    private var failed = false

    fun init() {
        log("START", "build=spade-bagheera-v4 mc=${SBOKotlin.mcVersion} mod=${SBOKotlin.version}")
        val hookPresent = net.minecraft.world.entity.player.Inventory::class.java.declaredMethods
            .any { it.name.contains("sbo\$traceSlotChange") }
        log("SLOT_HOOK", "applied=$hookPresent")
        Register.command("sbospadelog") {
            log("USER_MARK", "User requested log location")
            Chat.chat("§6[SBO] §aSpade diagnostics are recording to: §f${path.toAbsolutePath()}")
        }
        Register.onChatMessage(Regex("^[^:]*You (?:just )?dug .{1,160}$"), noFormatting = true) { message, _ ->
            log("SERVER_DIG_MESSAGE", message.string)
        }
        Register.onChatMessage(Regex("^You finished the Griffin burrow chain!.*$"), noFormatting = true) { message, _ ->
            log("SERVER_CHAIN_MESSAGE", message.string)
        }
    }

    fun slotChange(previous: Int, next: Int) {
        if (previous == next) return
        val caller = StackWalker.getInstance().walk { frames ->
            frames.limit(16).map { "${it.className}.${it.methodName}:${it.lineNumber}" }
                .toList().joinToString(" <- ")
        }
        log("SLOT_CHANGE", "from=$previous to=$next caller=$caller")
    }

    @Synchronized
    fun snapshotDue(): Boolean {
        val now = System.nanoTime()
        if (failed || now < nextSnapshotNs) return false
        nextSnapshotNs = now + 500_000_000L
        return true
    }

    @Synchronized
    fun log(event: String, details: String, throttle: Boolean = false) {
        if (failed) return
        val now = System.nanoTime()
        if (throttle) {
            val previous = lastEvents[event]
            if (previous != null && now - previous < 500_000_000L) return
            lastEvents[event] = now
        }
        try {
            Files.createDirectories(path.parent)
            if (Files.exists(path) && Files.size(path) >= 2_000_000L) {
                Files.move(path, path.resolveSibling("sbo-spade-debug.previous.log"), StandardCopyOption.REPLACE_EXISTING)
            }
            val clean = details.replace('\n', ' ').replace('\r', ' ').take(3000)
            Files.writeString(path, "${Instant.now()} $event $clean\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (error: Exception) {
            failed = true
            SBOKotlin.logger.warn("Could not write spade diagnostics", error)
        }
    }
}
