# Java 21 migration

This branch makes Java 21 the development and CI target while retaining Java 8 source, API, and bytecode compatibility. Gradle remains at 8.14.7 and the web application remains on Tomcat 9.

## Compatibility changes

- Javassist 3.33.0-GA replaces CGLIB 3.1 for dynamic model objects. The old CGLIB implementation accessed `ClassLoader.defineClass` through reflection, which Java 21 rejects. The new implementation runs without application `--add-opens` arguments.
- Dynamic objects retain primitive defaults and numeric conversions, constructor-initialized values, per-instance property storage, inherited interfaces, model metadata decomposition, and lazy references. Model properties named `handler` remain ordinary persisted fields. The parser continues to accept legacy NotXml records containing the former CGLIB factory interface name.
- Java compilation on JDK 9 and later uses `--release 8`. This restricts both bytecode and available Java APIs; setting source/target alone does not prevent accidental calls to newer JDK APIs.
- Test processes inherit application properties without copying JVM-owned `java.*`, `sun.*` and `jdk.*` properties from the Gradle process. This prevents a Java 8 test launcher from using Java 21 runtime paths.
- The Gradle plugin test fixture receives the specific `java.lang` opening required by `ProjectBuilder`. This is limited to plugin tests; it is not passed to application tests or Tomcat.
- JAXB runtime 2.3.9 supplies the `javax.xml.bind` implementation removed from newer JDKs. The existing JAXB API stays at 2.3.1.
- Testmine WAR extraction uses `Sync` so incremental builds remove obsolete dependency JARs.
- EasyMock 5.7.0 replaces its old CGLIB-based version in webapp tests. It supplies its compatible Objenesis dependency.
- CI runs core and bio suites on Java 21 and Java 8. Checkstyle and web-service tests use Java 21. Solr 8.11.4 runs separately on Java 11 in CI.

## Building

Set `JAVA_HOME` and `PATH` to a Java 21 JDK, and confirm `java -version` before using the existing installer:

```sh
python3 -m unittest discover -s config/lib -p 'test_*.py'
python3 config/lib/install_intermine.py
```

The database setup requirements and individual Gradle entry points remain as described in the [build modernization guide](build-modernization-2026-11-18.md). Existing Java 8 installations can still build this branch.

Custom code that directly used CGLIB's `Factory`, callbacks, or `DynamicBean.intercept` must migrate to Javassist's `Proxy`, `ProxyFactory.getHandler`, or `DynamicBean.invoke`. Ordinary model interfaces and `DynamicUtil` creation methods retain their signatures. Use `DynamicUtil.createObject(Class)` to instantiate a composed class without losing constructor-initialized values. Rebuild and deploy matching InterMine modules together; use a clean build when upgrading an existing mine.

InterMine uses Javassist’s deprecated class-level initializer API (`ProxyFactory.setHandler`) to preserve constructor callbacks. That API disables Javassist’s class cache, so InterMine caches generated classes by superclass and interfaces. The initializer installs a separate `DynamicBean` on each instance before dispatching its first constructor callback.

## Verification results

The local environment uses Temurin 21.0.13 and isolated PostgreSQL 14 and Solr 8.11.4 containers. No production databases are used. The compatibility comparison uses Temurin 8u512 as the test launcher while Gradle and compilation stay on Java 21. A separate plugin build runs Gradle and compilation on Java 8 as well.

