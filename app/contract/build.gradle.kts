plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.serialization.json)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("hub.url", providers.gradleProperty("hubUrl").getOrElse(""))
    systemProperty("hub.data", providers.gradleProperty("hubData").getOrElse(""))
    outputs.upToDateWhen { false }
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
