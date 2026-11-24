# Java 21 and browser authentication decisions
These decisions record the implementation tradeoffs and their costs. The validation reports and plans describe the scope and evidence. This branch requires Java 21. The rulings below retain their implementation sequence; the final source revisions and verification appear at the end.

## Java 21 style gate

- proceed inline under the existing modernization/build authorization and the user's explicit Java21-first steering. Cost: style-engine migration may expose stricter historical checks; preserve policy and record semantic differences, not silent suppressions.

- retain declared-exception documentation, the old policy, using an AST-scoped compatibility filter only for new body-throw diagnostics. Why: enabling new validateThrows also adds a different policy for unchecked throw statements; that is not required to parse Java21. Cost: unchecked exceptions absent from method signatures remain optional documentation, as before. Add positive body-throw and negative declared-throw fixtures to prove the boundary. No filename/broad suppression or changed zero-warning threshold.

- fix existing blocking Aislop errors in touched files, as the user's AGENTS instructions require, although the initial style spec aimed for mechanical changes only. Restore/report interruption for callers waiting on a connection or shared prefetch; report TagManager close failure; log exceptional cleanup and idle-worker interruption. Targeted regressions cover behavior changes. Cost: callers previously insulated from interruption or close errors now receive the declared exception.

- retain three verified Aislop false positives without changing rules. WebService intentionally tries JWT interpretation for a Token header before ordinary-token validation; the catch is part of its supported authentication protocol. BatchWriter statistics concatenate SQL identifiers supplied by model metadata/constants through ObjectStoreWriter -> Batch -> activity map, not request values; JDBC parameters cannot represent table identifiers. Cost: those diagnostics remain visible, and any future caller accepting arbitrary identifiers must validate them.

- a touched publication importer reads its Berkeley DB cache from java.io.tmpdir with unrestricted ObjectInputStream. Treat this as a real blocking Aislop finding and add a per-stream class/resource filter, preserving HashMap/String/ArrayList publication format. Cost: unexpected classes, excessive nesting or publication entries larger than8MiB fail the import explicitly. Isolate the cache decoder for actual serialized-object tests; no arbitrary code or external service involved in tests.

- earlier runtime/auth/BlueGenes commits are outside this incremental review; retain their earlier validation, and run deployed smoke checks on the resulting WAR. Cost: this review does not re-audit old behavior.

- the fork comparison is informational, not a choice or merge of an upstream base; defer that decision to coordinated integration. Cost: it is not an executable merge plan.

- hosted checks cannot cover an unpushed revision; run them after the single fix pass and fork push. Cost: local evidence alone is not hosted evidence.

- refine the cache-limit policy after review: reject damaged existing entries explicitly, but skip caching (with a warning) for valid newly fetched publications outside limits. This preserves the export and prevents writing entries the next run cannot read. Cost: oversized valid records are fetched again on each run; encoding validates by decoding once, adding bounded allocation/CPU per cache write.

- all Java21 implementation/local gates are finished and pushed; while hosted Checkstyle45298956447 and InterMineCI45298956306 run, resume only the isolated BlueGenes frontend test baseline to avoid idle wait. Prioritize any Java21 hosted failure and hold the paired CI pin until this exact commit passes. Cost: BlueGenes verification begins before hosted confirmation, but does not change or integrate backend code.

## Browser authentication

- retain authorized inline execution and existing isolated branches; security design decisions are implementation choices already authorized, not permission to change production accounts. Cost: process-local pending attempts require documented deployment affinity.

- transaction completion is serialized with cancellation; a confirmed cancellation waits for in-progress transfer, but network failure cannot guarantee remote cancellation. Cost: local logout can leave uncertain remote state and must warn.

- use a dedicated JSON transaction service rather than inheriting WebService; its private authenticate/error handler can include invalid token strings and full request parameters in errors/logs before execute. Explicit safe JSON, form POST validation and bounded field handling preserve the output contract. Cost: this server-to-server endpoint does not inherit optional CSV/JSONP/Basic-auth features, which are neither required nor appropriate for credential handoff.

- add POST transactions/revoke for token-specific cleanup after transaction expiry. Five-minute transaction retention cannot revoke an acknowledged 24-hour token once its handle is gone; late login/init responses must not force account-wide logout of a newer session. The extra operation proves possession of the token, revokes only temporary credentials, tolerates already-invalid tokens and rejects permanent credentials. Cost: one additional documented endpoint, carried into the Ring adapter and frontend cleanup contract.

- use an immutable operation-parameter map instead of a switch expression until the separately planned Checkstyle/dependency modernization. Cost: this Java21 runtime milestone still uses the legacy style parser; no dependency upgrade is hidden in auth work.

- use these fresh full-suite/deployment results for the task completion contract without redundantly repeating the same full suite after a documentation/test-only commit. Cost: hosted exact-source CI remains an additional check, not inferred success.

- track active five-minute reservations separately from bounded browser contexts (10,000 contexts, 24h inactivity); allocate contexts only on auth operations. Why: capacity must count active attempts and normal page visitors must not fill the auth store. Cost: deployments exceeding retained context capacity reject new auth explicitly; pending remote handles are independently expired by InterMine.

