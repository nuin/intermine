import copy
import unittest

from verify_database import compare_snapshots


class DatabaseComparisonTest(unittest.TestCase):
    def setUp(self):
        self.before = {
            "format_version": 1,
            "database": "source",
            "server_version": "14.25",
            "tables": {
                '"public"."employee"': {
                    "rows": 2,
                    "sha256": "a" * 64,
                    "owner": "test",
                    "columns": [["id", "integer", True, "", ""]],
                }
            },
            "sequences": {'"public"."serial"': {"last_value": 2, "is_called": True}},
            "large_objects": {
                "metadata": [[123, "test", None]],
                "pages": {"rows": 1, "sha256": "b" * 64},
            },
        }
        self.after = copy.deepcopy(self.before)

    def test_server_version_and_database_name_can_change(self):
        self.after["database"] = "restored"
        self.after["server_version"] = "18.7"
        self.assertEqual([], compare_snapshots(self.before, self.after))

    def test_missing_table_is_detected(self):
        self.after["tables"].clear()
        self.assertTrue(compare_snapshots(self.before, self.after))

    def test_changed_rows_are_detected_when_counts_match(self):
        self.after["tables"]['"public"."employee"']["sha256"] = "c" * 64
        self.assertTrue(compare_snapshots(self.before, self.after))

    def test_changed_table_owner_is_detected(self):
        self.after["tables"]['"public"."employee"']["owner"] = "postgres"
        self.assertTrue(compare_snapshots(self.before, self.after))

    def test_sequence_state_changes_are_detected(self):
        for field, value in [("last_value", 1), ("is_called", False)]:
            with self.subTest(field=field):
                changed = copy.deepcopy(self.after)
                changed["sequences"]['"public"."serial"'][field] = value
                self.assertTrue(compare_snapshots(self.before, changed))

    def test_large_object_changes_are_detected(self):
        self.after["large_objects"]["pages"]["sha256"] = "d" * 64
        self.assertTrue(compare_snapshots(self.before, self.after))
        self.after = copy.deepcopy(self.before)
        self.after["large_objects"]["metadata"] = []
        self.assertTrue(compare_snapshots(self.before, self.after))

    def test_incomplete_snapshot_is_rejected(self):
        for field in ["tables", "sequences", "large_objects"]:
            with self.subTest(field=field):
                incomplete = copy.deepcopy(self.before)
                del incomplete[field]
                with self.assertRaises(ValueError):
                    compare_snapshots(incomplete, incomplete)

    def test_unknown_snapshot_format_is_rejected(self):
        self.after["format_version"] = 2
        with self.assertRaises(ValueError):
            compare_snapshots(self.before, self.after)


if __name__ == "__main__":
    unittest.main()
