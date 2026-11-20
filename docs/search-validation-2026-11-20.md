# Java 21 and search validation

This phase follows the PostgreSQL 18 migration on fork `beta` at `5210c45ed9`.
The user selected Java 21 as the minimum for the next major release. The candidate
uses `6.0.0-SNAPSHOT`, Gradle 8.14.7, Solr/SolrJ 10.1.0, embedded Lucene 10.6.0 and
SLF4J 2.0.20 with the reload4j provider.

## Embedded search and build baseline

The Java 21 baseline and embedded search port are committed as `08f9c7b87d`.
The initial 11 saved-item search tests reproduced three failures: more than 1,000
matches overran the hit array, same-name public/private objects resolved to the
wrong repository, and an empty global scope failed while rewriting a query.
Additional tests reproduced an in-flight rename losing its object mapping, public
items from other publishers disappearing from the UI map, and a repository/tag
lock inversion. All 14 saved-item search tests pass after the initial port; final review adds two more cases below.

Each search captures an immutable directory/object mapping. Index construction
runs outside the repository monitor, and publication checks a revision counter.
Per-search readers, analyzers and token streams close after use; invalidation does
not close directories still used by active searches. Unpublished candidates close
immediately. The existing fixed English stopwords, stemming, prefix expansion,
ortholog expansion, tags and highlighted descriptions remain covered.

The baseline core build passed 2,160 tests with no failures, errors or skips in
3m01s. Its plugin test/install and generated testmine model build also passed.
All 305 API Java classes, eight plugin Java classes and generated model classes
checked were class-file major 65. Gradle's bundled Groovy 3 emits older bytecode
for Groovy support classes; the plugin still requires Java 21 through its Java
classes and build guard. A Java 8 build fails immediately with the explicit
InterMine 6/JDK 21 requirement.

## Failure handling

The initial six Solr transport/index prerequisite tests reproduced four failures.
Legacy clients accepted invalid core URLs, and failed delete, commit and schema
requests did not stop the rebuild. A subsequent field-type write test also
failed before propagation was fixed. Actual HTTP routing tests cover both root
and reverse-proxy-prefixed core URLs, including trailing slashes.

The autocomplete/logging run initially failed six of nine tests: four indexing
failures were swallowed, and both Commons Logging and SLF4J lost messages because
the new SLF4J 2 API ignored the old 1.7 binding. The provider now matches the API.
The keyword producer uses an interruptible bounded queue and publishes its error
before completion. A test fills the queue, cancels the blocked producer and
requires it to terminate. Another check rejects schema errors carried in an
otherwise successful response.

The production postprocessing plugin also returned success after a failed
search postprocess. A new plugin test reproduced that behavior; the task now
throws a GradleException retaining the cause. Both plugin tests and publication
pass after the fix (23s). The Ant search task propagates indexing failures and
closes its class-key input stream.

## Fixture and migration evidence

The old Solr 8 fixture and the new Solr 10 service use the same preserved
PostgreSQL 18 database pair, `intermine-runtime-fixed` and
`userprofile-runtime-fixed`. Solr 8 data was archived before stopping that
task-owned service. Solr 10 uses a separate named volume and official image
digest `sha256:ffc795d0b781e1817fa45b606fd7bb182bc09c976667c4e8141597810ca8dd1b`.
No production database or volume is involved.

The Solr 8 baseline has 407 keyword documents and 132 autocomplete documents.
Keyword cases include two David hits, 19 Human Resources hits, one
Department-filtered David hit and no unknown-term hits. Real DWR autocomplete
returns Dan Gore/David Brent for `Da`, David Brent for `Dav` and `David B`, and
no suggestions for the unknown term. Populated list filters previously returned
zero hits because the ID filter was malformed; expected corrected membership is
seven public and four private employees. Anonymous access to the private list
must remain rejected.

The persisted CI search regression checks those cases, all 407 identities across
40-result pages, exact authorized list membership against an independent query,
an empty list and the actual DWR endpoint. See the [operator migration guide](search-modernization.md)
for rebuild and coordinated rollback instructions.

Both real Solr 10 cores rebuilt successfully (40s), then rebuilt again with the
existing schema while assembling the candidate WAR (1m10s). Counts remained
407 keyword documents and 132 autocomplete documents. The initial autocomplete
task exposed a missing webapp classes JAR in the testmine build classpath; its
dependency now explicitly uses the same classifier as the production
autocomplete postprocess.

