# Convert strategies.sh to strategies-tcp.ini
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$zapretDir = Split-Path -Parent $scriptDir

$strategiesSh = Join-Path $zapretDir "strategies.sh"
$strategiesIni = Join-Path $zapretDir "strategies-tcp.ini"

Write-Host "Reading $strategiesSh..."
$content = Get-Content $strategiesSh -Raw -Encoding UTF8

# 1. Get list of strategy names from list_tcp_strategies()
$listMatch = [regex]::Match($content, 'list_tcp_strategies\s*\(\s*\)\s*\{\s*\n\s*echo\s+"([^"]+)"')
if (-not $listMatch.Success) {
    Write-Error "Could not find list_tcp_strategies() function"
    exit 1
}

$strategyNames = $listMatch.Groups[1].Value -split '\s+'
Write-Host "Found $($strategyNames.Count) TCP strategies"

# 2. Parse case statement for each strategy
$strategies = @{}
$defaultStrategy = "syndata_multisplit_tls_google_700"

foreach ($name in $strategyNames) {
    # Match: strategy_name)\n            echo "$filter \...args..."
    $escapedName = [regex]::Escape($name)
    $pattern = "$escapedName\)\s*\n\s*echo\s+`"\`$filter\s*(.*?)`"(?:\s*;;|\s*$)"
    $match = [regex]::Match($content, $pattern, [System.Text.RegularExpressions.RegexOptions]::Singleline)

    if ($match.Success) {
        $argsRaw = $match.Groups[1].Value
        # Clean up: remove \ and newlines, normalize whitespace
        $args = $argsRaw -replace '\\\r?\n\s*', ' '
        $args = $args -replace '\s+', ' '
        $args = $args.Trim()
        $strategies[$name] = $args
    } else {
        Write-Warning "Could not find args for strategy '$name'"
        $strategies[$name] = ""
    }
}

# 3. Generate description from name
function Get-Description($name) {
    $words = $name -split '_'
    $result = @()
    foreach ($word in $words) {
        if ($result.Count -eq 0) {
            $result += $word.Substring(0,1).ToUpper() + $word.Substring(1)
        } else {
            $result += $word
        }
    }
    return $result -join ' '
}

# 4. Write INI file
Write-Host "Writing $strategiesIni..."
$lines = @(
    "# ===============================",
    "# TCP Strategies for nfqws2",
    "# ===============================",
    "# Each section [strategy_name] contains:",
    "# - desc: Description of strategy",
    "# - args: Lua desync arguments (WITHOUT --filter-tcp, --hostlist - they come from categories)",
    "#",
    "# Usage: These strategies are applied to TLS/HTTP traffic",
    "# Filter arguments (--filter-tcp, --hostlist, --ipset) are added by categories.ini",
    ""
)

# Add [default] section first
if ($strategies.ContainsKey($defaultStrategy)) {
    $lines += "[default]"
    $lines += "desc=Default TCP bypass strategy (same as $defaultStrategy)"
    $lines += "args=$($strategies[$defaultStrategy])"
    $lines += ""
}

# Add all strategies in order
foreach ($name in $strategyNames) {
    $lines += "[$name]"
    $lines += "desc=$(Get-Description $name)"
    $lines += "args=$($strategies[$name])"
    $lines += ""
}

$lines -join "`n" | Set-Content $strategiesIni -Encoding UTF8 -NoNewline

$totalCount = $strategyNames.Count + 1  # +1 for default
Write-Host "Written $totalCount strategies to $strategiesIni"
Write-Host "Done!"
