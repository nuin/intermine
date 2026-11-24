# Java 21 and BlueGenes replacement program

## Intent and authorized order

The user requested all remaining modernization work, explicitly prioritizing Java 21,
UI replacement, and investigation of `../alliance/agr_bluegenes`. Continue the established
inline implementation/commit workflow. Preserve the reviewed InterMine beta baseline
`3a86d175ef`; preserve every local change in the supplied BlueGenes checkout at
`d22f14dc`. Work on isolated maintenance branches/checkouts. InterMine changes go to
the user's fork. Prepare BlueGenes changes separately; do not merge into the Alliance
organization's shared dev branch without explicit authorization for that destination.

## Approach

Confirmed user choice: make the Alliance BlueGenes fork the primary InterMine UI.
The user accepted this direction on 2026-11-20. BlueGenes already
has search, lists, reports, templates, a query builder, regions and Alliance-specific
tools. Keeping JSP as the primary UI would not deliver the requested replacement;
rewriting these working capabilities in another frontend would duplicate maintenance.

On 2026-11-24 the user superseded the earlier renaming proposal: retain the
**InterMine** and **BlueGenes** names and collaborate with Gos Micklem. Keep the
Alliance fork as the preferred styling source and preserve upstream attribution.
Product renaming and removal of upstream branding are no longer planned. Backend
package names and REST contracts remain compatible.

Prepare modernization work as focused contributions for coordinated integration.
Agree on the shared base, ownership of overlapping changes, release targets and
upstream Java baseline with Gos before merging into shared release branches.
The current development branches continue to require Java 21; this does not assert
that upstream has adopted that release policy. The UI replacement program remains
in scope, with sequencing coordinated against the shared integration plan.

This is a program of independently testable projects, in order:

1. Java 21 readiness across both repositories and replacement of the one Jersey HTTP
   consumer with Java's HTTP client. Produce a reproducible BlueGenes Java 21 build,
   uniform supported runtime checks/CI, and a verified InterMine 6 connection profile.
2. BlueGenes authentication integration: close the client-owned OAuth correlation gap,
   verify registration/login/logout/preferences/list ownership against InterMine 6,
   and test failures at the public Ring/REST boundary. No provider credentials needed.
3. Primary UI cutover: supported server configuration, preserved report/list/template/
   query links, visible capability/version errors, and browser-tested scientific
   workflows. Build on existing Alliance UI and tools, preserving multi-mine behavior.
4. Retire legacy runtime paths after feature mapping: separate API/bootstrap/profile
   logic from Struts, supply the new default deployment without JSP/Tiles/DWR/Jersey,
   and implement missing administration/configuration capabilities in BlueGenes/API.
   Inventory and explicitly handle every old route; do not silently drop a feature.
   A separately named legacy compatibility artifact may remain for migration, with
   a documented support boundary. Merely redirecting the home page is not completion.
5. Complete integration/release validation, operator migration instructions, exact-source
   hosted checks and fresh review. Preserve existing databases, API contracts and lists.

The public `/service` contract remains stable. InterMine accounts remain authoritative;
email must never link identities. BlueGenes namespaces and configured mine roots remain
explicit. Production deployment, publication and real-provider logins are outside the
local development task. Backend servlet/Jakarta changes must be driven by the resulting
API deployment, not a blanket package rename of the legacy UI.

## First project: Java 21 readiness

InterMine's Gradle build already rejects Java below 21 and compiles with release 21;
its final-source CI is green. BlueGenes uses a Java 21 runtime image and one Java 21
CI executor, but its integration executor installs Java 17 and its developer guide
still permits 17. Its backend/frontend build is old but functional; upgrade dependencies
only where necessary for a verified Java 21 build, not via an untested wholesale bump.

- Require Java 21 or newer in BlueGenes build and runtime startup with a clear message.
  CI must actually run build/backend/frontend checks under Java 21. Preserve runtime
  behavior on Java 21; test the version guard with injected version data, not by
  requiring installation of an obsolete JVM.
- Keep the ClojureScript production build and existing scientific UI working. Provide
  backend tests executable without an interactive browser, and run the actual compiled
  browser where frontend behavior needs verification. Measure build/test duration before
  planning long runs. Use task-owned paths/caches; do not run the destructive Alliance
  `dev/testmine/start.sh` against existing Docker/bootstrap data.
- Add a local InterMine 6 connection profile with deployment prefix `/bluegenes`, a
  configured mine namespace and explicit service root. Verify configuration parsing,
  initial API assets, login/list operations and successful production bundle loading.
  Use disposable accounts and preserved existing runtime databases where appropriate.
- Replace Jersey's registry request in SemanticMarkupFormatter with a small Java 21
  HTTP adapter using finite connection/request timeouts and explicit Jackson decoding.
  Preserve registry-derived markup and the existing explicit no-registry fallback.
  Encode namespace/query parameters, close resources and do not follow redirects into
  unintended endpoints. Test against local HTTP fixtures before removing dependencies.
- Remove unused Jersey/JAX-RS runtime libraries only after scanning consumers and WAR
  dependencies. Update runtime verification to prove the replacement client works and
  the retired providers are absent. Run core/live/client/data-preservation gates.

## Decisions left to the builder

Exact helper boundaries, guard API, cache/test runner setup, and fixture configuration
may follow existing patterns. Preserve the supplied checkout's local lockfile and
untracked files; the isolated checkout starts from the committed source, and any
lockfile changes must be deliberate and verified. Do not overwrite sibling data or
claim a real-provider/complete-UI migration from a build-only result.

## Acceptance and continuation

Each project gets a concrete plan and measured regression/full checks, one fresh final
review and one Important/Critical fix pass. Continue through the authorized program;
finishing the Java 21 project is a milestone, not completion of the user's UI request.
Record remaining route/feature gaps as work to complete, never as silent de-scoping.
