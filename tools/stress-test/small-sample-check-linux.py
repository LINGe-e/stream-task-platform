#!/usr/bin/env python3
"""在 VMware Linux 客户机中执行 Kafka-Flink 小样本正确性校验。"""

from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from typing import Any

import requests


ROOT = Path(os.environ.get("A22_STRESS_ROOT", "/home/l1ngee/stress-data"))
KAFKA_HOME = Path(os.environ.get("A22_KAFKA_HOME", str(ROOT / "kafka")))
FLINK_REST = "http://127.0.0.1:8081"
BOOTSTRAP = "127.0.0.1:9092"
JOB_JAR = Path(
    os.environ.get(
        "A22_JOB_JAR",
        str(ROOT / "flink-jobs-1.0-SNAPSHOT-shaded.jar"),
    )
)
ENTRY_CLASS = "com.a22.flink.DynamicPipelineJob"
CASE_ID = os.environ.get("A22_SMALL_CASE_ID", "small-sample-vm-20261008")
INPUT_TOPIC = f"stress-small-in-{CASE_ID}"
OUTPUT_TOPIC = f"stress-small-out-{CASE_ID}"
GROUP_ID = f"stress-small-group-{CASE_ID}"
SAMPLE_COUNT = 100


def run(command: list[str], input_text: str | None = None, timeout: int = 60) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        input=input_text,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        check=False,
    )


def require(result: subprocess.CompletedProcess[str], label: str) -> str:
    if result.returncode != 0:
        raise RuntimeError(f"{label} 失败：{result.stdout}\n{result.stderr}")
    return result.stdout


def create_topic(topic: str) -> None:
    command = [
        str(KAFKA_HOME / "bin" / "kafka-topics.sh"),
        "--bootstrap-server",
        BOOTSTRAP,
        "--create",
        "--if-not-exists",
        "--topic",
        topic,
        "--partitions",
        "4",
        "--replication-factor",
        "1",
    ]
    require(run(command), f"创建 Topic {topic}")


def upload_jar() -> str:
    if not JOB_JAR.exists():
        raise FileNotFoundError(JOB_JAR)
    with JOB_JAR.open("rb") as jar_file:
        response = requests.post(
            f"{FLINK_REST}/jars/upload",
            files={"file": (JOB_JAR.name, jar_file)},
            timeout=120,
        )
    response.raise_for_status()
    payload = response.json()
    if payload.get("status") != "success":
        raise RuntimeError(f"上传 JAR 失败：{payload}")
    listing = requests.get(f"{FLINK_REST}/jars", timeout=20).json()
    matches = [
        item
        for item in listing.get("files", [])
        if item.get("name") == JOB_JAR.name
    ]
    if not matches:
        raise RuntimeError("上传后未找到 JAR")
    return str(max(matches, key=lambda item: int(item.get("uploaded", 0)))["id"])


