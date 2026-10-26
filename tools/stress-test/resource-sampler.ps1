param(
    [Parameter(Mandatory = $true)]
    [string]$Output,

    [int]$IntervalSeconds = 1,

    [int]$DurationSeconds = 60
)

$ErrorActionPreference = "Stop"
$counterNames = @(
    "\Processor(_Total)\% Processor Time",
    "\Memory\Available MBytes",
    "\PhysicalDisk(_Total)\Disk Bytes/sec",
    "\PhysicalDisk(_Total)\Disk Read Bytes/sec",
    "\PhysicalDisk(_Total)\Disk Write Bytes/sec"
)
$totalMemoryMb = [math]::Round((Get-CimInstance Win32_OperatingSystem).TotalVisibleMemorySize / 1KB, 2)
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

while ($stopwatch.Elapsed.TotalSeconds -lt $DurationSeconds) {
    try {
        $samples = (Get-Counter -Counter $counterNames -ErrorAction Stop).CounterSamples
        function Get-CounterValue([object[]]$CounterSamples, [string]$Suffix) {
            $match = $CounterSamples |
                Where-Object { $_.Path.ToLowerInvariant().EndsWith($Suffix.ToLowerInvariant()) } |
                Select-Object -First 1
            if ($null -eq $match) {
                throw "未找到性能计数器：$Suffix"
            }
            return [double]$match.CookedValue
        }

        $cpuValue = Get-CounterValue $samples "processor(_total)\% processor time"
        $availableValue = Get-CounterValue $samples "memory\available mbytes"
        $diskValue = Get-CounterValue $samples "physicaldisk(_total)\disk bytes/sec"
        $readValue = Get-CounterValue $samples "physicaldisk(_total)\disk read bytes/sec"
        $writeValue = Get-CounterValue $samples "physicaldisk(_total)\disk write bytes/sec"
        $availableMemoryMb = [math]::Round($availableValue, 2)

        $record = [ordered]@{
            timestamp          = (Get-Date).ToString("o")
            cpu_percent        = [math]::Round($cpuValue, 2)
            memory_total_mb    = $totalMemoryMb
            memory_used_mb     = [math]::Round($totalMemoryMb - $availableMemoryMb, 2)
            memory_available_mb = $availableMemoryMb
            disk_bytes_per_sec = [math]::Round($diskValue, 2)
            disk_read_bytes_per_sec = [math]::Round($readValue, 2)
            disk_write_bytes_per_sec = [math]::Round($writeValue, 2)
        }
        $record | ConvertTo-Json -Compress | Add-Content -LiteralPath $Output -Encoding UTF8
    } catch {
        $record = [ordered]@{
            timestamp = (Get-Date).ToString("o")
            error     = $_.Exception.Message
        }
        $record | ConvertTo-Json -Compress | Add-Content -LiteralPath $Output -Encoding UTF8
    }
    Start-Sleep -Seconds $IntervalSeconds
}
