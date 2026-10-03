-- Isolated synthetic-only TTL contract check. The caller must pass a fresh,
-- unique --param_test_database identifier. This script drops it on success;
-- the caller should drop that exact identifier if an assertion fails.
CREATE DATABASE {test_database:Identifier};

CREATE TABLE {test_database:Identifier}.events
(
    event_time DateTime,
    event_id UInt64
)
ENGINE = MergeTree
ORDER BY (event_time, event_id)
TTL event_time + INTERVAL 30 DAY DELETE;

-- Pause merges only for this disposable table, so the pre-merge assertion is
-- deterministic. Start them before OPTIMIZE, which waits for the TTL merge.
SYSTEM STOP MERGES {test_database:Identifier}.events;
INSERT INTO {test_database:Identifier}.events VALUES (now() - INTERVAL 31 DAY, 1);
INSERT INTO {test_database:Identifier}.events VALUES (now() - INTERVAL 1 DAY, 2);

SELECT throwIf(countIf(event_id = 1) != 1 OR countIf(event_id = 2) != 1,
    'synthetic setup did not retain both rows before the forced TTL merge')
FROM {test_database:Identifier}.events;

SELECT throwIf(
    positionCaseInsensitive(create_table_query, 'toIntervalDay(30)') = 0,
    'synthetic event table is missing the 30-day delete TTL'
)
FROM system.tables
WHERE database = {test_database:String}
  AND name = 'events';

SYSTEM START MERGES {test_database:Identifier}.events;
OPTIMIZE TABLE {test_database:Identifier}.events FINAL;

SELECT throwIf(
    countIf(event_id = 1) != 0 OR countIf(event_id = 2) != 1,
    '30-day TTL did not remove the expired synthetic row while retaining recent data'
)
FROM {test_database:Identifier}.events;

DROP DATABASE {test_database:Identifier};
