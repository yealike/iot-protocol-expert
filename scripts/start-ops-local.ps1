# 兼容入口：原 OPS 进程已并入 pep-control。
$ErrorActionPreference = "Stop"
& (Join-Path $PSScriptRoot "start-control-local.ps1")
