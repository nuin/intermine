# Upstream integration comparison

This proposal compares public source snapshots. It does not establish an upstream
release policy.

Direction confirmed on 2026-11-24: retain the InterMine and BlueGenes names and
collaborate with Gos Micklem. The sequence below is a proposal for that collaboration;
the shared base, release targets and upstream Java baseline still need joint agreement.

| Source | Revision |
| --- | --- |
| `gmicklem/intermine`, `maint-2026` | `97a2647d78306829abcf9b1b41c60c24e90e8acc` |
| `nuin/intermine`, `maintenance/java21-bluegenes` | `bb6f02af8760a3c33be9498147a8e544fb4a8d52` |
| Shared ancestor | `77cf7068dad0beac153e93e9916997d0ea850372` |

GitHub reports 50 commits unique to the nuin snapshot and 319 unique to the
gmicklem snapshot. These are ancestry counts, not counts of independent features
or unresolved conflicts. [Compare the snapshots](https://github.com/gmicklem/intermine/compare/97a2647d78306829abcf9b1b41c60c24e90e8acc...nuin:bb6f02af8760a3c33be9498147a8e544fb4a8d52).

## Decisions to settle before integration

| Area | Observed difference | Proposed treatment |
| --- | --- | --- |
| Java baseline | gmicklem documents JDK 17 builds with Java 8 bytecode and JDK 21 validation. This branch requires Java 21 and emits Java 21 bytecode. | Agree on the 6.0 baseline explicitly; keep compatibility fixes separate from the runtime-floor change. |
| Gradle and dependencies | Both forks modernize Gradle, publishing and dependency management. gmicklem uses a version catalog and Gradle 8.14.6; this branch uses shared scripts and Gradle 8.14.7. | Compare dependency and publication behavior before choosing one configuration. Avoid stacking equivalent upgrades. |
| Dynamic classes | gmicklem documents Byte Buddy replacing cglib. This branch uses Javassist proxies in `DynamicBean`. | Compare generated-class behavior and regression tests before selecting an implementation. |
| Legacy web pages | gmicklem documents bundled CDN assets, Tomcat expression fixes and a source-tree EL checker. | Evaluate these for currently deployed pages and retain relevant tests during the BlueGenes transition. |
| Data import | Recent gmicklem commits fix publication duplication, Pfam naming, NCBIfam/AntiFam handling and interaction host organisms. | Review these as focused scientific-data changes, with their source fixtures. |
| New API behavior | This branch adds Solr 10 integration, PostgreSQL 18 validation and deferred OAuth transactions for BlueGenes. | Port by feature with deployed API checks; preserve anonymous lists, profiles and token ownership. |
| BlueGenes | Alliance styling/tools and backend authentication are being developed separately. | Integrate completed commits in dependency order, then run the paired backend/frontend gates. |

The first three rows are supported by the pinned
[build guide](https://github.com/gmicklem/intermine/blob/97a2647d78306829abcf9b1b41c60c24e90e8acc/docs/BUILDING.md).
The web-page changes are described in the pinned
[deployer guide](https://github.com/gmicklem/intermine/blob/97a2647d78306829abcf9b1b41c60c24e90e8acc/docs/UPGRADING-build.md).
Importer changes are visible in the
[maintenance history](https://github.com/gmicklem/intermine/commits/97a2647d78306829abcf9b1b41c60c24e90e8acc/).

## Proposed integration sequence

1. Choose a shared integration base and record ownership for each overlapping area.
2. Port compatible defect fixes as small, reviewable groups. Select the intended
   maintenance release with upstream before opening release-targeted PRs.
3. Agree on the major-release Java floor and consolidate the build/dependency work.
4. Integrate search, database and authentication changes with their regression gates.
5. Integrate completed BlueGenes tools/style commits, then exercise scientific
   workflows against a small biological mine and the deployed API.
6. Retain InterMine and BlueGenes branding, preserve contribution attribution and
   document the agreed release and deployment migration path.

Use one integration branch and one owner for conflict resolution. Compare behavior
and tests before replacing overlapping implementations; preserve commit attribution.
