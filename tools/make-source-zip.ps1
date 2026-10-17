# Build the source delivery ZIP for GIme and verify it contains no secrets.
# Usage: pwsh -File tools/make-source-zip.ps1 [-Out dist\GIme-source.zip]
param([string]$Out = "")

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

$root = Split-Path -Parent $PSScriptRoot
if (-not $Out) { $Out = Join-Path $root "dist\GIme-source.zip" }
$Out = [IO.Path]::GetFullPath($Out)
New-Item -ItemType Directory -Force (Split-Path -Parent $Out) | Out-Null
if (Test-Path $Out) { Remove-Item $Out -Force }

# Directories never shipped: build output, machine config, signing material, VCS.
$skipDirs = @('.git', '.gradle', '.idea', 'build', 'dist', 'signing', 'node_modules')
# Individual files never shipped.
$skipFiles = @('local.properties', 'keystore.properties')
$skipExt = @('.jks', '.keystore', '.apk', '.aab', '.iml', '.DS_Store', '.zip')

$files = New-Object System.Collections.Generic.List[string]
Get-ChildItem -LiteralPath $root -Recurse -File -Force | ForEach-Object {
    $rel = $_.FullName.Substring($root.Length + 1)
    $parts = $rel -split '[\\/]'
    foreach ($p in $parts[0..($parts.Length - 2)]) {
        if ($skipDirs -contains $p) { return }
    }
    if ($skipFiles -contains $_.Name) { return }
    if ($skipExt -contains $_.Extension) { return }
    $files.Add($_.FullName)
}

$zip = [IO.Compression.ZipFile]::Open($Out, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($f in $files) {
        $rel = $f.Substring($root.Length + 1).Replace('\', '/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f, "GIme/$rel") | Out-Null
    }
}
finally { $zip.Dispose() }

# Verify the produced archive, not just the working tree.
$check = [IO.Compression.ZipFile]::OpenRead($Out)
$bad = @()
try {
    foreach ($e in $check.Entries) {
        $n = $e.FullName
        if ($n -match '(^|/)(\.git|\.gradle|build|dist|signing)/') { $bad += $n; continue }
        if ($n -match '\.(jks|keystore|apk|aab)$') { $bad += $n; continue }
        if ($n -match '(^|/)(local\.properties|keystore\.properties)$') { $bad += $n }
    }
    $count = $check.Entries.Count
}
finally { $check.Dispose() }

if ($bad.Count -gt 0) {
    Write-Host "FAILED: forbidden entries in source zip:" -ForegroundColor Red
    $bad | ForEach-Object { Write-Host "  $_" }
    exit 1
}
Write-Host ("OK: {0} files, no signing material / local config / build output" -f $count)
Write-Host ("     -> {0}" -f $Out)
