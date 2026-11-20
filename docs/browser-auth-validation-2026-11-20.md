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

Baseline probes confirmed that successful browser login retained the anonymous
session ID, and switching from an administrator to an ordinary account retained
the administrator marker. Logout already changed the session and removed the
account name. Source review also found no caller-role check in ModifySuperUserAction
or UsersController. Negative role-update tests will use mocks before guards exist;
existing fixture roles will not be changed to reproduce these defects.

The session and administrative fixes are the next implementation task on this
branch. OAuth library/protocol replacement is the subsequent maintenance phase.
