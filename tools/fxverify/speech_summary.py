#!/usr/bin/env python3
"""Summarise a speech-probe FX matrix run.

Reads every verdicts.json produced by `analyze.py --skip-anchors` and prints
one row per effect plus the hard failures and missed directional expectations.
Exits 1 when any effect has a hard failure (non-finite, length change, thrown
render, numeric blow-up), so the CI job surfaces corruption while still
publishing the full report.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True,
                    help="directory containing per-effect verdicts.json files")
    ap.add_argument("--report", default=None, help="write a Markdown report")
    ap.add_argument("--json-out", default=None)
    args = ap.parse_args()

    root = Path(args.root)
    paths = sorted(root.rglob("verdicts.json"))
    if not paths:
        print(f"no verdicts.json under {root}", file=sys.stderr)
        return 2

    rows = []
    for path in paths:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001
            print(f"cannot read {path}: {exc}", file=sys.stderr)
            continue
        summary = data.get("summary") or {}
        rows.append({
            "effect": summary.get("effect") or path.parent.name,
            "title": summary.get("title", ""),
            "cases": summary.get("cases", 0),
            "fails": summary.get("fails", 0),
            "not_met": summary.get("expectation_not_met", 0),
            "ok": summary.get("single_point_ok", 0),
            "weak": summary.get("weak", 0),
            "no_change": summary.get("no_change", 0),
            "findings": summary.get("findings", 0),
            "series_ok": summary.get("series_ok", 0),
            "series_total": summary.get("series_total", 0),
            "verdicts": data.get("verdicts", []),
        })
    rows.sort(key=lambda r: r["effect"].lower())

    header = ("| effect | cases | fail | not met | 1-point ok | weak | "
              "no change | series |")
    lines = ["# Speech-probe FX matrix", "",
             f"Effects: {len(rows)}", "", header,
             "|---|---|---|---|---|---|---|---|"]
    for r in rows:
        lines.append(
            f"| {r['effect']} | {r['cases']} | {r['fails']} | {r['not_met']} | "
            f"{r['ok']} | {r['weak']} | {r['no_change']} | "
            f"{r['series_ok']}/{r['series_total']} |")

    total_fails = sum(r["fails"] for r in rows)
    total_not_met = sum(r["not_met"] for r in rows)
    total_cases = sum(r["cases"] for r in rows)
    lines += ["", f"Total cases: {total_cases}",
              f"Hard failures: {total_fails}",
              f"Missed directional expectations: {total_not_met}", ""]

    if total_fails:
        lines.append("## Hard failures")
        for r in rows:
            for v in r["verdicts"]:
                if v.get("verdict") == "fail":
                    lines.append(f"- **{r['effect']}** `{v.get('case')}`: "
                                 f"{'; '.join(v.get('fails') or [])}")
        lines.append("")

    if total_not_met:
        lines.append("## Missed directional expectations")
        for r in rows:
            misses = [v for v in r["verdicts"]
                      if v.get("verdict") == "expectation_not_met"]
            if not misses:
                continue
            lines.append(f"### {r['effect']}")
            for v in misses[:20]:
                ev = v.get("evidence") or {}
                lines.append(
                    f"- `{v.get('param')}` = {v.get('valueDisplay')} "
                    f"({v.get('probe')}): {ev.get('expectation', '')}; "
                    f"{ev.get('metric')} = {ev.get('value')} "
                    f"(needs |x| >= {ev.get('min_abs')})")
            if len(misses) > 20:
                lines.append(f"- ... {len(misses) - 20} more")
            lines.append("")

    report = "\n".join(lines) + "\n"
    if args.report:
        Path(args.report).write_text(report, encoding="utf-8")
        print(f"report written to {args.report}")
    else:
        print(report)

    if args.json_out:
        Path(args.json_out).write_text(json.dumps({
            "effects": [{k: v for k, v in r.items() if k != "verdicts"}
                        for r in rows],
            "total_cases": total_cases,
            "total_fails": total_fails,
            "total_not_met": total_not_met,
        }, indent=1), encoding="utf-8")

    return 1 if total_fails else 0


if __name__ == "__main__":
    raise SystemExit(main())
