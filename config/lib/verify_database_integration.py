"""Exercise migration snapshots against an explicitly supplied disposable database."""

import os
import subprocess
import unittest
from pathlib import Path

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


if __name__ == "__main__":
    unittest.main()
