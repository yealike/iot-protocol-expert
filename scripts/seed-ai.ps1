param(
    [Alias("?")]
    [switch]$Help,
    [switch]$Restart,
    [switch]$SkipBuild,
    [int]$StartupTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
if ($Help) {
    Write-Host @"
用法:
  .\scripts\seed-ai.ps1 [-Restart] [-SkipBuild] [-StartupTimeoutSeconds 180]

说明:
  加载 .env.local，构建并启动 OPS、Model Gateway、Control 和 Runtime。
  -Restart 会停止现有 control/runtime 后重新启动。
"@
    exit 0
}

. (Join-Path $PSScriptRoot "lib\pep-local-common.ps1")
$root = Initialize-PepLocalEnvironment
$mvn = Get-IpeMavenCommand

if (-not $env:IPE_OPS_DB_URL -and $env:PEP_CONTROL_DB_URL) {
    $env:IPE_OPS_DB_URL = $env:PEP_CONTROL_DB_URL
}

if (-not $SkipBuild) {
    Write-Host "[seed-ai] 构建 control / model-gateway / agent-runtime"
    & $mvn -B -pl iot-protocol-expert-ops,iot-protocol-expert-agent-control,iot-protocol-expert-model-gateway,iot-protocol-expert-agent-runtime -am package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        throw "Maven 构建失败，退出码 $LASTEXITCODE"
    }
}

$runDir = Join-Path $root "run"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$opsJar = Get-PepServiceJar -Root $root -Module "iot-protocol-expert-ops" -Jar "iot-protocol-expert-ops-1.0.0.jar"
$ops = Get-IpeOwnedProcess -PidFile (Join-Path $runDir "ops.pid") -JarName "iot-protocol-expert-ops-1.0.0.jar"
if (-not $ops) { Start-PepJar -Root $root -Name "ops" -Jar $opsJar -Port ([int]$env:IPE_OPS_PORT) -TimeoutSeconds $StartupTimeoutSeconds | Out-Null }
$gatewayJar = Get-PepServiceJar -Root $root -Module "iot-protocol-expert-model-gateway" -Jar "iot-protocol-expert-model-gateway-1.0.0.jar"
$runtimeJar = Get-PepServiceJar -Root $root -Module "iot-protocol-expert-agent-runtime" -Jar "iot-protocol-expert-agent-runtime-1.0.0.jar"
$controlJar = Get-PepServiceJar -Root $root -Module "iot-protocol-expert-agent-control" -Jar "iot-protocol-expert-agent-control-1.0.0.jar"

if ($Restart) {
    Stop-PepJar -Root $root -Name "control" -JarName "iot-protocol-expert-agent-control-1.0.0.jar"
    Stop-PepJar -Root $root -Name "runtime" -JarName "iot-protocol-expert-agent-runtime-1.0.0.jar"
}

$gateway = Get-IpeOwnedProcess -PidFile (Join-Path $runDir "gateway.pid") -JarName "iot-protocol-expert-model-gateway-1.0.0.jar"
if (-not $gateway) {
    Start-PepJar -Root $root -Name "gateway" -Jar $gatewayJar -Port ([int]$env:PEP_MODEL_GATEWAY_PORT) -TimeoutSeconds $StartupTimeoutSeconds | Out-Null
} else {
    Write-Host "[seed-ai] 复用 model-gateway pid=$($gateway.Id)"
}

$control = Get-IpeOwnedProcess -PidFile (Join-Path $runDir "control.pid") -JarName "iot-protocol-expert-agent-control-1.0.0.jar"
if (-not $control) {
    Start-PepJar -Root $root -Name "control" -Jar $controlJar -Port ([int]$env:PEP_CONTROL_PORT) -TimeoutSeconds $StartupTimeoutSeconds | Out-Null
}

$runtime = Get-IpeOwnedProcess -PidFile (Join-Path $runDir "runtime.pid") -JarName "iot-protocol-expert-agent-runtime-1.0.0.jar"
if (-not $runtime) {
    Start-PepJar `
        -Root $root `
        -Name "runtime" `
        -Jar $runtimeJar `
        -Port ([int]$env:IPE_RUNTIME_PORT) `
        -HealthPath "/actuator/health/readiness" `
        -TimeoutSeconds $StartupTimeoutSeconds | Out-Null
}

Write-Host "[seed-ai] 服务已启动。Control http://127.0.0.1:$($env:PEP_CONTROL_PORT)"