| Check | Result |
| --- | --- |
| Initial plugin test on Java 21 | Failed at `ProjectBuilder` module access; passed after the fixture configuration change. |
| Initial dynamic object tests on Java 21 | Failed at CGLIB's reflective class definition; passed after replacement. |
| Primitive defaults regression | New test failed with null Boolean unboxing, then passed with all eight primitive types and `hashCode` checked. |
| Focused object and legacy parsing tests | 38 tests passed, followed by a successful 2,117-test core build before independent review. |
| Final Java 21 verification after installation | 2,290 tests passed across plugin, core, bio, importers and postprocessors, with the final property-forwarding configuration. |
| Final core `build install` on Java 21 | 2,126 tests passed; Checkstyle and local artifact publication passed in 2m59s. |
| Importer `build install` on Java 21 | 59 tests passed; all importer builds and Checkstyle passed in 6m58s. |
| Clean bio build on Java 21 | 92 tests passed; the genomic model was regenerated from an empty build directory, compiled and installed. |
| Live Tomcat 9.0.115 on Java 21.0.13 | Service version 35, model metadata, 132 employee query rows, token authentication, anonymous rejection (403) and five Solr search hits passed. |
| JAXB with assembled WAR libraries | XML marshal/unmarshal passed on both Java 21.0.13 and Java 8u512. |
| Standard installer on Java 21 | `python3 config/lib/install_intermine.py` passed through the generated wrappers for plugin, core, bio, importers and postprocessors, including a fresh verified Gradle distribution download. |
| Java 8 compiler and Gradle runtime | Plugin `test install` passed in 52s. |
| Java 8 runtime compatibility | 2,290 tests passed: plugin 1, core 2,126, bio 92, importers 59 and postprocessors 12; zero failures, errors or skips. |
| Java 8 bytecode | All 244 plugin, 1,948 core/test and 74 freshly generated genomic model classes inspected have major version 52. |
| Incremental and clean WAR assembly | Both contain only Javassist 3.33.0-GA, retain JAXB and StringTree JSON libraries, and have no duplicate archive entries. |
| Postprocessors on Java 21 | All builds and Checkstyle passed in 1m42s; 12 postprocessor tests passed in 1m07s. |

Independent review added eight proxy regression tests covering field-name collisions, constructor dispatch, class reuse and numeric coercion. Seven failed before the fixes; all eight passed afterward. A JAXB probe against the original WAR failed without a provider, and the added XML round-trip test passes with the runtime. The cross-JDK run additionally caught the inherited `java.home` problem; the full Java 8 suites passed after correcting property forwarding. The standard installer and final Java 21 test pass completed afterward. Both runtimes passed all 2,290 JVM tests with zero failures, errors or skips. Nine Python bootstrap tests also passed; workflow YAML, CI shell syntax and `git diff --check` passed.

## Reproducing the Java 8 runtime comparison

Build normally with Java 21 first. With both JDKs installed, save this Gradle init script as `/tmp/intermine-test-java8.gradle`:

```groovy
allprojects {
    tasks.withType(Test).configureEach {
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(8)
        }
    }
}
```

Keep `JAVA_HOME` on Java 21 and set `JAVA8_HOME` to the Java 8 installation. In each build root (`plugin`, `intermine`, `bio`, `bio/sources`, `bio/postprocess-test`), run:

```sh
./gradlew --no-daemon --max-workers=2 \
  -Porg.gradle.java.installations.paths="$JAVA8_HOME,$JAVA_HOME" \
  -I /tmp/intermine-test-java8.gradle test
```

This uses Java 21 for Gradle and compilation, then executes the resulting classes on Java 8. The separate plugin build with Gradle and the compiler on Java 8 verifies the older compiler configuration too.

## Quality notes

Aislop reports 100/100 with no errors or fixable warnings. Seven nonfixable warnings remain: large legacy `TypeUtil` and `Util` files, existing nesting in `wildcardSqlToUser`, two existing broad `flatten` signatures, and two `throws Throwable` declarations required to propagate errors through the Javassist handler. Gradle Checkstyle runs separately because the host Aislop installation lacks its Java lint tools.

Gradle still reports existing deprecated APIs that need separate work before Gradle 9. This migration retains Gradle 8.14.7, Tomcat 9 and the existing servlet stack.

The full external JavaScript/Python web-service client suites were not run locally. The live checks above exercise queries, authentication and search directly. Hosted GitHub Actions results are separate from these local results.

## References

- [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html).
- [Gradle migration: test-worker module access](https://docs.gradle.org/current/userguide/upgrading_version_7.html#remove_test_add_opens).
- [Javassist proxy API](https://www.javassist.org/html/javassist/util/proxy/ProxyFactory.html).
- [Javassist 3.33.0-GA published POM](https://repo.maven.apache.org/maven2/org/javassist/javassist/3.33.0-GA/javassist-3.33.0-GA.pom), including Java 8 compilation target.
- [EasyMock documentation](https://easymock.org/user-guide.html).
