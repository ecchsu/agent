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

## Phase 3: CGLIB/`@Configuration`-enhancement class+instance resolution, and record-accessor one-hop discovery

Two real, independent bugs found via real-app testing (investigation trail below), both fixed and
verified live. Neither was caught by Phase 2's unit tests, since those use plain, non-proxied
fixture classes — the demo app has since been extended with fixtures that actually exercise both
(see "Verified live" below).

**Bug A — CGLIB/AOP-proxy and `@Configuration`-enhancement break the target side of the one-hop
scanner, at two different levels.** Fixing this took two attempts:

1. First attempt: `findOneHopCopies()` grouped candidate targets by
   `Type.getInternalName(entry.getValue().getClass())` and read bytecode via
   `targetEntry.getValue().getClass()` — both the bean's *runtime* class. For an AOP-advised bean
   (any `@Transactional`/`@Cacheable`/`@Async`/`@Aspect`-matched bean), this is a generated CGLIB
   subclass with no loadable `.class` resource, that doesn't match `site.targetInternalName`
   (always the real class, since that's what the factory method's `NEW` instruction actually
   names). Tried `org.springframework.aop.support.AopUtils.getTargetClass(bean)` in place of
   `bean.getClass()`.
2. That alone wasn't sufficient, for two separate reasons, both found empirically (not just
   reasoned about) against the real demo app:
   - `AopUtils.getTargetClass()` only unwraps `Advised`/`TargetClassAware` instances. Every
     `@Configuration` class is *also* CGLIB-enhanced by Spring by default (to enforce singleton
     semantics between its own `@Bean` methods) — but that enhancement is not an AOP proxy and
     doesn't implement `Advised`, so `AopUtils.getTargetClass()` still returned the resourceless
     enhanced subclass for it (confirmed with a standalone repro:
     `AnnotationConfigApplicationContext` + a plain `@Configuration` class → `getTargetClass()`
     returned the same unusable `MyConfig$$SpringCGLIB$$0`). Fixed by switching to
     `org.springframework.util.ClassUtils.getUserClass()` throughout, which strips *any* CGLIB
     naming-convention subclass — AOP proxy or plain configuration enhancement alike — confirmed to
     correctly resolve both cases via the same standalone repro.
   - Separately, and more fundamentally: **a CGLIB proxy does not share field storage with the
     object it wraps.** Reflection on a proxy's own inherited field reads/writes the proxy's own,
     disconnected copy — never the wrapped target. Confirmed experimentally (`ProxyFactory` +
     `setProxyTargetClass(true)`, then `Field.set(proxy, ...)`: a subsequent method call on the
     proxy, which delegates to the real target, still returned the pre-write value). Class-level
     resolution alone (the fix above) finds the *right field*, but reading/writing it on the proxy
     instance itself is still a silent no-op. Fixed by unwrapping the bean *instance* once, in
     `SpringBeanConfigRegistry.scan()`, before it ever enters `applicationBeans` — via
     `((Advised) bean).getTargetSource().getTarget()` when the bean implements `Advised`. This also
     retroactively fixes the same latent gap for any *plain* `@Value`/`@ConfigurationProperties`
     field on an AOP-advised bean (Phase 0/1), not just derived fields.

**Bug B — a record accessor call isn't a recognized source (new discovery capability).** A
record-typed bean (e.g. `KlaProperties`) injected directly as a `@Bean` factory method's parameter,
with one of its accessor methods called (`klaProperties.qtimeHourList()`) and the result passed
unchanged into another bean's constructor. `PassthroughDetector` only recognized `GETFIELD` as an
atomic source push; any other method call, including a record accessor, aborted the match.

