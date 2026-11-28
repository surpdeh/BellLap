package net.belllap.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalSerializationApi::class)
class SidecarTest {
    private val sample = Sidecar(
        id = "abc",
        name = "2026-11-28 Finish 1",
        videoFile = "acq_abc.mp4",
        device = SidecarDevice("Google", "Pixel 8", 36),
        fps = 60,
        width = 1920,
        height = 1080,
        line = FinishLine(NormPoint(0.25f, 0.1f), NormPoint(0.75f, 0.9f)),
        firstFramePtsUs = 123_456_789L,
        crossings = listOf(SidecarCrossing(2_000_000, 120, 0.9f, CrossingSource.LIVE)),
        flagIntervals = listOf(FlagInterval(0, 1_000_000)),
        appVersion = "0.1.0",
    )

    private val schema: JsonObject =
        Json.parseToJsonElement(File("../../docs/sidecar.schema.json").readText()).jsonObject

    @Test
    fun roundTripsThroughStrictDecode() {
        assertEquals(sample, Sidecar.decode(sample.encode()))
        val noLine = sample.copy(line = null, crossings = emptyList(), flagIntervals = emptyList())
        assertEquals(noLine, Sidecar.decode(noLine.encode()))
    }

    @Test
    fun decodeRejectsMalformedUnknownMissingAndInvalid() {
        assertThrows(SidecarFormatException::class.java) { Sidecar.decode("not json") }
        val json = Json.parseToJsonElement(sample.encode()).jsonObject
        val unknown = JsonObject(json + ("extra" to JsonPrimitive(1))).toString()
        assertThrows(SidecarFormatException::class.java) { Sidecar.decode(unknown) }
        val missing = JsonObject(json - "fps").toString()
        assertThrows(SidecarFormatException::class.java) { Sidecar.decode(missing) }
        val badLine = sample.encode().replace("\"x\":0.25", "\"x\":2.0")
        assertThrows(SidecarFormatException::class.java) { Sidecar.decode(badLine) }
        val e = assertThrows(SidecarFormatException::class.java) {
            Sidecar.decode(sample.copy(fps = 0, crossings = listOf(sample.crossings[0].copy(confidence = 2f))).encode())
        }
        assertTrue(e.message, "fps must be positive" in e.message!! && "confidence" in e.message!!)
    }

    @Test
    fun refinedCrossingsReplaceLiveOnesAndKeepManualEdits() {
        val live = SidecarCrossing(2_000L, 2, 0.9f, CrossingSource.LIVE)
        val manual = SidecarCrossing(5_000L, 5, 1f, CrossingSource.MANUAL)
        val refined = SidecarCrossing(1_900L, 2, 0.8f, CrossingSource.REFINED)
        val merged = sample.copy(crossings = listOf(live, manual)).withRefinedCrossings(listOf(refined))
        assertEquals(listOf(refined, manual), merged.crossings)
        val cleared = sample.copy(crossings = listOf(live)).withRefinedCrossings(emptyList())
        assertEquals(emptyList<SidecarCrossing>(), cleared.crossings)
    }

    @Test
    fun validateFlagsBackwardsFlagIntervals() {
        assertEquals(emptyList<String>(), sample.validate())
        assertEquals(1, sample.copy(flagIntervals = listOf(FlagInterval(5, 1))).validate().size)
    }

    @Test
    fun encodedSidecarSatisfiesTheCommittedSchema() {
        assertEquals(emptyList<String>(), check(schema, Json.parseToJsonElement(sample.encode()), "$"))
        val noLine = sample.copy(line = null)
        assertEquals(emptyList<String>(), check(schema, Json.parseToJsonElement(noLine.encode()), "$"))
    }

    @Test
    fun schemaRejectsWhatTheDecoderRejects() {
        val json = Json.parseToJsonElement(sample.encode()).jsonObject
        assertTrue(check(schema, JsonObject(json + ("extra" to JsonPrimitive(1))), "$").isNotEmpty())
        assertTrue(check(schema, JsonObject(json - "fps"), "$").isNotEmpty())
        assertTrue(check(schema, JsonObject(json + ("fps" to JsonPrimitive(0))), "$").isNotEmpty())
        val badSource = sample.encode().replace("\"live\"", "\"gps\"")
        assertTrue(check(schema, Json.parseToJsonElement(badSource), "$").isNotEmpty())
    }

