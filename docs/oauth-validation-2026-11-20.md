# OAuth modernization validation

This increment follows the completed browser authentication work. Its base source
is `63952ad837`; beta's documentation-only authentication CI record was merged before
OAuth completion. Java 21 remains the next-major baseline.

## Shared exchange client

A local HTTP provider and real InterMine test database exercise the actual browser
callback and public REST service boundary. Seven initial tests exposed five failures:
REST returned HTTP 200 with `wasSuccessful:true` after provider or parsing failures;
a null identity ID created an account; an error payload inside HTTP 200 authenticated;
and logs contained a client secret. Normal browser/REST identity matching and the
REST callback's lack of browser-session installation already worked.

Fourteen expanded tests exposed eleven failures. Additional cases proved resource
body mode dropped its token, whitespace tokens and duplicate form tokens were
accepted, userinfo redirects were followed, and an irrelevant malformed Google
migration ID token blocked valid userinfo login. A live test independently reproduced
the REST false-success response for an unconfigured provider.

The shared Nimbus implementation passes nineteen focused tests. These cover twelve
combinations of BODY/QUERY token requests, FORM/JSON responses and header/query/body
resource authorization; exact Unicode, quote, plus and ampersand values; preservation
of existing endpoint query parameters; nullable/invalid identity rejection; legacy
missing token type; unsupported types; numeric IDs and custom mappings; email collision
isolation; Google userinfo; redirect rejection; sanitized nested exceptions; and REST
client-owned correlation. Raw account values remain unchanged.

A separate stalled-provider regression failed before timeout settings were applied.
It now observes a read-timeout failure using the short constructor timeout. Production
connect and read defaults are ten seconds each. The provider fixture uses bounded
latches, local sockets and synthetic credentials; no remote provider login is used.

The shared-client task core build passed 2,233 tests with zero failures, errors or skips, including
301 webapp and 511 API cases. Checkstyle and local publication pass in 1m58s. All 22 deployed auth/search/registration/OAuth checks pass in 6.33s. The JavaScript
client passes 405 tests with one upstream pending case (7s); Python passes 74
(13.26s), retaining its existing registry no-op. The WAR passes 26 required-provider
and 42,086 unique-class checks across 128 runtime JARs. Oltu and the two removed
helper classes are absent. Changed OAuth classes and account-rendering resources
match the compiled output/source exactly. The original eight authenticated and two
public lists retain their members; eleven saved-list rows remain.

Resolved protocol dependencies are SDK11.39, JOSE10.9.1, json-smart/accessors-smart2.6.0,
content-type2.3, lang-tag1.7, jcip-annotations1.0-1 and ASM9.7.1. No optional OpenSAML
or additional optional crypto stack is packaged. Hosted checks and whole-branch
review remain integration gates after the browser-flow task.
The shared-client task Aislop scan reported 100 with no errors, warnings or fixable findings in this
increment's changed-file scope. Existing warnings outside that scope are unchanged.

## Browser flow and rendering

Eight public browser tests start authorization through the actual action, exchange
against the local provider and inspect the resulting profile/forward. Seven failed
on the previous implementation: provider substitution, successful-state replay,
failed-exchange replay, delayed completion after a password login/new authorization/
logout, and unescaped identity display names. Missing/wrong state already rejected
without consuming an otherwise valid authorization.

Eight additional helper tests failed against the minimal new API before implementation.
They cover independent 32-byte state, exact provider/redirect binding, one claimant
under concurrency, expiration at ten minutes (including during an exchange), identity
transition invalidation and session serialization. All forty focused Java tests now
pass, including the nineteen shared-client cases and five existing browser-login
cases. A blocked provider makes the delayed-response interleavings deterministic;
stale callbacks reach userinfo but cannot create an account or replace the session.

A deployed JSP regression reproduced preference keys/values breaking out of input
attributes. Three DOM regressions reproduced duplicate-key and service-error HTML
in notifications and leaked preference-handler variables. The eight-test DOM suite
now passes. Original preference values remain intact in API responses and updates.
The test-only session adapter now models shared requests, invalidated sessions and
fresh session creation; the logout assertion requires no installed profile in that
fresh fixture session, whose real application listener is outside this fixture.

The browser-flow full core build passes 2,249 tests with zero failures, errors or
skips, including 317 webapp tests and 511 API tests. Checkstyle and local publication
pass in 1m47s. Browser DOM checks pass 8/8 and bio JavaScript checks pass 15/15.
All 23 deployed checks pass in 5.66s. The JavaScript client passes 405 tests with
one upstream pending case (7s), and Python passes 74 (12.96s), including its existing
registry no-op. The WAR passes 26 required-provider and 42,088 unique-class checks;
all changed runtime classes and six rendering resources match tested output/source.
Eight authenticated and two public lists retain exactly their baseline members, and
eleven saved-list rows remain. Aislop reports 100 with no errors, warnings or fixable
findings. No remote provider login was exercised. Whole-branch review and hosted
checks on the final source remain required before integration.

