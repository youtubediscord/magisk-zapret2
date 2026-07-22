#!/usr/bin/env python3
"""Import portable winws2 presets into the Android nfqws2 catalog."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


WINDOWS_ONLY_LUA = {
    "--lua-init=@lua/custom_diag.lua",
    "--lua-init=@lua/fakemultisplit.lua",
    "--lua-init=@lua/fakemultidisorder.lua",
}


def is_windows_only(lines: list[str]) -> bool:
    """Reject profiles that require WinDivert's inbound/circular packet path."""
    return any(
        line.startswith("--in-range=")
        or line.startswith("--wf-tcp-in=")
        or line.startswith("--wf-udp-in=")
        or line.startswith("--lua-desync=circular:")
        for line in lines
    )


def make_portable(lines: list[str], package_root: Path) -> list[str]:
    portable = [
        line
        for line in lines
        if not line.startswith("--wf-")
        and not line.startswith("--name=")
        and line not in WINDOWS_ONLY_LUA
    ]
    body = "\n".join(line for line in portable if not line.startswith("--blob="))
    result: list[str] = []
    for line in portable:
        match = re.fullmatch(r"--blob=([^:]+):@bin/(.+)", line)
        if match:
            blob_name, relative_path = match.groups()
            dependency = package_root / "bin" / relative_path
            if not dependency.is_file() and not re.search(
                rf"(?<![A-Za-z0-9_]){re.escape(blob_name)}(?![A-Za-z0-9_])", body
            ):
                continue
        result.append(line)
    compact: list[str] = []
    for line in result:
        if not line.strip():
            line = ""
            if compact and not compact[-1]:
                continue
        compact.append(line)
    return compact


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="zapretgui builtin/winws2 directory")
    parser.add_argument(
        "--destination",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "presets",
    )
    args = parser.parse_args()

    source = args.source.resolve()
    destination = args.destination.resolve()
    if not source.is_dir():
        parser.error(f"source directory does not exist: {source}")
    destination.mkdir(parents=True, exist_ok=True)

    imported: dict[str, str] = {}
    skipped: list[str] = []
    for candidate in sorted(source.glob("*.txt"), key=lambda path: path.name.casefold()):
        lines = candidate.read_text(encoding="utf-8-sig").splitlines()
        if is_windows_only(lines):
            skipped.append(candidate.name)
            continue
        portable = make_portable(lines, destination.parent)
        imported[candidate.name] = "\n".join(portable).rstrip() + "\n"

    for stale in destination.glob("*.txt"):
        if stale.name.startswith("_") or stale.name in imported:
            continue
        stale.unlink()

    for name, content in imported.items():
        (destination / name).write_text(content, encoding="utf-8", newline="\n")

    print(f"Imported {len(imported)} portable presets")
    print(f"Skipped {len(skipped)} Windows-only presets:")
    for name in skipped:
        print(f"  {name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
