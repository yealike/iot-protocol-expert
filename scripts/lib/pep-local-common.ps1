. (Join-Path $PSScriptRoot "ipe-ai-common.ps1")

function Initialize-PepLocalEnvironment {
    param(
        [switch]$Quiet
    )

    $lib = $PSScriptRoot
    $root = Split-Path -Parent (Split-Path -Parent $lib)

    $envFile = Join-Path $root ".env.local"
    if (Test-Path -LiteralPath $envFile) {
        Import-IpeDotEnv -Path $envFile
    } elseif (-not $Quiet) {
        Write-Warning "未找到 .env.local；仅使用进程环境变量与本地默认值"
    }

    $env:JAVA_HOME = Get-IpeJavaHome
    $env:PATH = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:PATH

    $defaults = [ordered]@{
        PEP_CONTROL_PORT                    = "19083"
        IPE_OPS_PORT                        = "19082"
        PEP_MODEL_GATEWAY_PORT              = "19085"
        IPE_RUNTIME_PORT                    = "19084"
        IPE_OPS_BASE_URL                    = "http://127.0.0.1:19082"
        IPE_CONTROL_BASE_URL                = "http://127.0.0.1:19083"
        IPE_AGENT_RUNTIME_BASE_URL          = "http://127.0.0.1:19084"
        PEP_MODEL_GATEWAY_BASE_URL          = "http://127.0.0.1:19085"
        IPE_INTERNAL_SERVICE_NAME           = "iot-protocol-expert-agent-control"
        IPE_RUNTIME_SERVICE_NAME            = "iot-protocol-expert-agent-runtime"
        IPE_CONTROL_SERVICE_NAME            = "iot-protocol-expert-agent-control"
        IPE_INTERNAL_SERVICE_KEY            = "local-pep-hmac-secret"
        PEP_MODEL_GATEWAY_SERVICE_TOKEN     = "local-pep-service-token"
        IPE_OPS_SERVICE_TOKEN               = "local-pep-service-token"
        IPE_OPS_SIMULATOR_TOKEN             = "local-iot-protocol-expert-simulator-token"
        PEP_CREDENTIAL_MASTER_KEY           = "local-pep-master-key"
        IPE_AI_REGISTRY_ENABLED             = "false"
        IPE_NACOS_DISCOVERY_ENABLED         = "false"
        IPE_AI_MCP_REGISTRY_ENABLED         = "false"
        IPE_RUNTIME_DIRECT_FALLBACK_ENABLED = "true"
        IPE_AI_BOOTSTRAP_ENABLED            = "false"
        IPE_AI_OTEL_ENABLED                 = "false"
        IPE_OTLP_ENABLED                    = "false"
    }
    foreach ($entry in $defaults.GetEnumerator()) {
        if (-not [Environment]::GetEnvironmentVariable($entry.Key, "Process")) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
        }
    }

    # 仅迁移上一版内置的本机默认值，不修改外部自定义地址或秘密文件。
    if ($env:PEP_CONTROL_PORT -eq "19082" -and $env:IPE_OPS_PORT -eq "19082") {
        $env:PEP_CONTROL_PORT = "19083"
        if (-not $Quiet) { Write-Warning "OPS 与 Control 已拆分：Control 本机端口改为 19083" }
    }
    if ($env:IPE_CONTROL_BASE_URL -in @("http://127.0.0.1:19082", "http://localhost:19082")) {
        $env:IPE_CONTROL_BASE_URL = "http://127.0.0.1:$($env:PEP_CONTROL_PORT)"
    }
    foreach ($name in @("IPE_INTERNAL_SERVICE_NAME", "IPE_CONTROL_SERVICE_NAME")) {
        if ([Environment]::GetEnvironmentVariable($name, "Process") -eq "pep-control") {
            [Environment]::SetEnvironmentVariable($name, "iot-protocol-expert-agent-control", "Process")
        }
    }
    if ($env:IPE_RUNTIME_SERVICE_NAME -eq "pep-agent-runtime") {
        $env:IPE_RUNTIME_SERVICE_NAME = "iot-protocol-expert-agent-runtime"
    }
    if ($env:IPE_OPS_SERVICE_NAME -eq "pep-control") { $env:IPE_OPS_SERVICE_NAME = "iot-protocol-expert-ops" }

    return $root
}

function Get-PepServiceJar {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Module,
        [Parameter(Mandatory = $true)][string]$Jar
    )

    $path = Join-Path $Root (Join-Path $Module (Join-Path "target" $Jar))
    if (-not (Test-Path -LiteralPath $path)) {
        throw "缺少 $path，请先执行 mvn -DskipTests package"
    }
    return $path
}

