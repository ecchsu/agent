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

## Investigation: real-app testing after Phase 3, round 2 (2026-08-26, not yet fixed)

Ran the pushed Phase 3 agent (`cc8324f9`) against the real app again; several issues found. As
before, the user's own log access is on a different machine, so evidence came as 14 photos of
another AI's analysis document, reviewed here and checked claim-by-claim against this repo's
actual source rather than trusted at face value.

**Disregarded — not applicable to this codebase:** the document's root-cause section claims
`SpringBeanConfigExtractor.setFieldValue()` attempts two fallback mechanisms
(`Unsafe.objectFieldOffset()`, then `sun.reflect.ReflectionFactory.newFieldAccessor()`) to force
final-field writes on record components, both blocked on JDK 17, citing specific method names
(`setFinalField`, `setFinalFieldUnsafe`, `setFinalFieldReflectionFactory`) and line numbers. None
of this exists anywhere in this codebase — confirmed by grep, same as the previous round's
"Unsafe" claim. The user confirmed separately that this fallback code was likely added downstream,
after this repo's commits, in their own deployment — not something to reconcile against here.

**Correction on re-review — one warning in that same log excerpt is real, and points to a genuine
gap this repo does have.** The three-warning sequence for `KlaProperties.qtimeHourList` has one
line *before* the two disregarded fallback attempts:

```
WARN io.arex.inst.runtime.log.LogManager - [[title=arex.spring.bean.config.field.write]]
java.lang.IllegalAccessException: Can not set final java.lang.String field
  com.tsmc.lotaction.apps.confirm_lot.framework.di.KlaProperties.qtimeHourList
    at java.base/java.lang.reflect.Field.set(Field.java:799)
    at io.arex.inst.config.spring.SpringBeanConfigExtractor.setFieldValue(SpringBeanConfigExtractor.java:246)
    at io.arex.inst.config.spring.SpringBeanConfigExtractor.applyReplayOverrides(SpringBeanConfigExtractor.java:112)
```

Log tag `spring.bean.config.field.write`, method `setFieldValue`, exception `IllegalAccessException`
— an exact match to this repo's actual code (`LogManager.warn("spring.bean.config.field.write", e)`
inside `setFieldValue()`, called from `applyReplayOverrides()`). Only the *following* two warnings
(`...write.final.approach1`/`approach2`, via a `setFinalField` method) are from the disregarded
downstream code — this first one isn't, and it confirms a real, currently-happening gap: the
record bean's *own* registered fields are always attempted and always fail.

`SpringBeanConfigRegistry.mergeRecordHolderFields()` returns early for the record's own class:
```java
if (recordSourcesByType.containsKey(beanClass)) {
    return fields; // never registers the record's own fields as holders
}
```
`eligibleFields()` still registers the record's own 13 components in `BEAN_FIELDS` (the class is
`@ConfigurationProperties`-annotated), but they're never added to `RECORD_HOLDER_FIELDS` — that map
only ever holds *other* beans' reference fields pointing at the record. So every replay,
`applyReplayOverrides()` looks up `recordHolderFields().get(componentField)`, gets `null`, falls
through to the plain scalar path, and `field.set()` throws `IllegalAccessException` — for all 13
components, every request. That's the literal, confirmed source of "156 warnings" (12 non-primitive
fields × ~13 requests) — a real number, just not evidence of the fabricated root cause.

This was a known, explicit trade-off from Phase 1, not a new bug: *"a record's own components
still get registered under its own bean name too (harmless... leaving this path alone costs
nothing)"* (this file, Phase 1 section). The real-world log evidence shows the "costs nothing" part
was wrong — it's 13 warnings per replayed request in this app, not free. Whether it's also
*functionally* harmless depends on whether anything reads the record bean's own registered instance
directly, rather than through a holder field (handled correctly via reconstruct-and-swap, modulo
the non-idempotent-compact-constructor gap below) or a one-hop derivation (handled separately) —
unconfirmed from the photos alone, but the noise is worth fixing regardless.

**Proposed fix** (not yet implemented): when a bean's class is itself a known record source, don't
register its own component fields in `BEAN_FIELDS` at all (skip `eligibleFields()`'s contribution
for it, or filter fields whose declaring class matches a record source before merging) — there is
no valid in-place path for these fields; the only real path is holder-swap, a separate mechanism
that already runs independently of whether the record's own bean entry has any fields.

