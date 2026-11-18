# InterMine maintenance assessment — 18 November 2026

This document records the initial snapshot. Subsequent work is recorded in the [backlog triage](maintenance-triage-2026-11-18.md) and [build verification ledger](build-modernization-2026-11-18.md).

The recommended starting point is to contribute upstream through a reproducible build, focused fixes, and a maintenance release. Keep the existing fork available for development. Upstream is still active, so an independent release line should be a deliberate decision about ownership and support.

This is an initial assessment, not a completed modernization or security audit. It combines a complete inventory of open core-issue metadata with selected issue discussions, source inspection, CI history, and official platform documentation. No application builds, test suites, dependency resolution, or vulnerability scans were run. No upstream issues, pull requests, or permissions were changed.

The local `beta` branch points to `787f779b7a3be3d778de784229a701cc47dc355b`, identical to upstream `dev` when checked. Its only configured remote is `nuin/intermine`. Upstream merged a README change on 12 November 2026 and has development-branch activity on 18 November. The latest GitHub release returned is [5.1.0, published 26 August 2022](https://github.com/intermine/intermine/releases/tag/intermine-5.1.0); local build files declare 5.1.1. Maven publication status was not checked.

The full core inventory is saved in [maintenance-backlog-2026-11-18.json](maintenance-backlog-2026-11-18.json), including query URLs, issue links, dates, labels, assignments, milestones, and the three open pull requests.

| Observation | Count | Implication |
| --- | ---: | --- |
| Open core issues | 218 | Reproduction and ownership triage are substantial work. |
| Updated before 2023 | 214 | Age alone does not establish whether a defect persists. |
| Carrying the `bug` label | 41 | Labels are incomplete and overlap with other categories. |
| Without labels | 62 | Classification needs attention. |
| Without assignees | 194 | Ownership must be established for selected work. |
| Without milestones | 201 | The existing backlog is not a release plan. |
| Open core pull requests | 3 | Review existing contributions before duplicating them. |

These counts come from the [core issue tracker](https://github.com/intermine/intermine/issues?q=is%3Aissue+is%3Aopen) and [pull-request tracker](https://github.com/intermine/intermine/pulls?q=is%3Apr+is%3Aopen). Separate count-only queries returned 143 open issues in [BlueGenes](https://github.com/intermine/bluegenes/issues?q=is%3Aissue+is%3Aopen) and 12 in the [Python client](https://github.com/intermine/intermine-ws-python/issues?q=is%3Aissue+is%3Aopen). Their backlogs were not inventoried or reviewed here; the 218-issue file is not an ecosystem-wide inventory.

The platform work should be ordered by dependencies:

| Priority | Evidence in this checkout | Proposed work and completion criterion |
| --- | --- | --- |
| First: reproducible verification | Both [workflows](../.github/workflows/im-build.yml) trigger on `push` only. CI uses Java 8, PostgreSQL 14, and external client branches. | Establish a clean, isolated baseline; add pull-request and manual runs; retain test reports and logs; record exact client revisions. Require core, bio, JS/Python service tests, and Checkstyle on the same candidate commit. |
| First: release path | [Core publishing](../intermine/build.gradle) and [plugin publishing](../plugin/build.gradle) use legacy `mavenDeployer` and `s01.oss.sonatype.org`. | Migrate publication to Central Portal-compatible tooling. Generate and validate artifacts, signatures, POMs, and a downstream consumer build before an authorized release. Verify namespace ownership separately. |
| First: database support | CI specifies `postgres:14`; JDBC 42.2.2 is declared in [objectstore](../intermine/objectstore/build.gradle) and other modules. | Test a supported PostgreSQL target, initially 17 or 18, and an appropriate JDBC update. Exercise database creation, integration, COPY, queries, and user profiles, plus a documented migration rehearsal. These targets are proposals, not established InterMine compatibility. |
| First: dependency exposure | [Webapp dependencies](../intermine/webapp/build.gradle) include Struts 1.3.10, FileUpload 1.2.2, Jackson Databind 2.2.3, and old authentication libraries. | Resolve actual runtime dependencies and inspect packaged WARs and bundled JARs. Assess advisories and reachable code, then patch in focused changes with upload, authentication, serialization, and API checks. Declared versions alone do not establish exploitability. |
| Next: build and Java modernization | [Installer](../config/lib/install_intermine.py) defaults to Gradle 4.9; build scripts use Java 8, `compile`, `testCompile`, and the legacy `maven` plugin. | Migrate configurations, publication, and custom Gradle plugins in stages. Evaluate Java 21 as the first newer runtime target after the build can run on it. Preserve downstream mine builds and define the supported Java policy before changing bytecode requirements. |
| Next: search stack | [CI](../config/ci/init-solr.sh) downloads Solr 8.6.2 over HTTP; [API dependencies](../intermine/api/build.gradle) specify SolrJ 8.11.4 and direct Lucene 3.0.2 dependencies. | Inventory both search implementations, use verified downloads, and select a tested supported Solr/client combination. Validate indexing, keyword search, autocomplete, facets, and reindex instructions. |
| Planned architecture work | The webapp uses Struts 1 and `javax.servlet`; [testmine](../testmine/webapp/build.gradle) specifies Tomcat 9.0.115. | Establish a legacy UI support policy, then plan servlet/Jakarta and framework migration with BlueGenes/API compatibility. Verify which features still depend on the legacy UI before retiring any. |

Several upgrade constraints are already documented by their vendors. PostgreSQL lists 12 November 2026 as version 14's final release date; its support table still displayed “Yes” when inspected, so the date is the basis for prioritizing migration. [PostgreSQL policy](https://www.postgresql.org/support/versioning/). OSSRH was shut down on 30 June 2025, requiring a publishing-process change. [Sonatype notice](https://central.sonatype.org/pages/ossrh-eol/).

Gradle 7 removed the old Maven plugin and dependency configurations, so changing the wrapper alone is insufficient. Java 21 requires Gradle 8.5 or later to run Gradle; this is a compatibility floor, not a recommended final Gradle version. [Migration guide](https://docs.gradle.org/current/userguide/upgrading_version_6.html), [Java compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html).

Struts 1 is unsupported and has no drop-in framework replacement. [Apache announcement](https://struts.apache.org/struts1eol-announcement.html). Tomcat 9.0 support is scheduled to end on 31 March 2027, with a 9.1 continuation announced through 2030. A move to Tomcat 10+ crosses the `javax`/`jakarta` API boundary and needs a separate migration and test plan. [Tomcat lifecycle](https://tomcat.apache.org/tomcat-9.0.x-eos.html), [migration guide](https://tomcat.apache.org/migration-10.html).

Selected backlog candidates, in proposed working order:

| Item | Assessment | Next evidence needed |
| --- | --- | --- |
| [#2451: region-search pagination](https://github.com/intermine/intermine/issues/2451) | The reported strict `>` comparison remains in [the JavaScript](../bio/webapp/src/main/webapp/model/genomic_region_search/genomic_region_search_results_default.js). Strong small-fix candidate; not reproduced here. | Cover counts immediately below, equal to, and above page sizes; inspect server bounds and incomplete-query behavior. |
| [PR #2449: GO annotation format](https://github.com/intermine/intermine/pull/2449) | Open, one changed file; author reports testing in ThaleMine. | Review the patch against current formats; test both historical and updated fixture records. |
| [PR #2454: NCBIfam support](https://github.com/intermine/intermine/pull/2454) | Open, one changed file; author reports testing in ThaleMine. | Review identifier/database naming and add representative loader fixtures. |
| [#2355: non-ASCII source names](https://github.com/intermine/intermine/issues/2355) | Report describes integration failure during PostgreSQL COPY. | Reproduce with UTF-8 source names and inspect the encoding path. The reported diagnosis is not established here. |
| [#2241: private-list response status](https://github.com/intermine/intermine/issues/2241) | Reported API-contract error. | Test anonymous, owner, other-user, and nonexistent-list requests; verify response bodies and status codes. |
| [#1845: bundled JSON library](https://github.com/intermine/intermine/issues/1845) | The cited JSONWriter import and dependency remain. | Check all usages and generated JSON behavior before replacing the bundled JAR. |
| [#651: enrichment correction names](https://github.com/intermine/intermine/issues/651) | Current [calculation code](../intermine/webapp/src/main/java/org/intermine/web/logic/widget/EnrichmentCalculation.java) already throws for unknown strategies. The service catches that exception and returns a message. This needs verification before implementation or closure. | Test invalid, omitted, and valid correction parameters end to end, including status semantics and any remaining discovery requirement. |
| [#2424: Python imports](https://github.com/intermine/intermine/issues/2424) | Discussion includes a Python 3.10 workaround and a 2025 follow-up. Belongs in client/release coordination. | Compare the released Python package with client `dev`; reproduce on supported Python versions before proposing a fix. |

The third open contribution, [PR #2306](https://github.com/intermine/intermine/pull/2306), adds query operators across six files. Review it after the baseline is established, with parser, SQL generation, and cross-format query tests. None of the three patches was reviewed or executed in this assessment. Other reported reliability/performance candidates include [#1795](https://github.com/intermine/intermine/issues/1795), [#1894](https://github.com/intermine/intermine/issues/1894), and [#2423](https://github.com/intermine/intermine/issues/2423).

For each ticket, record the owning repository/component, affected versions, reproduction command and fixture, present-day outcome, impact, regression coverage, and release assignment. Use explicit outcomes such as reproduced, needs information, already addressed, duplicate, deployment-specific, or deferred. Propose closures only with evidence; do not close by age.

The first development milestone should be **a reproducible maintenance candidate**:

1. Agree the upstream/fork direction and a small initial scope. For upstream stewardship, establish who can review, triage, publish Maven artifacts and container images, and maintain documentation and security reporting. Existing maintainers must grant those roles. For independent releases, define ownership, artifact coordinates, versioning, and how changes return upstream.
2. Run the existing suites in disposable infrastructure and record the baseline. The current CI initialization drops named databases and a database user on localhost; use an isolated service. Repair environmental failures before treating them as application regressions.
3. Add reliable PR verification, then reproduce #2451 and review the two small loader contributions. Keep fixes independently reviewable.
4. Resolve the dependency graph, address confirmed urgent exposure, validate a supported database target, and repair the publication path. Coordinate with ongoing upstream work before duplicating CI or dependency changes.
5. Prepare a maintenance candidate with artifacts, release notes, supported versions, upgrade/rollback instructions, and a downstream mine trial. Schedule broader Java/Gradle, servlet/framework, search, and UI changes as separate milestones with compatibility evidence.

Recent CI is useful context but not a fresh local baseline: the latest returned `dev` runs both succeeded on 12 June 2026 at `77cf7068dad0beac153e93e9916997d0ea850372` ([run](https://github.com/intermine/intermine/actions/runs/27411575800), [run](https://github.com/intermine/intermine/actions/runs/27411575807)). Other development branches have both successes and failures. A run on another commit cannot establish that the current checkout passes.
