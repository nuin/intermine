# Browser authentication and ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent anonymous list replay, session fixation and stale or unchecked administrative authority while preserving login and registration behavior.

**Architecture:** One anonymous-list transfer helper serves registration and all login merges. Browser password/OAuth paths share request-aware login completion; current Profile controls server/JSP privileges. Administrative mutations require POST and dedicated per-session CSRF tokens.

**Tech Stack:** Java21, Gradle8.14.7, PostgreSQL18, Solr10.1, Tomcat9, Servlet3.1 compile API, existing Struts1/JSP, JUnit/EasyMock and Node22 live tests.

**Spec:** docs/superpowers/specs/2026-11-20-browser-auth-design.md

## Global Constraints

- Preserve runtime database pair intermine-runtime-fixed/userprofile-runtime-fixed; never run testmine/setup.sh on the local fixture.
- Do not mutate existing fixture accounts' roles, passwords or lists. Use disposable accounts/lists and mock negative admin requests.
- No dependency updates in this phase; retain Java21 and existing endpoint/provider configuration.
- No authenticated-source private-list transfer, and never remove a source before its independent copy has persisted.
- Stop the task-owned Tomcat before DB-backed JVM suites; run these sequentially. Restart it for real browser/REST tests.
- Inline implementation, one final fresh reviewer, one Important/Critical fix pass; no re-review. User authorization already covers commit/push and beta integration after green gates.
- Handle Aislop findings without changing configuration.

## Review Focus

- Copy/delete failure after partial persistence must retain useful recovery state, report failure with cause and never masquerade as successful ownership transfer.
- Two anonymous sources with colliding names entering one destination must not overwrite either list; two destinations claiming one source must have one winner.
- A previously privileged session must not regain authority via stale attributes, direct JSP access or a failed subsequent login.
- Missing/wrong/replayed CSRF token and GET role updates must fail before enumeration or role mutation.
- OAuth completion must rotate the session and use the same ownership guard; protocol exchanges remain for the next phase.

### Task 1: Single-owner anonymous-list transfer

**Files:** Create webapp/src/main/java/org/intermine/web/logic/profile/AnonymousProfileMerger.java and matching webapp/src/test/java/.../AnonymousProfileMergerTest.java (both under intermine/); modify LoginHandler.java, webservice/server/user/NewUserService.java; create config/ci/browser-auth.test.cjs and include it in config/run-ws-tests.sh.

**Interfaces:** Produces `public static ProfileMergeIssues transferBags(Profile from, Profile to) throws ObjectStoreException, UnknownBagTypeException` on AnonymousProfileMerger. Consumes existing Profile/InterMineBag and ProfileMergeIssues. LoginHandler.mergeProfiles retains its public signature and wraps checked transfer failures with the original cause; registration keeps its existing checked error boundary.

- [x] Add live login ownership regressions first: anonymous list disappears from old token after login; repeated and simultaneous login by two disposable accounts yields one owner; logging in from an already named account never transfers its private list. Assert actual IDs and independence when another account is deleted. Observe failures on the existing application and keep the log.
- [x] Add JVM regressions for null/authenticated source, different named target requirement, preserved metadata and membership, collision names, competing claims and copy/delete failure. The null/authenticated guard must return no changes; invalid destination throws IllegalArgumentException. Observe relevant failures through existing LoginHandler.mergeProfiles before extracting the new helper, then add helper-specific tests as it becomes available.
- [x] Implement helper: guard source/destination, synchronize source then destination (sources always anonymous, targets always named), copy lists and snapshot entries, save destination then remove source. On failure preserve cause and retain whichever successfully persisted copy exists; do not delete a valid destination copy when source deletion may already have removed its in-memory mapping. Record/document partial-transfer recovery explicitly; add failure tests for pre-copy failure and source-removal failure. Do not silently retry.
- [x] Replace registration's duplicate transfer loop with the helper. Merge anonymous preferences/history only after null/authenticated guard in LoginHandler; remove dead mergeTemplates variable/imports. Keep collision report semantics.
- [x] Run focused JVM tests and Checkstyle, then full core build/install in the established Java21 container; expected zero failures/errors/skips. Restart Tomcat and run `node --test config/ci/registration.test.cjs config/ci/browser-auth.test.cjs` for Task1 ownership cases; expected all green. Commit and record measured counts/logs.

