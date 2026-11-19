# Backlog reliability fixes

Work follows the approved sequence: three backlog defects, release validation and integration, then a runtime dependency audit. The working branch starts from Java 21 migration commit `5ae8ade9e0`.

## Region pagination — #2451

The AJAX endpoint accepts inclusive indices. A page ending exactly at the region count sent an out-of-range final index. A separate strict comparison prevented an already-complete page loading while the overall search was still running. Both comparisons now include equality.

The Node test runs the production browser script, replacing only DOM, timers and network boundaries. Before the fix, four of eleven cases failed; all eleven pass after it. CI runs the same command on Node 22:

```sh
node --test bio/webapp/src/test/js/*.test.cjs
```

The quality scan prompted cleanup of accidental global assignments, an unused assignment and shared navigation rendering. The renderer preserves the disabled Last action until the search finishes. The remaining scan findings are:

- 79 `no-undef` reports for jQuery and six variables supplied by `genomicRegionSearchResultsBase.jsp`. These are false positives from scanning the script without its hosting page.
- Six `no-unused-vars` reports for functions called from JSP or generated HTML handlers, also false positives.
- One nonfixable warning for the existing hardcoded experimental JBrowse URL. Configuring that integration is outside this pagination fix.

No scanner rules or configuration were changed. The scan score is 92; there are no remaining confirmed errors or fixable warnings for this fix.

## Unicode data tracking — #2355

Binary COPY previously wrote the low byte of each Java character and used character counts as byte lengths. Field and source names now use UTF-8 byte arrays and their byte lengths. Database regressions fail before the change with PostgreSQL invalid UTF-8 errors and pass after closing and reopening the tracker, covering accented, CJK, supplementary and ASCII text.

The scan also identified string concatenation in the portable INSERT writer. A separate regression reproduced a syntax error with quoted names; parameter binding now preserves quotes and Unicode. Empty catches now either avoid an expected missing-table exception with `DROP TABLE IF EXISTS` or report interrupted waits while retaining the existing wait policy.

All 102 integrate tests pass on Java 21 with PostgreSQL 14. Logs are in `intermine/build/reliability-tracker-{red,portable-red,final}.log` (ignored build outputs).

The tracker scan scores 99. Three SQL injection reports are false positives: two SQL expressions concatenate only literals, and the lookup concatenates a typed `Integer`. Four nonfixable warnings remain for the existing large class, long prefetch method and nesting in prefetch/write methods. Refactoring tracker threading and lifecycle is outside this encoding fix.

## JSON object ordering — #1639

The JSON object query transformation discarded every requested order and sorted solely by ascending root ID. It now preserves requested root and single-valued reference keys and directions, using root ID as a tie-breaker. Explicit root-ID direction is retained without adding a conflicting duplicate. Collection keys order entries within each object, after its root keys and ID, so tied values cannot split one object into multiple JSON objects.

Ten regressions cover descending order, multiple keys, references, explicit IDs, default ordering, simple classes, collection grouping and flat-result pagination. Eight failed before the fix. Four run the actual query executor and JSON iterator against PostgreSQL. All 234 webapp tests and 102 integrate tests now pass on Java 21, with Checkstyle for both modules; the combined run took 1m40s (`intermine/build/reliability-java21-green.log`).

The existing `start`/`size` contract limits flattened rows, so a page boundary through an inner-joined collection may return a partial collection. This change does not redesign collection pagination. Collection sort keys apply within a parent; they do not globally order parents by an aggregate of child values.

The changed query builder has no Aislop errors or warnings; its existing simple-class workaround is reported as informational. Combined tracker/query scan score: 99.

## Review follow-up

Fresh review found two regressions before pushing: the navigation consolidation enabled Last prematurely, and sorted grandchildren could be attached to the last parent appended instead of the parent in their current row. Regressions reproduced both. Navigation now passes 15 tests. JSON assembly records the objects selected in the current row and reuses the matching parent when traversing deeper collections. The database regression asserts both memberships and references for interleaved grandchildren.

The full changed-file scan scores 91, with no confirmed errors or fixable warnings. It includes the documented 82 false-positive errors (79 hosting-page globals and three SQL expressions), six false-positive unused-handler warnings, and six retained nonfixable warnings: the experimental JBrowse URL, four tracker size/complexity findings and the existing large JSON iterator class. Two existing workaround comments are informational. No scan configuration changed.

Review also considered object-count pagination, aggregate child ordering and template JSON. The first two retain the documented API behavior; the template service already bypassed this transformation and needs separate coverage. These are not silently included in the three reported fixes.

Final verification: 15 browser tests, 102 integrate tests and 235 webapp tests pass. Both affected JVM suites passed on Java 21 and Java 8; the final webapp reruns after review took 1m09s on each runtime. Checkstyle and `git diff --check` pass. The fixes are pushed on `maintenance/reliability`; release validation continues before integration into `beta`.
