#!/usr/bin/env python3
"""Run Loki's delete API against a disposable, isolated Loki container."""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path


IMAGE = os.environ.get("LOKI_DELETE_TEST_IMAGE", "grafana/loki:3.5.5")
LABEL_KEY = "com.monstereat.observability.loki-delete-test"


def run(command: list[str], *, capture: bool = True, check: bool = True) -> str:
    result = subprocess.run(command, text=True, capture_output=capture, check=False)
    if check and result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {command[0]} {command[1]}")
    return result.stdout.strip() if capture else ""


def request_json(url: str, method: str = "GET", payload: dict | None = None) -> tuple[int, object | None]:
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {} if body is None else {"Content-Type": "application/json"}
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            raw = response.read()
            if not raw:
                return response.status, None
            try:
                return response.status, json.loads(raw)
            except json.JSONDecodeError:
                return response.status, raw.decode("utf-8", errors="replace")
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            detail = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            detail = raw.decode("utf-8", errors="replace") if raw else None
        return error.code, detail
    except OSError:
        return 0, None


def wait_ready(base_url: str, container: str) -> None:
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        status, _ = request_json(f"{base_url}/ready")
        if status == 200:
            return
        state = subprocess.run(
            ["docker", "inspect", "--format", "{{.State.Status}}", container],
            text=True,
            capture_output=True,
            check=False,
        )
        if state.returncode == 0 and state.stdout.strip() not in ("running", "restarting"):
            logs = subprocess.run(
                ["docker", "logs", "--tail", "100", container],
                text=True,
                capture_output=True,
                check=False,
            ).stdout.strip()
            raise RuntimeError(
                f"Isolated Loki container exited during startup (state={state.stdout.strip()}); "
                f"last logs:\n{logs}"
            )
        time.sleep(1)
    raise RuntimeError(f"Isolated Loki container did not become ready: {container}")


def query(base_url: str, selector: str, start_ns: int, end_ns: int) -> list[dict]:
    params = urllib.parse.urlencode(
        {
            "query": selector,
            "start": str(start_ns),
            "end": str(end_ns),
            "limit": "100",
            "direction": "backward",
        }
    )
    status, response = request_json(f"{base_url}/loki/api/v1/query_range?{params}")
    if status != 200 or not isinstance(response, dict):
        raise RuntimeError(f"Isolated Loki query failed with HTTP {status}")
    data = response.get("data", {})
    return data.get("result", []) if isinstance(data, dict) else []


def flushed_chunk_count(base_url: str) -> float:
    status, body = request_json(f"{base_url}/metrics")
    if status != 200 or not isinstance(body, str):
        raise RuntimeError(f"Isolated Loki metrics endpoint failed with HTTP {status}")
    values = []
    for line in body.splitlines():
        if line.startswith("loki_ingester_chunks_flushed_total") and "#" not in line:
            try:
                values.append(float(line.rsplit(None, 1)[1]))
            except (IndexError, ValueError):
                continue
    return sum(values)


def start_isolated_loki(container: str, run_id: str, config_path: Path, storage_dir: Path) -> str:
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
    wait_ready(base_url, container)
    return base_url


def restart_isolated_loki(container: str, run_id: str, config_path: Path, storage_dir: Path) -> str:
    label = subprocess.run(
        ["docker", "inspect", "--format", f"{{{{ index .Config.Labels \"{LABEL_KEY}\" }}}}", container],
        text=True,
        capture_output=True,
        check=False,
    )
    if label.returncode != 0 or label.stdout.strip() != run_id:
        raise RuntimeError(f"Refusing to restart a container not owned by this harness: {container}")
    run(["docker", "stop", "--time", "30", container])
    run(["docker", "rm", container])
    return start_isolated_loki(container, run_id, config_path, storage_dir)


def fixture_chunk_objects(storage_dir: Path) -> set[Path]:
    tenant_dir = storage_dir / "chunks" / "fake"
    return {path for path in tenant_dir.rglob("*") if path.is_file()} if tenant_dir.exists() else set()


def delete_requests(base_url: str) -> list[dict]:
    status, response = request_json(f"{base_url}/loki/api/v1/delete")
    if status != 200:
        raise RuntimeError(f"Isolated Loki delete-request list failed with HTTP {status}")
    if isinstance(response, list):
        return response
    if isinstance(response, dict) and isinstance(response.get("data"), list):
        return response["data"]
    raise RuntimeError("Isolated Loki returned an unexpected delete-request list")


