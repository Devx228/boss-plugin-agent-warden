package ai.rever.boss.plugin.dynamic.warden.session

import ai.rever.boss.plugin.dynamic.warden.gateway.InvocationRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Accumulates one session's record: what was called, what changed, what the agent
 * said it was doing.
 *
 * Everything arrives from a different thread than the panel reads on - the gateway
 * serves calls on its own pool, file events arrive on the host's event bus - so
 * state is held in a single [MutableStateFlow] mutated under a lock rather than in
 * several mutable collections. A Compose panel collecting a `StateFlow` that is
 * mutated in place recomposes against a list being written to, which is a crash
 * that only shows up under load.
 *
 * [clock] is injected so tests do not sleep.
 */
class SessionRecorder(private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()

    private val _state = MutableStateFlow(idle())
    val state: StateFlow<SessionSnapshot> = _state

    /** False before [start] and after [end], so the panel can offer the right control. */
    @Volatile
    var isRecording: Boolean = false
        private set

    private fun idle() =
        SessionSnapshot(
            id = "idle",
            label = "No session",
            startedAtMillis = clock(),
            endedAtMillis = clock(),
            profileName = "-",
        )

    /**
     * Begins a session, discarding any previous one from memory.
     *
     * The previous session is expected to have been persisted by the caller before
     * this is called. Keeping history here instead would make the panel's flow carry
     * every session ever recorded, which is the same unbounded-growth-behind-a-
     * StateFlow problem the record caps exist to avoid.
     */
    fun start(
        label: String,
        profileName: String,
        projectPath: String?,
        git: GitSnapshot? = null,
    ): SessionSnapshot =
        synchronized(lock) {
            val now = clock()
            val snapshot =
                SessionSnapshot(
                    id = "session-$now",
                    label = label.ifBlank { DEFAULT_LABEL },
                    startedAtMillis = now,
                    endedAtMillis = null,
                    profileName = profileName,
                    projectPath = projectPath,
                    git = git,
                )
            _state.value = snapshot
            isRecording = true
            snapshot
        }

    /** Ignored when not recording, so a stray call from a gateway shutting down cannot resurrect a session. */
    fun record(entry: InvocationRecord) =
        mutate { it.copy(records = (it.records + entry).takeLast(MAX_RECORDS)) }

    fun note(summary: String, detail: String? = null) =
        mutate {
            it.copy(
                notes = (it.notes + AgentNote(clock(), summary.trim(), detail?.trim()?.ifBlank { null }))
                    .takeLast(MAX_NOTES),
            )
        }

    /**
     * Records a file change, collapsing an immediate repeat of the same path and
     * kind.
     *
     * An editor autosave or a build loop emits the same `MODIFIED` event for one
     * file many times a second. Keeping every one would push the rest of the
     * session out of a bounded list and turn the evidence table into a single
     * file repeated three hundred times.
     */
    fun fileTouched(path: String, change: String) =
        mutate { current ->
            val last = current.fileTouches.lastOrNull()
            if (last != null && last.path == path && last.change == change) {
                current
            } else {
                current.copy(
                    fileTouches = (current.fileTouches + FileTouch(path, change, clock())).takeLast(MAX_TOUCHES),
                )
            }
        }

    /**
     * Moves the session onto [toName] and keeps a note that it moved.
     *
     * Both halves matter. Leaving [SessionSnapshot.profileName] at the value the
     * session opened under made the exported report name a policy under which none
     * of the later decisions were taken; overwriting it silently would name a policy
     * under which none of the earlier ones were. A repeat of the profile already in
     * force is dropped, so re-pressing the active control does not manufacture an
     * event.
     */
    fun profileChanged(toName: String) =
        mutate { current ->
            if (current.profileName == toName) {
                current
            } else {
                current.copy(
                    profileName = toName,
                    profileChanges = (
                        current.profileChanges +
                            ProfileChange(clock(), current.profileName, toName)
                    ).takeLast(MAX_PROFILE_CHANGES),
                )
            }
        }

    fun setUnclassifiedTools(names: List<String>) =
        mutate { it.copy(unclassifiedTools = names.distinct().sorted()) }

    fun updateGit(git: GitSnapshot) = mutate { it.copy(git = git) }

    /**
     * Closes the session and returns the final snapshot, or null if none was open.
     *
     * Returning the snapshot rather than requiring a separate read closes a race:
     * the caller persists and exports what it is handed, instead of re-reading a
     * flow that a newly started session may already have replaced.
     */
    fun end(git: GitSnapshot? = null): SessionSnapshot? =
        synchronized(lock) {
            if (!isRecording) return null
            val finished = _state.value.copy(endedAtMillis = clock(), git = git ?: _state.value.git)
            _state.value = finished
            isRecording = false
            finished
        }

    private inline fun mutate(transform: (SessionSnapshot) -> SessionSnapshot) {
        synchronized(lock) {
            if (!isRecording) return
            _state.value = transform(_state.value)
        }
    }

    companion object {
        const val DEFAULT_LABEL = "Agent session"

        /** Matches the ledger's own cap; the panel and the report both read this list. */
        const val MAX_RECORDS = 500
        const val MAX_TOUCHES = 500
        const val MAX_NOTES = 200

        /** Capped like the rest. An operator flipping the control repeatedly is noise, not evidence. */
        const val MAX_PROFILE_CHANGES = 50
    }
}
