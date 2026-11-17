$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "lib\pep-local-common.ps1")

$root = Initialize-PepLocalEnvironment
$jar = Get-PepServiceJar -Root $root -Module "pep-control" -Jar "pep-control-1.0.0.jar"
Start-PepJar `
    -Root $root `
    -Name "control" `
    -Jar $jar `
    -Port ([int]$env:PEP_CONTROL_PORT) `
    -HealthPath "/actuator/health" `
    -TimeoutSeconds 180 | Out-Null
