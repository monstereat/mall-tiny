#!/usr/bin/env python3
"""Exercise the release workflow's remote deploy script with an isolated fake Docker CLI."""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import tarfile
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / ".github/workflows/monitor-release.yml"


DOCKER_STUB = r'''#!/usr/bin/env python3
import os
import re
import sys
from pathlib import Path

args = sys.argv[1:]
joined = " ".join(args)
log = Path(os.environ["HARNESS_LOG"])
mode = os.environ["HARNESS_MODE"]

def record(value):
    with log.open("a", encoding="utf-8") as stream:
        stream.write(value + "\n")

record("CALL " + joined)

if args[:1] == ["login"]:
    config = Path(os.environ["DOCKER_CONFIG"])
    config.mkdir(parents=True, exist_ok=True)
    (config / "config.json").write_text('{"auths":{"ghcr.io":{}}}', encoding="utf-8")
    sys.stdin.read()
    record("LOGIN_CONFIG_CREATED")
    raise SystemExit(0)

if args[:1] == ["inspect"]:
    container = args[-1]
    print("old-server:stable" if container == "container-server" else "old-admin:stable")
    raise SystemExit(0)

if args[:1] != ["compose"]:
    raise SystemExit(0)

if "ps" in args and "-q" in args:
    print("container-server" if "server" in args else "container-admin")
    raise SystemExit(0)

if "pull" in args:
    config = Path(os.environ["DOCKER_CONFIG"]) / "config.json"
    if not config.is_file():
        print("missing temporary Docker auth config", file=sys.stderr)
        raise SystemExit(31)
    record("PULL_AUTH_PRESENT")
    raise SystemExit(0)

if "exec" in args and "wget" in args:
    counter = Path(os.environ["HARNESS_HEALTH_COUNT"])
    count = int(counter.read_text() or "0") if counter.exists() else 0
    count += 1
    counter.write_text(str(count), encoding="utf-8")
    if mode == "health_failure" and count <= 60:
        raise SystemExit(1)
    print('{"status":"UP"}')
    raise SystemExit(0)

if "exec" in args and "mysql" in joined and "SELECT COUNT(*)" in joined:
    print("1")
    raise SystemExit(0)

if "exec" in args and "clickhouse-client" in joined and "schema_migration FINAL" in joined:
    print("1")
    raise SystemExit(0)

if "exec" in args and "mysql --protocol=tcp --user=root monitor_platform'" in joined and "-e" not in joined:
    payload = sys.stdin.read()
    if payload.strip():
        record("APPLY_MYSQL_MIGRATION")
    raise SystemExit(0)

if "exec" in args and "clickhouse-client" in joined and "--multiquery" in joined and "--query" not in joined:
    payload = sys.stdin.read()
    if payload.strip():
        record("APPLY_CLICKHOUSE_MIGRATION")
    raise SystemExit(0)

if "up" in args:
    rollback_file = next((part for part in args if part.endswith(".monitor-release.rollback.compose.yml")), None)
    if rollback_file:
        content = Path(rollback_file).read_text(encoding="utf-8")
        record("ROLLBACK_OVERRIDE " + content.replace("\n", " | "))
        raise SystemExit(0)
    if mode == "startup_failure":
        raise SystemExit(42)
    raise SystemExit(0)

raise SystemExit(0)
'''


def extract_remote_script() -> str:
    lines = WORKFLOW.read_text(encoding="utf-8").splitlines()
    start = next((i for i, line in enumerate(lines) if "<<'REMOTE'" in line), None)
    if start is None:
        raise RuntimeError("Could not find the workflow REMOTE heredoc")
    body: list[str] = []
    for line in lines[start + 1 :]:
        if line.strip() == "REMOTE":
            break
        body.append(line)
    else:
        raise RuntimeError("Could not find the workflow REMOTE heredoc terminator")
    nonempty = [line for line in body if line.strip()]
    indent = min(len(line) - len(line.lstrip()) for line in nonempty)
    script = "\n".join(line[indent:] if line.strip() else "" for line in body) + "\n"
    if "rollback_application()" not in script or "monitor.schema_migration" not in script:
        raise RuntimeError("Extracted script does not contain expected deploy safeguards")
    return script


