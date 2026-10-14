#!/usr/bin/env python3
"""Part D of docs/engineering/far-cloud-p1-plan.md: build the replay prompt set and the R1 prompt.

Subcommands:
  select    p1_prompts.csv + p1_forecasts.csv -> <evaluation_id>.<inland|coastal>.txt files
  build-r1  production system prompt text + patch markdown -> override system prompt file

Standard library only. Never touches the network.
"""
import argparse
import csv
import random
import re
import sys
from pathlib import Path

csv.field_size_limit(sys.maxsize)

# Training cases (plan section 4): reported individually, never pooled with the set.
# (location name, ISO date, target type)
TRAINING_CASES = [
    ("Angel of the North", "2026-10-13", "SUNSET"),
    ("Low Hauxley", "2026-10-04", "SUNRISE"),
    ("Copt Hill", "2026-03-05", "SUNSET"),
    ("Copt Hill", "2026-03-11", "SUNSET"),
]

# Approximate USD per million tokens, from backend/src/main/resources/application-example.yml
# (cost.sonnet55-*, cost.sonnet-*, cost.haiku-*). Standard (not batch) prices, no cache discount.
PRICES = {
    "SONNET_55": (2.00, 10.00),
    "SONNET": (3.00, 15.00),
    "HAIKU": (1.00, 5.00),
}
# ASSUMPTION, not measured: tokens per replay call (user message ~ plus uncached share of system).
DEFAULT_INPUT_TOKENS = 3500
DEFAULT_OUTPUT_TOKENS = 400
DEFAULT_ARMS = ["SONNET_55", "SONNET", "HAIKU", "SONNET_55"]  # arm 4 is SONNET_55 + R1 prompt


