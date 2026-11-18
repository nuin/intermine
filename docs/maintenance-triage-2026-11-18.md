# InterMine backlog triage — 18 November 2026

All 218 open core issue bodies in the [inventory](maintenance-backlog-2026-11-18.json) received a first-pass classification. The [full triage records](maintenance-triage-2026-11-18.json) contain component, disposition, reported-impact priority, rationale, next verification step, and evidence for every issue. Selected source paths were inspected at commit `787f779b7a3be3d778de784229a701cc47dc355b`. Runtime reproduction and exhaustive discussion review remain pending; these records are not closure decisions. No GitHub labels, assignments, comments or issue states were changed.

| Disposition | Tickets | Required action |
| --- | ---: | --- |
| Needs reproduction | 78 | Produce a small fixture and current-version failure before fixing. |
| Verify existing fix | 6 | Test the reported behavior and remaining requirements before proposing closure. |
| Route to repository | 12 | Confirm ownership in clients, scripts or other projects before transfer. |
| Deployment-specific | 14 | Obtain the mine version, configuration and input evidence. |
| Needs design | 108 | Define behavior and compatibility before implementation. |

Priorities reflect reported impact: 24 P1 reliability/data-integrity/access concerns, 97 P2 defects/maintenance items, and 97 P3 feature/UX proposals. P1 does not mean a confirmed vulnerability or reproduced defect. Existing labels and ticket age did not determine priority alone.

The first reproduction queue is deliberately small:

| Order | Ticket | Evidence and useful regression |
| --- | --- | --- |
| 1 | [#2451](https://github.com/intermine/intermine/issues/2451) | Region pagination still uses a strict upper-bound comparison. Exercise result counts just below, equal to and above page sizes, plus incomplete results. |
| 2 | [#2355](https://github.com/intermine/intermine/issues/2355) | DataTracker still writes source-name characters using `writeBytes`. Round-trip non-ASCII names through PostgreSQL COPY and provenance lookup. |
| 3 | [#1639](https://github.com/intermine/intermine/issues/1639) | JSON-object query generation clears requested ordering. Compare order and paging across object and row output formats. |
| 4 | [#2140](https://github.com/intermine/intermine/issues/2140) | List display performs an owner-unscoped tag lookup. Test same-named lists owned by different users. |
| 5 | [#1789](https://github.com/intermine/intermine/issues/1789) | Inheritance merge-key handling combines keys across class descriptors. Build a collision fixture before changing merge precedence. |
| 6 | [#1894](https://github.com/intermine/intermine/issues/1894), [#1971](https://github.com/intermine/intermine/issues/1971) | Postprocessor writer acquisition lacks local explicit cleanup. Measure actual pool lifecycle across successive processors before selecting a fix. |

The six existing-fix verification candidates are **#2244, #2423, #1517, #2078, #2125, and #651**. Check each record's source evidence and remaining behavior; a matching code change does not prove the full issue resolved. In particular, #651 now rejects unknown correction strategies inside the calculation, but service response semantics and method discovery remain to be checked.

Review the existing [GO annotation PR #2449](https://github.com/intermine/intermine/pull/2449) and [NCBIfam PR #2454](https://github.com/intermine/intermine/pull/2454) once the build baseline is reliable. Their small diffs still require current-format fixtures. The query-operator contribution [#2306](https://github.com/intermine/intermine/pull/2306) needs broader parser and SQL coverage. No patch was merged during triage.

Build-related issues such as #2244 and #2089 should be evaluated against the actual migration. Changing the test framework is a separate compatibility step; the first build modernization keeps Java 8 application compatibility and existing test semantics while removing Gradle APIs that prevent running a newer Gradle.
