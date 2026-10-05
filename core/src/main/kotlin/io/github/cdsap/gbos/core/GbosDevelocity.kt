package io.github.cdsap.gbos.core

/**
 * Receives Develocity custom values in emission order.
 *
 * Adapt it to the Develocity API at the call site, for example
 * `GbosCustomValueSink { key, value -> buildScan.value(key, value) }`.
 */
fun interface GbosCustomValueSink {
    fun value(key: String, value: String)
}

/**
 * A registered scalar index: the build-level measurement named [metric] with [aggregation] is
 * emitted as `gbos.v1.index.<producer_slug>.<metric>.<aggregation>`.
 *
 * Every index must also be declared in `registry/develocity-indexes.json`.
 */
data class GbosIndex(val metric: String, val aggregation: String)

/**
 * Producer-scoped Develocity projection of GBOS observations, as defined in
 * `docs/develocity-projection.md`.
 */
object GbosDevelocity {
    const val SCHEMA_KEY = "gbos.schema"

    /**
     * Writes the projection of [observations] to [sink], in this order:
     *
     * 1. `gbos.schema` = [schemaVersion]
     * 2. `gbos.v1.producer.<slug>.version` = [producerVersion], then
     *    `gbos.v1.producer.<slug>.name` = [producerName]
     * 3. one `gbos.v1.producer.<slug>.observation` per observation, in input order, encoded with
     *    [GbosJson.encodeFragment]
     * 4. one `gbos.v1.index.<slug>.<metric>.<aggregation>` per entry of [indexes], in the given
     *    order, taken from the single observation whose `aggregationScope` is `build`. An index is
     *    skipped when there is not exactly one build-level observation or when that observation
     *    does not contain exactly one matching measurement.
     *
     * Writes nothing when [observations] is empty. The observations' own `schemaVersion` and
     * `producer` are not read; fragments omit them and the header values come from the arguments.
     */
    fun publish(
        sink: GbosCustomValueSink,
        schemaVersion: String,
        producerName: String,
        producerVersion: String,
        observations: List<GbosObservation>,
        indexes: List<GbosIndex> = emptyList()
    ) {
        if (observations.isEmpty()) return

        val slug = producerSlug(producerName)
        val namespace = "gbos.v1.producer.$slug"
        sink.value(SCHEMA_KEY, schemaVersion)
        sink.value("$namespace.version", producerVersion)
        sink.value("$namespace.name", producerName)
        observations.forEach { sink.value("$namespace.observation", GbosJson.encodeFragment(it)) }

        val build = observations.singleOrNull { it.aggregationScope == "build" } ?: return
        indexes.forEach { index ->
            build.measurements
                .singleOrNull { it.name == index.metric && it.aggregation == index.aggregation }
                ?.let { sink.value("gbos.v1.index.$slug.${index.metric}.${index.aggregation}", jsonNumber(it.value).content) }
        }
    }

    /** Producer slug used in custom-value names: `-` and `.` become `_`. */
    fun producerSlug(producerName: String): String = producerName.replace('-', '_').replace('.', '_')
}
