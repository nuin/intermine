# BlueGenes authentication integration

## Intent and scope

Project 2 of the authorized Java 21 / Alliance BlueGenes replacement program.
Keep InterMine accounts, lists and preferences authoritative while making the
primary UI safe to use with InterMine 6. Retain the InterMine and BlueGenes names,
as confirmed on 2026-11-24; package names and the API contract remain compatible. The user has authorized the
whole program and implementation choices; this document records the builder's
decisions without reopening the accepted UI or Java choices.

Work in `/private/tmp/intermine-bluegenes-java21` and the existing InterMine
maintenance branch. Preserve the original Alliance checkout and all runtime data.
Use local fake services and disposable accounts. Real provider credentials,
production deployment, and changing identity ownership are outside this project.

## Current behavior

The compiled UI's password login, private lists, reload persistence and logout
have now passed against InterMine 6. Ring restricts authentication to configured
mine roots and supports a separate backend root for the default mine. Successful
credentials produce an API token that the browser stores per mine.

OAuth is incomplete: Ring stores service/mine/redirect in its session but creates
no state, binds no provider, and checks neither expiry nor replay. The callback
forwards caller-supplied provider/state/code directly to InterMine. InterMine's
REST contract intentionally leaves state correlation to its client. Error paths
can expose upstream exception text. Login/logout can race a callback. Frontend
login callbacks use whichever mine is current when the response arrives.

Ring uses its default in-memory session store. Copying a pending map into a
session is insufficient for atomic consumption: concurrent requests can read
the same map, and a response started before logout can write stale session data.

## Approach

Keep the existing direct API-token architecture and add a server-owned OAuth
transaction lifecycle, plus frontend request ownership checks. Replacing all
direct API access with a session proxy would expand the project into every query,
tool and multi-mine request. Moving state entirely into browser local storage
would not secure the public Ring callback or provide atomic consumption.

This choice follows the existing REST boundary and the session-bound, one-use
state requirement in [RFC 9700, sections 2.1 and 4.7](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.7).
It does not claim new provider-side PKCE support; provider token exchange and
provider-ID identity authority remain in the already-tested InterMine adapter.

## Required behavior

### Initiation and binding

- Generate an unpredictable state with at least 256 bits from Java SecureRandom.
  Bind it to a server-owned browser/session context, exact configured mine,
  public and backend service roots, provider, anonymous token and callback URI.
- Require an explicit public application URL for OAuth, including deployment
  prefix, rather than trusting arbitrary submitted origins or forwarded headers.
  A missing or invalid setting produces an actionable configuration error.
  Non-OAuth usage remains available. Document HTTPS for production and allow
  HTTP only for a loopback development URL.
- The backend assembles the complete authorization URL, with exactly one state
  and redirect_uri. The frontend follows it without appending security parameters.
  Reject a mine namespace/root mismatch and malformed or repeated parameters.
- One active OAuth attempt per browser context. Starting another attempt, password
  login, registration or logout invalidates the preceding attempt. Default expiry
  is five minutes. Bound live transaction capacity; remove expired entries and
  reject excess initiation rather than evict another active browser's transaction.

### Callback and races

- Reject missing, mismatched, expired, replayed, cross-session or wrong-provider
  callbacks before any token exchange. A valid state is consumed atomically;
  two concurrent callbacks can produce at most one exchange.
- Provider denial and missing/malformed code terminate the matching attempt and
  produce a generic safe message. Never include state, code, tokens, provider
  response bodies or raw exception messages in logs or displayed errors.
- A callback already exchanging when logout or a new login occurs cannot restore
  authentication. Check transaction ownership again after exchange. Revoke any
  token issued to a superseded transaction and never expose it to the frontend.
- Cancellation also prevents anonymous lists, preferences and history from moving
  to a superseded identity. The current InterMine callback merges profiles during
  exchange, too early for Ring to enforce this. Add an explicit opt-in deferred
  transfer API: exchange verifies the provider identity without transferring the
  anonymous profile, then an owned completion commits the transfer. Preserve the
  existing callback contract for other clients. Do not silently use the old flow
  if the server lacks deferred-transfer support. Serialize completion and
  cancellation around the transfer boundary; whichever commits first determines
  the outcome. After logout returns, no earlier attempt can commit a transfer.
