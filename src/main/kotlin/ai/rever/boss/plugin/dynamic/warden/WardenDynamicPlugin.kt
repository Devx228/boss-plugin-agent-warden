package ai.rever.boss.plugin.dynamic.warden

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.dynamic.warden.runtime.PluginContextWardenHost
import ai.rever.boss.plugin.dynamic.warden.runtime.WardenMcpToolProvider
import ai.rever.boss.plugin.dynamic.warden.runtime.WardenRuntime
import ai.rever.boss.plugin.dynamic.warden.runtime.WardenSettings
import ai.rever.boss.plugin.dynamic.warden.ui.WardenPanelComponent
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import compose.icons.FeatherIcons
import compose.icons.feathericons.Shield

/** Must match `pluginId` in src/main/resources/META-INF/boss-plugin/plugin.json. */
const val WARDEN_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.warden"

private val logger = BossLogger.forComponent("AgentWarden")

private val settingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Describes the Agent Warden panel: its id, sidebar icon, and default slot. */
object WardenPanelInfo : PanelInfo {
    override val id = PanelId("agent-warden", 60)
    override val displayName = "Agent Warden"
    override val icon = FeatherIcons.Shield
    override val defaultSlotPosition = left.bottom
}

/**
 * Agent Warden: an MCP endpoint that stands in front of BOSS's own, so every tool
 * call an agent makes can be recorded, refused, or put to the operator.
 *
 * Registers one panel and three read-only MCP tools, both driven by a single
 * [WardenRuntime], so the operator and the agent are looking at one state.
 */
class WardenDynamicPlugin : DynamicPlugin {
    override val pluginId = WARDEN_PLUGIN_ID
    override val displayName = "Agent Warden"
    override val version = "0.2.0"
    override val description =
        "Puts an approval and audit layer in front of the MCP tools an agent can call in BOSS. " +
            "Records every call, escalates high-risk ones to you, refuses changes to the tool " +
            "surface itself, and exports a session report you can hand to somebody."
    override val author = "Devansh Abhay Dhok"
    override val url = "https://github.com/Devx228/boss-plugin-project-studio"

    private var runtime: WardenRuntime? = null

    /**
     * Registration must not throw.
     *
     * A plugin whose `register` fails is recorded by the host as binary incompatible
     * and disabled, which the operator then has to diagnose from a log line. A
     * gateway that could not start is a far better outcome than a panel that is not
     * there, so the failure is caught, logged, and left for the panel to explain.
     */
    override fun register(context: PluginContext) {
        try {
            val storage = context.pluginStorageFactory?.createStorage(WARDEN_PLUGIN_ID)
            val instance =
                WardenRuntime(
                    host = PluginContextWardenHost(context) { runtime?.status?.value?.settings ?: WardenSettings() },
                    scope = context.pluginScope,
                    settingsStore =
                        object : WardenRuntime.SettingsStore {
                            override suspend fun load(): WardenSettings {
                                val raw = storage?.getJson(WardenSettings.STORAGE_KEY) ?: return WardenSettings()
                                return runCatching { settingsJson.decodeFromString<WardenSettings>(raw) }
                                    .getOrElse { WardenSettings() }
                            }

                            override suspend fun save(settings: WardenSettings) {
                                storage?.putJson(WardenSettings.STORAGE_KEY, settingsJson.encodeToString(settings))
                            }
                        },
                )
            runtime = instance

            context.panelRegistry.registerPanel(WardenPanelInfo) { componentContext, panelInfo ->
                WardenPanelComponent(componentContext, panelInfo, instance)
            }
            context.registerMcpToolProvider(WardenMcpToolProvider(instance, WARDEN_PLUGIN_ID))

            instance.initialise()
            logger.info(LogCategory.SYSTEM, "Agent Warden registered", emptyMap())
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Agent Warden failed to register", emptyMap(), e)
        }
    }

    override fun dispose() {
        runtime?.dispose()
        runtime = null
    }
}
