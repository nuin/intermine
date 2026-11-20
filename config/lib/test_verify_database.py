import copy
import unittest

from verify_database import compare_snapshots


class DatabaseComparisonTest(unittest.TestCase):
    def setUp(self):
        self.before = {
            "format_version": 2,
            "database": "source",
            "server_version": "14.25",
            "tables": {
                '"public"."employee"': {
                    "rows": 2,
                    "sha256": "a" * 64,
                    "owner": "test",
                    "kind": "r",
                    "columns": [["id", "integer", True, "", ""]],
                }
            },
            "sequences": {
                '"public"."serial"': {
                    "owner": "test",
                    "type": "bigint",
                    "start": 1,
                    "increment": 1,
                    "minimum": 1,
                    "maximum": 9223372036854775807,
                    "cache": 1,
                    "cycle": False,
                    "last_value": 2,
                    "is_called": True,
                }
            },
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
        self.after["format_version"] = 1
        with self.assertRaises(ValueError):
            compare_snapshots(self.before, self.after)

    def test_identically_incomplete_records_are_rejected(self):
        paths = [
            ("tables", '"public"."employee"'),
            ("sequences", '"public"."serial"'),
            ("large_objects",),
            ("large_objects", "pages"),
        ]
        for path in paths:
            record = self.before
            for key in path:
                record = record[key]
            for field in record:
                with self.subTest(path=path, field=field):
                    incomplete = copy.deepcopy(self.before)
                    target = incomplete
                    for key in path:
                        target = target[key]
                    del target[field]
                    with self.assertRaises(ValueError):
                        compare_snapshots(incomplete, incomplete)

    def test_empty_section_dictionaries_are_not_a_complete_snapshot(self):
        incomplete = {
            "format_version": 2,
            "tables": {},
            "sequences": {},
            "large_objects": {},
        }
        with self.assertRaises(ValueError):
            compare_snapshots(incomplete, incomplete)

    def test_genuinely_empty_database_is_valid(self):
        empty = copy.deepcopy(self.before)
        empty["tables"] = {}
        empty["sequences"] = {}
        empty["large_objects"] = {
            "metadata": [],
            "pages": {
                "rows": 0,
                "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            },
        }
        self.assertEqual([], compare_snapshots(empty, empty))

    def test_malformed_record_values_are_rejected(self):
        for field, value in [
            ("rows", -1),
            ("rows", True),
            ("sha256", "short"),
            ("columns", [["id"]]),
            ("owner", None),
        ]:
            with self.subTest(field=field):
                malformed = copy.deepcopy(self.before)
                malformed["tables"]['"public"."employee"'][field] = value
                with self.assertRaises(ValueError):
                    compare_snapshots(malformed, malformed)


if __name__ == "__main__":
    unittest.main()
