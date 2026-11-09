"""Splits a batch's billed output tokens into the JSON answer and (by difference) thinking.

Reads answers.jsonl from export-answers.sql, counts each rebuilt answer's tokens with the
Messages count_tokens endpoint (free; no generation), and reports the split.

    ANTHROPIC_API_KEY=... python3 -I measure_split.py answers.jsonl

count_tokens counts a whole user message, so a fixed wrapper overhead is measured once on an
empty-ish message and subtracted from every count.
"""
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request

MODEL = "claude-sonnet-5-5"
URL = "https://api.anthropic.com/v1/messages/count_tokens"


def count_tokens(text):
    body = json.dumps({"model": MODEL,
                       "messages": [{"role": "user", "content": text}]}).encode()
    req = urllib.request.Request(URL, data=body, headers={
        "x-api-key": os.environ["ANTHROPIC_API_KEY"],
        "anthropic-version": "2023-06-01",
        "content-type": "application/json",
    })
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req) as resp:
                return json.load(resp)["input_tokens"]
        except urllib.error.HTTPError as e:
            if e.code in (429, 529) and attempt < 5:
                time.sleep(2 ** attempt)
                continue
            raise


def pct(values, p):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(p / 100 * len(ordered)))]


def main(path):
    rows = [json.loads(line) for line in open(path) if line.strip()]
    if not rows:
        sys.exit("no rows in " + path)
    overhead = count_tokens("x") - 1
    billed, answer, thinking = [], [], []
    for i, row in enumerate(rows, 1):
        text = json.dumps(row["answer"], ensure_ascii=False)
        a = count_tokens(text) - overhead
        billed.append(row["output_tokens"])
        answer.append(a)
        thinking.append(max(0, row["output_tokens"] - a))
        if i % 50 == 0:
            print(f"  counted {i}/{len(rows)}", file=sys.stderr)

    total_b, total_a = sum(billed), sum(answer)
    total_t = total_b - total_a
    print(f"calls paired:            {len(rows)}")
    print(f"billed output tokens:    {total_b:,}  (mean {total_b / len(rows):.0f}/call)")
    print(f"answer JSON tokens:      {total_a:,}  (mean {total_a / len(rows):.0f}/call)"
          f"  {100 * total_a / total_b:.0f}%")
    print(f"thinking (difference):   {total_t:,}  (mean {total_t / len(rows):.0f}/call)"
          f"  {100 * total_t / total_b:.0f}%")
    print(f"thinking per call:       median {statistics.median(thinking):.0f}, "
          f"p90 {pct(thinking, 90)}, max {max(thinking)}, "
          f"zero-thinking calls {sum(1 for t in thinking if t < 10)}")
    fields = {}
    for row in rows:
        for k, v in row["answer"].items():
            fields.setdefault(k, []).append(v)
    print("answer fields present:   " + ", ".join(
        f"{k} {len(v)}" for k, v in fields.items()))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "answers.jsonl")
