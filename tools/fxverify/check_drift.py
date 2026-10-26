#!/usr/bin/env python3
"""Fails when the effect catalog in the code and the CI matrix drift apart.

The workflow cannot build its matrix from a file, so the effect ids are written
into fx-verify.yml by hand. This check compares that list with the inventory the
JVM harness derives from the processors themselves, so a newly registered effect
is a build failure rather than a silent gap in coverage.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


def matrix_effects(workflow: Path) -> list[str]:
    text = workflow.read_text(encoding="utf-8")
    match = re.search(r"\n\s*effect:\s*\[(.*?)\]", text, re.S)
    if not match:
        raise SystemExit(f"no effect matrix found in {workflow}")
    body = match.group(1)
    return [item.strip().strip("'\"") for item in body.split(",") if item.strip()]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--params", default="app/build/fxverify/params/params.json")
    ap.add_argument("--workflow", default=".github/workflows/fx-verify.yml")
    ap.add_argument("--inventory", default="app/build/fxverify/params/effect_ids.txt")
    args = ap.parse_args()

    params_path = Path(args.params)
    if not params_path.is_file():
        print(f"inventory missing: {params_path}", file=sys.stderr)
        return 2
    inventory = [e["id"] for e in json.loads(
        params_path.read_text(encoding="utf-8"))["effects"]]
    matrix = matrix_effects(Path(args.workflow))
    ids_file = Path(args.inventory)
    listed = [l.strip() for l in ids_file.read_text(encoding="utf-8").splitlines()
              if l.strip()] if ids_file.is_file() else inventory

    problems = []
    if set(inventory) != set(matrix):
        problems.append("matrix and catalog differ: only in code "
                        f"{sorted(set(inventory) - set(matrix))}, only in matrix "
                        f"{sorted(set(matrix) - set(inventory))}")
    if len(matrix) != len(set(matrix)):
        duplicates = sorted({x for x in matrix if matrix.count(x) > 1})
        problems.append(f"matrix lists duplicates: {duplicates}")
    if set(listed) != set(inventory):
        problems.append("inventory file and params.json differ")

    print(f"catalog={len(inventory)} matrix={len(matrix)}")
    if problems:
        for p in problems:
            print(f"DRIFT: {p}", file=sys.stderr)
        return 1
    print("no drift: the workflow matrix matches the effect catalog")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
