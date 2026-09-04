package ai.rever.boss.plugin.dynamic.warden.gateway

import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived permissions the operator has granted beyond their profile's ceiling.
 *
 * The problem this solves is prompt fatigue, which is a security failure and not a
 * usability one. An agent doing real work emits `run_command` dozens of times a
 * minute; a dialog per call trains the operator to click through without reading,
 * and the one call that mattered gets the same reflexive approval as the forty
 * that did not. A grant makes the approval explicit and bounded instead: yes, this
 * capability, for this long.
 *
 * **Nothing here is persisted, deliberately.** A grant that outlived the session
 * would be a permission given for one task quietly applying to the next, which is
 * exactly the drift the profile ceiling exists to prevent. Restarting BOSS returns
 * the operator to their profile, every time.
 *
 * [clock] is injected so expiry is testable without sleeping. Everything is keyed
 * by [Capability] rather than tool name: approving `run_command` and then being
 * asked again for `send_input`, which can drive the very shell that was just
 * authorised, would be theatre.
 */
class GrantBook(private val clock: () -> Long = System::currentTimeMillis) {
    private val expiries = ConcurrentHashMap<Capability, Long>()

    /**
     * Grants [capability] for [durationMillis].
     *
     * Extends rather than replaces: a second approval while one is live must not be
     * able to shorten the window the operator already agreed to, which a bare
     * assignment would do whenever the new duration was smaller.
     */
    fun grant(capability: Capability, durationMillis: Long) {
        if (durationMillis <= 0) return
        val until = clock() + durationMillis
        expiries.merge(capability, until, ::maxOf)
    }

    /** Also evicts on read, so an expired entry cannot linger and be re-read as live. */
    fun isGranted(capability: Capability): Boolean {
        val until = expiries[capability] ?: return false
        if (clock() < until) return true
        expiries.remove(capability, until)
        return false
    }

    fun revoke(capability: Capability) {
        expiries.remove(capability)
    }

    /** Used by the panel's "drop everything" control and on profile change. */
    fun revokeAll() {
        expiries.clear()
    }

    /** Live grants with their remaining milliseconds, for the panel. Never negative. */
    fun remaining(): Map<Capability, Long> {
        val now = clock()
        return expiries.entries
            .filter { it.value > now }
            .associate { it.key to (it.value - now) }
    }

    companion object {
        /** Long enough to finish a task, short enough that walking away closes it. */
        const val DEFAULT_DURATION_MILLIS = 20L * 60L * 1000L
    }
}
