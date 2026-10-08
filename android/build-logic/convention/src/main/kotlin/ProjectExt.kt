import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.intVersion(alias: String): Int = findVersion(alias).get().requiredVersion.toInt()

internal val JAVA_VERSION = JavaVersion.VERSION_17

/** Bytecode target 17 for every Kotlin compilation, whichever JDK (17+) runs the build. */
internal fun Project.configureKotlinJvmTarget() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
}

/** Settings every JVM unit-test task needs. */
internal fun Project.configureUnitTests() {
    tasks.withType<Test>().configureEach {
        // Robolectric reaches into JDK internals to emulate Android's shared memory (SDK 36+).
        jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        // Modules without tests yet (datastore, player, ...) must not fail the build.
        failOnNoDiscoveredTests.set(false)
    }
}

internal const val ENV_DIMENSION = "env"
internal val ENV_FLAVORS = listOf("dev", "staging", "prod")
