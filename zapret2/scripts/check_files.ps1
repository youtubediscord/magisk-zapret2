# Check line endings and first section
$file = "H:\Privacy\magisk-zapret2\zapret2\strategies-tcp.ini"
$bytes = [System.IO.File]::ReadAllBytes($file)

# Count line endings
$crlf = 0
for ($i = 0; $i -lt $bytes.Length - 1; $i++) {
    if ($bytes[$i] -eq 13 -and $bytes[$i+1] -eq 10) {
        $crlf++
    }
}
Write-Host "CRLF count: $crlf"

# Read and show first few lines
$lines = Get-Content $file -First 15
for ($i = 0; $i -lt $lines.Count; $i++) {
    $line = $lines[$i]
    $hex = ($line.ToCharArray() | ForEach-Object { '{0:X2}' -f [int][char]$_ }) -join ' '
    Write-Host "Line $($i+1): [$line]"
    if ($line -match '^\[') {
        Write-Host "  -> Section line, hex: $hex"
    }
}
