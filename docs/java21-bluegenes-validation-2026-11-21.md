# Java 21 readiness and paired UI validation

Executable revisions: InterMine `323a9cf409b0efd10a5e36730555acb6150da830` and
BlueGenes `2f6df74cf6ab6e025600b38b04fe28a4b2c03e73`, pushed to the user's
`maintenance/java21-bluegenes` and `maintenance/java21-intermine6` fork branches.
The supplied Alliance checkout was preserved; UI changes use an isolated clone.

## Changes and evidence

- Both build/runtime paths require Java 21. BlueGenes has a shared guard, a
  backend-test entry point, a reproducible npm lockfile and a local InterMine 6
  profile at `/bluegenes/intermine6`.
- Java's bounded HTTP registry adapter replaces Jersey/JAX-RS. Fixtures check
  query/path encoding, strict JSON, redirects, missing data and a stalled body.
- Biological report URLs now come from each request, including nested protein
  and organism URLs. The regression reproduced a null base on the first report;
  tests cover consecutive origins and configured identifier bases.
- Missing or invalid list creation dates no longer crash list/results pages.
- Headless Chromium runs the CLJS suite and production UI. CI builds a fresh
  PostgreSQL 18/Solr 10/InterMine 6 fixture pinned to the executable revision.
- The production browser logs in, sees a private list, reloads with that identity,
  logs out and reloads without access. Disposable users/lists are deleted.

Local verification on the reviewed source:

| Check | Result |
| --- | --- |
| Core build/install/Checkstyle | 2,265 tests; zero failures/errors/skips; 2m 2s |
| Testmine WAR | Built in 59s; 25 required providers; 40,092 unique runtime classes |
| Retired packages | Jersey/JAX-RS/Oltu providers absent |
| Registry tests | 11 passing |
| Direct biological report regressions | 2 passing after observed null-base failures |
| BlueGenes backend JVM guard | 1 test / 6 assertions |
| ClojureScript | 104 tests / 422 assertions; zero failures |
| Production compiler | 28.087s |
| Production browser | 2 passing, including authenticated private-list lifecycle |
| Paired API lifecycle | 2 passing |
| Deployed InterMine API | 23 passing |
| JavaScript client | 405 passing; one existing pending Unicode employee query |
| Python client | 74 passing |
| Alliance tools on Node 22 | 89 passing; two unavailable live-fixture skips |
| Preserved lists | Identical names, types, counts and members: 8 authenticated / 2 public |
| Aislop | 100 in both repositories; no errors or fixable warnings |

The final WAR's formatter bytes match the compiled reviewed source:
`601cb75286ef2e42630fdb789b97a054a5d44430ccdeb052324eea555a569dea` (SHA-256).

Hosted verification of earlier InterMine `27812975c4` succeeded in
[InterMine CI](https://github.com/nuin/intermine/actions/runs/44975638423) and
[Checkstyle](https://github.com/nuin/intermine/actions/runs/44975638391).
The first [paired run](https://github.com/nuin/bluegenes/actions/runs/44976150762)
passed its InterMine 6 integration job but exposed Node 22's rejection of a
directory passed to `node --test`. Explicit test-file discovery fixes that; all
seven tool packages pass under Node 22.

All final-source hosted checks passed:
[InterMine CI](https://github.com/nuin/intermine/actions/runs/44978033651),
[Checkstyle](https://github.com/nuin/intermine/actions/runs/44978033652), and
[paired BlueGenes CI](https://github.com/nuin/bluegenes/actions/runs/44978035589).
Both BlueGenes jobs passed: unit verification and the fresh InterMine 6 integration fixture.

## Review and decisions

One fresh whole-project review found two Important issues, both addressed: the
first-report URL defect and missing authenticated-browser coverage. The latter
was a test gap; the new browser test passed without a product-code login change.
No second review was used to substitute for regression verification.

Decisions made during implementation:

1. Continue the already-authorized inline workflow without another process menu.
2. Regenerate obsolete npm v1 lock metadata because clean `npm ci` rejected the
   git dependency metadata. Existing package versions and git revision were kept;
   the fresh hosted install confirms the resulting lock works.
3. The initial decision to postpone the shared report base URL defect was
   superseded after review: it broke a public cold-start entry point and was fixed.
4. Retain CircleCI's old Biotestmine job as an explicitly labelled older-server
   compatibility check. This requires maintaining two fixtures until consolidation.
5. Treat the core model as generic API/production-rendering evidence. It does not
   establish biological workflow or tool-output parity.
6. Add real browser coverage for the authentication test gap without manufacturing
   a failing product implementation solely to claim a red-to-green fix.

One review minor remains for release hardening: registry responses have a deadline
but no byte-size cap. Two existing nonfixable Aislop warnings remain in
`SemanticMarkupFormatter`: file size and a canonical schema URL flagged as an
environment URL. BlueGenes' unused-code scan reports that Knip is not configured.

## Continuing work

This is Java 21 readiness, not the completed primary-UI replacement. The next
projects cover browser-bound OAuth, cancellation and profile ownership;
the Alliance style with InterMine and BlueGenes branding; biological fixtures/capabilities and
report links; legacy framework retirement; dependency and release validation.
The 2026-11-24 decision to retain the product names and collaborate with Gos Micklem
supersedes the earlier renaming proposal.

Known UI gaps recorded for that work: generic mines trigger Organism queries,
the generic API lacks biological elinks/RDF, and result-table cells lack report
hrefs. The tool skips are HGNC:11179 disease rows and HGNC:620 phenotype rows;
they require the biological fixture. The npm audit has 45 existing advisories
to assess during dependency modernization. No production deployment was made.
