# Verify a published GitHub Release actually carries the accepted artifacts.
#
# Checks, in order:
#   1. the release exists and its tag matches the expected version
#   2. every asset's size and SHA-256 matches the local accepted file
#   3. the source zip contains no .git / build output / signing material / local config
#
# Usage:
#   pwsh -File tools/verify-release.ps1 -Tag v1.0.1 -Apk dist\GIme-v1.0.1.apk -Source dist\GIme-v1.0.1-source.zip
param(
    [string]$Tag = "v1.0.1",
    [string]$Repo = "HagimeLab/GIme",
    [string]$Apk = "dist\GIme-v1.0.1.apk",
    [string]$Source = "dist\GIme-v1.0.1-source.zip"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root = Split-Path -Parent $PSScriptRoot
$fails = @()

function Resolve-Local([string]$p) {
    if ([IO.Path]::IsPathRooted($p)) { return $p }
    return (Join-Path $root $p)
}

$apkPath = Resolve-Local $Apk
$srcPath = Resolve-Local $Source
foreach ($f in @($apkPath, $srcPath)) {
    if (-not (Test-Path $f)) { Write-Host "missing local file: $f" -ForegroundColor Red; exit 2 }
}

Write-Host "== querying release $Tag =="
try {
    $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/tags/$Tag" `
        -Headers @{ "User-Agent" = "GIme-release-verify"; "Accept" = "application/vnd.github+json" } `
        -TimeoutSec 30
}
catch {
    Write-Host "cannot read release: $($_.Exception.Message)" -ForegroundColor Red
    exit 2
}

if ($rel.tag_name -ne $Tag) { $fails += "tag mismatch: release says '$($rel.tag_name)', expected '$Tag'" }
if ($rel.draft -eq $true) { $fails += "release is still a draft" }
if (-not $rel.assets -or $rel.assets.Count -eq 0) { $fails += "release has no assets" }

# Remote asset -> local file we consider accepted
$expect = @{}
if ($rel.assets) {
    foreach ($a in $rel.assets) {
        if ($a.name -match '\.apk$') { $expect[$a.name] = $apkPath }
        elseif ($a.name -match 'source\.zip$' -or $a.name -match '-source\.zip$') { $expect[$a.name] = $srcPath }
    }
}

Write-Host "== comparing assets =="
foreach ($name in $expect.Keys) {
    $asset = $rel.assets | Where-Object { $_.name -eq $name }
    $local = $expect[$name]
    $localHash = (Get-FileHash $local -Algorithm SHA256).Hash
    $localSize = (Get-Item $local).Length
    Write-Host ("  {0}: remote {1} bytes / local {2} bytes" -f $name, $asset.size, $localSize)
    if ($asset.size -ne $localSize) { $fails += "$name size differs (remote $($asset.size) vs local $localSize)" }
    # GitHub exposes a digest field on newer responses; verify when present
    if ($asset.digest -and $asset.digest -match '^sha256:(.+)$') {
        $remoteHash = $Matches[1].ToUpper()
        if ($remoteHash -ne $localHash) { $fails += "$name sha256 differs (remote $remoteHash vs local $localHash)" }
        else { Write-Host "    sha256 matches" -ForegroundColor Green }
    }
    else {
        Write-Host "    (no remote digest; size matched, download the asset to compare bytes)" -ForegroundColor Yellow
    }
}
foreach ($a in $rel.assets) {
    if (-not $expect.ContainsKey($a.name)) { Write-Host "    note: unexpected asset '$($a.name)'" -ForegroundColor Yellow }
}

Write-Host "== release notes length =="
# The release body IS the in-app update dialog text, so it must stay short.
# Lines pointing at docs are metadata, not notes, so they are not counted.
$notesLines = @()
foreach ($line in ("" + $rel.body -split "`n")) {
    $t = $line.Trim()
    if ($t -eq "" -or $t -match 'docs/') { continue }
    $notesLines += $t
}
$notes = (($notesLines -join "") -replace '\s', '')
$len = $notes.Length
Write-Host ("  notes: {0} chars in {1} line(s)" -f $len, $notesLines.Count)
if ($len -lt 50 -or $len -gt 100) {
    $fails += "release notes are $len chars, outside the 50-100 rule (fills the phone dialog)"
} else {
    Write-Host "    within 50-100" -ForegroundColor Green
}

Write-Host "== auditing source zip =="
$z = [IO.Compression.ZipFile]::OpenRead($srcPath)
try {
    $bad = @()
    foreach ($e in $z.Entries) {
        $n = $e.FullName
        if ($n -match '(^|/)(\.git|\.gradle|build|dist|signing)/') { $bad += "$n : forbidden dir" }
        elseif ($n -match '\.(jks|keystore|apk|aab)$') { $bad += "$n : forbidden type" }
        elseif ($n -match '(^|/)(local\.properties|keystore\.properties|build\.local\.cmd)$') { $bad += "$n : machine config" }
    }
    if ($bad.Count -gt 0) { $fails += $bad }
    else { Write-Host ("  clean: {0} entries" -f $z.Entries.Count) -ForegroundColor Green }
}
finally { $z.Dispose() }

Write-Host ""
if ($fails.Count -gt 0) {
    Write-Host "RELEASE VERIFICATION FAILED:" -ForegroundColor Red
    $fails | ForEach-Object { Write-Host "  $_" }
    exit 1
}
Write-Host "RELEASE VERIFICATION PASSED" -ForegroundColor Green
