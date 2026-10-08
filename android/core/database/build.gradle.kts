plugins {
    id("videobridge.android.library")
    id("videobridge.android.hilt")
    id("videobridge.android.room")
}

android {
    namespace = "com.videobridge.core.database"
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
