#!/usr/bin/env python3
"""Aggregate TeleCrypt per-call metrics (JSON Lines) into summary statistics.

Each input line is one call record produced by CallMetrics.toJsonLine():
    {"schema":"telecrypt.call.metrics/v1",
     "latency_ms":{"join_sent":123,...},
     "counters":{"key_sent":1,...}}

Usage:
    python3 scripts/aggregate_call_metrics.py ~/.telecrypt/call-metrics.jsonl
    python3 scripts/aggregate_call_metrics.py metrics.jsonl --csv out.csv
"""
import argparse
import json
import statistics
import sys


def percentile(values, p):
    if not values:
        return None
    s = sorted(values)
    k = (len(s) - 1) * (p / 100.0)
    lo = int(k)
    hi = min(lo + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def load(path):
    records = []
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError:
                print(f"skipping malformed line: {line[:60]}...", file=sys.stderr)
    return records


def collect(records, section):
    cols = {}
    for r in records:
        for key, val in (r.get(section) or {}).items():
            if isinstance(val, (int, float)):
                cols.setdefault(key, []).append(val)
    return cols


def stat_rows(cols):
    rows = []
    for key in sorted(cols):
        vals = cols[key]
        rows.append((
            key, len(vals),
            round(statistics.median(vals), 1),
            round(percentile(vals, 95), 1),
            min(vals), max(vals),
        ))
    return rows


def print_table(title, rows, unit):
    print(f"\n## {title}\n")
    print(f"| Metric | n | median | p95 | min | max |")
    print(f"|---|---|---|---|---|---|")
    for key, n, med, p95, lo, hi in rows:
        print(f"| {key} | {n} | {med}{unit} | {p95}{unit} | {lo}{unit} | {hi}{unit} |")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("path", help="path to call-metrics.jsonl")
    ap.add_argument("--csv", help="optional CSV output path")
    args = ap.parse_args()

    records = load(args.path)
    if not records:
        print("no records found", file=sys.stderr)
        sys.exit(1)
    print(f"# Call metrics over {len(records)} call(s)")

    latency = stat_rows(collect(records, "latency_ms"))
    counters = stat_rows(collect(records, "counters"))
    print_table("Latency (ms since bridge start)", latency, " ms")
    print_table("Counters (per call)", counters, "")

    if args.csv:
        import csv
        with open(args.csv, "w", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh)
            w.writerow(["section", "metric", "n", "median", "p95", "min", "max"])
            for key, n, med, p95, lo, hi in latency:
                w.writerow(["latency_ms", key, n, med, p95, lo, hi])
            for key, n, med, p95, lo, hi in counters:
                w.writerow(["counters", key, n, med, p95, lo, hi])
        print(f"\nCSV written to {args.csv}", file=sys.stderr)


if __name__ == "__main__":
    main()
