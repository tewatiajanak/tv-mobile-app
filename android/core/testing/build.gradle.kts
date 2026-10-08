plugins {
    id("videobridge.android.library")
}

android {
    namespace = "com.videobridge.core.testing"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:data"))
    api(project(":core:network"))
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
}
