import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import java.util.Properties

/**
 * Shared setup for the phone and TV apps: SDK levels, Java 17, the `env` flavors with their
 * backend URLs, and the version from version.properties.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        configureKotlinJvmTarget()
        configureUnitTests()

        val version =
            Properties().apply {
                rootProject.file("version.properties").inputStream().use(::load)
            }
        // Emulators reach the host at 10.0.2.2; a real phone/TV needs -PVB_DEV_HOST=<LAN IP>.
        val devHost = providers.gradleProperty("VB_DEV_HOST").orElse("10.0.2.2").get()
        // The hosted backend. Override with -PVB_PROD_HOST=<host> when it moves.
        val prodHost = providers.gradleProperty("VB_PROD_HOST").orElse("tv-mobile-app.onrender.com").get()

        extensions.configure<ApplicationExtension> {
            compileSdk = libs.intVersion("compileSdk")
            defaultConfig {
                minSdk = libs.intVersion("minSdk")
                targetSdk = libs.intVersion("targetSdk")
                versionName = version.getProperty("VERSION_NAME")
                // One Play listing, two AABs: each needs a unique versionCode (ADR-0003).
                val formFactorOffset = if (project.name == "app-tv") 2 else 1
                versionCode = version.getProperty("VERSION_CODE_BASE").toInt() * 10 + formFactorOffset
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JAVA_VERSION
                targetCompatibility = JAVA_VERSION
            }
            buildFeatures { buildConfig = true }

            flavorDimensions += ENV_DIMENSION
            productFlavors {
                create("dev") {
                    dimension = ENV_DIMENSION
                    applicationIdSuffix = ".dev"
                    buildConfigField("String", "API_BASE_URL", "\"http://$devHost:3000/\"")
                    buildConfigField("String", "WS_URL", "\"ws://$devHost:3000/ws\"")
                }
                create("staging") {
                    dimension = ENV_DIMENSION
                    applicationIdSuffix = ".staging"
                    buildConfigField("String", "API_BASE_URL", "\"https://api-staging.example.com/\"")
                    buildConfigField("String", "WS_URL", "\"wss://api-staging.example.com/ws\"")
                }
                create("prod") {
                    dimension = ENV_DIMENSION
                    buildConfigField("String", "API_BASE_URL", "\"https://$prodHost/\"")
                    buildConfigField("String", "WS_URL", "\"wss://$prodHost/ws\"")
                }
            }

            lint {
                warningsAsErrors = false
                abortOnError = true
            }
            testOptions { unitTests.isIncludeAndroidResources = true }
            packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        }
    }
}