def request_time_ns(value: object) -> int | None:
    if isinstance(value, (int, float)):
        numeric = float(value)
        if abs(numeric) >= 1_000_000_000_000:
            return int(numeric * 1_000_000)
        return int(numeric * 1_000_000_000)
    if isinstance(value, str):
        try:
            return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1_000_000_000)
        except ValueError:
            return None
    return None


def deletion_diagnostics(container: str, request_id: str) -> list[str]:
    result = subprocess.run(
        ["docker", "logs", container], text=True, capture_output=True, check=False
    )
    events = []
    request_context = []
    for line in (result.stdout + result.stderr).splitlines():
        lower = line.lower()
        if request_id in line or "no chunks to retain" in lower or "delete request" in lower:
            request_context.append(line)
        timestamp = re.search(r"\bts=([^ ]+)", line)
        message = re.search(r'\bmsg="([^"]+)"', line)
        deleted = re.search(r"\bdeleted_lines=([0-9]+)", line)
        if "delete" in lower or "deletion" in lower or "marker" in lower:
            events.append(
                "compactor event "
                + (timestamp.group(1) if timestamp else "time=unknown")
                + ": "
                + (message.group(1) if message else "message unavailable")
                + (f"; deleted_lines={deleted.group(1)}" if deleted else "")
            )
    return (events[-40:] + ["relevant log: " + line for line in request_context[-20:]])


def deletion_metric_diagnostics(base_url: str) -> list[str]:
    status, body = request_json(f"{base_url}/metrics")
    if status != 200 or not isinstance(body, str):
        return [f"metrics unavailable: HTTP {status}"]
    terms = ("delete_requests", "deleted_lines", "marker")
    return [line for line in body.splitlines()
            if not line.startswith("#") and any(term in line for term in terms)]


def main() -> None:
    run_id = uuid.uuid4().hex
    container = f"monitor-loki-delete-e2e-{run_id[:12]}"
    temp_dir = Path(tempfile.mkdtemp(prefix="monitor-loki-delete-e2e-"))
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
        prefix: delete_test_index_
        period: 24h
storage_config:
  tsdb_shipper:
    active_index_directory: /tmp/loki-tsdb-active
    cache_location: /tmp/loki-tsdb-cache
ingester:
  chunk_idle_period: 1s
  max_chunk_age: 2s
  flush_check_period: 1s
compactor:
  working_directory: /loki/compactor
  compaction_interval: 1s
  retention_enabled: true
  retention_delete_delay: 0s
  delete_request_store: filesystem
  delete_request_cancel_period: 0s
limits_config:
  allow_structured_metadata: true
  deletion_mode: filter-and-delete
  retention_period: 168h
  reject_old_samples_max_age: 168h
