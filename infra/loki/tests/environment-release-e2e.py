#!/usr/bin/env python3
"""Verify the application's Loki environment and release filters on Loki 3.5.5."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


IMAGE = os.environ.get("LOKI_FILTER_TEST_IMAGE", "grafana/loki:3.5.5")
LABEL_KEY = "com.monstereat.observability.loki-filter-e2e"


def run(command: list[str], *, check: bool = True) -> str:
    result = subprocess.run(command, text=True, capture_output=True, check=False)
    if check and result.returncode:
        raise RuntimeError(
            f"Command failed ({result.returncode}): {' '.join(command[:2])}: "
            f"{result.stderr.strip()}"
        )
    return result.stdout.strip()


def request(
    base_url: str,
    path: str,
    method: str = "GET",
    payload: dict | None = None,
    content_type: str = "application/json",
):
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {} if body is None else {"Content-Type": content_type}
    req = urllib.request.Request(base_url + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Loki {method} {path} returned HTTP {error.code}: {detail}") from error


def start_loki(container: str, run_id: str, config_path: Path, storage_dir: Path) -> str:
    run(
        [
            "docker", "run", "--detach", "--name", container,
            "--label", f"{LABEL_KEY}={run_id}",
            "--user", f"{os.getuid()}:{os.getgid()}",
            "--publish", "127.0.0.1::3100",
            "--mount", f"type=bind,src={config_path},dst=/etc/loki/config.yaml,readonly",
            "--mount", f"type=bind,src={storage_dir},dst=/loki",
            IMAGE, "-config.file=/etc/loki/config.yaml",
        ]
    )
    published = run(["docker", "port", container, "3100/tcp"]).splitlines()[0]
    base_url = f"http://127.0.0.1:{published.rsplit(':', 1)[1]}"
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(base_url + "/ready", timeout=5) as response:
                if response.status == 200:
                    return base_url
        except (OSError, urllib.error.HTTPError):
            pass
        state = subprocess.run(
            ["docker", "inspect", "--format", "{{.State.Status}}", container],
            text=True, capture_output=True, check=False,
        )
        if state.returncode == 0 and state.stdout.strip() not in ("running", "restarting"):
            logs = run(["docker", "logs", "--tail", "100", container], check=False)
            raise RuntimeError(
                f"Isolated Loki exited during startup (state={state.stdout.strip()}); logs:\n{logs}"
            )
        time.sleep(1)
    raise RuntimeError(f"Isolated Loki did not become ready: {container}")


def query_range(base_url: str, query: str, start_ns: int, end_ns: int) -> list[dict]:
    params = urllib.parse.urlencode(
        {"query": query, "start": str(start_ns), "end": str(end_ns), "limit": "100",
         "direction": "forward"}
    )
    status, response = request(base_url, f"/loki/api/v1/query_range?{params}")
    if status != 200 or not isinstance(response, dict) or response.get("status") != "success":
        raise RuntimeError(f"Loki range query failed for {query!r}: {response}")
    data = response.get("data", {})
    return data.get("result", []) if isinstance(data, dict) else []


def query_count(base_url: str, query: str) -> int:
    params = urllib.parse.urlencode({"query": query})
    status, response = request(base_url, f"/loki/api/v1/query?{params}")
    if status != 200 or not isinstance(response, dict) or response.get("status") != "success":
        raise RuntimeError(f"Loki metric query failed for {query!r}: {response}")
    results = response.get("data", {}).get("result", [])
    if not results:
        return 0
    try:
        return int(float(results[0]["value"][1]))
    except (KeyError, IndexError, TypeError, ValueError) as error:
        raise RuntimeError(f"Unexpected Loki metric response: {response}") from error


def lines(streams: list[dict]) -> set[str]:
    return {
        entry[1]
        for stream in streams
        for entry in stream.get("values", [])
        if isinstance(entry, list) and len(entry) >= 2
    }


def main() -> None:
    run_id = uuid.uuid4().hex
    container = f"monitor-loki-filter-e2e-{run_id[:12]}"
    temp_dir = Path(tempfile.mkdtemp(prefix="monitor-loki-filter-e2e-"))
    storage_dir = temp_dir / "storage"
    storage_dir.mkdir(mode=0o700)
    config_path = temp_dir / "loki.yml"
    config_path.write_text(
        """auth_enabled: false
server:
  http_listen_port: 3100
common:
  path_prefix: /loki
  storage:
    filesystem:
      chunks_directory: /loki/chunks
      rules_directory: /loki/rules
  replication_factor: 1
  ring:
    kvstore:
      store: inmemory
schema_config:
  configs:
    - from: 2024-01-01
      store: tsdb
      object_store: filesystem
      schema: v13
      index:
        prefix: filter_test_index_
        period: 24h
storage_config:
  tsdb_shipper:
    active_index_directory: /tmp/loki-tsdb-active
    cache_location: /tmp/loki-tsdb-cache
