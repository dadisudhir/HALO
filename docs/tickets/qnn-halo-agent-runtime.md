## Symptom

HALO chat reaches the on-device Qwen path but responds with:

`Qwen is unavailable: ExecuTorch error 1: [ExecuTorch Error 0x1] Internal error: Failed to load model runner`

## Expected behavior

HALO should load the same Qwen3 1.7B SM8750 QNN artifact that works in MiniBench:

- `/data/local/tmp/minibench/qwen3-1_7b/hybrid_llama_qnn.pte`
- `/data/local/tmp/minibench/qwen3-1_7b/tokenizer.json`

Then chat should generate a response from the HALO health context.

## Diagnosis

The `.pte` and tokenizer exist on the S25 and HALO reaches `LlmModule`. Tokenizer loading succeeds.

The failure is in QNN DSP setup:

```text
QnnDsp loadRemoteSymbols failed with err 4000
Failed to create transport for device
Failed to load skel
Transport layer setup failed: 14001
Fail to configure Qnn device
Failed to load model runner
```

MiniBench performs QNN bootstrap at activity startup by setting `ADSP_LIBRARY_PATH` to `applicationInfo.nativeLibraryDir`. HALO must mirror that earlier and add a direct smoke test to isolate runtime/package failures from health chat.

## Plan

1. Add a shared `QnnEnvironment` bootstrap and diagnostics helper.
2. Call QNN bootstrap from `HealthApp.onCreate`.
3. Reuse the helper from the Qwen generator.
4. Add a plain Android smoke activity that loads Qwen and runs a tiny prompt.
5. If the smoke activity still fails, test HALO with MiniBench-compatible target SDK settings.
6. Verify on S25 with real install, UI, and logcat.

## Verification

- `./gradlew :app:assembleDebug`
- launch HALO home and verify no crash
- launch `com.health.secondbrain/.llm.QwenSmokeActivity`
- ask heart chat: `what changed in my heart data`
- inspect logcat for QNN load/generation or remaining `QnnDsp` error

## Status

Open.