    @Test
    fun schemaPropertiesMatchTheSerializedFields() {
        fun names(d: SerialDescriptor) = (0 until d.elementsCount).map { d.getElementName(it) }.toSet()

        fun props(o: JsonElement) = o.jsonObject["properties"]!!.jsonObject
        val top = props(schema)
        val crossing = props(top["crossings"]!!.jsonObject["items"]!!)
        val flag = props(top["flagIntervals"]!!.jsonObject["items"]!!)
        assertEquals(names(Sidecar.serializer().descriptor), top.keys)
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(names(Sidecar.serializer().descriptor), required)
        assertEquals(names(SidecarDevice.serializer().descriptor), props(top["device"]!!).keys)
        assertEquals(names(SidecarCrossing.serializer().descriptor), crossing.keys)
        assertEquals(names(FlagInterval.serializer().descriptor), flag.keys)
        val sources = crossing["source"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("live", "refined", "manual"), sources)
    }

    /** A tiny JSON Schema checker for the keywords this schema uses; returns the problems found. */
    @Suppress("ReturnCount")
    private fun check(s: JsonObject, v: JsonElement, at: String): List<String> {
        s["\$ref"]?.let { ref ->
            val name = ref.jsonPrimitive.content.substringAfterLast('/')
            return check(schema["\$defs"]!!.jsonObject[name]!!.jsonObject, v, at)
        }
        s["oneOf"]?.let { alts ->
            val matches = alts.jsonArray.count { check(it.jsonObject, v, at).isEmpty() }
            return if (matches == 1) emptyList() else listOf("$at matches $matches of oneOf")
        }
        val type = s["type"]?.jsonPrimitive?.content
        if (type != null && !typeOk(type, v)) return listOf("$at is not $type")
        val out = mutableListOf<String>()
        if (s["enum"]?.jsonArray?.none { it == v } == true) out += "$at not in enum"
        if (v is JsonPrimitive) out += checkScalar(s, v, at)
        if (v is JsonObject) out += checkObject(s, v, at)
        if (v is JsonArray) {
            s["items"]?.jsonObject?.let { item -> v.forEachIndexed { i, e -> out += check(item, e, "$at[$i]") } }
        }
        return out
    }

    private fun checkScalar(s: JsonObject, v: JsonPrimitive, at: String): List<String> {
        val out = mutableListOf<String>()
        val num = v.doubleOrNull.takeIf { !v.isString }
        val min = s["minimum"]?.jsonPrimitive?.content?.toDouble()
        val max = s["maximum"]?.jsonPrimitive?.content?.toDouble()
        val minLength = s["minLength"]?.jsonPrimitive?.content?.toInt()
        if (num != null && min != null && num < min) out += "$at below minimum"
        if (num != null && max != null && num > max) out += "$at above maximum"
        if (v.isString && minLength != null && v.content.length < minLength) out += "$at too short"
        return out
    }

    private fun checkObject(s: JsonObject, v: JsonObject, at: String): List<String> {
        val out = mutableListOf<String>()
        s["required"]?.jsonArray?.forEach { r -> if (r.jsonPrimitive.content !in v) out += "$at missing $r" }
        val props = s["properties"]?.jsonObject
        if (s["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false) {
            v.keys.filter { props == null || it !in props }.forEach { out += "$at has unknown $it" }
        }
        props?.forEach { (k, sub) -> v[k]?.let { out += check(sub.jsonObject, it, "$at.$k") } }
        return out
    }

    private fun typeOk(type: String, v: JsonElement): Boolean = when (type) {
        "object" -> v is JsonObject
        "array" -> v is JsonArray
        "string" -> v is JsonPrimitive && v.isString
        "null" -> v is JsonNull
        "integer" -> v is JsonPrimitive && !v.isString && v.longOrNull != null
        "number" -> v is JsonPrimitive && !v.isString && v.doubleOrNull != null
        else -> false
    }
}
