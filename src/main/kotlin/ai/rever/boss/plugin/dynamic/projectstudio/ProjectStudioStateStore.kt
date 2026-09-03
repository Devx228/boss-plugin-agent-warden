package ai.rever.boss.plugin.dynamic.projectstudio

import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

private val json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

/**
 * Loads/saves one [ProjectStudioState] per BOSS project, keyed by a hash of its
 * path (storage keys can't safely embed arbitrary path characters). Falls back
 * to in-memory-only — still usable for the running session — when the host has
 * no [ai.rever.boss.plugin.api.PluginStorageFactory] ([PluginContext.pluginStorageFactory]
 * is nullable).
 *
 * Known limitation: BOSS creates one [PluginContext] per window, so a second
 * open window gets its own store instance. Both read/write the same underlying
 * storage key for a given project, but changes made in one window only appear
 * in another after that window's panel reloads (e.g. re-opening it) — there is
 * no cross-window live sync in this first version.
 */
class ProjectStudioStateStore(
    private val context: PluginContext,
    scope: CoroutineScope,
) {
    private val storage = context.pluginStorageFactory?.createStorage(PROJECT_STUDIO_PLUGIN_ID)

    private val _current = MutableStateFlow(ProjectStudioState(projectPath = currentProjectPath()))
    val current: StateFlow<ProjectStudioState> = _current

    init {
        scope.launch { reload() }
    }

    private fun currentProjectPath(): String = context.projectPath ?: NO_PROJECT

    private fun keyFor(projectPath: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(projectPath.toByteArray())
        return "project-" + digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Re-reads state for whichever project is open now, discarding unsaved in-memory changes. */
    suspend fun reload() {
        val path = currentProjectPath()
        val stored = storage?.getJson(keyFor(path))
        val state =
            stored
                ?.let { runCatching { json.decodeFromString<ProjectStudioState>(it) }.getOrNull() }
                ?: ProjectStudioState(projectPath = path)
        _current.value = state
    }

    /**
     * Applies [transform] to the state for whichever project is open right now
     * and persists the result. If the open project changed since the last read,
     * reloads first so the edit lands on the right project instead of a stale
     * in-memory snapshot.
     */
    suspend fun update(transform: (ProjectStudioState) -> ProjectStudioState): ProjectStudioState {
        val path = currentProjectPath()
        if (_current.value.projectPath != path) reload()
        val next = transform(_current.value).copy(projectPath = path)
        _current.value = next
        storage?.putJson(keyFor(path), json.encodeToString(next))
        return next
    }

    companion object {
        const val NO_PROJECT = "no-project"
    }
}