def prepare_case(base: Path, mode: str, remote_script: str) -> tuple[Path, Path, Path]:
    case = base / mode
    deploy = case / "deploy"
    bundle = case / "bundle"
    bin_dir = case / "bin"
    deploy.mkdir(parents=True)
    (bundle / "sql/migrations").mkdir(parents=True)
    (bundle / "infra/clickhouse/migrations").mkdir(parents=True)
    bin_dir.mkdir(parents=True)

    (bundle / "docker-compose.deploy.yml").write_text("services: {}\n", encoding="utf-8")
    (bundle / "sql/migrations/V20261001_01__already_applied.sql").write_text(
        "SELECT 'should be skipped';\n", encoding="utf-8"
    )
    (bundle / "infra/clickhouse/migrations/V20261001_02__already_applied.sql").write_text(
        "SELECT 'should be skipped';\n", encoding="utf-8"
    )
    with tarfile.open(deploy / ".monitor-release-bundle.tar.gz", "w:gz") as archive:
        for path in bundle.rglob("*"):
            archive.add(path, arcname=path.relative_to(bundle))
    (deploy / ".monitor-release.compose.yml").write_text("services: {}\n", encoding="utf-8")

    docker = bin_dir / "docker"
    docker.write_text(DOCKER_STUB, encoding="utf-8")
    docker.chmod(0o755)
    sleep = bin_dir / "sleep"
    sleep.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    sleep.chmod(0o755)

    script = case / "remote-deploy.sh"
    script.write_text(remote_script, encoding="utf-8")
    log = case / "mock.log"
    (case / "health-count").write_text("0", encoding="utf-8")
    return deploy, bin_dir, log


def run_case(base: Path, mode: str, remote_script: str) -> tuple[int, str, str, Path]:
    deploy, bin_dir, log = prepare_case(base, mode, remote_script)
    auth_dir = deploy.parent / "temporary-docker-auth"
    health_count = deploy.parent / "health-count"
    env = os.environ.copy()
    env.update(
        {
            "PATH": f"{bin_dir}{os.pathsep}{env['PATH']}",
            "HARNESS_MODE": mode,
            "HARNESS_LOG": str(log),
            "HARNESS_HEALTH_COUNT": str(health_count),
            "DOCKER_CONFIG": str(auth_dir),
        }
    )
    login = subprocess.run(
        ["docker", "login", "ghcr.io", "--username", "mock", "--password-stdin"],
        input="mock-token\n",
        text=True,
        env=env,
        capture_output=True,
        check=False,
    )
    if login.returncode != 0:
        raise AssertionError(f"mock docker login failed: {login.stderr}")

    result = subprocess.run(
        ["bash", str(deploy.parent / "remote-deploy.sh"), str(deploy), str(auth_dir)],
        cwd=deploy,
        text=True,
        env=env,
        capture_output=True,
        check=False,
    )
    return result.returncode, result.stdout, result.stderr, log


def assert_case(base: Path, mode: str, remote_script: str) -> None:
    code, stdout, stderr, log_path = run_case(base, mode, remote_script)
    log = log_path.read_text(encoding="utf-8")
    auth_dir = log_path.parent / "temporary-docker-auth"
    rollback_file = log_path.parent / "deploy/.monitor-release.rollback.compose.yml"

    if auth_dir.exists():
        raise AssertionError(f"{mode}: temporary Docker auth directory was not cleaned")
    if rollback_file.exists():
        raise AssertionError(f"{mode}: rollback override was not cleaned")
    if "PULL_AUTH_PRESENT" not in log:
        raise AssertionError(f"{mode}: pull did not observe the temporary auth config")
    if "Skipping applied MySQL migration 20261001_01." not in stdout:
        raise AssertionError(f"{mode}: applied MySQL migration was not skipped\n{stdout}\n{stderr}")
    if "Skipping applied ClickHouse migration 20261001_02." not in stdout:
        raise AssertionError(f"{mode}: applied ClickHouse migration was not skipped\n{stdout}\n{stderr}")
    if "APPLY_MYSQL_MIGRATION" in log or "APPLY_CLICKHOUSE_MIGRATION" in log:
        raise AssertionError(f"{mode}: an already-applied migration was executed")

    if mode == "success":
        if code != 0 or "Server health check passed." not in stdout:
            raise AssertionError(f"success case failed ({code})\n{stdout}\n{stderr}")
        if "ROLLBACK_OVERRIDE" in log:
            raise AssertionError("success case unexpectedly rolled back")
    else:
        if code == 0:
            raise AssertionError(f"{mode}: injected deployment failure unexpectedly succeeded")
        rollback = next((line for line in log.splitlines() if line.startswith("ROLLBACK_OVERRIDE ")), "")
        if "old-server:stable" not in rollback or "old-admin:stable" not in rollback:
            raise AssertionError(f"{mode}: rollback did not restore old server/admin images\n{log}")
        if mode == "startup_failure" and "Server health check passed." in stdout:
            raise AssertionError("startup failure case unexpectedly passed the new image health check")
        if mode == "health_failure":
            count = int((log_path.parent / "health-count").read_text(encoding="utf-8"))
            if count != 61:
                raise AssertionError(f"health failure case expected 60 failed checks plus rollback health, got {count}")

    print(f"PASS {mode}: applied migrations skipped; temporary auth removed" +
          ("; prior app images restored" if mode != "success" else "; new app health passed"))


def main() -> None:
    remote_script = extract_remote_script()
    with tempfile.TemporaryDirectory(prefix="monitor-release-deploy-harness-") as temp:
        base = Path(temp)
        for mode in ("success", "startup_failure", "health_failure"):
            assert_case(base, mode, remote_script)
    print("All mocked monitor-release deploy harness checks passed; no Docker daemon, SSH, or registry was used.")


if __name__ == "__main__":
    main()
