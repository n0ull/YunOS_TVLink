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
        // 排除构建产物与生成代码（要求 #4：排除 build 目录）。
        // Compose 资源生成器会把代码注入到 build/generated 下的源集；该目录本身就是某个源集根，
        // 因此 Ant 字符串模式（相对于源集根）无法匹配。这里用 FileTreeElement 的绝对路径判断，确保过滤生效。
        filter {
            exclude { element: org.gradle.api.file.FileTreeElement ->
                val p = element.file.absolutePath.replace('\\', '/')
                p.contains("/build/") || p.contains("/generated/")
            }
        }
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
                tasks.matching { it.name.startsWith("detekt") && !it.name.contains("Baseline", ignoreCase = true) },
            )
        }
    }
}
