package io.github.cdsap.gbos.core

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GbosDevelocityTest {

    @Test
    fun emitsSchemaProducerHeaderThenObservationsInInputOrder() {
        val first = workerObservation(pid = 1, cpuTimeSec = 2.0)
        val second = workerObservation(pid = 2, cpuTimeSec = 1.0)

        val values = publish(listOf(first, second))

        assertEquals(
            listOf(
                "gbos.schema" to "1.0.0",
                "gbos.v1.producer.info_test_process.version" to "0.0.4",
                "gbos.v1.producer.info_test_process.name" to "info-test-process",
                OBSERVATION_KEY to GbosJson.encodeFragment(first),
                OBSERVATION_KEY to GbosJson.encodeFragment(second)
            ),
            values
        )
    }

    @Test
    fun emptyObservationListEmitsNothing() {
        assertEquals(emptyList<Pair<String, String>>(), publish(emptyList(), indexes = INFO_TEST_PROCESS_INDEXES))
    }

    @Test
    fun observationValuesValidateAgainstTheFragmentSchema() {
        val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(SchemaLocation.of("classpath:schema/observation-fragment.schema.json"))

        fun errors(value: String) = schema.validate(value, InputFormat.JSON) { context ->
            context.executionConfig { it.formatAssertionsEnabled(true) }
        }

        val observations = publish(fixtureObservations()).filter { it.first == OBSERVATION_KEY }

        assertEquals(4, observations.size)
        observations.forEach { (_, value) ->
            val errors = errors(value)
            assertTrue("observation fragment is invalid: $errors\n$value", errors.isEmpty())
        }
        assertTrue(errors(GbosJson.encode(fixtureObservations().first())).isNotEmpty())
    }

    @Test
    fun indexesAreEmittedOnlyForRegisteredMetricAggregationPairs() {
        val registered = publish(fixtureObservations(), indexes = INFO_TEST_PROCESS_INDEXES)
            .filter { it.first.startsWith("gbos.v1.index.") }
        assertEquals(EXPECTED_INDEXES, registered)
        assertTrue(registered.map { it.first }.all { it in registryIndexNames() })

        val onlyCpuTime = publish(fixtureObservations(), indexes = listOf(GbosIndex("jvm.process.cpu.time", "sum")))
            .filter { it.first.startsWith("gbos.v1.index.") }
        assertEquals(listOf("gbos.v1.index.info_test_process.jvm.process.cpu.time.sum" to "303"), onlyCpuTime)

        val unregistered = publish(fixtureObservations())
        assertTrue(unregistered.none { it.first.startsWith("gbos.v1.index.") })
    }

    @Test
    fun indexesWithoutAMatchingBuildMeasurementAreSkipped() {
        val entityOnly = publish(listOf(workerObservation(pid = 1, cpuTimeSec = 1.0)), indexes = INFO_TEST_PROCESS_INDEXES)
        assertTrue(entityOnly.none { it.first.startsWith("gbos.v1.index.") })

        val missingPair = publish(fixtureObservations(), indexes = listOf(GbosIndex("jvm.process.cpu.time", "max")))
        assertTrue(missingPair.none { it.first.startsWith("gbos.v1.index.") })
    }

    @Test
    fun matchesInfoTestProcessProjectionOfTheCharacterizationFixture() {
        assertEquals(INFO_TEST_PROCESS_GOLDEN, publish(fixtureObservations(), indexes = INFO_TEST_PROCESS_INDEXES))
    }

    @Test
    fun producerSlugReplacesDotsAndDashesWithUnderscores() {
        assertEquals("my_org_build_plugin", GbosDevelocity.producerSlug("my.org-build.plugin"))

        val values = publish(
            listOf(workerObservation(pid = 1, cpuTimeSec = 1.0)),
            producerName = "my.org-build.plugin"
        )
        assertEquals(
            listOf(
                "gbos.schema",
                "gbos.v1.producer.my_org_build_plugin.version",
                "gbos.v1.producer.my_org_build_plugin.name",
                "gbos.v1.producer.my_org_build_plugin.observation"
            ),
            values.map { it.first }
        )
        assertEquals("my.org-build.plugin", values[2].second)
    }

    private fun publish(
        observations: List<GbosObservation>,
        producerName: String = "info-test-process",
        indexes: List<GbosIndex> = emptyList()
    ): List<Pair<String, String>> {
        val values = mutableListOf<Pair<String, String>>()
        GbosDevelocity.publish(
            sink = { key, value -> values += key to value },
            schemaVersion = "1.0.0",
            producerName = producerName,
            producerVersion = "0.0.4",
            observations = observations,
            indexes = indexes
        )
        return values
    }

    private fun registryIndexNames(): Set<String> {
        val text = javaClass.classLoader.getResourceAsStream("registry/develocity-indexes.json")!!
            .bufferedReader().use { it.readText() }
        return Json.parseToJsonElement(text).jsonObject.getValue("indexes").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }
            .toSet()
    }

    private fun workerObservation(pid: Long, cpuTimeSec: Double) = observation(
        aggregationScope = "entity",
        attributes = mapOf("process.pid" to GbosAttributeValue.Integer(pid)),
        measurements = listOf(GbosMeasurement("jvm.process.cpu.time", cpuTimeSec, "s", "sum"))
    )

    /** The observations InfoTestProcess's CharacterizationTest builds from workers 41001, 41002 and 41003. */
    private fun fixtureObservations(): List<GbosObservation> = listOf(
        observation(
            aggregationScope = "entity",
            attributes = mapOf(
                "process.pid" to GbosAttributeValue.Integer(41001),
                "jvm.process.role" to GbosAttributeValue.Text("test-worker"),
                "gradle.task.path" to GbosAttributeValue.Text(":app:test"),
                "gradle.test.executor" to GbosAttributeValue.Text("Gradle Test Executor 1"),
                "jvm.gc.name" to GbosAttributeValue.Text("G1")
            ),
            measurements = listOf(
                GbosMeasurement("jvm.process.memory.heap.limit", 536870912.0, "By", "last"),
                GbosMeasurement("jvm.process.uptime", 60.0, "s", "last"),
                GbosMeasurement("jvm.process.cpu.time", 300.0, "s", "sum"),
                GbosMeasurement("jvm.process.cpu.cores", 5.0, "{core}", "last"),
                GbosMeasurement("jvm.process.memory.heap.used", 268435456.0, "By", "last"),
                GbosMeasurement("jvm.process.memory.heap.peak", 322122547.0, "By", "max"),
                GbosMeasurement("jvm.process.memory.metaspace.peak", 104857600.0, "By", "max"),
                GbosMeasurement("jvm.process.gc.collections", 12.0, "{collection}", "count"),
                GbosMeasurement("jvm.process.gc.time", 1.5, "s", "sum"),
                GbosMeasurement("jvm.process.jit.time", 4.0, "s", "sum"),
                GbosMeasurement("jvm.process.classes.loaded", 9000.0, "{class}", "last"),
                GbosMeasurement("jvm.process.threads.peak", 40.0, "{thread}", "max")
            )
        ),
        observation(
            aggregationScope = "entity",
            attributes = mapOf(
                "process.pid" to GbosAttributeValue.Integer(41002),
                "jvm.process.role" to GbosAttributeValue.Text("test-worker"),
                "gradle.task.path" to GbosAttributeValue.Text(":lib:test"),
                "gradle.test.executor" to GbosAttributeValue.Text("Gradle Test Executor 2"),
                "jvm.gc.name" to GbosAttributeValue.Text("Parallel")
            ),
            measurements = listOf(
                GbosMeasurement("jvm.process.memory.heap.limit", 1073741824.0, "By", "last"),
                GbosMeasurement("jvm.process.uptime", 120.0, "s", "last"),
                GbosMeasurement("jvm.process.cpu.time", 3.0, "s", "sum"),
                GbosMeasurement("jvm.process.cpu.cores", 0.03, "{core}", "last"),
                GbosMeasurement("jvm.process.memory.heap.used", 536870912.0, "By", "last"),
                GbosMeasurement("jvm.process.memory.heap.peak", 966367642.0, "By", "max"),
                GbosMeasurement("jvm.process.memory.metaspace.peak", 52428800.0, "By", "max"),
                GbosMeasurement("jvm.process.gc.collections", 3.0, "{collection}", "count"),
                GbosMeasurement("jvm.process.gc.time", 0.2, "s", "sum"),
                GbosMeasurement("jvm.process.jit.time", 2.0, "s", "sum"),
                GbosMeasurement("jvm.process.classes.loaded", 4000.0, "{class}", "last"),
                GbosMeasurement("jvm.process.threads.peak", 20.0, "{thread}", "max")
            )
        ),
        observation(
            aggregationScope = "entity",
            attributes = mapOf(
                "process.pid" to GbosAttributeValue.Integer(41003),
                "jvm.process.role" to GbosAttributeValue.Text("test-worker"),
                "gradle.task.path" to GbosAttributeValue.Text(":lib:test"),
                "gradle.test.executor" to GbosAttributeValue.Text("Gradle Test Executor pid-41003")
            ),
            measurements = listOf(GbosMeasurement("jvm.process.memory.heap.limit", 268435456.0, "By", "last")),
            diagnostics = listOf(GbosDiagnostic(code = "gbos.test_process.stats_snapshot_missing", severity = "warning"))
        ),
        observation(
            aggregationScope = "build",
            attributes = mapOf("jvm.process.role" to GbosAttributeValue.Text("test-worker")),
            measurements = listOf(
                GbosMeasurement("jvm.process.cpu.cores", 5.0, "{core}", "max"),
                GbosMeasurement("jvm.process.cpu.time", 303.0, "s", "sum"),
                GbosMeasurement("jvm.process.memory.heap.peak", 966367642.0, "By", "max"),
                GbosMeasurement("jvm.process.memory.metaspace.peak", 104857600.0, "By", "max"),
                GbosMeasurement("jvm.process.jit.time", 6.0, "s", "sum"),
                GbosMeasurement("jvm.process.jit.time", 4.0, "s", "max"),
                GbosMeasurement("jvm.process.classes.loaded", 9000.0, "{class}", "max"),
                GbosMeasurement("jvm.process.gc.collections", 15.0, "{collection}", "sum"),
                GbosMeasurement("jvm.process.uptime", 180.0, "s", "sum")
            )
        )
    )

    private fun observation(
        aggregationScope: String,
        attributes: Map<String, GbosAttributeValue>,
        measurements: List<GbosMeasurement>,
        diagnostics: List<GbosDiagnostic> = emptyList()
    ) = GbosObservation(
        schemaVersion = "1.0.0",
        producer = GbosProducer(name = "info-test-process", version = "2.1.0"),
        scope = "jvm.process",
        aggregationScope = aggregationScope,
        attributes = attributes,
        measurements = measurements,
        diagnostics = diagnostics
    )

    private companion object {
        const val OBSERVATION_KEY = "gbos.v1.producer.info_test_process.observation"

        val INFO_TEST_PROCESS_INDEXES = listOf(
            GbosIndex("jvm.process.cpu.cores", "max"),
            GbosIndex("jvm.process.cpu.time", "sum"),
            GbosIndex("jvm.process.memory.heap.peak", "max")
        )

        val EXPECTED_INDEXES = listOf(
            "gbos.v1.index.info_test_process.jvm.process.cpu.cores.max" to "5",
            "gbos.v1.index.info_test_process.jvm.process.cpu.time.sum" to "303",
            "gbos.v1.index.info_test_process.jvm.process.memory.heap.peak.max" to "966367642"
        )

        // Recorded from InfoTestProcess 2.1.0 (BuildScanReport(publishGbos = true) on its
        // CharacterizationTest fixture, built against build-observability-core 0.0.6).
        val INFO_TEST_PROCESS_GOLDEN = listOf(
            "gbos.schema" to "1.0.0",
            "gbos.v1.producer.info_test_process.version" to "0.0.4",
            "gbos.v1.producer.info_test_process.name" to "info-test-process",
            OBSERVATION_KEY to """{"scope":"jvm.process","aggregationScope":"entity","attributes":{"process.pid":41001,"jvm.process.role":"test-worker","gradle.task.path":":app:test","gradle.test.executor":"Gradle Test Executor 1","jvm.gc.name":"G1"},"measurements":[{"name":"jvm.process.memory.heap.limit","value":536870912,"unit":"By","aggregation":"last"},{"name":"jvm.process.uptime","value":60,"unit":"s","aggregation":"last"},{"name":"jvm.process.cpu.time","value":300,"unit":"s","aggregation":"sum"},{"name":"jvm.process.cpu.cores","value":5,"unit":"{core}","aggregation":"last"},{"name":"jvm.process.memory.heap.used","value":268435456,"unit":"By","aggregation":"last"},{"name":"jvm.process.memory.heap.peak","value":322122547,"unit":"By","aggregation":"max"},{"name":"jvm.process.memory.metaspace.peak","value":104857600,"unit":"By","aggregation":"max"},{"name":"jvm.process.gc.collections","value":12,"unit":"{collection}","aggregation":"count"},{"name":"jvm.process.gc.time","value":1.5,"unit":"s","aggregation":"sum"},{"name":"jvm.process.jit.time","value":4,"unit":"s","aggregation":"sum"},{"name":"jvm.process.classes.loaded","value":9000,"unit":"{class}","aggregation":"last"},{"name":"jvm.process.threads.peak","value":40,"unit":"{thread}","aggregation":"max"}]}""",
            OBSERVATION_KEY to """{"scope":"jvm.process","aggregationScope":"entity","attributes":{"process.pid":41002,"jvm.process.role":"test-worker","gradle.task.path":":lib:test","gradle.test.executor":"Gradle Test Executor 2","jvm.gc.name":"Parallel"},"measurements":[{"name":"jvm.process.memory.heap.limit","value":1073741824,"unit":"By","aggregation":"last"},{"name":"jvm.process.uptime","value":120,"unit":"s","aggregation":"last"},{"name":"jvm.process.cpu.time","value":3,"unit":"s","aggregation":"sum"},{"name":"jvm.process.cpu.cores","value":0.03,"unit":"{core}","aggregation":"last"},{"name":"jvm.process.memory.heap.used","value":536870912,"unit":"By","aggregation":"last"},{"name":"jvm.process.memory.heap.peak","value":966367642,"unit":"By","aggregation":"max"},{"name":"jvm.process.memory.metaspace.peak","value":52428800,"unit":"By","aggregation":"max"},{"name":"jvm.process.gc.collections","value":3,"unit":"{collection}","aggregation":"count"},{"name":"jvm.process.gc.time","value":0.2,"unit":"s","aggregation":"sum"},{"name":"jvm.process.jit.time","value":2,"unit":"s","aggregation":"sum"},{"name":"jvm.process.classes.loaded","value":4000,"unit":"{class}","aggregation":"last"},{"name":"jvm.process.threads.peak","value":20,"unit":"{thread}","aggregation":"max"}]}""",
            OBSERVATION_KEY to """{"scope":"jvm.process","aggregationScope":"entity","attributes":{"process.pid":41003,"jvm.process.role":"test-worker","gradle.task.path":":lib:test","gradle.test.executor":"Gradle Test Executor pid-41003"},"measurements":[{"name":"jvm.process.memory.heap.limit","value":268435456,"unit":"By","aggregation":"last"}],"diagnostics":[{"code":"gbos.test_process.stats_snapshot_missing","severity":"warning"}]}""",
            OBSERVATION_KEY to """{"scope":"jvm.process","aggregationScope":"build","attributes":{"jvm.process.role":"test-worker"},"measurements":[{"name":"jvm.process.cpu.cores","value":5,"unit":"{core}","aggregation":"max"},{"name":"jvm.process.cpu.time","value":303,"unit":"s","aggregation":"sum"},{"name":"jvm.process.memory.heap.peak","value":966367642,"unit":"By","aggregation":"max"},{"name":"jvm.process.memory.metaspace.peak","value":104857600,"unit":"By","aggregation":"max"},{"name":"jvm.process.jit.time","value":6,"unit":"s","aggregation":"sum"},{"name":"jvm.process.jit.time","value":4,"unit":"s","aggregation":"max"},{"name":"jvm.process.classes.loaded","value":9000,"unit":"{class}","aggregation":"max"},{"name":"jvm.process.gc.collections","value":15,"unit":"{collection}","aggregation":"sum"},{"name":"jvm.process.uptime","value":180,"unit":"s","aggregation":"sum"}]}""",
            "gbos.v1.index.info_test_process.jvm.process.cpu.cores.max" to "5",
            "gbos.v1.index.info_test_process.jvm.process.cpu.time.sum" to "303",
            "gbos.v1.index.info_test_process.jvm.process.memory.heap.peak.max" to "966367642"
        )
    }
}
