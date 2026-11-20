"""Exercise migration snapshots against an explicitly supplied disposable database."""

import os
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

from verify_database import compare_snapshots, snapshot_database


class DatabaseSnapshotIntegrationTest(unittest.TestCase):
    def sql(self, statement):
        return subprocess.run(
            [
                "psql",
                "-X",
                "-Atq",
                "--no-password",
                "-v",
                "ON_ERROR_STOP=1",
                "--dbname",
                self.database,
                "-c",
                statement,
            ],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    def setUp(self):
        self.database = os.environ["INTERMINE_VERIFY_TEST_DB"]
        fixture = Path(__file__).resolve().parents[1] / "ci/migration-fixture.sql"
        self.sql(fixture.read_text())
        self.large_object = int(
            self.sql("SELECT id FROM migration_probe.large_objects")
        )

    def tearDown(self):
        self.sql(f"SELECT lo_unlink({self.large_object})")
        self.sql("DROP SCHEMA migration_probe CASCADE")

    def test_snapshots_preserve_sequence_and_ignore_physical_row_order(self):
        before = snapshot_database(self.database)
        table = before["tables"]['"migration_probe"."quoted""records"']
        self.assertEqual(3, table["rows"])
        self.assertEqual(64, len(table["sha256"]))
        sequence = before["sequences"]['"migration_probe"."counter"']
        self.assertEqual(73, sequence["last_value"])
        self.assertFalse(sequence["is_called"])
        self.sql(
            'CLUSTER migration_probe."quoted""records" USING "quoted""records_pkey"'
        )
        after = snapshot_database(self.database)
        self.assertEqual([], compare_snapshots(before, after))

    def test_changed_row_is_detected_without_a_count_change(self):
        before = snapshot_database(self.database)
        self.sql(
            'UPDATE migration_probe."quoted""records" SET label = \'changed\' WHERE id = 3'
        )
        after = snapshot_database(self.database)
        self.assertTrue(compare_snapshots(before, after))

    def test_changed_large_object_is_detected(self):
        before = snapshot_database(self.database)
        self.assertIn(
            self.large_object, [row[0] for row in before["large_objects"]["metadata"]]
        )
        self.sql(f"SELECT lo_put({self.large_object}, 0, decode('ff', 'hex'))")
        after = snapshot_database(self.database)
        self.assertTrue(compare_snapshots(before, after))

    def test_json_null_is_distinct_from_sql_null(self):
        self.sql(
            "CREATE TABLE migration_probe.json_values (value json); "
            "INSERT INTO migration_probe.json_values VALUES (NULL)"
        )
        before = snapshot_database(self.database)
        self.sql("UPDATE migration_probe.json_values SET value = 'null'::json")
        self.assertTrue(compare_snapshots(before, snapshot_database(self.database)))

    def test_array_lower_bounds_are_preserved(self):
        self.sql(
            "CREATE TABLE migration_probe.arrays (value integer[]); "
            "INSERT INTO migration_probe.arrays VALUES ('[0:1]={1,2}'::integer[])"
        )
        before = snapshot_database(self.database)
        self.sql("UPDATE migration_probe.arrays SET value = '[1:2]={1,2}'::integer[]")
        self.assertTrue(compare_snapshots(before, snapshot_database(self.database)))

    def test_row_security_cannot_silently_filter_snapshot(self):
        self.sql("CREATE ROLE migration_probe_reader")
        try:
            self.sql(
                "CREATE TABLE migration_probe.policy_rows (id integer); "
                "INSERT INTO migration_probe.policy_rows VALUES (1), (2); "
                "ALTER TABLE migration_probe.policy_rows ENABLE ROW LEVEL SECURITY; "
                "CREATE POLICY visible_rows ON migration_probe.policy_rows USING (id = 1); "
                "GRANT USAGE ON SCHEMA migration_probe TO migration_probe_reader; "
                "GRANT SELECT ON ALL TABLES IN SCHEMA migration_probe TO migration_probe_reader; "
                "GRANT SELECT ON ALL SEQUENCES IN SCHEMA migration_probe TO migration_probe_reader; "
                "GRANT SELECT ON pg_largeobject TO migration_probe_reader"
            )
            with (
                patch.dict(os.environ, {"PGOPTIONS": "-c role=migration_probe_reader"}),
                self.assertRaisesRegex(RuntimeError, "row-level security"),
            ):
                snapshot_database(self.database)
        finally:
            self.sql(
                "DROP OWNED BY migration_probe_reader; DROP ROLE migration_probe_reader"
            )


if __name__ == "__main__":
    unittest.main()
