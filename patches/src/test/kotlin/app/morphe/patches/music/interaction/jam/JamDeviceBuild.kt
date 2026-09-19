package app.morphe.patches.music.interaction.jam

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patches.all.misc.clone.cloneAppPatch
import app.morphe.patches.music.ad.hideAdsPatch
import app.morphe.patches.music.misc.backgroundplayback.backgroundPlaybackPatch
import app.morphe.patches.music.misc.gms.gmsCoreSupportPatch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

private const val PROBE_PACKAGE = "app.morphe.jam.next.music"

fun main(arguments: Array<String>) {
    require(arguments.size in 1..2) {
        "Usage: JamDeviceBuildKt <input-apk> [output-apk]"
    }

    val input = File(arguments[0]).canonicalFile
    require(input.isFile) { "Input APK does not exist: $input" }

    val output = arguments.getOrNull(1)?.let(::File)?.canonicalFile
    output?.parentFile?.mkdirs()

    cloneAppPatch.options["packageName"] = PROBE_PACKAGE

    val selectedPatches = setOf(
        gmsCoreSupportPatch,
        hideAdsPatch,
        jamQueueProbePatch,
        backgroundPlaybackPatch,
    )
    val workspace = Files.createTempDirectory("jam-device-build")

    Patcher(PatcherConfig(input, workspace.toFile())).use { patcher ->
        patcher += selectedPatches
        runBlocking {
            patcher().collect { result ->
                result.exception?.let { throw it }
                println("Applied: ${result.patch.name}")
            }
        }

        val patched = patcher.get()
        if (output == null) {
            println("Jam patch applied and DEX serialized: $workspace")
        } else {
            patched.applyTo(output)
            println("Unsigned isolated device probe: $output")
        }
    }
}
