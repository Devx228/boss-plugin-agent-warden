package ai.rever.boss.plugin.dynamic.projectstudio

import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckSquare

/** Describes the Project Studio panel: its id, sidebar icon, and default slot. */
object ProjectStudioInfo : PanelInfo {
    override val id = PanelId("project-studio", 60)
    override val displayName = "Project Studio"
    override val icon = FeatherIcons.CheckSquare
    override val defaultSlotPosition = left.bottom
}
