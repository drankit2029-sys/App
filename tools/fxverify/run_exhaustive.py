#!/usr/bin/env python3
"""Run one effect's exhaustive parameter sweep end to end.

1. inventory the runtime parameters (unless cached)
2. build the deterministic input and the individual/combination plan
3. render every active case with the Robolectric renderer
4. analyse the outputs and write report.md / analysis.csv
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))

from exhaustive_inputs import build_input  # noqa: E402
from exhaustive_plan import build_plan, load_inventory, load_profile, write_plan  # noqa: E402


def gradle(args: list[str], env: dict | None = None, attempts: int = 3) -> int:
    for attempt in range(1, attempts + 1):
        result = subprocess.run(
            ["sh", "./gradlew", ":app:testDebugUnitTest", *args, "--stacktrace"],
            cwd=REPO, env=env,
        )
        if result.returncode == 0:
            return 0
        print(f"gradle attempt {attempt}/{attempts} failed; retrying", flush=True)
        time.sleep(15)
    return 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--effect", required=True)
    ap.add_argument("--root", default="fx-exhaustive")
    ap.add_argument("--inventory", default="app/build/fxverify/params/params.json")
    ap.add_argument("--profile", default=None)
    ap.add_argument("--skip-inventory", action="store_true")
    ap.add_argument("--refresh-inventory", action="store_true")
    args = ap.parse_args()

    profile_path = Path(args.profile) if args.profile else (
        REPO / "tools/fxverify/exhaustive" / f"{args.effect}.json"
    )
    profile = load_profile(profile_path)
    if profile["effect"] != args.effect:
        raise SystemExit(f"profile {profile_path} is for {profile['effect']}, "
                         f"not {args.effect}")

    inventory = (REPO / args.inventory).resolve()
    if args.refresh_inventory or not args.skip_inventory or not inventory.is_file():
        print("inventory: FxParamInventoryTest", flush=True)
        if gradle(["--tests", "*FxParamInventoryTest"]):
            return 1
    if not inventory.is_file():
        raise SystemExit(f"inventory missing after inventory run: {inventory}")

    effect_dir = (REPO / args.root / args.effect).resolve()
    effect_dir.mkdir(parents=True, exist_ok=True)
    input_meta = build_input(profile, REPO, effect_dir)
    effect = load_inventory(inventory, args.effect)
    plan = build_plan(effect, profile, input_meta)
    write_plan(plan, effect_dir)
    print(
        f"plan: {plan['counts']['rendered']} renders, "
        f"{plan['counts']['combinations']} permutations "
        f"({plan['counts']['active_combinations']} active)",
        flush=True,
    )

    env = os.environ.copy()
    env["FX_EXHAUSTIVE_PLAN"] = str(effect_dir / "plan.json")
    env["FX_EXHAUSTIVE_ROOT"] = str(effect_dir)
    print("render: FxExhaustiveRenderTest", flush=True)
    if gradle(["--tests", "*FxExhaustiveRenderTest"], env=env):
        return 1

    print("analyse", flush=True)
    result = subprocess.run([
        sys.executable, str(HERE / "exhaustive_analyze.py"),
        "--root", str(effect_dir),
        "--plan", str(effect_dir / "plan.json"),
    ], cwd=REPO)
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
