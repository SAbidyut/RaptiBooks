import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.raptibooks.core.ocr"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:structure"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.javacpp)
    implementation(libs.leptonica)
    implementation(libs.tesseract)
    implementation("org.bytedeco:javacpp:${libs.versions.javacpp.get()}:android-arm")
    implementation("org.bytedeco:javacpp:${libs.versions.javacpp.get()}:android-arm64")
    implementation("org.bytedeco:javacpp:${libs.versions.javacpp.get()}:android-x86_64")
    implementation("org.bytedeco:leptonica:${libs.versions.leptonica.get()}:android-arm")
    implementation("org.bytedeco:leptonica:${libs.versions.leptonica.get()}:android-arm64")
    implementation("org.bytedeco:leptonica:${libs.versions.leptonica.get()}:android-x86_64")
    implementation("org.bytedeco:tesseract:${libs.versions.tesseract.get()}:android-arm")
    implementation("org.bytedeco:tesseract:${libs.versions.tesseract.get()}:android-arm64")
    implementation("org.bytedeco:tesseract:${libs.versions.tesseract.get()}:android-x86_64")
    testImplementation(libs.junit)
}
