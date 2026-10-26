#!/usr/bin/env python3
"""运行单组 Kafka-Flink 压测并保存原始指标。"""

from __future__ import annotations

import argparse
import base64
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
from typing import Any

import requests


ROOT = Path(__file__).resolve().parents[2]
FLINK_REST = "http://127.0.0.1:8081"
KAFKA_BOOTSTRAP = "127.0.0.1:9092"
KAFKA_TOPICS = Path(r"D:\kafka\bin\windows\kafka-topics.bat")
KAFKA_GROUPS = Path(r"D:\kafka\bin\windows\kafka-consumer-groups.bat")
KAFKA_GET_OFFSETS = Path(r"D:\kafka\bin\windows\kafka-get-offsets.bat")
JAVA = Path(r"D:\JAVA\jdk-17\bin\java.exe")
KAFKA_LIBS = Path(r"D:\kafka\libs")
BENCH_CLASSES = ROOT / "tools" / "stress-test" / "classes"
RESOURCE_SAMPLER = ROOT / "tools" / "stress-test" / "resource-sampler.ps1"
JOB_JAR = ROOT / "flink-jobs" / "target" / "flink-jobs-1.0-SNAPSHOT-shaded.jar"
ENTRY_CLASS = "com.a22.flink.DynamicPipelineJob"
PARTITIONS = 4
SAMPLE_INTERVAL_SECONDS = 1.5


def now_iso() -> str:
    from datetime import datetime, timezone

    return datetime.now(timezone.utc).astimezone().isoformat(timespec="milliseconds")


def run_cmd(command: list[str], timeout: int = 120) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        check=False,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )


def flink_get(path: str, params: dict[str, Any] | None = None) -> Any:
    response = requests.get(f"{FLINK_REST}{path}", params=params, timeout=20)
    response.raise_for_status()
    if not response.content:
        return None
    return response.json()


def flink_post(path: str, payload: Any | None = None, params: dict[str, Any] | None = None) -> Any:
    response = requests.post(f"{FLINK_REST}{path}", json=payload, params=params, timeout=30)
    if not response.ok:
        raise RuntimeError(f"Flink REST {response.status_code}：{response.text}")
    response.raise_for_status()
    if not response.content:
        return None
    return response.json()


def create_topic(topic: str) -> None:
    command = [
        "cmd.exe",
        "/d",
        "/c",
        str(KAFKA_TOPICS),
        "--bootstrap-server",
        KAFKA_BOOTSTRAP,
        "--create",
        "--if-not-exists",
        "--topic",
        topic,
        "--partitions",
        str(PARTITIONS),
        "--replication-factor",
        "1",
    ]
    result = run_cmd(command, timeout=60)
    if result.returncode != 0:
        raise RuntimeError(f"创建 Kafka Topic 失败：{topic}\n{result.stdout}\n{result.stderr}")


def upload_jar() -> str:
    if not JOB_JAR.exists():
        raise FileNotFoundError(f"压测 JAR 不存在：{JOB_JAR}")
    with JOB_JAR.open("rb") as jar_file:
        response = requests.post(
            f"{FLINK_REST}/jars/upload",
            files={"file": (JOB_JAR.name, jar_file, "application/java-archive")},
            timeout=120,
        )
    response.raise_for_status()
    uploaded = response.json()
    if not response.ok or uploaded.get("status") != "success":
        raise RuntimeError(f"上传 Flink JAR 失败：{uploaded}")
    jar_listing = flink_get("/jars")
    matching = [
        item
        for item in jar_listing.get("files", [])
        if item.get("name") == JOB_JAR.name
    ]
    if not matching:
        raise RuntimeError("Flink JAR 上传成功，但未在 /jars 列表中找到对应条目")
    latest = max(matching, key=lambda item: int(item.get("uploaded", 0)))
    return str(latest["id"])


