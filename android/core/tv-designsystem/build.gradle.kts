plugins {
    id("videobridge.android.library")
    id("videobridge.android.compose")
}

android {
    namespace = "com.videobridge.core.tvdesignsystem"
}

dependencies {
    api(libs.androidx.tv.material)
}
