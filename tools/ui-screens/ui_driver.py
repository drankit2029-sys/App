#!/usr/bin/env python3
"""Generic adb UI driver: run declarative actions and screenshots on an emulator.

Examples:
  ui_driver.py --out shots/Nexus5 --scenario scenario.json
  ui_driver.py --out shots/x --steps '[{"action":"launch","package":"com.app"}]'

A scenario is {"steps": [...]} (or a bare list). Every step may define:
  "action": launch | wait | tap | swipe | press_back | press_home |
            press_recents | open_notifications | collapse_notifications |
            press_key | screenshot | assert_visible | shell | rotate
  "delay_ms": wait this long after the action before the screenshot (default 0)
  "screenshot": file name (written under <out>/<folder>/<name>.png)
  "folder": screenshot subfolder (default "steps")
  "optional": true to continue when the target/action fails

Selectors for tap/assert_visible:
  "by": "text" | "id" | "desc" | "xy" | "contains"
  "value": selector value, or "x,y" for xy
  "timeout_ms": wait for the selector (default 3000)
"""

import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def adb(args, binary=False, check=True):
    cmd = ["adb"] + args
    if binary:
        return subprocess.run(cmd, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, check=check)
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          text=True, check=check)


def shell(*args, check=True):
    return adb(["shell"] + list(args), check=check).stdout.strip()


def dump_ui():
    shell("uiautomator", "dump", "/sdcard/ui_driver.xml", check=False)
    raw = adb(["exec-out", "cat", "/sdcard/ui_driver.xml"], binary=True).stdout
    try:
        return ET.fromstring(raw)
    except ET.ParseError:
        return None


def center(node):
    b = node.attrib.get("bounds", "")
    m = BOUNDS_RE.search(b)
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def matches(node, by, value, contains=False):
    if by == "id":
        rid = node.attrib.get("resource-id", "")
        return rid == value or rid.endswith("/" + value)
    if by == "text":
        text = node.attrib.get("text", "")
        return value in text if contains else text == value
    if by == "desc":
        desc = node.attrib.get("content-desc", "")
        return value in desc if contains else desc == value
    if by == "contains":
        return value in node.attrib.get("text", "") or value in node.attrib.get(
            "content-desc", "")
    return False


def find_node(root, by, value, contains=False):
    if root is None:
        return None
    for node in root.iter("node"):
        if matches(node, by, value, contains):
            if center(node):
                return node
    return None


def wait_node(by, value, timeout_ms, contains=False):
    deadline = time.time() + max(0, timeout_ms) / 1000.0
    while True:
        node = find_node(dump_ui(), by, value, contains)
        if node is not None:
            return node
        if time.time() >= deadline:
            return None
        time.sleep(0.25)


def take_screenshot(out_root, folder, name):
    directory = os.path.join(out_root, folder or "steps")
    os.makedirs(directory, exist_ok=True)
    path = os.path.join(directory, name + ".png")
    with open(path, "wb") as f:
        proc = subprocess.run(["adb", "exec-out", "screencap", "-p"],
                              stdout=f, stderr=subprocess.PIPE)
    if proc.returncode != 0 or not os.path.exists(path) or os.path.getsize(path) == 0:
        raise RuntimeError("screencap failed: " + path)
    return path


def device_info():
    info = {}
    for key, prop in (("sdk", "ro.build.version.sdk"),
                      ("model", "ro.product.model"),
                      ("manufacturer", "ro.product.manufacturer"),
                      ("release", "ro.build.version.release")):
        try:
            info[key] = shell("getprop", prop)
        except Exception:
            info[key] = ""
    for key, cmd in (("wmSize", ["wm", "size"]),
                     ("wmDensity", ["wm", "density"]),
                     ("rotation", ["settings", "get", "system", "user_rotation"])):
        try:
            info[key] = shell(*cmd)
        except Exception:
            info[key] = ""
    return info


