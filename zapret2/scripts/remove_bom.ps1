# Remove BOM from INI files
$files = @(
    "H:\Privacy\magisk-zapret2\zapret2\strategies-tcp.ini",
    "H:\Privacy\magisk-zapret2\zapret2\strategies-udp.ini",
    "H:\Privacy\magisk-zapret2\zapret2\strategies-stun.ini"
)

foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file)
    if ($content.Length -gt 0 -and $content[0] -eq [char]0xFEFF) {
        $content = $content.Substring(1)
        [System.IO.File]::WriteAllText($file, $content, [System.Text.UTF8Encoding]::new($false))
        Write-Host "Removed BOM from $file"
    } else {
        Write-Host "No BOM in $file"
    }
}
