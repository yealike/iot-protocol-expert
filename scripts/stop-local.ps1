$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "lib\pep-local-common.ps1")

$root = Initialize-PepLocalEnvironment -Quiet
foreach ($service in Get-PepServiceDefinitions) {
    Stop-PepJar -Root $root -Name $service.Name -JarName $service.Jar
}

# 清理旧版 PID 文件，避免重命名后的脚本误判。
foreach ($legacy in @("ops", "ai", "protocol")) {
    $pidFile = Join-Path (Join-Path $root "run") "$legacy.pid"
    if (Test-Path -LiteralPath $pidFile) {
        Remove-Item -LiteralPath $pidFile -Force
    }
}
