plugins {
    id("videobridge.android.library")
    id("videobridge.android.compose")
}

android {
    namespace = "com.videobridge.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.material3)
}
