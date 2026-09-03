package ai.rever.boss.plugin.dynamic.projectstudio

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/** Must match `pluginId` in src/main/resources/META-INF/boss-plugin/plugin.json. */
const val PROJECT_STUDIO_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.projectstudio"

/**
 * Project Studio — a guided workspace for a course or personal project.
 *
 * Registers one sidebar panel ([ProjectStudioInfo]/[ProjectStudioComponent]) and
 * four MCP tools ([ProjectStudioMcpToolProvider]) that share the same
 * [ProjectStudioStateStore], so an agent driving the tools and a person using
 * the panel are always looking at the same state.
 *
 * The host instantiates this class (via `mainClass` in plugin.json) and calls
 * [register] once at load.
 */
class ProjectStudioDynamicPlugin : DynamicPlugin {
    override val pluginId = PROJECT_STUDIO_PLUGIN_ID
    override val displayName = "Project Studio"
    override val version = "0.1.0"
    override val description =
        "A guided workspace for a course or personal project: track Research, Plan, Code, Review, " +
            "and Final stages, with notes and sources per stage — from the sidebar panel or via MCP " +
            "tools an agent can call directly."
    override val author = "Devansh Abhay Dhok"
    override val url = "https://github.com/Devx228/boss-plugin-project-studio"

    private var store: ProjectStudioStateStore? = null

    override fun register(context: PluginContext) {
        val stateStore = ProjectStudioStateStore(context, context.pluginScope)
        store = stateStore

        context.panelRegistry.registerPanel(ProjectStudioInfo) { ctx, panelInfo ->
            ProjectStudioComponent(ctx, panelInfo, stateStore)
        }

        context.registerMcpToolProvider(ProjectStudioMcpToolProvider(stateStore))
    }

    override fun dispose() {
        store = null
    }
}
