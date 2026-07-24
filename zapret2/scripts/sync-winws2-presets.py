#!/usr/bin/env python3
"""Import portable winws2 presets into the Android nfqws2 catalog."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


WINDOWS_ONLY_LUA = {
    "--lua-init=@lua/custom_diag.lua",
}

LIST_OPTION = re.compile(
    r"(?P<option>--(?:hostlist|hostlist-exclude|ipset|ipset-exclude)=)"
    r"(?P<prefix>@?lists/)(?P<name>[^,]+)"
)
BLOB_OPTION = re.compile(r"--blob=([^:]+):(.+)")
CAPTURE_POLICY = (
    "# NFQWS2_TCP_PKT_OUT=20",
    "# NFQWS2_TCP_PKT_IN=10",
    "# NFQWS2_UDP_PKT_OUT=20",
    "# NFQWS2_UDP_PKT_IN=10",
)

# Exact filename normalization only.  A broad list is never substituted for a
# narrower source dependency because that would change profile matching.
ANDROID_LIST_RENAMES = {
    "russia-youtube-rtmps.txt": "ipset-russia-youtube-rtmps.txt",
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


def compact_blank_lines(lines: list[str]) -> list[str]:
    compact: list[str] = []
    for line in lines:
        if not line.strip():
            line = ""
            if compact and not compact[-1]:
                continue
        compact.append(line)
    return compact


def make_portable(lines: list[str], package_root: Path) -> list[str]:
    lines = [line.rstrip() for line in lines]
    portable = [
        line
        for line in lines
        if not line.startswith("--wf-")
        and not line.startswith("--ipcache")
        and line not in WINDOWS_ONLY_LUA
    ]
    portable = [
        LIST_OPTION.sub(
            lambda match: (
                f"{match.group('option')}{match.group('prefix')}"
                f"{ANDROID_LIST_RENAMES.get(match.group('name'), match.group('name'))}"
            ),
            line,
        )
        for line in portable
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
    return compact_blank_lines(result)


def collect_common_blobs(presets: dict[str, list[str]]) -> list[str]:
    """Build one deterministic blob block shared by every builtin preset."""
    by_name: dict[str, str] = {}
    for preset_name in sorted(presets, key=str.casefold):
        for line in presets[preset_name]:
            match = BLOB_OPTION.fullmatch(line)
            if not match:
                continue
            blob_name = match.group(1)
            previous = by_name.setdefault(blob_name, line)
            if previous != line:
                raise ValueError(
                    f"conflicting --blob definition for {blob_name!r}: "
                    f"{previous!r} != {line!r} in {preset_name}"
                )
    return [by_name[name] for name in sorted(by_name, key=str.casefold)]


def with_common_blobs(lines: list[str], common_blobs: list[str]) -> list[str]:
    """Replace preset-local blob declarations with the canonical common block."""
    without_blobs = [line for line in lines if not BLOB_OPTION.fullmatch(line)]
    if not common_blobs:
        return compact_blank_lines(without_blobs)
    try:
        first_profile = next(
            index for index, line in enumerate(without_blobs) if line.startswith("--name=")
        )
    except StopIteration as error:
        raise ValueError("preset has no --name profile boundary") from error

    global_lines = without_blobs[:first_profile]
    profile_lines = without_blobs[first_profile:]
    while global_lines and not global_lines[-1]:
        global_lines.pop()
    return compact_blank_lines(global_lines + [""] + common_blobs + [""] + profile_lines)


def with_android_capture_policy(lines: list[str]) -> list[str]:
    """Make the imported TXT the sole source of its kernel capture limits."""
    existing = [line for line in lines if line.startswith("# NFQWS2_")]
    if existing:
        if tuple(existing) != CAPTURE_POLICY:
            raise ValueError("preset has an incomplete or unsupported capture policy")
        return lines
    header_end = 0
    while header_end < len(lines) and (
        lines[header_end].startswith("#") or not lines[header_end]
    ):
        header_end += 1
    prefix = lines[:header_end]
    while prefix and not prefix[-1]:
        prefix.pop()
    return compact_blank_lines(prefix + list(CAPTURE_POLICY) + [""] + lines[header_end:])


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

    imported_lines: dict[str, list[str]] = {}
    skipped: list[str] = []
    for candidate in sorted(source.glob("*.txt"), key=lambda path: path.name.casefold()):
        lines = candidate.read_text(encoding="utf-8-sig").splitlines()
        if is_windows_only(lines):
            skipped.append(candidate.name)
            continue
        portable = make_portable(lines, destination.parent)
        imported_lines[candidate.name] = portable

    try:
        common_blobs = collect_common_blobs(imported_lines)
        imported = {
            name: "\n".join(
                with_android_capture_policy(with_common_blobs(lines, common_blobs))
            ).rstrip()
            + "\n"
            for name, lines in imported_lines.items()
        }
    except ValueError as error:
        parser.error(str(error))

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
