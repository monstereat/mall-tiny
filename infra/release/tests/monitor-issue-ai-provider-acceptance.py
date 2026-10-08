#!/usr/bin/env python3
"""Run the synthetic Issue AI service harness against the explicitly configured DeepSeek provider."""

import argparse
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--from-local-server", action="store_true",
                        help="Read AI configuration from the running local Server without printing values")
    args = parser.parse_args()
    workspace = Path(__file__).resolve().parents[3]
    runtime_env = os.environ.copy()
    names = ("MONITOR_AI_API_KEY", "MONITOR_AI_BASE_URL", "MONITOR_AI_MODEL")
    if args.from_local_server:
        for name in names:
            result = subprocess.run(
                ["docker", "exec", "monitor-platform-server-1", "printenv", name],
                capture_output=True, text=True, check=False)
            if result.returncode != 0:
                raise RuntimeError("Local Server AI configuration is unavailable")
            runtime_env[name] = result.stdout.rstrip("\n")
    if any(not runtime_env.get(name, "").strip() for name in names):
        raise RuntimeError("Provide all three MONITOR_AI environment settings")
    report = workspace / "target/surefire-reports/TEST-com.macro.mall.tiny.modules.monitor.service.MonitorIssueAiAnalysisServiceTest.xml"
    if not report.is_file():
        raise RuntimeError("Run MonitorIssueAiAnalysisServiceTest before provider acceptance")
    properties = ET.parse(report).getroot().findall("./properties/property")
    classpath = next((p.get("value") for p in properties if p.get("name") == "surefire.test.class.path"), None)
    if not classpath or "/workspace/target/test-classes" not in classpath:
        raise RuntimeError("Use the documented Docker Maven command to generate the classpath")
    command = ["docker", "run", "--rm", "-v", f"{workspace}:/workspace",
               "-v", f"{Path.home() / '.m2'}:/root/.m2", "-w", "/workspace"]
    for name in names:
        command.extend(["--env", name])
    command.extend(["maven:3.9.11-eclipse-temurin-17", "java", "-cp", classpath,
                    "com.macro.mall.tiny.modules.monitor.service.MonitorIssueAiProviderAcceptanceHarness"])
    result = subprocess.run(command, env=runtime_env, capture_output=True, text=True, timeout=180)
    # Emit only the harness's fixed result records. JVM/provider exception output is never forwarded.
    for line in result.stdout.splitlines():
        if line.startswith("AI_ACCEPTANCE "):
            print(line)
    if result.returncode != 0:
        raise RuntimeError("Synthetic Issue AI provider acceptance failed; no credentials or response body were printed")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("Synthetic Issue AI acceptance failed; check configuration and safe harness result records.", file=sys.stderr)
        sys.exit(1)
