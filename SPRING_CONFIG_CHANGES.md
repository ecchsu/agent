# Spring Configuration Record/Replay — Code Changes

Implements record/replay of Spring configuration (dynamic `Environment` reads, `@Value` fields,
and `@ConfigurationProperties` beans), so that when traffic recorded in one production
environment is replayed against a different host, it uses its own originally-recorded
configuration rather than the replay host's local config. Full design rationale, decision log,
and a debugging guide are in
[`docs/design/spring-configuration-record-replay.md`](docs/design/spring-configuration-record-replay.md)
— this file is just an index of what changed and where.

Companion project: a standalone Spring Boot demo app used to build and verify this feature end to
end, at `../arex-spring-config-demo` (sibling directory, separate repo).

## New module: `arex-instrumentation/config/arex-spring-config`

All new files. One module covers both parts of the feature, gated by a single flag
(`arex.spring.config`, default off).

| File | Purpose |
|---|---|
| `pom.xml` | Module definition; `provided` deps on spring-core/context/beans/webmvc, spring-boot, and both javax/jakarta servlet APIs. |
| `SpringConfigChecker.java` | Reads the `arex.spring.config` flag directly via `System.getProperty(...)` (not `Config.get()` — see design doc §12.2 for why). Off by default. |
| `SpringConfigModuleInstrumentation.java` | `@AutoService(ModuleInstrumentation.class)` entry point. Returns an empty instrumentation list when the flag is off, so no bytecode of any of the classes below is ever touched. Logs the enabled/disabled decision once at startup. |
| **Part A — dynamic `Environment` reads** | |
| `SpringPropertyResolverInstrumentation.java` | Advises `org.springframework.core.env.PropertySourcesPropertyResolver#getProperty(String, Class, boolean)` — the plain-Spring resolver, and what `@Value` placeholder resolution uses internally. |
| `SpringBootConfigurationPropertyResolverInstrumentation.java` | Advises `org.springframework.boot.context.properties.source.ConfigurationPropertySourcesPropertyResolver#getProperty(String, Class, boolean)` — the resolver a Spring Boot app's *own* `Environment` actually uses (a different class from the above; without this, Part A silently does nothing on real Spring Boot apps). |
| `SpringPropertyAdvice.java` | The shared ByteBuddy advice body for both of the above (same method shape, so one advice class serves both). |
| `SpringConfigExtractor.java` | Buffers dynamic reads into the request's `ArexContext` attachment during recording; flushes as one aggregate `ConfigFile` mocker (`operationName = "spring-config-env"`) at request exit. On replay, fetches that one aggregate on the first read of a request and caches it for the rest of the request. |
| `SpringConfigServletV3RequestHandler.java` / `SpringConfigServletV5RequestHandler.java` | `@AutoService(RequestHandler.class)`; call `SpringConfigExtractor.flushRecordBuffer()` in `postHandle` (javax vs jakarta servlet variants, mirroring Apollo's own V3/V5 pattern). |
| **Part B — `@Value` / `@ConfigurationProperties`** | |
| `SpringApplicationRunInstrumentation.java` | Advises `SpringApplication#run(String...)` on exit (same hook `arex-component-scan` already uses) to trigger the registry scan once, right after the context finishes starting. |
| `SpringBeanConfigRegistry.java` | Built once per JVM: reflectively scans every already-instantiated singleton bean for `@Value`-annotated fields or `@ConfigurationProperties`-annotated classes. Excludes anything under `org.springframework.*` (Spring Boot's own internal auto-configuration beans — see design doc §12.2). Logs a summary of what was registered. |
| `DispatcherServletInstrumentation.java` | Advises `DispatcherServlet#doDispatch`: `onEnter` applies replay overrides, `onExit(onThrowable = Throwable.class)` always restores them and records — guaranteed even if the request handler throws. |
| `SpringBeanConfigExtractor.java` | The core logic: per-bean `ReentrantLock`s (acquired in sorted order to avoid deadlock), reflective field get/set, one aggregate `ConfigFile` mocker per request (`operationName = "spring-config-properties"`) for both record and replay. |

## Changes to existing files

| File | Change |
|---|---|
| `arex-agent-bootstrap/.../constants/ConfigConstants.java` | Added `ENABLE_SPRING_CONFIG = "arex.spring.config"`. |
| `arex-instrumentation/pom.xml` | Registered `config/arex-spring-config` as a submodule. |
| `arex-agent/pom.xml` | Added `arex-spring-config` as a dependency, so it's bundled into the shaded agent jar. |

## New documentation

| File | Purpose |
|---|---|
| `docs/design/spring-configuration-record-replay.md` | Full proposal: background on how the agent works, why Apollo's mechanism doesn't transfer directly, the two-part design, the feature flag, cross-cutting concerns, decisions made during discussion, what was actually implemented (and where/why it differs from the original proposal), and a log-tracing debugging guide. |

## Phase 0: `23a57ead` — isolate per-field replay failures

Found during live testing: a type mismatch on one field (a `String` recorded for an
enum-typed property) threw `IllegalArgumentException`, which propagated past
`setFieldValue`'s `IllegalAccessException`-only catch and was caught by the outer
per-request handler — aborting override for *every* field and bean in the request, not
just the one bad one. Prerequisite for Phase 1 and 2: both add fields whose write can fail
in new ways (a record's own components, and a derived field with no annotation of its
own), and both rely on this isolation to fail open per-field instead of per-request.

| File | Change |
|---|---|
| `SpringBeanConfigExtractor.java` | Wrapped each field's deserialize/read/write in its own try/catch inside `applyReplayOverrides()`'s per-field loop, logging `spring.bean.config.field.replay.error` and continuing on failure. |

## Phase 1: `9fd3d244` — reconstruct-and-swap for record-typed sources

Fixes: a record-typed `@ConfigurationProperties` bean (e.g. `public record KProperties(String
timeHourList, List<String> scanNameList) {}`) can never be mutated in place during replay —
verified live: `Field.set()` on a record component throws `IllegalAccessException` even with
`setAccessible(true)`, unlike an ordinary final field. It silently keeps its live value.

Reconstructs a new instance via the record's canonical constructor (recorded values for
recorded components, the live instance's own current value for anything not recorded this
time) and swaps the reference in whatever ordinary field holds it — the record itself is
never touched. `SpringBeanConfigRegistry.scan()` now finds record-typed sources first, then
scans *every* application bean (not only ones with their own `@Value`/`@ConfigurationProperties`
field) for a holder field, since a holder bean — e.g. a controller with `private final
KProperties kProperties;` and no annotated field of its own — would otherwise never be
registered at all.

| File | Change |
|---|---|
| `RecordConfigSource.java` | New. `isRecordSafe()` (guards `Class.isRecord()`, a Java 16 API this module doesn't compile against via `--release`, so it's reflection-checked rather than assumed available on the target JVM), `reconstruct()`, `serializeComponents()`. |
| `SpringBeanConfigRegistry.java` | `scan()` restructured into passes: collect all application beans → find record-typed sources → per bean, merge annotation-eligible fields with any holder field found by `mergeRecordHolderFields()`. New `RECORD_HOLDER_FIELDS` map and `recordHolderFields()` accessor. |
| `SpringBeanConfigExtractor.java` | `captureAndRecord()`/`applyReplayOverrides()` branch on `recordHolderFields()`: a holder field's value is serialized as a component map and reconstructed via `RecordConfigSource`, instead of the normal scalar `serializeWithType`/`deserializeWithType` path. |
| `arex-spring-config/pom.xml` | `maven.compiler.testSource`/`testTarget` set to 17 — test fixtures use `record` syntax; main code stays on the inherited `java.version` (8) for target-JVM compatibility. |

**Tests:** `RecordConfigSourceTest.java`, `SpringBeanConfigRegistryTest.java`.

**Verified live** against `../arex-spring-config-demo`: recorded a case, changed the live
config to different values, rebuilt/restarted, replayed the old case — the record-typed
field correctly reflected the recorded values, not the live ones.

## Phase 2: `61ef5772` — one-hop bytecode tracer for constructor-flattened fields

Fixes: a `@Value` scalar field read once and passed unchanged into another bean's
constructor (e.g. `DependencyInjection.fetchGroup` → `new GetFabImpl(gateway, fetchGroup)`,
cached in `GetFabImpl`'s own final field) is invisible to the registry — the source field
overwrites fine during replay (an ordinary final field, reflectively writable), but nothing
re-reads it once copied. A **discovery** gap, not a writability one.

`OneHopFieldCopyScanner` statically reads the source field's declaring class's own bytecode
(private fields can only be `GETFIELD`'d from within their own declaring class, so no need to
scan the whole application) for a `this.field` read passed unchanged as one constructor
argument — no transformation, no helper-method hop — then checks the target constructor's own
bytecode for that parameter assigned directly to one of its fields. Deliberately scoped to
exactly this shape: a branch anywhere in either method disqualifies every match found there
(an `if`/`else` could construct the same target differently in each arm), and anything wider —
multi-hop chains, record-accessor sources, transformations — fails open, registering nothing,
same as today's behavior for that field. First bytecode-body-analysis feature in this
codebase; needed a new `org.ow2.asm:asm` dependency (not `asm-tree`/`asm-analysis` — the
narrow scope only needs a linear single-pass scan) and a shade-plugin relocation, matching the
existing pattern for `net.bytebuddy`/`org.slf4j`.

| File | Change |
|---|---|
| `OneHopFieldCopyScanner.java` | New. `findOneHopCopies()`, plus the two ASM `MethodVisitor`s: `PassthroughDetector` (factory-method side) and `DirectAssignmentDetector` (constructor side). |
| `SpringBeanConfigRegistry.java` | `scan()` gains a third pass, `registerDerivedFields`-equivalent inline block: calls `OneHopFieldCopyScanner.findOneHopCopies()` and merges results into `BEAN_INSTANCES`/`BEAN_FIELDS` the same way the Phase 1 holder-field pass does. No `SpringBeanConfigExtractor` change needed — a derived field is an ordinary field with an ordinary scalar value, flowing through the existing per-field loop unchanged. |
| `arex-spring-config/pom.xml` | Added `org.ow2.asm:asm:9.6` (compile scope, not `provided` — unlike Spring, the target app doesn't bring its own ASM the agent can rely on). |
| `arex-agent/pom.xml` | Added `org.objectweb.asm` → `shaded.org.objectweb.asm` relocation, and `org.ow2.asm:asm` to the shade plugin's `artifactSet` `<includes>` allowlist (without this, the dependency is silently dropped from the shaded jar entirely). |

**Tests:** `OneHopFieldCopyScannerTest.java` — the positive case plus four negative fail-open
cases (transformation, helper-method hop, branch in the factory method, branch in the
constructor).

**Bug found and fixed during live verification, not caught by unit tests:** `@Configuration`
beans are CGLIB-proxied by Spring by default, so `bean.getClass()` at runtime returns a
proxy subclass with no `.class` file resource to read (generated in-memory). Fixed by
scanning `field.getDeclaringClass()` instead — reflection always resolves this to the
original user class regardless of proxying.

**Verified live** against `../arex-spring-config-demo`, same workflow as Phase 1 — the
derived field correctly reflected the recorded value instead of the live one, with no
regression to Phase 1 or any unrelated endpoint. Also verified under genuine concurrent
replay load (60 interleaved requests across 5 distinct recorded environments plus a 6th,
distinct local config, both via direct `arex-record-id` header requests and via the actual
`createPlan` schedule-service webhook): every request correctly used its own recorded
config, with zero cross-contamination.

## What's *not* changed

No existing instrumentation module, no existing shared runtime class (`MockUtils`, `ContextManager`,
`ArexContext`, `Serializer`, etc.), and no existing servlet/Apollo code was modified — this feature
is entirely additive, reusing the existing `MockCategoryType.CONFIG_FILE` category and existing
`RequestHandler`/`ModuleInstrumentation` extension points rather than changing their behavior for
other categories.
