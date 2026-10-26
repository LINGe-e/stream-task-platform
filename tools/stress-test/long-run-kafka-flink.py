#!/usr/bin/env python3
"""执行 Kafka-Flink 30 分钟长稳测试并保存分块与汇总结果。"""

from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
RUNNER_PATH = ROOT / "tools" / "stress-test" / "run-kafka-flink-test.py"
CASE_ID = os.environ.get("A22_LONG_CASE_ID", "long-run-20261007-p4-r6000")
RATE = 6000
PARALLELISM = 4
CHUNKS = 10
CHUNK_SECONDS = 180
CHUNK_COUNT = RATE * CHUNK_SECONDS
DURATION_SECONDS = CHUNKS * CHUNK_SECONDS


def load_runner() -> Any:
    runner_path = RUNNER_PATH
    if os.name != "nt":
        runner_path = RUNNER_PATH.with_name("run-kafka-flink-test-linux.py")
    spec = importlib.util.spec_from_file_location("kafka_flink_runner", runner_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载测试驱动：{runner_path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    if os.name != "nt":
        return module.load_base()
    return module


def write_json(path: Path, value: Any) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def weighted_latency(chunks: list[dict[str, Any]], metric: str) -> dict[str, Any]:
    latencies = [chunk[metric] for chunk in chunks]
    count = sum(int(item["count"]) for item in latencies)
    average = (
        sum(float(item["avg"]) * int(item["count"]) for item in latencies) / count
        if count
        else None
    )
    return {
        "count": count,
        "avg": round(average, 2) if average is not None else None,
        "p95_chunk_mean": round(
            sum(float(item["p95"]) for item in latencies) / len(latencies), 2
        )
        if latencies
        else None,
        "p95_chunk_max": max(float(item["p95"]) for item in latencies)
        if latencies
        else None,
        "p99_chunk_mean": round(
            sum(float(item["p99"]) for item in latencies) / len(latencies), 2
        )
        if latencies
        else None,
        "p99_chunk_max": max(float(item["p99"]) for item in latencies)
        if latencies
        else None,
        "max": max(float(item["max"]) for item in latencies) if latencies else None,
    }


def checkpoint_sampler(
    runner: Any,
    job_id: str,
    output_path: Path,
    stop_event: threading.Event,
) -> None:
    while not stop_event.is_set():
        sample = {
            "timestamp": runner.now_iso(),
            "cluster": runner.flink_get("/overview"),
            "checkpoints": runner.summarize_checkpoints(
                runner.safe_get(f"/jobs/{job_id}/checkpoints")
            ),
        }
        with output_path.open("a", encoding="utf-8") as output:
            output.write(json.dumps(sample, ensure_ascii=False) + "\n")
        stop_event.wait(10)


def wait_for_cancel(runner: Any, job_id: str) -> None:
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if runner.flink_get(f"/jobs/{job_id}").get("state") == "CANCELED":
            return
        time.sleep(0.5)


def main() -> int:
    runner = load_runner()
    input_topic = f"stress-in-{CASE_ID}"
    output_topic = f"stress-out-{CASE_ID}-0"
    group_id = f"stress-group-{CASE_ID}-0"
    output_path = ROOT / "outputs" / f"kafka-flink-{CASE_ID}.json"
    chunk_log_path = ROOT / "outputs" / f"{CASE_ID}-chunks.jsonl"
    resource_path = ROOT / "outputs" / f"resource-{CASE_ID}.jsonl"
    backlog_path = ROOT / "outputs" / f"backlog-{CASE_ID}.jsonl"
    checkpoint_path = ROOT / "outputs" / f"checkpoint-{CASE_ID}.jsonl"

    if output_path.exists():
        print(f"[SKIP] {output_path}")
        return 0

    runner.create_topic(input_topic)
    runner.create_topic(output_topic)
    overview_before = runner.flink_get("/overview")
    jar_id = runner.upload_jar()
    job_id = runner.submit_job(
        jar_id,
        CASE_ID,
        0,
        PARALLELISM,
        input_topic,
        output_topic,
        group_id,
    )

    estimated_seconds = DURATION_SECONDS + 180
    if os.name == "nt":
        resource_command = [
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(runner.RESOURCE_SAMPLER),
            "-Output",
            str(resource_path),
            "-IntervalSeconds",
            "1",
            "-DurationSeconds",
            str(estimated_seconds),
        ]
    else:
        resource_sampler = Path(__file__).with_name("resource-sampler-linux.py")
        resource_command = [
            sys.executable,
            str(resource_sampler),
            "-Output",
            str(resource_path),
            "-IntervalSeconds",
            "1",
            "-DurationSeconds",
            str(estimated_seconds),
        ]
    resource_process = subprocess.Popen(
        resource_command,
        cwd=ROOT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    lag_sampler = runner.LagSampler([group_id], input_topic, backlog_path)
    lag_sampler.start()
    checkpoint_stop = threading.Event()
    checkpoint_thread = threading.Thread(
        target=checkpoint_sampler,
        args=(runner, job_id, checkpoint_path, checkpoint_stop),
        daemon=True,
    )
    checkpoint_thread.start()

    chunks: list[dict[str, Any]] = []
    started_at = runner.now_iso()
    first_started = None
    last_ended = None
    try:
        for index in range(CHUNKS):
            case_id = f"{CASE_ID}-c{index + 1:02d}"
            chunk_started = runner.now_iso()
            print(f"[CHUNK] {index + 1}/{CHUNKS} 开始", flush=True)
            chunk = runner.run_bench(
                case_id,
                input_topic,
                [output_topic],
                CHUNK_COUNT,
                RATE,
                15000,
                ROOT / "outputs" / f"e2e-{case_id}.json",
            )
            chunk_ended = runner.now_iso()
            record = {
                "index": index + 1,
                "case_id": case_id,
                "started_at": chunk_started,
                "ended_at": chunk_ended,
                "e2e": chunk,
            }
            chunks.append(record)
            with chunk_log_path.open("a", encoding="utf-8") as output:
                output.write(json.dumps(record, ensure_ascii=False) + "\n")
            if first_started is None:
                first_started = chunk_started
            last_ended = chunk_ended
            print(
                f"[CHUNK] {index + 1}/{CHUNKS} 完成 "
                f"吞吐={chunk['end_to_end_throughput_rps']} "
                f"P95={chunk['end_to_end_latency_ms']['p95']}ms "
                f"失败率={chunk['record_failure_rate']}",
                flush=True,
            )

        runner.wait_for_checkpoint([job_id], 30)
        time.sleep(2)
        final_lags = runner.wait_for_lags_settled([group_id], input_topic, 30)
        lag_sampler.stop()
        lag_sampler.join(timeout=120)
        resource_process.wait(timeout=estimated_seconds + 60)
        checkpoint_stop.set()
        checkpoint_thread.join(timeout=20)
        cluster_during = runner.flink_get("/overview")
        ended_at = runner.now_iso()

        runner.cancel_job(job_id)
        wait_for_cancel(runner, job_id)
        details = runner.flink_get(f"/jobs/{job_id}")
        checkpoints = runner.safe_get(f"/jobs/{job_id}/checkpoints")
        exceptions = runner.safe_get(
            f"/jobs/{job_id}/exceptions", {"maxExceptions": 10}
        )
        exception_history = (
            exceptions.get("exceptionHistory", {})
            if isinstance(exceptions, dict)
            else {}
        )
        exception_entries = exception_history.get("entries", [])
        status = details.get("state") or details.get("status")
        failed_job_count = 1 if status == "FAILED" or exception_entries else 0

        e2e_chunks = [chunk["e2e"] for chunk in chunks]
        expected = sum(int(chunk["expected_outputs"]) for chunk in e2e_chunks)
        received = sum(int(chunk["received_unique"]) for chunk in e2e_chunks)
        missing = max(0, expected - received)
        producer_seconds = sum(float(chunk["producer_seconds"]) for chunk in e2e_chunks)
        measurement_seconds = sum(
            float(chunk["measurement_seconds"]) for chunk in e2e_chunks
        )
        producer_acked = sum(int(chunk["producer_acked"]) for chunk in e2e_chunks)
        from datetime import datetime

        first_timestamp = datetime.fromisoformat(first_started).timestamp()
        last_timestamp = datetime.fromisoformat(last_ended).timestamp()
        wall_seconds = max(1.0, last_timestamp - first_timestamp)

        result = {
            "schema_version": 1,
            "suite": "long-run",
            "case_id": CASE_ID,
            "created_at": runner.now_iso(),
            "started_at": started_at,
            "ended_at": ended_at,
            "parameters": {
                "duration_seconds": DURATION_SECONDS,
                "chunks": CHUNKS,
                "chunk_seconds": CHUNK_SECONDS,
                "chunk_count": CHUNK_COUNT,
                "parallelism": PARALLELISM,
                "rate_rps": RATE,
                "partitions": runner.PARTITIONS,
            },
            "cluster_before": overview_before,
            "cluster_during": cluster_during,
            "cluster_after": runner.flink_get("/overview"),
            "input_topic": input_topic,
            "output_topics": [output_topic],
            "group_ids": [group_id],
            "job_ids": [job_id],
            "chunks": chunks,
            "e2e": {
                "expected_outputs": expected,
                "received_unique": received,
                "missing_outputs": missing,
                "record_failure_rate": round(missing / expected, 8)
                if expected
                else None,
                "producer_throughput_rps": round(
                    producer_acked / producer_seconds, 2
                )
                if producer_seconds
                else None,
                "chunk_window_end_to_end_throughput_rps": round(
                    received / measurement_seconds, 2
                )
                if measurement_seconds
                else None,
                "overall_wall_throughput_rps": round(received / wall_seconds, 2),
                "first_chunk_started_at": first_started,
                "last_chunk_ended_at": last_ended,
                "overall_wall_seconds": round(wall_seconds, 3),
                "end_to_end_latency_ms": weighted_latency(
                    e2e_chunks, "end_to_end_latency_ms"
                ),
                "producer_latency_ms": weighted_latency(
                    e2e_chunks, "producer_latency_ms"
                ),
            },
            "backlog": {
                **runner.summarize_lag(lag_sampler.samples, [group_id]),
                "final_by_group": final_lags,
                "raw_file": str(backlog_path),
            },
            "resources": runner.read_resource_samples(resource_path),
            "checkpoint_samples_file": str(checkpoint_path),
            "jobs": {
                "0": {
                    "job_id": job_id,
                    "group_id": group_id,
                    "output_topic": output_topic,
                    "status": status,
                    "parallelism": details.get("parallelism", PARALLELISM),
                    "metrics": runner.extract_job_metrics(details),
                    "checkpoints": runner.summarize_checkpoints(checkpoints),
                    "exceptions": exceptions,
                }
            },
            "job_summary": {
                "total": 1,
                "failed": failed_job_count,
                "failure_rate": float(failed_job_count),
            },
        }
        write_json(output_path, result)
        print(f"[DONE] {output_path}", flush=True)
        return 0
    except Exception as exc:
        print(f"[ERROR] {exc}", file=sys.stderr, flush=True)
        return 1
    finally:
        checkpoint_stop.set()
        lag_sampler.stop()
        try:
            runner.cancel_job(job_id)
        except Exception:
            pass
        if resource_process.poll() is None:
            resource_process.terminate()
            try:
                resource_process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                resource_process.kill()


if __name__ == "__main__":
    raise SystemExit(main())
