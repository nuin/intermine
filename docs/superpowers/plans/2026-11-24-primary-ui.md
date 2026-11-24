# BlueGenes Primary UI Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline, task by task. Existing authorization covers implementation and personal-fork pushes. Steps use checkbox syntax.

**Goal:** Make BlueGenes the configured primary entry point while preserving supported legacy links and explicit compatibility behavior.
**Architecture:** A bounded servlet route bridge sends browser entry links to an operator-configured BlueGenes mine. BlueGenes imports deep-linked queries and derives usable pages from service metadata. Legacy stateful operations remain available until their separate retirement.
**Tech Stack:** Java 21/Servlet 3.1, Gradle, ClojureScript/re-frame/Reitit, production browser tests.
**Spec:** `docs/superpowers/specs/2026-11-24-primary-ui-design.md`

## Global Constraints

- Retain InterMine/BlueGenes names, Alliance styling, Java 21 and current authentication guarantees.
- Work in the existing maintenance branches; preserve the original Alliance checkout, runtime databases and lists.
- No production rollout, upstream merge, rebrand, legacy framework removal or claim of broad biological validation.
- `bluegenes.primary.url` is the complete configured mine URL; absent means legacy behavior, invalid means explicit configuration failure.
- API 27 is the UI baseline; OAuth still requires 36. Never forward credentials in redirects.
- Retain the other worklist items in `docs/development-worklist.md`.

## Review Focus

- Encoded names, repeated parameters and path normalization must not target a different resource or host.
- Existing query drafts, POST operations and export links must retain their behavior instead of losing state.
- Query import after a mine switch or asset reload must use the intended mine, with failures visible.
- Missing metadata and unsupported versions must not appear as working pages or an endless loader.
- Production prefixes, browser reloads and private list access must work through the actual configured entry point.

## Task 1: Configured InterMine entry bridge

**Files:** new `intermine/webapp/src/main/java/org/intermine/web/filters/PrimaryUiFilter.java`, `PrimaryUiRoutes.java`; corresponding tests; `WEB-INF/web.xml`, `global.web.properties`; operator guide `docs/bluegenes-primary-ui.md`.
**Interfaces:** `PrimaryUiRoutes(String target)` validates configuration; `destination(String path, Map<String,String[]> parameters, boolean sessionQuery, ReportTypeResolver resolver)` returns a target or null for retained legacy handling. Resolver maps an object ID to its model class and can report a missing object. `PrimaryUiFilter` translates invalid request/missing object to 400/404, delegates unknown paths/methods and maps servlet configuration to the helper.

- [x] Add route/filter regressions for enabled/disabled entry, report/list/template/query links, Unicode/reserved characters, ambiguous parameters, invalid target, absent object, legacy escape, session query, HEAD/POST, APIs/static/export preservation; observe RED.
- [x] Implement the route helper and filter with explicit validation, temporary uncached redirects, allowlisted parameters and object-type lookup; observe focused GREEN.
- [x] Wire before permanent-URL handling, document mappings and rollback, run core/checkstyle and Aislop; commit.

## Task 2: BlueGenes query-link import

**Files (BlueGenes):** new `src/cljs/bluegenes/primary_ui.cljs` and `test/cljs/bluegenes/primary_ui_test.cljs`; `route.cljs`, `events.cljs`/boot wiring as needed.
**Interfaces:** `:primary-ui/import-query` consumes route mine, XML query and run flag after mine boot; yields query-builder state or existing `:results/history+` flow. Invalid XML/model or excessive size yields a persistent safe message. Query-builder routes consume `query`/`run` query parameters; no backend authentication state changes.

- [ ] Add failing regressions for valid XML, immediate run, malformed/oversized input, unknown model paths and wrong-mine delivery.
- [ ] Implement import through existing parser/query-builder/results events; make the new route parameters participate in controller identity so back/reload work. Run focused/full CLJS, format and Aislop; commit.

## Task 3: Explicit version and model capabilities

**Files (BlueGenes):** `primary_ui.cljs`, `views.cljs`, `events/boot.cljs`, `events/cache.cljs`, regions views/events and navbar; tests in `primary_ui_test.cljs` plus existing boot/regions tests.
**Interfaces:** Pure `capabilities` derives API/model readiness and Regions support from a mine's metadata. Subscriptions/rendering consume that state; biological fetches run only when required model paths exist. Compatibility messages distinguish unsupported version, missing metadata and unavailable Regions.

- [ ] Add RED tests for API26/27/36, missing/invalid metadata, generic vs genomic model, direct Regions route and mine switching.
- [ ] Implement persistent accessible compatibility state, guard biological fetches, and preserve supported navigation. Run focused/full CLJS and backend suites, formatting and Aislop; commit.

## Task 4: Paired primary-UI validation and review

**Files:** paired CI pin/configuration, production browser tests, operator/validation documentation, completed plan and ledger.

- [ ] Build/deploy the backend and production frontend sequentially; enable the primary URL in the task's preserved local deployment. Exercise home and old report/list/template/query links, reloads, capability notices and existing account/list tests; compare list snapshots.
- [ ] Configure the disposable paired CI fixture to enable the route bridge and pin the tested backend. Run existing core/live/client checks appropriate to changes and inspect exact-source hosted results.
- [ ] Run Aislop on both change ranges; one fresh whole-project review and one Important/Critical fix pass with RED/GREEN regressions. Record deferred minors and all rulings.
- [ ] Commit/push verified work, complete this plan and retain the queued worklist. Report runtime/deployment boundaries and rollback instructions.
