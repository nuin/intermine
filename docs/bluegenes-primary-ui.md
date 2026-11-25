# BlueGenes as the primary interface

This development configuration directs supported browser entry links to BlueGenes
while retaining InterMine's API and legacy stateful operations. Java 21 is required
by both maintenance branches. It does not remove the legacy application framework.

## Configure a pair

Deploy the production BlueGenes bundle with an explicit mine namespace and service
root, for example the `intermine6` profile in the paired BlueGenes repository:

```properties
BLUEGENES_DEFAULT_SERVICE_ROOT=https://data.example.org/mine
BLUEGENES_DEFAULT_NAMESPACE=mymine
BLUEGENES_DEPLOY_PATH=/bluegenes
BLUEGENES_PUBLIC_URL=https://explore.example.org/bluegenes
```

The public URL above is the application's OAuth callback base. The primary UI URL
below additionally includes the mine namespace. Configure the InterMine mine's
properties (merged into `WEB-INF/web.properties`) and rebuild/redeploy its WAR:

```properties
bluegenes.primary.url=https://explore.example.org/bluegenes/mymine
```

Use HTTPS in production. HTTP is accepted only for `localhost`, `127.0.0.1` and
`[::1]` development targets. The URL must have a mine path and contain no credentials,
query, fragment, escaped path segments, traversal or matrix parameters. No target is
taken from incoming headers or link parameters. A blank setting retains the legacy
entry points. Register OAuth callbacks and configure session affinity according to
the BlueGenes authentication deployment guide.

## Browser links

| InterMine GET/HEAD link | BlueGenes destination |
| --- | --- |
| `/`, `/index.jsp`, `/begin.do` | Configured mine home |
| `/templates.do` | Templates |
| `/template.do?name=...` | Named template |
| `/bag.do` | Identifier upload |
| `/bagDetails.do?bagName=...` (or `name`) | Named list results |
| `/report.do?id=...` | Report using the object's model class and ID |
| `/query.do`, without an existing session query | Query builder |
| `/loadQuery.do?method=xml&query=...` | Import XML into query builder |
| Same XML link with `skipBuilder=true` | Import and run the query |

Names normally use encoded path segments. Names containing slash, backslash, percent
or semicolon use `/results?title=...` or `/templates?template=...` query entries,
which preserve the exact name across server reloads without relaxing URI checks. Empty names, dot-only path segments,
names longer than 1,024 characters, duplicate parameters and ambiguous list names
are rejected. XML entry links accept at most 8,192 characters, and the complete
encoded destination must also fit within 8,192 characters. Invalid report IDs
produce HTTP 400; an absent report object produces 404. Redirects are temporary and
uncached, with referrers suppressed.

Imported queries keep their XML in the query-builder URL, including when run
immediately, so reloading or sharing that URL runs the same query. Imports wait for
the selected mine's version and model; retrying failed metadata replays the link.
Query features the current client cannot preserve (including LOOKUP `extraValue`,
loop constraints and ID-set constraints) produce an explicit unavailable message
instead of executing a changed query. Use the legacy query link for those features.

Parameters outside the supported route's allowlist retain the legacy handler. This
preserves parameterized template operations, query exports and other semantics the
bridge does not implement. Credentials are never copied into a redirect. POSTs,
administration, service endpoints, static assets and existing session query drafts
also retain their current handlers. A permanent identifier URL continues to resolve
through InterMine's existing resolver, then follows the report mapping.

## Mine capabilities

BlueGenes requires API 27 or later. External sign-in requires API 36 or later;
password sign-in remains available on supported older APIs. Missing API-version or
model metadata produces a persistent message with a retry action instead of showing
a usable page based on failed metadata. An unsupported API has its own message.

Regions is available only when the model contains the genomic paths its queries
need. Generic mines keep list, template, report and query-builder navigation; direct
Regions links explain the limitation and offer the query builder. No Organism or
SequenceFeature query is sent by those region controls against a generic model.

## Sessions and rollback

BlueGenes and the legacy servlet UI have separate browser sessions. Sign in to
BlueGenes to access the same persisted account lists. Anonymous legacy work stays in
its original servlet session; no token or anonymous-profile migration is implied.
Append `ui=legacy` to a browser entry link to use its legacy handler for that request.
This escape does not create a persistent UI preference. For the home page use
`/begin.do?ui=legacy`: the legacy redirect from `/` or `/index.jsp` drops that parameter.

To roll back the deployment entry point, clear `bluegenes.primary.url` and rebuild/
redeploy the WAR. The API, profile database and saved lists are not reset. Keep the
BlueGenes deployment available for its existing links while coordinating any rollback.

## Validation scope

The paired production browser checks exercise home, report, list, template and XML
query links under a deployment prefix, plus login/private-list behavior. The generic
Employee fixture verifies navigation and unsupported biological capabilities. The
separate queued genomic-fixture work is needed to validate biological tools and
scientific workflows; see [the worklist](development-worklist.md).

Two minor edge cases remain: internal BlueGenes list links named exactly `.` or `..`
can normalize to another path (the InterMine bridge rejects these names), and the
home escape requires the explicit `begin.do` URL above. Existing bookmarks with
encoded path separators may still be rejected by the server; newly generated links
use the reload-safe query entries described above.