""",
        encoding="utf-8",
    )

    started = False
    try:
        existing = subprocess.run(
            ["docker", "inspect", container], text=True, capture_output=True, check=False
        )
        if existing.returncode == 0:
            raise RuntimeError(f"Refusing to reuse an existing container name: {container}")

        base_url = start_isolated_loki(container, run_id, config_path, storage_dir)
        started = True

        flushed_before = flushed_chunk_count(base_url)
        # Use a prior daily TSDB table so the compactor is not racing the active table.
        event_ns = time.time_ns() - 48 * 60 * 60 * 1_000_000_000 - 120_000_000_000
        selector = f'{{service_name="monitor-delete-harness",harness_run="{run_id}"}}'
        line = f"synthetic Loki deletion verification {run_id}"
        push_status, _ = request_json(
            f"{base_url}/loki/api/v1/push",
            method="POST",
            payload={
                "streams": [
                    {
                        "stream": {
                            "service_name": "monitor-delete-harness",
                            "harness_run": run_id,
                        },
                        "values": [[str(event_ns), line]],
                    }
                ]
            },
        )
        if push_status != 204:
            raise RuntimeError(f"Isolated Loki synthetic push failed with HTTP {push_status}")

        start_ns = event_ns - 30_000_000_000
        end_ns = event_ns + 30_000_000_000
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            rows = query(base_url, selector, start_ns, end_ns)
            if any(value[1] == line for stream in rows for value in stream.get("values", [])):
                break
            time.sleep(1)
        else:
            raise RuntimeError("Synthetic Loki line was not queryable before deletion")

        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if flushed_chunk_count(base_url) > flushed_before:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Loki did not flush the synthetic chunk before deletion")
        if not any(value[1] == line for stream in query(base_url, selector, start_ns, end_ns)
                   for value in stream.get("values", [])):
            raise RuntimeError("Synthetic Loki line was not queryable after its chunk flush")

        flush_status, flush_detail = request_json(f"{base_url}/flush", method="POST")
        if flush_status != 204:
            raise RuntimeError(f"Isolated Loki explicit flush failed with HTTP {flush_status}: {flush_detail}")

        # A flushed chunk can still be served from this ingester's in-memory state. Restart
        # the disposable process, preserving only its mounted storage, to make the query
        # below prove that the fixture is present in the persisted TSDB index and chunks.
        base_url = restart_isolated_loki(container, run_id, config_path, storage_dir)
        if not any(value[1] == line for stream in query(base_url, selector, start_ns, end_ns)
                   for value in stream.get("values", [])):
            raise RuntimeError("Synthetic Loki line was not queryable from persisted storage after restart")
        chunk_objects_before = fixture_chunk_objects(storage_dir)
        if not chunk_objects_before:
            raise RuntimeError("No persisted synthetic chunk object was found before deletion")

        start = datetime.fromtimestamp(start_ns / 1_000_000_000, timezone.utc).isoformat().replace("+00:00", "Z")
        end = datetime.fromtimestamp(end_ns / 1_000_000_000, timezone.utc).isoformat().replace("+00:00", "Z")
        delete_params = urllib.parse.urlencode({"query": selector, "start": start, "end": end})
        delete_status, delete_detail = request_json(
            f"{base_url}/loki/api/v1/delete?{delete_params}", method="POST"
        )
        if delete_status not in (200, 204):
            raise RuntimeError(
                f"Isolated Loki delete submission failed with HTTP {delete_status}: {delete_detail}"
            )

        requests = delete_requests(base_url)
        request_id = next(
            (entry.get("request_id") for entry in requests if entry.get("query") == selector),
            None,
        )
        if not request_id:
            raise RuntimeError("Loki accepted the request but did not list its request id")

        deadline = time.monotonic() + 240
        status = "unknown"
        completed_request: dict | None = None
        while time.monotonic() < deadline:
            matching = next((entry for entry in delete_requests(base_url)
                             if entry.get("request_id") == request_id), None)
            status = str(matching.get("status", "unknown")) if matching else "missing"
            if status.lower() == "processed":
                completed_request = matching
                break
            time.sleep(2)
        else:
            raise RuntimeError(f"Loki delete request did not reach processed (last status: {status})")

        # Loki writes a marker when it removes the index entry; its sweeper scans markers
        # once per minute. Restarting this isolated instance clears in-process query/index
        # caches and lets the fresh sweeper consume the durable marker immediately.
        base_url = restart_isolated_loki(container, run_id, config_path, storage_dir)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline and any(path.exists() for path in chunk_objects_before):
            time.sleep(1)
        remaining = query(base_url, selector, start_ns, end_ns)
        if any(value[1] == line for stream in remaining for value in stream.get("values", [])):
            request_start = request_time_ns((completed_request or {}).get("start_time", (completed_request or {}).get("start")))
            request_end = request_time_ns((completed_request or {}).get("end_time", (completed_request or {}).get("end")))
            diagnostic = deletion_diagnostics(container, request_id)
            raise RuntimeError(
                "Processed delete request still returned the synthetic log line after isolated restart; "
                f"request_range={((completed_request or {}).get('start_time') or (completed_request or {}).get('start'))}.."
                f"{((completed_request or {}).get('end_time') or (completed_request or {}).get('end'))}; "
                f"request_status={(completed_request or {}).get('status', status)}; "
                f"event_ns={event_ns}; "
                f"request_start_covers_event={request_start is not None and request_start <= event_ns}, "
                f"request_end_covers_event={request_end is not None and request_end >= event_ns}; "
                + " | ".join(diagnostic + deletion_metric_diagnostics(base_url))
            )

        remaining_chunk_objects = sorted(str(path) for path in chunk_objects_before if path.exists())
        if remaining_chunk_objects:
            raise RuntimeError(
                "Post-delete query was empty but persisted synthetic chunk objects remain: "
                + ", ".join(remaining_chunk_objects)
            )

        print("PASS isolated Loki deletion: synthetic line was queryable from persisted storage before delete")
        print(f"PASS delete request reached {status}; query returned no line and persisted chunk object was removed")
        print("PASS isolation: temporary Loki container, config, and storage were independent of shared services")
    finally:
        if started:
            label = subprocess.run(
                ["docker", "inspect", "--format", f"{{{{ index .Config.Labels \"{LABEL_KEY}\" }}}}", container],
                text=True,
                capture_output=True,
                check=False,
            )
            if label.returncode == 0 and label.stdout.strip() == run_id:
                subprocess.run(["docker", "rm", "--force", container], check=False, capture_output=True)
        shutil.rmtree(temp_dir, ignore_errors=True)


if __name__ == "__main__":
    main()
