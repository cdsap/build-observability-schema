plugins {
    `java-library`
    `maven-publish`
    signing
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

group = "io.github.cdsap"
version = "0.0.4"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

description = "Gradle Build Observability Schema contract resources"

tasks.named<ProcessResources>("processResources") {
    from("schema") {
        into("schema")
    }
    from("registry") {
        into("registry")
    }
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("build-observability-schema")
}

val verifyArtifactLayout = tasks.register("verifyArtifactLayout") {
    dependsOn(tasks.named("jar"))

    doLast {
        val archive = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val entries = mutableSetOf<String>()
        zipTree(archive).matching {
            include("schema/*.json")
            include("registry/*.json")
        }.visit {
            if (!isDirectory) {
                entries += relativePath.pathString
            }
        }

        val expected = setOf(
            "schema/observation.schema.json",
            "schema/observation-batch.schema.json",
            "schema/observation-fragment.schema.json",
            "schema/report.schema.json",
            "schema/develocity-projection.schema.json",
            "schema/semantic-conventions.schema.json",
            "schema/develocity-indexes.schema.json",
            "registry/semantic-conventions.json",
            "registry/develocity-indexes.json"
        )
        check(expected.all { expectedEntry -> entries.any { it.endsWith(expectedEntry) } }) {
            "Published artifact is missing expected GBOS resources: ${expected - entries}"
        }
    }
}

val verifyJavaTargetMetadata = tasks.register("verifyJavaTargetMetadata") {
    dependsOn(tasks.named("generateMetadataFileForMavenPublication"))

    doLast {
        val metadata = layout.buildDirectory.file("publications/maven/module.json").get().asFile
        check(metadata.isFile) { "Published Gradle Module Metadata was not generated: $metadata" }
        check(metadata.readText().contains("\"org.gradle.jvm.version\": 17")) {
            "Published Gradle Module Metadata must target Java 17: $metadata"
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.cdsap", "build-observability-schema", "0.0.4")

    pom {
        name.set("Gradle Build Observability Schema")
        description.set(project.description)
        url.set("https://github.com/cdsap/build-observability-schema")
        licenses {
            license {
                name.set("The MIT License (MIT)")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/cdsap/build-observability-schema.git")
            developerConnection.set("scm:git:ssh://github.com/cdsap/build-observability-schema.git")
            url.set("https://github.com/cdsap/build-observability-schema")
        }
        developers {
            developer {
                id.set("cdsap")
                name.set("Inaki Villar")
            }
        }
    }
}

if (extra.has("signing.keyId")) {
    afterEvaluate {
        configure<SigningExtension> {
            val publishingExtension = extensions.getByName("publishing") as PublishingExtension
            publishingExtension.publications.forEach { sign(it) }
        }
    }
}