**Already handled, not a new finding:** the document's P1 (`SwaggerUiConfigProperties.groupsOrder`,
a `Direction` enum receiving a recorded `String`) and P2 (a `HashSet<String>` failing Jackson
polymorphic-type resolution) both throw inside the per-field try/catch Phase 0 already added — so
they fall back to the live value for *that one field* rather than crashing the request. That's the
original bug this whole effort started from; Phase 0 contains the blast radius but was never meant
to make either field correctly replay, and doesn't.

**The document contradicts itself — the narrower, evidence-backed claim wins:** an early section
claims *all 13* `KlaProperties` fields fail on every replay (156 warnings). A later section, backed
by actual recorded MongoDB data cross-referenced across two separate recordings, shows
`kla-qtime-hours` replays correctly — `getKlaQTimeHoursUseCase.klaQTimeHourListConfig` is present
and correctly applied, consistent with Phase 1-3 actually working for that endpoint. The narrower
claim is the reliable one; the broad "all fields fail" framing is disproven by the document's own
evidence.

### Confirmed, reproduced live: null-value recording gap

`confirmLotOrderService.reasonAllowGroupForFab` is never recorded in any of the checked recordings
because it's `null` at record time. Verified directly in this repo's actual source:

- `SpringBeanConfigExtractor.captureAndRecord()` (line ~193): `if (value == null) { continue; }` —
  a null field gets no recorded entry at all.
- `SpringBeanConfigExtractor.applyReplayOverrides()` (line ~90): `if (raw == null) { continue; }` —
  "no recorded entry" and "recorded as null" are indistinguishable; both leave the field untouched,
  falling back to whatever the *replay host's own local config* produces.

Reproduced live in `arex-spring-config-demo`: added `OptionalGroupController`
(`@Value("${demo.optional-group:#{null}}")`, genuinely null when unset, matching the real field's
shape rather than an empty string). Recorded a case with the field null, restarted with
`--demo.optional-group=LEAKED-LOCAL-VALUE`, replayed the same case: response returned
`"LEAKED-LOCAL-VALUE"` instead of the actually-recorded `null`.

**Proposed fix** (not yet implemented): record a sentinel marker (e.g. `"__AREX_NULL__"`) instead
of skipping a null value in `captureAndRecord()`; in `applyReplayOverrides()`, treat that marker as
an explicit instruction to set the field to `null`, while a genuinely-absent map entry still fails
open exactly as today. The same skip exists in `RecordConfigSource.serializeComponents()`/
`reconstruct()` for individual record components, though `KlaProperties`'s own compact constructor
already defaults its list components away from null, so that path may not be live for this app.

### Confirmed via investigation, not yet reproduced live: enum round-trip gap

Traced P1 (the `Direction` enum) to its real root cause, independent of the disregarded
Unsafe/ReflectionFactory narrative: the shared `JacksonSerializerWithType`
(`arex-instrumentation-foundation`) configures Jackson with
`activateDefaultTyping(validator, DefaultTyping.NON_FINAL)`. A plain Java enum (no per-constant
class bodies) compiles to a `final` class, so `NON_FINAL` typing never wraps it with type info —
it serializes as a bare name string. Since `Serializer.deserializeWithType()` always deserializes
into `Object.class` with no type hint, that string round-trips back as a `java.lang.String`, not
the enum — confirmed by compiling and running the actual serializer against a test enum
(`ENUM BACK TYPE: class java.lang.String`). This is a shared, agent-wide serializer limitation
(no enum-aware handling anywhere in that class), not specific to Spring config.

**Proposed fix** (not yet implemented): fix locally in `SpringBeanConfigExtractor`, not in the
shared serializer — it already has the field's real declared type via reflection at both record
and replay time. For an enum-typed field specifically: serialize with plain `Serializer.serialize
(value)` (no wrapper needed) and deserialize with `Serializer.deserialize(raw, field.getType())` —
an explicitly-typed overload already used elsewhere in this same file (record component overrides)
— bypassing the generic serializer's blind-`Object.class` limitation for just this field shape.

**Unconfirmed, not reproduced:** P2's `HashSet<String>` `InvalidTypeIdException` claim. Ran the
identical shape (`HashSet` of `"A","B","C","D"`) through the actual serializer: it round-tripped
correctly with a proper type wrapper. No `HashSet`-vs-`ArrayList`-specific code path exists
anywhere in the serializer. Given this document already contained fabricated and overstated claims
elsewhere, this one is set aside rather than chased without something concrete to reproduce.

