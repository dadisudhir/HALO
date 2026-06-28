## Symptom

HALO chat correctly classifies a health/status prompt as local health context, but the answer only says recorded biometrics are unavailable and does not use the visible backend Heart card signals shown directly below the answer.

The tool path also masks execution failures: a web/tool request that fails is collapsed to null, so the final answer cannot distinguish no tool request from a failed tool request.

A follow-up failure remained for prompts like `tell me current status for my heart`: the answer could still enter the Qwen planner first, and the fallback copy led with `I cannot infer...` / `MVP demo` even though the UI already had a HALO heart-card status and graph to report.

## Expected behavior

For prompts like `how healthy is my heart right now`, HALO should say recorded/watch biometrics are unavailable for personal conclusions, then cite the visible backend card values as display/demo context, for example Resting HR and HRV.

For prompts like `tell me current status for my heart`, HALO should answer the local card status directly, not call web/tooling, not wait on Qwen planner generation, and not lead with refusal-style copy.

For explicit web/tool prompts, HALO should preserve whether the tool succeeded or failed and surface that status to the final answer/status line.

## Diagnosis

`HealthAgentContext.toPromptJson()` emits `personal_metrics` only when recorded summaries exist. In fake/demo mode with no recorded watch data, `organ.metrics` is an empty array in the prompt even though the UI renders Heart values. The model therefore cannot reference the visible card signals.

`OnDeviceLlmService.executeApprovedTool()` catches all tool exceptions and returns null, which erases tool failure state.

The first fix still called `requestPlan()` before deterministic backend handling. That meant golden local prompts could still touch Qwen just to classify the route. The backend fallback text was also too refusal-heavy for display-card status.

## Plan

1. Add display-only context to `HealthAgentContext.toPromptJson()` including visible organ metrics, preview, graph trend, and next-step text.
2. Tighten route guidance so Qwen can use display context only as visible/demo context, never as a personal health conclusion.
3. Update local fallback for health routes to include visible display metrics when recorded data is missing.
4. Preserve tool execution outcome with success/failure status instead of returning nullable success only.
5. Short-circuit missing-recorded-data health/status routes to a deterministic backend display-context answer before final Qwen generation.
6. Pre-route non-web golden flows locally before Qwen planner generation.
7. Rewrite backend-display health/status copy to report the HALO card status first, then qualify that it is card context rather than live watch-recorded medical conclusion.
8. Add regression tests for missing-recorded-data health prompts, display-context prompt payloads, the exact `tell me current status for my heart` prompt, and tool failure status.
9. Run unit tests, lint, assemble, and install/UI smoke on S25.

## Verification

- `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest` passed.
- `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:lintDebug :app:assembleDebug` passed.
- Installed `app/build/outputs/apk/debug/app-debug.apk` on S25 `R3CXC07ZYCV`.
- Opened HALO and confirmed the normal home state:
  - `No recorded biometrics yet`
  - `fake path - SQLite 30 daily rows - 7 components`
- Ran the on-device smoke activity against `OnDeviceLlmService.generateChat(...)` with the exact prompt `how healthy is my heart right now`. The result file `/sdcard/Android/data/com.health.secondbrain/files/halo_chat_smoke_result.json` rendered:
  - `classified health_status - health_context - backend display context`
  - `Recorded biometrics are not available yet... The visible HALO heart card shows Resting HR 65 (+4bpm) and HRV 44 (-9) and Sleep 6h34m as display-only context.`
  - `action=data_recall`, `route=health_status`, `answer_source=backend_display_context`, `used_tool=false`
  - `Current signal graph`
- After the follow-up copy/routing fix, ran the on-device smoke activity against the exact prompt `tell me current status for my heart`. The result file rendered:
  - `latency_ms: 3`
  - `classified health_status - health_context - backend display context`
  - `HALO currently marks your heart card as needs attention. The visible signals are Resting HR 65 (+4bpm) and HRV 44 (-9) and Sleep 6h34m. This is HALO card context, not a live watch-recorded medical conclusion.`
  - `action=data_recall`, `route=health_status`, `answer_source=backend_display_context`, `used_tool=false`
- Sent an explicit web prompt while no local bridge was available. The UI rendered:
  - `classified web_research - web_search failed - Qwen final`
  - a user-facing search-unavailable answer instead of silently treating it as no tool request.
- Checked logcat for the smoke flows; no `FATAL EXCEPTION`, `Fatal signal`, or `sequence length exceeded` was present.

## Status

Verified on device.
