# Modernization decisions and remaining limits

This records the decisions made while executing the requested PostgreSQL → search →
browser authentication → OAuth sequence. Measurements and source/CI references live
in the individual validation records. This is a development-fork migration, not an
operator production deployment or a complete security assessment.

## PostgreSQL

1. Continue inline in the existing maintenance checkout to preserve validated caches.
   Concurrent implementation would require a separate worktree.
2. Treat the selected sequence and prior commit/push request as authorization for the
   concrete migration plan. Operator production databases remain outside scope.
3. Validate PostgreSQL with the then-supported Java 8/21 matrix, then require Java 21
   for the next major release as requested. Java 8 consumers need the legacy line.
4. Propagate failed metadata writes after rollback. Callers that previously received
   false success now receive the database error.
5. Repair blocking metadata SQL and large-object cleanup findings in the touched class.
   The wider patch requires fresh database and restore checks.
6. Prepare the independent Python verifier while Java tests run. Acceptance still
   waits for the completed Java gates; no overlapping Java/build edits.
7. Commit the migration fixture, verifier and CI wiring together so CI never references
   absent files. The two logical tasks do not have separate implementation commits.
8. Advance snapshot format to version 2 after repairing row encoding. Old manifests
   must be recaptured on both sides; format 1 cannot establish the new guarantees.
9. Record completion with a documentation-only `[skip ci]` commit after exact-source
   checks pass. Executable content must remain identical to the validated source.

## Search and Java 21

1. Continue inline on an isolated maintenance branch with validated caches. Concurrent
   edits would require separate checkout/build isolation.
2. Adopt Java 21, Solr 10.1, Lucene 10.6 and the next-major snapshot under the user's
   selected scope. Downstream mines must upgrade Java and rebuild search indexes.
3. Keep invalidated heap-backed directories readable by active readers, closing each
   reader/analyzer and allowing released cached directories to be reclaimed. Closing
   a directory immediately would break active queries; retained references delay GC.
4. Preserve the global public repository map for existing UI consumers while search
   resolves document IDs through repository/item mappings. Removing it would hide
   lists published by other repositories.
5. Publish new indexes optimistically to avoid nested-lock inversion; close discarded
   candidates and invalidate shared-list caches. Incorrect coordination could expose
   stale results or deadlocks, so concurrent mutation regressions are required.
6. Run the fresh review and hosted CI concurrently after local task completion. Any
   resulting source correction must pass regression/full checks and fresh hosted CI.
7. Leave atomic search-core replacement to operators, using separate fresh cores as
   documented. Rebuilding an active core in place can expose partial/empty results.
8. Preserve result identities, counts and facets, not exact relevance scores or tie
   order. Consumers relying on ranking need their own acceptance checks.
9. Retain the existing in-memory autocomplete batch. Very large sources can still
   exhaust heap; this migration does not redesign the indexing algorithm.

## Browser authentication and list ownership

1. Start the next isolated branch while search CI finishes, with no overlapping edits
   or builds. Any search correction remains a prerequisite for integration.
2. Bound the audit to anonymous ownership transfer, browser session completion,
   administrative authorization and confirmed account-name rendering paths. Broader
   web security and framework replacement remain additional work.
3. If source-list removal fails after a valid destination copy saves, preserve that
   copy and propagate the failure. Recovery may require reconciling duplicates;
   deleting the destination could destroy the only surviving copy.
4. Use a dedicated random administrative token rather than Struts' shared transaction
   token, which query-builder forms also replace. The helper must enforce atomic
   consumption and identity-transition reset.
5. Extend account-name escaping to API-key and sharing controls, with test-only jsdom
   interaction coverage. Runtime jQuery is unchanged; legacy control compatibility
   is checked through DOM interactions and deployed pages.
6. Do not claim cross-JVM or crash-atomic list transfers. Multiple processes or crashes
   can still require reconciliation after partial persistence.
7. Defer OAuth protocol, logging and claim handling to the immediately following
   increment. Those risks persisted until the OAuth work was integrated.
8. Include preference inputs and OAuth message arguments in that following increment,
   while leaving broader rich-HTML flash-message contracts for a separate audit.
   The targeted sinks are now covered; other legacy markup paths may remain.
9. Require measured live-data preservation and exact-source hosted CI independently
   of code review. A review alone cannot establish deployment or fixture safety.
10. Repair the test-only parallel precompute recorder exposed by hosted CI with a
    synchronized collection. Production precomputation is unchanged; a fresh full
    run and final-source CI verify the diagnosis.

## OAuth

1. Reject an already-claimed callback after a newer identity transition. Validate the
   ticket and create/install the profile under the same session lock; perform HTTP
   outside that lock. Incorrect coordination could replace a newer login.
2. Continue on an isolated OAuth branch while authentication CI finishes. The earlier
   phase must pass and integrate before OAuth reaches beta.
3. Use Nimbus 11.39 behind shared adapters. Struts, Tiles and Jersey remain legacy
   frameworks requiring a separate migration.
4. Make explicit resource body mode a form POST because the former GET dropped its
   body. Operators using that mode must verify provider POST support.
5. Interpret absent token type as Bearer for compatibility and reject explicit other
   types. Non-Bearer provider support needs a separate design.
6. Keep REST state/redirect correlation client-owned. Clients remain responsible for
   those checks; this release does not silently add browser cookies or PKCE.
7. Exclude raw causes from public service exceptions because the generic logger prints
   nested causes. Diagnostics retain sanitized phase/status, losing raw provider
   detail while preventing credential disclosure.
8. Coordinate logout under the same session lock as callback completion. Otherwise
   an older response could reinstall a logged-out identity.
9. Escape preference notification call sites as well as JSP attributes. The generic
   rich-HTML notification contract remains unchanged, so unrelated callers require
   their own output-context audit.
10. Enable the existing custom-preference UI setting in testmine only. It changes the
    example UI but leaves production configuration under operator control.
11. Leave live compatibility of historical remote providers operator-validated; tests
    use local synthetic providers and no real credentials. Old endpoints may fail.
12. Fail closed across in-flight session passivation/process migration and require a
    fresh login. Session movement can interrupt an authorization attempt.
13. Preserve userinfo identity keys and defer PKCE, ID-token authentication and
    historical account migrations. Providers requiring those features need more work.
14. Keep broader rich-HTML paths and previously reviewed increments outside the OAuth
    review-fix pass. Earlier evidence remains required; broader security is unproven.
15. Require actual hosted CI on the corrected source independently of review. Local
    results cannot establish the hosted environment's behavior.
16. Push the review fixes after focused tests and Checkstyle, allowing hosted checks
    to run alongside the full local suite. Both must pass before beta integration;
    another executable change resets the exact-source gate.

The fresh OAuth review's two Important findings were accepted: preserve native
private-use redirect schemes and reject malformed userinfo JSON before profile
creation. Each was reproduced before its correction. No second review was used as
a substitute for regression and full-suite verification.

## Deferred minor findings

- The CI migration negative check accepts any nonzero comparator exit, rather than a
  dedicated mismatch status. Its preceding positive comparison and real regression
  suite still cover the normal comparison path.
- Opening another administrative form replaces the prior form's token. Submitting
  the older form can return 403 until it is refreshed.
- Provider documentation could state more clearly that overriding `url.token`
  selects the historical custom-provider path and also requires `messageformat`
  and `responsetype`. The configuration behavior itself is unchanged.
