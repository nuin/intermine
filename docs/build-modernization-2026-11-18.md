# Build modernization — 18 November 2026

For the subsequent Java 21 work, see the [Java migration guide](java21-migration-2026-11-19.md).
The results below record the original Gradle migration on Java 8.

This change moves the build from Gradle 4.9 to **Gradle 8.14.7** while retaining Java 8 source/target compatibility and application dependency versions. Gradle 8 is the bridge for the existing Java 8 application; moving the application to a newer JDK remains separate work.

## Changes

- All seven wrappers use the official Gradle 8.14.7 wrapper. The installer generates a distribution checksum automatically for the official ZIP and accepts `--gradle_distribution_sha256` for a custom distribution.
- Explicit HTTP repository URLs receive a separate opt-in for that repository. Default HTTPS repositories retain Gradle's protection against an insecure redirect; `--offline` continues to support a local HTTP mirror.
- Maven publications replace the removed `maven` plugin. `install` remains an alias for `publishToMavenLocal`; source, Javadoc, test-class and webapp class artifacts retain their classifiers. Remote publication requires an explicit `publishRepositoryUrl`; the retired OSSRH URL is no longer a default destination.
- Dependency declarations use modern configurations. Ant tasks resolve execution classpaths, and the model plugin's private `intermineApi` configuration avoids colliding with the Java plugin's `api` configuration.
- Resource preparation now has explicit task dependencies. Importer test setup retains each source's merged model instead of overwriting it with the base model; downstream mine compilation consumes the model JAR, including its generated classes.
- Test failures now fail Gradle directly. CI runs on pull requests, pushes affecting the project, and manual dispatch; it includes the Python bootstrap and Gradle plugin tests.
- The importer build uses a 1 GiB Gradle heap after the full suite exposed pressure on Gradle's default heap.

The official distribution SHA-256 is `903bfc4bc7622323124054c10f509246212a1902948bdaf26c379cca8735e856`; the wrapper JAR SHA-256 is `ad16bca46cb71bb5887c546860d93992aeee35c1fdaf4a242b0a612375d937e0`.

## Developer entry points

Use Python 3.10 or later and JDK 8. Set `PSQL_HOST`, `PSQL_USER` and `PSQL_PWD` for a disposable test database, then run:

```sh
python3 -m unittest discover -s config/lib -p 'test_*.py'
python3 config/lib/install_intermine.py
```

The installer generates ignored Gradle configuration files and locally installs the plugin, core, bio, source and postprocessing artifacts. It preserves existing files in `~/.intermine`; inspect those files when changing test database settings. The existing test database setup tasks use a local operating-system `postgres` account and PostgreSQL on localhost.

Run `./gradlew test` inside `plugin`; run `./gradlew build` inside `intermine`, `bio`, `bio/sources`, `bio/postprocess` and `bio/postprocess-test` after installation. The existing CI scripts configure the database and run these suites.

## Verification ledger

Verification started from `787f779b7a3be3d778de784229a701cc47dc355b`. This workstation has no host JDK, so Java checks run in isolated Docker containers with an isolated PostgreSQL 14 service and no published database port. Application deployment and remote publishing are not implied by local build results.

| Check | Observed result |
| --- | --- |
| Original Gradle 4.9 / Java 8 plugin `install` | Passed. |
| Original Gradle 4.9 / Java 8 core `:intermine-model:compileJava` | Passed. |
| Unchanged plugin on Gradle 8.14.7 | Failed at removed `MavenDeployment`, confirming the migration requirement. |
| Python wrapper/bootstrap tests | Nine passed, including custom/offline distribution integrity and scoped HTTP mirrors. |
| Official wrapper download and launch | Passed on Java 8u512; reported Gradle 8.14.7. All seven wrapper JAR hashes match the official checksum. |
| New plugin `test install` | Passed; one regression test checks the model plugin configuration boundary. |
| New core `:intermine-model:compileJava` | Passed. |
| Core `build install --continue` | Passed, including all Checkstyle tasks. XML reports: 2,116 tests across 251 test classes; zero failures, errors or skipped tests. |
| Core artifacts | Test model JAR contains generated `Employee.class` and configured database properties; core WAR and classes JAR contain no duplicate entries. POM coordinates remain `org.intermine:*:5.1.1`; webapp packaging is `war`. |
| Bio `build install --continue` | Passed, including Checkstyle, generated model, WAR assembly and local publication. XML reports: 92 tests; zero failures, errors or skipped tests. |
| Bio sources `build install --continue` | Passed, including Checkstyle and all importer artifacts. XML reports: 59 tests across 44 classes; zero failures, errors or skipped tests. |
| Importer model regression | Clean builds of BioGRID, Chado and GO annotation passed all 17 tests after fixing the model overwrite. |
| Checkstyle adapter | Original 7.8.1 rules retained. Clean API run passed; a deliberately invalid temporary fixture failed with five warnings and produced XML/HTML reports. Model and pathquery checks also pass with the explicit CLI logging dependency. |
| Java 8 bytecode | Representative core, generated bio model and Groovy plugin classes have class-file major version 52. |
| Test mine | `:dbmodel:loadsadata`, `:dbmodel:insertUserData` and `:webapp:war` passed against isolated demo/profile databases. The WAR contains the generated model classes, merged web properties and database summary, with no duplicate entries. |
| Postprocessor `build install --continue` | Passed for every configured postprocessor, including database setup and all artifacts. These modules have no separate unit tests; their test suite is listed below. |
| Postprocessor Checkstyle | Passed for all configured postprocessors. |
| Postprocessor test suite | `build --continue` passed: 12 tests across eight classes, with zero failures, errors or skipped tests. |
| Standard installer | `python3 config/lib/install_intermine.py` passed end to end through the generated wrappers: plugin, core, bio, importers and postprocessors all installed locally. The importer build completed with the new 1 GiB heap. |
| Local quality checks | Nine Python tests passed; workflow YAML parsed, CI shell syntax passed, and `git diff --check` was clean. Aislop reported 100/100 with zero findings for supported files; Gradle/Groovy changes also received independent review and build validation. |

The final JVM test reports contain **2,280 tests**, with zero failures, errors or skipped tests. The nine Python bootstrap tests are additional to that total.

The test image was built from `gradle:8.14.7-jdk8` (Temurin 8u512), with Python, PostgreSQL client, `sudo` and an operating-system `postgres` user added for the existing test harness. Test state is confined to named Docker volumes and the repository's ignored build output.

Live JS/Python web-service client suites requiring Tomcat and Solr were not run. GitHub workflow YAML and shell scripts were checked locally; hosted workflow execution and remote publication remain separate checks.

## Sources

- [Official Gradle version metadata](https://services.gradle.org/versions/all), including distribution and wrapper checksums.
- [Gradle Java compatibility](https://docs.gradle.org/8.14.7/userguide/compatibility.html).
- [Gradle 7 migration guide](https://docs.gradle.org/8.14.7/userguide/upgrading_version_6.html), covering removed legacy publishing and dependency APIs.
- Current CI actions: [checkout 7](https://github.com/actions/checkout/releases/tag/v7.0.1), [setup-java 6](https://github.com/actions/setup-java/releases/tag/v6.3.0), [setup-python 7](https://github.com/actions/setup-python/releases/tag/v7.1.0).
