# Measuring query performance

Use the API replay tool to establish timings and result fingerprints, then pair it
with PostgreSQL counters to locate expensive work. The first
[YeastMine baseline](query-performance-validation-2026-11-25.md) is recorded; no
application cache, index or query behavior was changed by this phase.

## Replay a bounded workload

Requires Node 22 or later; no npm dependencies. From the repository root:

```sh
node config/performance/query-benchmark.cjs \
  --service https://yeastmine.alliancegenome.org/yeastmine/service/ \
  --workload config/performance/workloads/yeastmine.json \
  --repeat 5 --timeout-ms 15000 --max-bytes 4000000 \
  --output yeastmine-before.json

node config/performance/query-benchmark.cjs \
  --service https://yeastmine.alliancegenome.org/yeastmine/service/ \
  --workload config/performance/workloads/yeastmine.json \
  --repeat 5 --timeout-ms 15000 --max-bytes 4000000 \
  --baseline yeastmine-before.json --output yeastmine-after.json
```

Each query has one initial observation followed by `repeat` sequential samples.
The example makes 30 query requests plus two metadata requests. Start with
`--repeat 1` when testing a new target. The YeastMine workload requests at most
100 rows per query. Keep production runs bounded and coordinate any larger load.

For authenticated queries, supply `INTERMINE_BENCHMARK_TOKEN` through the process
environment. Do not put tokens in URLs or workload files. Tokens go only in the
Authorization header; redirects are rejected. Reports omit tokens, XML queries,
returned rows and server error bodies. Files are created with mode 0600 and cannot
overwrite existing files. Workload files themselves contain query parameters and
optional expected rows; keep private workloads out of version control.

`--baseline` requires the same workload and model hash, then checks counts and
fingerprints. It permits comparison across service URLs so that staging can be
compared with a reference. Check the recorded service, API version and data release
before interpreting timings. Capture the mine's `/service/version/release` alongside
the report; a model hash does not identify a data release.

### What the numbers mean

- `headersMs`: request start through response headers, including network and server work.
- `transferMs`: reading the body after headers; `parseMs`: JSON parsing and shape checks.
- `totalMs`: request, transfer and parsing; excludes result fingerprint computation.
- `repeatedMs`: min, median, max and sample count. p95 is omitted (`null`) below
  20 repeats; with 20 repeats it is a descriptive order statistic, not an SLA estimate.
- `rowCount`: rows in this requested page, not the total matching database population.

The initial observation is **not a controlled cold-cache measurement**. Neither
tool clears application, database or operating-system caches. Grouped repeats favor
cache reuse and do not represent a concurrent user mix. Network timings cannot
separate query planning, execution, Java processing and caching.

Every repeat must retain the same values, duplicate multiplicity and, for ordered
workloads, row order. Specify a complete deterministic ordering for paginated queries.
`expectedChecked: true` means an explicit expected result was supplied; provenance
determines whether that expectation is independent. Other fingerprints establish
consistency only. A page containing 100 rows is intentionally bounded and cannot
establish completeness of a larger query. Unsafe JSON integers and decimal tokens
that change through numeric decoding fail rather than silently accepting rounded
values. Malformed UTF-8, inconsistent success envelopes, incorrect page offsets/models
and rows narrower than their advertised views are also rejected.

The Employee workload is a harness check for the preserved local test mine; it is
not a representative biological or production-scale dataset.

## Capture PostgreSQL evidence

Requires Python 3.10 or later, `psql`, and a PostgreSQL 15 or later target. Configure
libpq service/password files locally; use a read-only account with the necessary
statistics access. Keep credentials out of command arguments and reports.

```sh
python3 config/performance/pg-profile.py snapshot \
  --database 'service=yeastmine-profile' --output pg-before.json

# Run the bounded API workload during this interval.

python3 config/performance/pg-profile.py snapshot \
  --database 'service=yeastmine-profile' --output pg-after.json
python3 config/performance/pg-profile.py compare \
  pg-before.json pg-after.json --output pg-delta.json
```

