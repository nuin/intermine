# Migrating search for InterMine 6

InterMine 6 requires JDK 21 or newer to build and run. This development branch uses
`6.0.0-SNAPSHOT`, Gradle 8.14.7, Solr/SolrJ 10.1.0 and embedded Lucene 10.6.0.
Solr's bundled Lucene version is managed by its own distribution. Upgrade the
InterMine Gradle plugin, core and bio artifacts together; rebuild generated mine
models and the WAR with Java 21. Existing Java 8 deployments cannot load these
artifacts.

Keyword search and autocomplete use separate Solr cores. Saved lists and templates
use an embedded, in-memory Lucene index that is recreated by the application.
There is no PostgreSQL schema migration in this search change.

## Rebuild on separate storage

1. Record keyword result IDs and counts, category facets, representative list
   restrictions and autocomplete suggestions from the existing service. Save the
   old application artifacts, configuration and Solr 8 data before changing them.
2. Start Solr 10.1.0 with Java 21 and fresh storage. For a standalone installation,
   use `bin/solr start --user-managed`, then `bin/solr create -c intermine-search`
   and `bin/solr create -c intermine-autocomplete`. For Docker, use the official
   image's `precreate-core` and `solr-foreground --user-managed` entry points with
   a separate persistent volume. Do not open or copy a Solr 8 index into Solr 10.
3. Point a Java 21 build of the mine at the new cores. Set `index.solrurl` in
   `dbmodel/resources/keyword_search.properties` and `autocomplete.solrurl` in
   `dbmodel/resources/objectstoresummary.config.properties` to their complete
   core URLs, for example `http://search-next:8983/solr/intermine-search` and
   `http://search-next:8983/solr/intermine-autocomplete`. A reverse-proxy path
   prefix and a trailing slash are supported. Credentials, query strings and
   fragments in these URLs are rejected.
4. Ensure the mine's `project.xml` includes the `create-search-index` and
   `create-autocomplete-index` postprocesses, then run these from the mine root:

   ```sh
   ./gradlew postProcess -Pprocess=create-search-index
   ./gradlew postProcess -Pprocess=create-autocomplete-index
   ```

   The repository's standalone testmine instead exposes:

   ```sh
   ./gradlew :dbmodel:createSearchIndex :dbmodel:createAutocompleteIndex
   ```

   These tasks rebuild search indexes from the configured database. Do not run
   `testmine/setup.sh` against a database you intend to keep: that fixture setup
   recreates its databases.
5. Require successful task exits, healthy cores and the expected document counts.
   Repeat both rebuilds to check the existing-schema path. Start the candidate
   application and compare keyword IDs, facets, pagination, public and private
   list restrictions, empty lists and actual application autocomplete responses.
6. Switch application traffic and configuration to the validated Java 21/Solr 10
   pair. Retain the previous deployment and storage until the rollback window
   closes.

Index rebuilds delete documents in their destination core. They are not atomic:
an interrupted rebuild can leave an empty or partial index. Use separate cores
and hold traffic until validation succeeds. Schema, fetch, document-write and
commit failures now propagate through the indexer and build task. Correct the
failure and rerun the affected postprocess; a full database reload is unnecessary.

## Behavior and rollback

REST paths and response shapes remain unchanged. Scores and ordering among tied
matches can change between Lucene versions; compare identities and meaningful
relevance cases rather than floating-point score equality. The saved-item search
still considers at most 1,000 matching candidates. It now handles that boundary
without an array overrun and resolves hits to their original repository, including
public/private items with identical names.

List-filtered keyword search now uses the permitted ID set. A populated list
returns its matching members, an empty list returns no hits, and an inaccessible
list remains rejected. This fixes the previous malformed filter that returned no
hits for populated lists.

To roll back, restore the previous application and its matching Solr 8 core URLs
and storage together. Do not mount Solr 10 data in Solr 8 or mix the old SolrJ
client with the new deployment. PostgreSQL remains the source for rebuilding
either set of indexes. Any database upgrade has its own backup and rollback plan.

## References

- [Solr system requirements](https://solr.apache.org/guide/solr/latest/deployment-guide/system-requirements.html)
- [Solr 10 changes](https://solr.apache.org/guide/solr/latest/upgrade-notes/major-changes-in-solr-10.html)
- [SolrJ client configuration](https://solr.apache.org/guide/solr/latest/deployment-guide/solrj.html)
- [Solr command-line control](https://solr.apache.org/guide/solr/latest/deployment-guide/solr-control-script-reference.html)
- [Solr Docker image](https://solr.apache.org/guide/solr/latest/deployment-guide/solr-in-docker.html)
