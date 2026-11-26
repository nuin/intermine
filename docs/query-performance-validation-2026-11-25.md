# Query performance baseline — 2026-11-25

The first measurement phase adds bounded API replay and read-only PostgreSQL
profiling. It establishes a YeastMine baseline and regression checks; it does not
claim a production speedup or change caching, indexes or query semantics.

## YeastMine observations

Target: `https://yeastmine.alliancegenome.org/yeastmine/service/`, selected by the user.
The service reported API 35, model `genomic`, and release
`R64-5-1 (built 2026-11-20, GO 2026-07-26)`.

The pilot completed at 2026-11-25 21:57 UTC. A separate baseline completed at
2026-11-26 00:25 UTC (November 25 in America/Edmonton). Each query requested at most
100 rows with a 15-second timeout and 4 MB response cap. The pilot used one repeat;
the later run used five and compared every result with the pilot fingerprint.
Across both runs there were 40 sequential query requests, plus metadata requests.

| Query | Page rows | Pilot initial (ms) | Pilot repeat (ms) | Later median, 5 repeats (ms) | Later repeat range (ms) |
| --- | ---: | ---: | ---: | ---: | ---: |
| ACT1 lookup | 1 | 1901.33 | 171.95 | 178.67 | 156.50–306.71 |
| ACT1 GO annotations | 21 | 703.59 | 154.01 | 186.50 | 151.80–261.86 |
| ACT1 protein | 1 | 698.03 | 158.54 | 178.60 | 156.82–182.80 |
| Gene page, offset 0 | 100 | 7585.38 | 111.85 | 102.72 | 91.32–189.16 |
| Gene page, offset 1000 | 100 | 81.87 | 103.69 | 177.96 | 83.03–185.18 |