New `RecordAccessorPassthroughDetector` (sibling to `PassthroughDetector`, same straight-line/
branch-disqualification skeleton): recognizes `ALOAD <paramSlot>` (any parameter slot, not just
`this`) immediately followed by `INVOKEVIRTUAL`/`INVOKEINTERFACE` calling one of a known record
type's own accessor methods (matched via `Class.getRecordComponents()`) as one atomic argument
push. Reuses the existing `DirectAssignmentDetector`/`scanConstructorForDirectAssignment` for the
target side unchanged. `SpringBeanConfigRegistry.scan()` now threads `recordSourcesByType.keySet()`
(already built for Phase 1) into `OneHopFieldCopyScanner.findOneHopCopies()` as a new parameter,
enabling this second discovery pass alongside the existing field-based one. Once discovered, a
derived field merges into `BEAN_FIELDS` exactly like any other — no `SpringBeanConfigExtractor`
change needed, same as Phase 2. Scoped exactly to a directly-injected record parameter with one
accessor call; a `this.recordHolderField.accessor()` two-hop chain is unevidenced and deliberately
out of scope, same fail-open philosophy as the rest of this scanner.

| File | Change |
|---|---|
| `OneHopFieldCopyScanner.java` | `findOneHopCopies()` gains a `Set<Class<?>> recordSourceTypes` parameter and a second discovery pass; class-level resolution throughout (`groupBeansByRealInternalName()`, the target-side lookup in the new `mergeCallSites()` helper, and the new pass's own iteration) uses `ClassUtils.getUserClass()`. New `RecordAccessorPassthroughDetector`, `scanForRecordAccessorPassthrough()`, `recordComponentAccessorNames()`. |
| `SpringBeanConfigRegistry.java` | New `unwrapProxyTarget()`, called on every bean right after retrieval from the bean factory, before it enters `applicationBeans` — unwraps an `Advised` (AOP) proxy to its real target instance. `scan()` passes `recordSourcesByType.keySet()` into `findOneHopCopies()`. |

**Tests:** `OneHopFieldCopyScannerTest.java` gained a CGLIB-proxy positive case (via `ProxyFactory`
+ `setProxyTargetClass(true)`) and a record-accessor positive case plus three negative fixtures
(transformation, branch, two-hop holder-field chain). `SpringBeanConfigRegistryTest.java` gained
`initialize_unwrapsAopProxiedBeanToItsRealTargetInstance` (confirms the registered instance is the
real target, `== plain`, not the proxy) and
`initialize_findsRecordAccessorDerivedField_throughRealConfigurationClassEnhancement` (boots a real
`AnnotationConfigApplicationContext` with an actual `@Configuration` class, the only way to
exercise real CGLIB enhancement rather than a plain-fixture stand-in — confirmed this test fails
with `AopUtils.getTargetClass()` alone and passes with `ClassUtils.getUserClass()`, proving it's a
genuine regression guard for the exact bug found).

**Verified live** against `../arex-spring-config-demo`, extended with two new fixtures matching
the real-app shapes exactly: `GetFabProxied`/`GetFabProxiedImpl` + `/api/fetch-group-proxied`
(Bug A — `LoggingAspect`, a trivial `@Aspect`, forces a genuine CGLIB proxy; confirmed via its own
log line firing on each call) and `GetTimeHours`/`GetTimeHoursImpl` + `/api/time-hours` (Bug B —
reuses the existing `KProperties` record). Recorded both at one local config, changed local config,
replayed the same case both directly (`arex-record-id` header) and via the actual `createPlan`
webhook (plan `6a8d92c3b0fb652e034683b4`) — all four endpoints (these two plus the pre-existing
`/api/fetch-group`, `/api/k-properties`) passed clean in the AREX UI, zero regression.

Also verified at larger scale under genuine concurrent, multi-environment replay: recorded 20
distinct simulated environments (one JVM restart per environment, one hit per endpoint each — first
hit per URI per JVM run always succeeds under AREX's own per-operation `RecordLimiter`, one
recording per 60s window), 80 cases total, 20 per endpoint, each environment's `demo.allow-fab`/
`demo.k.time-hour-list` distinct from every other's. Started a single replay-target instance with
yet another, 21st distinct ("wrong") local config, then fired all 80 recorded cases concurrently
(via direct `arex-record-id` header requests, backgrounded and awaited together) against that one
instance. Result: 80/80 passed — every case reflected its own originally-recorded environment's
config, not the replay target's local config and not any other environment's, across all four
endpoints.

## Investigation trail: how Bugs A and B were found (2026-08-25)

After Phase 0-2 were pushed (`174ca7ef`), the pushed agent was run against the user's actual
production app (not the demo) and traffic still replayed against local config for at least two
endpoints. The replay ran on a different machine, so the only evidence available was a
screenshot-photographed analysis from a different AI session with access to that log. Reviewed
here (11 photos) and checked claim-by-claim against this repo's actual source before acting on
any of it.

**Claim found to be false, disregarded:** the analysis asserted `SpringBeanConfigExtractor
.setFieldValue()` (cited at line 242, matching this file's actual line number for that method)
uses `sun.misc.Unsafe` to clear a field's `final` modifier before writing, and that this fails
under JDK 17's strong encapsulation. Checked directly: the real method at that line is `field.set
(bean, value)` wrapped in a try/catch for `IllegalAccessException` — no `Unsafe`, no modifier
manipulation, anywhere in this module (confirmed by grep). Same cited line number, entirely
different, non-existent code — the "Unsafe" failure mode was invented, not observed. Whatever
build produced the log the other AI analyzed, it either predates this code or the analysis
fabricated this detail; either way it's not an issue in the current implementation.

**Claim confirmed real — this is Bug A:** an `/issue-reasons`-style endpoint
(`GetIssueReasonsByFabUseCaseImpl`, backed by `DependencyInjection.fetchAllowGroup`, a `@Value`
field read once and passed unchanged into the use-case's constructor) is structurally identical
to the `fetchGroup`/`GetFabImpl` shape Phase 2 was built for. The user confirmed the *latest*
commit (including Phase 2, `61ef5772`) was what was actually tested, ruling out a stale jar as the
explanation — meaning the scanner itself had a real gap. Re-reading `findOneHopCopies()` with that
constraint found it: it grouped candidate targets by `Type.getInternalName(entry.getValue()
.getClass())` (the bean's *runtime* class) and read bytecode via `targetEntry.getValue().getClass()`
directly — both silently broken for any AOP-advised target bean (common in a real app,
`@Transactional`/`@Cacheable`/`@Async`/`@Aspect`-matched; never exercised by the demo app, which
had none of those). This is Bug A, described in full above (including the second, deeper layer —
instance-level field-storage sharing — found only once the class-level fix alone still didn't make
the equivalent demo fixture pass).

**Claim confirmed real and independent of Bug A — this is Bug B:** a `/kla-qtime-hours`-style
endpoint (`GetKlaQTimeHoursUseCaseImpl`, backed by `KlaProperties.qtimeHourList()` — a **record
accessor method call**, not a field read, on a `KlaProperties` parameter injected directly into a
`@Bean` factory method) is a genuinely new, unaddressed gap. Verified directly against
`OneHopFieldCopyScanner`'s own code and doc comment: `PassthroughDetector` only recognized
`GETFIELD` as an atomic source push (`this.field`); any other method call mid-argument-list,
including a record accessor, hit `aborted = true` in `visitMethodInsn` — the class's own Javadoc
listed "a record-accessor source" explicitly as something that "fails open." Described in full
above as Bug B.

## What's *not* changed

No existing instrumentation module, no existing shared runtime class (`MockUtils`, `ContextManager`,
`ArexContext`, `Serializer`, etc.), and no existing servlet/Apollo code was modified — this feature
is entirely additive, reusing the existing `MockCategoryType.CONFIG_FILE` category and existing
`RequestHandler`/`ModuleInstrumentation` extension points rather than changing their behavior for
other categories.
