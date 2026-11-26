# Development worklist

Updated 2026-11-25 from the user's selected order. Retain the InterMine and BlueGenes
names and collaborate with Gos Micklem on upstream integration.

| Item | Status | Scope |
| --- | --- | --- |
| Query efficiency and result quality | Current priority | YeastMine API baseline, PostgreSQL 15/18 tools and read-only RDS inventory complete. Obtain SQL-level evidence through authorized log access or a separately approved statistics setup, then measure one cache/query/index improvement with preserved result fingerprints. See the [baseline](query-performance-validation-2026-11-25.md) and [operator guide](query-performance.md). |
| 1. Product renaming | Discarded | No Alluvium rebrand. |
| 2. Biological workflow validation | Queued | Small genomic mine; gene reports, tools, enrichment, exports and scientific correctness. |
| 3. BlueGenes primary UI | Complete | Configurable entry bridge, preserved report/list/template/query links, capability errors and paired production-browser validation. See the [validation record](primary-ui-validation-2026-11-24.md). |
| 4. Legacy UI/runtime retirement | Queued | Feature/route inventory, missing administration/configuration, then remove Struts/JSP/Tiles/DWR from the default deployment. |
| 5. Dependency and release work | Queued | Remaining advisories, operator migration, full integration/release checks and review. |

Java 21 readiness and paired authentication are complete; see
[java21-auth-decisions-2026-11-24.md](java21-auth-decisions-2026-11-24.md).
The completed primary UI phase does not claim completion of the queued biological
or legacy work. Rollback remains available by clearing `bluegenes.primary.url` and
rebuilding/redeploying the WAR; see the [operator guide](bluegenes-primary-ui.md).
