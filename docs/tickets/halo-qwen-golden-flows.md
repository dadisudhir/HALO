## Symptom

HALO Heart chat can show prompt/context leakage or repeated model output in the UI. A simple chat prompt produced repeated heart-rate text even though the backend indicated no recorded biometrics.

## Expected behavior

Golden prompts must be stable:

- `hi` replies with a short greeting.
- current health/status questions answer from HALO backend state and explain the source.
- next-step/improvement questions produce a bounded action response and attempt web search when available.

The app must not display raw `HEALTH_CONTEXT_JSON`, repeated output loops, or crash from sequence-window overflow.

## Diagnosis

The Qwen/QNN model loads and generates on the S25, but the Android `LlmGenerationConfig.maxNewTokens` field is not wired through `LlmModule.generate(...)` to native generation in the packaged ExecuTorch wrapper. The practical bound is the total sequence length plus callback-side `stop()`. The model can still echo prompt context or repeat visible output before native generation returns.

## Plan

1. Route golden flows through a weighted semantic intent classifier before Qwen.
2. Keep Qwen thinking-mode fallback behind prompt-leak, missing-data, and repeat filters.
3. Stop native generation when visible or raw output exceeds HALO limits.
4. Add a Codex skill documenting the HALO golden-flow validation.
5. Render referenced domain graphs from existing backend component data.
6. Summarize web-search tool output into source titles/domains before it reaches the UI or model prompt.
7. Verify semantic prompts through the real S25 UI.

## Verification

- `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:assembleDebug` passed.
- Installed `app/build/outputs/apk/debug/app-debug.apk` on S25 `R3CXC07ZYCV`.
- Opened HALO -> Heart -> Heart coach through the actual UI.
- `hey there` returned `Hi — I’m here...` with `local greeting`.
- `how is my heart doing today` returned `local health status` and explained that recorded biometrics are unavailable while demo heart metrics show attention needed.
- `what am i looking at` returned `local context` and rendered `Referenced signal graph` without invoking web search.
- `search the web for heart recovery next steps` returned `golden next step + web_search`, rendered `Web-backed action graph`, and displayed source titles/domains instead of raw URLs.
- Final UI XML grep found no `HEALTH_CONTEXT_JSON`, `USER_MESSAGE`, `recorded_daily_count`, `duckduckgo.com`, `https://`, `ad_domain`, or `click_metadata` output in the latest web response.
- Final logcat grep found no `Fatal signal`, `FATAL EXCEPTION`, `sequence length exceeded`, or abort message from HALO.

## Status

Verified on device.
