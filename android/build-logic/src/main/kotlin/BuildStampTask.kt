import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Writes the build's wall-clock instant into a generated asset,
 * `build_stamp.txt` (epoch milliseconds, one line).
 *
 * An asset written by a task ACTION rather than a `buildConfigField` on
 * purpose: a timestamp in BuildConfig is configuration input and would
 * invalidate the configuration cache on every single build — the exact cost
 * the old commit-anchored GitStamp existed to avoid. A task output leaves
 * configuration untouched; the price is that this task is never up to date,
 * so every assemble re-merges assets and repackages. Packaging is seconds;
 * reconfiguration was the expensive half.
 *
 * The stamp replaces GitStamp's commit-anchored string (retired 2026-08-22,
 * user ruling): every device in this fleet runs the debug or dev variant,
 * which the old design deliberately left as a bare version — so in practice
 * no installed build could be told from another. The honest fact a person
 * wants from the Tools row is "when was this binary built", and that is a
 * timestamp, not a sha.
 */
abstract class BuildStampTask : DefaultTask() {

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        // Never up to date: the whole point is a fresh instant per build.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun stamp() {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        dir.resolve("build_stamp.txt").writeText(System.currentTimeMillis().toString())
    }
}
