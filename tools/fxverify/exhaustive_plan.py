#!/usr/bin/env python3
"""Plan individual and combinatorial cases for one effect.

The inventory (FxParamInventoryTest -> params.json) supplies the real bounds,
defaults, units and enum labels. The effect profile supplies the input recipe
and the mode applicability rules. Every parameter is tested individually at
its min/default/max (or every dropdown/boolean state); combinations are the
full Cartesian product, with rows that move a mode-inactive control marked
disabled instead of rendered.
"""

from __future__ import annotations

import argparse
import csv
import itertools
import json
import re
from pathlib import Path


def load_inventory(path: Path, effect_id: str) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    for effect in data.get("effects", []):
        if effect.get("id") == effect_id:
            return effect
    raise SystemExit(f"effect {effect_id!r} not found in {path}")


def load_profile(path: Path) -> dict:
    profile = json.loads(path.read_text(encoding="utf-8"))
    if not profile.get("effect"):
        raise SystemExit(f"profile has no effect id: {path}")
    return profile


def display(p: dict, raw: int) -> str:
    t = p.get("type")
    if t == "ENUM":
        labels = p.get("enums") or []
        return labels[raw] if 0 <= raw < len(labels) else str(raw)
    if t == "BOOLEAN":
        return "On" if raw else "Off"
    if t == "ACTION":
        return ""
    div = int(p.get("div") or 1)
    value = raw / float(div)
    text = f"{value:.4f}".rstrip("0").rstrip(".")
    unit = (p.get("unit") or "").strip()
    if unit == "%":
        return text + "%"
    return text + unit


def slug(p: dict, raw: int) -> str:
    text = display(p, raw).replace("%", "pct").strip()
    text = text.replace(" ", "_").replace("/", "-")
    text = re.sub(r"[^A-Za-z0-9._+-]+", "_", text)
    text = text.strip("_")
    return text or str(raw)


def values_for(p: dict) -> list[tuple[int, str]]:
    t = p.get("type")
    if t == "ENUM":
        return [(i, label) for i, label in enumerate(p.get("enums") or [])]
    if t == "BOOLEAN":
        return [(0, "Off"), (1, "On")]
    if t == "ACTION" or t == "CURVE_NODES":
        return []
    raw = [int(p["min"]), int(p["def"]), int(p["max"])]
    seen: list[int] = []
    for v in raw:
        if v not in seen:
            seen.append(v)
    return [(v, display(p, v)) for v in seen]


def is_active(profile: dict, param_id: str, values: dict[str, int],
              defaults: dict[str, int]) -> bool:
    rules = (profile.get("active") or {}).get(param_id)
    if not rules:
        return True
    for rule in rules:
        other = rule["param"]
        actual = values.get(other, defaults.get(other))
        if "equals" in rule and actual != int(rule["equals"]):
            return False
        if "in" in rule and actual not in [int(x) for x in rule["in"]]:
            return False
    return True


def inactive_params(profile: dict, values: dict[str, int],
                    defaults: dict[str, int]) -> list[str]:
    return [
        p for p in defaults
        if not is_active(profile, p, values, defaults)
    ]


