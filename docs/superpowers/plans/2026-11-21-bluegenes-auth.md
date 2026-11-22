# Alluvium authentication integration implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans inline, task by task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Secure Alluvium's OAuth callback and asynchronous login lifecycle while preserving mine-specific identity and anonymous work.

**Architecture:** Ring owns browser correlation and request generations. A new opt-in InterMine transaction API separates provider verification from anonymous-profile transfer, with atomic cancellation and token-specific cleanup. Existing REST callback and account-wide logout contracts remain available to other clients.

**Tech Stack:** Java 21, Gradle 8.14.7, Clojure/Ring, ClojureScript/re-frame, PostgreSQL 18, local HTTP fixtures and Playwright.

**Spec:** `docs/superpowers/specs/2026-11-21-bluegenes-auth-design.md`

## Global Constraints

- Java 21 floor; work only on the existing maintenance branches and isolated BlueGenes clone.
- Preserve the original Alliance checkout and runtime databases/lists. No destructive setup or real-provider credentials.
- State contains at least 256 random bits, lifetime five minutes; at most 10,000 active transactions per process. Fail explicitly at capacity after removing expired entries.
- One active OAuth attempt per browser context. Reject mismatched/repeated security parameters before exchange.
- Preserve configured mine/root mapping, API-token architecture, profile ownership and multi-mine behavior.
- OAuth requires an explicit public application URL; HTTPS except loopback development. No fallback to the legacy exchange on unsupported servers.
- Ordinary logout retains account-wide temporary-token revocation; superseded attempts revoke only their issued token.
- Confirmed cancellation prevents later transfer. Network failures clear local state and report unconfirmed remote cancellation.
- No tokens, authorization codes, state, transaction handles or upstream bodies in logs/errors. Keep auth/init responses uncached.

## Review Focus

- Logout/new-login can arrive during exchange, completion, session rotation or init rendering; stale session snapshots must not restore identity or transfer anonymous work after confirmed cancellation.
- Two independent browsers or mines can use the same provider without sharing state, anonymous work or tokens.
- Canceling one completed attempt must preserve a newer token for that account; account-wide logout keeps its existing semantics.
- Expiry and capacity cleanup cannot leave usable unacknowledged tokens or evict another active transaction.
- A timeout does not prove remote cancellation, and retrying completion must not transfer a profile twice.

## Task 1: Deferred InterMine OAuth transactions

**Files:** `intermine/api/src/main/java/org/intermine/api/profile/ProfileManager.java`, its `ProfileManagerTest.java`; new `intermine/webapp/src/main/java/org/intermine/web/logic/oauth2/OAuthTransactions.java`; `InterMineContext.java`; new `webservice/server/oauth2/TransactionService.java`, `TransactionServlet.java`; `WEB-INF/web.xml`; `Constants.java`; new `webapp/src/test/java/org/intermine/web/logic/profile/OAuthTransactionIntegrationTest.java`; reuse `OAuthHttpFixture.java`.

**Interfaces:** Produce `ProfileManager.revokeTemporaryToken(String token, Profile owner): boolean`, which only removes the matching limited token. Produce `OAuthTransactions` with `prepare(Profile anonymous, String provider, URI redirect, String state)`, `exchange(String handle, String code)`, `complete(String handle)`, `acknowledge(String handle)` and `cancel(String handle)`. Prepare returns handle/authorization URL; exchange returns verification success; complete returns the existing `output` identity/token/renamedLists shape. Expose POST `/service/oauth2/transactions/{prepare,exchange,complete,acknowledge,cancel}`. Keep handles in body parameters; reject duplicate required parameters. Existing endpoints remain unchanged. Bump the API metadata version from 35 to 36 for these additions.

- [ ] Add token revocation tests: remove one token, preserve another for the same profile, reject a different owner and permanent keys, and tolerate already-removed temporary tokens. Run focused API tests; expected missing method/failing assertions.
- [ ] Implement atomic limited-token lookup/removal without a contains/get race. Run focused tests; expected pass.
- [ ] Add public-service tests with the local provider fixture: prepare and exchange leave anonymous lists/preferences/history unmoved; complete transfers once; repeat complete is idempotent; cancel before exchange/after verification prevents transfer; cancel during commit waits and revokes only the transaction token. Capture logs to assert no secrets.
- [ ] Add clock-controlled expiry and capacity tests, cross-handle isolation, missing/wrong/replayed handle/code, provider failure and acknowledgement behavior. Run focused tests and observe the missing transaction behavior.
- [ ] Implement the per-application transaction lifecycle with bounded storage and injectable clock. Exchange outside the transaction lock; recheck ownership before storing verification. Serialize complete/cancel. Reuse existing OAuth provider verification and anonymous merge logic; token creation occurs only at complete. Expired unacknowledged completions revoke their token.
- [ ] Wire lifecycle initialization/shutdown, servlet mappings and metadata. Run focused OAuth tests, full core build/install/checkstyle and WAR verification; expected no failures or retired provider regressions. Commit and push the independently verified API addition.

