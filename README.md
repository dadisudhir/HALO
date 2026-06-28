# Health Second Brain — Android (Hackathon Build)

Native Android app for the **ExecuTorch Hackathon (Qualcomm × Meta, SF, Jun 27–28 2026)**.
Target device: **Samsung Galaxy S25 Ultra** (Snapdragon 8 Elite, SM8750).

## Stack
- Kotlin + Jetpack Compose UI
- Health Connect (`androidx.health.connect:connect-client`) for on-device health data
- ExecuTorch (NPU-backed Llama / Phi via QNN) for the three LLM-generated strings per organ — wired through `OnDeviceLlmService` (currently a stub returning canned strings)

## Screens
| Route | File |
|---|---|
| Home (organ bubble cluster, dark) | `ui/screens/HomeScreen.kt` |
| Scan focus (rings + preview card) | `ui/screens/ScanFocusScreen.kt` |
| Organ detail (metrics, charts, sentiment, next step, chat entry) | `ui/screens/OrganDetailScreen.kt` |
| Full chat | `ui/screens/ChatScreen.kt` |

## Design notes
- Built from `DESIGN_SPEC.md`, but the **palette is custom** and every surface is **dark mode** (the spec switched detail screens to light — we don't). OLED-friendly, judging-friendly demo on the S25 Ultra display.
- Caveat/Gaegu are not bundled — type system uses Serif/SansSerif as drop-in stand-ins to keep first-build deterministic. Swap to Downloadable Fonts when wifi is reliable.

## Build
1. Open in Android Studio Koala+ (AGP 8.5).
2. Generate the Gradle wrapper once: `gradle wrapper` (or let Studio do it on first sync).
3. Plug in the S25 Ultra → Run.

## ExecuTorch integration plan
1. Build (or download) a `.pte` for Llama 3.2 1B with the QNN backend.
2. Drop `executorch.aar` into `app/libs/` and uncomment the dependency in `app/build.gradle.kts`.
3. Replace the body of `OnDeviceLlmService.generate` with a `Module.load(...)` + `forward()` call.
   - Per-organ prompts already constructed in the service.
   - 1-sentence cap enforced on the caller side (truncate at first `.`/`!`/`?`).

## Health Connect integration plan
- `HealthConnectRepository` already declares permissions and exposes `readRestingHrLast7Days()`.
- Wire a `ViewModel` that swaps `OrganRegistry`'s demo metrics for live records before screen render.

## Repo layout
```
app/src/main/
  AndroidManifest.xml
  java/com/health/secondbrain/
    MainActivity.kt
    HealthApp.kt
    model/Organ.kt
    health/HealthConnectRepository.kt
    llm/OnDeviceLlmService.kt
    ui/AppRoot.kt
    ui/theme/{Palette,Theme}.kt
    ui/components/Charts.kt
    ui/screens/{Home,ScanFocus,OrganDetail,Chat}Screen.kt
```
