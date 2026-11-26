import copy
import importlib.util
import json
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "pg_profile", Path(__file__).with_name("pg-profile.py")
)
profile = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(profile)


def snapshot(calls=2, milliseconds=10):
    return {
        "schemaVersion": 1,
        "capturedAt": "2026-11-25T00:00:00+00:00",
        "database": {
            "name": "test",
            "oid": 123,
            "serverAddress": "127.0.0.1",
            "serverPort": 5432,
            "postmasterStartedAt": "boot",
            "serverVersion": "180007",
            "statsReset": "database-reset",
        },
        "statements": {
            "status": "available",
            "resetAt": "reset",
            "dealloc": 0,
            "rows": [
                {
                    "queryId": "1234567890123456789",
                    "userId": 10,
                    "toplevel": True,
                    "calls": calls,
                    "totalExecMs": milliseconds,
                    "totalPlanMs": 1,
                    "rows": calls * 3,
                    "sharedHits": calls * 2,
                    "sharedReads": calls,
                    "tempReads": 0,
                    "tempWrites": 0,
                }
            ],
        },
    }


class ComparisonTests(unittest.TestCase):
    def test_ranks_interval_cost_not_lifetime_cost(self):
        before = snapshot()
        after = snapshot(5, 22)
        after["capturedAt"] = "2026-11-25T00:01:00+00:00"
        new = copy.deepcopy(after["statements"]["rows"][0])
        new.update(queryId="2", totalExecMs=20, calls=1)
        after["statements"]["rows"].append(new)
        result = profile.compare(before, after)
        self.assertEqual(
            [q["queryId"] for q in result["queries"]], ["2", "1234567890123456789"]
        )
        query = result["queries"][1]
        self.assertEqual(query["calls"], 3)
        self.assertEqual(query["totalExecMs"], 12)
        self.assertEqual(query["meanExecMs"], 4)
        self.assertNotIn("query", query)

    def test_rejects_resets_evictions_restarts_and_identity_changes(self):
        changes = [
            ("statements", "resetAt", "other"),
            ("statements", "dealloc", 1),
            ("database", "oid", 456),
            ("database", "postmasterStartedAt", "restarted"),
            ("database", "statsReset", "other"),
            ("database", "serverAddress", "other"),
        ]
        for section, key, value in changes:
            with self.subTest(key=key):
                before, after = snapshot(), snapshot(5, 22)
                after[section][key] = value
                with self.assertRaises(ValueError):
                    profile.compare(before, after)

    def test_rejects_regression_disappearing_queries_and_unavailable_stats(self):
        for after in [snapshot(1, 22), snapshot(5, 1), snapshot(5, 22)]:
            if (
                after["statements"]["rows"][0]["calls"] == 5
                and after["statements"]["rows"][0]["totalExecMs"] == 22
            ):
                after["statements"]["rows"] = []
            with self.assertRaises(ValueError):
                profile.compare(snapshot(), after)
        unavailable = snapshot()
        unavailable["statements"] = {"status": "not-installed"}
        with self.assertRaises(ValueError):
            profile.compare(unavailable, snapshot())

    def test_unchanged_queries_are_not_reported_as_work(self):
        self.assertEqual(profile.compare(snapshot(), snapshot())["queries"], [])

    def test_legacy_reset_detection_limit_is_preserved_in_comparisons(self):
        before, after = snapshot(), snapshot(5, 22)
        for report in [before, after]:
            report["statements"]["resetDetection"] = "global-and-counter-regression"
        result = profile.compare(before, after)
        self.assertEqual(result["resetDetection"], "global-and-counter-regression")
        self.assertTrue(result["limitations"])
        after["statements"]["resetDetection"] = "per-statement-start"
        with self.assertRaises(ValueError):
            profile.compare(before, after)


