# Runtime dependency audit and maintenance

This phase follows the Java 21 and reliability changes integrated into the fork's `beta` at `d803440c87`. It preserves Java 8-compatible artifacts and the existing `javax` web framework. It does not constitute a security certification of the application.

## Baseline

The deployed testmine WAR contained 107 JARs. Matching embedded Maven metadata and the resolved core/testmine graphs yielded 105 external package/version queries. The [OSV batch API](https://google.github.io/osv.dev/post-v1-querybatch/) returned 60 distinct advisory IDs across 21 queried versions. No response required pagination. The [baseline inventory](audits/runtime-before-2026-11-19.json) records artifact hashes, coordinates, and advisory matches.

Package matches identify affected versions, not demonstrated exploitability. The audit does not cover vendored JavaScript, bytecode without identifiable metadata, production configuration, or all application-level attack paths. StringTree JSON 2.0.5 has no Maven metadata in its vendored JAR and requires separate review.

## Packaging correction

Testmine's artifact-only local Ivy repositories discarded dependency metadata. Its WAR then overlaid its own dependency set with the core WAR's libraries, leaving multiple ANTLR, Commons Codec, Digester, and Validator versions. It also bundled the servlet API supplied by Tomcat.

Development builds now resolve `org.intermine` exclusively from local Maven publications, including their metadata. Run `./gradlew install` in `intermine` before building testmine; `testmine/setup.sh` already does this. Missing local publications fail instead of falling back to upstream binaries. Testmine assembles managed libraries from its resolved graph and explicitly retains the core's vendored StringTree library. Future vendored libraries must be added to this packaging rule. Servlet and JSP APIs remain provided dependencies.

Resolving that metadata exposed invalid objectstore/pathquery POM dependencies for the custom ANTLR generator (missing `groupId`). The generator remains the vendored caching JAR, declared as a file dependency; the standard ANTLR 2.7.6 runtime now has explicit Maven coordinates. The corrected graph packages previously omitted transitive libraries, so the final inventory must be compared by package as well as by total advisory count.

Before version upgrades, the corrected WAR contains 139 JARs and 138 external Maven package/version queries. OSV matches 91 distinct advisories across 28 versions, including the now-packaged older Jackson/Jena stack. Compatible transitive-library patches are therefore part of this phase; restoring metadata alone is not the completed security change.

`python3 config/lib/verify_war.py testmine/webapp/build/libs/webapp.war` checks one provider for 14 critical runtime classes, including JAXB and StringTree, and rejects bundled servlet/JSP APIs. The setup script runs this gate after deployment. This is a targeted packaging check, not a complete duplicate-class or vulnerability scanner.

## Compatible upgrade batch

The selected releases retain the existing package namespaces and Java 8 support:

| Component | Before | Target | Upstream evidence |
| --- | --- | --- | --- |
| PostgreSQL JDBC | 42.2.2 | 42.7.14 | [Supported Java version](https://jdbc.postgresql.org/download/), [security fixes](https://jdbc.postgresql.org/security/) |
| Commons BeanUtils | 1.8.0 packaged / 1.9.3 declared | 1.11.0 | [Release history](https://commons.apache.org/proper/commons-beanutils/changes.html) |
| Commons Collections | 3.2 packaged | 3.2.2 | [Serialization mitigation and binary compatibility](https://commons.apache.org/proper/commons-collections/release_3_2_2.html) |
| Commons IO | 2.11.0 resolved | 2.22.0 | [Java 8 release notes](https://commons.apache.org/proper/commons-io/changes.html) |
| Commons FileUpload | 1.2.2 | 1.6.0 | [Security fixes](https://commons.apache.org/proper/commons-fileupload/security.html), [Java 8 release](https://commons.apache.org/proper/commons-fileupload/download_fileupload.cgi) |
| JSON-java | 20170516 | 20260814 | [Security release history](https://github.com/stleary/JSON-java/releases), [Java 8 build target](https://github.com/stleary/JSON-java/blob/20260814/pom.xml) |
| Tomcat | 9.0.115 | 9.0.124 | [Changelog](https://tomcat.apache.org/tomcat-9.0-doc/changelog.html), [Java support](https://tomcat.apache.org/whichversion.html) |
| Jackson | Core/Databind 2.10.1; JAX-RS/JAXB modules 2.8.4 | 2.21.8 BOM | [Java 8 LTS branch](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.21) |
| Ant | 1.6.5 / 1.7.0 | 1.10.18 | [Java 8 release](https://ant.apache.org/bindownload) |
| Guava | 19.0 | 33.7.2-jre | [Security patch](https://github.com/google/guava/releases/tag/v33.7.2) |
| Commons Compress | 1.20 | 1.28.0 | [Java 8 support](https://commons.apache.org/proper/commons-compress/) |
| Commons Lang 3 | 3.10 | 3.22.0 | [Release history](https://commons.apache.org/proper/commons-lang/changes.html) |
| Xalan and serializer | 2.7.2 | 2.7.3 | [Java 8 security release](https://xalan.apache.org/xalan-j/readme.html) |
| Snappy | 1.1.10.1 | 1.1.10.12 | [Security and subsequent compatibility fixes](https://github.com/xerial/snappy-java/releases), [Java 8 compilation](https://github.com/xerial/snappy-java/blob/v1.1.10.12/build.sbt) |

`config/gradle/runtime-dependencies.gradle` supplies shared versions and dependency constraints across the core, bio, and testmine builds. Constraints upgrade libraries already needed by a module without adding each library to every module. Jackson uses its BOM to align the older Jersey provider with Databind and the JAXB annotation module; the `javax` providers are retained. The BOM controls each module's version, including Jackson's separate annotation-version scheme. Legacy `ant:ant` coordinates are normalized to `org.apache.ant:ant` so Gradle resolves one Ant family.

Guava 33.8.0 is also available; this batch deliberately uses the verified security patch 33.7.2. Snappy 1.1.10.12 includes fixes following the 1.1.10.9 security release. `RuntimeCompatibilityTest` checks Jersey JSON over loopback HTTP, Jena Turtle serialization, and native Snappy compression before and after the second batch.

Xalan 2.7.3's published POM omits its serializer dependency. The existing profile XML export test failed with a missing `SerializerTrace` class after the version update; `intermine-api` now declares the matching serializer explicitly.

FileUpload's file-count limit is opt-in; updating the library alone does not configure limits in Struts' multipart handler. The remaining Struts work must cover request size, part-count limits, and error handling rather than treating a clean package-version lookup as sufficient protection.

## Remaining support work

- **Struts 1, Tiles, and DWR:** Struts 1.3.10 is [unsupported](https://struts.apache.org/struts1eol-announcement.html) and has no drop-in supported replacement. The baseline also matches DWR advisories. Replace or isolate these legacy web paths in the separate framework migration.
- **Solr/Lucene and their networking dependencies:** [Solr's support policy](https://solr.apache.org/downloads.html) marks versions older than 9.11 EOL. Solr 8.11.4 retains older Jetty, Netty, and ZooKeeper dependencies with advisory matches. A supported search-stack migration needs index/configuration compatibility and integration tests; Java 8 compatibility constrains the available client versions.
- **PostgreSQL server:** PostgreSQL 14 [reached EOL on 2026-11-12](https://www.postgresql.org/support/versioning/). The JDBC upgrade does not upgrade an operator's database. Plan and test a supported server version, backup/restore or `pg_upgrade`, and query-performance checks separately.
- **Other legacy libraries:** Commons HttpClient 3, Commons Lang 2, Jersey/Jena and Thrift, and vendored libraries remain audit targets. The final artifact scan below will enumerate current matches without implying that unmatched packages are supported or safe. Embedded Torque/Velocity bytecode without Maven metadata and downstream mine WARs also require separate inventories.

## Validation

The packaging regression failed on all four duplicate providers and the bundled servlet/JSP APIs before the fix. It passes after the metadata/assembly correction. Fresh-fixture live runs passed 405 JavaScript tests (one upstream pending) and 74 Python tests.

Those runs exposed an existing ownership defect: registration with an authenticated token moved the current account's private lists into the new account. Deleting that new account removed the original lists from PostgreSQL, while the running service's cache could still return them. The JavaScript registration test registered and deleted an account this way, explaining why reusing its fixture database after a restart failed. The defective condition is also present before the Java modernization at `d41accd515`.

`config/ci/registration.test.cjs` reproduces this using disposable accounts and a private fixture list. The authenticated case failed against the original service; anonymous-list migration passed. Registration now transfers lists only from anonymous profiles and initializes an empty merge result for other requests. Both live regressions pass after the fix. The full webapp suite passes 235/235 on Java 21 and Java 8, with Checkstyle passing. The full live suites pass again (JavaScript 405 plus one upstream pending; Python 74), and all 11 fixture list records remain in PostgreSQL afterward. The live gate runs after the JavaScript client suite in CI. The upgraded deployment will also verify these lists after a restart.

Final upgrade-suite results and the post-upgrade inventory remain in progress. Aislop reports no errors or fixable warnings; three existing nonfixable broad-`throws Exception` warnings in the registration service's framework/mail callbacks remain for separate exception-API cleanup.
