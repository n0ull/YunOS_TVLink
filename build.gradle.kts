import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

plugins {
    kotlin("multiplatform") version "2.1.20" apply false
    kotlin("android") version "2.1.20" apply false
    kotlin("plugin.compose") version "2.1.20" apply false
    kotlin("plugin.serialization") version "2.1.20" apply false
    id("com.android.application") version "8.9.2" apply false
    id("com.android.library") version "8.9.2" apply false
    id("org.jetbrains.compose") version "1.8.0" apply false
    // 静态分析与格式化
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

// ---------------------------------------------------------------------------
// ktlint + detekt：统一在根工程对所有子模块（shared / androidApp / desktopApp）生效
// 职责划分：ktlint 管「格式」，detekt 管「静态分析」，二者规则不重叠（detekt 关闭 formatting 规则集）。
// 执行：
//   ./gradlew check          # 运行全部子模块的测试 + ktlintCheck + detekt（CI 入口）
//   ./gradlew ktlintFormat    # 按 .editorconfig 自动格式化
//   ./gradlew detekt          # 仅运行 detekt
// ---------------------------------------------------------------------------
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<KtlintExtension> {
        // 采用 ktlint 1.x；与 .editorconfig 中 ktlint_code_style = ktlint_official 配合
        version = "1.4.1"
        outputToConsole = true
        coloredOutput = true
        ignoreFailures = false
        reporters {
            reporter(ReporterType.PLAIN)
            reporter(ReporterType.HTML)
            reporter(ReporterType.SARIF)
        }
        // 排除构建产物与生成代码（要求 #4）由 .editorconfig 的 [**/build/**] 段统一实现。
        // 此前的 filter 闭包对 KMP 源集检查任务不生效（build/generated 被注册为源集根，
        // 实测 ActualResourceCollectors.kt 等生成文件仍被检查），故移除，避免「看似已排除」的假象。
    }

    configure<DetektExtension> {
        // 在 detekt 默认规则集之上叠加 detekt.yml 中的覆盖项
        config.setFrom(files(rootProject.file("detekt.yml")))
        buildUponDefaultConfig = true
        allRules = false
        parallel = true
        ignoreFailures = false
    }

    tasks.withType<Detekt>().matching { !it.name.contains("Baseline", ignoreCase = true) }.configureEach {
        // 仅对检查任务配置 HTML/SARIF 报告（排除基线生成任务）。
        // KMP 每个源集都会生成一个 Detekt 任务，用「完整任务名」区分输出路径，避免相互覆盖。
        val taskName = name
        reports {
            html.required.set(true)
            html.outputLocation.set(layout.buildDirectory.file("reports/detekt/$taskName.html"))
            sarif.required.set(true)
            sarif.outputLocation.set(layout.buildDirectory.file("reports/detekt/$taskName.sarif"))
            md.required.set(false)
        }
    }

    // 将 ktlint / detekt 检查挂到 check 任务，使 `./gradlew check` 成为统一入口。
    // 放在 afterEvaluate 中，确保 androidApp 等模块的 check 任务（由各模块的 android/kotlin 插件稍后注册）已存在。
    afterEvaluate {
        tasks.named("check") {
            dependsOn(
                tasks.matching { it.name.startsWith("ktlint") && it.name.endsWith("Check") },
                // 仅挂 detekt 分析任务，必须排除 detektGenerateConfig（配置生成任务）：
                // 该任务将根目录 detekt.yml 声明为自己的输出，而各模块 Detekt 任务经
                // config.setFrom(rootProject.file("detekt.yml")) 把同一文件作为输入。
                // 一旦生成任务随 check 进入任务图，Gradle 校验会判定 Detekt 任务
                // 「使用了未声明依赖的任务输出」并直接使构建失败（CI 即死于此）。
                // detekt.yml 已纳入版本管理，生成任务只会 skip，在 check 中本就不需要。
                tasks.matching {
                    it.name.startsWith("detekt") &&
                        !it.name.contains("Baseline", ignoreCase = true) &&
                        it.name != "detektGenerateConfig"
                },
            )
        }
    }
}

// 要求 #4 同样适用于 detekt：排除 build/ 下的生成代码（Compose 资源生成器产出的
// Res.kt / ActualResourceCollectors.kt 等，其风格由生成器决定，不应由人工源码规则裁决）。
// 必须用 projectsEvaluated：detekt 的 KMP 集成在子项目应用 kotlin 插件后才注册自己的
// afterEvaluate 向任务追加源集目录（含 build/generated），普通 afterEvaluate 里的
// setSource 会被其覆盖（实测 Res.kt 仍被分析）；projectsEvaluated 晚于一切 afterEvaluate。
gradle.projectsEvaluated {
    subprojects {
        tasks.withType<Detekt>().matching { !it.name.contains("Baseline", ignoreCase = true) }.configureEach {
            setSource(
                source.filter { file ->
                    val p = file.absolutePath.replace('\\', '/')
                    !p.contains("/build/") && !p.contains("/generated/")
                },
            )
        }
    }
}
