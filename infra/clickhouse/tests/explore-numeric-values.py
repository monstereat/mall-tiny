#!/usr/bin/env python3
"""Check Explore's numeric value predicate with a read-only synthetic ClickHouse query."""

import json
import subprocess


def main():
    payloads = ['{"data":{"value":5}}', '{"data":{"value":-2.5}}', '{"data":{"value":0}}',
                '{"data":{"value":"12"}}', '{"data":{"value":null}}', '{"data":{"value":true}}',
                '{"data":{}}', '{"data":{"value":1e999}}']
    quoted = ",".join("'" + value + "'" for value in payloads)
    sql = "SELECT count() AS count,sum(value) AS sum,min(value) AS min,max(value) AS max FROM (" \
          "SELECT JSONExtractFloat(payload,'data','value') AS value FROM (SELECT arrayJoin([" + quoted + "]) AS payload) " \
          "WHERE JSONType(payload,'data','value') IN ('Int64','UInt64','Double') " \
          "AND isFinite(JSONExtractFloat(payload,'data','value'))) FORMAT JSONEachRow"
    result = subprocess.run([
        "docker", "exec", "-i", "monitor-platform-clickhouse-1", "sh", "-c",
        'exec clickhouse-client --password "$CLICKHOUSE_PASSWORD" --multiquery'
    ], input=sql, capture_output=True, text=True, timeout=30)
    if result.returncode or json.loads(result.stdout) != {"count": 3, "sum": 2.5, "min": -2.5, "max": 5}:
        raise RuntimeError("Synthetic numeric sample result mismatch")
    print("CLICKHOUSE_FINITE_NUMERIC_STORAGE_ACCEPTANCE=passed count=3 sum=2.5 min=-2.5 max=5")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("CLICKHOUSE_FINITE_NUMERIC_STORAGE_ACCEPTANCE=failed")
        raise SystemExit(1)
