$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $root 'run'
foreach ($name in @('simulator', 'protocol', 'ops')) {
    $pidFile = Join-Path $runDir "$name.pid"
    if (Test-Path $pidFile) {
        $appPid = Get-Content $pidFile
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$appPid" -ErrorAction SilentlyContinue
        if ($proc) {
            $jarName = switch ($name) {
                'ops' { 'iot-protocol-expert-ops-1.0.0.jar' }
                'protocol' { 'iot-protocol-expert-protocol-1.0.0-exec.jar' }
                default { 'iot-protocol-expert-simulator-1.0.0.jar' }
            }
            if (!$proc.CommandLine -or !$proc.CommandLine.Contains($root) -or !$proc.CommandLine.Contains($jarName)) {
                throw "$name PID 文件指向其他进程（$appPid），未停止该进程"
            }
            Stop-Process -Id $appPid -Force
            Write-Host "$name 已停止（PID $appPid）"
        }
        Remove-Item $pidFile -Force
    } else {
        Write-Host "$name 未在运行"
    }
}