# HALO Agent Routing Failure-Mode Eval

Run date: 2026-06-28

Device/runtime:

- Samsung S25 Ultra / `SM-S938U1`
- Serial: `R3CXC07ZYCV`
- Qwen model: `/data/local/tmp/minibench/qwen3-1_7b/hybrid_llama_qnn.pte`
- Tokenizer: `/data/local/tmp/minibench/qwen3-1_7b/tokenizer.json`
- Eval activity: `com.health.secondbrain/.llm.AgentPlannerEvalActivity`

Dataset:

- `docs/evals/agent_tool_routing_failed_modes.jsonl`
- Split: `failure`
- Count: 24 prompts
- Classes: 8 `web_search`, 10 `data_recall`, 6 `no_action`

## Final Score

```json
{
  "split": "failure",
  "n": 24,
  "action_accuracy": 100.0,
  "web_precision": 100.0,
  "web_recall": 100.0,
  "web_f1": 100.0,
  "health_context_accuracy": 100.0,
  "no_action_specificity": 100.0,
  "halo_planner_score": 100.0,
  "latency_avg_ms": 7199.17,
  "latency_median_ms": 6872.0,
  "latency_min_ms": 5953,
  "latency_p90_ms": 8278,
  "latency_max_ms": 8717
}
```

Prediction distribution:

- `tool_request`: 8
- `final`: 16
- `web_research`: 8
- `local_context`: 10
- `smalltalk`: 6

## What Changed

- The planner is now treated as a first-pass direction agent that emits `web_search`, `data_recall`, `no_action`, or `clarify`.
- Raw Qwen planner text is canonicalized before execution, repairing schema drift such as `tool__request`, `web__search`, route/type swaps, enum-literal values, and multiple JSON objects.
- The host gate still blocks web search for local HALO/watch/UI prompts, so tool calls are not baked into every flow.
- The planner prompt is shorter and uses a bounded sequence length for direction planning.
- The eval writes row-by-row predictions so partial failures are visible.

## Speed Finding

Attempted warm `LlmModule` reuse was not safe with the current ExecuTorch Java LLM wrapper. The second call reused internal `cur_pos_` state and crashed with:

```text
sequence length exceeded - please increase the seq_len value
```

So the final eval uses fresh module load per planner request. This avoids the fatal crash, but latency is still high. The next real speed fix needs a runner/API path that can reset the LLM session between prompts, or a native planner path that avoids long Qwen generation entirely.

Final pulled artifacts:

- `/tmp/halo_agent_eval_failed_modes_final/predictions.jsonl`
- `/tmp/halo_agent_eval_failed_modes_final/metrics.json`
