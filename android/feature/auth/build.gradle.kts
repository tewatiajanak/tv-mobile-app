plugins {
    id("videobridge.android.library")
    id("videobridge.android.hilt")
}

android {
    namespace = "com.videobridge.feature.auth"
}

dependencies {
    api(project(":core:data"))
    api(libs.androidx.lifecycle.viewmodel.ktx)

    testImplementation(project(":core:testing"))
    testImplementation(libs.turbine)
}