def submit_job(jar_id: str) -> str:
    config: dict[str, Any] = {
        "jobName": f"{CASE_ID}-job",
        "source": {
            "type": "kafka",
            "bootstrapServers": BOOTSTRAP,
            "topic": INPUT_TOPIC,
            "groupId": GROUP_ID,
        },
        "transforms": [
            {
                "type": "concat",
                "fields": ["first_name", "last_name"],
                "separator": " ",
                "target": "full_name",
            }
        ],
        "sink": {
            "type": "kafka",
            "bootstrapServers": BOOTSTRAP,
            "topic": OUTPUT_TOPIC,
        },
    }
    encoded = base64.b64encode(
        json.dumps(config, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    ).decode("ascii")
    response = requests.post(
        f"{FLINK_REST}/jars/{jar_id}/run",
        json={
            "entryClass": ENTRY_CLASS,
            "parallelism": 1,
            "programArgs": f"--config {encoded}",
        },
        timeout=30,
    )
    response.raise_for_status()
    job_id = str(response.json()["jobid"])
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        state = requests.get(f"{FLINK_REST}/jobs/{job_id}", timeout=20).json().get("state")
        if state == "RUNNING":
            return job_id
        if state in {"FAILED", "CANCELED", "FINISHED"}:
            raise RuntimeError(f"Flink 小样本作业状态异常：{state}")
        time.sleep(0.5)
    raise RuntimeError("Flink 小样本作业未进入 RUNNING")


def produce(rows: list[dict[str, object]]) -> None:
    input_text = "\n".join(
        json.dumps(row, ensure_ascii=False, separators=(",", ":")) for row in rows
    ) + "\n"
    command = [
        str(KAFKA_HOME / "bin" / "kafka-console-producer.sh"),
        "--bootstrap-server",
        BOOTSTRAP,
        "--topic",
        INPUT_TOPIC,
        "--request-required-acks",
        "all",
        "--batch-size",
        "1000",
        "--timeout",
        "10000",
    ]
    require(run(command, input_text=input_text, timeout=60), "生产小样本")


def consume(attempt: int) -> list[dict[str, object]]:
    verify_group = f"{GROUP_ID}-verify-{int(time.time())}-{attempt}"
    command = [
        str(KAFKA_HOME / "bin" / "kafka-console-consumer.sh"),
        "--bootstrap-server",
        BOOTSTRAP,
        "--topic",
        OUTPUT_TOPIC,
        "--from-beginning",
        "--max-messages",
        str(SAMPLE_COUNT),
        "--timeout-ms",
        "30000",
        "--group",
        verify_group,
    ]
    result = run(command, timeout=60)
    rows: list[dict[str, object]] = []
    for line in result.stdout.splitlines():
        line = line.strip()
        if not line.startswith("{") or not line.endswith("}"):
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return rows


def cancel_job(job_id: str) -> None:
    try:
        requests.patch(
            f"{FLINK_REST}/jobs/{job_id}",
            params={"mode": "cancel"},
            timeout=20,
        )
    except Exception:
        pass


def main() -> int:
    create_topic(INPUT_TOPIC)
    create_topic(OUTPUT_TOPIC)
    time.sleep(2)
    rows = [
        {
            "id": index,
            "first_name": f"名{index:03d}",
            "last_name": f"姓{index:03d}",
        }
        for index in range(SAMPLE_COUNT)
    ]
    jar_id = upload_jar()
    job_id = submit_job(jar_id)
    outputs: list[dict[str, object]] = []
    try:
        produce(rows)
        deadline = time.monotonic() + 35
        attempt = 0
        while time.monotonic() < deadline:
            attempt += 1
            outputs = consume(attempt)
            if len(outputs) >= SAMPLE_COUNT:
                break
            time.sleep(1)
    finally:
        cancel_job(job_id)

    ids = [row.get("id") for row in outputs]
    unique_ids = set(ids)
    expected_ids = {row["id"] for row in rows}
    missing = sorted(expected_ids - unique_ids)
    duplicates = len(ids) - len(unique_ids)
    bad_concat = [
        row.get("id")
        for row in outputs
        if row.get("full_name")
        != f"{row.get('first_name')} {row.get('last_name')}"
    ]
    summary = {
        "case_id": CASE_ID,
        "execution_location": "vmware-guest",
        "input_count": len(rows),
        "output_count": len(outputs),
        "unique_id_count": len(unique_ids),
        "missing_ids": missing,
        "duplicate_records": duplicates,
        "bad_concat_records": bad_concat,
        "passed": (
            len(outputs) == SAMPLE_COUNT
            and not missing
            and duplicates == 0
            and not bad_concat
        ),
        "input_topic": INPUT_TOPIC,
        "output_topic": OUTPUT_TOPIC,
        "job_id": job_id,
    }
    output_path = ROOT / "outputs" / f"small-sample-{CASE_ID}.json"
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
