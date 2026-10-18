# 把一个新构建的安装包放进 release/，并同步清单。
#
# 发布件的一致性靠三件事同时成立：清单里的版本号、清单里的文件名、目录里真实的文件。
# 手工改名很容易漏掉一处，这里把三步做成一次原子操作。
#
# 文件名会带上内容指纹（SHA-256 前 8 位）。这不是装饰：jsDelivr 按 URL 强缓存 7 天，
# 同一个版本号重新构建时若文件名不变，新包在缓存过期前不会被下发，
# 用户装到的还是上一版。路径变了，缓存自然失效。
#
# Usage:
#   pwsh -File tools/publish.ps1 -Apk app\build\outputs\apk\release\app-release.apk -Version 1.0.2 -Notes notes.txt
#   pwsh -File tools/publish.ps1 -Apk ... -Version 1.0.2 -Notes "第一条|第二条"

param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Notes = "",
    [string]$Date = "",
    [string]$Dir = "release"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
function Resolve-Local([string]$p) {
    if ([IO.Path]::IsPathRooted($p)) { return $p }
    return (Join-Path $root $p)
}

# 同 check-release.ps1：不依赖 Get-FileHash，受限环境下也能算
function Get-Sha256([string]$path) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $fs = [IO.File]::OpenRead($path)
    try {
        return ([BitConverter]::ToString($sha.ComputeHash($fs)) -replace '-', '').ToLower()
    }
    finally {
        $fs.Dispose()
        $sha.Dispose()
    }
}

$src = Resolve-Local $Apk
if (-not (Test-Path $src)) { Write-Host "找不到安装包：$src" -ForegroundColor Red; exit 2 }

$relDir = Resolve-Local $Dir
if (-not (Test-Path $relDir)) { New-Item -ItemType Directory -Path $relDir | Out-Null }

# 版本号必须和构建脚本一致，否则清单说一套、装出来是另一套
$gradle = Join-Path $root "app\build.gradle.kts"
$buildVer = ""
if (Test-Path $gradle) {
    $txt = Get-Content $gradle -Raw -Encoding UTF8
    if ($txt -match 'versionName\s*=\s*"([^"]+)"') { $buildVer = $Matches[1] }
}
if ($buildVer -ne $Version) {
    Write-Host "版本号不一致：参数 $Version，build.gradle.kts $buildVer" -ForegroundColor Red
    exit 2
}

if ($Date -eq "") { $Date = (Get-Date).ToString("yyyy-MM-dd") }

$sha = (Get-Sha256 $src)
$digest = $sha.Substring(0, 8)
$name = "GIme-v$Version-$digest.apk"
$dst = Join-Path $relDir $name

# 同一份字节重复发布是幂等的：内容没变，文件名就没变
if ((Test-Path $dst) -and ((Get-Sha256 $dst) -eq $sha)) {
    Write-Host "安装包未变，跳过复制：$name" -ForegroundColor Yellow
}
else {
    Copy-Item $src $dst -Force
    Write-Host "已放入：release/$name（$([math]::Round((Get-Item $dst).Length/1KB))KB）"
}

# 旧版本的包不该留在目录里：留着只会在发布时被误传
Get-ChildItem $relDir -Filter "*.apk" | Where-Object { $_.Name -ne $name } | ForEach-Object {
    Write-Host "移除旧包：$($_.Name)" -ForegroundColor Yellow
    Remove-Item $_.FullName -Force
}

# 说明支持两种写法：一个文件（一行一条），或一串用 | 分隔。
# 用 [IO.File]::Exists 而不是 Test-Path：后者遇到 "|" 这类非法路径字符会直接抛异常。
$list = @()
if ($Notes -ne "") {
    if ([IO.File]::Exists($Notes)) {
        $list = Get-Content $Notes -Encoding UTF8 | Where-Object { $_.Trim() -ne "" } | ForEach-Object { $_.Trim() }
    }
    else {
        $list = $Notes.Split("|") | Where-Object { $_.Trim() -ne "" } | ForEach-Object { $_.Trim() }
    }
}

$man = [ordered]@{
    version = $Version
    apk     = $name
    date    = $Date
    notes   = @($list)
}
$json = $man | ConvertTo-Json -Depth 5
$manPath = Join-Path $relDir "latest.json"
[IO.File]::WriteAllText($manPath, $json + "`n", (New-Object Text.UTF8Encoding($false)))
Write-Host "已更新清单：release/latest.json（版本 $Version，$($list.Count) 条说明）"

Write-Host ""
Write-Host "SHA-256  $sha"
Write-Host "接下来：git add release && 提交，CDN 会在推上去之后按新路径拉取。"
