<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-20 -->

# androidApp

## Purpose

Android application shell. Thin wrapper that hosts the shared Compose UI in a
`ComponentActivity`, declares permissions, and manages the Wi-Fi `MulticastLock`
required for mDNS discovery.

## Key Files

| File                                       | Description                                                                      |
|--------------------------------------------|----------------------------------------------------------------------------------|
| `build.gradle.kts`                         | Android application plugin config (minSdk 21, compileSdk/targetSdk 36)                     |
| `src/main/java/app/tvlink/MainActivity.kt` | Single activity — sets Compose content to `shared` App(), acquires MulticastLock |
| `src/main/AndroidManifest.xml`             | Permissions (INTERNET, BLUETOOTH, ACCESS_WIFI_STATE, etc.), activity declaration |

## For AI Agents

### Working In This Directory

- Keep this module minimal — business logic belongs in `shared/`
- Android-specific platform code (BLE, sensors) lives in `shared/src/androidMain/`, not here
- Only manifest/permissions and the hosting Activity belong here

### Testing Requirements

- Build check: `./gradlew :androidApp:assembleDebug`

## Dependencies

### Internal

- `:shared` (protocol + UI)

### External

- AndroidX Activity Compose
- Android SDK (compileSdk 36)

<!-- MANUAL: -->
