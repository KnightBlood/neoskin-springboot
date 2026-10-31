# =============================================================================
# 把源项目预编译好的前端产物拷到 Java 部署的 <base>/public/
#
# 用法（在 NeoSkinSpringBoot 目录）：
#   pwsh scripts/prepare-public.ps1
#   pwsh scripts/prepare-public.ps1 -Source D:\path\to\blessing-skin\public -Target D:\deploy\public
#
# 说明：
#   * public/app 是源仓库的预编译产物（不重新打包，见 PORT-NOTES §8）；
#   * public/lang/<locale>.js 由后端 /lang/ 动态生成，部署时不需要；
#   * StaticAssets 只从 <base>/public 提供静态文件，所以这个拷贝是必需的，
#     否则首页 200 但白屏。
# =============================================================================
param(
    [string]$Source = (Join-Path (Split-Path $PSScriptRoot -Parent) "..\blessing-skin-server-Laravel13\public"),
    [string]$Target = (Join-Path (Split-Path $PSScriptRoot -Parent) "..\public")
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path (Join-Path $Source "app"))) {
    throw "找不到源前端产物：$Source\app（请确认 blessing-skin-server-Laravel13 在同级目录）"
}

New-Item -ItemType Directory -Path $Target -Force | Out-Null

$appSource = Join-Path $Source "app"
$appTarget = Join-Path $Target "app"
if (Test-Path $appTarget) {
    Remove-Item -Recurse -Force $appTarget
}
Copy-Item -Recurse -Force $appSource $appTarget
Write-Host "copied $appSource -> $appTarget"

$favicon = Join-Path $Source "favicon.ico"
if (Test-Path $favicon) {
    Copy-Item -Force $favicon (Join-Path $Target "favicon.ico")
    Write-Host "copied favicon.ico"
}

$count = (Get-ChildItem -Recurse -File $appTarget | Measure-Object).Count
Write-Host "done: $count files in $appTarget"
