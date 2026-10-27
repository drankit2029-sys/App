#!/usr/bin/env python3
"""Writes a full project dump: every tracked source file, one section each.

The format matches the repo's existing project-dump.txt so diffs between dumps
stay readable: a header with the run identity, then a banner and the contents of
each file. Binaries, build output and the downloaded verification artifacts are
skipped.

Usage: make_project_dump.py [output] [--root DIR]
"""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
from datetime import datetime
from pathlib import Path

SKIP_DIRS = {".git", ".gradle", ".idea", ".commandcode", "build",
             "fx-verify-runs", ".cxx", "__pycache__"}
SKIP_SUFFIXES = {".apk", ".aab", ".wav", ".mp3", ".m4a", ".mp4", ".png", ".jpg",
                 ".jpeg", ".webp", ".gif", ".zip", ".jar", ".so", ".aar",
                 ".keystore", ".jks", ".pyc"}
# The dump cannot contain itself; session transcripts and handoff notes are not
# project sources.
SKIP_NAMES = {"project-dump.txt", "session-ses_ed80.md",
              "takeone_fixed_files.txt", "Updated Files.txt"}


def md5_of(path: Path) -> str:
    if not path.is_file():
        return "not built"
    h = hashlib.md5()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def git(root: Path, *args: str) -> str:
    try:
        return subprocess.run(["git", "-C", str(root), *args],
                              capture_output=True, text=True,
                              check=True).stdout.strip()
    except Exception:
        return ""


def files_to_dump(root: Path) -> list[Path]:
    out = []
    for path in sorted(root.rglob("*")):
        rel = path.relative_to(root)
        if any(part in SKIP_DIRS for part in rel.parts):
            continue
        if not path.is_file():
            continue
        if path.name in SKIP_NAMES:
            continue
        if path.suffix.lower() in SKIP_SUFFIXES:
            continue
        out.append(path)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("output", nargs="?", default="project-dump.txt")
    ap.add_argument("--root", default=".")
    args = ap.parse_args()

    root = Path(args.root).resolve()
    files = files_to_dump(root)
    head = git(root, "log", "-1", "--pretty=%h %s")
    branch = git(root, "rev-parse", "--abbrev-ref", "HEAD")

    header = [
        "PROJECT DUMP — TakeOne",
        f"Generated: {datetime.now().strftime('%Y-%m-%dT%H:%M:%S')}",
        f"Root: {root}",
        f"APK md5: {md5_of(root / 'app-debug.apk')}",
        f"HEAD: {head}",
        f"Branch: {branch}",
        f"Files: {len(files)} source files",
        "=" * 81,
        "",
    ]

    out = Path(args.output)
    skipped_binary = 0
    written = 0
    with open(out, "w", encoding="utf-8") as fh:
        fh.write("\n".join(header))
        for path in files:
            rel = path.relative_to(root).as_posix()
            try:
                text = path.read_text(encoding="utf-8")
            except (UnicodeDecodeError, OSError):
                skipped_binary += 1
                continue
            fh.write("=" * 81 + "\n")
            fh.write(f"FILE: {rel}\n")
            fh.write("=" * 81 + "\n")
            fh.write(text)
            if not text.endswith("\n"):
                fh.write("\n")
            fh.write("\n")
            written += 1

    print(f"dump written to {out}")
    print(f"files included: {written}, binary skipped: {skipped_binary}, "
          f"size: {out.stat().st_size / 1e6:.2f} MB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
