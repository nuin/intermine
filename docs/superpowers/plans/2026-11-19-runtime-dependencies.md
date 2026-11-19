# Runtime dependency maintenance

**Goal:** Audit the deployed runtime, remove conflicting packaged libraries, and apply compatible security upgrades after the validated Java 21 and reliability work reaches the fork's `beta` branch.

**Spec:** User-approved sequence “2 then 1 and 3”; dependency audit and small tested upgrade batches. Commit and push are authorized. Preserve Java 8 artifact compatibility and Java 21 runtime. Struts/Jakarta and search-engine major migrations remain separate projects.

## Review focus

- Local development resolves the freshly published artifacts and their metadata.
- The assembled WAR retains vendored StringTree and JAXB while avoiding duplicate library versions and servlet APIs.
- Database COPY, upload parsing, JSON number handling, and published dependencies retain compatibility.
- Authenticated registration preserves list ownership; anonymous registration still carries anonymous lists into the new account.
- Audit counts describe the actual artifact, distinguish known advisories from demonstrated exposure, and disclose remaining unsupported components.

## Task 1: Dependency resolution and packaging

- [x] Add an assembled-WAR regression gate for critical class providers; observe duplicate-provider failures on the current WAR.
- [x] Resolve local InterMine artifacts through Maven metadata after `install`, and assemble managed libraries from that graph. Preserve the vendored StringTree library explicitly.
- [x] Build/install core, rebuild testmine, run the artifact gate and live clients.
  Expected: one provider for each checked library, JAXB and StringTree present, no servlet API bundled, both live client suites pass.
- [x] Fix the account/list ownership defect discovered during live validation: reproduce it over HTTP with disposable accounts, restrict migration to anonymous profiles, retain anonymous-list migration, and rerun webapp/live suites.
- [x] Scan touched files and commit.

## Task 2: Compatible security batch

- [ ] Update pgJDBC, Commons BeanUtils/Collections/IO/FileUpload, JSON-java, and Tomcat 9 from verified upstream releases; keep declarations consistent across core, bio, and testmine.
- [ ] Apply a second compatible batch for Jackson 2 LTS, Ant, Guava, Commons Compress/Lang 3, Xalan, and Snappy, addressing transitive libraries exposed by the corrected graph. Centralize shared versions/constraints and verify JSON/Jersey and Jena runtime compatibility.
- [ ] Run affected suites on Java 21 first, investigate any compatibility changes, then full core/bio tests on Java 8 and 21 and live JavaScript/Python clients.
- [ ] Rebuild and inspect the WAR, query its final Maven versions against OSV, and document remaining findings and support migrations.
  Expected: upgraded libraries are the ones deployed, no duplicate checked providers, suites pass without locally disabled tests, and unresolved advisories remain visible.
- [ ] Scan, commit, push, obtain one fresh review for this phase, address important findings, and verify hosted CI.

## Audit scope

The initial WAR contains 107 JARs and 105 external Maven coordinates/versions, including embedded Maven metadata. OSV reports 60 unique advisory IDs across 21 queried versions on 2026-11-19. This is a package inventory and advisory match, not a penetration test. Vendored JavaScript, embedded bytecode without metadata, application-specific exploitability, and production infrastructure require separate assessment. Unsupported Struts 1, Solr 8, legacy HTTP client, and PostgreSQL 14 are tracked explicitly; the database server is not silently migrated with a JDBC update.
