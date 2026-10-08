plugins {
    id("videobridge.android.library")
    id("videobridge.android.hilt")
}

android {
    namespace = "com.videobridge.core.common"
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.timber)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
