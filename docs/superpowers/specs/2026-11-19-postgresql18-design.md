# PostgreSQL 18 migration

## Intent and authorization

The user selected PostgreSQL, search, authentication, then framework modernization (2 → 3 → 1 → 4). This design implements the first phase of the proposed PostgreSQL 14 → 18 migration: validate imports, queries, saved lists, backup/restore, and performance before changing CI and deployment guidance. Commit and push to the fork remain authorized. Later phases have separate plans.

## Approach

Use fresh PostgreSQL 18 databases for the existing Java and live-client suites, plus a logical dump/restore rehearsal from PostgreSQL 14 fixture databases. Logical restore is the chosen reference migration because it creates independent target storage and exercises schema, data, indexes, sequences, ownership, and large objects. An in-place data-directory swap is invalid across major versions. `pg_upgrade` can suit large operator databases but requires a separate rehearsal with their extensions, binaries, locale and checksum settings.

Keep the existing Java 8/21 matrix during this phase. Run the database-dependent suites sequentially locally, using isolated containers and disposable fixture databases. Reuse build caches without modifying unrelated services or operator data. Establish actual pilot timings before broader tests; do not infer production performance from tiny fixtures.

## Deliverables and acceptance

- PostgreSQL 18 is the default CI database, with a matching major-version client for backup/restore verification. SCRAM password authentication remains enabled.
- Fresh schema/model generation, database COPY, objectstore queries and optimizer behavior, provenance Unicode, profile/list operations, bio imports and postprocessing pass the existing suites.
- A fixture migration preserves the logical contents of every application table, sequences and PostgreSQL large objects; a controlled mismatch proves the comparison detects lost data.
- The restored mine starts and passes the JavaScript/Python live clients and registration/OAuth regressions. Anonymous and authenticated list ownership remains correct after restart.
- Representative query outputs are identical and timings/plans are recorded on both servers under comparable settings. These results are compatibility evidence, not a production-scale benchmark.
- Operator guidance covers paired mine/userprofile backups while writes are stopped, target roles/extensions/locale, matching client tools, restore errors, statistics, cutover, and rollback before accepting new writes. It records PostgreSQL 18 Docker's changed data-volume path.
- Any actual compatibility defect receives a failing regression before its fix. Configuration-only changes are validated by real database suites, not tests that merely repeat version strings.
- Aislop findings follow the user's severity rules; existing intentional false positives are explained, and no rule is weakened.
- Fresh branch review and hosted CI cover the final code before integration into the fork's beta branch.

## Boundaries

No operator database is migrated or deleted. Existing PostgreSQL 14 data directories are never opened with PostgreSQL 18. The migration rehearsal is confined to task-owned fixtures. Search, Java compatibility policy changes, browser login changes and framework replacement follow this phase. Deployment repositories outside this checkout are documented, not silently edited.

## Known compatibility checks

PostgreSQL 15 changes default public-schema ownership/privileges; create fixture databases with the intended application owner. PostgreSQL 18 enables data checksums by default and changes some COPY/optimizer behavior. Preserve large-object metadata used by InterMine, and verify sequence state as well as rows. Compare application results independently of query plans and timing.

Sources: [PostgreSQL version policy](https://www.postgresql.org/support/versioning/), [PostgreSQL 18 migration notes](https://www.postgresql.org/docs/18/release-18.html), [official Docker image](https://hub.docker.com/_/postgres).
