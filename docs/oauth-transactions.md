# Deferred OAuth transactions (service version 36)

BlueGenes uses this opt-in API to verify a provider before committing anonymous
lists, preferences and query history to the authenticated account. The existing
OAuth callback and ordinary account-wide logout endpoints remain available.

Send form-encoded POST requests to `/service/oauth2/transactions/<operation>`.
Query-string parameters, duplicate parameters and unexpected fields are rejected.
Responses are JSON with `wasSuccessful`, `statusCode` and `output` (or `error`),
and carry `Cache-Control: no-store`. The client backend keeps transaction handles
private; never put them in provider redirects, browser storage or logs.

| Operation | Body fields | Output / effect |
| --- | --- | --- |
| `prepare` | `provider`, `redirect_uri`, `state`; optional anonymous `token` | `handle`, `authorizationUrl`; binds source, provider and exact callback |
| `exchange` | `handle`, `code` | Verifies the provider once; no account or ownership changes |
| `complete` | `handle` | `user`, `token`, `renamedLists`; commits transfer once; retry returns the same result |
| `acknowledge` | `handle` | Marks credentials delivered; subsequent completion is rejected |
| `cancel` | `handle` | Cancels pending work and revokes only this transaction's issued token; repeat is safe |
| `revoke` | `token` (or Authorization header) | Revokes only that temporary credential, including after handle expiry; already-invalid tokens are harmless; permanent credentials are rejected |

An anonymous token can instead use `Authorization: Token ...`; supplying both
forms is rejected. An authenticated source never transfers that account's private
data. Other operations authenticate through the unpredictable 256-bit handle.
The browser client remains responsible for session-bound, one-use OAuth state,
provider binding and validating its public callback URL.

Each process allows 10,000 active transactions, with a five-minute lifetime.
Expiry cleanup runs every second. Expired completions that were never acknowledged
lose their temporary token; delivered tokens keep their normal lifetime.
Application shutdown cancels pending transactions and revokes undelivered tokens.
Transactions are process-local: route every operation to the same application
process, and restart the flow after a server restart. No shared-store or
multi-replica guarantee is implied.

Completion and cancellation serialize on the same transaction. If transfer has
already started, cancellation waits for it to finish and then revokes the token.
It cannot undo a transfer that already committed. Once cancellation acknowledges,
that transaction cannot transfer later or return usable credentials. A network
failure is not confirmation: the client must clear local credentials and report
unconfirmed remote cancellation. Retry completion after a lost response rather
than starting a second transfer; it returns the same result until acknowledgement
or expiry. A failed commit is canceled and cannot be replayed automatically.

HTTP 400 denotes invalid input, 401 invalid source credentials, 403 insufficient
source permission, 409 an unavailable/consumed transaction, 503 capacity or
shutdown, and 502 a provider or completion failure. Error bodies do not include
credentials or raw upstream responses.

## Validation

The Java 21 build/install and Checkstyle gates passed with 2,281 tests and no
failures, errors or skips. Twelve transaction tests use a local HTTP provider and
real profile storage, including cancellation during exchange/transfer, expiry,
replay, account ownership, application restart and safe errors. Eight profile
manager tests cover scoped revocation and ordinary account-wide logout, including
reloaded profile instances. Existing OAuth exchange/browser tests remain green.

The rebuilt WAR passed checks for 25 required providers and 40,097 unique runtime
classes, with retired providers absent. All 26 deployed API checks passed,
including actual servlet routing, authorization parameters, cancellation and
idempotent cleanup. The eight authenticated and two public preserved lists retain
identical names, types, counts and members. Aislop scores this change range at 100;
its only warning is the existing size of `ProfileManager.java`.

This validates the InterMine API prerequisite. Ring/BlueGenes callback correlation,
frontend request ownership and production-browser pairing are the next tasks;
they are not completion claims of this API change.
