# ============================================================
#  do压缩包 V5  PowerShell 版启动器 / PowerShell launcher
#  用法 / Usage:
#    解压  .\do.ps1 x  "D:\资料"  [-Pwd 123] [-Delete] [-Lang en]
#    压缩  .\do.ps1 c  "D:\a.txt" "D:\b" -Out D:\out.7z -Fmt 7z -Pwd 123 -Vol 100m
#    菜单  .\do.ps1
#  若提示禁止运行脚本 / if execution policy blocks:
#    powershell -ExecutionPolicy Bypass -File .\do.ps1
# ============================================================
[CmdletBinding()]
param(
    [ValidateSet('x','c','menu')]
    [string]$Mode = 'menu',
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$Targets,
    [string]$Out,
    [ValidateSet('7z','zip','tar')]
    [string]$Fmt = '7z',
    [string]$Pwd,
    [string]$Vol,
    [switch]$Delete,
    [ValidateSet('zh','en')]
    [string]$Lang = 'zh'
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$py = if (Get-Command py -ErrorAction SilentlyContinue) { 'py' } else { 'python' }

$argList = @("$scriptDir\do_extract.py")

if ($Mode -eq 'menu') {
    & $py @argList
    exit
}

$argList += $Mode
if ($Targets) { $argList += $Targets }
if ($Pwd)    { $argList += '--pwd'; $Pwd }
if ($Delete) { $argList += '--del' }
$argList += '--lang'; $Lang
if ($Mode -eq 'c') {
    if ($Out) { $argList += '-o'; $Out }
    $argList += '-f'; $Fmt
    if ($Vol) { $argList += '-v'; $Vol }
}

& $py @argList
