# HALO Agent Tool Routing Eval

This eval measures whether the on-device planner understands when to use tools, when to use local HALO data, and when to do nothing.

Dataset:

- `agent_tool_routing_dataset.jsonl`
- 60 synthetic prompts
- `judge` split: 40 prompts for demo/reporting
- `dev` split: 20 prompts for iteration

## Gold Actions

`gold_action` is the primary label:

- `web_search`: model should request `web_search`.
- `data_recall`: model should answer from HALO/local health context or general health knowledge without web.
- `no_action`: model should not request tools or health context; usually smalltalk or clarification.

`gold_route` is the secondary label:

- `smalltalk`
- `clarify`
- `local_context`
- `health_status`
- `next_step`
- `general_health`
- `web_research`

`needs_health_context` checks whether the final prompt should include local HALO health JSON.

## Planner Output Contract

Run the model planner with the same tool protocol used by the app. The model should output one compact JSON object:

```json
{"type":"final","route":"smalltalk|clarify|local_context|health_status|next_step|general_health","needs_health_context":true}
```

or:

```json
{"type":"tool_request","tool":"web_search","route":"web_research","query":"short search query","reason":"why external evidence is required","needs_health_context":false}
```

For scoring, map outputs to predicted action:

- `type=tool_request` and `tool=web_search` -> `web_search`
- `type=final` and `needs_health_context=true` -> `data_recall`
- `type=final` and `route in smalltalk, clarify` -> `no_action`
- `type=final` and `route=general_health` with `needs_health_context=false` -> `data_recall`

## Judge Metric

Report these on the `judge` split:

1. **Action Accuracy**

   Percent of prompts where predicted action equals `gold_action`.

2. **Web Precision**

   Of all prompts where the planner requested `web_search`, how many were actually `gold_action=web_search`.

3. **Web Recall**

   Of all `gold_action=web_search` prompts, how many requested `web_search`.

4. **No-Action Specificity**

   Of all `gold_action=no_action` prompts, how many avoided both web search and health context.

5. **Health Context Accuracy**

   Percent of prompts where predicted `needs_health_context` equals the dataset label.

Recommended headline score:

```text
HALO Planner Score =
  0.50 * Action Accuracy
+ 0.20 * Web F1
+ 0.20 * Health Context Accuracy
+ 0.10 * No-Action Specificity
```

Where:

```text
Web F1 = 2 * Web Precision * Web Recall / (Web Precision + Web Recall)
```

## What Judges Should Care About

The most important failure modes are:

- False web call: planner searches when local data or no action was enough.
- Missed web call: planner refuses to search when user explicitly asked for current/sourced/latest info.
- False health context: planner attaches personal health data for smalltalk.
- Missed health context: planner answers personal health-status prompts without HALO data.

## Target MVP Thresholds

For demo readiness:

- Action Accuracy: `>= 85%`
- Web Precision: `>= 90%`
- Web Recall: `>= 80%`
- No-Action Specificity: `>= 90%`
- Health Context Accuracy: `>= 85%`

For strong judge-readiness:

- Action Accuracy: `>= 92%`
- Web Precision: `>= 95%`
- Web Recall: `>= 90%`
- No-Action Specificity: `>= 95%`
- Health Context Accuracy: `>= 92%`

## Reporting Sentence

Use this format in the deck/demo:

```text
On a 40-prompt held-out routing benchmark, HALO selected the correct action X% of the time, avoided unnecessary web calls on Y% of no-action prompts, and only requested web search when source-backed or current information was needed.
```
