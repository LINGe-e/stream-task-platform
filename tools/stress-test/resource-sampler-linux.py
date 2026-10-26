#!/usr/bin/env python3
"""按秒采集 Linux 宿主 CPU、内存与块设备 I/O。"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import time


WHOLE_DISK = re.compile(r"^(sd[a-z]+|vd[a-z]+|nvme\d+n\d+)$")


def cpu_times() -> tuple[int, int]:
    fields = Path("/proc/stat").read_text(encoding="utf-8").splitlines()[0].split()
    values = [int(value) for value in fields[1:]]
    idle = values[3] + (values[4] if len(values) > 4 else 0)
    return sum(values), idle


def memory() -> tuple[float, float]:
    values: dict[str, int] = {}
    for line in Path("/proc/meminfo").read_text(encoding="utf-8").splitlines():
        key, raw = line.split(":", 1)
        values[key] = int(raw.strip().split()[0])
    total_mb = values["MemTotal"] / 1024
    available_mb = values.get("MemAvailable", values.get("MemFree", 0)) / 1024
    return total_mb, total_mb - available_mb


def disk_bytes() -> tuple[int, int, int]:
    read_bytes = 0
    write_bytes = 0
    for line in Path("/proc/diskstats").read_text(encoding="utf-8").splitlines():
        fields = line.split()
        if len(fields) < 10 or not WHOLE_DISK.fullmatch(fields[2]):
            continue
        read_bytes += int(fields[5]) * 512
        write_bytes += int(fields[9]) * 512
    return read_bytes + write_bytes, read_bytes, write_bytes


def network_bytes() -> tuple[int, int]:
    receive_bytes = 0
    transmit_bytes = 0
    for line in Path("/proc/net/dev").read_text(encoding="utf-8").splitlines()[2:]:
        if ":" not in line:
            continue
        interface, raw = line.split(":", 1)
        fields = raw.split()
        if len(fields) >= 9:
            receive_bytes += int(fields[0])
            transmit_bytes += int(fields[8])
    return receive_bytes, transmit_bytes


def java_gc_totals() -> dict[str, float]:
    if shutil.which("pgrep") is None or shutil.which("jstat") is None:
        return {}
    pattern = (
        "kafka.Kafka|org.apache.flink.runtime.entrypoint."
        "StandaloneSessionClusterEntrypoint|org.apache.flink.runtime.taskexecutor."
        "TaskExecutor"
    )
    result = subprocess.run(
        ["pgrep", "-f", pattern],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    totals = {
        "gc_count": 0,
        "gc_time_ms": 0.0,
        "full_gc_count": 0,
        "full_gc_time_ms": 0.0,
    }
    for pid in result.stdout.split():
        gc = subprocess.run(
            ["jstat", "-gc", pid],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
        lines = [line.split() for line in gc.stdout.splitlines() if line.strip()]
        if gc.returncode != 0 or len(lines) < 2 or len(lines[-1]) < 7:
            continue
        values = lines[-1][-7:]
        try:
            young_count = int(values[0])
            young_time_ms = float(values[1]) * 1000
            full_count = int(values[2])
            full_time_ms = float(values[3]) * 1000
            concurrent_count = int(values[4])
            concurrent_time_ms = float(values[5]) * 1000
        except ValueError:
            continue
        totals["gc_count"] += young_count + full_count + concurrent_count
        totals["gc_time_ms"] += young_time_ms + full_time_ms + concurrent_time_ms
        totals["full_gc_count"] += full_count
        totals["full_gc_time_ms"] += full_time_ms
    return totals


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("-Output", "--output", required=True)
    parser.add_argument("-IntervalSeconds", "--interval-seconds", type=int, default=1)
    parser.add_argument("-DurationSeconds", "--duration-seconds", type=int, default=60)
    args = parser.parse_args()

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    previous_total, previous_idle = cpu_times()
    previous_disk, previous_read, previous_write = disk_bytes()
    previous_rx, previous_tx = network_bytes()
    previous_time = time.monotonic()
    deadline = previous_time + args.duration_seconds
    _, memory_used_mb = memory()
    total_memory_mb = memory_used_mb

    while time.monotonic() < deadline:
        time.sleep(args.interval_seconds)
        now = time.monotonic()
        elapsed = max(now - previous_time, 0.001)
        total, idle = cpu_times()
        disk, disk_read, disk_write = disk_bytes()
        receive_bytes, transmit_bytes = network_bytes()
        total_memory_mb, memory_used_mb = memory()
        sample = {
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "cpu_percent": round(
                100.0 * (1.0 - (idle - previous_idle) / max(total - previous_total, 1)),
                2,
            ),
            "memory_total_mb": round(total_memory_mb, 2),
            "memory_used_mb": round(memory_used_mb, 2),
            "memory_available_mb": round(total_memory_mb - memory_used_mb, 2),
            "disk_bytes_per_sec": round((disk - previous_disk) / elapsed, 2),
            "disk_read_bytes_per_sec": round((disk_read - previous_read) / elapsed, 2),
            "disk_write_bytes_per_sec": round((disk_write - previous_write) / elapsed, 2),
            "network_rx_bytes_per_sec": round(
                (receive_bytes - previous_rx) / elapsed, 2
            ),
            "network_tx_bytes_per_sec": round(
                (transmit_bytes - previous_tx) / elapsed, 2
            ),
            **java_gc_totals(),
        }
        with output.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(sample, ensure_ascii=False) + "\n")
        previous_total, previous_idle = total, idle
        previous_disk, previous_read, previous_write = disk, disk_read, disk_write
        previous_rx, previous_tx = receive_bytes, transmit_bytes
        previous_time = now
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
