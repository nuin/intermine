# Migrating an InterMine database to PostgreSQL 18

This procedure uses a logical backup and restore into a separate cluster. It covers the mine and its userprofile database together. It does not modify an existing PostgreSQL 14 data directory or perform an operator's production migration automatically.

PostgreSQL 14 reached end of support on 12 November 2026. PostgreSQL 18 is supported through November 2030. Use the latest PostgreSQL 18 minor release; this change validates 18.7. The JDBC driver upgrade alone does not upgrade the database server. See the [version policy](https://www.postgresql.org/support/versioning/) and [PostgreSQL 18 migration notes](https://www.postgresql.org/docs/18/release-18.html).

## Rehearse with a copy first

Record the source versions, database owners, roles/grants, extensions, encoding, locale/collation provider, tablespaces and database sizes. Provision the required extensions on the target. Review the PostgreSQL 15–18 release notes for features used by the deployment. A production-sized rehearsal must establish downtime, storage requirements and query timings; the small repository fixtures establish compatibility, not production performance.

For the official Docker image, PostgreSQL 18 uses `/var/lib/postgresql/18/docker` and mounts its persistent volume at `/var/lib/postgresql`. PostgreSQL 14 commonly mounts `/var/lib/postgresql/data`. Create a new target volume. Pointing a PostgreSQL 18 container at a PostgreSQL 14 data directory is not a migration. See the [image documentation](https://hub.docker.com/_/postgres).

The commands below use service entries `intermine_old` and `intermine_new` in `.pg_service.conf`, with credentials supplied privately through `.pgpass` or the deployment's secret mechanism. Replace the example database names `mine` and `userprofile` and owner names with the actual values. The service entries should select the intended source and target hosts explicitly.

## Capture a consistent pair

Stop web applications, imports, scheduled jobs and other writers to both databases. Keep them stopped through the final source backup and validation. Independent per-database backups are not an atomic snapshot of a running application. The verification tool also requires quiesced databases: it reads tables and sequences through separate read-only connections.

Use PostgreSQL 18 client tools for the migration, including when dumping the old server. Older `pg_dump` versions cannot back up a newer server.

```sh
umask 077
pg_dump --version
PGSERVICE=intermine_old pg_dumpall --globals-only --file=roles-and-grants.sql
PGSERVICE=intermine_old pg_dump --format=custom --dbname=mine --file=mine.dump
PGSERVICE=intermine_old pg_dump --format=custom --dbname=userprofile --file=userprofile.dump
PGSERVICE=intermine_old python3 config/lib/verify_database.py snapshot --database mine --output mine-before.json
PGSERVICE=intermine_old python3 config/lib/verify_database.py snapshot --database userprofile --output userprofile-before.json
```

The snapshot role needs read access to every application table and the PostgreSQL large-object catalogs. An administrative connection is suitable for an isolated rehearsal. The tool fails on inaccessible data instead of silently excluding it. Snapshot files contain names, schema attributes, owners, sequence state and hashes, rather than application row contents; protect the backups and role export as sensitive data.

## Restore into empty target databases

Review the role export and recreate the required application roles and grants. Do not blindly replay existing cluster-administrator role definitions into another cluster. Preserve application role names so ownership can be compared.

Create the target databases with the intended application owners and the encoding/locale selected during the rehearsal. The example commands use target cluster defaults; supply explicit locale/encoding options when these differ from the required values.

```sh
PGSERVICE=intermine_new createdb --template=template0 --owner=mine_owner mine
PGSERVICE=intermine_new createdb --template=template0 --owner=profile_owner userprofile
PGSERVICE=intermine_new pg_restore --exit-on-error --dbname=mine mine.dump
PGSERVICE=intermine_new pg_restore --exit-on-error --dbname=userprofile userprofile.dump
PGSERVICE=intermine_new psql --dbname=mine --set=ON_ERROR_STOP=1 --command=ANALYZE
PGSERVICE=intermine_new psql --dbname=userprofile --set=ON_ERROR_STOP=1 --command=ANALYZE
```

Do not use `--no-owner` unless ownership changes are intentional and have been separately verified. PostgreSQL 15 changed the default `public` schema privileges. New InterMine test databases now belong to their application role, which can create tables through `pg_database_owner` on PostgreSQL 18. A restored PostgreSQL 14 database can retain older schema ownership and grants: inspect these explicitly. Where `public` is dedicated to the mine, assign it to `pg_database_owner` and remove public `CREATE` privileges; retain explicit grants required by additional import or maintenance roles. Test schema creation and data loading as the actual application role, not only as an administrator.

## Compare and exercise the restored mine

```sh
PGSERVICE=intermine_new python3 config/lib/verify_database.py snapshot --database mine --output mine-after.json
PGSERVICE=intermine_new python3 config/lib/verify_database.py snapshot --database userprofile --output userprofile-after.json
python3 config/lib/verify_database.py compare mine-before.json mine-after.json
python3 config/lib/verify_database.py compare userprofile-before.json userprofile-after.json
```

The comparison checks all non-system physical tables, including inherited tables without double-counting child rows and materialized-view contents; column attributes and owners; sequence definitions and state; and large-object owners, ACLs and page contents. Rows are ordered using the `C` collation before hashing, so physical row order does not affect the result. Foreign tables cause an explicit failure and need their own migration procedure. This is a logical-data comparison, not a complete audit of view/function definitions, extensions, privileges or application behavior.

Start a staging InterMine instance against the restored pair. Verify login, private and public saved lists, templates, uploads, exports, enrichment, keyword search and the relevant client suites. Restart it and verify persistent lists again. Exercise imports and postprocessing against a disposable copy. Capture representative query results and `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` plans/timings on both servers with comparable data, statistics and settings. Investigate changed results or material slowdowns before cutover; optimizer plan changes alone are expected.

## Cutover and rollback

After a successful rehearsal, repeat the quiesced backup/restore/validation for the final cutover. Point the application at the new pair, perform the agreed smoke checks, and then enable writes. Keep the old cluster and verified backups until the retention window ends.

Before accepting new writes, rollback can restore the old connection configuration and restart against the unchanged old pair. After accepting writes on PostgreSQL 18, switching back would lose those new accounts, lists or imported records; reconciliation or a separately tested recovery procedure is required. Rebuilding a search index is separate from preserving the mine and userprofile data.

`pg_upgrade` is an alternative for large installations, but is not the procedure validated here. It needs matching binaries/extensions and compatible cluster settings. PostgreSQL 18 enables data checksums by default; checksum settings must match for `pg_upgrade`. Validate that route independently before scheduling it.