def run_step(step, out_root, report):
    action = step.get("action", "wait")
    delay = int(step.get("delay_ms", 0))
    by = step.get("by", "text")
    value = str(step.get("value", ""))
    timeout = int(step.get("timeout_ms", 3000))
    contains = bool(step.get("contains", False))
    optional = bool(step.get("optional", False))
    entry = {"action": action, "by": by, "value": value}

    try:
        if action == "launch":
            package = step.get("package") or value
            shell("am", "force-stop", package, check=False)
            if step.get("activity"):
                shell("am", "start", "-n", package + "/" + step["activity"],
                      check=False)
            else:
                shell("monkey", "-p", package, "-c",
                      "android.intent.category.LAUNCHER", "1", check=False)
        elif action == "wait":
            pass
        elif action == "tap":
            if by == "xy":
                x, y = [int(v.strip()) for v in value.split(",")[:2]]
                shell("input", "tap", str(x), str(y))
            else:
                node = wait_node(by, value, timeout, contains)
                if node is None:
                    raise RuntimeError("target not found: %s=%s" % (by, value))
                x, y = center(node)
                shell("input", "tap", str(x), str(y))
        elif action == "swipe":
            parts = value.split(",")
            if len(parts) != 4:
                raise RuntimeError("swipe value must be x1,y1,x2,y2")
            shell("input", "swipe", *[p.strip() for p in parts],
                  str(step.get("duration_ms", 300)))
        elif action == "press_back":
            shell("input", "keyevent", "KEYCODE_BACK")
        elif action == "press_home":
            shell("input", "keyevent", "KEYCODE_HOME")
        elif action == "press_recents":
            shell("input", "keyevent", "KEYCODE_APP_SWITCH")
        elif action == "press_key":
            key = value or step.get("key", "KEYCODE_ENTER")
            shell("input", "keyevent", key)
        elif action == "open_notifications":
            ok = shell("cmd", "statusbar", "expand-notifications", check=False)
            time.sleep(0.2)
            if not ok:
                shell("input", "swipe", "540", "0", "540", "1600", "250",
                      check=False)
        elif action == "collapse_notifications":
            ok = shell("cmd", "statusbar", "collapse", check=False)
            if not ok:
                shell("input", "keyevent", "KEYCODE_BACK", check=False)
        elif action == "screenshot":
            pass
        elif action == "assert_visible":
            node = wait_node(by, value, timeout, contains)
            if node is None:
                raise AssertionError("not visible: %s=%s" % (by, value))
        elif action == "shell":
            args = step.get("args") or shlex.split(value)
            shell(*args)
        elif action == "rotate":
            rotation = str(step.get("rotation", value or "0"))
            shell("settings", "put", "system", "accelerometer_rotation", "0")
            shell("settings", "put", "system", "user_rotation", rotation)
            time.sleep(0.8)
        else:
            raise RuntimeError("unknown action: " + action)

        entry["ok"] = True
    except Exception as exc:
        entry["ok"] = False
        entry["error"] = str(exc)
        report["failures"].append(entry)
        if not optional:
            raise

    if delay > 0:
        time.sleep(delay / 1000.0)
    name = step.get("screenshot")
    if name:
        folder = step.get("folder", "steps")
        path = take_screenshot(out_root, folder, name)
        entry["screenshot"] = os.path.relpath(path, out_root)
        report["screenshots"].append(entry["screenshot"])
    report["steps"].append(entry)
    return entry


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True, help="device screenshot root")
    parser.add_argument("--scenario", help="JSON scenario file")
    parser.add_argument("--steps", help="inline JSON steps array")
    parser.add_argument("--strict", action="store_true",
                        help="fail if any optional action also fails")
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    if args.scenario:
        with open(args.scenario, "r", encoding="utf-8") as f:
            scenario = json.load(f)
    elif args.steps:
        scenario = json.loads(args.steps)
    else:
        scenario = {"steps": [{"action": "wait"}]}
    steps = scenario.get("steps", scenario) if isinstance(scenario, dict) else scenario

    report = {"device": device_info(), "steps": [], "screenshots": [],
              "failures": []}
    status = 0
    try:
        for step in steps:
            run_step(step, args.out, report)
    except Exception as exc:
        report["fatal"] = str(exc)
        status = 1
    finally:
        report_path = os.path.join(args.out, "driver-report.json")
        with open(report_path, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
        print("ui driver: %d screenshot(s), %d failure(s) -> %s"
              % (len(report["screenshots"]), len(report["failures"]),
                 report_path))
    if args.strict and report["failures"]:
        status = 1
    return status


if __name__ == "__main__":
    sys.exit(main())
