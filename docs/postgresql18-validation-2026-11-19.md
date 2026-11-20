# PostgreSQL 18 validation

This phase changes the CI database to PostgreSQL 18.7 and retains Java 8/21 coverage. Java 21 becomes the minimum in the following search/framework phase for the next major release. The operator procedure is [postgresql18-migration.md](postgresql18-migration.md).

## Fresh database compatibility

PostgreSQL 18 initially rejected schema creation by the non-superuser application role. Database-level grants did not confer `CREATE` access to the default `public` schema. Core, bio and testmine setup now assign each database to its intended application owner. SCRAM password authentication remains enabled.

The failure also exposed swallowed SQL exceptions in `MetadataManager.store` and `storeBinary`. Both now propagate failed writes after rollback. Regression tests reject writes with a real database constraint and verify that the original value survives. Large-object operations now bind metadata keys as parameters; a quoted-key regression covers creation, reading, replacement, truncation and deletion. Test streams are closed so they cannot retain transactions and block later schema changes.

Final validation, including the four connection-cleanup regressions:

| Suite | Java 21 | Java 8 |
| --- | ---: | ---: |
| Core | 2,153 | 2,153 |
| Bio | 92 | 92 |
| Importers | 59 | 59 |
| Postprocessor tests | 12 | 12 |
| Total passed | 2,316 | 2,316 |

All counts have zero failures, errors or skips. Local builds use Gradle 8.14.7 on Java 21.0.13; the Java 8 tests use its configured 8u512 test launcher. The hosted matrix also builds natively on Java 8. Java 21 core, bio, importer and postprocessor-test runs took 2m08s, 41s, 3m52s and 35s; Java 8 took 1m15s, 15s, 1m32s and 29s. Postprocessor libraries also passed build/Checkstyle/install. Bootstrap/comparator unit tests passed 21/21; browser regressions passed 15/15.

## Logical migration rehearsal

Task-owned Docker clusters ran PostgreSQL 14.25 and 18.7. A real testmine was populated on 14 using `loadsadata insertUserData`. Its mine has 29 physical tables and two sequences; its userprofile has 15 tables and two sequences, including 11 saved-list records. An explicit 8 KiB large-object fixture was added through a production-format metadata pointer because the initial fixture had no large objects.

With application writers stopped, PostgreSQL 18.7 `pg_dump -Fc` backed up both databases. `pg_restore --exit-on-error` restored them into fresh PostgreSQL 18 databases owned by the same non-superuser role. Both were analyzed. The before/after verifier matched every table's row count and deterministic content digest, column attributes and owner, every sequence definition/state, and the large object's owner/ACL and four data pages. This is fixture compatibility evidence, not a production migration.

An additional synthetic migration preserved quoted identifiers, Unicode text, NULL, bytea, numeric values, sequence state and a large object. Changing one row without changing its table's count made comparison exit nonzero; a fresh restore matched again. Six real PostgreSQL integration tests additionally cover physical row reordering, content changes, large-object changes, SQL NULL versus JSON null, array lower bounds and row-level security filtering. The synthetic migration and negative check now run in CI with PostgreSQL 18 backup/restore binaries.

## Query results and timings

Both restored/source fixtures were analyzed before measurement. Each query ran five times with `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` as the application role. Ordered result hashes match across servers. Full SQL and the last execution plans are in the [PostgreSQL 14 evidence](audits/postgresql14-queries-2026-11-19.json) and [PostgreSQL 18 evidence](audits/postgresql18-queries-2026-11-19.json).

| Query | Result rows | PG14 median execution | PG18 median execution |
| --- | ---: | ---: | ---: |
| Employee → department → company join | 130 | 0.189 ms | 0.208 ms |
| Department employee count/average age | 30 | 0.176 ms | 0.169 ms |
| Employee name prefix filter | 3 | 0.072 ms | 0.067 ms |

These tiny, warm fixtures establish matching results and working plans. Sub-millisecond differences are not production performance estimates. Saved-list membership is checked through the running application after its fixture-list upgrade; the initial imported lists are marked `NOT_CURRENT` and their membership table is empty.

## Live application and final gates

The restored mine runs on Java 21.0.13, Tomcat 9.0.124 and Solr 8.11.4. Its rebuilt WAR passes all 19 provider checks with 42,188 unique runtime classes. Search indexes were rebuilt against the restored database without reloading its source data.

- JavaScript live client: 405 passed in 8s; one upstream Unicode-query test remains pending.
- Python live client: 74 passed in 11.536s. The upstream registry live test remains a no-op, so this is not registry coverage.
- Registration/OAuth regressions: 4/4 passed, including private-list preservation, anonymous transfer and concurrent registration.
- All 11 saved-list records remain after the live clients. Eight valid lists expose 31 total memberships to the fixture user; the anonymous API exposes only the two public lists with nine memberships. The three intentionally noncurrent fixture lists remain stored.

The final client suites ran sequentially against the freshly rebuilt application after the metadata review fixes. Client revisions are the same as [release validation](release-validation-2026-11-19.md). After stopping and restarting the Tomcat container, and again after the final reviewed WAR was deployed, all eight authenticated lists and both public lists returned exactly the same ordered object IDs and sizes. All 11 saved-list database records remained. The review corrections are recorded below. The initial [CI run](https://github.com/nuin/intermine/actions/runs/44656076072) passed migration, browser and both core jobs; its Python web-service setup hit Maven Central HTTP 429 before tests, cancelling the paired JavaScript job. Final-code [CI run 44657477126](https://github.com/nuin/intermine/actions/runs/44657477126) passed all eight jobs: both core/bio Java runtimes, both live clients, browser regressions and migration. Its first attempt hit a Java 8 distribution checksum failure; the failed/cancelled jobs passed on retry with checksum verification unchanged. [Checkstyle](https://github.com/nuin/intermine/actions/runs/44657477023) also passed. Both runs validate code commit `bc7739079d7d5a7270c1e183cbb161ce912b54da`; the completion commit only updates documentation.

Aislop currently reports score 100, zero errors and zero fixable warnings. The remaining nonmechanical warning is the existing size of `MetadataManager` (746 lines).

## Review corrections

The fresh branch review identified five important issues. Snapshot format 2 uses PostgreSQL composite text output with JSON string escaping, so SQL NULL/JSON null and array lower bounds remain distinct. Read sessions set `row_security=off` and fail if policies would hide rows. Comparison validates complete table, sequence and large-object records, while allowing genuinely empty databases. The migration was recaptured and restored into another fresh pair; all 44 tables, four sequences and the large object match using the corrected encoding.

Operator command blocks now run in fail-fast subshells. Stubbed failed backup, database-creation and snapshot commands demonstrated that the old examples continued, and that the corrected examples stop before subsequent commands. Metadata transactions now preserve original errors and close connections even when rollback, statement closure or auto-commit restoration fails. Malformed large-object references produce checked errors and release their connections. Four new real-connection fault-injection regressions failed before these fixes and passed afterward; all 11 metadata tests passed. The corrected core passed 2,153/2,153 tests on both Java runtimes, and the final complete suites passed 2,316/2,316 each. Java 21 Checkstyle/install also passed for all roots.

One minor review finding is deferred: the CI corruption step accepts any nonzero comparison result, rather than a dedicated mismatch exit status. The preceding positive comparison and independent real-data mutation tests still exercise the expected comparison path.