limits_config:
  allow_structured_metadata: true
  reject_old_samples_max_age: 168h
""",
        encoding="utf-8",
    )

    try:
        existing = subprocess.run(
            ["docker", "inspect", container], text=True, capture_output=True, check=False
        )
        if existing.returncode == 0:
            raise RuntimeError(f"Refusing to reuse an existing container name: {container}")

        base_url = start_loki(container, run_id, config_path, storage_dir)
        project = f"filter-e2e-{run_id[:12]}"
        environment = "production"
        release = f"web-{run_id[:8]}"
        other_environment = "staging"
        other_release = f"api-{run_id[:8]}"
        production_line = f"synthetic production log {run_id}"
        staging_line = f"synthetic staging log {run_id}"
        event_ns = time.time_ns()
        push_status, _ = request(
            base_url,
            "/otlp/v1/logs",
            method="POST",
            payload={
                "resourceLogs": [
                    {
                        "resource": {
                            "attributes": [
                                {"key": "service.name", "value": {"stringValue": "observability-platform"}}
                            ]
                        },
                        "scopeLogs": [
                            {
                                "scope": {"name": "mall-tiny-loki-filter-e2e"},
                                "logRecords": [
                                    {
                                        "timeUnixNano": str(event_ns),
                                        "body": {"stringValue": production_line},
                                        "attributes": [
                                            {"key": "monitor.project", "value": {"stringValue": project}},
                                            {"key": "monitor.environment", "value": {"stringValue": environment}},
                                            {"key": "monitor.release", "value": {"stringValue": release}},
                                        ],
                                    },
                                    {
                                        "timeUnixNano": str(event_ns + 1),
                                        "body": {"stringValue": staging_line},
                                        "attributes": [
                                            {"key": "monitor.project", "value": {"stringValue": project}},
                                            {"key": "monitor.environment", "value": {"stringValue": other_environment}},
                                            {"key": "monitor.release", "value": {"stringValue": other_release}},
                                        ],
                                    },
                                ],
                            }
                        ],
                    }
                ]
            },
        )
        if push_status not in (200, 204):
            raise RuntimeError(f"Synthetic Loki push failed with HTTP {push_status}")

        # Keep the selectors byte-for-byte aligned with MonitorLogQueryService.search().
        selector = f'{{service_name="observability-platform"}} | monitor_project="{project}"'
        environment_selector = selector + f' | monitor_environment="{environment}"'
        environment_miss_selector = selector + f' | monitor_environment="{other_environment}-wrong"'
        release_selector = selector + f' | monitor_release="{release}"'
        release_miss_selector = selector + f' | monitor_release="{release}-wrong"'
        combined_selector = environment_selector + f' | monitor_release="{release}"'
        crossed_selector = environment_selector + f' | monitor_release="{other_release}"'

        start_ns = event_ns - 60_000_000_000
        end_ns = event_ns + 60_000_000_000
        expected = {
            "environment exact hit": (environment_selector, {production_line}),
            "environment wrong value miss": (environment_miss_selector, set()),
            "release exact hit": (release_selector, {production_line}),
            "release wrong value miss": (release_miss_selector, set()),
            "environment and release exact hit": (combined_selector, {production_line}),
            "environment and release cross miss": (crossed_selector, set()),
        }
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            results = {
                name: lines(query_range(base_url, query, start_ns, end_ns))
                for name, (query, _) in expected.items()
            }
            if results["environment exact hit"] and results["release exact hit"]:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Synthetic structured-metadata logs were not queryable")

        for name, (_, wanted) in expected.items():
            actual = results[name]
            if actual != wanted:
                raise AssertionError(f"{name}: expected {wanted}, got {actual}")
            print(f"PASS {name}: {len(actual)} line(s)")

        # Also exercise the count_over_time query shape used by Logs Explore aggregation.
        count_queries = {
            "environment count":
                f'sum(count_over_time({environment_selector} [1h]))',
            "environment mismatch count":
                f'sum(count_over_time({environment_miss_selector} [1h]))',
            "release count":
                f'sum(count_over_time({release_selector} [1h]))',
            "release mismatch count":
                f'sum(count_over_time({release_miss_selector} [1h]))',
        }
        for name, query in count_queries.items():
            actual = query_count(base_url, query)
            wanted = 0 if "mismatch" in name else 1
            if actual != wanted:
                raise AssertionError(f"{name}: expected {wanted}, got {actual}")
            print(f"PASS {name}: {actual}")
    finally:
        inspected = subprocess.run(
            ["docker", "inspect", "--format", f'{{{{ index .Config.Labels "{LABEL_KEY}" }}}}', container],
            text=True, capture_output=True, check=False,
        )
        if inspected.returncode == 0 and inspected.stdout.strip() == run_id:
            run(["docker", "rm", "--force", container], check=False)
        shutil.rmtree(temp_dir, ignore_errors=True)


if __name__ == "__main__":
    main()
