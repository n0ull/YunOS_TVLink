import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvm()
    sourceSets {
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(project(":shared"))
        }
    }
}

compose.desktop {
    application {
        mainClass = "app.tvlink.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            windows {
                menuGroup = "TVLink"
                upgradeUuid = "8f3a2b1c-4e5d-4a6b-9c0d-1e2f3a4b5c6d"
            }
            packageName = "TVLink"
            packageVersion = "1.0.0"
        }
    }
}
