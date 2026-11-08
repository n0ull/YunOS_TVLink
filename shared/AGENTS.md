# shared

KMP library for both application shells.

## Source sets

Both platform source sets depend on `jvmCommonMain`.

- `jvmCommonMain`: Java networking, protocols, device services, Compose UI, and shared widgets such as `VoiceButton`.
- `androidMain` / `desktopMain`: only behavior that genuinely differs by platform.
- `app.tvlink.platform`: key-value storage and Android application context, independent of UI package ownership.
- `desktopTest`: JVM tests using real socket/loopback fixtures where appropriate.

## Verification

Run `:shared:desktopTest` after protocol or state changes and `check` before delivery. Build both Android and Desktop when changing an `expect/actual` declaration or platform adapter.
