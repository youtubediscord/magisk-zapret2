#!/usr/bin/env python3
"""
Convert strategies.sh to strategies-tcp.ini

Parses the bash case statement structure in strategies.sh and generates
an INI file compatible with the other strategy files (strategies-udp.ini, strategies-stun.ini).
"""

import re
import os
from pathlib import Path


def parse_strategies_sh(content: str) -> dict:
    """
    Parse strategies.sh case statement and extract strategy names and arguments.

    Returns dict: {strategy_name: args_string}
    """
    strategies = {}

    # Pattern to match case entries like:
    #     strategy_name)
    #         echo "$filter \
    # --lua-desync=... \
    # --lua-desync=..."
    #         ;;

    # Find the get_tcp_strategy_options function
    tcp_match = re.search(
        r'get_tcp_strategy_options\(\)\s*\{.*?case\s+"\$strategy_name"\s+in(.*?)esac',
        content,
        re.DOTALL
    )

    if not tcp_match:
        print("ERROR: Could not find get_tcp_strategy_options case statement")
        return strategies

    case_content = tcp_match.group(1)

    # Split by case entries (strategy_name))
    # Pattern: strategy_name)\n            echo "..."
    pattern = r'(\w+)\)\s*\n\s*echo\s+"([^"]*(?:\\"[^"]*)*)"'

    # More robust pattern that handles multi-line echo with backslash continuations
    entries = re.split(r'\n\s{8}(\w+)\)', case_content)

    # entries[0] is empty or whitespace, then alternating: name, content, name, content...
    for i in range(1, len(entries), 2):
        if i + 1 >= len(entries):
            break

        name = entries[i].strip()
        block = entries[i + 1]

        # Extract echo content
        echo_match = re.search(r'echo\s+"([^"]*(?:\\"[^"]*)*)"', block, re.DOTALL)
        if echo_match:
            raw_args = echo_match.group(1)

            # Remove $filter variable placeholder and clean up
            raw_args = raw_args.replace('$filter', '').strip()

            # Remove backslash-newline continuations and normalize whitespace
            raw_args = re.sub(r'\\\n\s*', ' ', raw_args)
            raw_args = re.sub(r'\s+', ' ', raw_args).strip()

            if raw_args:
                strategies[name] = raw_args

    return strategies


def generate_description(name: str) -> str:
    """
    Generate a human-readable description from strategy name.
    """
    # Replace underscores with spaces
    desc = name.replace('_', ' ')

    # Known patterns to expand
    replacements = {
        'syndata': 'SYN data',
        'multisplit': 'multi-split',
        'multidisorder': 'multi-disorder',
        'tls google': 'TLS Google pattern',
        'tls max': 'TLS Max.ru pattern',
        'seqovl': 'sequence overlap',
        'midsld': 'mid-SLD position',
        'sniext': 'SNI extension',
        'autottl': 'auto TTL',
        'badseq': 'bad sequence',
        'md5sig': 'MD5 signature',
        'tcpack': 'TCP ACK manipulation',
        'fakedsplit': 'fake+split',
        'fakeddisorder': 'fake+disorder',
        'datanoack': 'data without ACK',
        'ipfrag': 'IP fragmentation',
        'udplen': 'UDP length',
        'wssize': 'window size',
    }

    for pattern, replacement in replacements.items():
        desc = desc.replace(pattern, replacement)

    # Capitalize first letter
    desc = desc[0].upper() + desc[1:] if desc else desc

    return desc


def write_ini(strategies: dict, output_path: str, default_strategy: str = None):
    """
    Write strategies to INI format file.
    """
    lines = [
        "# ===============================",
        "# TCP Strategies for nfqws2",
        "# ===============================",
        "# Auto-generated from strategies.sh",
        "# Each section [strategy_name] contains:",
        "# - desc: Description of strategy",
        "# - args: Lua desync arguments (WITHOUT --filter-tcp, --hostlist - they come from categories)",
        "#",
        "# Usage: These strategies are applied to TCP/TLS traffic",
        "# Filter arguments (--filter-tcp, --hostlist, --ipset) are added by categories.txt",
        "",
        "[disabled]",
        "desc=TCP bypass disabled",
        "args=",
        "",
    ]

    # Determine default strategy
    if default_strategy is None:
        if 'syndata_multisplit_tls_google_700' in strategies:
            default_strategy = 'syndata_multisplit_tls_google_700'
        elif strategies:
            default_strategy = list(strategies.keys())[0]

    # Write default section
    if default_strategy and default_strategy in strategies:
        lines.append("[default]")
        lines.append(f"desc=Default TCP bypass strategy ({default_strategy})")
        lines.append(f"args={strategies[default_strategy]}")
        lines.append("")

    # Group strategies by prefix for better organization
    groups = {
        'syndata': [],
        'seqovl': [],
        'multisplit': [],
        'multidisorder': [],
        'fake': [],
        'tls': [],
        'dis': [],
        'general': [],
        'censorliber': [],
        'other': [],
    }

    for name in strategies:
        if name == default_strategy:
            continue  # Already written

        placed = False
        for prefix in groups:
            if name.startswith(prefix + '_') or name == prefix:
                groups[prefix].append(name)
                placed = True
                break

        if not placed:
            groups['other'].append(name)

    # Write grouped sections
    section_titles = {
        'syndata': 'SYNDATA STRATEGIES',
        'seqovl': 'SEQUENCE OVERLAP STRATEGIES',
        'multisplit': 'MULTISPLIT STRATEGIES',
        'multidisorder': 'MULTIDISORDER STRATEGIES',
        'fake': 'FAKE PACKET STRATEGIES',
        'tls': 'TLS-SPECIFIC STRATEGIES',
        'dis': 'DISORDER STRATEGIES',
        'general': 'GENERAL STRATEGIES',
        'censorliber': 'CENSORLIBER STRATEGIES',
        'other': 'OTHER STRATEGIES',
    }

    for group_name, group_strategies in groups.items():
        if not group_strategies:
            continue

        lines.append(f"# ==================== {section_titles.get(group_name, group_name.upper())} ====================")
        lines.append("")

        for name in sorted(group_strategies):
            args = strategies[name]
            desc = generate_description(name)

            lines.append(f"[{name}]")
            lines.append(f"desc={desc}")
            lines.append(f"args={args}")
            lines.append("")

    # Write file
    with open(output_path, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines))

    print(f"Written {len(strategies)} strategies to {output_path}")


def main():
    # Find paths
    script_dir = Path(__file__).parent
    zapret_dir = script_dir.parent

    strategies_sh = zapret_dir / 'strategies.sh'
    output_ini = zapret_dir / 'strategies-tcp.ini'

    print(f"Reading: {strategies_sh}")

    if not strategies_sh.exists():
        print(f"ERROR: {strategies_sh} not found")
        return 1

    with open(strategies_sh, 'r', encoding='utf-8') as f:
        content = f.read()

    print("Parsing strategies...")
    strategies = parse_strategies_sh(content)

    if not strategies:
        print("ERROR: No strategies found")
        return 1

    print(f"Found {len(strategies)} TCP strategies")

    write_ini(strategies, str(output_ini))

    print("Done!")
    return 0


if __name__ == '__main__':
    exit(main())
