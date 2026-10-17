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
$skipFiles = @('local.properties', 'keystore.properties', 'build.local.cmd')
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
        if ($n -match '(^|/)(\.git|\.gradle|build|dist|signing)/') { $bad += "$n : 禁止的目录"; continue }
        if ($n -match '\.(jks|keystore|apk|aab)$') { $bad += "$n : 禁止的文件类型"; continue }
        if ($n -match '(^|/)(local\.properties|keystore\.properties)$') { $bad += "$n : 本机配置"; continue }
        # 再看内容：文件名干净不代表里面没抄进口令或本机路径
        if ($n -notmatch '\.(java|kts|html|md|cmd|ps1|mjs|txt|xml|properties|gitignore)$') { continue }
        if ($e.Length -gt 2MB) { continue }
        $sr = New-Object IO.StreamReader($e.Open())
        try { $t = $sr.ReadToEnd() } finally { $sr.Close() }
        # 路径片段拼接出来，避免本脚本自己的检测串把自己判成违规
        $homeSeg = 'C:' + '\\' + 'Users' + '\\' + 'fan'
        $homeAlt = 'C:' + '/Users/fan'
        if ($t -match [regex]::Escape($homeSeg) -or $t -match [regex]::Escape($homeAlt)) { $bad += "$n : 含本机绝对路径" }
        # 口令必须是引号里的字面量；= keystoreProps.getProperty("storePassword") 是在读配置，不算泄露
        if ($t -match '(storePassword|keyPassword|storePass|keyPass)\s*[=:]\s*["''][^"'']{4,}["'']') { $bad += "$n : 含口令明文" }
        if ($t -match '-----BEGIN [A-Z ]*PRIVATE KEY-----') { $bad += "$n : 含私钥" }
    }
    $count = $check.Entries.Count
}
finally { $check.Dispose() }

if ($bad.Count -gt 0) {
    Write-Host "FAILED: source zip is not safe to ship:" -ForegroundColor Red
    $bad | ForEach-Object { Write-Host "  $_" }
    exit 1
}
Write-Host ("OK: {0} files, no signing material / local config / build output / secrets" -f $count)
Write-Host ("     -> {0}" -f $Out)
