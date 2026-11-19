# Release validation

Validation follows the three backlog fixes on `maintenance/reliability`. Java 21 remains the development/runtime target, with Java 8-compatible artifacts.

## Hosted CI

The first run on `8868b7bb36` passed Checkstyle and browser tests. Web-service setup failed because the hosted runner's `sudo` ignored `-E` and removed `PGPASSWORD`; `dropdb` repeatedly prompted for a password. PostgreSQL administrative commands now use explicit `-U postgres --no-password` options directly, including core/bio Gradle database setup and testmine setup. This retains password authentication and makes missing credentials fail immediately. Both core and bio database-creation tasks passed locally with the changed commands.

JavaScript client execution now uses the checked-in lockfile with `npm ci` and the package's `npm test` command, so it resolves local Grunt rather than requiring a global installation. CI selects Node 22 to match local validation. PostgreSQL health checks name the postgres role explicitly.

- Initial [InterMine CI run](https://github.com/nuin/intermine/actions/runs/44599216455).
- Initial [Checkstyle run](https://github.com/nuin/intermine/actions/runs/44599216515).

## External clients

Public client revisions fetched for validation:

| Client | Revision | Unit result |
| --- | --- | --- |
| imjs | `a12fa1469d8b77d4f50538fc52cb7c3064925a62` | 898 passed on Node 22 |
| intermine-ws-python, dev | `ed1bf6bd0c89defe647b9e4f66c363ada65303ae` | 105 discovered: 102 passed, 3 upstream skips on Python 3.10 |

The Python skips are the upstream `RegistryTest` class, marked `Flymine not available`. No tests were disabled locally. Client checkouts and logs are outside the repository under `/private/tmp/intermine-release-2026-11-19`.

Live client validation, final hosted CI, and integration are still in progress.
