# Browser authentication and list ownership design

## Intent and authorization

The user selected PostgreSQL, search, browser authentication/list ownership, then incremental framework replacement, and authorized implementation, commit and push to the fork. Java 21 is the next-major floor. This is the browser authentication phase. The builder chooses the bounded fixes below from observed defects; these are implementation decisions, not additional user preferences. The search branch must pass its own integration gates before this branch is merged into beta.

## Evidence

The disposable PostgreSQL 18/Solr 10 test mine reproduced: successful password login keeps the anonymous JSESSIONID; anonymous API tokens retain a list transferred to a named account by login; switching from the fixture administrator to a disposable ordinary account retains the admin marker. Logout already invalidates the session. Source review found ModifySuperUserAction changes account roles without checking the caller, and UsersController exposes administrative data without an explicit role check. Tests of unauthorized role updates must use mocks before the guard exists: never change existing fixture roles to reproduce this.

## Approaches

1. Patch each login/registration path separately: smaller immediate edits but repeats ownership logic and allows flows to diverge again.
2. Share anonymous-list transfer and centralize session/profile authority, with focused checks on administrative routes: chosen by the builder, preserves the public application while fixing the confirmed defects.
3. Replace the whole web framework first: much larger migration with no immediate proof that existing authentication behavior is correct. The next phase will replace the retired OAuth library as a separate increment.

## Required behavior

- Only an anonymous source profile may transfer lists; null source is a no-op, authenticated sources never transfer private data. Destination must be a different named profile.
- Transfer creates independent list objects, preserving type, contents, description, creation time, state and key fields; uses the existing collision naming policy and ProfileMergeIssues names; removes the anonymous original only after the destination copy saves successfully. Registration and all browser/REST login merge paths use the same helper.
- Serialize competing claims of one anonymous profile. Reusing or concurrently logging in with the old token can transfer a list at most once. Failures propagate with their cause; do not report a successful transfer when persistence failed. Existing registration protection remains covered. History/preferences still merge for anonymous login. Distributed/multi-JVM transaction semantics remain an explicitly documented limitation of existing profile caches.
- Successful password and browser OAuth login rotate the servlet session ID while retaining intended session data. Authentication failure does not authenticate the session or transfer lists. Old cookie replay cannot access the authenticated account. Logout invalidates authentication. Retain public Java method compatibility where practical; internal browser entry points must use a request-aware authentication completion method.
- The current Profile is authoritative for superuser decisions. Setting an ordinary/anonymous profile removes an earlier admin marker. A stale marker must never grant server authority. Administrative views must consult current profile authority, including after a role revocation.
- ModifySuperUserAction and UsersController reject missing/ordinary profiles before reading account lists or changing anything. Role updates require POST and a valid per-session CSRF token. The existing administrator form receives that token. Negative tests do not touch real fixture roles. Read-only property views and property updates receive equivalent current-profile authorization; existing positive behavior is preserved.
- All new regression artifacts are repeatable in CI. Disposable accounts/lists are deleted; baseline runtime list identities and contents remain unchanged.

## Scope and constraints

Java21, Gradle8.14.7, PostgreSQL18, Tomcat9/Servlet4 and existing Struts/JSP remain. No password hashing change, account schema migration, production data mutation, endpoint rename, provider rollout, blanket rewrite, or claim of a complete application penetration test. OAuth protocol/provider changes belong to the following phase; test the common browser completion seam here and existing OAuth provider-link regression. Document additional findings with precise evidence and impact if outside this bounded phase. Aislop config/rules stay untouched and findings are handled under AGENTS.md.

## Validation

First observe failing tests for ownership replay/concurrency, login ID rotation, old-cookie replay, ordinary role switch and unauthorized admin actions. Add Java tests for copy independence, naming/metadata, null/authenticated source, failure propagation, current-profile authority, and admin authorization/CSRF before backend access. Use the real preserved testmine for password login, invalid password, logout and list ownership. Run full affected JVM suites plus existing live JS/PY/search/registration/OAuth checks and the new browser-auth checks. Compare saved-list snapshots, scan Aislop and verify packaged WAR. Hosted CI and one fresh final review are mandatory before beta integration.

## Left to builder

Exact helper names and location within web/logic/profile, servlet request completion method names, minimal fixture/mocking structure and which existing administrative JSP expressions are converted to current-profile checks. Keep runtime dependencies unchanged in this phase. Test-only jsdom is permitted for the confirmed account-name rendering regressions. Per-account in-memory synchronization and recovery behavior on a copy/delete failure must be explicit in the implementation plan and review focus.
