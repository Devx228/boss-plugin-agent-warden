# Fresh review handoff - 12 September 2026

This is a review record for the next contributor. It does not change runtime
behaviour. The repository is clean at `c717eb6` before this document.

## Verified baseline

The existing test suite was rerun with `./gradlew.bat test --rerun-tasks`.
It completed successfully with 228 tests, zero failures, zero errors and zero
skipped tests. The plugin repo has no uncommitted source changes from this
review. The BossConsole checkout was left untouched because it already has
unrelated work in progress.

The public open plugin PRs were read. The relevant overlap is:

- `db-browser` already provides PostgreSQL and SQLite browsing, `db_query`, and
  `db_execute` with `db.write` RBAC.
- `run-ledger` records commands, commits, working-tree patches and rescued
  artifacts for reproducible runs.
- `Mission Control` provides human-agent handoff.
- `rpa-recorder` and `rpa-engine` already record and replay browser workflows.

Do not pitch another ordinary database browser, run ledger, browser recorder or
handoff panel.

## Security findings

### 1. `CommandRisk` has unsafe false positives

`CommandRisk.isReadOnly` returns true for every command whose first `git`
subcommand is in `READ_ONLY_GIT`, without validating the remaining options or
the repository configuration. It also returns true for `tree`, even though its
output option can write a report file.

The review harness ran against a temporary git repository and observed:

- `git branch review-created` was classified as a read and created a branch.
- `git diff --no-index --output=probe.patch left.txt right.txt` was classified
  as a read and created `probe.patch`.
- The allowlist includes `date`, although `date -s ...` changes the system
  clock when the process has permission.
- `hostname` and `tree` are accepted without option validation.

This breaks the documented invariant that a downgraded command provably only
reads. The fix should either remove commands with write-capable options from
the allowlist or validate a complete, command-specific option grammar. A
shorter allowlist is safer for a hackathon deadline.

### 2. A time grant for `UNKNOWN` grants unrelated tools

`ApprovalCoordinator` keys `GrantBook` by `Capability`. A user choosing
"Allow for a while" for one unknown tool grants `Capability.UNKNOWN`, so a
different unknown tool is silently granted until expiry. The harness observed:

```text
first=APPROVED, second=GRANTED, prompts=1
```

Unknown capabilities should not be grantable as a category. Either force
`UNKNOWN` to allow-once only or key grants by a narrower identity such as the
tool name and, for judged calls, a safe command class.

### 3. Stop and disable do not cancel an in-flight approval

`McpGateway.stop()` closes the HTTP server but does not cancel calls already
blocked in `runBlocking { approve(...) }`. In the harness, stopping while an
approval was held and then releasing it still forwarded the call upstream:

```text
upstreamDeltaAfterStop=1
```

The runtime's disable workaround therefore closes the listening socket but
does not guarantee that already-held calls cannot execute. Add a lifecycle
generation or cancellation token checked after approval and before forwarding,
and make disposal revoke that token. The result should be recorded as cancelled
or blocked and never sent upstream.

### 4. Upstream transport failures are absent from the ledger

`McpGateway.interceptToolCall` calls `forward()` before `record()` for calls
that pass policy. A connection failure throws through `handle`, so no
`InvocationRecord` is emitted. The harness observed `recordDelta=0` after
stopping the fake upstream. The audit should record a `FAILED` call with the
transport error, without returning the exception text to the agent if it could
contain sensitive data.

### 5. Redaction is not applied to the downgrade detail

The arguments preview is redacted, but `CommandRisk.downgradeReason(script)`
receives the raw script and is stored in `InvocationRecord.detail` and the
trace. The harness observed a credential-shaped test string in the detail even
though the preview was masked. Apply the same redactor to all persisted and
operator-visible detail, or make the detail describe only the rule name and
omit the command text.

### 6. The disable watcher can miss a transient unregister

`WardenHost.unregistered()` is a `StateFlow` pipeline with `dropWhile`,
`distinctUntilChanged` and `take(1)`. A present -> absent -> present sequence
can be conflated by `StateFlow`, and a subscriber that attaches after the
provider has already disappeared waits forever. The harness's synthetic flow
showed no disable signal in both cases. The host needs an explicit disable hook;
until then, the plugin should use a signal with event semantics or verify the
provider state before accepting each forwarded call.

### 7. Gateway executors are not shut down

`McpGateway.start()` creates a new fixed thread pool and `stop()` only stops the
HTTP server. The harness found five live non-daemon pool threads after stopping
three gateways. This leaks threads across restart, disable and port fallback.
Keep the executor as a field and call `shutdownNow()` during stop, with an
idempotent stop guard.

### 8. The classifier/catalog is already stale against the host source

The host's `McpMutatingToolCatalog` currently names 19 tools absent from
Agent Warden's 29-entry catalog: seven Docker tools, five Kubernetes tools,
four Helm tools, `secret_get`, `codebase_write` and `project_replace`. Unknown
still fails closed, which is safe but creates prompt fatigue. The durable fix
is the host discovery `readOnly` metadata proposed in BossConsole#496, not an
ever-growing hand-written table.

## Research direction

The most promising second plugin is a **change rehearsal and authorization
proof** workflow, not another CRUD database browser:

1. Accept a proposed migration or data change and create an isolated disposable
   database environment where the provider supports branching. Supabase says
   preview branches are separate environments and data-less by default. Neon
   already exposes `prepare_database_migration` and `complete_database_migration`,
   so the differentiator cannot be branch creation alone.
2. Run schema and query checks in the rehearsal environment. PostgreSQL's
   `EXPLAIN` gives a plan without execution unless `ANALYZE` is requested;
   `EXPLAIN ANALYZE` actually executes and can have side effects. The plugin
   must label those modes explicitly.
3. Exercise the app through BOSS's embedded browser using two or more identities
   or roles, capture the visible result, and compare database state with the
   browser evidence. The compelling failure is an authorization regression:
   each user sees another user's record after a change even though the happy path
   still works.
4. Produce a signed, local report containing the proposed SQL, schema diff,
   query plans, identity matrix, screenshots or browser evidence, and the final
   human decision. A green SQL lint result is not proof of tenant isolation.

This fits the hackathon's Web Workflow Kit and uses BOSS's distinctive browser,
database and operator-control surfaces together. It is materially different
from the existing db-browser's four JDBC tools and from Agent Warden's generic
MCP call audit.

The idea still needs a feasibility spike before implementation. The first spike
should use a disposable local Supabase/Postgres project and a tiny sample app,
prove that two browser profiles can be exercised, and verify that the report can
show a real cross-tenant failure without recording credentials.

Useful external references checked during this review:

- [MCP tool security guidance](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)
- [MCP local transport security](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)
- [Supabase branching](https://supabase.com/docs/guides/deployment/branching)
- [Supabase RLS testing](https://supabase.com/docs/guides/database/postgres/row-level-security)
- [PostgreSQL EXPLAIN](https://www.postgresql.org/docs/current/sql-explain.html)
- [PostgreSQL read-only transactions](https://www.postgresql.org/docs/current/sql-set-transaction.html)
- [Neon migration preparation](https://github.com/neondatabase/mcp-server-neon)
- [Browserbase session replay](https://docs.browserbase.com/platform/browser/observability/session-replay)

## Recommended takeover order

1. Add failing tests for the eight findings above, beginning with command
   classification and post-approval cancellation.
2. Fix the security regressions and rerun the live BOSS disable/re-enable test.
3. Build the isolated database/browser feasibility spike and record what is
   genuinely measurable.
4. Only then decide whether the rehearsal plugin is feasible before the deadline.

