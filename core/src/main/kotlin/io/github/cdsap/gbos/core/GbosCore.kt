package io.github.cdsap.gbos.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal

@Serializable
data class GbosProducer(val name: String, val version: String)

@Serializable
data class GbosMeasurement(
    val name: String,
    val value: Double,
    val unit: String,
    val aggregation: String
)

@Serializable
data class GbosDiagnostic(val code: String, val severity: String, val message: String? = null)

@Serializable
sealed interface GbosAttributeValue {
    @Serializable
    data class Text(val value: String) : GbosAttributeValue

    @Serializable
    data class Integer(val value: Long) : GbosAttributeValue
}

data class GbosObservation(
    val schemaVersion: String,
    val producer: GbosProducer,
    val scope: String,
    val aggregationScope: String,
    val attributes: Map<String, GbosAttributeValue>,
    val measurements: List<GbosMeasurement>,
    val partial: Boolean = false,
    val droppedObservations: Int? = null,
    val diagnostics: List<GbosDiagnostic> = emptyList()
)

object GbosJson {
    private val json = kotlinx.serialization.json.Json { prettyPrint = true }
    private val compactJson = kotlinx.serialization.json.Json

    fun encode(observation: GbosObservation): String = json.encodeToString(JsonObject.serializer(), toJsonObject(observation))

    fun encodeFragment(observation: GbosObservation): String =
        compactJson.encodeToString(JsonObject.serializer(), toJsonObject(observation, includeHeader = false))

    fun encodeReport(observations: List<GbosObservation>): String {
        require(observations.isNotEmpty()) { "cannot encode an empty report" }
        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("schemaVersion", observations.first().schemaVersion)
            put("resource", buildJsonObject { put("build.tool.name", "gradle") })
            put("observations", buildJsonArray { observations.forEach { add(toJsonObject(it)) } })
        })
    }

    private fun toJsonObject(observation: GbosObservation, includeHeader: Boolean = true): JsonObject = buildJsonObject {
        if (includeHeader) {
            put("schemaVersion", observation.schemaVersion)
            put("producer", json.encodeToJsonElement(observation.producer))
        }
        put("scope", observation.scope)
        put("aggregationScope", observation.aggregationScope)
        put("attributes", buildJsonObject {
            observation.attributes.forEach { (name, value) ->
                when (value) {
                    is GbosAttributeValue.Text -> put(name, value.value)
                    is GbosAttributeValue.Integer -> put(name, value.value)
                }
            }
        })
        if (observation.measurements.isNotEmpty()) {
            put("measurements", buildJsonArray {
                observation.measurements.forEach { measurement ->
                    add(buildJsonObject {
                        put("name", measurement.name)
                        put("value", jsonNumber(measurement.value))
                        put("unit", measurement.unit)
                        put("aggregation", measurement.aggregation)
                    })
                }
            })
        }
        if (observation.partial) put("partial", true)
        observation.droppedObservations?.let { put("droppedObservations", it) }
        if (observation.diagnostics.isNotEmpty()) put("diagnostics", json.encodeToJsonElement(observation.diagnostics))
    }
}

fun jsonNumber(value: Double): JsonPrimitive =
    JsonUnquotedLiteral(BigDecimal.valueOf(value).stripTrailingZeros().toPlainString())
