"""Verify profiling against an explicitly disposable PostgreSQL fixture."""

import importlib.util
import os
import subprocess
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "pg_profile", Path(__file__).with_name("pg-profile.py")
)
profile = importlib.util.module_from_spec(spec)
spec.loader.exec_module(profile)

database = os.environ["PGPROFILE_TEST_DATABASE"]
before = profile.snapshot(database)
if before["database"]["name"] != "intermine_performance_fixture":
    raise RuntimeError("Expected the dedicated intermine_performance_fixture database")
for value in [1, 2, 3]:
    command, environment = profile.psql_command(database, "SELECT 1")
    command[-1] = f"SELECT count(*) FROM benchmark_fixture WHERE id >= {value}"
    subprocess.run(
        command, env=environment, check=True, capture_output=True, timeout=25
    )
after = profile.snapshot(database)
delta = profile.compare(before, after)
assert len(delta["queries"]) == 1, "Profiling overhead contaminated the workload delta"
assert delta["queries"][0]["calls"] == 3, (
    "The three measured reads were not recorded exactly"
)
assert delta["queries"][0]["rows"] == 3
psql_command = profile.psql_command
for role, status in [
    ("intermine_profile_reader", "available"),
    ("intermine_profile_plain", "insufficient-privileges"),
    ("intermine_profile_noinherit", "insufficient-privileges"),
]:

    def role_command(database, sql, role=role):
        command, environment = psql_command(database, sql)
        return command[:-2] + ["-c", "SET ROLE " + role] + command[-2:], environment

    with patch.object(profile, "psql_command", role_command):
        observed = profile.snapshot(database)
    assert observed["statements"]["status"] == status, role
    assert not any(
        s["name"] == "shared_preload_libraries" for s in observed["settings"]
    )


def untracked_command(database, sql):
    command, environment = psql_command(database, sql)
    return command[:-2] + ["-c", "SET pg_stat_statements.track='none'"] + command[
        -2:
    ], environment


with patch.object(profile, "psql_command", untracked_command):
    untracked = profile.snapshot(database)
assert untracked["statements"]["status"] == "tracking-disabled"
print("Verified workload counters, restricted roles and disabled tracking")
