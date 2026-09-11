package ai.rever.boss.plugin.dynamic.warden.runtime

import ai.rever.boss.plugin.dynamic.warden.gateway.ApprovalCoordinator
import ai.rever.boss.plugin.dynamic.warden.gateway.ApprovalPrompt
import ai.rever.boss.plugin.dynamic.warden.gateway.Capability
import ai.rever.boss.plugin.dynamic.warden.gateway.GrantBook
import ai.rever.boss.plugin.dynamic.warden.gateway.McpGateway
import ai.rever.boss.plugin.dynamic.warden.gateway.Profile
import ai.rever.boss.plugin.dynamic.warden.gateway.ToolCatalog
import ai.rever.boss.plugin.dynamic.warden.session.SessionRecorder
import ai.rever.boss.plugin.dynamic.warden.session.SessionReport
import ai.rever.boss.plugin.dynamic.warden.session.SessionSnapshot
import ai.rever.boss.plugin.dynamic.warden.trace.TraceLog
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicLong

private val logger = BossLogger.forComponent("WardenRuntime")

/** What the panel renders. One value, so the UI never reads a half-updated set of fields. */
data class WardenStatus(
    val running: Boolean = false,
    val port: Int? = null,
    val settings: WardenSettings = WardenSettings(),
    val lastError: String? = null,
    /**
     * Non-null while the durable trace is known to be incomplete.
     *
     * Surfaced rather than logged. An operator who believes there is an audit trail
     * and has none is worse off than one who knows there is none, so a trace that
     * cannot be written has to reach the panel.
     */
    val traceError: String? = null,
) {
    val endpoint: String? get() = port?.let { "http://127.0.0.1:$it/mcp" }
    val profile: Profile get() = settings.profile
}

/**
 * Owns the gateway's lifecycle and joins it to the session record.
 *
 * Everything host-shaped arrives through [WardenHost], so this class can be driven
 * end to end in a test with no BOSS running.
 *
 * **Failure policy: the plugin must always load.** A panel that throws during
 * `register()` is recorded by the host as binary-incompatible and disabled, which
 * is a far worse outcome than a gateway that could not bind. Every entry point
 * here catches, records the reason in [status], and leaves the panel able to
 * explain itself.
 */
