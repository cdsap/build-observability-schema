plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    signing
    alias(libs.plugins.maven.publish)
}

group = "io.github.cdsap"
version = rootProject.version

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.cdsap", "build-observability-core", rootProject.version.toString())

    pom {
        name.set("Gradle Build Observability Core")
        description.set("Shared typed GBOS observation model and encoding mechanics")
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