### Task 2: Browser session and administrative authority

**Files:** Modify LoginHandler.java, web/struts/oauth2/Callback.java, web/logic/session/SessionMethods.java, web/struts/ModifySuperUserAction.java, UsersController.java, EditPropertiesAction.java, MyMineController.java; create web/logic/profile/AdminAccess.java and web/struts/ShowPropertiesController.java; administrative JSPs that currently use IS_SUPERUSER; new SessionAuthorityTest/AdministrativeAuthorizationTest under webapp/src/test/java/org/intermine/web/; extend config/ci/browser-auth.test.cjs. Add docs/browser-auth-validation-2026-11-20.md.

**Interfaces:** Consumes Task1 transfer through LoginHandler.mergeProfiles. Produces `public static ProfileMergeIssues completeLogin(HttpServletRequest request, Profile profile)` on LoginHandler: merge anonymous state, rotate ID, then install authenticated profile. Preserve existing public session-only setup methods for external Java compatibility, but internal browser authentication must call completeLogin. SessionMethods.isSuperUser reads current Profile; setProfile updates the compatibility marker. JSPs read current PROFILE.superuser instead of trusting marker snapshots.

- [ ] Add live regressions: successful login changes JSESSIONID, replay old cookie cannot see account/private list, wrong password retains anonymous state/list, logout loses account access, administrator-to-ordinary switch removes admin UI and protects properties. Do not expose runtime property values in logs. Observe failures before edits.
- [ ] Add JVM regressions for marker/profile disagreement, null profile, ordinary profile replacing admin, direct unauthorized ModifySuperUserAction/UsersController calls rejected403 before API access; wrong/missing/replayed CSRF and GET mutations rejected; valid admin POST/token preserves intended behavior; query-builder Struts token remains unchanged. Use EasyMock request/session/response and mock ProfileManager for role changes; no live fixture role updates.
- [ ] Factor credential lookup from existing session setup without changing public signatures. Route password and OAuth success through completeLogin after credential/state validation. Merge before installing authenticated profile, rotate with request.changeSessionId, and reset marker through central setProfile. Test request-aware completion with the actual test API or bounded mocks including its bag-upgrade collaborators.
- [ ] Authorize UsersController/ModifySuperUserAction/EditPropertiesAction with current profile and reject null/ordinary users. Require POST and a dedicated adminToken for both role/property writes. AdminAccess issues 32 random bytes encoded with Base64URL, stores ADMIN_CSRF_TOKEN in the session, and consumes a matching token atomically under the session lock. Reject unauthorized/token failures403 and authorized GET mutations405 with Allow:POST; preserve the separate Struts query-builder token. Clear the admin token when browser identity changes; issue it from authorized form controllers and render it as an escaped hidden field. Convert administrative JSP conditions to PROFILE.superuser to reflect revocation and avoid stale flags. If properties currently lack a controller, add a small authorized controller in the same package and change the existing /showProperties mapping; it issues the token and returns the existing view. Normalize an unauthorized persisted users tab to lists so administrator-to-ordinary login still reaches a usable MyMine page.
- [ ] Run focused JVM suites and Checkstyle, then full core build/install. Restart Tomcat, run all persisted search/registration/oauth/browser-auth tests, JS405/PY74 client suites, compare runtime saved-list snapshot, verify WAR providers and Aislop. Document limitations and measured results, commit and push.

## Completion and integration

Each task completion requires its local gates with measured results; hosted CI and final review are separate branch integration gates. Once both tasks are complete, dispatch the skill-required fresh whole-branch review with the exact base and review focus. Fix Important/Critical findings with observed RED→GREEN plus affected full suite. After final-source hosted CI and Checkstyle pass, archive the ledger/review package, integrate into fork beta with existing authorization and push. Preserve task-owned validation volumes for the next framework phase.