The profiler executes fixed SELECTs with read-only transactions, statement/lock
timeouts and no password prompt. It captures table/index sizes and usage, selected
settings, and statement counters. It never installs extensions, changes settings,
resets counters or executes submitted workload SQL. Snapshots are multiple bounded
reads, not one atomic view of a busy database. Each read also returns database identity,
which must match the initial identity even through a load-balanced connection service.
Review host/schema/table identifiers
before sharing reports; SQL text and connection strings are excluded.

`pg_stat_statements` needs preloading and a server restart when first enabled, plus
the extension in the selected database. Only an operator should arrange that on an
appropriate staging instance or in an approved maintenance window. Full statement
identities require superuser or `pg_read_all_stats` access. Missing installation,
preloading or effective privileges is reported explicitly. Settings hidden from a
restricted statistics reader are omitted rather than requiring `pg_read_all_settings`.
Disabled tracking is reported as unavailable; missing representative statement text
invalidates a snapshot because measurement queries cannot be excluded reliably.
The recorded tracking settings describe the profiling session: application sessions
can override them, so verify the application's settings before interpreting zero work.
Planning counters are zero when
`pg_stat_statements.track_planning` is disabled; that does not mean planning is free.
See the [PostgreSQL 18 documentation](https://www.postgresql.org/docs/18/pgstatstatements.html).

Compare ranks interval execution cost, not lifetime totals. It rejects database
changes/restarts, statistics resets, evictions, disappeared statements and regressed
counters. Older extensions without `stats_since` cannot reliably detect an individual
statement reset if its counters regrow before the next snapshot; reports expose this
limitation. Keep reset operations outside the measurement window. Query IDs remain meaningful only within the same database context.
The profiler excludes its own queries using an SQL alias marker; leading comments
alone do not survive PostgreSQL's stored statement text reliably.

Other users' work in the same database contributes to the interval. Pair timing
windows carefully or use a staging copy. An operator can map an expensive query ID
back to SQL privately and inspect its plan there; `EXPLAIN ANALYZE` actually executes
the query, so do not run it indiscriminately on the live service.

## Choose the first improvement from evidence

InterMine already caches result batches and singleton objects in
`ObjectStoreInterMineImpl`, and optimized SQL in `OptimiserCache`. The optimizer
cache removes LIMIT/OFFSET from its lookup key. Some `CacheMap` entries use soft
references, so retention can change with garbage collection. Precomputed tables
are another existing optimization mechanism. Inspect these paths before adding a
second result cache.

| Observation | Next experiment |
| --- | --- |
| SQL execution dominates; poor estimates or excess scans | Inspect plans and statistics, then test a targeted index or statistics change on a staging copy. |
| Identical API requests repeatedly execute expensive SQL | Measure existing cache hits, misses, retention and invalidation before designing a bounded cache. |
| SQL is inexpensive but API latency remains high | Profile Java query translation, materialization/serialization and request overhead. |
| Identifier/text matching is slow | Check actual matching SQL and indexes before considering a text-specific extension. |
| Results are fast but biologically unhelpful | Extend independently curated biological cases and evaluate Solr ranking separately from relational query timing. |

A shared cache would need keys that account for mine/data version, user and list
permissions, query parameters, ordering and pagination, with invalidation on data
or list changes. No cache design is accepted solely because repeated requests are
faster. PostgreSQL extensions are candidates for specific measured problems, not a
general performance switch.

## Verify the tools

```sh
node --test config/performance/query-benchmark.test.cjs
python3 -m unittest discover -s config/performance -p 'test_*.py'
```

CI also provisions `intermine_performance_fixture` in disposable PostgreSQL 15 and 18
services and verifies that three workload calls produce exactly three counted calls,
with no profiler queries in the delta. It also checks restricted statistics roles,
non-inherited privileges and disabled tracking. The fixture SQL is guarded by that exact
database name. CI restarts only its ephemeral profiling services. Database migration checks remain
a separate job. Do not run fixture setup against an existing mine.
