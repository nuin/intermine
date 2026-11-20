# Java 21 and search modernization implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Ship the next-major Java 21 baseline with current embedded Lucene and Solr, preserving supported search behavior and documenting index rebuilds.

**Architecture:** Keep the embedded saved-item search and external Solr responsibilities. Port each to current APIs, retaining public service contracts and validating identity/visibility independently of scores. Rebuild external indexes into separate storage from the same PostgreSQL fixture.

**Tech Stack:** Java 21, Gradle 8.14.7, Lucene 10.6.0, Solr/SolrJ 10.1.0, PostgreSQL 18.7, Tomcat 9.0.124.

**Spec:** `docs/superpowers/specs/2026-11-20-search-modernization-design.md`

## Global constraints

- PostgreSQL phase gates must pass before implementation.
- New development version is `6.0.0-SNAPSHOT`; Java 21 is required to build and run.
- No production data, in-place Solr index upgrade or public API redesign.
- Retain the existing 1,000-candidate saved-item search cap and ASCII normalization.
- Compare identities/counts/scopes/facets, not exact cross-major score values or tied order.
- User authorized commit/push; execute inline and perform one fresh final branch review.

## Review focus

- Public/private same-name objects must resolve to their source repository, not expose an unmatched private item (Task 1).
- More than 1,000 matching saved items and zero global indexes must return valid bounded results (Task 1).
- Concurrent index invalidation must not close a directory under an active reader or return stale objects silently (Task 1).
- A core URL with a deployment prefix/trailing slash must still target exactly that core (Task 2).
- A failed schema update/add/commit must fail a rebuild visibly, including when a core already has schema (Task 2).

### Task 1: Java 21 baseline and embedded Lucene

**Files:** Active `build.gradle` roots in plugin/intermine/bio/bio-sources/bio-postprocess/bio-postprocess-test/testmine, their `gradle.properties.in` templates, `config/gradle/java-compatibility.gradle`, `config/lib/install_intermine.py` if needed for early Java validation, `.github/workflows/im-build.yml`, `README.md`; dependency declarations in intermine/api, intermine/webapp, bio/build, bio/webapp and testmine/dbmodel; `intermine/api/src/main/java/org/intermine/api/search/{SearchRepository,SearchResults}.java`; `intermine/webapp/src/main/java/org/intermine/dwr/AjaxServices.java`; `intermine/webapp/src/main/java/org/intermine/web/logic/Constants.java`; search tests under `intermine/api/src/test/java/org/intermine/api/search/` and plugin's existing functional tests.

**Interfaces:** Keep `SearchRepository.getSearchIndex(String): Directory` if safe ownership can be retained; otherwise encapsulate snapshot-reader acquisition within the search package and update its only consumers. Preserve `SearchResults.runLuceneSearch(String, SearchTarget, SearchRepository): SearchResults` with current `queryparser.classic.ParseException`. Subsequent Task 2 consumes Java21 artifacts at 6.0.0-SNAPSHOT.

- [ ] Record Solr 8 API and autocomplete baselines against the restored fixture before any server change. Preserve canonical result identities/facets/index counts under ignored build artifacts for later comparison.
- [ ] Add real regressions: `testMoreThanOneThousandMatches` expects 1,000 returned items without exception; `testGlobalSearchDoesNotResolvePrivateNameCollision` expects the public object when only it matches; `testGlobalSearchWithoutRepositories` expects zero items. Add an invalidation/concurrent-read case with deterministic synchronization if directory ownership changes. Extend existing scope/stemming/tag/highlight cases where coverage is missing.
- [ ] Run the new regressions using the existing Java21 build image and PostgreSQL18 fixture (`gradle :intermine-api:test --tests org.intermine.api.search.SearchResultsTest`); capture observed failures before the port.
- [ ] Update shared compile/runtime Java floor and active versions. Replace old Lucene dependencies, port the index writer/fields/analyzer and reader/searcher/query-parser APIs, and close owned resources. Resolve hits by source repository and safely iterate returned scoreDocs. Keep non-search dependencies unchanged in this task.
- [ ] Run focused search tests plus Checkstyle; use existing complete search test class to check index events. Verify Java21 bytecode and an actionable rejection when building with Java8. Run plugin functional tests with Java21 and generated mine settings resolving the new artifacts. Update active build instructions.
- [ ] Commit the independently passing Java/Lucene change. Full final suites occur after Solr task; any new failure is resolved before proceeding.

