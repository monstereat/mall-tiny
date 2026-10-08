#!/usr/bin/env python3
"""Run production analytics SQL against synthetic inline tables; no business data is read or written."""

import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import json


def main():
    workspace = Path(__file__).resolve().parents[3]
    environment = os.environ.copy()
    result = subprocess.run(["docker", "exec", "monitor-platform-clickhouse-1", "printenv", "CLICKHOUSE_PASSWORD"],
                            capture_output=True, text=True, check=True)
    environment["CLICKHOUSE_PASSWORD"] = result.stdout.rstrip("\n")
    state = json.loads(subprocess.run(["docker", "inspect", "monitor-platform-clickhouse-1"],
                                      capture_output=True, text=True, check=True).stdout)[0]
    network = next(name for name, item in state["NetworkSettings"]["Networks"].items()
                   if "clickhouse" in (item.get("Aliases") or []))
    report = workspace / "target/surefire-reports/TEST-com.macro.mall.tiny.modules.monitor.service.MonitorPerformanceSamplesTest.xml"
    classpath = next(item.get("value") for item in ET.parse(report).getroot().findall("./properties/property")
                     if item.get("name") == "surefire.test.class.path")
    result = subprocess.run([
        "docker", "run", "--rm", "--network", network, "--env", "CLICKHOUSE_PASSWORD",
        "-v", f"{workspace}:/workspace", "-v", f"{Path.home() / '.m2'}:/root/.m2", "-w", "/workspace",
        "maven:3.9.11-eclipse-temurin-17", "java", "-cp", classpath,
        "com.macro.mall.tiny.modules.monitor.service.MonitorAnalyticsStorageHarness"
    ], env=environment, capture_output=True, text=True, timeout=120)
    for line in result.stdout.splitlines():
        if line.startswith("ANALYTICS_STORAGE_ACCEPTANCE="):
            print(line)
    if result.returncode:
        raise RuntimeError("Synthetic analytics SQL acceptance failed")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("ANALYTICS_STORAGE_ACCEPTANCE=failed")
        raise SystemExit(1)
