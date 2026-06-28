# Qwen3 1.7B QNN Planner Eval Results

Run date: 2026-06-28

Device:

- Samsung S25 Ultra / `SM-S938U1`
- Serial: `R3CXC07ZYCV`

Model/runtime:

- `/data/local/tmp/minibench/qwen3-1_7b/hybrid_llama_qnn.pte`
- `/data/local/tmp/minibench/qwen3-1_7b/tokenizer.json`
- QNN V79 runtime via HALO app `QwenLocalGenerator`
- Eval activity: `com.health.secondbrain/.llm.AgentPlannerEvalActivity`

Dataset:

- `docs/evals/agent_tool_routing_dataset.jsonl`
- Split: `judge`
- Count: 40 prompts

## Strict Executable Score

This is the score that matters for the current app harness. A tool call only counts if the model emitted exact executable fields such as:

```json
{"type":"tool_request","tool":"web_search"}
```

Results:

- Action accuracy: `52.50%`
- Web precision: `50.00%`
- Web recall: `30.77%`
- Web F1: `38.10%`
- Health-context accuracy: `60.00%`
- No-action specificity: `30.00%`
- HALO Planner Score: `48.87`

## Tolerant Semantic Score

This score normalizes obvious schema typos such as `tool__request` and `web__search`, and treats `route=web_research` with a query as semantic web-search intent.

Results:

- Action accuracy: `62.50%`
- Web precision: `61.11%`
- Web recall: `84.62%`
- Web F1: `70.97%`
- Health-context accuracy: `60.00%`
- No-action specificity: `30.00%`
- HALO Planner Score: `60.44`

## Latency

Measured on-device over 40 QNN planner calls:

- Mean latency: `7715.25 ms`
- Median latency: `7606.5 ms`
- Min latency: `6117 ms`
- P90 latency: `8712 ms`
- Max latency: `9378 ms`

## Output Distribution

Raw `type` field counts:

- `tool_request`: 8
- `tool__request`: 7
- `final`: 8
- `general_health`: 8
- `clarify`: 5
- `next_step`: 2
- `health_heart`: 1
- `parse_error`: 1

Raw `route` field counts:

- `web_research`: 18
- empty: 10
- `smalltalk`: 5
- `web_search`: 4
- `general_health`: 2
- `clarify`: 1

## Interpretation

The model often semantically recognizes web-search requests, but its planner output is not schema-reliable enough for direct executable tool calling.

Main failure classes:

- Schema drift: `tool__request`, `web__search`, missing `route`, or route/type swapped.
- Over-tooling: local data-recall prompts like graph explanations sometimes become web-search requests.
- Under-tooling: several explicit web prompts are emitted as malformed tool requests, so strict execution blocks them.
- No-action weakness: smalltalk and typos often become `general_health` or incorrectly request health context.

## Recommended Fix

Keep the agentic shape, but add a constrained planner adapter:

1. Ask Qwen for the plan.
2. Parse tolerant fields into a canonical `AgentPlan`.
3. Validate against policy before executing tools.
4. If confidence is low, ask a clarification instead of attaching health context.
5. For judging, report both strict executable accuracy and tolerant semantic accuracy.

Do not return to automatic host-side web search. The right fix is stricter structured decoding / canonicalization, not hardcoded prompt responses.
