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

Names are encoded as individual path segments. Empty names, dot-only path segments,
names longer than 1,024 characters, duplicate parameters and ambiguous list names
are rejected. XML entry links accept at most 8,192 characters, and the complete
encoded destination must also fit within 8,192 characters. Invalid report IDs
produce HTTP 400; an absent report object produces 404. Redirects are temporary and
uncached, with referrers suppressed.

Parameters outside the supported route's allowlist retain the legacy handler. This
preserves parameterized template operations, query exports and other semantics the
bridge does not implement. Credentials are never copied into a redirect. POSTs,
administration, service endpoints, static assets and existing session query drafts
also retain their current handlers. A permanent identifier URL continues to resolve
through InterMine's existing resolver, then follows the report mapping.

## Sessions and rollback

BlueGenes and the legacy servlet UI have separate browser sessions. Sign in to
BlueGenes to access the same persisted account lists. Anonymous legacy work stays in
its original servlet session; no token or anonymous-profile migration is implied.
Append `ui=legacy` to a browser entry link to use its legacy handler for that request.
This escape does not create a persistent UI preference.

To roll back the deployment entry point, clear `bluegenes.primary.url` and rebuild/
redeploy the WAR. The API, profile database and saved lists are not reset. Keep the
BlueGenes deployment available for its existing links while coordinating any rollback.

## Validation scope

The paired production browser checks exercise home, report, list, template and XML
query links under a deployment prefix, plus login/private-list behavior. The generic
Employee fixture verifies navigation and unsupported biological capabilities. The
separate queued genomic-fixture work is needed to validate biological tools and
scientific workflows; see [the worklist](development-worklist.md).
