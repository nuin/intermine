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

The full core build passes 2,233 tests with zero failures, errors or skips, including
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
Aislop currently reports 100 with no errors, warnings or fixable findings in this
increment's changed-file scope. Existing warnings outside that scope are unchanged.

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
  subsequent browser-flow task before this branch is integrated.
