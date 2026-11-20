# Browser authentication and list ownership validation

This branch follows the reviewed Java21/search candidate `10817a1fb3`. It retains
Java21, PostgreSQL18, Solr10.1 and the existing Struts/Tomcat application. It is a
bounded authentication audit, not a complete application penetration test.

## Anonymous list ownership

The initial live regression run reproduced three failures in four cases: the old
anonymous API token retained a transferred list, replaying that token transferred
the list again, and concurrent logins gave two accounts access. Logging in from
an already named account correctly preserved its private lists at the REST entry
point. Tests use disposable accounts and lists on the preserved test mine.

Eight Java regressions initially failed: null-source handling, authenticated-source
isolation at the shared merge boundary, anonymous destination rejection, independent
list copies, naming collisions, concurrent claims, source-removal errors and copy
read errors. The shared transfer helper now creates an independent list, preserves
its metadata and contents, and removes the anonymous original after saving the copy.
Source locking serializes competing claims; destination locking serializes collision
naming when separate anonymous profiles enter the same account. Registration and
all login merge callers use this helper.

Ten focused tests pass, including two additional checks of shared-helper guards
and concurrent source lists with colliding destination names. The full core build,
Checkstyle and local Maven publication pass with 2,186 tests, including 254 webapp
and 511 API cases, with no failures, errors or skips. The final run took 2m02s.
All seven live login/registration regressions pass on the rebuilt application
(0.93s), including deletion of a later account without affecting the first owner.
The WAR passes 22 required-provider checks and 40,881 unique-class checks. The
preserved fixture retains exactly eight authenticated and two public lists with
the same members, and eleven saved-list database records.

Copy reads happen before allocating a persisted destination bag. If filling a new
bag fails, cleanup attempts to remove that partial copy and preserves any cleanup
error as a suppressed cause. If source removal fails after a valid copy exists,
the request reports failure and retains the valid copy. This is deliberately not
an all-or-nothing distributed transaction: a failure may require reconciling a
partial transfer or duplicate list. The locks coordinate this application's
in-memory profiles, not independent JVMs with separate caches.

The Aislop scan reports 100 with no errors or fixable warnings. An existing empty
SQL connection-cleanup catch in LoginHandler now logs the exception. Three
nonfixable broad-throws warnings remain in existing NewUserService framework/mail
callbacks. No lint configuration was changed.

## Session and administrator checks

Live baseline tests reproduced unchanged session IDs after password login, access
with the old cookie, stale administrator UI after switching accounts, and raw HTML
account names in the administrator table. Wrong-password login already preserved
anonymous lists, and logout already removed access.

Password and browser OAuth success now share `LoginHandler.completeLogin`: transfer
anonymous work, rotate JSESSIONID, discard the old administrator token, normalize an
administrator-only users tab, then install the authenticated profile. Failed
credential lookup and merge failure leave the browser profile unchanged. Public
session-only Java setup methods remain available for external integrations; browser
callers use the request-aware method.

The current profile controls authorization. Administrative views and actions reject
ordinary or missing profiles before accessing account or property data. Role and
property mutations require POST and a single-use 32-byte random token. Token
consumption is atomic within the servlet session. The separate Struts query-builder
token remains intact. Opening a new administrative form replaces the previous admin
token; after submitting, return to the form to obtain a new token. Direct endpoint
calls without authorization return 403; authorized GET mutations return 405.

Account names render as escaped text in administrator tables, API-key pages and
list-sharing controls. Sharing controls carry names in escaped DOM attributes rather
than inline JavaScript, and client-side updates use text insertion. Five DOM tests
exercise markup, quotes, removal, refresh and invitation errors against the legacy
jQuery distributed in the repository. A locked jsdom dependency is used only by
these tests; the application's default CDN jQuery remains unchanged.

Twenty-four Java regressions cover marker/profile conflicts, invalid credentials,
failed merges, password/OAuth completion, direct and embedded administrator routes,
missing or wrong tokens, replay, concurrent consumption, request methods, token
generation and permitted forms. Negative role changes use mocks; fixture roles
are never changed. Five DOM regressions and fifteen existing browser unit tests
pass.

