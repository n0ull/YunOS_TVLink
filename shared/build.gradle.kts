plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jetbrains.compose")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    jvm("desktop")

    sourceSets {
        val commonMain =
            getByName("commonMain") {
                dependencies {
                    implementation(compose.runtime)
                    implementation(compose.foundation)
                    implementation(compose.material3)
                    implementation(compose.ui)
                    implementation(compose.components.resources)
                    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
                    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")
                    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
                }
            }
        val commonTest =
            getByName("commonTest") {
                dependencies {
                    implementation(kotlin("test"))
                }
            }
        // Both targets are JVM-based: socket/IO code lives here using plain java.net.
        val jvmCommonMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                // 图标库独立发布线,最新即 1.7.3(不随 CMP 1.8.0 重发,官方确认兼容)
                implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            }
        }
        getByName("androidMain").apply {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation("androidx.activity:activity-compose:1.10.1")
            }
        }
        getByName("desktopMain").dependsOn(jvmCommonMain)
    }
}

android {
    namespace = "app.tvlink.shared"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