The large first-request gap is a profiling lead. It does not identify a cache miss:
server load, planning, initialization, database/OS caches and networking were not
controlled. No p95 is reported for five repeats. All sampled counts and fingerprints
matched across the two runs. ACT1's expected `YFL039C`/`ACT1` mapping came from
[SGD's YFL039C record](https://wiki.yeastgenome.org/index.php/YFL039C), independently
retrieved from the API under test but sharing SGD's underlying curation. The other
four queries establish consistency only, not annotation correctness or completeness.

Raw timings and hashes: [pilot](audits/query-performance-2026-11-25/yeastmine-pilot.json),
[baseline](audits/query-performance-2026-11-25/yeastmine-baseline.json),
[release and provenance](audits/query-performance-2026-11-25/provenance.json).

## Local checks and PostgreSQL proof

The preserved Employee test mine supplied four queries with 20 repeated observations
each. Counts were 2, 2, 100 and 32; repeated medians were 19.16, 15.40, 16.65 and
14.42 ms. Results matched the separate earlier pilot. Direct SQL over the same
fixture independently established the prefix and department-join expectations;
this tests the API/query translator against the stored data, not biological truth.
An incorrect `%` PathQuery wildcard was rejected by those expectations; changing
the workload to the supported `*` restored the expected rows.

The local 12 MB fixture is not a model of YeastMine's database or query distribution.
Its PostgreSQL 18.7 runtime did not preload `pg_stat_statements`; it was left intact.
A separate, disposable PostgreSQL 18.7 container provided the profiler integration
check. Fresh before/after snapshots recorded exactly three SELECT calls and three
returned aggregate rows, excluding all measurement queries. A real integration
failure first exposed that leading comments were insufficient to exclude profiler
SQL; the persistent alias marker fixed it.

Artifacts: [Employee pilot](audits/query-performance-2026-11-25/employee-pilot.json),
[Employee baseline](audits/query-performance-2026-11-25/employee-baseline.json),
[PostgreSQL before](audits/query-performance-2026-11-25/pg-before.json),
[after](audits/query-performance-2026-11-25/pg-after.json),
[delta](audits/query-performance-2026-11-25/pg-delta.json).

Validation before final review:

- 17 Node tests passed, including changed values/order/duplicates, malformed/failed
  responses, response limits, timeouts, redirects and credential isolation.
- Six Python tests passed, covering interval cost, resets/evictions/restarts,
  regressed/disappearing counters, unavailable statistics and read-only invocation.
- PostgreSQL integration passed with three measured reads and no profiler overhead.
- Existing JavaScript suites passed: eight browser/security tests and 15 bio UI tests.
- Aislop scored 100 with no errors or fixable warnings. Its single nonfixable
  `python-print-debug` warning is a false positive on the integration CLI's success
  message; the output is intentional and carries no data or credentials.

## Next evidence and retained work

The first optimization needs YeastMine's database-side evidence: version/settings,
table/index sizes, and statement snapshots around this workload. The HTTP endpoint
alone cannot reveal SQL execution cost or cache hit rates. Use the
[operator commands](query-performance.md#capture-postgresql-evidence) against an
existing profiled instance or an appropriate staging copy. If YeastMine runs an
older PostgreSQL version, adapt the profiler to that version's available fields
before using it. The later PostgreSQL 15 compatibility work is described below.

Then inspect the most expensive plan or instrument the existing cache, choose one
change, and replay the same workload with unchanged fingerprints. Broader biological
correctness, legacy UI retirement and release work remain in the
[development worklist](development-worklist.md).

Implementation decisions retained from the work ledger:

- Continued under the user's accepted measurement-first scope and fork-push
  authorization. Cost if misunderstood: a new tool interface might need revision.
- Used an isolated PostgreSQL fixture rather than restarting existing databases.
  Cost: this proves the profiler, not production behavior.
- Ran only bounded sequential reads on the user's selected YeastMine. Cost:
  results exclude concurrency and may include unrelated load and cache effects.
- Restored unrelated changes made by an over-broad formatter invocation; final
  changes contain only the performance tools, CI wiring, documentation and evidence.

## Final review and verification — 2026-11-26

One fresh review identified five important findings. One fix pass added failing
controls before correcting each behavior:

- Restricted statistics readers now work without `pg_read_all_settings`; ordinary
  and non-inheriting roles receive an explicit unavailable status.
- Incorrect response models/offsets, missing views, short rows and contradictory
  success/error fields fail even without independent expected rows.
- Decimal precision loss and invalid UTF-8 fail before fingerprinting; the same
  numeric guard applies to file-based expectations.
- Disabled statement tracking is explicit. Lost representative SQL text invalidates
  capture rather than silently omitting statements. Tracking settings and their
  session scope are recorded.
- Each captured section carries database identity in the same SQL statement;
  switching backends between connections invalidates the snapshot.

Final local checks: 32 Node tests and 10 Python tests passed. Live PostgreSQL checks
passed for the three-call delta, a statistics reader without settings access, an
ordinary role, a non-inheriting role and disabled tracking. A second bounded
verification on both mines passed the stricter checks and matched the earlier
fingerprints: [YeastMine](audits/query-performance-2026-11-25/yeastmine-verified.json)
and [Employee](audits/query-performance-2026-11-25/employee-verified.json).
The YeastMine verification added ten sequential query requests on November 26.

One minor finding remains deferred: reversed timestamps on otherwise unchanged idle
PostgreSQL snapshots are accepted. Supply the earlier snapshot first; this does not
affect the captured baseline. The intentional CLI status print remains the sole
Aislop warning (score 100, no errors or fixable warnings).

Review scope rulings: cache/index choices await database evidence; the four
consistency-only biological queries still need independent completeness expectations;
the reviewer used synthetic controls instead of repeating production traffic.
These limits avoid unsupported performance or biological claims, but leave those
decisions open. The implementer subsequently ran the bounded verification above.

The initial implementation passed all six [InterMine CI jobs](https://github.com/nuin/intermine/actions/runs/45732145952).
Review-fix commit `5a57f90a82` also passed all six
[InterMine CI jobs](https://github.com/nuin/intermine/actions/runs/45834628217).

## Deployment compatibility

The user subsequently identified their EC2 deployment and RDS database. Direct
read-only inventory confirmed PostgreSQL 15.17, requiring compatibility beyond the
original PostgreSQL 18 contract. The profiler now detects `stats_since` from the
extension's actual columns, uses the available counters on PostgreSQL 15, and
records the weaker reset-detection capability. On older extensions, a targeted
statement reset can escape detection if counters regrow before the next snapshot;
the comparison output carries that limitation.

Two new failing controls passed after the compatibility change (12 Python tests
total). Disposable PostgreSQL 15.6 and 18.7 integrations both passed; CI now checks
15.17 and 18.7 separately. The live database inventory and connection details remain
in ignored local build artifacts, with credentials kept in process memory. This
compatibility change does not upgrade or alter the deployed database.