## Task 2: Browser-bound Ring OAuth lifecycle

**Files (BlueGenes):** new `src/clj/bluegenes/auth/transactions.clj`, `src/clj/bluegenes/auth/intermine.clj`; `ws/auth.clj`, `handler.clj`, `routes.clj`, configuration/docs; new `test/clj/bluegenes/auth_integration_test.clj` and local fixture helper.

**Interfaces:** Consume Task 1's five POST operations. Produce a backend-owned authorization URL response from `/api/auth/oauth2authenticator`; callback parameters remain provider/state/code. Add `BLUEGENES_PUBLIC_URL` (absolute application URL including deployment path). Session holds an opaque browser context reference; authoritative state tracks attempt, mine, roots, provider, callback, deadline, remote transaction and delivery status.

- [ ] Add tests through the public Ring handler and a local HTTP InterMine fixture, using real session cookies: valid flow, cross-browser state, wrong/missing/duplicate provider/state, stale expiry, denial, replay, redirect tampering, namespace/root mismatch and default-backend-root mapping. Observe rejection tests failing against the current handler.
- [ ] Implement SecureRandom state, exact public callback construction, safe authorization-URL assembly and atomic consumption. Preserve provider-specific callback URIs and cookie rotation. Reject unsupported transaction endpoints without trying the legacy flow.
- [ ] Add barrier-driven tests for two callbacks, logout/new login during exchange/completion, stale Ring response writes and init delivery after logout. Assert exchange/transfer/revocation counts and that no rejected identity enters HTML. Observe each race regression before fixing it.
- [ ] Implement authoritative lifecycle checks and serialized completion/cancellation using Task 1. Resolve identity/init data through the live context, not an embedded stale session identity. Acknowledge only an owned delivery; invalidate prior attempts on new login/register/logout. Bound waits and retain safe warnings for unconfirmed cancellation.
- [ ] Sanitize upstream errors, disable auth/init caching, configure Secure cookies for HTTPS, document process-local sessions and required public URL. Run `lein test-backend` and `lein cljfmt check`; expected all pass. Commit.

## Task 3: Frontend request ownership and paired account behavior

**Files (BlueGenes):** `src/cljs/bluegenes/events/auth.cljs`, `events/boot.cljs`, supporting subscriptions/effects as needed; new `test/cljs/bluegenes/auth_test.cljs`; existing `test/browser/intermine6.spec.cjs`, `test/integration/intermine6.test.cjs` and fixture helper.

**Interfaces:** Each auth operation carries initiating mine, service root and generation into success/failure handlers. OAuth success follows the backend's complete URL without appending redirect/state. Stale results cannot replace another mine or a newer authentication generation. Logout warnings distinguish local clearing from confirmed remote revocation.

- [ ] Add delayed-response tests for mine switches, login after logout, overlapping logins and late failures. Assert per-mine token/local-storage/list changes. Run focused CLJS tests and observe current behavior failing.
- [ ] Implement request ownership, generation checks and origin-mine refresh. Preserve renamed-list notices and boot token validation. Follow backend-generated OAuth URLs.
- [ ] Add tests for revocation failure warning and local logout cleanup, then implement safe messages. Add paired list/preference retention and ownership checks using disposable accounts.
- [ ] Run all CLJS tests, build production assets sequentially, restart the task server and run browser/API suites against Task 1's API. Expected all applicable checks pass and fixture cleanup preserves existing lists. Commit.

## Task 4: Exact-source integration and review

**Files:** both CI workflows as needed, BlueGenes configuration/operator docs, InterMine modernization decision record.

- [ ] Pin the paired workflow to the tested InterMine commit, run backend/CLJS/tool/browser/API and relevant live-client gates, and inspect hosted results for both exact revisions.
- [ ] Run Aislop on both ranges. Fix errors/fixable warnings and surface nonfixable warnings.
- [ ] Complete one fresh whole-project review, grade by effect, and make one Important/Critical fix pass with regressions and green suites. Do not re-review; record deferred minors and rulings.
- [ ] Commit/push verified work and record sources, tests, configuration and known deployment boundary. Continue to Alluvium display identity, scientific workflow validation and primary-UI cutover.
