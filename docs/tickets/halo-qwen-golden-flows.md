## Symptom

HALO Heart chat can show prompt/context leakage or repeated model output in the UI. A simple chat prompt produced repeated heart-rate text even though the backend indicated no recorded biometrics.

Follow-up Kidney chat regression:

- `whta` falls through to Qwen and returns a missing-biometrics fallback instead of asking for clarification.
- `hello today` correctly routes to `local greeting`.
- `what is up my dude` falls through to Qwen, displays `answered from HALO health context`, leaks non-English thinking/safety text, and leaves an orphan `</think>` marker visible.

## Expected behavior

Golden prompts must be stable:

- `hi` replies with a short greeting.
- current health/status questions answer from HALO backend state and explain the source.
- next-step/improvement questions produce a bounded action response and attempt web search when available.

The app must not display raw `HEALTH_CONTEXT_JSON`, repeated output loops, or crash from sequence-window overflow.

Casual greetings, unclear typos, and low-signal social messages should not load Qwen. They should route locally as greeting or clarification.

## Diagnosis

The Qwen/QNN model loads and generates on the S25, but the Android `LlmGenerationConfig.maxNewTokens` field is not wired through `LlmModule.generate(...)` to native generation in the packaged ExecuTorch wrapper. The practical bound is the total sequence length plus callback-side `stop()`. The model can still echo prompt context or repeat visible output before native generation returns.

The follow-up screenshot has two additional confirmed causes:

- The semantic greeting scorer recognizes `hello today`, but not social variants such as `what is up my dude`, so those messages incorrectly fall through to Qwen.
- The visible-output sanitizer strips `<think>...</think>` and open `<think>` blocks, but not malformed/orphaned `</think>` output. Qwen can emit non-English reasoning before an orphan closing tag, and that survives into the UI.
- A typo like `whta` has no intent route, so the model is used even though the safest behavior is a local clarification.

## Plan

1. Route golden flows through a weighted semantic intent classifier before Qwen.
2. Keep Qwen thinking-mode fallback behind prompt-leak, missing-data, and repeat filters.
3. Stop native generation when visible or raw output exceeds HALO limits.
4. Add a Codex skill documenting the HALO golden-flow validation.
5. Render referenced domain graphs from existing backend component data.
6. Summarize web-search tool output into source titles/domains before it reaches the UI or model prompt.
7. Verify semantic prompts through the real S25 UI.
8. Add a local clarification route for low-confidence short inputs.
9. Expand greeting semantics for casual social variants without exact prompt matching.
10. Strip orphan thinking tags and reject generic model safety boilerplate that does not satisfy the missing-recorded-data policy.
11. Add regression coverage for `whta`, `hello today`, and `what is up my dude`.

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
- Added JVM regression coverage for the screenshot chain:
  - `whta` routes to `Clarification`.
  - `hello today` routes to `Greeting`.
  - `what is up my dude` routes to `Greeting`.
  - orphan `</think>` output removes pre-tag reasoning text.
- `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest` passed.
- `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:assembleDebug` passed.
- Installed the patched debug APK on S25 `R3CXC07ZYCV`.
- Opened HALO -> Kidney -> Kidney coach and replayed the exact screenshot chain:
  - `whta` returned `local clarification` with `I didn’t catch that...`.
  - `hello today` returned `local greeting`.
  - `what is up my dude` returned `local greeting`.
- Final UI XML grep found no `answered from HALO health context`, `</think>`, `的能力`, medical-advice boilerplate, `HEALTH_CONTEXT_JSON`, or `USER_MESSAGE` in the replayed chain.
- Final logcat grep found no `AndroidRuntime`, `FATAL EXCEPTION`, `Fatal signal`, `sequence length exceeded`, prompt leak markers, or think-tag leakage.

## Status

Verified on device.
