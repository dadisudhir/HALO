#!/usr/bin/env python3
import argparse
import json
from collections import Counter
from statistics import median


def load_jsonl(path):
    rows = []
    with open(path, "r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as error:
                raise SystemExit(f"{path}:{line_number}: invalid JSON: {error}") from error
    return rows


def predicted_action(row):
    output_type = str(row.get("type", "")).lower()
    tool = str(row.get("tool", "")).lower()
    route = str(row.get("route", "")).lower()
    needs_health_context = bool(row.get("needs_health_context", False))

    if output_type == "tool_request" and tool == "web_search":
        return "web_search"
    if output_type == "final" and route in {"smalltalk", "clarify"} and not needs_health_context:
        return "no_action"
    return "data_recall"


def latency_summary(rows):
    values = sorted(int(row["latency_ms"]) for row in rows if "latency_ms" in row)
    if not values:
        return {}
    p90_index = min(len(values) - 1, round((len(values) - 1) * 0.90))
    return {
        "latency_avg_ms": round(sum(values) / len(values), 2),
        "latency_median_ms": median(values),
        "latency_min_ms": values[0],
        "latency_p90_ms": values[p90_index],
        "latency_max_ms": values[-1],
    }


def f1(precision, recall):
    if precision + recall == 0:
        return 0.0
    return 2 * precision * recall / (precision + recall)


def pct(value):
    return round(value * 100, 2)


def main():
    parser = argparse.ArgumentParser(description="Score HALO agent tool routing predictions.")
    parser.add_argument("--gold", default="docs/evals/agent_tool_routing_dataset.jsonl")
    parser.add_argument("--predictions", required=True)
    parser.add_argument("--split", default="judge")
    args = parser.parse_args()

    gold_rows = load_jsonl(args.gold)
    prediction_list = load_jsonl(args.predictions)
    prediction_rows = {row["id"]: row for row in prediction_list}
    if args.split != "all":
        gold_rows = [row for row in gold_rows if row.get("split") == args.split]

    missing = [row["id"] for row in gold_rows if row["id"] not in prediction_rows]
    if missing:
        raise SystemExit(f"missing predictions for {len(missing)} ids: {', '.join(missing[:10])}")

    counts = Counter()
    for gold in gold_rows:
        prediction = prediction_rows[gold["id"]]
        gold_action = gold["gold_action"]
        pred_action = predicted_action(prediction)
        gold_health_context = bool(gold["needs_health_context"])
        pred_health_context = bool(prediction.get("needs_health_context", False))

        counts["total"] += 1
        counts["action_correct"] += int(pred_action == gold_action)
        counts["health_context_correct"] += int(pred_health_context == gold_health_context)

        counts["pred_web"] += int(pred_action == "web_search")
        counts["gold_web"] += int(gold_action == "web_search")
        counts["true_web"] += int(pred_action == "web_search" and gold_action == "web_search")

        counts["gold_no_action"] += int(gold_action == "no_action")
        counts["true_no_action"] += int(gold_action == "no_action" and pred_action == "no_action" and not pred_health_context)

    action_accuracy = counts["action_correct"] / counts["total"]
    web_precision = counts["true_web"] / counts["pred_web"] if counts["pred_web"] else 0.0
    web_recall = counts["true_web"] / counts["gold_web"] if counts["gold_web"] else 0.0
    web_f1 = f1(web_precision, web_recall)
    health_context_accuracy = counts["health_context_correct"] / counts["total"]
    no_action_specificity = counts["true_no_action"] / counts["gold_no_action"] if counts["gold_no_action"] else 0.0
    halo_planner_score = (
        0.50 * action_accuracy
        + 0.20 * web_f1
        + 0.20 * health_context_accuracy
        + 0.10 * no_action_specificity
    )

    result = {
        "split": args.split,
        "n": counts["total"],
        "action_accuracy": pct(action_accuracy),
        "web_precision": pct(web_precision),
        "web_recall": pct(web_recall),
        "web_f1": pct(web_f1),
        "health_context_accuracy": pct(health_context_accuracy),
        "no_action_specificity": pct(no_action_specificity),
        "halo_planner_score": pct(halo_planner_score),
    }
    result.update(latency_summary([prediction_rows[row["id"]] for row in gold_rows]))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
