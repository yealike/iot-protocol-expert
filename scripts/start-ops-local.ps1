$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "lib\pep-local-common.ps1")
$root = Initialize-PepLocalEnvironment
$jar = Get-PepServiceJar -Root $root -Module "iot-protocol-expert-ops" -Jar "iot-protocol-expert-ops-1.0.0.jar"
Start-PepJar -Root $root -Name "ops" -Jar $jar -Port ([int]$env:IPE_OPS_PORT) -TimeoutSeconds 180 | Out-Null
