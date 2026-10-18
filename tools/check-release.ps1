# release/ 目录自检。
#
# 发布件靠两样东西保持一致：release/latest.json 声明版本号与安装包文件名，
# 同目录下真的躺着那个文件。任何一边动了另一边没跟上，用户点更新就会 404，
# 或者更糟——拿到一个和清单版本号不符的包。
#
# 另外钉住一条：安装包文件名必须带内容指纹。
# jsDelivr 按 URL 强缓存 7 天，同版本号重新构建时若路径不变，新包永远发不出去。
#
# Usage: pwsh -File tools/check-release.ps1 [-Dir release]

param(
    [string]$Dir = "release"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$relDir = if ([IO.Path]::IsPathRooted($Dir)) { $Dir } else { Join-Path $root $Dir }

$pass = 0
$fail = 0

function Ok([string]$name) {
    $script:pass++
    Write-Host "  [PASS] $name"
}

function Bad([string]$name, [string]$why) {
    $script:fail++
    Write-Host "  [FAIL] $name  ($why)"
}

# 不用 Get-FileHash：它来自 Microsoft.PowerShell.Utility，精简/受限环境下可能没有。
# 直接用 .NET 算，任何 PowerShell 版本都能跑。
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

if (-not (Test-Path $relDir)) {
    Bad "release 目录存在" $relDir
    Write-Host ""
    Write-Host "失败 1 项"
    exit 1
}

$manPath = Join-Path $relDir "latest.json"
if (-not (Test-Path $manPath)) {
    Bad "latest.json 存在" $manPath
    Write-Host ""
    Write-Host "失败 1 项"
    exit 1
}
Ok "latest.json 存在"

try {
    $man = Get-Content $manPath -Raw -Encoding UTF8 | ConvertFrom-Json
}
catch {
    Bad "latest.json 是合法 JSON" $_.Exception.Message
    Write-Host ""
    Write-Host "失败 $fail 项"
    exit 1
}
Ok "latest.json 是合法 JSON"

$ver = "$($man.version)".Trim()
if ($ver -match '^\d+\.\d+\.\d+$') { Ok "清单版本号格式正确（$ver）" }
else { Bad "清单版本号格式正确" "实际 '$ver'" }

$apk = "$($man.apk)".Trim()
if ($apk -ne "") { Ok "清单声明了安装包文件名" }
else { Bad "清单声明了安装包文件名" "apk 为空" }

# 文件名必须带内容指纹：GIme-v<版本>-<8位十六进制>.apk
$want = $null
$pat = '^GIme-v' + [regex]::Escape($ver) + '-([0-9a-f]{8})\.apk$'
if ($apk -match $pat) {
    $want = $Matches[1]
    Ok "安装包名带内容指纹（$want）"
}
else {
    Bad "安装包名带内容指纹" "期望形如 GIme-v$ver-xxxxxxxx.apk，实际 '$apk'"
}

$apkPath = Join-Path $relDir $apk
if (-not (Test-Path $apkPath)) {
    Bad "清单指向的安装包存在" $apkPath
}
else {
    Ok "清单指向的安装包存在"

    $len = (Get-Item $apkPath).Length
    if ($len -gt 100KB) { Ok "安装包大小合理（$([math]::Round($len/1KB))KB）" }
    else { Bad "安装包大小合理" "只有 $len 字节" }

    # 清单里写的指纹必须真的对应这个文件，否则等于没钉
    $sha = (Get-Sha256 $apkPath)
    if ($null -ne $want -and $sha.StartsWith($want)) {
        Ok "文件名指纹与实际内容一致（$want）"
    }
    else {
        Bad "文件名指纹与实际内容一致" "文件名写 $want，实际 SHA-256 前 8 位是 $($sha.Substring(0,8))"
    }

    # 版本号要和构建脚本一致，免得清单说 1.0.2、装出来是 1.0.1
    $gradle = Join-Path $root "app\build.gradle.kts"
    if (Test-Path $gradle) {
        $txt = Get-Content $gradle -Raw -Encoding UTF8
        if ($txt -match 'versionName\s*=\s*"([^"]+)"') {
            $buildVer = $Matches[1]
            if ($buildVer -eq $ver) { Ok "清单版本号与 build.gradle.kts 一致（$ver）" }
            else { Bad "清单版本号与 build.gradle.kts 一致" "清单 $ver，构建 $buildVer" }
        }
        else {
            Bad "build.gradle.kts 里有 versionName" "没匹配到"
        }
    }
    else {
        Bad "build.gradle.kts 存在" $gradle
    }
}

# 目录里不该留上一版的包：留着只会在发布时被误传，也让「哪个才是当前版」变模糊
$stray = Get-ChildItem $relDir -Filter "*.apk" | Where-Object { $_.Name -ne $apk }
if ($stray.Count -eq 0) { Ok "release 目录只有当前版本的安装包" }
else { Bad "release 目录只有当前版本的安装包" ("多余：" + (($stray | ForEach-Object { $_.Name }) -join ", ")) }

Write-Host ""
if ($fail -gt 0) {
    Write-Host "失败 $fail 项"
    exit 1
}
Write-Host "全部通过：$pass 项"
