import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Shared setup for Android library modules. Libraries carry the same `env` flavors as the apps
 * so variant names line up (`testDevDebugUnitTest`, `lintDevDebug` run everywhere).
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        configureKotlinJvmTarget()
        configureUnitTests()

        extensions.configure<LibraryExtension> {
            compileSdk = libs.intVersion("compileSdk")
            defaultConfig {
                minSdk = libs.intVersion("minSdk")
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JAVA_VERSION
                targetCompatibility = JAVA_VERSION
            }

            flavorDimensions += ENV_DIMENSION
            productFlavors {
                ENV_FLAVORS.forEach { name -> create(name) { dimension = ENV_DIMENSION } }
            }

            lint {
                warningsAsErrors = false
                abortOnError = true
            }
            testOptions { unitTests.isIncludeAndroidResources = true }
        }
    }
}