class TransportTests(unittest.TestCase):
    def test_postgres15_snapshot_uses_available_statement_columns(self):
        queries = []

        def read(_database, sql, _identity=None):
            queries.append(sql)
            if sql == profile.DATABASE_SQL:
                return {
                    "name": "fixture",
                    "serverVersion": "150017",
                    "canReadAllStats": True,
                }
            if sql == profile.SETTINGS_SQL:
                return [{"name": "pg_stat_statements.track", "setting": "top"}]
            if sql == profile.EXTENSION_SQL:
                return {
                    "schema": "public",
                    "version": "1.10",
                    "statsSinceAvailable": False,
                }
            if "pg_stat_statements_info" in sql:
                return {"resetAt": "reset", "dealloc": 0}
            return []

        with patch.object(profile, "query_json", read):
            report = profile.snapshot("fixture")
        self.assertEqual(
            report["statements"]["resetDetection"], "global-and-counter-regression"
        )
        statement_sql = next(q for q in queries if 'AS "queryId"' in q)
        self.assertNotIn("stats_since AS", statement_sql)
        self.assertIn('NULL::timestamptz AS "statsSince"', statement_sql)

    def test_each_section_rejects_a_different_backend_identity(self):
        details = {"name": "fixture", "postmasterStartedAt": "first"}
        result = subprocess.CompletedProcess(
            [],
            0,
            json.dumps(
                {"database": {**details, "postmasterStartedAt": "second"}, "value": []}
            ),
            "",
        )
        with (
            patch.object(profile.subprocess, "run", return_value=result),
            self.assertRaisesRegex(ValueError, "identity"),
        ):
            profile.query_json("service=fixture", profile.TABLES_SQL, details)

    def test_unavailable_preload_is_explicit_without_disclosing_server_errors(self):
        result = subprocess.CompletedProcess(
            [], 1, "", "ERROR:  55000\nprivate details"
        )
        with (
            patch.object(profile.subprocess, "run", return_value=result),
            self.assertRaises(profile.DatabaseQueryError) as raised,
        ):
            profile.query_json("fixture", "SELECT 1")
        self.assertEqual(raised.exception.sqlstate, "55000")
        self.assertNotIn("private", str(raised.exception))
        with patch.object(
            profile,
            "query_json",
            side_effect=[{"schema": "public", "version": "1.12"}, raised.exception],
        ):
            result = profile.statement_snapshot(
                "fixture", {"canReadAllStats": True}, []
            )
        self.assertEqual(result["status"], "not-preloaded")

    def test_hidden_preload_setting_does_not_require_read_all_settings(self):
        responses = [
            {"schema": "public", "version": "1.12"},
            {"resetAt": "reset", "dealloc": 0},
            [],
            {"resetAt": "reset", "dealloc": 0},
        ]
        with patch.object(profile, "query_json", side_effect=responses):
            result = profile.statement_snapshot(
                "fixture",
                {"canReadAllStats": True},
                [{"name": "pg_stat_statements.track", "setting": "top"}],
            )
        self.assertEqual(result["status"], "available")
        with patch.object(profile, "query_json", return_value=responses[0]):
            result = profile.statement_snapshot(
                "fixture", {"canReadAllStats": False}, []
            )
        self.assertEqual(result["status"], "insufficient-privileges")

    def test_disabled_tracking_and_missing_statement_text_are_not_empty_successes(self):
        for track, rows in [("none", []), ("top", [{"hasQueryText": False}])]:
            with self.subTest(track=track):
                responses = [
                    {"schema": "public", "version": "1.12"},
                    {"resetAt": "reset", "dealloc": 0},
                    rows,
                    {"resetAt": "reset", "dealloc": 0},
                ]
                settings = [
                    {"name": "pg_stat_statements.track", "setting": track},
                    {
                        "name": "shared_preload_libraries",
                        "setting": "pg_stat_statements",
                    },
                ]
                with patch.object(profile, "query_json", side_effect=responses):
                    if track == "none":
                        result = profile.statement_snapshot(
                            "fixture", {"canReadAllStats": True}, settings
                        )
                        self.assertEqual(result["status"], "tracking-disabled")
                    else:
                        with self.assertRaisesRegex(ValueError, "text"):
                            profile.statement_snapshot(
                                "fixture", {"canReadAllStats": True}, settings
                            )

    def test_psql_is_read_only_bounded_and_does_not_inherit_write_capable_options(self):
        with patch.dict(
            "os.environ", {"PGOPTIONS": "-c default_transaction_read_only=off"}
        ):
            command, environment = profile.psql_command("service=fixture", "SELECT 1")
        self.assertEqual(command[0], "psql")
        self.assertNotIn("default_transaction_read_only=off", environment["PGOPTIONS"])
        self.assertIn("default_transaction_read_only=on", environment["PGOPTIONS"])
        self.assertIn("statement_timeout=15000", environment["PGOPTIONS"])
        self.assertIn("--no-password", command)
        self.assertTrue(command[-1].startswith("/* intermine-performance-profile */"))

    def test_missing_extension_is_explicit_without_database_mutation(self):
        queries = []

        def read(_database, sql, _identity=None):
            queries.append(sql)
            if "pg_extension" in sql:
                return None
            if "pg_postmaster_start_time" in sql:
                return {"name": "fixture", "serverVersion": "180007"}
            return []

        with patch.object(profile, "query_json", read):
            result = profile.snapshot("service=fixture")
        self.assertEqual(result["statements"]["status"], "not-installed")
        self.assertTrue(all(sql.lstrip().startswith("SELECT") for sql in queries))
        self.assertFalse(
            any(
                "query," in sql or "pg_stat_statements_reset(" in sql for sql in queries
            )
        )


if __name__ == "__main__":
    unittest.main()
