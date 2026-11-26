"""Verify profiling against an explicitly disposable PostgreSQL fixture."""

import importlib.util
import os
import subprocess
from pathlib import Path

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
print("Verified three workload calls with no profiler queries in the delta")