def build_plan(effect: dict, profile: dict, input_meta: dict) -> dict:
    effect_id = effect["id"]
    params = effect["params"]
    param_by_id = {p["id"]: p for p in params}
    defaults = {p["id"]: int(p["def"]) for p in params}
    mode_id = profile.get("mode_param")
    action_param = next((p for p in params if p.get("type") == "ENUM"
                         and p["id"] == mode_id), None)
    if action_param is None:
        raise SystemExit(f"profile mode_param {mode_id!r} is not an enum")
    targets = {int(k): v for k, v in (profile.get("targets") or {}).items()}
    combo_columns = profile.get("combo_columns") or []
    for target in targets.values():
        if target not in param_by_id:
            raise SystemExit(f"profile target {target!r} is not a parameter")

    cases: list[dict] = []
    baseline: dict[int, str] = {}

    def add(case: dict) -> None:
        cases.append(case)

    # ---- individual parameters -----------------------------------------
    for p in params:
        pid = p["id"]
        for raw, label in values_for(p):
            values = dict(defaults)
            values.update((profile.get("individual_modes") or {}).get(pid, {}))
            values[pid] = raw
            active = is_active(profile, pid, values, defaults)
            case_id = f"ind_{pid}_{raw}"
            if pid == mode_id:
                baseline[raw] = case_id
            out = f"Output_{effect_id}_{pid}_{slug(p, raw)}.wav"
            add({
                "id": case_id,
                "kind": "individual",
                "param": pid,
                "value": raw,
                "value_display": display(p, raw),
                "values": values,
                "values_display": {
                    qid: display(param_by_id[qid], qv)
                    for qid, qv in values.items()
                },
                "active": bool(active),
                "target_active": bool(active),
                "render": bool(active),
                "output": out if active else None,
            })

    # ---- disabled invariance pairs -------------------------------------
    for target in targets.values():
        p = param_by_id[target]
        for raw, label in values_for(p):
            if raw == p["def"]:
                continue
            inactive_mode = None
            for mode_raw, mode_label in values_for(action_param):
                values = dict(defaults)
                values[mode_id] = mode_raw
                values[target] = raw
                if not is_active(profile, target, values, defaults):
                    inactive_mode = mode_raw
                    break
            if inactive_mode is None:
                continue
            values = dict(defaults)
            values[mode_id] = inactive_mode
            values[target] = raw
            action_label = display(action_param, inactive_mode)
            case_id = f"dis_{target}_{inactive_mode}_{raw}"
            add({
                "id": case_id,
                "kind": "disabled",
                "param": target,
                "value": raw,
                "value_display": display(p, raw),
                "values": values,
                "values_display": {
                    qid: display(param_by_id[qid], qv)
                    for qid, qv in values.items()
                },
                "active": False,
                "target_active": False,
                "render": True,
                "allow_inactive": True,
                "compare_to": baseline.get(inactive_mode),
                "output": (f"Output_{effect_id}_disabled_{target}_in_"
                           f"{slug(action_param, inactive_mode)}_{slug(p, raw)}.wav"),
            })

    # ---- full Cartesian product of all value-bearing parameters --------
    combo_params = [p for p in params if values_for(p)]
    choices = [values_for(p) for p in combo_params]
    combo_active = combo_disabled = 0
    for idx, combo in enumerate(itertools.product(*choices), start=1):
        values = dict(defaults)
        for p, (raw, label) in zip(combo_params, combo):
            values[p["id"]] = raw
        inactive = inactive_params(profile, values, defaults)
        invalid = [pid for pid in inactive
                   if values[pid] != defaults[pid]]
        active = not invalid
        action_raw = values[mode_id]
        action_label = display(action_param, action_raw)
        target_id = targets.get(action_raw)
        target_p = param_by_id[target_id] if target_id else None
        target_raw = values.get(target_id) if target_id else None
        prefix = f"Output_{effect_id}_combo_{idx:04d}"
        if active:
            combo_active += 1
            cols = []
            for pid in combo_columns:
                cols.append(f"{pid}_{slug(param_by_id[pid], values[pid])}")
            output = (f"{prefix}_{slug(action_param, action_raw)}_"
                      + "_".join(cols)
                      + (f"_{target_id}_{slug(target_p, target_raw)}"
                         if target_p else "")
                      + ".wav")
            reason = ""
        else:
            combo_disabled += 1
            output = None
            bad = invalid[0]
            reason = (f"{bad} is inactive when {mode_id}={action_label} "
                      f"(value {display(param_by_id[bad], values[bad])}, "
                      f"default {display(param_by_id[bad], defaults[bad])})")
        add({
            "id": f"combo_{idx:04d}",
            "kind": "combination",
            "param": "",
            "value": None,
            "value_display": "",
            "values": values,
            "values_display": {
                qid: display(param_by_id[qid], qv)
                for qid, qv in values.items()
            },
            "active": bool(active),
            "target_active": bool(active),
            "render": bool(active),
            "output": output,
            "disabled": invalid,
            "reason": reason,
        })

    counts = {
        "individual": sum(1 for c in cases if c["kind"] == "individual"),
        "disabled_invariance": sum(1 for c in cases if c["kind"] == "disabled"),
        "combinations": sum(1 for c in cases if c["kind"] == "combination"),
        "active_combinations": combo_active,
        "disabled_combinations": combo_disabled,
        "rendered": sum(1 for c in cases if c["render"]),
    }
    return {
        "effect": effect_id,
        "title": effect.get("title", profile.get("title", effect_id)),
        "sample_rate": int(input_meta.get("sample_rate", 48000)),
        "input": input_meta.get("file", profile["input"]["file"]),
        "input_meta": input_meta,
        "output_format": profile.get("output_format", "pcm16"),
        "params": params,
        "defaults": defaults,
        "mode_param": mode_id,
        "targets": {str(k): v for k, v in targets.items()},
        "cases": cases,
        "counts": counts,
    }


def write_plan(plan: dict, effect_dir: Path) -> None:
    effect_dir.mkdir(parents=True, exist_ok=True)
    (effect_dir / "plan.json").write_text(
        json.dumps(plan, indent=2) + "\n", encoding="utf-8"
    )

    fields = [
        "id", "kind", "render", "active", "param", "value_display",
        "values", "disabled", "reason", "output", "compare_to",
    ]
    with (effect_dir / "plan.csv").open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        for c in plan["cases"]:
            vd = c.get("values_display") or {}
            row = {
                "id": c["id"],
                "kind": c["kind"],
                "render": c.get("render", False),
                "active": c.get("active", False),
                "param": c.get("param", ""),
                "value_display": c.get("value_display", ""),
                "values": "; ".join(f"{k}={v}" for k, v in vd.items()),
                "disabled": ";".join(c.get("disabled") or []),
                "reason": c.get("reason", ""),
                "output": c.get("output") or "",
                "compare_to": c.get("compare_to", ""),
            }
            writer.writerow(row)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--inventory", required=True)
    ap.add_argument("--profile", required=True)
    ap.add_argument("--root", required=True,
                    help="effect folder (contains input_meta.json)")
    args = ap.parse_args()

    effect_dir = Path(args.root)
    profile = load_profile(Path(args.profile))
    effect = load_inventory(Path(args.inventory), profile["effect"])
    meta_path = effect_dir / "input_meta.json"
    if not meta_path.is_file():
        raise SystemExit(f"input metadata missing: {meta_path}")
    input_meta = json.loads(meta_path.read_text(encoding="utf-8"))
    plan = build_plan(effect, profile, input_meta)
    write_plan(plan, effect_dir)
    c = plan["counts"]
    print(
        f"plan: {c['individual']} individual, {c['disabled_invariance']} "
        f"invariance, {c['combinations']} permutations "
        f"({c['active_combinations']} active, "
        f"{c['disabled_combinations']} disabled); "
        f"{c['rendered']} renders"
    )


if __name__ == "__main__":
    main()
