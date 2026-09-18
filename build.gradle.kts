plugins {
    `java-library`
    `maven-publish`
    signing
}

group = "io.github.cdsap"
version = providers.gradleProperty("gbosVersion")
    .orElse(providers.environmentVariable("GBOS_VERSION"))
    .orElse("0.0.1")
    .get()

check(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$").matches(version.toString())) {
    "gbosVersion must be a semantic version, got $version"
}

description = "Gradle Build Observability Schema contract resources"

java {
    withSourcesJar()
    withJavadocJar()
}

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

publishing {
    repositories {
        maven {
            name = "central"
            url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
            credentials {
                username = providers.environmentVariable("MAVEN_CENTRAL_USERNAME").orNull
                password = providers.environmentVariable("MAVEN_CENTRAL_PASSWORD").orNull
            }
        }
    }

    publications {
        create<MavenPublication>("gbos") {
            from(components["java"])
            artifactId = "build-observability-schema"

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
    }
}

val signingKey = providers.environmentVariable("MAVEN_CENTRAL_GPG_PRIVATE_KEY").orNull
val signingPassword = providers.environmentVariable("MAVEN_CENTRAL_GPG_PASSWORD").orNull

signing {
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["gbos"])
    }
}
