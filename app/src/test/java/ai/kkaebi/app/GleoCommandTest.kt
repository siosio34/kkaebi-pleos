package ai.kkaebi.app

import ai.kkaebi.app.gleo.GleoCommand
import ai.kkaebi.app.gleo.GleoRequest
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GleoCommandTest {
    private val action = GleoCommand.ACTION_PREPARE_SLEEP
    private fun parse(value: Any?) = GleoCommand.parse(action, mapOf(GleoCommand.ACTION_PARAM to value))!!

    @Test fun documentedEmptyWireFormatsOnlyPrepare() {
        assertEquals("rest-mode", GleoCommand.parse(action)!!.command)
        assertEquals("rest-mode", parse("{}").command)
        assertEquals("rest-mode", parse(" \r\n{\t }\n").command)
    }

    @Test fun emptyObjectAllowsOnlyJsonWhitespaceAtEveryPosition() {
        for (char in listOf(' ', '\t', '\r', '\n')) {
            assertEquals("rest-mode", parse("$char{$char}$char").command)
        }
        for (char in listOf('\u0000', '\u000B', '\u000C', '\u001C', '\u00A0', '\u2003', '\uFEFF')) {
            for (value in listOf("$char{}", "{$char}", "{}$char")) {
                assertNull("Non-JSON whitespace U+${char.code.toString(16)}", parse(value).command)
            }
        }
    }

    @Test fun emptyObjectLengthAndBraceBoundariesStayStrict() {
        assertEquals("rest-mode", parse("{" + " ".repeat(254) + "}").command)
        assertNull(parse("{" + " ".repeat(255) + "}").command)
        for (value in listOf("", " ", "}", "{", "}{", "{{}}", "{ } { }", "{\n}\n{")) {
            assertNull("Rejected $value", parse(value).command)
        }
    }

    @Test fun unknownActionsNeverUseACommandExtra() {
        for (unknown in listOf(null, "android.intent.action.MAIN", "PREPARE_SLEEP_MODE;applyRest", "prepare_sleep_mode")) {
            assertNull(GleoCommand.parse(unknown, mapOf("command" to "rest-mode")))
        }
    }

    @Test fun payloadCannotAuthorizeHardwareOrAlarm() {
        val payloads = listOf("{\"command\":\"applyRest\"}", "{\"startAlarm\":true}",
            "{\"durationMinutes\":20}", "{\"motionConsent\":true}", "{\"command\":\"rest-mode\",\"command\":\"applyRest\"}")
        for (payload in payloads) assertNull(payload, parse(payload).command)
        assertNull(GleoCommand.parse(action, mapOf("command" to "rest-mode"))!!.command)
        assertNull(GleoCommand.parse(action, mapOf(GleoCommand.ACTION_PARAM to "{}", "command" to "rest-mode"))!!.command)
    }

    @Test fun strictInputRejectsNonStringMalformedNestedAndOversizedValues() {
        for (value in listOf(null, true, 1, mapOf<String, String>(), listOf("{}"), "null", "[]", "\"{}\"", "{", "{}{}", "{} trailing", "{/* comment */}", "{\"x\":{}}", " ".repeat(257) + "{}")) {
            assertNull("Rejected $value", parse(value).command)
        }
    }

    @Test fun legacyFormatDoesNotGainNewPowers() {
        for (command in listOf("rest-mode", "drowsy-mode", "quiet")) {
            val request = GleoCommand.parse(GleoCommand.ACTION_PREPARE, mapOf("command" to command))!!
            assertEquals(command, request.command)
            assertNull(GleoCommand.response(request))
        }
        for (command in listOf<Any?>(null, 20, true, "applyRest", "startAlarm", "restoreRest", "rest-mode;applyRest")) {
            assertNull(GleoCommand.parse(GleoCommand.ACTION_PREPARE, mapOf("command" to command))!!.command)
        }
    }

    @Test fun callbackNeverClaimsStartedSleepOrHardwareSuccess() {
        val accepted = GleoCommand.response(GleoCommand.parse(action)!!)!!
        assertEquals("asking", accepted.status)
        assertEquals(action, accepted.functionName)
        assertTrue(accepted.message.contains("confirm"))
        assertEquals("fail", GleoCommand.response(parse("[]"))!!.status)
        assertEquals("fail", GleoCommand.response(GleoCommand.parse(action)!!, false)!!.status)
        assertNull(GleoCommand.response(GleoRequest("applyRest", "rest-mode", true)))
        val json = JsonParser.parseString(accepted.toJson()).asJsonObject
        assertEquals(setOf("functionName", "status", "message", "mode"), json.keySet())
    }

    @Test fun packagedSchemasUseVariantPackageAndPrepareOnlyContract() {
        val root = if (File("src/main/assets/schema.json").exists()) File(".") else File("app")
        for ((variant, packageName) in listOf("main" to "ai.kkaebi.app", "release" to "ai.kkaebi.app.contest")) {
            val schema = JsonParser.parseString(File(root, "src/$variant/assets/schema.json").readText()).asJsonObject
            assertEquals(packageName, schema["packageName"].asString)
            val actions = schema["actionCalls"].asJsonArray
            assertEquals(1, actions.size())
            val function = actions[0].asJsonObject["function"].asJsonObject
            assertEquals(action, function["name"].asString)
            assertEquals("activity", function["component"].asString)
            assertEquals(0, function["parameters"].asJsonObject["properties"].asJsonObject.size())
        }
    }
}