### Confirmed via code reading, not yet reproduced live: non-idempotent compact constructor breaks reconstruct-and-swap

Re-reading the real `KlaProperties` app code (not just its truncated summary) surfaced a case
Phase 1's design never accounted for. Its compact constructor does more than null-coalescing
defaults for most components — for one component specifically:

```java
scanOpeQTimeTypeOrder = scanOpeQTimeTypeOrder.stream()
        .filter(item -> item != null && item.split(":").length >= 2)
        .sorted(Comparator.comparingInt(KlaProperties::extractSortKey))
        .map(item -> item.split(":")[0])
        .toList();
```

This is a **one-way, non-idempotent transform**: it expects raw `"key:sortvalue"`-shaped input and
maps it down to just `key`, discarding the sort value entirely. `RecordConfigSource
.serializeComponents()` captures each component via its *accessor* — for this component, the
already-transformed, colon-free output. `RecordConfigSource.reconstruct()` then passes that
recorded value back in as a constructor argument during replay. Java gives no way to invoke a
record's canonical constructor without also running its compact constructor — confirmed
unavoidable, not just inconvenient: `Unsafe.objectFieldOffset()` is unconditionally blocked for
*any* record field access on JDK 17+ (per the confirmed root cause of the original record bug),
so there is no field-poking route around the constructor either. The transform runs a second time
on data that no longer has colons: every item fails `item.split(":").length >= 2`, the filter
drops everything, and `scanOpeQTimeTypeOrder` reconstructs as an **empty list**.

