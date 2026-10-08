plugins {
    id("videobridge.android.library")
    id("videobridge.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.videobridge.core.datastore"
}

dependencies {
    api(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tink.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
