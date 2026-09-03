<!-- markdownlint-disable MD041 -->
# boss-plugin-project-studio

A [BOSS Console](https://github.com/risa-labs-inc/BossConsole) plugin: **Project Studio**, a guided
workspace for a course or personal project. Built for the [BOSS Contributor Hackathon](https://bossconsole.ai/hackathon/)
("Extend BOSS" + "Apply BOSS" tracks).

## What it does

Adds a **Project Studio** sidebar panel tracking a project through five stages — Research, Plan, Code,
Review, Final — each with a status (not started / in progress / done), a summary, free-form notes, and
a list of research sources. State is scoped to whichever BOSS project is open and persists across
restarts.

It also registers four MCP tools, so an agent (Claude Code, Codex, Gemini, OpenCode) running in a BOSS
terminal can drive the same state directly instead of only through the panel:

| Tool | Purpose |
|---|---|
| `project_studio_get_state` | Read the current project's full state as JSON |
| `project_studio_set_milestone` | Set a stage's status (`not_started` / `in_progress` / `done`) and optional summary |
| `project_studio_add_source` | Add a research source (title, optional url/note) to a stage |
| `project_studio_add_note` | Add a free-form note to a stage |

The panel and the MCP tools read/write the same [`ProjectStudioStateStore`](src/main/kotlin/ai/rever/boss/plugin/dynamic/projectstudio/ProjectStudioStateStore.kt),
so a note an agent adds via a tool call shows up in the panel, and a milestone marked done in the panel
is visible to `project_studio_get_state`.

## Build

```bash
./gradlew buildPluginJar
```

Produces `build/libs/boss-plugin-project-studio-<version>.jar`. `boss-plugin-api` is `compileOnly`;
locally the build downloads the pinned release jar (`risa-labs-inc/boss-plugin-api` v1.0.87) into
`libs/` automatically the first time you build — no separate checkout needed.

## Local testing (hot-reload)

1. `./gradlew clean buildPluginJar`
2. Copy the jar to BOSS's **dev** plugin directory (not the regular one, so this doesn't touch your
   real install): `~/.boss_debug/plugins/`
3. Clear the extraction cache for this plugin id so the new jar is actually picked up:
   `rm -rf ~/.boss_debug/plugin-cache/ai.rever.boss.plugin.dynamic.projectstudio`
4. Start BOSS in dev mode and check the sidebar for **Project Studio**.

## Project layout

```
src/main/kotlin/ai/rever/boss/plugin/dynamic/projectstudio/
  ProjectStudioModels.kt       # StageId, StageStatus, NoteEntry, SourceEntry, ProjectStudioState
  ProjectStudioStateStore.kt   # per-project load/save via PluginStorageFactory (JSON)
  ProjectStudioInfo.kt         # panel id/icon/default sidebar slot
  ProjectStudioComponent.kt    # the panel's Compose UI
  ProjectStudioMcpTools.kt     # the four MCP tools, sharing the same store
  ProjectStudioDynamicPlugin.kt # registers the panel + the MCP tool provider
```

## Known limitations (v0.1)

- One `PluginContext` — and so one in-memory store — per open BOSS window. Two windows editing the
  same project stay correct on disk but don't live-sync with each other; reopening the panel reloads.
- No delete/edit for individual notes or sources yet, only append.