- Superseded-attempt cleanup revokes only its issued token. Ordinary logout keeps
  the existing account-wide temporary-token revocation behavior. InterMine's
  current logout endpoint cannot serve both purposes, so add a token-specific
  revocation operation and prove it preserves a newer token for the same account.
- Use a dedicated InterMine transaction API, available only on the upgraded
  server, for prepare/exchange/complete/acknowledge/cancel and token-specific revoke. Prepare binds the
  anonymous profile and returns an opaque handle. Exchange verifies the provider
  without creating an authenticated token or moving data. Complete is the
  serialized transfer boundary and returns the existing identity/token response.
  Repeated completion for the same handle returns the same result without a
  second transfer. Acknowledgement marks delivery; cancellation revokes only that
  transaction's token. Expired unacknowledged completions revoke their token.
  After transaction expiry, token-specific revoke can still discard a late response
  without invalidating newer credentials for the same account. Never send the
  transaction handle to the external provider or frontend.
- Do not use a stale Ring session snapshot as proof that a transaction is still
  valid. Session rotation, callback completion and init-data delivery must consult
  authoritative lifecycle state. Test both callback-before-logout and
  logout-before-callback-completion orderings with controlled barriers.
- Hand the resulting identity and renamed lists to the matching mine exactly once.
  Preserve session rotation and HttpOnly/SameSite=Lax cookies; set Secure for an
  HTTPS public application URL. Prevent caching of authentication responses and
  pages containing one-time credentials.
- The current process-local deployment boundary remains: Ring sessions and OAuth
  transactions must be served by the same process. Restart invalidates pending
  attempts. Document this limitation; do not imply multi-replica support without a
  shared atomic store. Transaction cleanup must not leak a record per login.

### Password login, logout and frontend ownership

- Associate each async login/registration/logout result with its initiating mine,
  service root and request generation. It must never replace another mine's token
  after a switch, or restore a token after a later logout/login.
- Refresh and clear the appropriate mine's private lists, templates and local
  storage. Keep list ownership and preferences after successful reauthentication.
- Logout always clears local credentials. Distinguish successful remote token
  revocation/already-invalid tokens from network/upstream failure; show a bounded
  warning when remote revocation cannot be confirmed, rather than falsely claiming
  it succeeded. Do not log credentials or let cleanup hide the original failure.
- A network failure while canceling or revoking cannot prove remote cancellation.
  Clear local credentials and report that uncertainty. The no-late-transfer
  guarantee applies after the remote cancellation acknowledges; test that it
  waits for any already-committing transfer before returning. Do not describe a
  timeout as confirmed cancellation.
- Preserve existing older-server compatibility only where it is an explicit,
  tested behavior. No hidden authentication fallback after an arbitrary failure.

## Verification

Use the public Ring handler with HTTP-shaped requests, real session cookies and a
local HTTP InterMine/provider fixture for adversarial cases. Stub external network
boundaries, not the state validation under test. Cover successful OAuth, denied
consent, wrong/missing/duplicate state and provider, expiry, replay, concurrent
callbacks, logout/new-login races, redirect tampering, configured root mapping,
unexpected upstream responses and safe errors. Capture exchange counts and tokens
only in test memory to assert rejection and revocation.

ClojureScript tests control delayed auth responses and mine switches. Real paired
API and production-browser tests exercise registration/login/logout, private list
ownership and preferences with cleanup. Existing 104 frontend tests, backend tests,
Alliance tools and relevant InterMine/live gates must remain green. Add the new
checks to the Java 21 workflow. Run Aislop, one fresh final review and one verified
Important/Critical fix pass; commit and push to the user's forks.

## Builder decisions

Exact namespace boundaries, lifecycle representation and test fixture helpers are
implementation choices. Prefer a small atomic state machine with injected clock
and narrowly scoped network adapters over reflection or timing-dependent tests.
Use measured tests before expanding runs. If inspection reveals a prerequisite
to the required race guarantees, include it explicitly rather than weakening them.
Continue to scientific workflows and primary-UI cutover, coordinating integration
with Gos Micklem and retaining the existing product names.
