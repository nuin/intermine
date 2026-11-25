# BlueGenes primary UI validation

## Source pair and scope

InterMine maintenance branch `maintenance/java21-bluegenes` and BlueGenes
`maintenance/java21-intermine6` retain their product names and the Alliance UI style.
Validated executable sources: InterMine `26bb354fc8def3a9991a8885fa9679b8bfdc00f5`
and BlueGenes `89deee6d676921b458d6d5561bdb51f2f38101f3` (2026-11-25).
The BlueGenes workflow pins that exact InterMine revision.
This work enables a configured primary browser entry point. It does not remove the
legacy framework, deploy to production, merge upstream, or validate genomic tools.

## Local evidence

- Core build/install/checkstyle and the test-mine WAR passed, including all 11
  primary-route/filter tests and two Unicode/compression regressions. Aggregate
  core reports: 2,303 tests, zero failures/errors/skips; unchanged tasks can be cached.
- Frontend: 147 tests / 576 assertions passed after the final review fixes.
- Backend: 30 tests / 176 assertions passed. Account/list integration: 2 passed.
- Formatting passed. Aislop reports no findings in the primary UI change ranges;
  ClojureScript semantics are covered by its tests rather than Aislop.
- The eight authenticated and two public saved lists, including all members, match
  the snapshot taken before the deployment rebuild.
- All eight production browser tests passed: navigation, private Unicode lists,
  legacy home/report/template/list redirects, query reloads and fresh tabs,
  generic-model Regions handling, unsupported API retry, delayed metadata and
  malformed model-response recovery.
- Both jobs in [paired CI run 45648894679](https://github.com/nuin/bluegenes/actions/runs/45648894679)
  passed for the exact source pair, including the production browser and account
  integration checks.
- InterMine's [first run on the source revision](https://github.com/nuin/intermine/actions/runs/45635549980)
  passed browser, migration and JS/Python client checks. Its core job was blocked
  by Maven Central HTTP 429 during dependency download, cancelling the biological
  unit job. Checkstyle passed separately. GitHub's retry endpoints returned HTTP
  500; a documentation-only push triggered a new run without changing executable sources.
- The fresh [InterMine CI run 45651562113](https://github.com/nuin/intermine/actions/runs/45651562113)
  passed all six jobs, including core and biological modules, both web-service
  clients, migration and browser checks. [Checkstyle 45651562276](https://github.com/nuin/intermine/actions/runs/45651562276)
  also passed on `7e20d99160d452c39147087c965e3daa88c5af04`, whose changes after
  the validated source revision are documentation only. Final worklist bookkeeping
  reuses these checks without changing executable sources.

The local validation pair uses the preserved runtime databases. A local address
conflict means its backend URL uses `127.0.0.1:8080`, while the frontend is
`http://localhost:5016/bluegenes`. The CI fixture uses its own disposable databases.

## Operator controls

See [the primary UI guide](bluegenes-primary-ui.md) for the route mappings,
`bluegenes.primary.url` setting, separate browser sessions and rollback. Clear that
property and rebuild/redeploy to restore the legacy entry point. `ui=legacy` is a
single-request escape; use `/begin.do?ui=legacy` for home. Persisted lists and the API
do not need a reset.

See [the worklist](development-worklist.md) for the queued biological validation,
legacy retirement and dependency/release work. Product renaming is discarded.

## Final review and decisions

One independent review returned five important findings and two minor findings.
The single fix pass resolved the important findings with failing regressions
followed by passing unit and production-browser suites:

1. Immediate-run links retain the query URL, even when a public list has the same
   name; reloads and fresh tabs execute the linked query.
2. Imports wait for required metadata and resume after delayed responses or retry.
   Malformed model responses become visible failures rather than an endless loader.
3. XML imports preserve lowercase outer joins and default ascending sort order;
   unsupported lossy constraints are rejected before execution.
4. The query parser preserves servlet-decoded Unicode and LZW dictionary codes.
   The removed Latin-1 conversion corrupted private-list names.
5. The report-link browser test activates the existing interactive table and checks
   the object's actual model class. It previously assumed Employee for a Manager.

Deferred minors: internal dot-only list names can normalize frontend paths; the
legacy home redirect loses `ui=legacy` unless `begin.do` is used explicitly.

Implementation rulings, in order:

- Existing authorization covered inline implementation and personal-fork pushes;
  configuration and compatibility choices remain documented for review.
- Use a configured application bridge. Unmapped stateful/admin/export operations
  retain the legacy framework until its separate retirement phase.
- Keep servlet and BlueGenes sessions separate. Users may need to sign in again;
  anonymous legacy work stays in its original session.
- Reuse fresh passing checks for unchanged executable sources. Evidence is tied
  to source revisions, and relevant checks rerun after implementation changes.
- Organism loading lives in `events.cljs`, not the plan's initially assumed cache
  namespace. Direct Regions rendering is verified in the production browser because
  the unit runner does not load its vendor globals.
- Use `127.0.0.1:8080` locally because `localhost:8080` reaches an unrelated service.
  Disposable accounts avoid relying on sessions from the previous local identity.
- Reserved names use query entries without weakening server URI validation. Old
  encoded-path bookmarks can still be rejected before application routing.
- Overlap independent review and runtime/hosted checks. Both remain gates; findings
  and browser failures enter the single fix pass rather than a second review.
- Preserve existing report-link generation after the browser demonstrated the
  correct Manager link. The table retains its existing hover interaction.
- Biological correctness, rebranding, legacy removal and dependency/release work
  remain outside this phase; previous authentication review stands alongside the
  private-list checks here. This is not production or genomic certification.
- Recover the BlueGenes checkout into `.worktrees/bluegenes-primary-ui` after older
  temporary files disappeared. Pushed commits and saved edits restored the work;
  the original Alliance checkout remains untouched.
- Use a bounded raw-text model request with explicit decoding because the existing
  client's JSON middleware could leave a channel unfinished on malformed JSON.
  Other client requests retain their existing behavior.
- GitHub's failed-job retry endpoint was unavailable. A documentation-only push
  triggers a fresh run of the same executable sources; the original infrastructure
  failure remains visible in the linked run.
