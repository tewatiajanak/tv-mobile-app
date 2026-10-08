plugins {
    id("videobridge.android.library")
    id("videobridge.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.videobridge.core.network"
}

dependencies {
    implementation(project(":core:common"))

    api(libs.okhttp)
    api(libs.retrofit)
    api(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit.kotlinx.serialization)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
