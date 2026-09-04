package ai.rever.boss.plugin.dynamic.warden.gateway

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Expiry is driven by an injected clock, so nothing here sleeps. */
class GrantBookTest {
    private var now = 1_000L
    private fun book() = GrantBook { now }

    @Test
    fun `a grant is live until its deadline and not after`() {
        val grants = book()
        grants.grant(Capability.EXECUTE, durationMillis = 100)
        assertTrue(grants.isGranted(Capability.EXECUTE))
        now += 99
        assertTrue(grants.isGranted(Capability.EXECUTE), "expired one tick early")
        now += 1
        assertFalse(grants.isGranted(Capability.EXECUTE), "survived its own deadline")
    }

    @Test
    fun `a second grant extends but can never shorten a live one`() {
        // The operator approved twenty minutes. A later one-minute approval arriving
        // from a queued prompt must not silently cut that short.
        val grants = book()
        grants.grant(Capability.EXECUTE, durationMillis = 1000)
        grants.grant(Capability.EXECUTE, durationMillis = 10)
        now += 500
        assertTrue(grants.isGranted(Capability.EXECUTE), "a shorter grant truncated a longer live one")

        grants.grant(Capability.EXECUTE, durationMillis = 5000)
        now += 4000
        assertTrue(grants.isGranted(Capability.EXECUTE), "a longer grant failed to extend")
    }

    @Test
    fun `grants are scoped to one capability and do not bleed`() {
        val grants = book()
        grants.grant(Capability.EXECUTE, 1000)
        assertFalse(grants.isGranted(Capability.BROWSER_SCRIPT), "granting shell also granted browser scripting")
        assertFalse(grants.isGranted(Capability.GOVERN))
    }

    @Test
    fun `a non-positive duration grants nothing`() {
        // Guards a division-of-labour bug: a caller computing a duration from a
        // config value that came back zero must not produce a permanent grant, and
        // must not produce a negative deadline that reads as "already expired" only
        // by accident.
        val grants = book()
        grants.grant(Capability.EXECUTE, 0)
        grants.grant(Capability.BROWSER_SCRIPT, -5000)
        assertFalse(grants.isGranted(Capability.EXECUTE))
        assertFalse(grants.isGranted(Capability.BROWSER_SCRIPT))
    }

    @Test
    fun `revokeAll drops live grants immediately`() {
        val grants = book()
        grants.grant(Capability.EXECUTE, 100_000)
        grants.grant(Capability.CONTROL_UI, 100_000)
        grants.revokeAll()
        assertFalse(grants.isGranted(Capability.EXECUTE))
        assertFalse(grants.isGranted(Capability.CONTROL_UI))
        assertTrue(grants.remaining().isEmpty())
    }

    @Test
    fun `remaining reports only live grants, never a negative countdown`() {
        val grants = book()
        grants.grant(Capability.EXECUTE, 1000)
        grants.grant(Capability.CONTROL_UI, 100)
        now += 500
        val remaining = grants.remaining()
        assertEquals(setOf(Capability.EXECUTE), remaining.keys, "expired grant still listed")
        assertEquals(500L, remaining[Capability.EXECUTE])
        assertTrue(remaining.values.all { it > 0 })
    }

    @Test
    fun `reading an expired grant evicts it rather than leaving it to be re-read`() {
        val grants = book()
        grants.grant(Capability.EXECUTE, 10)
        now += 100
        assertFalse(grants.isGranted(Capability.EXECUTE))
        assertTrue(grants.remaining().isEmpty(), "expired entry lingered after being read")
    }

    @Test
    fun `concurrent grants and reads do not lose a grant`() {
        // The gateway serves calls on a thread pool, so the book is read and written
        // from several threads at once. This would fail on a plain HashMap.
        val grants = GrantBook { System.currentTimeMillis() }
        val threads = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.submit {
                start.await()
                repeat(200) {
                    grants.grant(Capability.EXECUTE, 60_000)
                    grants.isGranted(Capability.EXECUTE)
                    grants.remaining()
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "concurrent access deadlocked or stalled")
        pool.shutdownNow()
        assertTrue(grants.isGranted(Capability.EXECUTE), "grant lost under contention")
    }
}
