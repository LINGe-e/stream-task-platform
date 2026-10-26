"""使用 Python 标准库执行 HTTP 压力测试。"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def parse_headers(values: list[str]) -> dict[str, str]:
    headers: dict[str, str] = {}
    for value in values:
        if ":" not in value:
            raise ValueError(f"请求头格式错误：{value}")
        key, item = value.split(":", 1)
        headers[key.strip()] = item.strip()
    return headers


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, round((len(ordered) - 1) * fraction)))
    return ordered[index]


def run_request(
    url: str,
    method: str,
    headers: dict[str, str],
    body: bytes | None,
    timeout: float,
) -> tuple[int | None, float, str | None]:
    started = time.perf_counter()
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            response.read()
            status = response.status
        return status, time.perf_counter() - started, None
    except urllib.error.HTTPError as exc:
        exc.read()
        return exc.code, time.perf_counter() - started, None
    except Exception as exc:
        return None, time.perf_counter() - started, f"{type(exc).__name__}: {exc}"


def worker(
    url: str,
    method: str,
    headers: dict[str, str],
    body: bytes | None,
    timeout: float,
    deadline: float,
    results: list[tuple[int | None, float, str | None]],
    lock: Any,
) -> None:
    while time.perf_counter() < deadline:
        result = run_request(url, method, headers, body, timeout)
        with lock:
            results.append(result)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="HTTP 压力测试")
    parser.add_argument("--url", required=True, help="目标 URL")
    parser.add_argument("--method", default="GET", help="HTTP 方法")
    parser.add_argument("--concurrency", type=int, default=10, help="并发数")
    parser.add_argument("--duration", type=float, default=30.0, help="持续秒数")
    parser.add_argument("--timeout", type=float, default=10.0, help="单请求超时秒数")
    parser.add_argument("--header", action="append", default=[], help="请求头 Key:Value，可重复")
    parser.add_argument("--body", default=None, help="请求体字符串")
    parser.add_argument("--body-file", default=None, help="从 UTF-8 文件读取请求体")
    parser.add_argument("--output", default=None, help="结果 JSON 输出路径")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    if args.concurrency < 1 or args.duration <= 0 or args.timeout <= 0:
        raise SystemExit("concurrency、duration 和 timeout 必须为正数")
    headers = parse_headers(args.header)
    if args.body_file:
        body = Path(args.body_file).read_bytes()
    elif args.body is not None:
        body = args.body.encode("utf-8")
    else:
        body = None
    if body is not None and not any(key.casefold() == "content-type" for key in headers):
        headers["Content-Type"] = "application/json"

    results: list[tuple[int | None, float, str | None]] = []
    lock = __import__("threading").Lock()
    deadline = time.perf_counter() + args.duration
    started_at = datetime.now(timezone.utc)
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [
            pool.submit(
                worker,
                args.url,
                args.method.upper(),
                headers,
                body,
                args.timeout,
                deadline,
                results,
                lock,
            )
            for _ in range(args.concurrency)
        ]
        for future in futures:
            future.result()
    elapsed = max(time.perf_counter() - (deadline - args.duration), 0.001)

    latencies = [item[1] * 1000 for item in results]
    status_counts = Counter(str(item[0]) for item in results)
    errors = [item[2] for item in results if item[2]]
    successful = sum(1 for item in results if item[0] and 200 <= item[0] < 300)
    summary = {
        "target": args.url,
        "method": args.method.upper(),
        "concurrency": args.concurrency,
        "duration_seconds": round(args.duration, 3),
        "elapsed_seconds": round(elapsed, 3),
        "started_at": started_at.isoformat(),
        "total_requests": len(results),
        "successful_requests": successful,
        "failed_requests": len(results) - successful,
        "requests_per_second": round(len(results) / elapsed, 2),
        "latency_ms": {
            "min": round(min(latencies), 2) if latencies else 0,
            "avg": round(statistics.fmean(latencies), 2) if latencies else 0,
            "p50": round(percentile(latencies, 0.50), 2),
            "p95": round(percentile(latencies, 0.95), 2),
            "p99": round(percentile(latencies, 0.99), 2),
            "max": round(max(latencies), 2) if latencies else 0,
        },
        "status_counts": dict(status_counts),
        "error_samples": errors[:10],
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if successful == len(results) and results else 1


if __name__ == "__main__":
    sys.exit(main())
