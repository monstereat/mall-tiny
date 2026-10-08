#!/usr/bin/env python3
"""Exercise the production numeric LogQL builder against isolated synthetic Loki storage."""

import importlib.util
import base64
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET


def main():
    sys.dont_write_bytecode = True
    workspace = Path(__file__).resolve().parents[3]
    spec = importlib.util.spec_from_file_location("loki_storage_helpers", Path(__file__).with_name("environment-release-e2e.py"))
    helpers = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helpers)
    run_id = uuid.uuid4().hex
    container = f"monitor-loki-numeric-{run_id[:12]}"
    project = f"numeric-{run_id[:12]}"
    temp_dir = Path(tempfile.mkdtemp(prefix="monitor-loki-numeric-"))
    storage = temp_dir / "storage"
    storage.mkdir(mode=0o700)
    config = temp_dir / "loki.yml"
    config.write_text("""auth_enabled: false
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
        prefix: numeric_test_index_
        period: 24h
storage_config:
  tsdb_shipper:
    active_index_directory: /tmp/loki-tsdb-active
    cache_location: /tmp/loki-tsdb-cache
limits_config:
  allow_structured_metadata: true
""")
    try:
        base_url = helpers.start_loki(container, run_id, config, storage)
        event_ns = time.time_ns() - 2_000_000_000
        bodies = [json.dumps({"value": value}) for value in [0, -2.5, 10, "3.25", "bad", None, True, "NaN", "Infinity"]]
        bodies.extend(['{}', '{"value":1e999}', 'not-json'])
        records = []
        for index, body in enumerate(bodies):
            records.append(record(event_ns + index, project, "production", body))
        records.extend([record(event_ns + 100, project, "staging", '{"value":1000}'),
                        record(event_ns + 101, project + "-other", "production", '{"value":9999}'),
                        record(event_ns + 102, project, "production", '{"value":1000}', user="other-user"),
                        record(event_ns + 103, project, "production", '{"value":1000}', tag="other-flow"),
                        record(event_ns + 104, project, "production", '{"value":1000}', release="other-release"),
                        record(event_ns + 105, project, "production", '{"value":1000}', severity="INFO")])
        status, _ = helpers.request(base_url, "/otlp/v1/logs", "POST", {"resourceLogs": [{
            "resource": {"attributes": [{"key": "service.name", "value": {"stringValue": "observability-platform"}}]},
            "scopeLogs": [{"scope": {"name": "numeric-storage-acceptance"}, "logRecords": records}]
        }]})
        if status not in (200, 204):
            raise RuntimeError(f"Synthetic Loki push failed with HTTP {status}")
        report = workspace / "target/surefire-reports/TEST-com.macro.mall.tiny.modules.monitor.service.MonitorQueryExploreNumericTest.xml"
        properties = ET.parse(report).getroot().findall("./properties/property")
        classpath = next(p.get("value") for p in properties if p.get("name") == "surefire.test.class.path")
        result = subprocess.run([
            "docker", "run", "--rm", "--network", f"container:{container}",
            "-v", f"{workspace}:/workspace", "-v", f"{Path.home() / '.m2'}:/root/.m2",
            "-w", "/workspace", "maven:3.9.11-eclipse-temurin-17", "java", "-cp", classpath,
            "com.macro.mall.tiny.modules.monitor.service.MonitorLogNumericStorageHarness",
            "http://127.0.0.1:3100", project, str(int(time.time()))
        ], capture_output=True, text=True, timeout=120)
        for line in result.stdout.splitlines():
            if line.startswith("LOKI_NUMERIC_STORAGE_ACCEPTANCE="):
                print(line)
        if result.returncode:
            raise RuntimeError("Numeric service storage acceptance failed")
    finally:
        # This run owns both the randomly named container and temporary synthetic storage.
        ownership = subprocess.run([
            "docker", "inspect", "--format", '{{index .Config.Labels "' + helpers.LABEL_KEY + '"}}', container
        ], capture_output=True, text=True)
        if ownership.returncode == 0 and ownership.stdout.strip() == run_id:
            subprocess.run(["docker", "rm", "--force", container], capture_output=True, text=True)
        shutil.rmtree(temp_dir)


def record(timestamp, project, environment, body, user="synthetic-user", tag="numeric",
           release="numeric-v1", severity="ERROR"):
    fields = {"monitor.project": project, "monitor.environment": environment,
              "monitor.release": release, "monitor.user_id": user,
              "monitor.tags": base64.urlsafe_b64encode(b"flow").decode().rstrip("=") + "." +
                              base64.urlsafe_b64encode(tag.encode()).decode().rstrip("=")}
    return {"timeUnixNano": str(timestamp), "body": {"stringValue": body}, "severityText": severity,
            "attributes": [{"key": key, "value": {"stringValue": value}} for key, value in fields.items()]}


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"LOKI_NUMERIC_STORAGE_ACCEPTANCE=failed category={type(error).__name__}")
        # This runner handles only its own synthetic, credential-free storage.
        print(str(error)[:2000])
        raise SystemExit(1)
