<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-20 -->

# desktopApp

## Purpose

Compose Desktop application shell (JVM target). Entry point for the Windows/Linux/macOS
client. Packages as exe/msi via the Compose Desktop packaging plugin.

## Key Files

| File | Description |
|------|-------------|
| `build.gradle.kts` | Compose Desktop plugin, mainClass config, native packaging (Windows exe/msi) |
| `src/jvmMain/kotlin/app/tvlink/desktop/Main.kt` | `fun main()` — creates application window, sets Compose content to shared App() |

## For AI Agents

### Working In This Directory

- Keep minimal — all logic is in `shared/`
- Desktop-specific platform code (AWT file dialogs, screenshot save) lives in `shared/src/desktopMain/`
- Window sizing and app metadata configured in `Main.kt`

### Testing Requirements

- Run check: `./gradlew :desktopApp:run`
- Tests run via `:shared:desktopTest` (shared module's JVM target)

## Dependencies

### Internal

- `:shared` (protocol + UI)

### External

- Compose Desktop (JetBrains)

<!-- MANUAL: -->