def submit_job(
    jar_id: str,
    case_id: str,
    job_index: int,
    parallelism: int,
    input_topic: str,
    output_topic: str,
    group_id: str,
) -> str:
    config = {
        "jobName": f"{case_id}-job-{job_index}",
        "source": {
            "type": "kafka",
            "bootstrapServers": KAFKA_BOOTSTRAP,
            "topic": input_topic,
            "groupId": group_id,
        },
        "transforms": [],
        "sink": {
            "type": "kafka",
            "bootstrapServers": KAFKA_BOOTSTRAP,
            "topic": output_topic,
        },
    }
    config_base64 = base64.b64encode(
        json.dumps(config, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    ).decode("ascii")
    payload = {
        "entryClass": ENTRY_CLASS,
        "parallelism": parallelism,
        "programArgs": f"--config {config_base64}",
    }
    submitted = flink_post(f"/jars/{jar_id}/run", payload)
    job_id = str(submitted["jobid"])
    deadline = time.monotonic() + 30
    last_state = "UNKNOWN"
    while time.monotonic() < deadline:
        job = flink_get(f"/jobs/{job_id}")
        last_state = str(job.get("state") or job.get("status") or "UNKNOWN")
        if last_state == "RUNNING":
            return job_id
        if last_state in {"FAILED", "CANCELED", "FINISHED"}:
            break
        time.sleep(0.5)
    raise RuntimeError(f"Flink 作业未进入 RUNNING：job={job_id}, state={last_state}")


def cancel_job(job_id: str) -> None:
    try:
        requests.patch(f"{FLINK_REST}/jobs/{job_id}", params={"mode": "cancel"}, timeout=20).raise_for_status()
    except Exception:
        pass


def safe_get(path: str, params: dict[str, Any] | None = None) -> Any:
    try:
        return flink_get(path, params)
    except Exception as exception:
        return {"error": str(exception)}


def extract_job_metrics(job: dict[str, Any]) -> dict[str, Any]:
    job_id = str(job.get("jid") or job.get("jobid") or "")
    if not job_id:
        return {"error": "作业详情缺少 jid"}
    metric_queries = [
        "numRestarts",
        "numRecordsIn",
        "numRecordsOut",
        "lastCheckpointDuration",
        "numberOfCompletedCheckpoints",
        "numberOfFailedCheckpoints",
    ]
    metrics: dict[str, Any] = {}
    source_values: list[Any] = []
    sink_values: list[Any] = []
    available = safe_get(f"/jobs/{job_id}/metrics")
    if isinstance(available, list):
        available_names = {
            item.get("id") or item.get("name")
            for item in available
        }
        for metric_name in metric_queries:
            if metric_name in available_names:
                values = safe_get(
                    f"/jobs/{job_id}/metrics", {"get": metric_name}
                )
                if isinstance(values, list) and values:
                    metrics[metric_name] = values[0].get("value")

    records_in = 0
    records_out = 0
    source_vertices = []
    sink_vertices = []
    for vertex in job.get("vertices", []):
        vertex_name = str(vertex.get("name", ""))
        vertex_id = str(vertex.get("id", ""))
        if vertex_id:
            vertex_metric_ids = safe_get(f"/jobs/{job_id}/vertices/{vertex_id}/metrics")
            if isinstance(vertex_metric_ids, list):
                for metric_descriptor in vertex_metric_ids:
                    metric_id = str(
                        metric_descriptor.get("id")
                        or metric_descriptor.get("name")
                        or ""
                    )
                    if metric_id.endswith(".Source__Kafka_Source.numRecordsIn"):
                        values = safe_get(
                            f"/jobs/{job_id}/vertices/{vertex_id}/metrics",
                            {"get": metric_id},
                        )
                        if isinstance(values, list) and values:
                            source_values.append(values[0].get("value"))
                    elif metric_id.endswith(".Kafka_Sink__Writer.numRecordsIn"):
                        values = safe_get(
                            f"/jobs/{job_id}/vertices/{vertex_id}/metrics",
                            {"get": metric_id},
                        )
                        if isinstance(values, list) and values:
                            sink_values.append(values[0].get("value"))
        raw_vertex_metrics = vertex.get("metrics", [])
        if isinstance(raw_vertex_metrics, dict):
            vertex_metrics = raw_vertex_metrics
        else:
            vertex_metrics = {
                item.get("name"): item.get("value")
                for item in raw_vertex_metrics
                if isinstance(item, dict)
            }
        if "Kafka Source" in vertex_name:
            source_vertices.append(
                {
                    "id": vertex.get("id"),
                    "name": vertex_name,
                    "metrics": vertex_metrics,
                    "status": vertex.get("status"),
                }
            )
            try:
                records_in += int(vertex_metrics.get("read-records", 0) or 0)
            except (TypeError, ValueError):
                pass
        if "Kafka Sink" in vertex_name:
            sink_vertices.append(
                {
                    "id": vertex.get("id"),
                    "name": vertex_name,
                    "metrics": vertex_metrics,
                    "status": vertex.get("status"),
                }
            )
            try:
                records_out += int(vertex_metrics.get("write-records", 0) or 0)
            except (TypeError, ValueError):
                pass
    metrics["source_records_in"] = sum(
        int(float(value))
        for value in source_values
        if value is not None
    ) if source_values else records_in
    metrics["sink_records_out"] = sum(
        int(float(value))
        for value in sink_values
        if value is not None
    ) if sink_values else records_out
    metrics["source_metric_values"] = source_values
    metrics["sink_metric_values"] = sink_values
    metrics["source_vertices"] = source_vertices
    metrics["sink_vertices"] = sink_vertices
    return metrics


def summarize_checkpoints(checkpoints: Any) -> dict[str, Any]:
    if not isinstance(checkpoints, dict):
        return {"error": checkpoints}
    counts = checkpoints.get("counts", {})
    history = checkpoints.get("history", [])
    durations = []
    state_sizes = []
    for item in history:
        if item.get("status") == "COMPLETED":
            duration = item.get("end_to_end_duration", item.get("duration"))
            state_size = item.get("state_size")
            if isinstance(duration, (int, float)):
                durations.append(float(duration))
            if isinstance(state_size, (int, float)):
                state_sizes.append(float(state_size))
    durations.sort()
    state_sizes.sort()

    def percentile(values: list[float], quantile: float) -> float | None:
        if not values:
            return None
        position = quantile * (len(values) - 1)
        lower = math.floor(position)
        upper = math.ceil(position)
        if lower == upper:
            return values[lower]
        weight = position - lower
        return values[lower] * (1 - weight) + values[upper] * weight

    return {
        "counts": counts,
        "completed": int(counts.get("completed", 0) or 0),
        "failed": int(counts.get("failed", 0) or 0),
        "duration_ms": {
            "samples": len(durations),
            "avg": round(sum(durations) / len(durations), 2) if durations else None,
            "p95": round(percentile(durations, 0.95), 2) if durations else None,
            "max": round(max(durations), 2) if durations else None,
        },
        "state_size_bytes": {
            "avg": round(sum(state_sizes) / len(state_sizes), 2) if state_sizes else None,
            "max": round(max(state_sizes), 2) if state_sizes else None,
        },
        "latest": checkpoints.get("summary", {}).get("latest", {}),
    }


def wait_for_checkpoint(job_ids: list[str], timeout_seconds: int) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        completed_counts = []
        all_reached = True
        for job_id in job_ids:
            checkpoints = safe_get(f"/jobs/{job_id}/checkpoints")
            completed = 0
            if isinstance(checkpoints, dict):
                completed = int(checkpoints.get("counts", {}).get("completed", 0) or 0)
            completed_counts.append(completed)
            if completed < 1:
                all_reached = False
        if all_reached:
            return
        time.sleep(1)


def read_resource_samples(path: Path) -> dict[str, Any]:
    samples = []
    if path.exists():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            if not line.strip():
                continue
            try:
                sample = json.loads(line)
            except json.JSONDecodeError:
                continue
            if "error" not in sample:
                samples.append(sample)
    if not samples:
        return {"samples": 0}

    def stats(key: str) -> dict[str, float]:
        values = [float(item[key]) for item in samples if key in item]
        if not values:
            return {"avg": None, "max": None}
        return {"avg": round(sum(values) / len(values), 2), "max": round(max(values), 2)}

    disk_stats = stats("disk_bytes_per_sec")
    return {
        "samples": len(samples),
        "cpu_percent": stats("cpu_percent"),
        "memory_used_mb": stats("memory_used_mb"),
        "disk_bytes_per_sec": disk_stats,
        "disk_mb_per_sec": {
            "avg": round(float(disk_stats["avg"]) / 1024 / 1024, 2)
            if disk_stats["avg"] is not None
            else None,
            "max": round(float(disk_stats["max"]) / 1024 / 1024, 2)
            if disk_stats["max"] is not None
            else None,
        },
        "disk_read_mb_per_sec": scale_counter(stats("disk_read_bytes_per_sec")),
        "disk_write_mb_per_sec": scale_counter(stats("disk_write_bytes_per_sec")),
        "raw_file": str(path),
    }


def scale_counter(stats: dict[str, float | None]) -> dict[str, float | None]:
    return {
        "avg": round(float(stats["avg"]) / 1024 / 1024, 2) if stats["avg"] is not None else None,
        "max": round(float(stats["max"]) / 1024 / 1024, 2) if stats["max"] is not None else None,
    }


def group_lag(group_id: str, input_topic: str) -> int | None:
    command = [
        "cmd.exe",
        "/d",
        "/c",
        str(KAFKA_GROUPS),
        "--bootstrap-server",
        KAFKA_BOOTSTRAP,
        "--describe",
        "--group",
        group_id,
    ]
    result = run_cmd(command, timeout=45)
    if result.returncode != 0:
        return topic_end_offset(input_topic)
    total_lag = 0
    found = False
    for line in (result.stdout + "\n" + result.stderr).splitlines():
        parts = line.split()
        if len(parts) < 6 or parts[1] != input_topic:
            continue
        try:
            partition = int(parts[2])
            lag = int(parts[5])
        except ValueError:
            continue
        if partition >= 0 and lag >= 0:
            total_lag += lag
            found = True
    return total_lag if found else topic_end_offset(input_topic)


def topic_end_offset(topic: str) -> int | None:
    command = [
        "cmd.exe",
        "/d",
        "/c",
        str(KAFKA_GET_OFFSETS),
        "--bootstrap-server",
        KAFKA_BOOTSTRAP,
        "--topic",
        topic,
        "--time",
        "-1",
    ]
    result = run_cmd(command, timeout=45)
    if result.returncode != 0:
        return None
    total = 0
    found = False
    for line in (result.stdout + "\n" + result.stderr).splitlines():
        if ":" not in line:
            continue
        value = line.rsplit(":", 1)[-1].strip()
        if value == "-1":
            continue
        try:
            total += int(value)
            found = True
        except ValueError:
            continue
    return total if found else None


class LagSampler(threading.Thread):
    def __init__(self, group_ids: list[str], input_topic: str, output_file: Path):
        super().__init__(daemon=True)
        self.group_ids = group_ids
        self.input_topic = input_topic
        self.output_file = output_file
        self.stop_event = threading.Event()
        self.samples: list[dict[str, Any]] = []

    def run(self) -> None:
        while not self.stop_event.is_set():
            timestamp = now_iso()
            for group_id in self.group_ids:
                lag = group_lag(group_id, self.input_topic)
                sample = {"timestamp": timestamp, "group": group_id, "lag": lag}
                self.samples.append(sample)
                with self.output_file.open("a", encoding="utf-8") as output:
                    output.write(json.dumps(sample, ensure_ascii=False) + "\n")
            self.stop_event.wait(SAMPLE_INTERVAL_SECONDS)

    def stop(self) -> None:
        self.stop_event.set()


def summarize_lag(samples: list[dict[str, Any]], group_ids: list[str]) -> dict[str, Any]:
    numeric = [int(item["lag"]) for item in samples if isinstance(item.get("lag"), int)]
    totals_by_timestamp: dict[str, int] = {}
    for item in samples:
        if not isinstance(item.get("lag"), int):
            continue
        timestamp = str(item.get("timestamp", ""))
        totals_by_timestamp[timestamp] = totals_by_timestamp.get(timestamp, 0) + int(item["lag"])
    totals = list(totals_by_timestamp.values())
    by_group: dict[str, Any] = {}
    for group_id in group_ids:
        values = [
            int(item["lag"])
            for item in samples
            if item.get("group") == group_id and isinstance(item.get("lag"), int)
        ]
        by_group[group_id] = {
            "samples": len(values),
            "peak": max(values) if values else None,
            "last": values[-1] if values else None,
        }
    return {
        "samples": len(numeric),
        "peak_records": max(totals) if totals else None,
        "last_records": totals[-1] if totals else None,
        "by_group": by_group,
    }


def wait_for_lags_settled(
    group_ids: list[str],
    input_topic: str,
    timeout_seconds: int,
) -> dict[str, int | None]:
    deadline = time.monotonic() + timeout_seconds
    latest: dict[str, int | None] = {}
    while time.monotonic() < deadline:
        latest = {
            group_id: group_lag(group_id, input_topic)
            for group_id in group_ids
        }
        if latest and all(value == 0 for value in latest.values()):
            return latest
        time.sleep(1)
    return latest


def run_bench(
    case_id: str,
    input_topic: str,
    output_topics: list[str],
    count: int,
    rate: float,
    drain_timeout_ms: int,
    result_path: Path,
) -> dict[str, Any]:
    classpath = os.pathsep.join([str(BENCH_CLASSES), str(KAFKA_LIBS / "*")])
    command = [
        str(JAVA),
        "-cp",
        classpath,
        "KafkaEndToEndBench",
        "--bootstrap",
        KAFKA_BOOTSTRAP,
        "--input-topic",
        input_topic,
        "--output-topics",
        ",".join(output_topics),
        "--case-id",
        case_id,
        "--count",
        str(count),
        "--rate",
        str(rate),
        "--partitions",
        str(PARTITIONS),
        "--drain-timeout-ms",
        str(drain_timeout_ms),
        "--result",
        str(result_path),
    ]
    result = run_cmd(command, timeout=max(120, int(count / max(rate, 1)) + drain_timeout_ms // 1000 + 60))
    if result.returncode != 0 or not result_path.exists():
        raise RuntimeError(f"端到端驱动器失败：\n{result.stdout}\n{result.stderr}")
    return json.loads(result_path.read_text(encoding="utf-8"))


def run_case(args: argparse.Namespace) -> dict[str, Any]:
    case_id = args.case_id
    input_topic = f"stress-in-{case_id}"
    output_topics = [f"stress-out-{case_id}-{index}" for index in range(args.job_count)]
    group_ids = [f"stress-group-{case_id}-{index}" for index in range(args.job_count)]
    create_topic(input_topic)
    for output_topic in output_topics:
        create_topic(output_topic)

    overview_before = flink_get("/overview")
    jar_id = upload_jar()
    job_ids: list[str] = []
    try:
        for index, (output_topic, group_id) in enumerate(zip(output_topics, group_ids)):
            job_ids.append(
                submit_job(
                    jar_id,
                    case_id,
                    index,
                    args.parallelism,
                    input_topic,
                    output_topic,
                    group_id,
                )
            )

        resource_path = ROOT / "outputs" / f"resource-{case_id}.jsonl"
        estimated_seconds = int(count_seconds(args.count, args.rate) + args.drain_timeout_ms / 1000 + 20)
        resource_process = subprocess.Popen(
            [
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(RESOURCE_SAMPLER),
                "-Output",
                str(resource_path),
                "-IntervalSeconds",
                "1",
                "-DurationSeconds",
                str(estimated_seconds),
            ],
            cwd=ROOT,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        lag_path = ROOT / "outputs" / f"backlog-{case_id}.jsonl"
        lag_sampler = LagSampler(group_ids, input_topic, lag_path)
        lag_sampler.start()
        time.sleep(1)

        started_at = now_iso()
        e2e_result = run_bench(
            case_id,
            input_topic,
            output_topics,
            args.count,
            args.rate,
            args.drain_timeout_ms,
            ROOT / "outputs" / f"e2e-{case_id}.json",
        )
        ended_at = now_iso()
        wait_for_checkpoint(job_ids, args.checkpoint_wait_seconds)
        time.sleep(2)
        wait_for_lags_settled(group_ids, input_topic, 12)
        lag_sampler.stop()
        lag_sampler.join(timeout=120)
        final_lags = {group_id: group_lag(group_id, input_topic) for group_id in group_ids}
        resource_process.wait(timeout=estimated_seconds + 30)
        cluster_during = flink_get("/overview")
        for job_id in job_ids:
            cancel_job(job_id)
        cancel_deadline = time.monotonic() + 20
        while time.monotonic() < cancel_deadline:
            states = [
                flink_get(f"/jobs/{job_id}").get("state")
                for job_id in job_ids
            ]
            if all(state == "CANCELED" for state in states):
                break
            time.sleep(0.5)
        time.sleep(2)

        jobs = {}
        failed_job_count = 0
        for index, job_id in enumerate(job_ids):
            details = flink_get(f"/jobs/{job_id}")
            checkpoints = safe_get(f"/jobs/{job_id}/checkpoints")
            exceptions = safe_get(f"/jobs/{job_id}/exceptions", {"maxExceptions": 10})
            exception_history = (
                exceptions.get("exceptionHistory", {})
                if isinstance(exceptions, dict)
                else {}
            )
            exception_entries = exception_history.get("entries", [])
            status = details.get("state") or details.get("status")
            if status == "FAILED" or exception_entries:
                failed_job_count += 1
            jobs[str(index)] = {
                "job_id": job_id,
                "group_id": group_ids[index],
                "output_topic": output_topics[index],
                "status": status,
                "parallelism": details.get("parallelism")
                or max(
                    (
                        int(vertex.get("parallelism", 0) or 0)
                        for vertex in details.get("vertices", [])
                    ),
                    default=args.parallelism,
                ),
                "metrics": extract_job_metrics(details),
                "checkpoints": summarize_checkpoints(checkpoints),
                "exceptions": exceptions,
            }

        taskmanagers = flink_get("/taskmanagers")
        result = {
            "schema_version": 1,
            "suite": args.suite,
            "case_id": case_id,
            "created_at": now_iso(),
            "started_at": started_at,
            "ended_at": ended_at,
            "parameters": {
                "job_count": args.job_count,
                "parallelism_per_job": args.parallelism,
                "message_count": args.count,
                "offered_rate_rps": args.rate,
                "drain_timeout_ms": args.drain_timeout_ms,
                "partitions": PARTITIONS,
            },
            "cluster_before": overview_before,
            "cluster_during": cluster_during,
            "cluster_after": flink_get("/overview"),
            "taskmanagers": taskmanagers,
            "input_topic": input_topic,
            "output_topics": output_topics,
            "group_ids": group_ids,
            "job_ids": job_ids,
            "e2e": e2e_result,
            "backlog": {
                **summarize_lag(lag_sampler.samples, group_ids),
                "final_by_group": final_lags,
                "raw_file": str(lag_path),
            },
            "resources": read_resource_samples(resource_path),
            "jobs": jobs,
            "job_summary": {
                "total": len(job_ids),
                "failed": failed_job_count,
                "failure_rate": failed_job_count / len(job_ids) if job_ids else 0,
            },
        }
        return result
    finally:
        for job_id in job_ids:
            cancel_job(job_id)


def count_seconds(count: int, rate: float) -> float:
    return count / rate if rate > 0 else 1


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="运行 Kafka-Flink 端到端压力测试")
    parser.add_argument("--suite", required=True, help="实验套件名称")
    parser.add_argument("--case-id", required=True, help="唯一实验编号")
    parser.add_argument("--job-count", type=int, default=1, help="并发 Flink 作业数")
    parser.add_argument("--parallelism", type=int, default=1, help="单作业并行度")
    parser.add_argument("--count", type=int, default=60000, help="输入消息数")
    parser.add_argument("--rate", type=float, default=6000, help="生产速率 messages/s")
    parser.add_argument("--drain-timeout-ms", type=int, default=15000, help="生产结束后的排空超时")
    parser.add_argument("--checkpoint-wait-seconds", type=int, default=16, help="等待 Checkpoint 的最长时间")
    parser.add_argument("--output", required=True, help="原始结果 JSON 路径")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.job_count < 1 or args.parallelism < 1 or args.count < 1:
        raise ValueError("作业数、并行度和消息数必须大于 0")
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    result = run_case(args)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
    except Exception as error:
        print(f"测试失败：{error}", file=sys.stderr)
        raise
