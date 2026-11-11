plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(17)
    jvm()
    android {
        namespace = "dev.resticky.shared"
        compileSdk = 36
        minSdk = 31
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.server.core)
            implementation(libs.ktor.client.cio)
            api(libs.coroutines.core)
            api(libs.serialization.json)
            api(libs.io.core)
            api(libs.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
