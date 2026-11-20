# Java 21 and search modernization design

## Intent and decisions

Modernize the fork for the next major InterMine release while retaining the current keyword-search, facet, autocomplete and saved-item search interfaces. The user's selected sequence is PostgreSQL, search, browser authentication/ownership, then incremental framework/auth-library replacement. PostgreSQL gates must pass before this implementation starts. The user explicitly requires Java 21 for the next major release and authorized implementation, commit and push to their fork. Version/library details below are builder choices within that scope.

## Approaches

1. Solr 10.1.0 and embedded Lucene 10.6.0, with a Java 21 baseline: chosen because both are current and it removes the old Lucene 3 API and Solr 8 dependency family in one next-major migration. Solr's own bundled Lucene remains its distribution version; the embedded application index uses 10.6.0.
2. Solr 9.11 first: a smaller transitional step, but another server/client migration would remain. The new Java minimum removes its main advantage.
3. Retaining Java 8 through a separate search service: adds a second application/service boundary and operational burden without serving the user's chosen baseline.

## Scope and constraints

- Require JDK 21 to build and run the next major release. Emit Java 21 class files, set the development artifact version to `6.0.0-SNAPSHOT`, and update all active build roots, the Gradle plugin, generated mine defaults, CI and operator documentation consistently. Keep Gradle 8.14.7. Earlier Java-compatible commits remain in fork history; this does not promise maintenance of a legacy branch.
- Pin Solr/SolrJ 10.1.0 and application Lucene modules 10.6.0. Replace retired `lucene-snowball`/`lucene-analyzers` modules with current analysis, queryparser and highlighter modules. Verify resolved dependencies and packaged classes rather than assuming direct declarations eliminate old artifacts.
- Preserve external REST paths and response shapes, configured core URLs, list authorization, stemming, prefix/ortholog expansion, tags, highlighted descriptions, event-driven index invalidation and invalid-template filtering. Exact floating-point scores and tied result order are not stable across Lucene majors; compare result identities, scope, counts, facets and meaningful relevance cases.
- No production data or Solr volumes are changed. No query language redesign, Unicode normalization expansion, UI redesign, broad search scalability rewrite or framework migration in this phase.

## Components

### Build baseline

`config/gradle/java-compatibility.gradle` is the shared enforcement point. Active root Gradle files and plugin defaults use version 6.0.0-SNAPSHOT and Java 21. Existing bootstrap code must select a Gradle/JDK pair that can build those artifacts and report an actionable error for older Java. CI uses Java 21 for build, tests, Solr and Tomcat; remove the Solr-only Java 11 setup and Java 8 matrix from the new-major branch. Historical validation documents remain historical.

### Embedded saved-item search

`SearchRepository` replaces RAMDirectory and old IndexWriter/Field APIs with ByteBuffersDirectory, IndexWriterConfig and current text/stored fields. Use an English analyzer with a fixed English stopword set for both writing and reading. Preserve the current ASCII content normalization. Writers, analyzers, readers and token streams have explicit ownership and close on exceptional paths. Do not close a directory still in use by another request; directory invalidation/concurrency behavior must remain safe and bounded to this subsystem.

`SearchResults` replaces MultiSearcher with current readers/searchers, restricting index selection before searching to USER, GLOBAL or ALL. A document must resolve to the searchable belonging to its source repository, including when a private item and a public item have the same name. If both legitimately match and the name-keyed response allows one result, prefer the user's matching item consistently. Never substitute a private object for a public hit. Existing 1,000-candidate search limit remains, but iterate actual returned scoreDocs so more than 1,000 matches cannot overrun the result array. Preserve public method contracts apart from the relocated Lucene ParseException type and update callers.

### Solr transport and indexing

Replace legacy HttpSolrClient construction in the API and autocomplete client managers with explicit HttpJdkSolrClient. Preserve existing properties ending in `/solr/<core>` by deriving the Solr base URL and setting the default collection, with focused tests for trailing slash and non-root deployment prefixes. Do not silently choose a different core or accept malformed URLs. Use safe singleton publication and avoid optional Jetty transport dependencies unless an actual requirement emerges.

Adapt schema/index/query APIs only where required. A failed delete, schema update, document addition or commit must fail the indexing operation visibly; it must not be reported as a successful rebuilt index. Test fresh cores and a second rebuild, since schema definitions already exist on the latter. CI initializes standalone cores using the supported Solr 10 CLI and a verified pinned distribution. The two cores remain `intermine-search` and `intermine-autocomplete`.

### Upgrade and rollback

Run a separate Solr 10 service with fresh storage. Rebuild both indexes from PostgreSQL using InterMine's existing index-generation commands; never open a Solr 8 index with Solr 10 or copy old index files. Record index counts and query/facet/autocomplete baselines on the old fixture, then compare against rebuilt indexes on the same database. Switch application configuration only after validation. Keep the previous application and Solr 8 storage available for a coordinated application/search rollback. Saved data lives in PostgreSQL; this phase adds no database schema migration.

## Acceptance

1. Focused saved-item search tests pass for scopes, English stemming, stopwords, prefix and ortholog expansion, tags/highlighting, invalidation and invalid templates. Add regressions for more than 1,000 hits, empty global repositories and same-name private/public objects. Where current code is wrong, demonstrate RED then GREEN.
2. A real Solr 8 baseline and rebuilt Solr 10 fixture return equivalent result identities and facet counts; autocomplete returns the expected attribute suggestions. Pagination and authorized/private-list filters continue to work. Failure injection confirms unavailable Solr does not result in a success report. A second index rebuild passes.
3. All Java 21 core, bio, importer and postprocessor suites pass; plugin/generated-mine checks use the new baseline. WAR verification passes without old Lucene/Solr artifacts or duplicate providers. Live JavaScript/Python clients and registration/OAuth regressions pass on PostgreSQL 18, Tomcat 9 and Solr 10. Record upstream skipped tests accurately.
4. CI, Checkstyle, bootstrap checks and Aislop blocking findings pass. One fresh whole-branch review and fix pass precede integration/push to fork beta. Document runtime requirements, rebuild commands, validation evidence and limitations.

## Baseline discoveries

The current Solr8 fixture returns zero results when restricted to a populated list: `SolrKeywordSearchHandler` adds separate `fq=id` and `fq=<number>` clauses instead of a single ID-set filter. Correct this in the Solr task and assert results equal the authorized list membership; an empty authorized list must return zero, and inaccessible lists must remain rejected. Other baseline results (407 total, two David hits, 19 Human Resources hits, category facets) are retained for cross-version comparison. The current testmine setup does not populate autocomplete, so explicitly build that index and capture a real DWR getContent baseline before switching Solr.

## Builder discretion

Choose small internal helper names, exact reader ownership mechanics and test fixtures consistent with the repository. Add focused tests for concrete migration failures, not assertions that mirror configuration text. If current Solr API behavior contradicts a proposed adapter, use verified official API documentation and revise the adapter while retaining the interface and safety constraints above.

## Sources checked 2026-11-19

- https://solr.apache.org/downloads.html
- https://solr.apache.org/guide/solr/latest/deployment-guide/system-requirements.html
- https://solr.apache.org/guide/solr/latest/upgrade-notes/major-changes-in-solr-10.html
- https://solr.apache.org/guide/solr/latest/deployment-guide/solrj.html
- https://lucene.apache.org/core/downloads.html
- https://lucene.apache.org/core/10_6_0/SYSTEM_REQUIREMENTS.html
