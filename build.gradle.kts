import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

subprojects {
    tasks.withType<Test>().configureEach {
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}
