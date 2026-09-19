package app.morphe.patches.music.interaction.jam

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertTrue

class JamPatchRegressionTest {
    @Test
    fun `Jam patch sources do not encode host obfuscation descriptors`() {
        val sourceRoot = kotlin.io.path.Path("src/main/kotlin/app/morphe/patches/music/interaction/jam")
        val descriptors = Regex("(?<![A-Za-z0-9_/])L[a-z]{1,6};")
        val matches = sourceRoot.walk().filter { it.toString().endsWith(".kt") }.flatMap { source ->
            descriptors.findAll(source.readText()).map { "${source.fileName}: ${it.value}" }
        }.toList()
        assertTrue(matches.isEmpty(), "Jam patch sources contain host ABI literals: ${matches.joinToString()}")
    }

    @Test
    fun `Jam fingerprints resolve against the supplied target APK`() {
        val apkPath = System.getProperty("jamApk")
        assumeTrue(!apkPath.isNullOrBlank()) {
            "Jam APK resolution test skipped; pass -PjamApk=/absolute/path/to/ytm.apk"
        }
        val workspace = createTempDirectory("jam-patch-resolution")
        Patcher(PatcherConfig(kotlin.io.path.Path(apkPath).toFile(), workspace.toFile())).use { patcher ->
            patcher += setOf(jamQueueProbePatch)
            runBlocking {
                patcher().collect { result ->
                    assertTrue(result.exception == null, result.exception?.stackTraceToString().orEmpty())
                }
            }
            patcher.get()
        }
    }
}
