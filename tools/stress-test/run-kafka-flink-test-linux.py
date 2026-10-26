#!/usr/bin/env python3
"""在 Linux VMware 客户机中运行 Kafka-Flink 端到端压测。"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from typing import Any


ROOT = Path(os.environ.get("A22_STRESS_ROOT", "/home/l1ngee/stress-data"))
KAFKA_HOME = Path(os.environ.get("A22_KAFKA_HOME", str(ROOT / "kafka")))
FLINK_HOME = Path(os.environ.get("A22_FLINK_HOME", str(ROOT / "flink")))
BENCH_CLASSES = Path(
    os.environ.get("A22_BENCH_CLASSES", str(ROOT / "tools" / "stress-test" / "classes"))
)
JOB_JAR = Path(
    os.environ.get(
        "A22_JOB_JAR",
        str(ROOT / "flink-jobs-1.0-SNAPSHOT-shaded.jar"),
    )
)
RUNNER_PATH = Path(__file__).with_name("run-kafka-flink-test.py")
RESOURCE_SAMPLER = Path(__file__).with_name("resource-sampler-linux.py")
FLINK_REST = "http://127.0.0.1:8081"
KAFKA_BOOTSTRAP = "127.0.0.1:9092"
ENTRY_CLASS = "com.a22.flink.DynamicPipelineJob"
PARTITIONS = 4


def load_base() -> Any:
    spec = importlib.util.spec_from_file_location("a22_stress_base", RUNNER_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载基础驱动：{RUNNER_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    module.ROOT = ROOT
    module.KAFKA_BOOTSTRAP = KAFKA_BOOTSTRAP
    module.KAFKA_TOPICS = KAFKA_HOME / "bin" / "kafka-topics.sh"
    module.KAFKA_GROUPS = KAFKA_HOME / "bin" / "kafka-consumer-groups.sh"
    module.KAFKA_GET_OFFSETS = KAFKA_HOME / "bin" / "kafka-get-offsets.sh"
    module.JAVA = Path("/usr/bin/java")
    module.KAFKA_LIBS = KAFKA_HOME / "libs"
    module.BENCH_CLASSES = BENCH_CLASSES
    module.JOB_JAR = JOB_JAR
    module.ENTRY_CLASS = ENTRY_CLASS
    module.PARTITIONS = PARTITIONS
    module.SAMPLE_INTERVAL_SECONDS = 1.5
    module.run_cmd = linux_run_cmd
    module.create_topic = linux_create_topic
    module.group_lag = linux_group_lag
    module.topic_end_offset = linux_topic_end_offset
    return module


def linux_run_cmd(command: list[str], timeout: int = 120) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        check=False,
    )


def linux_create_topic(topic: str) -> None:
    command = [
        str(KAFKA_HOME / "bin" / "kafka-topics.sh"),
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
    result = linux_run_cmd(command, timeout=60)
    if result.returncode != 0:
        raise RuntimeError(
            f"创建 Kafka Topic 失败：{topic}\n{result.stdout}\n{result.stderr}"
        )


def linux_group_lag(group_id: str, input_topic: str) -> int | None:
    command = [
        str(KAFKA_HOME / "bin" / "kafka-consumer-groups.sh"),
        "--bootstrap-server",
        KAFKA_BOOTSTRAP,
        "--describe",
        "--group",
        group_id,
    ]
    result = linux_run_cmd(command, timeout=45)
    if result.returncode != 0:
        return linux_topic_end_offset(input_topic)
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
    return total_lag if found else linux_topic_end_offset(input_topic)


def linux_topic_end_offset(topic: str) -> int | None:
    command = [
        str(KAFKA_HOME / "bin" / "kafka-get-offsets.sh"),
        "--bootstrap-server",
        KAFKA_BOOTSTRAP,
        "--topic",
        topic,
        "--time",
        "-1",
    ]
    result = linux_run_cmd(command, timeout=45)
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


def start_resource_sampler(base: Any, case_id: str, duration_seconds: int) -> subprocess.Popen[bytes]:
    output = ROOT / "outputs" / f"resource-{case_id}.jsonl"
    return subprocess.Popen(
        [
            sys.executable,
            str(RESOURCE_SAMPLER),
            "-Output",
            str(output),
            "-IntervalSeconds",
            "1",
            "-DurationSeconds",
            str(duration_seconds),
        ],
        cwd=ROOT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def cancel_all(base: Any, job_ids: list[str]) -> None:
    for job_id in job_ids:
        try:
            base.cancel_job(job_id)
        except Exception:
            pass
    deadline = time.monotonic() + 20
    while job_ids and time.monotonic() < deadline:
        states = [base.flink_get(f"/jobs/{job_id}").get("state") for job_id in job_ids]
        if all(state == "CANCELED" for state in states):
            return
        time.sleep(0.5)


def collect_jobs(base: Any, job_ids: list[str], group_ids: list[str], output_topics: list[str], parallelism: int) -> tuple[dict[str, Any], int]:
    jobs: dict[str, Any] = {}
    failed = 0
    for index, job_id in enumerate(job_ids):
        details = base.flink_get(f"/jobs/{job_id}")
        checkpoints = base.safe_get(f"/jobs/{job_id}/checkpoints")
        exceptions = base.safe_get(f"/jobs/{job_id}/exceptions", {"maxExceptions": 10})
        history = exceptions.get("exceptionHistory", {}) if isinstance(exceptions, dict) else {}
        entries = history.get("entries", [])
        status = details.get("state") or details.get("status")
        if status == "FAILED" or entries:
            failed += 1
        jobs[str(index)] = {
            "job_id": job_id,
            "group_id": group_ids[index],
            "output_topic": output_topics[index],
            "status": status,
            "parallelism": details.get("parallelism", parallelism),
            "metrics": base.extract_job_metrics(details),
            "checkpoints": base.summarize_checkpoints(checkpoints),
            "exceptions": exceptions,
        }
    return jobs, failed


def run_case(args: argparse.Namespace, base: Any) -> dict[str, Any]:
    case_id = args.case_id
    input_topic = f"stress-in-{case_id}"
    output_topics = [f"stress-out-{case_id}-{index}" for index in range(args.job_count)]
    group_ids = [f"stress-group-{case_id}-{index}" for index in range(args.job_count)]
    output_path = ROOT / "outputs" / f"kafka-flink-{case_id}.json"
    if output_path.exists():
        return json.loads(output_path.read_text(encoding="utf-8"))

    base.create_topic(input_topic)
    for topic in output_topics:
        base.create_topic(topic)
    overview_before = base.flink_get("/overview")
    jar_id = base.upload_jar()
    job_ids: list[str] = []
    resource_process: subprocess.Popen[bytes] | None = None
    lag_sampler: Any = None
    try:
        for index, (topic, group_id) in enumerate(zip(output_topics, group_ids)):
            job_ids.append(
                base.submit_job(
                    jar_id,
                    case_id,
                    index,
                    args.parallelism,
                    input_topic,
                    topic,
                    group_id,
                )
            )
        estimated = int(args.count / max(args.rate, 1) + args.drain_timeout_ms / 1000 + 25)
        resource_process = start_resource_sampler(base, case_id, estimated)
        lag_path = ROOT / "outputs" / f"backlog-{case_id}.jsonl"
        lag_sampler = base.LagSampler(group_ids, input_topic, lag_path)
        lag_sampler.start()
        time.sleep(1)
        started_at = base.now_iso()
        e2e = base.run_bench(
            case_id,
            input_topic,
            output_topics,
            args.count,
            args.rate,
            args.drain_timeout_ms,
            ROOT / "outputs" / f"e2e-{case_id}.json",
        )
        ended_at = base.now_iso()
        base.wait_for_checkpoint(job_ids, args.checkpoint_wait_seconds)
        time.sleep(2)
        final_lags = base.wait_for_lags_settled(group_ids, input_topic, 30)
        lag_sampler.stop()
        lag_sampler.join(timeout=120)
        if resource_process is not None:
            resource_process.wait(timeout=estimated + 60)
        cluster_during = base.flink_get("/overview")
        cancel_all(base, job_ids)
        jobs, failed_jobs = collect_jobs(
            base, job_ids, group_ids, output_topics, args.parallelism
        )
        result = {
            "schema_version": 1,
            "suite": args.suite,
            "case_id": case_id,
            "created_at": base.now_iso(),
            "started_at": started_at,
            "ended_at": ended_at,
            "parameters": {
                "job_count": args.job_count,
                "parallelism_per_job": args.parallelism,
                "message_count": args.count,
                "offered_rate_rps": args.rate,
                "drain_timeout_ms": args.drain_timeout_ms,
                "partitions": PARTITIONS,
                "execution_location": "vmware-guest",
            },
            "cluster_before": overview_before,
            "cluster_during": cluster_during,
            "cluster_after": base.flink_get("/overview"),
            "input_topic": input_topic,
            "output_topics": output_topics,
            "group_ids": group_ids,
            "job_ids": job_ids,
            "e2e": e2e,
            "backlog": {
                **base.summarize_lag(lag_sampler.samples, group_ids),
                "final_by_group": final_lags,
                "raw_file": str(lag_path),
            },
            "resources": base.read_resource_samples(
                ROOT / "outputs" / f"resource-{case_id}.jsonl"
            ),
            "jobs": jobs,
            "job_summary": {
                "total": len(job_ids),
                "failed": failed_jobs,
                "failure_rate": failed_jobs / len(job_ids) if job_ids else 0,
            },
        }
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        return result
    finally:
        if lag_sampler is not None:
            lag_sampler.stop()
        cancel_all(base, job_ids)
        if resource_process is not None and resource_process.poll() is None:
            resource_process.terminate()
            try:
                resource_process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                resource_process.kill()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="VMware 客户机 Kafka-Flink 压测")
    parser.add_argument("--suite", required=True)
    parser.add_argument("--case-id", required=True)
    parser.add_argument("--job-count", type=int, default=1)
    parser.add_argument("--parallelism", type=int, default=1)
    parser.add_argument("--count", type=int, default=60000)
    parser.add_argument("--rate", type=float, default=6000)
    parser.add_argument("--drain-timeout-ms", type=int, default=15000)
    parser.add_argument("--checkpoint-wait-seconds", type=int, default=16)
    args = parser.parse_args()
    if args.job_count < 1 or args.parallelism < 1 or args.count < 1:
        parser.error("job-count、parallelism 和 count 必须大于 0")
    if args.rate <= 0:
        parser.error("rate 必须大于 0")
    return args


def main() -> int:
    args = parse_args()
    base = load_base()
    result = run_case(args, base)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
