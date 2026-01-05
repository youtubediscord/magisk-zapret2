#!/usr/bin/env python3
"""
Convert strategies.sh (bash format) to strategies-tcp.ini (INI format)
"""
import re
import sys
from pathlib import Path

def parse_strategies_sh(content: str) -> dict:
    """Parse strategies.sh and extract TCP strategies with their args"""
    strategies = {}

    # 1. Get list of strategy names from list_tcp_strategies()
    list_match = re.search(r'list_tcp_strategies\s*\(\s*\)\s*\{\s*\n\s*echo\s+"([^"]+)"', content)
    if not list_match:
        print("ERROR: Could not find list_tcp_strategies() function")
        return strategies

    strategy_names = list_match.group(1).split()
    print(f"Found {len(strategy_names)} TCP strategies")

    # 2. Parse case statement to get args for each strategy
    # Pattern: strategy_name)\n            echo "$filter \n...args..."
    for name in strategy_names:
        # Match the case block for this strategy
        pattern = rf'{re.escape(name)}\)\s*\n\s*echo\s+"\$filter\s*(.*?)"(?:\s*;;|\s*$)'
        match = re.search(pattern, content, re.DOTALL)

        if match:
            args_raw = match.group(1)
            # Clean up: remove line continuations, extra whitespace
            args = re.sub(r'\\\n\s*', ' ', args_raw)  # Remove \ and newlines
            args = re.sub(r'\s+', ' ', args).strip()  # Normalize whitespace
            strategies[name] = args
        else:
            print(f"WARNING: Could not find args for strategy '{name}'")
            strategies[name] = ""

    return strategies

def generate_description(name: str) -> str:
    """Generate human-readable description from strategy name"""
    # Replace underscores with spaces and capitalize words
    words = name.split('_')
    # Capitalize first word, keep rest as-is for technical terms
    return ' '.join(w.capitalize() if i == 0 else w for i, w in enumerate(words))

def write_ini(strategies: dict, output_path: Path, default_strategy: str = "syndata_multisplit_tls_google_700"):
    """Write strategies to INI format"""
    lines = [
        "# ===============================",
        "# TCP Strategies for nfqws2",
        "# ===============================",
        "# Each section [strategy_name] contains:",
        "# - desc: Description of strategy",
        "# - args: Lua desync arguments (WITHOUT --filter-tcp, --hostlist - they come from categories)",
        "#",
        "# Usage: These strategies are applied to TLS/HTTP traffic",
        "# Filter arguments (--filter-tcp, --hostlist, --ipset) are added by categories.ini",
        "",
    ]

    # Add [default] section first
    if default_strategy in strategies:
        lines.append("[default]")
        lines.append(f"desc=Default TCP bypass strategy (same as {default_strategy})")
        lines.append(f"args={strategies[default_strategy]}")
        lines.append("")

    # Add all other strategies
    for name, args in strategies.items():
        lines.append(f"[{name}]")
        lines.append(f"desc={generate_description(name)}")
        lines.append(f"args={args}")
        lines.append("")

    output_path.write_text('\n'.join(lines), encoding='utf-8')
    print(f"Written {len(strategies) + 1} strategies to {output_path}")  # +1 for default

def main():
    script_dir = Path(__file__).parent
    zapret_dir = script_dir.parent

    strategies_sh = zapret_dir / "strategies.sh"
    strategies_ini = zapret_dir / "strategies-tcp.ini"

    if not strategies_sh.exists():
        print(f"ERROR: {strategies_sh} not found")
        sys.exit(1)

    print(f"Reading {strategies_sh}...")
    content = strategies_sh.read_text(encoding='utf-8')

    print("Parsing TCP strategies...")
    strategies = parse_strategies_sh(content)

    if not strategies:
        print("ERROR: No strategies found")
        sys.exit(1)

    print(f"Writing {strategies_ini}...")
    write_ini(strategies, strategies_ini)

    print("Done!")

if __name__ == "__main__":
    main()
