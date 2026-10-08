plugins {
    id("videobridge.android.library")
    id("videobridge.android.hilt")
}

android {
    namespace = "com.videobridge.core.player"
}

dependencies {
    api(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    api(libs.androidx.media3.ui)
}
