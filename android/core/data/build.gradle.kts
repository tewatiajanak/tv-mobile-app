plugins {
    id("videobridge.android.library")
    id("videobridge.android.hilt")
}

android {
    namespace = "com.videobridge.core.data"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:network"))
    implementation(project(":core:database"))
    api(project(":core:datastore"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
