"""Read PostgreSQL workload counters without changing database state."""

import argparse
import datetime
import json
import math
import os
import subprocess
from pathlib import Path

MARKER = "/* intermine-performance-profile */"
METRICS = (
    "calls",
    "totalExecMs",
    "totalPlanMs",
    "rows",
    "sharedHits",
    "sharedReads",
    "tempReads",
    "tempWrites",
)
DATABASE_SQL = """SELECT json_build_object(
    'name', current_database(),
    'oid', (SELECT oid FROM pg_database WHERE datname = current_database()),
    'serverAddress', inet_server_addr(), 'serverPort', inet_server_port(),
    'postmasterStartedAt', pg_postmaster_start_time(),
    'serverVersion', current_setting('server_version_num'),
    'statsReset', (SELECT stats_reset FROM pg_stat_database WHERE datname = current_database()),
    'canReadAllStats', (SELECT rolsuper FROM pg_roles WHERE rolname = current_user)
        OR pg_has_role(current_user, 'pg_read_all_stats', 'MEMBER'))"""
SETTINGS_SQL = """SELECT coalesce(json_agg(s ORDER BY name), '[]'::json) FROM (
    SELECT name, setting, unit FROM pg_settings WHERE name IN (
        'shared_buffers', 'work_mem', 'effective_cache_size', 'random_page_cost',
        'max_connections', 'track_io_timing', 'track_counts', 'compute_query_id',
        'shared_preload_libraries', 'log_min_duration_statement',
        'default_statistics_target', 'jit', 'max_parallel_workers_per_gather')) s"""
TABLES_SQL = """SELECT coalesce(json_agg(t ORDER BY "totalBytes" DESC), '[]'::json) FROM (
    SELECT schemaname AS schema, relname AS name,
        pg_total_relation_size(relid) AS "totalBytes",
        pg_indexes_size(relid) AS "indexBytes", n_live_tup AS "estimatedLiveRows",
        n_dead_tup AS "estimatedDeadRows", seq_scan AS "seqScans", idx_scan AS "indexScans",
        last_analyze AS "lastAnalyze", last_autoanalyze AS "lastAutoanalyze"
    FROM pg_stat_user_tables) t"""
INDEXES_SQL = """SELECT coalesce(json_agg(i ORDER BY bytes DESC), '[]'::json) FROM (
    SELECT schemaname AS schema, relname AS table, indexrelname AS name,
        pg_relation_size(indexrelid) AS bytes, idx_scan AS scans
    FROM pg_stat_user_indexes) i"""
EXTENSION_SQL = """SELECT json_build_object('schema', n.nspname, 'version', e.extversion)
    FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
    WHERE e.extname = 'pg_stat_statements'"""


def psql_command(database, sql):
    environment = os.environ.copy()
    environment["PGOPTIONS"] = (
        "-c default_transaction_read_only=on -c statement_timeout=15000 "
        "-c lock_timeout=2000 -c timezone=UTC"
    )
    environment["PGCONNECT_TIMEOUT"] = "10"
    environment["PGAPPNAME"] = "intermine-performance-profile"
    return [
        "psql",
        "-X",
        "-Atq",
        "--no-password",
        "-v",
        "ON_ERROR_STOP=1",
        "--dbname",
        database,
        "-c",
        MARKER
        + "\nSELECT profile_value FROM ("
        + sql
        + ") AS intermine_performance_probe(profile_value)",
    ], environment