- frontend OAuth initiation sends an opaque request ID, echoed with mine/root in init HTML; frontend persistence checks it again before accepting credentials. Why: Ring can discard a render before response construction, but cannot retract an HTML response already in transit when logout occurs. Cost: frontend request persistence is required for OAuth handoff; it supplements, never replaces, the server's random state and transaction authority.

- use the fresh full CLJS and paired browser/API results rather than repeat unchanged tests through task-done. Production artifact serving verifies the build interrupted at the daemon boundary. Cost: no recoverable build exit code, but compiled output and serving behavior are verified. Existing8 authenticated/2 public list snapshots, including members, are byte-identical.

- preserve fresh completed backend25/129, frontend118/461 and paired browser/API results rather than repeat unchanged suites solely for bookkeeping. Hosted workflow will repeat them on the committed pair; any review fix receives focused RED/GREEN and applicable whole-suite verification. Cost: tests are tied to source changes, not repeated at every documentation commit.

- real providers and multiple-process OAuth remain deployment validation, with required explicit callback URLs and affinity; local fixtures prove the application protocol. Cost: live provider setup and non-affine replicas remain unverified.

- preserve the existing anonymous-profile merger rather than expanding this pass to crash-atomic multi-list migration. Cancellation serializes with the existing transfer; database failure during transfer remains a recovery limitation. Cost: an interrupted database transfer can need operator reconciliation.

- retain registration temporary-token and provider transport contracts; fix account-wide logout to revoke all temporary credentials. Cost: registration can retain its existing extra temporary token until logout/expiry, and historical providers still need operator validation.

- Java21 style/cache changes retain their separate completed review and exact hosted evidence. The original Alliance checkout is untouched. Cost: this authentication review does not repeat the separate style/cache audit.

- use a bounded process-local SessionStore with retired keys valid only for cancellation of their original generation for five minutes. Retired keys cannot deliver identities, initiate login or be resurrected by stale writes; generation-mismatched responses cannot rotate into live authority. Cost: overlapping requests can require a fresh login; canceled-cookie aliases consume bounded short-lived storage.

- bind stored identities to exact service roots and discard legacy entries with unknown provenance. Exclude lists/templates from persistent boot cache and sanitize prior caches on boot/logout. Cost: existing users must sign in again once, and lists/templates require a network refresh after reload.

- retain the separately merged table-row fix and its regression tests; auth review scope is unchanged, and exact hosted339371eaf0 validates the combined source. Cost: auth review does not duplicate the separate table-row review.

- start the paired BlueGenes hosted workflow against final backend339371eaf0 while its two hosted client jobs finish. The full local core/deployed/browser checks and hosted core/bio/database/browser/style gates already pass. Both complete hosted workflows remain required before final validation; this refines the earlier hold-pin ruling to avoid serial CI waits. Cost: if either remaining backend job fails, the pin and paired workflow must be corrected and rerun.

## Deferred minor

A delayed OAuth initiation can navigate the tab back to its originating mine after a mine switch. Identity ownership remains checked against that root and request.

## CI transport decision

- prefer Apache's primary CDN for the unchanged Solr 10.1.0 archive, retain the pinned SHA512, report archive fallback explicitly and bound download times. A canceled run's log showed 29 minutes downloading the archive before any client tests. The complete CDN artifact downloaded locally in 12.17 seconds and in the hosted Python job in about six seconds; both matched the checksum. This is a separately diagnosed CI transport issue, not another authentication review. Cost: archive fallback remains slower and can hit the explicit 60-minute job deadline.

## Current verification

InterMine `5d246cd54d4b6ab2810dcac8fc91ad9325c7ba94` and BlueGenes `295dc278367e3ebd484e61c96aeda0551c35878c` are pushed to the personal maintenance branches. Local core (2,287), deployed API/browser (26), backend (30 tests/176 assertions), frontend (127 tests/484 assertions), paired API (2) and Chromium (2) checks pass. Existing lists and members are unchanged. The Alliance tool suite passes 89 checks with two optional biological-mine checks skipped because that fixture is unavailable.

Aislop reports Java96 (four reviewed false positives,72 nonfixable warnings,zero fixable) and BlueGenes100 (no errors/warnings; Clojure semantics unsupported). See the [Java assessment](java21-style-validation-2026-11-23.md#aislop-assessment) for the retained findings.

Hosted verification passed for both exact revisions: [InterMine](https://github.com/nuin/intermine/actions/runs/45454653234), [Checkstyle](https://github.com/nuin/intermine/actions/runs/45454653275), and [paired BlueGenes](https://github.com/nuin/bluegenes/actions/runs/45454677448). InterMine's six jobs passed; BlueGenes' unit and production integration jobs passed. The hosted unit log confirms 30 backend tests/176 assertions and 127 frontend tests/484 assertions, with zero failures.

Documentation-only commits after these revisions do not change the validated executable sources. The paired BlueGenes workflow pins the backend revision above.

The user confirmed on 2026-11-24 that the InterMine and BlueGenes names will be retained and development will be coordinated with Gos Micklem. This supersedes the earlier renaming proposal. Biological workflow validation and the primary UI cutover remain subsequent work, sequenced with the shared integration plan. The Employee fixture proves authentication and list behavior, not biological tool correctness. Completed Alliance styling is already present in the isolated BlueGenes branch; the original Alliance checkout and Claude's worktrees remain untouched.
