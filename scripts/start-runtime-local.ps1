$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
. (Join-Path $root "scripts\lib\ipe-ai-common.ps1")
Import-IpeDotEnv -Path (Join-Path $root ".env.local")

$env:JAVA_HOME = Get-IpeJavaHome
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

if (-not $env:IPE_CONTROL_BASE_URL) { $env:IPE_CONTROL_BASE_URL = "http://127.0.0.1:19083" }
if (-not $env:IPE_RUNTIME_SERVICE_NAME) { $env:IPE_RUNTIME_SERVICE_NAME = "runtime" }
if (-not $env:IPE_RUNTIME_KEY_ID) { $env:IPE_RUNTIME_KEY_ID = "runtime" }
if (-not $env:IPE_CONTROL_SERVICE_NAME) { $env:IPE_CONTROL_SERVICE_NAME = "control" }
if (-not $env:IPE_INTERNAL_SERVICE_KEY) { $env:IPE_INTERNAL_SERVICE_KEY = "local-service-token" }
if (-not $env:IPE_RUNTIME_PORT) { $env:IPE_RUNTIME_PORT = "19084" }
if (-not $env:IPE_AI_REGISTRY_ENABLED -and $env:IPE_NACOS_SERVER_ADDR) { $env:IPE_AI_REGISTRY_ENABLED = "true" }
if (-not $env:IPE_RUNTIME_ADVERTISE_IP -and $env:IPE_OPS_ADVERTISE_IP) {
    $env:IPE_RUNTIME_ADVERTISE_IP = $env:IPE_OPS_ADVERTISE_IP
}

New-Item -ItemType Directory -Force -Path (Join-Path $root "logs") | Out-Null

$jar = Join-Path $root "iot-protocol-expert-agent-runtime\target\iot-protocol-expert-agent-runtime-1.0.0.jar"
if (!(Test-Path -LiteralPath $jar)) { throw "缺少 $jar，请先执行 mvn package" }

$process = Start-Process `
    -FilePath (Join-Path $env:JAVA_HOME "bin\java.exe") `
    -ArgumentList @("-jar", $jar) `
    -WorkingDirectory $root `
    -WindowStyle Hidden `
    -PassThru `
    -RedirectStandardOutput (Join-Path $root "logs\runtime.out.log") `
    -RedirectStandardError (Join-Path $root "logs\runtime.err.log")
$process.Id | Set-Content -LiteralPath (Join-Path $root "logs\runtime.pid")

for ($attempt = 0; $attempt -lt 90; $attempt++) {
    if ($process.HasExited) { throw "runtime 启动失败，退出码 $($process.ExitCode)，请查看 logs\runtime.out.log" }
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$($env:IPE_RUNTIME_PORT)/actuator/health" -TimeoutSec 2
        if ($health.status -eq "UP") {
            Write-Output "runtime ready: pid=$($process.Id), port=$($env:IPE_RUNTIME_PORT)"
            return
        }
    } catch {
    }
    Start-Sleep -Seconds 1
}
throw "runtime 健康检查超时，请查看 logs\runtime.out.log"