def query_json(database, sql):
    command, environment = psql_command(database, sql)
    try:
        result = subprocess.run(
            command,
            env=environment,
            capture_output=True,
            text=True,
            timeout=25,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RuntimeError(
            "psql unavailable or timed out; check local connection configuration"
        ) from error
    if result.returncode:
        raise RuntimeError(
            "PostgreSQL profiling query failed; check connection and read privileges"
        )
    return json.loads(result.stdout) if result.stdout.strip() else None


def statement_snapshot(database, details, settings):
    extension = query_json(database, EXTENSION_SQL)
    if extension is None:
        return {"status": "not-installed"}
    preloaded = next(
        s["setting"] for s in settings if s["name"] == "shared_preload_libraries"
    )
    if "pg_stat_statements" not in preloaded:
        return {"status": "not-preloaded"}
    if not details.get("canReadAllStats"):
        return {"status": "insufficient-privileges"}
    schema = '"' + extension["schema"].replace('"', '""') + '"'
    info_sql = (
        "SELECT json_build_object('resetAt', stats_reset, 'dealloc', dealloc) "
        f"FROM {schema}.pg_stat_statements_info"
    )
    info = query_json(database, info_sql)
    rows = query_json(
        database,
        f"""SELECT coalesce(json_agg(s), '[]'::json) FROM (
        SELECT queryid::text AS "queryId", userid AS "userId", toplevel,
            stats_since AS "statsSince", calls, total_exec_time AS "totalExecMs",
            total_plan_time AS "totalPlanMs", rows, shared_blks_hit AS "sharedHits",
            shared_blks_read AS "sharedReads", temp_blks_read AS "tempReads",
            temp_blks_written AS "tempWrites"
        FROM {schema}.pg_stat_statements
        WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
            AND query NOT LIKE '%intermine_performance_probe%'
        ORDER BY userid, queryid, toplevel) s""",
    )
    if info != query_json(database, info_sql):
        raise ValueError(
            "Statement statistics reset or evicted entries while capturing snapshot"
        )
    return {
        "status": "available",
        "extensionVersion": extension["version"],
        **info,
        "rows": rows,
    }


def snapshot(database):
    details = query_json(database, DATABASE_SQL)
    if int(details["serverVersion"]) < 180000:
        raise ValueError("This profiler requires PostgreSQL 18 or later")
    settings = query_json(database, SETTINGS_SQL)
    return {
        "schemaVersion": 1,
        "capturedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "database": details,
        "settings": settings,
        "tables": query_json(database, TABLES_SQL),
        "indexes": query_json(database, INDEXES_SQL),
        "statements": statement_snapshot(database, details, settings),
    }


def statement_key(row):
    return row["userId"], row["queryId"], row["toplevel"]


def indexed_statements(statements):
    entries = {}
    for row in statements["rows"]:
        key = statement_key(row)
        if row["queryId"] is None or key in entries:
            raise ValueError("Missing or duplicate statement identity")
        for metric in METRICS:
            value = row[metric]
            if (
                not isinstance(value, (int, float))
                or not math.isfinite(value)
                or value < 0
            ):
                raise ValueError("Invalid statement counter")
        entries[key] = row
    return entries


def compare(before, after):
    if before.get("schemaVersion") != 1 or after.get("schemaVersion") != 1:
        raise ValueError("Unsupported snapshot format")
    if before["database"] != after["database"]:
        raise ValueError(
            "Database identity, restart, privileges or database statistics reset changed"
        )
    old, new = before["statements"], after["statements"]
    if old["status"] != "available" or new["status"] != "available":
        raise ValueError("Statement profiling is unavailable in one or both snapshots")
    if (old["resetAt"], old["dealloc"]) != (new["resetAt"], new["dealloc"]):
        raise ValueError(
            "Statement statistics reset or evicted entries during the interval"
        )
    previous, current = indexed_statements(old), indexed_statements(new)
    if not previous.keys() <= current.keys():
        raise ValueError("Statements disappeared during the interval")
    queries = []
    for key, row in current.items():
        prior = previous.get(key)
        if prior and prior.get("statsSince") != row.get("statsSince"):
            raise ValueError("An individual statement was reset during the interval")
        delta = {
            metric: row[metric] - (prior[metric] if prior else 0) for metric in METRICS
        }
        if any(value < 0 for value in delta.values()):
            raise ValueError("Statement counters regressed during the interval")
        if delta["calls"]:
            queries.append(
                {
                    "queryId": row["queryId"],
                    "userId": row["userId"],
                    "toplevel": row["toplevel"],
                    **delta,
                    "meanExecMs": delta["totalExecMs"] / delta["calls"],
                }
            )
    queries.sort(key=lambda row: row["totalExecMs"], reverse=True)
    return {
        "schemaVersion": 1,
        "from": before["capturedAt"],
        "to": after["capturedAt"],
        "database": after["database"],
        "queries": queries,
    }


def write_report(path, result):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as output:
        json.dump(result, output, indent=2)
        output.write("\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    capture = commands.add_parser("snapshot")
    capture.add_argument(
        "--database", required=True, help="database name or libpq service connection"
    )
    capture.add_argument("--output", required=True)
    delta = commands.add_parser("compare")
    delta.add_argument("before", type=Path)
    delta.add_argument("after", type=Path)
    delta.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        if args.command == "snapshot":
            result = snapshot(args.database)
        else:
            result = compare(
                json.loads(args.before.read_text()), json.loads(args.after.read_text())
            )
        write_report(args.output, result)
    except (ValueError, RuntimeError, OSError) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
