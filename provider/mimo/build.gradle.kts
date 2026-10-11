import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    implementation(project(":core:tts-spi"))
    implementation(project(":core:rules"))
    implementation(project(":core:audio"))
    implementation(libs.okhttp)
    implementation(libs.jlayer)
    testImplementation(libs.junit)
}