def read_csv(path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def to_int(value):
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return None


def truthy(value):
    return str(value).strip().lower() in ("t", "true", "1", "yes")


def is_coastal_prompt(forecast_row, user_message):
    """Which system prompt production used for this row.

    Production keys the builder on DATA: BatchRequestFactory.selectBuilder
    (backend/.../service/evaluation/BatchRequestFactory.java:387) returns the coastal builder when
    AtmosphericData.tide() != null, else the inland one; ForecastTaskCollector.java:551 repeats the
    rule ("Keyed on DATA, not on metadata"). The export carries no tide columns, so tide() != null is
    approximated by location metadata: a SEASCAPE type or is_coastal_tidal (LocationService.isCoastal,
    LocationService.java:400, is "tide_type non-empty"). A coastal location with no stored tide
    extremes is bucketed inland in production and will be misclassified here; the stored prompt
    cannot tell (the tide line was removed in v2.13.2). A STORM SURGE block in the message proves
    coastal. Use --coastal-ids to override.
    """
    if "STORM SURGE" in user_message.upper():
        return True
    if forecast_row is None:
        return False
    types = {t.strip() for t in (forecast_row.get("loc_types") or "").split(",")}
    return "SEASCAPE" in types or truthy(forecast_row.get("loc_is_coastal_tidal"))


def training_label(row):
    for name, date, target in TRAINING_CASES:
        if row["loc_name"] == name and row["target_date"] == date and row["target_type"] == target:
            return f"{name} {date} {target}"
    return None


def latest_run_per_slot(rows):
    """Latest run before the event per (location, date, type): plan section 4's definition.

    Uses the prompt export's own fields; rows must be SCORED/NULL batch state with a rating.
    The prompt export has no solar_event_time, so 'before the event' is approximated by taking the
    greatest forecast_run_at among the prompted rows of the slot (a post-event admin run has no
    batch prompt).
    """
    best = {}
    for r in rows:
        if r.get("batch_state") not in ("", None, "SCORED"):
            continue
        if to_int(r.get("rating")) is None:
            continue
        key = (r["location_id"], r["target_date"], r["target_type"])
        if key not in best or r["forecast_run_at"] > best[key]["forecast_run_at"]:
            best[key] = r
    return best


def select(prompts, forecasts, seed):
    """Returns (go_rows, sample_rows, training_rows) from the prompt rows."""
    forecast_by_id = {r["evaluation_id"]: r for r in forecasts}
    latest = latest_run_per_slot(prompts)
    gos, threes, training = [], [], []
    for r in latest.values():
        label = training_label(r)
        if label:
            training.append(r)
            continue
        rating = to_int(r["rating"])
        if rating >= 4:
            gos.append(r)
        elif rating == 3:
            threes.append(r)
    gos.sort(key=lambda r: int(r["evaluation_id"]))
    threes.sort(key=lambda r: int(r["evaluation_id"]))
    rng = random.Random(seed)
    sample = rng.sample(threes, min(len(gos), len(threes)))
    sample.sort(key=lambda r: int(r["evaluation_id"]))
    training.sort(key=lambda r: int(r["evaluation_id"]))
    return gos, sample, training, forecast_by_id


def write_prompt(directory, row, forecast_by_id, coastal_ids):
    eid = row["evaluation_id"]
    coastal = eid in coastal_ids or is_coastal_prompt(forecast_by_id.get(eid), row["user_message"])
    kind = "coastal" if coastal else "inland"
    directory.mkdir(parents=True, exist_ok=True)
    (directory / f"{eid}.{kind}.txt").write_text(row["user_message"], encoding="utf-8")
    return kind


def estimate_cost(n_prompts, runs, arms, in_tok, out_tok):
    per_arm = []
    total = 0.0
    for model in arms:
        p_in, p_out = PRICES[model]
        cost = n_prompts * runs * (in_tok * p_in + out_tok * p_out) / 1_000_000
        per_arm.append((model, cost))
        total += cost
    return total, per_arm


def cmd_select(args):
    prompts = read_csv(args.prompts)
    forecasts = read_csv(args.forecasts) if args.forecasts else []
    gos, sample, training, forecast_by_id = select(prompts, forecasts, args.seed)
    coastal_ids = set()
    if args.coastal_ids:
        coastal_ids = {x.strip() for x in Path(args.coastal_ids).read_text().split() if x.strip()}
    out = Path(args.out)
    kinds = {"inland": 0, "coastal": 0}
    for r in gos + sample:
        kinds[write_prompt(out, r, forecast_by_id, coastal_ids)] += 1
    for r in training:
        write_prompt(out / "training", r, forecast_by_id, coastal_ids)
    n = len(gos) + len(sample)
    arms = args.arms.split(",") if args.arms else DEFAULT_ARMS
    total, per_arm = estimate_cost(n, args.runs, arms, args.input_tokens, args.output_tokens)
    print(f"gos (rating >= 4, latest prompted run per slot): {len(gos)}")
    print(f"3-star sample (seed {args.seed}): {len(sample)}")
    print(f"written to {out}: {n} prompts ({kinds['inland']} inland, {kinds['coastal']} coastal)")
    print(f"training cases (in {out / 'training'}, not in the set): {len(training)}")
    print(f"COST ESTIMATE: {n} prompts x N={args.runs} x {len(arms)} arms "
          f"= {n * args.runs * len(arms)} calls")
    print(f"  assumptions: {args.input_tokens} input + {args.output_tokens} output tokens per call "
          "(ASSUMED, not measured), standard prices, no cache discount;")
    print("  USD per MTok in/out from application-example.yml: "
          + ", ".join(f"{m} {PRICES[m][0]}/{PRICES[m][1]}" for m in PRICES))
    for model, cost in per_arm:
        print(f"  {model}: ${cost:,.2f}")
    print(f"  total: ${total:,.2f}")
    return 0


def parse_patch(text):
    olds = {m.group(1): m.group(2) for m in re.finditer(r"```old (\w+)\n(.*?)\n```", text, re.S)}
    news = {m.group(1): m.group(2) for m in re.finditer(r"```new (\w+)\n(.*?)\n```", text, re.S)}
    if not olds or set(olds) != set(news):
        raise ValueError(f"patch must have matching old/new blocks (old={sorted(olds)}, "
                         f"new={sorted(news)})")
    return [(k, olds[k], news[k]) for k in sorted(olds)]


def apply_patch(system_prompt, replacements):
    """Exact-substring replace; raises ValueError if any old text is not found exactly once."""
    result = system_prompt
    for key, old, new in replacements:
        count = result.count(old)
        if count != 1:
            raise ValueError(f"replacement {key}: old text found {count} times, expected exactly 1")
        result = result.replace(old, new)
    return result


def cmd_build_r1(args):
    system_prompt = Path(args.system_prompt).read_text(encoding="utf-8")
    replacements = parse_patch(Path(args.patch).read_text(encoding="utf-8"))
    try:
        patched = apply_patch(system_prompt, replacements)
    except ValueError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1
    Path(args.out).write_text(patched, encoding="utf-8")
    print(f"wrote {args.out}: {len(replacements)} replacements, "
          f"{len(system_prompt)} -> {len(patched)} characters")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("select")
    s.add_argument("--prompts", required=True, help="p1_prompts.csv")
    s.add_argument("--forecasts", help="p1_forecasts.csv (for the coastal/inland choice)")
    s.add_argument("--out", required=True, help="output directory (REPLAY_PROMPTS_DIR)")
    s.add_argument("--seed", type=int, default=20261014)
    s.add_argument("--runs", type=int, default=3, help="N per prompt and arm")
    s.add_argument("--arms", help="comma-separated models, one per arm (default: 4 arms)")
    s.add_argument("--input-tokens", type=int, default=DEFAULT_INPUT_TOKENS)
    s.add_argument("--output-tokens", type=int, default=DEFAULT_OUTPUT_TOKENS)
    s.add_argument("--coastal-ids", help="file of evaluation ids to force to the coastal prompt")
    s.set_defaults(func=cmd_select)
    b = sub.add_parser("build-r1")
    b.add_argument("--system-prompt", required=True)
    b.add_argument("--patch", required=True)
    b.add_argument("--out", required=True)
    b.set_defaults(func=cmd_build_r1)
    args = ap.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
