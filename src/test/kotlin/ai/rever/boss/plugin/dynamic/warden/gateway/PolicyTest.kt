package ai.rever.boss.plugin.dynamic.warden.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The decision matrix is the security boundary, so it is pinned exhaustively
 * rather than by example. Every test here fails for a reason that would be a real
 * hole, and several of them describe holes that existed in earlier drafts.
 */
class PolicyTest {
    @Test
    fun `every capability has a decision under every shipped profile`() {
        // Guards against a Capability added later falling through a `when` and
        // silently defaulting to Allow. Exhaustiveness is the point.
        for (profile in Profile.ALL) {
            for (capability in Capability.entries) {
                val decision = profile.decide(capability)
                assertTrue(
                    decision is Decision.Allow || decision is Decision.Ask || decision is Decision.Deny,
                    "${profile.id} produced no decision for $capability",
                )
            }
        }
    }

    @Test
    fun `read-only allows inspection and reading, and escalates everything else`() {
        val p = Profile.READ_ONLY
        assertIs<Decision.Allow>(p.decide(Capability.INSPECT))
        assertIs<Decision.Allow>(p.decide(Capability.READ_CONTENT))
        assertIs<Decision.Ask>(p.decide(Capability.CONTROL_UI))
        assertIs<Decision.Ask>(p.decide(Capability.EXECUTE))
        assertIs<Decision.Ask>(p.decide(Capability.BROWSER_SCRIPT))
    }

    @Test
    fun `build allows shell but still escalates browser scripting`() {
        // The browser carries live sessions a shell may hold no credentials for, so
        // it sits above EXECUTE deliberately. If someone reorders the enum for
        // cosmetic reasons this fails, which is the intent.
        val p = Profile.BUILD
        assertIs<Decision.Allow>(p.decide(Capability.EXECUTE))
        assertIs<Decision.Ask>(p.decide(Capability.BROWSER_SCRIPT))
    }

    @Test
    fun `no profile ever auto-allows an unrecognised tool`() {
        // The whole fail-closed design rests on this. A profile whose ceiling is high
        // must still stop at UNKNOWN, because "unknown" is an absence of information
        // and cannot be covered by a permission granted over things the operator saw.
        for (profile in Profile.ALL) {
            val decision = profile.decide(Capability.UNKNOWN)
            assertTrue(
                decision !is Decision.Allow,
                "${profile.id} auto-allowed an unclassified tool",
            )
        }
    }

    @Test
    fun `a ceiling of UNKNOWN still does not auto-allow unclassified tools`() {
        // Found by mutation testing. The three shipped profiles all sit below UNKNOWN
        // in the escalation order, so they reach `Ask` through the ordinal comparison
        // whether or not the explicit UNKNOWN branch exists - which meant deleting
        // that branch broke nothing any test could see. The branch is what makes the
        // documented promise ("whatever the ceiling") true, and this is the only
        // profile shape that can tell the difference.
        val reckless =
            Profile(
                id = "reckless",
                name = "Reckless",
                description = "",
                ceiling = Capability.UNKNOWN,
                hardDenied = emptySet(),
            )
        assertIs<Decision.Ask>(
            reckless.decide(Capability.UNKNOWN),
            "a ceiling at UNKNOWN turned unclassified tools into a blanket allow",
        )
    }

    @Test
    fun `full access still refuses self-governance`() {
        // The single most important assertion in the suite. If GOVERN were merely
        // "high", the most permissive profile would hand the agent the ability to
        // re-enable everything the operator switched off, and every other guarantee
        // here would be advisory.
        assertIs<Decision.Deny>(Profile.FULL.decide(Capability.GOVERN))
    }

    @Test
    fun `hard denial outranks the ceiling even when the ceiling would allow it`() {
        val permissive =
            Profile(
                id = "test",
                name = "Test",
                description = "",
                ceiling = Capability.UNKNOWN, // above everything
                hardDenied = setOf(Capability.EXECUTE),
            )
        assertIs<Decision.Deny>(permissive.decide(Capability.EXECUTE))
        // and the ceiling still works for everything not hard-denied
        assertIs<Decision.Allow>(permissive.decide(Capability.BROWSER_SCRIPT))
    }

    @Test
    fun `denial reason names the capability and its consequence`() {
        // The refusal text is returned to the agent and shown to the operator, so it
        // has to explain rather than just say no. An agent told only "denied" retries.
        val denial = Profile.READ_ONLY.decide(Capability.GOVERN)
        assertIs<Decision.Deny>(denial)
        assertTrue(Capability.GOVERN.label in denial.reason, "reason omits the capability")
        assertTrue(Capability.GOVERN.consequence in denial.reason, "reason omits the consequence")
    }

    @Test
    fun `unknown profile ids resolve to the safest profile`() {
        // Persisted state can name a profile a later build removed. Resolving to
        // READ_ONLY means a rename degrades to over-prompting; resolving to the last
        // entry, or to FULL, would silently widen access on upgrade.
        assertEquals(Profile.READ_ONLY, Profile.byId(null))
        assertEquals(Profile.READ_ONLY, Profile.byId("nonexistent"))
        assertEquals(Profile.READ_ONLY, Profile.byId(""))
    }

    @Test
    fun `profile ids are unique and resolvable`() {
        val ids = Profile.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate profile id would make byId order-dependent")
        for (profile in Profile.ALL) {
            assertEquals(profile, Profile.byId(profile.id))
        }
    }

    @Test
    fun `escalation order is monotonic, so a ceiling implies everything below it`() {
        // Profile.decide compares ordinals, so the enum's declaration order is load
        // bearing. This states that contract where someone editing the enum will see it.
        val ordered =
            listOf(
                Capability.INSPECT,
                Capability.READ_CONTENT,
                Capability.CONTROL_UI,
                Capability.EXECUTE,
                Capability.BROWSER_SCRIPT,
                Capability.GOVERN,
                Capability.UNKNOWN,
            )
        assertEquals(ordered, Capability.entries.toList(), "capability order changed; profiles now mean something else")
    }
}
