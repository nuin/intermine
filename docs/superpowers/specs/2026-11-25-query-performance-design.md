# Query performance baseline

The user made efficiency and useful query results the primary objective and said
to start the recommended measurement-first phase. Continue inline under existing
implementation and personal-fork push authorization.

## Approach

Build a repeatable API workload and read-only PostgreSQL snapshots before changing
caches, indexes or query semantics. Adding a shared cache immediately would duplicate
existing result/optimiser caches without evidence of their hit rate. Index changes
without execution evidence could spend storage without helping the workload.

The first milestone produces runnable tools, regression checks and a local baseline.
The existing 12 MB Employee fixture proves the measurement tools work, not that they
predict MouseMine/AllianceMine performance. The user selected their deployment at
`https://yeastmine.alliancegenome.org/yeastmine` for a bounded biological workload;
comprehensive biological correctness and production-sized performance remain follow-ups.

## API replay

A dependency-free Node 22 command consumes a versioned JSON workload containing
named XML PathQueries, bounded pagination, and explicit ordered/unordered result
semantics. Use POST query/results with an optional token from the environment.
Validate every response, preserve duplicate rows in SHA-256 result fingerprints,
and fail if results change between observations or differ from supplied expectations
or a prior baseline. An initial sample is not labelled cold: server caches are not
flushed. Repeated requests are sequential with explicit sample counts and timeouts.

Report initial and repeated latency, transfer size, parsing time, result counts,
fingerprints, workload identity and mine/model metadata. Do not persist tokens,
raw returned rows, query text or server error bodies. Explicit expected results are
independent only when they come from fixture knowledge or another documented source;
agreement with the first sample establishes consistency, not biological correctness.

Bound request duration and response bytes. Never follow redirects carrying tokens.
API origins must use HTTPS or exact loopback HTTP and cannot contain credentials,
queries or fragments. The specified YeastMine pilot uses at most 100 rows per request,
five queries and sequential requests, followed by five repeats after a successful pilot.
No concurrent load tests or automatic scheduled runs against production services.

## PostgreSQL profiling

A Python standard-library command uses psql and read-only fixed SQL to snapshot
version, relevant settings, table/index sizes and usage, and pg_stat_statements
counters when available. Missing extension or missing privileges is explicit.
Never enable an extension, reset counters, restart a server or run user SQL.

Before/after comparison ranks deltas in execution time and calls, rejecting database
identity changes, stats resets, entry eviction and counter regression. Reports omit
SQL text and credentials. Query IDs identify candidates for an operator to inspect
privately with EXPLAIN (ANALYZE, BUFFERS) on a staging copy.

## Acceptance

- HTTP fixtures exercise changed values, changed order, duplicate loss, API errors,
  truncation, timeout, redirect and token/report isolation.
- PostgreSQL delta tests reject invalid comparison windows; a disposable profiled
  database demonstrates real counter changes. Existing runtime databases stay intact.
- Run a small pilot, then repeat only when the observed runtime supports it.
- Publish reproducible commands, sample counts, source identities and limitations.
- No speedup or scientific-correctness claim follows from fixture consistency alone.
