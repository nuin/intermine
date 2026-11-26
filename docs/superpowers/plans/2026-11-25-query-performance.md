# Query Performance Baseline Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline. Existing user
> authorization covers execution and personal-fork pushes. Steps use checkboxes.

**Goal:** Produce repeatable query timings with result checks and PostgreSQL evidence.
**Architecture:** A bounded HTTP replay command pairs with a fixed-query read-only
PostgreSQL snapshot/delta command. JSON artifacts support before/after comparison.
**Tech Stack:** Node 22 built-ins, Python 3 standard library, psql/PostgreSQL 15–18.
**Spec:** `docs/superpowers/specs/2026-11-25-query-performance-design.md`

## Global Constraints

- Keep existing runtime databases, lists, application behavior and queued work intact.
- Benchmark sequentially against the selected mine; no concurrent production load,
  cache flush or restart of an existing database.
- Missing statistics and incomplete correctness references must remain visible.
- Omit credentials, query text and result rows from saved benchmark artifacts.
- The Employee fixture is a harness check, not a production performance model.

## Review Focus

- A fast error/truncated response must not be counted as a successful query.
- Changed values/order or lost duplicates must fail comparisons.
- Credentials and private server responses must not enter errors or artifacts.
- Stats reset, eviction and database changes must invalidate delta comparisons.
- Initial versus repeated timing must not imply controlled cold/warm caches.

## Task 1: API workload runner

**Files:** `config/performance/query-benchmark.cjs`, corresponding `.test.cjs`,
`config/performance/workloads/testmodel.json`, CI unit-test wiring.
**Interfaces:** exported `fingerprint(rows, ordered)`, `summarize(samples)`,
`runBenchmark(options)` and CLI taking workload/service/output/repeat/timeout options.

- [x] Write failing HTTP fixture tests for successful samples, expected rows,
  baseline mismatch, changed values/order/duplicates, errors, timeouts, byte limits,
  redirects and credential isolation. Observe RED with `node --test`.
- [x] Implement the bounded runner and fixture workload. Observe GREEN; run Aislop.
- [x] Add the independent Node tests to existing CI, commit the runnable tool.

## Task 2: PostgreSQL evidence

**Files:** `config/performance/pg-profile.py`, `test_pg_profile.py`.
**Interfaces:** `snapshot(database)` returns versioned fixed-query evidence;
`compare(before, after)` returns ranked execution/call deltas or an explicit error.

- [x] Add failing tests for valid deltas, reset, eviction, identity and regression.
- [x] Implement read-only snapshots with optional pg_stat_statements and comparison;
  test with Python unittest and a disposable local PostgreSQL 18 profiler instance.
- [x] Add CI unit checks, run Aislop, commit.

## Task 3: Baseline and handoff

**Files:** `docs/query-performance.md`, `docs/audits/query-performance-2026-11-25/`,
development worklist, this plan and validation record.

- [x] Pilot API runner on the preserved test mine, then collect repeated samples
  and compare an independent rerun. Verify seeded expectations and negative controls.
- [x] Capture database evidence and demonstrate profiler deltas in an isolated
  instance. Record limitations, useful candidates and next data requirements.
- [x] Run one fresh review and one important-finding fix pass, then relevant CI,
  Aislop and final commands. Commit/push, update the worklist and report evidence.

Deployment follow-up authorized by the user: identify Multitenant EC2 and InterMine
RDS, adapt profiling to the observed PostgreSQL 15 version, and collect read-only
database evidence. Live extension installation remains a separate explicit decision.
Keep raw production inventory and connection details in ignored local artifacts.
Completed read-only inventory on PostgreSQL 15.17. User declined extension setup;
existing slow-log download was denied by AWS IAM. No production mutations performed.