The final core build passes 2,210 tests (278 webapp and 511 API), with zero failures,
errors or skips; Checkstyle and local publication pass in 1m47s. All twenty deployed
auth/search/registration/OAuth regressions pass in 6.08s. The JavaScript client passes
405 tests with one existing upstream pending case; the Python live client passes
74 tests in 15.20s, retaining its existing registry no-op case. The final WAR passes
22 required-provider and 40,883 unique-class checks; its five changed account-rendering
resources exactly match the tested source. Saved-list names, types, sizes and members
match the prior PostgreSQL baseline: eight authenticated, two public, eleven saved-list
records. Hosted CI and whole-branch review remain separate integration gates.

The latest Aislop scan reports 100, no errors and no fixable warnings. Six nonfixable
warnings remain: the large existing SessionMethods class and broad checked-exception
signatures in existing query, role-update and registration/mail code. The small
UsersController callback now declares IOException. Dead commented migration code
was removed from the OAuth callback; OAuth protocol and library replacement remains
the subsequent maintenance phase.

## Whole-branch review and final fixes

The fresh review identified three important defects. Regression tests reproduced
each before its fix: double encoding of administrator checkbox values, collisions
with recoverable invalid lists, and concurrent requests sharing the property-edit
form. The checkbox now lets Struts encode its raw value once; transfers reserve
all destination list names; property forms have request scope. Two additional
tests exercise the real Struts checkbox tag, and the form regression loads the
production action mapping.

The corrected core passes 2,214 tests with zero failures, errors or skips, including
282 webapp and 511 API cases; Checkstyle and publication pass. All 21 deployed
regressions pass (7.04s), including the actual administrator checkbox value and
checked state. JavaScript passes 405 with one upstream pending case; Python passes
74 (15.18s). The WAR still passes 22 provider and 40,883 unique-class checks, and
its five changed rendering resources match source. The original eight private
and two public lists retain their members; the database retains eleven saved lists.
Aislop reports 100, zero errors or fixable warnings, and the six warnings above.

Hosted CI on the earlier implementation exposed a separate test-helper race:
four precompute workers appended to an unsynchronized ArrayList, recording nine
of eleven queries. The test recorder now uses a synchronized list. Production
precomputation is unchanged. The final core rerun passes 2,214 tests in 2m01s. The exact source
commit `63952ad837877a3f31765eb6da22485cb4ee346a` passes all six
[InterMine CI jobs](https://github.com/nuin/intermine/actions/runs/44805593852)
and [Checkstyle](https://github.com/nuin/intermine/actions/runs/44805593822).
This verified commit is integrated into the fork's beta branch; this record changes
documentation only.

The review's minor finding is deferred: opening a second administrative form
invalidates the first form's unused token. Refreshing the earlier form restores
normal use. OAuth exchange/logging, identity message arguments and preference
attribute encoding are assigned to the next increment. Generic rich-HTML flash
messages remain a separate audit. Cross-JVM and crash-atomic list transfer remain
outside this increment's in-memory coordination guarantee.

## Implementation decisions

- Continued on a separate authentication branch while the preceding search checks
  ran. Search passed its final-source hosted checks and was integrated before this
  branch's completion; the branches do not share concurrent builds or edits.
- Bounded this audit to list ownership, login sessions, administrative guards and
  confirmed account-name rendering. Other webapp security risks remain outside
  the evidence collected here; this is not a complete security assessment.
- Retain a valid destination copy if source cleanup fails. Removing it could lose
  the only surviving copy; the tradeoff is explicit partial-transfer recovery.
- Use a dedicated administrative token to preserve query-builder forms. A new
  admin form replaces an earlier token, so stale forms must be refreshed.
- Extend escaping to related account-name render paths and add test-only jsdom.
  DOM interaction tests use the repository's legacy jQuery; the runtime dependency
  remains unchanged, and a broader frontend-library upgrade remains separate.
- Preserve the in-memory transfer boundary: independent copies and propagated
  cleanup failures are verified; crashes or multiple JVMs can require reconciliation.
- Complete OAuth exchange and adjacent identity/preference encoding in the next
  authorized increment. Other rich-HTML message paths may still carry markup risks.
- Treat hosted CI and live fixture preservation as measured integration gates;
  a read-only review cannot establish either.
- Repair the test-only parallel recorder after its observed CI failure. Final-source
  CI must confirm that the change resolves the race without masking missing jobs.