function Start-PepJar {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Jar,
        [Parameter(Mandatory = $true)][int]$Port,
        [string]$HealthPath = "/actuator/health",
        [string]$BindAddress = "",
        [int]$TimeoutSeconds = 120,
        [string[]]$ExtraArgs = @()
    )

    if (-not $BindAddress) {
        $BindAddress = if ($env:IPE_NACOS_DISCOVERY_ENABLED -eq "true" -or $env:IPE_AI_REGISTRY_ENABLED -eq "true") {
            "0.0.0.0"
        } else {
            "127.0.0.1"
        }
    }

    $runDir = Join-Path $Root "run"
    $logDir = Join-Path $Root "logs"
    New-Item -ItemType Directory -Force -Path $runDir, $logDir | Out-Null
    $pidFile = Join-Path $runDir "$Name.pid"
    $jarName = Split-Path -Leaf $Jar
    $existing = Get-IpeOwnedProcess -PidFile $pidFile -JarName $jarName
    if ($existing) {
        throw "$Name 已在运行（PID $($existing.Id)）；如需加载新版本，请先运行 scripts/stop-local.ps1"
    }

    $arguments = @("-jar", ('"' + $Jar + '"'), "--spring.profiles.active=local", "--server.address=$BindAddress") + $ExtraArgs
    $out = Join-Path $logDir "$Name.out.log"
    $err = Join-Path $logDir "$Name.err.log"
    $process = Start-Process `
        -FilePath (Join-Path $env:JAVA_HOME "bin\java.exe") `
        -ArgumentList $arguments `
        -WorkingDirectory $Root `
        -WindowStyle Hidden `
        -PassThru `
        -RedirectStandardOutput $out `
        -RedirectStandardError $err
    $process.Id | Set-Content -LiteralPath $pidFile

    try {
        Wait-IpeHttpUp `
            -Uri ("http://127.0.0.1:{0}{1}" -f $Port, $HealthPath) `
            -TimeoutSeconds $TimeoutSeconds `
            -ProcessId $process.Id | Out-Null
    } catch {
        throw ("$Name 健康检查失败：{0}；日志：{1}，{2}" -f $_.Exception.Message, $out, $err)
    }
    Write-Host "$Name 已就绪：pid=$($process.Id), port=$Port"
    return $process
}

function Stop-PepJar {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$JarName
    )

    $pidFile = Join-Path (Join-Path $Root "run") "$Name.pid"
    $process = Get-IpeOwnedProcess -PidFile $pidFile -JarName $JarName
    if ($process) {
        Stop-Process -Id $process.Id -Force
        Write-Host "$Name 已停止（PID $($process.Id)）"
    } else {
        Write-Host "$Name 未在运行"
    }
    if (Test-Path -LiteralPath $pidFile) {
        Remove-Item -LiteralPath $pidFile -Force
    }
}

function Get-PepServiceDefinitions {
    return @(
        [pscustomobject]@{
            Name = "ops"
            Module = "iot-protocol-expert-ops"
            Jar = "iot-protocol-expert-ops-1.0.0.jar"
            Port = [int]$env:IPE_OPS_PORT
            HealthPath = "/actuator/health"
        },
        [pscustomobject]@{
            Name = "control"
            Module = "iot-protocol-expert-agent-control"
            Jar = "iot-protocol-expert-agent-control-1.0.0.jar"
            Port = [int]$env:PEP_CONTROL_PORT
            HealthPath = "/actuator/health"
        },
        [pscustomobject]@{
            Name = "gateway"
            Module = "iot-protocol-expert-model-gateway"
            Jar = "iot-protocol-expert-model-gateway-1.0.0.jar"
            Port = [int]$env:PEP_MODEL_GATEWAY_PORT
            HealthPath = "/actuator/health"
        },
        [pscustomobject]@{
            Name = "runtime"
            Module = "iot-protocol-expert-agent-runtime"
            Jar = "iot-protocol-expert-agent-runtime-1.0.0.jar"
            Port = [int]$env:IPE_RUNTIME_PORT
            HealthPath = "/actuator/health/readiness"
        },
        [pscustomobject]@{
            Name = "device-gateway"
            Module = "iot-protocol-expert-protocol-gateway"
            Jar = "iot-protocol-expert-protocol-gateway-1.0.0.jar"
            Port = 19080
            HealthPath = "/actuator/health"
        },
        [pscustomobject]@{
            Name = "simulator"
            Module = "iot-protocol-expert-simulator"
            Jar = "iot-protocol-expert-simulator-1.0.0.jar"
            Port = 19081
            HealthPath = "/actuator/health"
        }
    )
}
