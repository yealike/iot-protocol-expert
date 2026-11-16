$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
. (Join-Path $root "scripts\lib\ipe-ai-common.ps1")
Import-IpeDotEnv -Path (Join-Path $root ".env.local")

$env:JAVA_HOME = Get-IpeJavaHome
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

if (-not $env:IPE_NACOS_DISCOVERY_ENABLED) { $env:IPE_NACOS_DISCOVERY_ENABLED = "true" }
if (-not $env:IPE_OPS_ADVERTISE_IP) { $env:IPE_OPS_ADVERTISE_IP = "127.0.0.1" }

New-Item -ItemType Directory -Force -Path (Join-Path $root "logs") | Out-Null

$jar = Join-Path $root "iot-protocol-expert-ops\target\iot-protocol-expert-ops-1.0.0.jar"
if (!(Test-Path -LiteralPath $jar)) { throw "缺少 $jar，请先执行 mvn package" }

$process = Start-Process `
    -FilePath (Join-Path $env:JAVA_HOME "bin\java.exe") `
    -ArgumentList @("-jar", $jar, "--spring.profiles.active=local", "--server.address=0.0.0.0") `
    -WorkingDirectory $root `
    -WindowStyle Hidden `
    -PassThru `
    -RedirectStandardOutput (Join-Path $root "logs\ops.out.log") `
    -RedirectStandardError (Join-Path $root "logs\ops.err.log")
$process.Id | Set-Content -LiteralPath (Join-Path $root "logs\ops.pid")

for ($attempt = 0; $attempt -lt 90; $attempt++) {
    if ($process.HasExited) { throw "ops 启动失败，退出码 $($process.ExitCode)，请查看 logs\ops.out.log" }
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:19082/actuator/health" -TimeoutSec 2
        if ($health.status -eq "UP") {
            Write-Output "ops ready: pid=$($process.Id)"
            return
        }
    } catch {
    }
    Start-Sleep -Seconds 1
}
throw "ops 健康检查超时，请查看 logs\ops.out.log"
