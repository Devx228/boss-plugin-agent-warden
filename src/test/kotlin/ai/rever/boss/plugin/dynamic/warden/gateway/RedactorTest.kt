package ai.rever.boss.plugin.dynamic.warden.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The redactor stands between tool arguments and two places they must never reach
 * intact: persisted plugin storage, and a panel that gets screenshotted into a
 * hackathon submission. These tests are written from the leak backwards.
 */
class RedactorTest {
    private fun args(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun `values under sensitive keys are never shown at all`() {
        val preview =
            Redactor.preview(
                args("""{"password":"hunter2","api_key":"sk-live-abc","authToken":"xyz","COOKIE":"a=b"}"""),
            )
        for (secret in listOf("hunter2", "sk-live-abc", "xyz", "a=b")) {
            assertFalse(secret in preview, "leaked $secret via a sensitive key: $preview")
        }
    }

    @Test
    fun `sensitive key matching is case insensitive and matches substrings`() {
        // Real argument names are not normalised: BOSS's own tools use snake_case,
        // callers send camelCase, and env maps arrive SCREAMING. A matcher anchored
        // to whole lowercase words would miss most of them.
        for (key in listOf("Password", "PASSWORD", "userPassword", "db_secret", "X-Auth-Token", "sessionId")) {
            val preview = Redactor.preview(args("""{"$key":"leakme12345"}"""))
            assertFalse("leakme12345" in preview, "key '$key' was not treated as sensitive: $preview")
        }
    }

    @Test
    fun `long key-like values are truncated even under an innocuous key`() {
        // The dangerous case is not `password`, it is a token pasted into a shell
        // command, where the key is `script`. Nothing can parse that reliably, so
        // long unbroken runs are cut to a length that is useless for reuse.
        val token = "ghp_" + "A".repeat(40)
        val preview = Redactor.preview(args("""{"script":"curl -H 'Authorization: Bearer $token' api.example.com"}"""))
        assertFalse(token in preview, "full token survived: $preview")
        assertTrue("chars>" in preview, "expected a length marker in place of the token: $preview")
    }

    @Test
    fun `short ordinary values are left readable`() {
        // Redaction that hides everything makes the ledger useless and the operator
        // stops reading it, so the useful half has to survive.
        val preview = Redactor.preview(args("""{"tab_id":"main","lines":200}"""))
        assertTrue("main" in preview, preview)
        assertTrue("200" in preview, preview)
    }

    @Test
    fun `whitespace is flattened so one entry stays one line`() {
        // Ledger rows and dialog text are single-line. A multi-line script argument
        // would otherwise break the layout and push later entries out of view.
        val preview = Redactor.preview(args("""{"script":"echo one\n\n  echo two\techo three"}"""))
        assertFalse("\n" in preview, "newline survived into a single-line preview")
        assertFalse("\t" in preview, "tab survived into a single-line preview")
    }

    @Test
    fun `overall length is bounded regardless of argument count`() {
        val many = (1..50).joinToString(",") { """"field$it":"value$it"""" }
        val preview = Redactor.preview(args("{$many}"))
        assertTrue(preview.length <= 230, "preview grew to ${preview.length}, unbounded by argument count")
    }

    @Test
    fun `empty and absent arguments render as an empty object, not a crash`() {
        assertEquals("{}", Redactor.preview(null))
        assertEquals("{}", Redactor.preview(args("{}")))
    }

    @Test
    fun `nested objects and arrays are scrubbed rather than dumped raw`() {
        // A nested structure previously stringified whole, which put an entire
        // env-var map into the ledger in one field.
        val token = "B".repeat(40)
        val preview = Redactor.preview(args("""{"env":{"HOME":"/root","TOKEN":"$token"},"list":[1,2,3]}"""))
        assertFalse(token in preview, "nested value leaked in full: $preview")
    }

    @Test
    fun `preview never returns the raw json of its input`() {
        // Blunt backstop for the whole class: whatever the shape, the output must not
        // be reconstructible into the original arguments.
        val raw = """{"script":"rm -rf /","password":"p"}"""
        val preview = Redactor.preview(args(raw))
        assertFalse(preview.contains(raw), "preview echoed its raw input")
    }
}
