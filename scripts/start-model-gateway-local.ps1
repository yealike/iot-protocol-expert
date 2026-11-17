$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "lib\pep-local-common.ps1")

$root = Initialize-PepLocalEnvironment
$jar = Get-PepServiceJar -Root $root -Module "iot-protocol-expert-model-gateway" -Jar "iot-protocol-expert-model-gateway-1.0.0.jar"
Start-PepJar `
    -Root $root `
    -Name "gateway" `
    -Jar $jar `
    -Port ([int]$env:PEP_MODEL_GATEWAY_PORT) `
    -HealthPath "/actuator/health" | Out-Null
