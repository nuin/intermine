# Java 21 style gate validation

InterMine 6 requires Java 21 to build and run. The shared Checkstyle engine is now
14.4.0, replacing 7.8.1 and the custom CLI/classloader workaround. Gradle's native
Checkstyle tasks produce XML/HTML reports and enforce zero warnings in all five
configured build roots. This does not change the application dependency versions.

The configuration preserves package-visible documentation requirements and the
existing declaration-only exception documentation policy. A narrow AST filter
excludes new diagnostics on `throw new` expressions; declared exceptions still
require `@throws`. The standalone fixture verifies both sides of that boundary,
including package-visible methods. No Aislop rule or configuration was disabled.

## Local evidence

| Gate | Result |
| --- | --- |
| Core build, install and style | 2,284 tests, zero failures/errors/skips; 1m 46s |
| Biology build, install and style | 92 tests, zero failures/errors/skips; 1m 5s |
| Importer style | Passed across all configured importer modules |
| Postprocess / postprocess-test style | Passed; the latter has no main Java source |
| Java 21 Gradle fixture | Records, record patterns, guarded switch and text blocks compile and pass style |
| Negative style fixture | Eight intentional warnings detected, including declared-exception and package-method documentation |
| Production report coverage | Five Gradle manifests identify 61 reports covering 1,442 Java sources, with zero violations |
| Report-parser regressions | Seven tests pass, including fixture-only results, missing reports/manifests and incomplete source coverage |
| Publication cache module | Eight tests pass, including the existing importer fixture, four decoder tests and three Berkeley DB integration tests |
| Serialization compatibility | Four modified serializable private helpers retain their measured prior serialVersionUID |

Checkstyle 7.8.1 failed the positive Java 21 fixture before the engine update.
The interruption and close-failure regressions failed before the fixes. The full
suite exposed a duplicate writer close in API test teardown; the owner now closes
it once. The cache tests initially accepted unexpected classes, excessive nesting
and an oversized entry; the filtered reader rejects all three while retaining the
existing HashMap/String/ArrayList cache format.

The fresh review identified four additional cases, reproduced before the fixes:
fixture-only reports, truncated cache records, entries accepted by the writer but
rejected by the reader, and an unbounded database value read. The report parser now
requires a manifest from every build root and checks every expected source in its
report. Genuine `NO-SOURCE` projects produce no report entries. Berkeley DB reads
return at most 8 MiB plus one overflow byte. Damaged cached publications fail with
their PMID. New entries are validated against the reader before persistence;
valid publications beyond cache limits are exported with a warning and left
uncached. Two consecutive imports verify that behavior.

The source cleanup also reports previously silent cleanup failures, logs optional
schema lookup failures, removes redundant syntax and commented-out code, and fixes
malformed Javadoc. Caller interruptions now preserve the interrupt flag and throw
the method's declared exception. An interrupted prefetch waiter leaves the shared
fetch running. Idle service workers remain available to process requests.

## Aislop assessment

The final local scan scores 96, with zero fixable warnings. There are 72
nonfixable warnings: 67 for existing broad exception declarations, large
files/functions, deep nesting and one legacy source URL, plus five false positives
for the report parser's intentional CLI diagnostic output. The legacy warnings
require separate design decisions rather than mechanical edits.

Four error-level diagnostics remain as reviewed false positives:

- `WebService.authenticate` intentionally tries JWT interpretation of a `Token`
  header before ordinary-token authentication; that supported protocol fallback
  is not a failed request being discarded.
- Two `BatchWriterSimpleImpl` statistics queries concatenate table identifiers
  derived from model metadata/constants via the writer's batch activity map.
  Request values do not supply these identifiers, and JDBC parameters cannot bind
  identifiers. A future caller accepting arbitrary names must validate them.
- `EntrezPublicationsRetriever.readCachedPublication` now installs a per-stream
  type filter before `readObject`, limits depth/references/arrays, and rejects
  input larger than 8 MiB before decoding. The database read also requests a bounded
  partial value. The scanner still flags `readObject`;
  the regression verifies rejection before an unexpected deserialization callback
  can execute. Rejected cache entries fail the import explicitly.

The cache filter uses the JDK's supported
[serialization filtering API](https://docs.oracle.com/en/java/javase/21/core/serialization-filtering1.html).
Damaged existing cache entries stop the import explicitly. Fresh publication data
outside the cache bounds can still be exported without caching.