class WardenRuntime(
    private val host: WardenHost,
    private val scope: CoroutineScope,
    private val settingsStore: SettingsStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Persistence seam, so the runtime does not need a live PluginStorageProvider in tests. */
    interface SettingsStore {
        suspend fun load(): WardenSettings

        suspend fun save(settings: WardenSettings)
    }

    val recorder = SessionRecorder(clock)
    val grants = GrantBook(clock)

    private val _status = MutableStateFlow(WardenStatus())
    val status: StateFlow<WardenStatus> = _status

    private val recordIds = AtomicLong(1)
    private var gateway: McpGateway? = null
    private var fileWatch: Job? = null

    private var coordinator: ApprovalCoordinator = buildCoordinator(WardenSettings())

    /** Exposed so the panel can say "waiting on you" instead of looking frozen. */
    val pendingApproval get() = coordinator.pendingRequests

    /**
     * The durable half of the record, opened lazily on first use.
     *
     * Lazy because the directory depends on the host's project path, which is not
     * knowable at construction and can be blank; resolving it once at first write
     * avoids both a constructor that can fail and a path chosen before the host was
     * ready to answer.
     */
    @Volatile
    private var trace: TraceLog? = null

    private fun traceLog(): TraceLog? {
        trace?.let { return it }
        val base =
            ReportLocation.resolveBaseDirectory(host.projectPath, null)
                ?: return null
        val opened =
            TraceLog(
                directory = Paths.get(base, ReportLocation.DIRECTORY_NAME),
                clock = clock,
                onFault = { message -> _status.value = _status.value.copy(traceError = message) },
            )
        trace = opened
        return opened
    }

    /** The trace file's path once one has been opened, for the panel to show. */
    val tracePath: String? get() = trace?.file?.toString()

    private fun sessionId() = recorder.state.value.id

    private fun buildCoordinator(settings: WardenSettings) =
        ApprovalCoordinator(
            prompt = ApprovalPrompt { request -> host.askApproval(request) },
            grants = grants,
            timeoutMillis = settings.approvalTimeoutMillis,
            grantDurationMillis = settings.grantDurationMillis,
        )

    /** Loads settings and starts the gateway if enabled. Never throws. */
    fun initialise() {
        scope.launch {
            val loaded = runCatching { settingsStore.load() }.getOrElse { WardenSettings() }.sanitised()
            coordinator = buildCoordinator(loaded)
            _status.value = _status.value.copy(settings = loaded)
            if (loaded.gatewayEnabled) start()
        }
        watchForDisable()
    }

    /**
     * Closes the gateway if the host disables the plugin without telling it.
     *
     * The host calls `dispose()` on unload only. Disabling unregisters the panel and
     * the tools and stops the sandbox, and never calls in, so a plugin holding a
     * socket keeps holding it. Measured against 9.5.11: after disabling from the
     * Toolbox, 7678 was still listening and still forwarding `run_command`, with the
     * panel gone. See [WardenHost.unregistered].
     *
     * The collector runs on `pluginScope`, which belongs to the window rather than to
     * the plugin and therefore outlives the disable. That is what makes it able to
     * observe one, and it is also why the flow completes after a single emission
     * rather than staying subscribed forever.
     */
    private fun watchForDisable() {
        val signal = host.unregistered() ?: return
        scope.launch {
            runCatching { signal.collect { onDisabled() } }
                .onFailure { logger.warn(LogCategory.SYSTEM, "Disable watch ended", emptyMap(), it) }
        }
    }

    private fun onDisabled() {
        logger.info(LogCategory.SYSTEM, "Plugin was disabled; closing the gateway", emptyMap())
        dispose()
        _status.value = _status.value.copy(running = false, port = null)
    }

    /**
     * Starts the gateway, and starts a session if none is open.
     *
     * The session is tied to the gateway rather than to a button because a call
     * arriving with no session open would be dropped, and the operator would have no
     * way to tell that from an agent that made no calls.
     */
    fun start() {
        scope.launch {
            try {
                stopGateway()
                val settings = _status.value.settings
                val instance =
                    McpGateway(
                        upstream = URI.create(settings.upstreamUrl),
                        decide = { _, capability ->
                            if (grants.isGranted(capability)) {
                                ai.rever.boss.plugin.dynamic.warden.gateway.Decision.Allow
                            } else {
                                _status.value.profile.decide(capability)
                            }
                        },
                        approve = { tool, capability, preview ->
                            coordinator.requestApproval(tool, capability, preview)
                        },
                        onRecord = { record ->
                            recorder.record(record)
                            traceLog()?.call(sessionId(), record)
                        },
                        onAnswerUndelivered = { tool, reason ->
                            traceLog()?.undelivered(sessionId(), tool, reason)
                        },
                        nextRecordId = { recordIds.getAndIncrement() },
                        hiddenCapabilities = {
                            if (settings.hideDeniedTools) _status.value.profile.hardDenied else emptySet()
                        },
                        // Read from status, not the captured settings, so turning it off
                        // takes effect without restarting the gateway.
                        judgeShellCommands = { _status.value.settings.judgeShellCommands },
                    )
                val port = instance.start(settings.preferredPort)
                gateway = instance
                _status.value = _status.value.copy(running = true, port = port, lastError = null)

                if (!recorder.isRecording) beginSession(SessionRecorder.DEFAULT_LABEL)
                watchFileChanges()
                reportCoverage()

                // Only when a specific port was asked for and not given. Port 0 means
                // "any free port", so landing somewhere else is the request being
                // honoured, not a fallback worth interrupting anyone about.
                if (settings.preferredPort != 0 && port != settings.preferredPort) {
                    // Silence here would leave an agent pointed at a dead port with no clue why.
                    host.notify(
                        "Agent Warden",
                        "Port ${settings.preferredPort} was busy. Listening on $port instead.",
                    )
                }
            } catch (e: Exception) {
                logger.error(LogCategory.NETWORK, "Gateway failed to start", emptyMap(), e)
                _status.value = _status.value.copy(running = false, port = null, lastError = e.message ?: "start failed")
            }
        }
    }

    fun stop() {
        scope.launch {
            stopGateway()
            _status.value = _status.value.copy(running = false, port = null)
        }
    }

    private fun stopGateway() {
        fileWatch?.cancel()
        fileWatch = null
        gateway?.stop()
        gateway = null
    }

    /**
     * Changing profile revokes live grants.
     *
     * Without this, dropping from Full access to Read only would leave a live
     * EXECUTE grant quietly overriding the profile the operator just chose, which is
     * the opposite of what pressing that control means.
     */
    fun setProfile(profile: Profile) {
        scope.launch {
            grants.revokeAll()
            val next = _status.value.settings.copy(profileId = profile.id).sanitised()
            _status.value = _status.value.copy(settings = next)
            // The open session has to move with it, or its report names the policy the
            // session opened under while the calls below were decided by this one.
            recorder.profileChanged(profile.name)
            traceLog()?.session(sessionId(), "policy is now '${profile.name}'")
            runCatching { settingsStore.save(next) }
                .onFailure { logger.warn(LogCategory.SYSTEM, "Could not persist profile", emptyMap(), it) }
            logger.info(LogCategory.SYSTEM, "Profile changed", mapOf("profile" to profile.id))
        }
    }

    fun updateSettings(transform: (WardenSettings) -> WardenSettings) {
        scope.launch {
            val next = transform(_status.value.settings).sanitised()
            val restartNeeded =
                next.preferredPort != _status.value.settings.preferredPort ||
                    next.upstreamUrl != _status.value.settings.upstreamUrl
            coordinator = buildCoordinator(next)
            _status.value = _status.value.copy(settings = next)
            runCatching { settingsStore.save(next) }
            if (restartNeeded && _status.value.running) start()
        }
    }

    fun revokeGrants() {
        grants.revokeAll()
    }

    /**
     * Tells the operator that something asked to change the policy over a deep link
     * and was refused.
     *
     * Announced rather than logged quietly: a `boss://` link can be produced by any
     * program that can ask the OS to open a URL, so an attempt to widen the policy
     * through one is exactly the event the operator should see.
     */
    fun notifyPolicyChangeRefused(requestedProfile: String) {
        host.notify(
            "Agent Warden",
            "Refused a request to switch to '$requestedProfile'. The policy can only be " +
                "changed from the panel.",
            isError = true,
        )
    }

    // ---- sessions ------------------------------------------------------------

    fun beginSession(label: String) {
        scope.launch {
            val git = runCatching { host.gitSnapshot() }.getOrNull()
            recorder.start(
                label = label,
                profileName = _status.value.profile.name,
                projectPath = host.projectPath,
                git = git,
            )
            traceLog()?.session(sessionId(), "session opened under '${_status.value.profile.name}'")
            reportCoverage()
        }
    }

    /** Ends the session and writes its report. Returns nothing; the path is announced through [host]. */
    fun endSessionAndExport() {
        scope.launch {
            val git = runCatching { host.gitSnapshot(recorder.state.value.git) }.getOrNull()
            val sessionId = sessionId()
            val finished = recorder.end(git)
            if (finished == null) {
                host.notify("Agent Warden", "No session is open.")
                return@launch
            }
            // Read before end(), because end() is what makes it the closed session.
            traceLog()?.session(sessionId, "session closed after ${finished.records.size} call(s)")
            export(finished)
        }
    }

    /** Writes a report for the current session without closing it. */
    fun exportSnapshot() {
        scope.launch { export(recorder.state.value) }
    }

    private suspend fun export(snapshot: SessionSnapshot) {
        val markdown = SessionReport.render(snapshot)
        val path = runCatching { host.writeReport(SessionReport.fileNameFor(snapshot), markdown) }.getOrNull()
        if (path == null) {
            host.notify("Agent Warden", "Could not write the session report.", isError = true)
        } else {
            host.notify("Agent Warden", "Session report written to $path")
        }
    }

    /** Called by the agent-facing `warden_log_intent` tool. */
    fun logIntent(summary: String, detail: String?) {
        recorder.note(summary, detail)
    }

    // ---- host wiring ---------------------------------------------------------

    private fun watchFileChanges() {
        fileWatch?.cancel()
        val flow = host.fileChanges() ?: return
        fileWatch =
            scope.launch {
                try {
                    flow.collect { change -> recorder.fileTouched(change.path, change.change) }
                } catch (e: CancellationException) {
                    throw e // cancellation is how this coroutine is meant to end
                } catch (e: Exception) {
                    // A dead event stream costs the report its file evidence. It must not
                    // take the gateway down with it, so this is logged, not rethrown.
                    logger.warn(LogCategory.FILE, "File change stream ended", emptyMap(), e)
                }
            }
    }

    /**
     * Records which advertised tools this build cannot classify.
     *
     * Diagnostic, so it must never be able to fail a start: an unreachable upstream
     * here means the coverage list stays empty, not that the gateway is down.
     */
    private fun reportCoverage() {
        scope.launch {
            val names = runCatching { host.upstreamToolNames() }.getOrDefault(emptyList())
            val unclassified = names.filter { ToolCatalog.capabilityOf(it) == Capability.UNKNOWN }
            if (unclassified.isNotEmpty() || names.isNotEmpty()) recorder.setUnclassifiedTools(unclassified)
        }
    }

    fun dispose() {
        stopGateway()
        grants.revokeAll()
    }
}