With the task-owned Solr service stopped, both Gradle index tasks exited with
failure and retained the underlying connection exception (38s, exit 1). After
restarting the same service and volume, both document counts were unchanged.

## Validation status

The full core build, Checkstyle and local Maven publication pass after final review
fixes (2m37s), with 2,176 tests and no failures, errors or skips. That includes
511 API and 244 webapp tests.
All 21 Python bootstrap/database-verifier tests and 15 browser JavaScript
regressions pass; shell and Node syntax checks also pass.

The candidate WAR passes 22 required-provider checks and duplicate-class checks
across 40,880 unique runtime classes. Its 121 libraries include only Solr API/J
10.1.0 and Lucene 10.6.0 for search, with SLF4J API/provider 2.0.20. The former
Jetty, Netty, ZooKeeper and Snappy libraries are absent from this WAR. The Snappy
runtime test was removed because SolrJ no longer brings that library into the
application and no production code uses it. This inventory does not imply the
remaining legacy framework libraries are supported or free of vulnerabilities.

Bio build, Checkstyle and publication pass (1m34s), including all 92 tests with
no failures, errors or skips. Importers also pass all 59 tests, build, Checkstyle
and publication (5m40s). Postprocessor setup exposed an undeclared Commons IO
dependency previously supplied by SolrJ 8; the postprocessor root now declares
it explicitly, as the importer and postprocessor-test roots already do.
Postprocessor build, Checkstyle and publication then pass (1m59s); all 12
postprocessor tests and publication pass (1m04s). In total, 2,339 JVM tests plus
two plugin tests pass with no failures, errors or skips.

All four persisted live search regressions pass (4.2s), including the actual DWR
autocomplete endpoint, complete pagination, exact public/private list membership,
inaccessible-list rejection and an empty authorized list. Captured Solr 10
identities, fields, counts and facets match Solr 8 for every complete representative
case; the total count and category facets also match. All 100 IDs on the captured
first page overlap, although tied ordering is not an acceptance invariant.
Autocomplete suggestions match every baseline case. Populated list results change
from the incorrect zero hits to the expected seven/four authorized members.

The JavaScript live client passes 405 tests in 10s, with its one upstream pending
Unicode-constraint test unchanged. Python passes all 74 live tests (14.833s); its
upstream registry case is a no-op and does not establish registry coverage.
All four registration/OAuth regressions pass. After both clients, eight
authenticated and two public lists have exactly the same IDs, sizes and members
as the preserved PostgreSQL baseline; all 11 saved-list database records remain.
The running Tomcat JVM reports OpenJDK 21.0.13. The initial hosted InterMine CI run 44769503594 passes all six jobs, including both
clients, and Checkstyle run 44769503541 passes. Final-source CI remains pending.

The whole-branch Aislop scan reports 99 with zero errors. Its one fixable warning
is a false positive on the existing Constants class Javadoc, “Container for
ServletContext and Session attribute names used by the webapp”: the detector
matches “used by”. That API documentation is retained as required by the project
instructions. Thirteen nonfixable warnings remain: two existing large classes,
ten broad exception declarations in framework callbacks and an existing
AjaxServices template-update catch. These require separate refactoring or an
error-contract change. No rule configuration was changed. Gradle/Groovy is
outside Aislop's supported lint coverage; builds and the plugin regression cover
the modified postprocessing path.

## Final review regressions

A fresh independent review found three important issues. The new browser test
reproduced Select All saving an outside result (ID 2000028) instead of the one
source-list member (ID 2000191); a second test showed a deleted source list still
allowed saving. The form now carries the source name, and the action resolves
membership against the current profile, returning HTTP 400 if access is gone.

Two Java regressions reproduced a NullPointerException when a template was renamed
after search returned, and blank ALL searches selecting a public same-name object
over the user's item. Metadata maps now use object identity, and blank searches
collect public items before user items. All 16 saved-item search cases and the
full core suite pass. All ten live search/registration/OAuth cases pass on the
rebuilt WAR (5.9s), including both new browser regressions. The WAR again passes
22 required-provider checks and 40,880 unique-class checks.

Review boundaries remain explicit: rebuilds are not atomic replacements of active
cores, exact scores/tie ordering are not compatibility invariants, and autocomplete
still builds its document batch in memory. Operators must use fresh separate cores
for migration; very large autocomplete sources can still require substantial heap.
