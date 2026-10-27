# AGENTS.md — TakeOne (Android audio editor)

This file gives coding agents the context needed to work on this repository.

## Project overview
- Android app (TakeOne studio): audio recording/editing, DSP/FX pipeline, teleprompter, conversion/export.
- Single Gradle module `:app`. Package `com.example.hello`. applicationId `com.takeone.studio`.
- Min SDK 24, target/compile SDK 35. Java 17. Gradle 8.7, AGP 8.6.1.

## Layout
- `app/src/main/java/com/example/hello/` — 100+ Java sources (DSP processors, EditEngine, DspChain, Recording/Conversion services, UI).
- `app/src/test/java/com/example/hello/` — 66+ Robolectric/JUnit unit tests.
- `app/src/androidTest/java/com/example/hello/` — 28 instrumentation tests.
- `app/schemas/` — exported Room schema.
- Workflows: `.github/workflows/{verify,build-apk,fx-verify}.yml`.

## Build & test (run on GitHub runners — host here is aarch64, cannot run x86_64 tools)
- Always invoke Gradle via `sh gradlew` (noexec filesystem).
- Unit (Robolectric): `sh gradlew testDebugUnitTest`
- Instrumentation (emulator): `sh gradlew connectedDebugAndroidTest` (needs device/emulator + KVM).
- Compile all: `sh gradlew :app:compileDebugJavaWithJavac :app:compileDebugUnitTestJavaWithJavac :app:compileDebugAndroidTestJavaWithJavac`
- Debug APK: `sh gradlew assembleDebug` -> `app/build/outputs/apk/debug/app-debug.apk`
- Lint: `sh gradlew lintDebug`
- Release bundle: `sh gradlew bundleRelease` (needs keystore secrets).

## CI
- `verify.yml`: compiles all source sets; runs unit tests sharded across 6 GitHub runners (Robolectric tests included); optional emulator job via workflow_dispatch.
- `build-apk.yml`: unit tests + compile androidTest + lint + assembleDebug + device tests on emulator + bundleRelease.
- `fx-verify.yml`: FX param inventory/matrix + device sweep.
- Robolectric tests run on ubuntu-latest GitHub runners (no emulator needed). Emulator tests run on GitHub runners with KVM via reactivecircus/android-emulator-runner.

## Conventions
- Java 17; follow existing processor patterns (AudioProcessor, FxParameter, DspChain).
- No comments unless requested. Match existing code style.
- Tests: JUnit 4 + Robolectric (sdk 34) for UI; plain JUnit for DSP.
- Never commit unless user asks; co-author trailer required.

## Taste
- See `.commandcode/taste/taste.md` for learned preferences (verification must be evidence-based end-to-end).

## Notes
- Environment is aarch64; local gradle execution fails on x86_64 aapt2. Use CI.
- Large test matrices parallelise on GitHub Actions (~40 concurrent jobs available).
