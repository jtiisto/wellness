import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Runs a git command and yields its trimmed stdout, or [UNKNOWN].
 *
 * A `ValueSource` rather than a direct `exec` at configuration time because the
 * configuration cache has to be able to *re-check* this: it records the source,
 * re-runs it on each build, and only invalidates the cached configuration when
 * the answer actually changes. Reading `.git/HEAD` by hand would have been
 * cheaper and wrong — it holds a ref name rather than a sha on any branch, and
 * points somewhere else entirely in a worktree or with packed refs.
 */
abstract class GitOutputValueSource : ValueSource<String, GitOutputValueSource.Params> {

    interface Params : ValueSourceParameters {
        val args: ListProperty<String>
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    /**
     * `ok=false` means the command did not run or exited non-zero. That is
     * **not** the same as an empty stdout, and conflating the two produced a
     * real defect: `git status --porcelain` failing yielded the string
     * `"unknown"`, which is non-empty, so the build stamped itself `-dirty` on
     * the strength of a command that never ran.
     */
    override fun obtain(): String = try {
        val stdout = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine(parameters.args.get())
            standardOutput = stdout
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        if (result.exitValue == 0) OK_PREFIX + stdout.toString(Charsets.UTF_8.name()).trim() else FAILED
    } catch (_: Exception) {
        // No git, not a repository, a source zip — none of which should fail a
        // build over a string that only ever appears on a diagnostics screen.
        FAILED
    }

    companion object {
        const val OK_PREFIX = "ok:"
        const val FAILED = "failed"
    }
}

/** A git command's outcome, keeping "did not run" distinct from "said nothing". */
internal data class GitOutput(val ran: Boolean, val text: String) {
    /** The value to print, or [UNKNOWN] when the command did not run. */
    fun orUnknown(): String = if (ran) text else UNKNOWN

    companion object {
        const val UNKNOWN = "unknown"

        fun parse(raw: String): GitOutput = when {
            raw.startsWith(GitOutputValueSource.OK_PREFIX) ->
                GitOutput(ran = true, text = raw.removePrefix(GitOutputValueSource.OK_PREFIX))
            else -> GitOutput(ran = false, text = "")
        }
    }
}

private fun Project.gitOutput(vararg args: String): GitOutput = GitOutput.parse(
    providers.of(GitOutputValueSource::class.java) {
        parameters.args.set(listOf("git", *args))
    }.get(),
)

/**
 * The string the Tools tab's build row shows.
 *
 * Debug builds say `0.2.0 (dev)` and nothing more: the sha of a debug build
 * installed over ADB tells you where the *source* was, not what is running, and
 * a wrong answer is worse than no answer.
 *
 * Release builds are commit-anchored — `0.2.0 a1b2c3d · 2026-08-09T14:30:05Z` —
 * which is deliberately not a build timestamp. A timestamp would change on
 * every build and invalidate the configuration cache with it; the commit's own
 * date identifies the code just as precisely and is stable until the code moves.
 * `-dirty` marks a build made over uncommitted changes, because that is exactly
 * the build whose sha would otherwise be a lie. It is appended **only when
 * `git status` actually ran**: a command that failed says nothing about the
 * worktree, and claiming it was dirty on that basis is as wrong as claiming it
 * was clean. When git is unavailable the stamp degrades to a bare `unknown`.
 */
fun Project.buildStamp(versionName: String, isRelease: Boolean): String {
    if (!isRelease) return "$versionName (dev)"
    val sha = gitOutput("rev-parse", "--short", "HEAD")
    val committedAt = gitOutput("log", "-1", "--format=%cI")
    val status = gitOutput("status", "--porcelain")
    val dirty = if (status.ran && status.text.isNotEmpty()) "-dirty" else ""
    return "$versionName ${sha.orUnknown()}$dirty · ${committedAt.orUnknown()}"
}
