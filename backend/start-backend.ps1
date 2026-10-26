# 启动后端脚本 - 自动清理端口占用
param(
    [int]$Port = 8080,
    [string]$DbPassword = "123456"
)

Write-Host "检查端口 $Port 占用..." -ForegroundColor Cyan

# 查找占用端口的进程
$connections = netstat -ano | Select-String ":${Port}\s" | Select-String "LISTENING"
if ($connections) {
    $pids = $connections | ForEach-Object { ($_ -split '\s+')[-1] } | Sort-Object -Unique
    foreach ($pid in $pids) {
        if ($pid -and $pid -ne "0") {
            Write-Host "终止占用端口的进程 PID: $pid" -ForegroundColor Yellow
            taskkill /F /PID $pid 2>$null
        }
    }
    Start-Sleep -Seconds 1
}

Write-Host "启动后端 (端口: $Port)..." -ForegroundColor Green
$env:DB_PASSWORD = $DbPassword
cd $PSScriptRoot
mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-DDB_PASSWORD=$DbPassword -DSERVER_PORT=$Port"