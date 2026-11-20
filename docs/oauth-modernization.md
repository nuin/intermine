# OAuth client migration for the next major release

The next major InterMine release requires Java 21. OAuth code exchange moves from
Apache Oltu to Nimbus OAuth2/OIDC SDK 11.39. This changes the protocol client;
Struts, Tiles and Jersey remain separate migration work.

Apache retired [Oltu in March 2018](https://attic.apache.org/projects/oltu.html).
[Nimbus](https://connect2id.com/products/nimbus-oauth-openid-connect-sdk) supplies
framework-independent OAuth messages and HTTP transport. Browser and REST adapters
share one exchange implementation and retain their existing routes and account IDs.

## Provider configuration

Explicit `oauth2.PROVIDER` settings remain authoritative: `url.auth`, `url.token`,
`client-id`, `client-secret`, `scopes`, `messageformat` (`BODY` or `QUERY`),
`responsetype` (`FORM` or `JSON`), `identity-resource`, optional `identity-envelope`,
`id-key`, `name-key` (including comma-separated keys), `email-key`, and
`resource-auth-mechanism` (`header`, `queryparam` or `body`). Missing or invalid
configuration fails explicitly. Authorization-link generation requires a client ID.
Do not copy testmine's dummy client ID into a deployed mine.

Two defaults follow verified provider documentation:

- Google exchanges codes at `https://oauth2.googleapis.com/token`, using form POST.
  Its existing userinfo endpoint and `id` identity mapping remain unchanged.
  See [Google's web-server flow](https://developers.google.com/identity/protocols/oauth2/web-server).
- GitHub uses form POST for code exchange and a Bearer authorization header for
  userinfo. Explicit configuration overrides remain available. See
  [GitHub's OAuth app flow](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps).

The eleven historical built-in provider names remain available. Defaults for other
providers, including old Microsoft endpoints, are retained for compatibility and
have not been validated against live providers. Operators must verify their chosen
provider's endpoints, scopes and identity mapping. Changing an identity mapping can
create different local accounts; this release does not migrate those identities.

Resource `body` mode now sends a form POST. Oltu previously discarded the token body
because that resource request used GET. A provider using this mode must accept POST.
Legacy QUERY modes remain available, but credentials or bearer tokens in URLs can
appear in proxy/provider access logs. Prefer BODY token exchange and header resource
authorization where supported. The application does not log exchange credentials,
request bodies or raw provider responses.

Responses without `token_type` are explicitly treated as Bearer for compatibility
with previous InterMine behavior. An explicitly unsupported type is rejected.
Identity IDs must be nonblank strings or numbers; email never selects an existing
local account. Google login uses userinfo, without decoding the obsolete migration
ID token. This increment does not introduce ID-token authentication or PKCE.

`DefaultOAuthProvider(Properties, OAuthProviderType)` is replaced by
`DefaultOAuthProvider(Properties, String)` because the Oltu enum is removed. Java
extensions using that constructor must update when adopting the new major release.
The unused `MigrationMapping` and `ForseenProblem` helper classes are also removed;
no remaining repository consumers use them.

## Failure and session behavior

Connect and read waits are bounded at ten seconds each; redirects are not followed
for credential or userinfo exchanges. Provider errors, malformed responses and
invalid identities cannot authenticate. Userinfo must be a complete, strictly valid
JSON object; unquoted/single-quoted fields, trailing commas and trailing documents
are rejected even when they contain a usable identity ID. Browser failures return to login with a
sanitized message. JSON REST failures carry an error HTTP status; the existing
JSONP convention retains HTTP 200 with a structured error payload.

Browser initiation binds a single-use state to the provider, exact redirect URI
and session for ten minutes. The callback claims it before contacting the provider,
so a failed exchange also requires starting a new login. A newer successful login,
OAuth initiation or logout cancels earlier pending and in-flight authorizations.
Network requests remain outside the session lock; profile creation and installation
check that the claim is still current under that lock. Opening another OAuth login
in the same browser replaces the earlier flow. In-flight requests cannot complete
across session passivation or migration to another process; restart login in that case.

REST clients retain their existing contract: request an authorization link, supply
their own redirect URI and correlation state, then exchange provider/code/redirect
through the REST callback. Redirect URIs must be absolute and fragment-free;
provider-registered private-use schemes for native clients are preserved exactly.
Only endpoints contacted by the server require HTTP(S). The callback returns a temporary API token without
installing browser authentication. Clients remain responsible for validating state
and redirect correlation; no mandatory browser cookie is added.

Provider/display names in OAuth messages, account preference input attributes and
preference error notifications are escaped when rendered. Stored values and API
responses retain the original text. Testmine enables the existing custom-preference
UI option to exercise custom keys; production mines retain their configured setting.
General rich-HTML flash-message contracts elsewhere in the webapp remain outside
this increment. This is not a complete security assessment.

Validation uses a local fake provider and disposable accounts. No real provider
credentials or remote interactive logins are used. See the accompanying validation
record for measured checks and remaining limitations.
