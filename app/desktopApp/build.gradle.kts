plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.server.cio)
    implementation(libs.jmdns)
    implementation(libs.sherpa.onnx.jvm)
    runtimeOnly(libs.sherpa.onnx.native.linux)
    runtimeOnly(libs.slf4j.simple)
}

application {
    mainClass = "dev.resticky.desktop.MainKt"
    applicationName = "resticky-hub"
}