### Task 2: Solr 10 transport, rebuilds and release validation

**Files:** `intermine/api/src/main/java/org/intermine/api/searchengine/solr/{SolrClientManager,SolrIndexHandler,SolrObjectHandler,SolrKeywordSearchHandler}.java`, small shared client factory if needed; `intermine/webapp/src/main/java/org/intermine/web/autocompletion/{SolrClientHandler,AutoCompleter}.java`; `intermine/webtasks/src/main/java/org/intermine/web/task/CreateSearchIndexTask.java` (currently catches indexing failures without failing the Ant task); other Solr call sites only if API changes require; direct Solr dependency declarations; `config/ci/init-solr.sh`, `.github/workflows/im-build.yml`; focused Java client/index tests; `config/ci/search.test.cjs` for service behavior; `docs/search-modernization.md`, `docs/search-validation-2026-11-20.md`.

**Interfaces:** A shared `create(String coreUrl): SolrClient` adapter may centralize URI validation, root URL/default collection and HttpJdkSolrClient creation. Managers keep existing public entry points with safe publication. Existing index entry points retain checked exception contracts and propagate failures.

- [ ] Add tests for root and prefixed core URLs, trailing slashes, invalid schemes/missing core, and a deliberately unavailable Solr endpoint failing a rebuild. Reproduce swallowed schema/write failures using a controlled fake HTTP server or client injection with observable requests. Assert no success/commit after a failed prerequisite.
- [ ] Pin all SolrJ declarations to 10.1.0, use explicit JDK transport, adapt required APIs and replace swallowed indexing errors with propagation. Update CI to run Solr10 on Java21 with verified distribution and supported standalone-core commands.
- [ ] Create separate task-owned Solr10 storage, initialize both cores, rebuild keyword and autocomplete indexes against the same restored PostgreSQL fixture, then repeat rebuild to exercise existing schema. Test a failing rebuild and restore the fixture service afterward.
- [ ] Compare Solr8/10 baseline identities and counts, category facets, paginated queries and list constraints. Run `AutoCompleter.getFastList` through the actual application endpoint or Java integration harness with expected Employee.name suggestions; direct Solr queries alone do not prove the adapter. Fix the baseline malformed list-filter clauses and test exact authorized memberships (public7/private4), inaccessible list rejection, and zero results for an empty list. Add a persisted service regression to CI for stable externally visible behavior.
- [ ] Run all Java21 core/bio/importer/postprocessor test and Checkstyle/install tasks sequentially against PG18. Rebuild WAR, run provider/duplicate checks, inspect resolved/runtime archives for legacy Solr/Lucene. Start Tomcat, run JavaScript/Python live clients and registration/OAuth tests sequentially. Run Python bootstrap/browser checks and Aislop; fix blocking findings without changing rule settings.
- [ ] Write operator migration/rebuild/rollback instructions and final measured validation report, commit/push maintenance branch, and wait for hosted CI. Dispatch the one required fresh whole-branch review, accept/blocking fixes with reproduced tests, then integrate to fork beta after all gates pass.

Self-review: the two tasks cover the spec. Task 1 keeps legacy Solr temporarily while changing embedded search; Task 2 completes the new-major runtime. The shared build-root versions must be changed together so generated mine tests can resolve coherent artifacts. No separate setup-only tasks or implementation agents are needed.
