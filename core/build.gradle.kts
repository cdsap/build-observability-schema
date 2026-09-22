plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = "io.github.cdsap"
version = rootProject.version

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "build-observability-core"
        }
    }
}
