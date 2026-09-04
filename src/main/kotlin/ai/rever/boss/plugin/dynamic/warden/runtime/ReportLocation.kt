package ai.rever.boss.plugin.dynamic.warden.runtime

/**
 * Decides where a session report is written.
 *
 * Extracted from [PluginContextWardenHost] so it can be tested, because getting it
 * wrong is silent: the write fails somewhere unwriteable and the operator is told
 * the report could not be written, with no clue that the reason was an empty
 * string.
 *
 * **`PluginContext.projectPath` is `String?` and returns `""`, not `null`, when no
 * project is open.** Measured against a running BossConsole 9.5.7. An Elvis
 * operator therefore does not fall through, and `"" + "/.agent-warden/x.md"`
 * resolves to `/.agent-warden/x.md` - the filesystem root on Linux and macOS, and
 * an unwriteable path on Windows. That is exactly what happened the first time a
 * report was exported from the running plugin.
 *
 * Every candidate is treated as absent when blank, and there is a final fallback to
 * `user.home` so that a host answering blank for everything still produces a file
 * somewhere the operator can find.
 */
object ReportLocation {
    const val DIRECTORY_NAME = ".agent-warden"

    /**
     * Returns the directory to write into, or null if no candidate was usable.
     *
     * Preference order is deliberate: next to the work it describes, else the user's
     * home. A report is written to be handed to somebody, and one the operator cannot
     * find has not been produced.
     */
    fun resolveBaseDirectory(
        projectPath: String?,
        homeDirectory: String?,
        systemHome: String? = System.getProperty("user.home"),
    ): String? =
        listOf(projectPath, homeDirectory, systemHome)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trimEnd('/', '\\')
            ?.takeIf { it.isNotBlank() }

    /** Joins with a forward slash, which both `File` and the host's file provider accept on Windows. */
    fun reportPath(baseDirectory: String, fileName: String): String =
        "$baseDirectory/$DIRECTORY_NAME/$fileName"
}
