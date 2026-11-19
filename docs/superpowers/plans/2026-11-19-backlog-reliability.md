# Backlog reliability plan

**Goal:** Resolve issues #2451, #2355 and #1639 with regressions, then validate and integrate the Java 21 maintenance work, followed by compatible runtime dependency upgrades.

**Spec:** The user approved the sequence “2 then 1 and 3”: backlog fixes, release validation, dependency audit. Commit and push to the user's fork remains authorized.

## Global constraints

- Preserve Java 8 artifact compatibility, Java 21 development, Gradle 8.14.7 and Tomcat 9.
- Keep the Struts/Jakarta migration outside this phase.
- Observe a failing regression before each behavioral fix; use real PostgreSQL for tracking tests.
- Fix Aislop errors and fixable warnings without changing rule configuration.
- Work on `maintenance/reliability`; integrate into `beta` only after validation.

## Review focus

- Inclusive region indices, asynchronous completion and partial pages.
- UTF-8 byte lengths in PostgreSQL binary COPY, and provenance read after reopening.
- JSON object sorting, nested collections, ties and pagination without splitting objects.

## Task 1: Region pagination (#2451)

- [x] Add Node built-in tests executing the production browser script with DOM/network boundaries stubbed.
- [x] Observe failures for 9/10 and 24/25 regions and a completed page during an ongoing search.
- [x] Correct inclusive bounds and readiness checks; retain waiting behavior.
- [x] Add the test command to CI and run `node --test bio/webapp/src/test/js/*.test.cjs`.
  Expected: all boundary and polling regressions pass.
- [x] Scan touched files and commit the fix separately.

## Task 2: Unicode tracking (#2355)

- [x] Add database round-trip tests for accented, CJK and supplementary Unicode source/field names.
- [x] Observe the existing COPY encoding failure, then encode lengths and values as UTF-8 bytes.
- [x] Run focused DataTracking tests and the integrate suite with PostgreSQL on Java 21.
  Expected: persisted provenance survives closing and reopening, and existing integration behavior passes.
- [x] Scan touched files and commit separately.

## Task 3: JSON object ordering (#1639)

- [x] Trace query transformation and object grouping before selecting a fix.
- [x] Add ordering regressions including descending, multiple keys, nested data and pagination where supported.
- [x] Observe the failure and preserve requested ordering without breaking object assembly.
- [x] Run focused query/formatter tests and the webapp suite on Java 21.
  Expected: explicit ordering is retained and existing JSON object behavior passes.
- [x] Scan, commit separately, obtain a fresh review and address important findings.

## Following phases

- [x] Run full JavaScript and Python client suites against the deployed Java 21 test mine.
- [x] Check hosted CI, repair any verified failures, and integrate into the fork's beta branch once green.
- [x] Audit resolved runtime artifacts against primary security/support sources.
- [x] Upgrade compatible dependency batches, validate, document unresolved major migrations, commit and push.
