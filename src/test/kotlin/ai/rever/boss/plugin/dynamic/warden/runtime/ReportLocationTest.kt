package ai.rever.boss.plugin.dynamic.warden.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReportLocationTest {
    @Test
    fun `an open project wins`() {
        assertEquals("/home/dev/proj", ReportLocation.resolveBaseDirectory("/home/dev/proj", "/home/dev", "/home/dev"))
    }

    @Test
    fun `an empty project path falls through to the home directory`() {
        // The bug this class exists for. PluginContext.projectPath answers "" rather
        // than null when no project is open, so an Elvis operator does not fall
        // through and the report path became "/.agent-warden/...", which is the
        // filesystem root. Measured against a running BossConsole 9.5.7.
        assertEquals("/home/dev", ReportLocation.resolveBaseDirectory("", "/home/dev", "/sys/home"))
        assertEquals("/home/dev", ReportLocation.resolveBaseDirectory("   ", "/home/dev", "/sys/home"))
        assertEquals("/home/dev", ReportLocation.resolveBaseDirectory(null, "/home/dev", "/sys/home"))
    }

    @Test
    fun `a blank home directory falls through to the system property`() {
        assertEquals("/sys/home", ReportLocation.resolveBaseDirectory("", "", "/sys/home"))
        assertEquals("/sys/home", ReportLocation.resolveBaseDirectory(null, null, "/sys/home"))
    }

    @Test
    fun `nothing usable answers null rather than an empty path`() {
        // Returning "" here would rebuild the original bug one layer down.
        assertNull(ReportLocation.resolveBaseDirectory("", "", ""))
        assertNull(ReportLocation.resolveBaseDirectory(null, null, null))
        assertNull(ReportLocation.resolveBaseDirectory("  ", null, "   "))
    }

    @Test
    fun `a trailing separator does not produce a doubled slash`() {
        assertEquals("/home/dev", ReportLocation.resolveBaseDirectory("/home/dev/", null, null))
        // Built from a constant rather than written as a literal, because a Windows
        // path in a Kotlin string is nothing but escape hazards.
        val sep = '\\'
        val windows = "C:${sep}Users${sep}dev"
        assertEquals(windows, ReportLocation.resolveBaseDirectory("$windows$sep", null, null))
    }

    @Test
    fun `a root path is not trimmed away into nothing`() {
        // "/" trims to "", which must not then be treated as a usable directory.
        assertNull(ReportLocation.resolveBaseDirectory("/", null, null))
    }

    @Test
    fun `the report path nests under the plugin's own directory`() {
        assertEquals(
            "/home/dev/proj/.agent-warden/report.md",
            ReportLocation.reportPath("/home/dev/proj", "report.md"),
        )
    }
}