This is a real gap in the reconstruct-and-swap design itself, not a missing null-check: it
implicitly assumes a record's compact constructor is idempotent (safe to re-run on its own
accessor output), which holds for simple defaulting logic (this component's siblings, and the
demo app's own `KProperties` fixture) but not for a one-way filter/sort/map like this one.

**No clean fix identified.** The only architecturally-correct approach found so far is to stop
capturing accessor *output* and instead capture the raw, pre-binding property values, then
reconstruct by re-running Spring Boot's own constructor-binding
(`org.springframework.boot.context.properties.bind.Binder`) against those raw values, so the
compact constructor runs exactly once — same as at original startup. That's a substantially larger
change than anything built so far for this feature; not started, pending direction on how deep to
take it. Not yet reproduced live (would need a demo fixture with the same filter/sort/map shape).

## Phase 4 — null marker, enum round-trip, dead record-field registration

Fixes Bugs 1-3 from the round-2 investigation above. Bug 4 (non-idempotent compact constructors)
is left out of scope — no clean fix identified yet, needs its own conversation.

**Reproduced live before fixing**, matching this feature's established discipline:

- **Bug 2 (enum)**: added `SortDirectionController` (`@Value("${demo.sort-direction}") private
  SortDirection sortDirection;`, `SortDirection` a plain two-constant enum, matching
  `BusinessHoursController`'s direct-field-read style). Recorded `ASC`, restarted with local
  `DESC`, replayed: response showed `"DESC"` (the local value), not the recorded `"ASC"`.
- **Bug 3 (dead record-field registration)**: not observable via HTTP response, since the holder
  swap already masks it from `/api/k-properties`'s output — that's the whole problem, it's dead
  code, not a response-visible symptom. Confirmed instead with a characterization test
  (`SpringBeanConfigRegistryTest`): before the fix, a record source bean's own name *was* present
  in `beanInstances()`/`beanFields()` after `scan()` — later inverted into the fix's regression
  test (below).

**Fix 1 — null marker for genuinely-null field values.** File: `SpringBeanConfigExtractor.java`.
Scoped to the plain (non-record-holder) field path — a record holder field being null itself
(not one of its components) is a separate, unevidenced case, left alone. New constant
`NULL_MARKER = "__AREX_NULL__"`. `captureAndRecord()`: when a non-holder field's live value is
`null`, writes `NULL_MARKER` instead of skipping the entry. `applyReplayOverrides()`: when the
recorded value equals `NULL_MARKER`, sets the field to `null` explicitly instead of skipping. A
genuinely-absent map entry still fails open exactly as before.

**Fix 2 — enum-aware serialize/deserialize.** Same file, same two methods, using the field's own
reflected type (already available at both call sites) instead of the shared serializer's blind
`Object.class` target. `captureAndRecord()`: for a non-holder, enum-typed field
(`field.getType().isEnum()`), uses `Serializer.serialize(value)` instead of
`Serializer.serializeWithType(value)`. `applyReplayOverrides()`: for the same case, uses
`Serializer.deserialize(raw, field.getType())` (an already-used typed overload,
`Serializer.java:160`) instead of `Serializer.deserializeWithType(raw)`. Both methods already
branched on `isRecordHolder`; this is one more `else if` in the same place, not a restructure.

**Fix 3 — stop registering a record source's own fields.** File: `SpringBeanConfigRegistry.java`.
`scan()`'s per-bean loop now skips a bean entirely when its class is itself a known record source
(`recordSourcesByType.containsKey(beanClass)`) — there is no in-place update path for these
fields; only holder-swap applies, and that's a separate, already-independent mechanism.
`mergeRecordHolderFields()`'s own early-return for this same case is now dead code for this call
site but was left as-is (harmless, still used for every other bean). `OneHopFieldCopyScanner` is
unaffected — it receives the full `applicationBeans` map directly from `scan()`, not the filtered
`BEAN_INSTANCES`.

| File | Change |
|---|---|
| `SpringBeanConfigExtractor.java` | New `NULL_MARKER` constant; `captureAndRecord()`/`applyReplayOverrides()` each gain a null-marker branch and an enum branch alongside the existing record-holder branch. |
| `SpringBeanConfigRegistry.java` | `scan()`'s per-bean loop skips beans whose class is a known record source. |

**Tests:** new `SpringBeanConfigExtractorTest.java` (no such file existed before) — null-field and
enum-field round-trips (record + replay + restore), plus regression coverage for existing
non-null/non-enum fields and the fail-open no-recorded-entry case. `ContextManager`/`MockUtils`/
`Serializer` mocked with simple, naive stand-ins, same convention as `RecordConfigSourceTest` (not
the real Jackson wiring — these tests are about this class's own branching). Confirmed genuine
(not vacuous): temporarily changed `NULL_MARKER`'s value and reran — the two null-marker tests
failed exactly as expected, then reverted. `SpringBeanConfigRegistryTest.java`'s characterization
test was inverted into `initialize_doesNotRegisterRecordSourceOwnFields`.

**Verified live** against `arex-spring-config-demo`: recorded all of `/api/optional-group`,
`/api/sort-direction`, `/api/k-properties`, `/api/fetch-group`, `/api/fetch-group-proxied`,
`/api/time-hours` at one local config, restarted with every one of those values changed locally
(including a leaked-value string for the null field and a flipped enum constant), replayed all
six — both directly (`arex-record-id` header) and via the actual `createPlan` webhook (plan
`6a8ee2bcb0fb652e03468719`, zero `failCases`). All three fixes confirmed working
(`/api/optional-group` correctly replayed `null` instead of the leaked local string;
`/api/sort-direction` correctly replayed `"ASC"` instead of local `"DESC"`), zero regression to
the four pre-existing endpoints.

Also verified at scale under genuine concurrent, multi-environment replay, same methodology as
Phase 3: recorded 15 distinct environments across all six endpoints (90 cases total, 15 per
endpoint), alternating `/api/optional-group` between null and a distinct non-null value per
environment and `/api/sort-direction` between `ASC`/`DESC`, so both branches of both new fixes were
exercised under real concurrency, not just their happy path. Fired all 90 cases concurrently against
a single replay-target instance running a 16th, entirely distinct local config. Result: 90/90
passed, zero cross-contamination between environments, zero leakage of the replay target's local
config into any response.

## Investigation: nested-constructor argument breaks one-hop discovery (2027-08-27, not yet fixed)

Ran the pushed Phase 4 agent (`d930e275`) against the real app again; `/api/confirmlot/v2/templates/issue-reasons`
(backed by `GetIssueReasonsByFabUseCaseImpl.fetchAllowGroup`, the same one-hop passthrough shape
Phase 2 was built for) still returned unfiltered results. This round's evidence came in three
stages: an initial analysis, an attempted fix based on it, and a second analysis after that fix
didn't fully resolve the symptom — all from another AI working against the user's real app (whose
log the user doesn't have direct access to), reviewed here across 17 photos and checked
claim-by-claim against this repo's actual source, same discipline as prior rounds.

**Stage 1 claim, determined incorrect:** that `findOneHopCopies()`'s merge step
(`SpringBeanConfigRegistry.java:127-138`) stores `null` via
`BEAN_INSTANCES.put(beanName, applicationBeans.get(beanName))`, because
`GetIssueReasonsByFabUseCaseImpl` (no `@Value`/`@ConfigurationProperties` of its own) was supposedly
never added to `applicationBeans` in the first collection pass. Traced this directly:
`groupBeansByRealInternalName()` builds its lookup by iterating `applicationBeans.entrySet()`
itself, so every bean name that can possibly reach the merge step is *already* a key in
`applicationBeans` — this lookup cannot return null for a key derived from the same map. The
attempted fix built on this theory (threading the bean instance directly through
`OneHopFieldCopyScanner`'s return type instead of doing a separate `applicationBeans.get()` lookup)
was harmless but didn't address the real gap, which is why the symptom persisted.

**Stage 3 claim, also determined incorrect (self-contradicted by the same investigation's own
evidence):** that `DependencyInjection` (a `@Configuration` class) might be entirely absent from
`applicationBeans`/the registry because `beanFactory.containsSingleton("dependencyInjection")`
could return `false` for a CGLIB-enhanced `@Configuration` proxy. This can't be right:
`dependencyInjection.fetchAllowGroup` (the *source* field, a different field on the same bean) was
independently confirmed recorded successfully in this same investigation's own evidence — which is
only possible if `DependencyInjection` is already in the registry.

**Confirmed real, verified independently against this repo's actual `OneHopFieldCopyScanner`
source, and corroborated by the investigation's own final analysis:** the real factory method is
```java
return new GetIssueReasonsByFabUseCaseImpl(
    new GetIssueReasonsByFabUseCaseImpl.Gateways(gateway), // nested constructor call
    fetchAllowGroup                                         // simple field passthrough
);
```
`PassthroughDetector.visitTypeInsn()` aborts the *entire* match the moment it sees a nested `NEW`
while already collecting an outer call's arguments (`aborted = true`, with this class's own
existing comment: "a nested 'new' while already collecting an outer call's args") — regardless of
whether some *other* argument in the same call is a perfectly clean, simple passthrough.
`fetchAllowGroup` never gets recognized purely because its sibling argument happens to be a wrapped
object construction. Confirmed this is unavoidable under the current design: the state machine
tracks exactly one pending `new` at a time (`pendingNewType`), with no way to represent "currently
inside a nested constructor call that is itself part of an outer one." This is a real, narrower
version of the same "anything wider than this exact shape fails open" limitation already documented
for this class - just one specific "wider" shape not previously identified.

A separate, unconfirmed variant surfaced in this round's first analysis stage: `fetchAllowGroup`
computed via a runtime service call (`fetchAllowGroupService.getFetchAllowGroup(fab, useCase)`)
wrapped in try/catch, rather than a field. Unlike the nested-constructor finding, this one only
appears once, isn't cross-referenced against other evidence in the same investigation, and directly
contradicts the field-based shape shown consistently everywhere else in the same three-stage
investigation - set aside as unconfirmed, likely a conflation, rather than acted on.

**Reproduced live** in `arex-spring-config-demo`: added `GetIssueReasonsImpl` (with a nested
`public static class GatewayWrapper`, matching the real app's `Gateways` shape exactly) +
`DependencyInjection.getIssueReasons(Gateway)` (`return new GetIssueReasonsImpl(new
GetIssueReasonsImpl.GatewayWrapper(gateway), fetchGroup);`) + `/api/issue-reasons`. Recorded at
`fetchGroup=team-a`, restarted with local `NESTEDBUG-LOCAL-team`, replayed the recorded case:
response returned `"NESTEDBUG-LOCAL-team"` (the local value) instead of the recorded `"team-a"` -
confirming the diagnosis live, independent of any real-app log.

**A broader fix was proposed and considered, then rejected:** replacing (or supplementing) the
one-hop scanner with a `BeanPostProcessor` that captures every non-static field on every singleton
bean after Spring finishes constructing it, regardless of annotation or how the field's value was
computed - sidestepping bytecode analysis (and this specific limitation) entirely. Rejected: this
is a fundamentally wider net than this feature's deliberate "config only" scope. Phase 1's own
original design rationale excludes non-config framework state for exactly this reason (payload
bloat, reflection failures on complex runtime objects) - capturing *every* field on *every* bean
adds the same risk to *application* beans too, plus a new one specific to replay: overwriting a
bean's live mutable runtime state (a cache, a connection, a counter) with a stale recorded snapshot
at replay time has nothing to do with config and could break application behavior in ways this
feature has never previously risked. A narrower fix - teaching the existing scanner to treat one
level of self-contained nested construction as a single atomic argument, rather than aborting
outright - stays within the class's existing straight-line-only philosophy without expanding scope.

## Phase 5 - handle a self-contained nested constructor call as one atomic argument

Fixes the nested-constructor gap above. `PassthroughDetector`'s per-call tracking state (previously
a flat set of fields: `pendingNewType`, `collectingArgs`, `argCount`, `sawSource`,
`sourceArgPosition`, `aborted`, `pendingObjectRefSlot`) became a `Frame` on a small `Deque<Frame>`
stack, one per nesting level. Encountering a nested `NEW` while already collecting an outer call's
arguments no longer aborts unconditionally - a new frame is pushed for it. When a frame's own
`INVOKESPECIAL <init>` closes it:

- If it's the outermost call (no parent frame left), this is a completed candidate match, exactly
  as before.
- If the frame is *not* aborted and a parent frame exists, the whole nested construction collapses
  into one atomic argument for the parent - exactly like a bare `ALOAD`/`LDC` would - and a source
  read found inside the nested call (unevidenced, but free to support given the design) propagates
  to the parent too.
- If the frame *is* aborted (another `NEW` inside it, a branch, a non-`<init>` method call), the
  parent frame is poisoned the same way any other non-atomic argument would poison it - the whole
  outer match still fails open, no wider than what's already evidenced.

`DirectAssignmentDetector` (the target-constructor side) is unaffected - it already only cares about
the tracked parameter slot, not how the caller assembled it.

| File | Change |
|---|---|
| `OneHopFieldCopyScanner.java` | `PassthroughDetector` restructured around a `Frame`/`Deque<Frame>` stack instead of flat per-call fields; every `visit*` method now operates on `frames.peek()`. |

**Tests:** `OneHopFieldCopyScannerTest.java` gained a positive case (a sibling argument is itself a
`new Wrapper(gateway)` construction, matching the real app's shape exactly) and a negative case (the
nested constructor's own argument is computed via a method call, not atomic - confirms poisoning
propagates to the outer frame rather than only failing the nested slot). Confirmed genuine: reverted
to abort-on-nested-`NEW` and reran - exactly the new positive test failed, nothing else regressed.
All 32 existing tests (Phase 0-4) still pass unchanged.

**Verified live** against `arex-spring-config-demo`: added `GetIssueReasonsImpl` (with a nested
`GatewayWrapper`, matching the real app's `Gateways` shape) + `DependencyInjection.getIssueReasons()`
+ `/api/issue-reasons`. Recorded all seven endpoints (this one plus the six from Phase 3/4) at one
local config, restarted with every value changed locally, replayed all seven - both directly
(`arex-record-id` header) and via the actual `createPlan` webhook (plan
`6a90521bb0fb652e034687de`, zero `failCases`). `/api/issue-reasons` now correctly replays the
recorded value instead of the leaked local one; zero regression to the six pre-existing endpoints.

Also verified at scale under genuine concurrent, multi-environment replay, same methodology as
Phase 3/4: recorded 15 distinct environments across all seven endpoints (105 cases total, 15 per
endpoint), alternating `/api/optional-group` between null and a distinct non-null value per
environment and `/api/sort-direction` between `ASC`/`DESC`. Fired all 105 cases concurrently against
a single replay-target instance running a 16th, entirely distinct local config. Result: 105/105
passed, zero cross-contamination between environments, zero leakage of the replay target's local
config into any response - `/api/issue-reasons` (the new Phase 5 fixture) held up under real
concurrency exactly like the six pre-existing endpoints.

## What's *not* changed

No existing instrumentation module, no existing shared runtime class (`MockUtils`, `ContextManager`,
`ArexContext`, `Serializer`, etc.), and no existing servlet/Apollo code was modified — this feature
is entirely additive, reusing the existing `MockCategoryType.CONFIG_FILE` category and existing
`RequestHandler`/`ModuleInstrumentation` extension points rather than changing their behavior for
other categories.
