import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("com.github.johnrengelman.shadow") version "8.1.1"
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
        // 不含 JRE 的便携单 jar：用户自备 Java 17+，无需安装。
        // 构建命令：./gradlew :desktopApp:distribNoJre
        // 产物：desktopApp/build/distrib-no-jre/TVLink-no-jre/
        //   TVLink-no-jre.jar   — fat jar（~33M，含所有依赖）
        //   bin/TVLink.bat      — Windows 启动器（检查 JAVA_HOME/PATH，缺失时提示安装）
    }
}

// ---- 便携版（不含 JRE）：fat jar + 启动 bat ----
// 用户自备 Java（JAVA_HOME 或 PATH 中的 java），无需安装，解压即用。
// 产物在 desktopApp/build/distrib-no-jre/TVLink-no-jre/

val jvmRuntimeConfig = configurations.named("jvmRuntimeClasspath")

val shadowJarTask =
    tasks.register<ShadowJar>("shadowJarDesktop") {
        group = "distribution"
        description = "Build a fat jar (all dependencies) for portable distribution without JRE"
        archiveBaseName.set("TVLink-no-jre")
        archiveClassifier.set("")
        archiveVersion.set("")
        from(sourceSets["jvmMain"].output)
        configurations = listOf(jvmRuntimeConfig.get())
        mergeServiceFiles()
        manifest {
            attributes["Main-Class"] = compose.desktop.application.mainClass
        }
        // 排除签名文件，避免 SecurityException: invalid manifest
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }

val distribNoJre by tasks.registering(DefaultTask::class) {
    group = "distribution"
    description = "Assemble the portable (no-JRE) distribution: fat jar + launcher bat"
    dependsOn(shadowJarTask)
    val outDir =
        layout.buildDirectory
            .dir("distrib-no-jre/TVLink-no-jre")
            .get()
            .asFile
    doLast {
        val jarFile =
            shadowJarTask
                .get()
                .archiveFile
                .get()
                .asFile
        val batFile = File(outDir, "bin/TVLink.bat")
        batFile.parentFile.mkdirs()
        batFile.writeText(
            """
            @echo off
:: TVLink 便携启动器（不含 JRE）
:: 依赖：PATH 或 JAVA_HOME 中存在 java（建议 JRE/JDK 17+）
setlocal enabledelayedexpansion
if not defined JAVA_HOME (
    where java >nul 2>&1
    if errorlevel 1 (
        echo.
        echo TVLink requires Java 17+. Please install a JRE/JDK and ensure 'java' is on PATH,
        echo or set JAVA_HOME to your JDK installation directory.
        echo.
        echo Download: https://adoptium.net/  or  https://bell-sw.com/pages/downloads/
        pause
        exit /b 1
    )
    set _JAVA=java
) else (
    set _JAVA=%JAVA_HOME%\bin\java.exe
    if not exist "%_JAVA%" set _JAVA=java
)
"%_JAVA%" -jar "%~dp0..\TVLink-no-jre.jar" %*
            """.trimIndent(),
        )
        // 将 fat jar 复制到分发目录
        jarFile.copyTo(File(outDir, "TVLink-no-jre.jar"), overwrite = true)
        logger.lifecycle("Portable (no-JRE) distribution written to ${outDir.absolutePath}")
    }
}
