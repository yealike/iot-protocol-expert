$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "lib\pep-local-common.ps1")

$root = Initialize-PepLocalEnvironment
$jar = Get-PepServiceJar -Root $root -Module "pep-agent-runtime" -Jar "pep-agent-runtime-1.0.0.jar"
Start-PepJar `
    -Root $root `
    -Name "runtime" `
    -Jar $jar `
    -Port ([int]$env:IPE_RUNTIME_PORT) `
    -HealthPath "/actuator/health/readiness" | Out-Null
