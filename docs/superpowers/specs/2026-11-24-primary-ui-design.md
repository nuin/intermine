# BlueGenes primary UI cutover

## Intent and current scope

The user selected worklist item 3 on 2026-11-24: make Alliance BlueGenes the primary
UI now and retain the other work for later. Product renaming is discarded. Retain
InterMine/BlueGenes names, existing Alliance styling, Java 21 and the completed
browser authentication design. Implement on the existing personal maintenance
branches; preserve the original Alliance checkout and existing runtime databases.
This is development and validation, not a production rollout or upstream release
policy decision.

## Approach

Use an explicit `bluegenes.primary.url` property containing the complete public
BlueGenes mine URL (deployment prefix plus configured namespace). A small servlet
filter translates supported browser GET/HEAD entry links to that configured root.
An unset property keeps existing deployments unchanged. The maintained paired
configuration enables it and demonstrates BlueGenes as the default entry point.

A reverse-proxy-only home redirect cannot translate report IDs or preserve query
links. Removing the legacy WAR now would also require the separately deferred
administration/framework work. The chosen application bridge supports the current
cutover while retaining those operations. Do not silently discard parameters or
redirect unknown/stateful operations to the homepage.

## Route and configuration contract

- Support `/`, `/index.jsp`, `/begin.do`, `/templates.do`, named `/template.do`,
  `/bag.do` (upload), named `/bagDetails.do`, `/report.do?id=...`, and stateless
  `/query.do`. Resolve report object type from the existing object store; missing
  objects return a visible 404 and invalid IDs a 400.
- Support GET XML query links at `/loadQuery.do?method=xml&query=...`, including
  `skipBuilder=true`. Map them to a BlueGenes query-builder entry that imports the
  XML and, when requested, runs it. Preserve Unicode and reserved characters;
  invalid/oversized queries display an error, never a different query.
- Named list/template links preserve the exact name as one encoded path segment.
  Unknown parameters, unsupported query methods/exports, legacy form POSTs,
  administration, authentication callbacks, API and static resources retain their
  existing handlers. Existing session-backed query drafts also retain their legacy
  handler rather than being replaced by an empty query builder.
- Keep a one-request `ui=legacy` escape for human entry links and document operator
  rollback by clearing the property. Do not persist a hidden browser mode.
- Validate the configured target: absolute HTTPS, or HTTP for loopback development;
  no credentials, query, fragment, traversal or empty mine path. Invalid configuration
  fails explicitly. Request-supplied hosts/return URLs never select the destination.
  Use temporary redirects, `Cache-Control: no-store` and `Referrer-Policy: no-referrer`.
  Do not forward tokens, sessions or arbitrary query strings to BlueGenes.
- Existing servlet sessions and BlueGenes sessions remain distinct. Persisted account
  lists are available after signing in to BlueGenes; old anonymous work stays in its
  original session, accessible through legacy routes. No account/token migration is
  implied. Do not redirect a session-backed draft and lose its contents.

## BlueGenes behavior

- Preserve its configured mine namespace, deployment prefix and multiple-mine routes.
  An imported query belongs to the route's selected mine and is processed only after
  that mine's assets are ready. Reloads of the link reproduce the same query.
- Show persistent, accessible compatibility notices instead of the current browser
  alert. Baseline UI requires API 27; OAuth requires API 36 as already implemented.
  Missing/invalid version and model metadata must be distinguishable from a usable
  mine. Unsupported pages explain the missing capability and offer supported paths.
- Derive biological capabilities from the model. A generic Employee mine must not
  issue Organism/SequenceFeature queries or offer a working Regions action. Direct
  Regions navigation displays an explanation. Biological mines keep those features.
- Preserve report links in result tables and the existing template/list/report/query
  flows. Exercise the primary entry and old links in the production browser build.

## Verification and boundaries

Use real filter/request regressions for disabled/enabled configuration, Unicode,
ambiguous parameters, secrets, target validation, HEAD/POST and API preservation.
Use ClojureScript regressions for query import, mine ownership, invalid XML/version
and biological capability checks. Exercise the configured deployed backend and
production BlueGenes with disposable accounts and the preserved Employee fixture.
Check list snapshots before and after. Add these gates to the pinned paired CI.

Broad biological tool correctness, legacy framework removal and dependency/release
cleanup remain separate worklist items. The generic fixture proves navigation and
capability behavior, not a validated genomic workflow. Complete one fresh review
and one Important/Critical regression fix pass; retain minor findings explicitly.

## Builder decisions

Exact helper boundaries, URL encoders, test fixtures and operator examples may follow
existing conventions. Keep route translation separate from the filter and query/
capability decisions testable without a production server. Existing authorization
covers implementation, local reversible validation and pushes to personal forks;
shared upstream integration still requires coordination with Gos.
