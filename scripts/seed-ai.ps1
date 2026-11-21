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
  加载 .env.local，构建并启动 model-gateway、agent-runtime、control，
  以 IPE_AI_BOOTSTRAP_ENABLED=true 触发一次幂等引导（MCP/Skill/Agent 发布）。
  -Restart 会停止现有 control/runtime 后重新启动。
"@
    exit 0
}

. (Join-Path $PSScriptRoot "lib\pep-local-common.ps1")
$root = Initialize-PepLocalEnvironment
$mvn = Get-IpeMavenCommand

$env:IPE_AI_BOOTSTRAP_ENABLED = "true"
if (-not $env:IPE_MODEL_API_KEY) {
    Write-Warning "未配置 IPE_MODEL_API_KEY；引导只登记逻辑模型，不会写入真实上游密钥。"
}
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
if (-not $ops) { Start-PepJar -Root $root -Name "ops" -Jar $opsJar -Port ([int]$env:IPE_OPS_PORT) | Out-Null }
$gatewayJar = Get-PepServiceJar -Root $root -Module "iot-protocol-expert-model-gateway" -Jar "iot-protocol-expert-model-gateway-1.0.0.jar"
$runtimeJar = Get-PepServiceJar -Root $root -Module "iot-protocol-expert-agent-runtime" -Jar "iot-protocol-expert-agent-runtime-1.0.0.jar"
$controlJar = Get-PepServiceJar -Root $root -Module "iot-protocol-expert-agent-control" -Jar "iot-protocol-expert-agent-control-1.0.0.jar"

if ($Restart) {
    Stop-PepJar -Root $root -Name "control" -JarName "iot-protocol-expert-agent-control-1.0.0.jar"
    Stop-PepJar -Root $root -Name "runtime" -JarName "iot-protocol-expert-agent-runtime-1.0.0.jar"
}

$gateway = Get-IpeOwnedProcess -PidFile (Join-Path $runDir "gateway.pid") -JarName "iot-protocol-expert-model-gateway-1.0.0.jar"
if (-not $gateway) {
    Start-PepJar -Root $root -Name "gateway" -Jar $gatewayJar -Port ([int]$env:PEP_MODEL_GATEWAY_PORT) | Out-Null
} else {
    Write-Host "[seed-ai] 复用 model-gateway pid=$($gateway.Id)"
}

# Control 先以 bootstrap=false 启动，runtime readiness 通过后再重启触发引导。
$env:IPE_AI_BOOTSTRAP_ENABLED = "false"
$control = Get-IpeOwnedProcess -PidFile (Join-Path $runDir "control.pid") -JarName "iot-protocol-expert-agent-control-1.0.0.jar"
if (-not $control) {
    Start-PepJar -Root $root -Name "control" -Jar $controlJar -Port ([int]$env:PEP_CONTROL_PORT) | Out-Null
}

$runtime = Get-IpeOwnedProcess -PidFile (Join-Path $runDir "runtime.pid") -JarName "iot-protocol-expert-agent-runtime-1.0.0.jar"
if (-not $runtime) {
    Start-PepJar `
        -Root $root `
        -Name "runtime" `
        -Jar $runtimeJar `
        -Port ([int]$env:IPE_RUNTIME_PORT) `
        -HealthPath "/actuator/health/readiness" | Out-Null
}

Stop-PepJar -Root $root -Name "control" -JarName "iot-protocol-expert-agent-control-1.0.0.jar"
$env:IPE_AI_BOOTSTRAP_ENABLED = "true"
Start-PepJar `
    -Root $root `
    -Name "control" `
    -Jar $controlJar `
    -Port ([int]$env:PEP_CONTROL_PORT) `
    -HealthPath "/actuator/health/readiness" `
    -TimeoutSeconds $StartupTimeoutSeconds | Out-Null

$publicationSql = @'
select p.id as publication_id,
       p.state as publication_state,
       p.phase as publication_phase,
       p.error_code,
       p.error_message,
       p.version as agent_version,
       a.component_key as agent_key,
       r.manifest_digest
  from pep_agent.ai_publication p
  join pep_agent.ai_agent_component a on a.id = p.resource_id
  left join pep_agent.ai_agent_release r on r.agent_id = a.id and r.version = p.version
 where p.resource_type = 'AGENT'
 order by p.created_at desc
 limit 5
'@

Start-Sleep -Seconds 2
try {
    $rows = @(Invoke-IpeAiJdbc -Mode query -Sql $publicationSql)
    if ($rows.Count -eq 0) {
        Write-Warning "未读取到 Agent 发布记录；请查看 logs/control.out.log 中的 bootstrap 日志。"
    } else {
        $rows | Format-Table -AutoSize | Out-String | Write-Host
    }
} catch {
    Write-Warning "读取发布摘要失败：$($_.Exception.Message)"
}
Write-Host "[seed-ai] 完成；Control http://127.0.0.1:$($env:PEP_CONTROL_PORT)"
