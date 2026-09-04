# Draft: follow-up comment on risa-labs-inc/BossConsole#332

Post as a new comment. Leave the original issue as it is - the timeline reads more honestly if the "before I build" framing stays where it was and this explains what happened next.

---

Update, and one more question.

I have started building this rather than waiting, because the deadline is close and the answers to my questions above would change the transport rather than the design - the ledger, the policy model, the approval flow and the panel are the same whether calls are intercepted on the wire or through `McpToolRegistry`. So the questions still stand, and I would rather be told I picked the wrong seam now than at review. It is running end to end against BossConsole 9.5.7: https://github.com/Devx228/boss-plugin-agent-warden

Three things I ran into while building, which may be worth knowing regardless of what happens to the plugin:

**`McpToolDefinition.readOnly` is never serialised into `tools/list`.** Tool objects on the wire carry only `name`, `description` and `inputSchema`, so no MCP client can see the flag. MCP has a standard slot for it (`annotations.readOnlyHint`). I ended up hand-maintaining a classification table because of this, and I would be glad to open a small PR exposing it if that would be useful.

**`PluginContext.projectPath` returns `""` rather than `null` when no project is open**, so an Elvis fallback never fires. It cost me a bug where a file path resolved to the filesystem root.

**`boss://plugin?id=<x>&action=<y>` resolves the handler by whatever id the caller writes**, which for a panel plugin is the panel id rather than the plugin id. Registering a `DeepLinkActionHandler` under the plugin id logs "No deep-link action handler registered" while the caller still receives `ok: true`, because dispatch succeeds and only the lookup fails. That asymmetry was hard to spot from the caller's side.

The new question: for a contribution that is a plugin, and so lives in its own repository like every other BOSS plugin rather than inside BossConsole, what should the hackathon submission point at? A PR in that repo, or do you want it referenced somewhere here? `PLUGIN_DEVELOPMENT.md` describes the store as the distribution channel and I do not have a publish key, so I could not find a documented path. #79 looks like it is asking a version of the same thing.
