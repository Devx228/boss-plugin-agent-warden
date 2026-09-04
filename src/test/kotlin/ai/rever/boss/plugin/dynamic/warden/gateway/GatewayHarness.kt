package ai.rever.boss.plugin.dynamic.warden.gateway

import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs [McpGateway] standalone against a live BOSS MCP endpoint, so the wire
 * behaviour can be driven with curl without loading the plugin into BOSS.
 *
 * This exists because the interesting failures are protocol-level - a dropped
 * session header, a response an agent reads as a broken server - and reproducing
 * them through the full plugin load cycle would make each iteration a rebuild, a
 * jar copy and a panel reload.
 *
 * Profile is taken from the first argument so all three can be exercised without
 * recompiling. Approval is answered from the second: a harness cannot show a
 * dialog, and blocking forever on a person who is not there would look like the
 * gateway hanging.
 *
 * Not a test. `./gradlew runGatewayHarness`, then drive it with curl.
 */
fun main(args: Array<String>) {
    val profile = Profile.byId(args.getOrNull(0) ?: Profile.READ_ONLY.id)
    val approveAnswer = args.getOrNull(1)?.toBooleanStrictOrNull() ?: true
    val counter = AtomicLong(1)

    val gateway =
        McpGateway(
            upstream = URI.create("http://127.0.0.1:7677/mcp"),
            decide = { _, capability -> profile.decide(capability) },
            approve = { tool, capability, preview ->
                println("  ASK   $tool [${capability.label}] $preview -> $approveAnswer")
                if (approveAnswer) ApprovalOutcome.APPROVED else ApprovalOutcome.REFUSED
            },
            onRecord = { r ->
                println(
                    "  ${r.outcome.name.padEnd(8)} ${r.toolName.padEnd(20)} " +
                        "[${r.capability.name}] ${r.argumentsPreview} (${r.durationMillis}ms)",
                )
            },
            nextRecordId = { counter.getAndIncrement() },
            hiddenCapabilities = { profile.hardDenied },
        )

    val port = gateway.start(7688)
    println("gateway on http://127.0.0.1:$port/mcp -> 127.0.0.1:7677")
    println("profile='${profile.name}' ceiling=${profile.ceiling} approvalAnswer=$approveAnswer")
    println("hidden from tools/list: ${profile.hardDenied.joinToString { it.label }}")
    Runtime.getRuntime().addShutdownHook(Thread { gateway.stop() })
    Thread.currentThread().join()
}