## Decisions and limits

- Continue on a separate OAuth branch while authentication CI runs. Authentication
  passed all final-source checks and was integrated into beta before OAuth integration.
- Use Nimbus 11.39 behind shared adapters. Struts/Tiles/Jersey migration remains
  separate; adopting a maintained protocol client does not modernize those frameworks.
- Make explicit resource body mode a form POST because the old GET discarded it.
  Operators using that mode must verify POST support.
- Normalize absent token type to Bearer for compatibility; reject explicit unsupported
  types. Providers needing another token mechanism require a separate design.
- Keep REST clients responsible for state/redirect correlation. This increment adds
  neither mandatory browser cookies to REST nor PKCE/ID-token authentication.
- Keep raw exception causes out of public service exceptions: the generic service
  logger recursively prints causes even for non-500 statuses. Public diagnostics
  retain sanitized phase/status information instead of provider payloads.
- Bind delayed browser completion to the active flow ticket under the same session
  lock as other login transitions. That behavior is implemented and measured in the
  browser-flow task; old callbacks are rejected before profile lookup or creation.
- Coordinate logout with the same session lock used by callback completion. This
  prevents logout from interleaving between ticket validation and identity installation.
- Escape preference notification call sites as well as JSP attributes. The generic
  notification renderer retains its existing rich-HTML contract.
- Enable the existing custom-preference UI flag in testmine only, so disposable-user
  live checks exercise arbitrary keys without changing administrator fixtures.

## Fresh review and corrections

One fresh whole-branch review found two Important issues and no Critical issues.
Both reproduced before correction: a private-use native redirect URI returned HTTP
400, and malformed userinfo with an otherwise usable ID completed browser login.
The fixes separate client redirect validation from outbound endpoint validation and
use the pinned JSON library's strict parser. The review regressions cover hierarchical
and opaque native URIs with percent-encoded values, relative/fragment rejection
before HTTP, and malformed userinfo through both browser and REST adapters without
profile creation. All 43 focused tests and Checkstyle pass (1m10s) after the fixes.
The parser's strict behavior is documented in the
[JSON-java 20260814 source](https://github.com/stleary/JSON-java/blob/20260814/src/main/java/org/json/JSONParserConfiguration.java).

Deferred Minor: the provider guide could make clearer that overriding `url.token`
selects the existing custom-provider path and also requires `messageformat` and
`responsetype`. That historical configuration behavior is unchanged.

The reviewer did not establish remote-provider compatibility, cross-process login
completion, PKCE/ID-token migration, wider rich-HTML safety or hosted CI results.
Those retain the documented operator/client responsibilities and scope limits; the
executor independently requires all corrected-source local and hosted gates.

The corrected source is `1799750bce99fb568704c36407e579788608f773`. Its full core build
passes 2,252 tests (zero failures, errors or skips), including 320 webapp tests and
511 API tests, with Checkstyle and local publication in 1m45s. The two Important
findings are covered by `testRestPreservesPrivateUseRedirectSchemes` and
`testMalformedUserInfoWithValidIdentityCannotAuthenticate`, both observed failing
before correction. Relative/fragment redirects also have a rejection regression.

The complete decisions and deferred findings across PostgreSQL, search, browser
authentication and OAuth are collected in
[the modernization decision record](modernization-decisions-2026-11-20.md).

After the review fixes, all 23 deployed checks pass (5.96s), JavaScript passes
405 tests with its one upstream pending case (7s), and Python passes 74 (13.50s).
The original eight authenticated and two public lists retain their exact members;
eleven saved-list rows remain. The rebuilt WAR retains 26 required providers and
42,088 unique runtime classes, with tested class/resource bytes matching the archive.
Aislop remains 100 with no errors, warnings or fixable findings. The unchanged DOM
and bio JavaScript sources retain their passing 8/8 and 15/15 results.

## Final-source hosted gates and integration

Source `1799750bce99fb568704c36407e579788608f773` passed
[all six InterMine CI jobs](https://github.com/nuin/intermine/actions/runs/44820710211):
PostgreSQL migration, browser/DOM, core Java 21, bio Java 21, JavaScript client and
Python client. Its separate
[Checkstyle run](https://github.com/nuin/intermine/actions/runs/44820710276) also passed.
The two Important review findings are fixed and independently regression-tested;
the deferred Minor and every review scope ruling are retained in the decision record.

The final integration record changes documentation only and uses `[skip ci]`.
Executable files match the exact source validated locally and by the hosted runs
above. Both the OAuth maintenance branch and fork `beta` receive this record after
fast-forward integration; no upstream release or production database is modified.
