package ai.rever.boss.plugin.dynamic.warden.gateway

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The coordinator is where "ask the operator" meets a thread pool, and every test
 * here corresponds to a way that combination misleads somebody.
 */
class ApprovalCoordinatorTest {
    private fun coordinator(
        grants: GrantBook = GrantBook(),
        timeoutMillis: Long = 60_000,
        prompt: ApprovalPrompt,
    ) = ApprovalCoordinator(prompt, grants, timeoutMillis)

    @Test
    fun `approving once allows the call without opening a grant`() = runTest {
        val grants = GrantBook()
        val c = coordinator(grants) { ApprovalChoice.ONCE }
        assertEquals(
            ApprovalOutcome.APPROVED,
            c.requestApproval("run_command", Capability.EXECUTE, "{}"),
        )
        assertFalse(grants.isGranted(Capability.EXECUTE), "ONCE must not leave a standing permission")
    }

    @Test
    fun `approving for a while opens a grant that covers later calls silently`() = runTest {
        val grants = GrantBook()
        val asked = AtomicInteger()
        val c =
            coordinator(grants) {
                asked.incrementAndGet()
                ApprovalChoice.FOR_A_WHILE
            }
        assertEquals(ApprovalOutcome.APPROVED, c.requestApproval("run_command", Capability.EXECUTE, "{}"))
        assertEquals(ApprovalOutcome.GRANTED, c.requestApproval("send_input", Capability.EXECUTE, "{}"))
        assertEquals(1, asked.get(), "a live grant should not raise a second dialog")
    }

    @Test
    fun `a grant covers a different tool of the same capability`() = runTest {
        // Approving run_command and then being asked again for send_input, which can
        // drive the very shell just authorised, would be security theatre.
        val grants = GrantBook()
        grants.grant(Capability.EXECUTE, 60_000)
        val c = coordinator(grants) { error("must not be asked") }
        assertEquals(ApprovalOutcome.GRANTED, c.requestApproval("send_input", Capability.EXECUTE, "{}"))
    }

    @Test
    fun `denial refuses the call`() = runTest {
        val c = coordinator { ApprovalChoice.DENY }
        assertEquals(ApprovalOutcome.REFUSED, c.requestApproval("browser_run_js", Capability.BROWSER_SCRIPT, "{}"))
    }

    @Test
    fun `a timeout denies rather than allowing`() = runTest {
        // An operator who walked away has not consented. Treating silence as yes
        // would make the gateway most permissive exactly when least supervised.
        val c =
            coordinator(timeoutMillis = 50) {
                delay(10_000)
                ApprovalChoice.ONCE
            }
        assertEquals(ApprovalOutcome.REFUSED, c.requestApproval("run_command", Capability.EXECUTE, "{}"))
    }

    @Test
    fun `a prompt that throws denies rather than allowing`() = runTest {
        // A host with no dialog provider, or one that blows up, must fail closed.
        val c = coordinator { throw IllegalStateException("no dialog provider") }
        assertEquals(ApprovalOutcome.REFUSED, c.requestApproval("run_command", Capability.EXECUTE, "{}"))
    }

    @Test
    fun `concurrent requests are asked one at a time`() = runTest {
        // GenericDialogProvider has no queue. Two dialogs raised at once means the
        // operator answers one believing they answered the other.
        val concurrent = AtomicInteger()
        val maxSeen = AtomicInteger()
        val c =
            coordinator {
                val inFlight = concurrent.incrementAndGet()
                maxSeen.updateAndGet { maxOf(it, inFlight) }
                delay(20)
                concurrent.decrementAndGet()
                ApprovalChoice.ONCE
            }
        val results =
            (1..8).map { async { c.requestApproval("run_command", Capability.EXECUTE, "{}") } }.awaitAll()
        assertEquals(1, maxSeen.get(), "more than one dialog was open at once")
        assertTrue(results.all { it == ApprovalOutcome.APPROVED })
    }

    @Test
    fun `a queued backlog drains without re-asking once a grant is given`() = runTest {
        // While the operator reads one dialog the agent fires more calls. Without the
        // post-lock re-check they each raise their own prompt about a permission that
        // is already live by the time they are dequeued.
        val grants = GrantBook()
        val asked = AtomicInteger()
        val c =
            coordinator(grants) {
                asked.incrementAndGet()
                delay(20)
                ApprovalChoice.FOR_A_WHILE
            }
        (1..10).map { async { c.requestApproval("run_command", Capability.EXECUTE, "{}") } }.awaitAll()
        assertEquals(1, asked.get(), "backlog re-asked about an already-granted capability")
    }

    @Test
    fun `pending exposes the open request and clears afterwards`() = runTest {
        // The panel reads this to explain why it is waiting. A pending request that
        // outlived its dialog would leave the panel claiming a prompt is open forever.
        lateinit var c: ApprovalCoordinator
        c =
            coordinator {
                assertEquals("run_command", c.pending?.toolName)
                ApprovalChoice.ONCE
            }
        c.requestApproval("run_command", Capability.EXECUTE, "{}")
        assertEquals(null, c.pending, "pending survived the dialog it described")
    }

    @Test
    fun `pending clears even when the prompt throws`() = runTest {
        val c = coordinator { throw RuntimeException("boom") }
        c.requestApproval("run_command", Capability.EXECUTE, "{}")
        assertEquals(null, c.pending, "a failed prompt left the panel showing a phantom request")
    }

    @Test
    fun `the request carries the redacted preview it was given`() = runTest {
        var seen: ApprovalRequest? = null
        val c =
            coordinator {
                seen = it
                ApprovalChoice.DENY
            }
        c.requestApproval("browser_run_js", Capability.BROWSER_SCRIPT, "script=document...<40 chars>")
        assertEquals("browser_run_js", seen?.toolName)
        assertEquals(Capability.BROWSER_SCRIPT, seen?.capability)
        assertEquals("script=document...<40 chars>", seen?.argumentsPreview)
    }
}
