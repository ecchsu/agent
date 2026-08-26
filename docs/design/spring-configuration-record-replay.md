# Proposal: Spring Configuration Record & Replay

Status: draft for review
Author: assistant (drafted with ecc.hsu@gmail.com)
Date: 2026-08-23

## 1. Goal

Today the AREX agent records and replays *traffic* (servlet/dubbo entry points and their
outbound dependencies: database, HTTP client, Redis, MQ, Apollo config, ...). It does **not**
record or replay Spring's own configuration (`application.yml`/`.properties`, active profiles,
env vars, system properties — anything reachable through `org.springframework.core.env.Environment`).

We want to add that capability, modeled on the existing Apollo config record/replay feature,
with two hard requirements from the user that Apollo's implementation does **not** satisfy and
that this design treats as first-class constraints:

1. **Spring configuration must be recorded and replayed per request**, not as a shared,
   time-windowed, JVM-global blob. (Note on §6.3's later revision: this constraint is about
   *replay* — each replayed request must resolve config independently, with no shared mutable
   state on the replaying agent. *Recording* turned out to be a separate question, and §6.3 records
   once per environment-scoped generation rather than once per request, once the user confirmed
   Spring configuration doesn't actually vary across traffic within one environment — replay
   remains fully per-request throughout.)
2. **Topology**: PROD-1, PROD-2, PROD-3, ... each run their own independent deployment — a full
   app + agent + a single, distinct Spring configuration per environment — and each records its
   own traffic independently. Replay, however, happens against **one single TEST deployment**
   (one app + one agent, with its own, generally different, local `application.yml`), which
   receives and replays recorded traffic **originating from all of those production
   environments**, potentially interleaved/concurrent. So for any given replayed request, the
   agent must resolve Spring config reads using *that request's originating production
   environment's* recorded values — never the TEST host's own local config, and never another
   request's originating environment's values, even when two requests recorded in different
   production environments are being replayed at the same time on the one shared TEST agent.
3. **"Spring configuration" explicitly includes `@Value`-injected fields and
   `@ConfigurationProperties`-bound beans**, not just dynamic `Environment.getProperty(...)` calls.
   During replay, the agent must **overwrite the startup-bound value** these fields already hold
   (they were bound once, from the TEST host's own `application.yml`, when the Spring context
   started) with the value recorded from the request's originating production environment — and
   must do this **freshly for every replayed request**, with the same no-cross-talk guarantee as
   (2): a `@Value` field must never be overwritten with a value recorded for a *different*,
   concurrently-replaying request.
4. **The whole feature must be opt-in and default off**, behind one config flag
   (`arex.spring.config`), with every new code path gated behind it strongly enough that an
   application running without the flag set is byte-for-byte unaffected — not merely "behaves the
   same," but never has its (or Spring's) classes modified by this feature's instrumentation at
   all. See §5.

Section 3 explains in detail why Apollo's existing mechanism structurally cannot give us those
two properties, and why we should not copy it verbatim.

## 2. How the agent works today (relevant parts)

### 2.1 Module layout

Instrumentation is organized as one Maven module per technology under `arex-instrumentation/`,
each contributing a `ModuleInstrumentation` (ByteBuddy type/method advices) discovered via
`ServiceLoader`/`@AutoService`. Config-shaped integrations live in their own subtree:

```
arex-instrumentation/
  config/
    arex-apollo/        <- existing Apollo config record/replay module
  servlet/arex-httpservlet/
  dubbo/...
  ...
```

`arex-instrumentation/pom.xml:58` registers `config/arex-apollo` as a submodule, and
`arex-agent/pom.xml` pulls it into the shaded agent jar. A new module would be a sibling:
`arex-instrumentation/config/arex-spring-config/`.

### 2.2 Per-request context: `ArexContext` / `ContextManager`

Every other dependency category (database, HTTP client, Redis, dubbo consumer, ...) is scoped
per request through this mechanism, which is the piece Apollo's config feature deliberately
bypasses:

- `TraceContextManager` (`arex-agent-bootstrap`) is a bootstrap-classloader `ThreadLocal<String>`
  holding the current `traceId`. It's propagated across instrumented thread pools/executors by
  `arex-instrumentation/internal/arex-executors`.
- `ContextManager.currentContext()` (`arex-instrumentation-api/.../context/ContextManager.java`)
  looks up an `ArexContext` keyed by that `traceId` in a map (`RECORD_MAP`). One `ArexContext` is
  created per inbound request at the servlet/dubbo entry point and removed when the request ends
  (`ContextManager.remove()`), driven from `ServletAdviceHelper.onServiceEnter/onServiceExit`.
- `ArexContext` (`arex-instrumentation-api/.../context/ArexContext.java`) carries `caseId`
  (record id) and `replayId`, a per-request `attachments` map (`setAttachment`/`getAttachment`),
  a `calculateSequence()` counter for ordering repeated identical calls, and a
  `cachedReplayResultMap`.
- `MockUtils.create(categoryType, operationName)` (`arex-instrumentation-api/.../util/MockUtils.java:79-95`)
  is the generic mocker factory used by every dependency category: it reads
  `ContextManager.currentContext()` and stamps `recordId = context.getCaseId()`,
  `replayId = context.getReplayId()` **automatically**. `MockUtils.recordMocker(...)` sends it to
  the storage backend; `MockUtils.replayBody(...)` queries the backend for the previously-recorded
  answer for that exact `(categoryType, operationName, recordId/replayId, sequence)` key.

This is the mechanism that already gives database/HTTP/Redis mocks correct per-request,
per-environment isolation: a replayed case's `recordId` is the id of *that specific original
recording*, so the storage query can only ever return data captured during that same recording —
never another case's, never another environment's, regardless of how many replays run
concurrently on the shared agent/JVM.

### 2.3 How Apollo config record/replay works today

Files: `arex-instrumentation/config/arex-apollo/src/main/java/io/arex/inst/config/apollo/*`.

- `ApolloModuleInstrumentation` registers three ByteBuddy hooks:
  - `DefaultConfig#updateAndCalcConfigChanges` → marks that Apollo config changed
    (`ApolloConfigExtractor.onConfigUpdate()`).
  - `RemoteConfigRepository#loadApolloConfig()` → the actual replay substitution point
    (`ApolloRemoteConfigRepositoryInstrumentation`, `@Advice.OnMethodEnter(skipOn = OnNonDefaultValue.class)`
    calling `ApolloConfigHelper.getReplayConfig(...)`).
  - `LocalFileConfigRepository#persistLocalCacheFile` → suppressed during replay so fake replayed
    config never pollutes Apollo's local disk cache.
- `ApolloServletV3RequestHandler`/`V5`/`Dubbo` implement `RequestHandler` and hook the same
  generic lifecycle used by every other category (`preHandle` / `handleAfterCreateContext` /
  `postHandle`), but instead of using `ContextManager`/`MockUtils.create()` the normal way, they:
  - **Record** (`postHandle`, i.e. after business logic ran, on every request while
    `ApolloConfigExtractor.needRecord()`): mint a brand-new `RECORD_CONFIG_BATCH_NO = UUID.randomUUID()`
    "config batch id", reflectively walk Apollo's JVM-singleton `ConfigService` to snapshot every
    namespace's `Properties`, and record one `ConfigFile` mocker per namespace **with `recordId`
    explicitly overridden to that batch UUID** (`ApolloConfigExtractor.record()`,
    `ApolloConfigExtractor.java:30-37`) — not the request's own `caseId`. The batch id is then
    stamped as a `configBatchNo` attribute onto *this request's* entry-point mocker
    (`ArexConstants.CONFIG_VERSION`) so the recorded case remembers which config generation was
    live when it was captured.
  - **Replay** (`handleAfterCreateContext`, i.e. before business logic runs): read the
    `arex-record-id` and `arex_replay_prepare_dependency` (config batch no) headers, and flip a
    set of **`static` (JVM-global, not `ThreadLocal`) fields** in `ApolloConfigExtractor`:
    `startReplay`, `replayStartTime`, `currentReplayConfigBatchNo`. `duringReplay()` is true for
    up to **one minute** after the last request that referenced a given batch
    (`ONE_MINUTE_EXPIRED_NANOS_TIME`). While true, `loadApolloConfig()` is short-circuited and
    answered from a `ConfigFile` mocker looked up **by `recordId = currentReplayConfigBatchNo`**
    (again bypassing the request's own `caseId`/`replayId`).

This works for Apollo because Apollo's `Config` objects are process-wide singletons maintained by
a background long-polling client, completely decoupled from any HTTP/dubbo request thread — there
is no natural "current request" at the point where Apollo refreshes a namespace, so AREX invented
a coarse, time-boxed, JVM-global substitute for request scoping (a "config generation" shared by
whichever requests happen to be recorded/replayed within the same ~1 minute window).

## 3. Why we should not reuse Apollo's mechanism for Spring configuration

The user's two requirements map directly onto the two properties Apollo's design explicitly
trades away:

| Property | Apollo's mechanism | What we need for Spring config |
|---|---|---|
| Scoping key | Global `currentReplayConfigBatchNo` (`static`, one value per JVM) | Per-request `caseId`/`replayId` from `ArexContext` |
| Isolation window | Time-boxed (~60s sliding TTL), not case-boxed | Bounded exactly by the request's lifetime |
| Concurrent replay of two different origin cases/environments | **Not safe** — both would contend for the same static `currentReplayConfigBatchNo`; whichever set it last wins for everyone inside the 1-minute window | Required to be safe: one TEST agent replays cases recorded across PROD-1/2/3, potentially interleaved, and must not let one case's config bleed into another's |
| Fallback when not "in a replay window" | Falls through to whatever real config the local Apollo client has (i.e. local/live config) | Falling back to TEST's own local `application.yml` is exactly the leak we must prevent whenever a key *was* successfully recorded — TEST's config is a real, different, "wrong" config for replay purposes, not a neutral default |

Apollo *had* to accept coarse, global scoping because Apollo `Config` objects live outside any
request's call stack (updated by a background poller). **Spring `Environment`/`PropertySource`
reads do not have that constraint** — `Environment.getProperty(...)` is called synchronously, on
the request's own thread, from inside the code path that is already running under an `ArexContext`.
That means we can — and should — scope Spring config record/replay exactly the way database/HTTP
mocks already are: through `ContextManager.currentContext()` and `MockUtils`, with no new global
mutable state and no time windows at all. This is the central design decision of this proposal.

## 4. Scope: two different Spring config surfaces, two different mechanisms

Spring property consumption falls into two categories, and they differ in *when* Spring itself
reads the value — which is exactly what determines what "record per request" / "replay per
request" has to mean for each:

1. **Dynamic reads made during request processing** — code that calls
   `Environment.getProperty(key)` / `getProperty(key, Class)` / `getRequiredProperty(...)` /
   `resolvePlaceholders(...)` while handling a request (feature flags, dynamic thresholds, a
   `@RefreshScope`/prototype bean that re-resolves values, `Environment` injected directly into a
   service). Spring itself re-evaluates these on every call, on the request's own thread, so
   "record/replay per request" is a direct, literal match for how the value is actually produced.
   **Design in §6 ("Part A").**
2. **Values bound once at application-context startup** into bean state via `@Value` or
   `@ConfigurationProperties`. Spring resolves these exactly once — during context refresh, before
   any request exists — and from then on the bean simply holds them as ordinary Java state; nothing
   in Spring re-reads `application.yml` for these on a per-request basis. Making replay
   "per request" here does **not** mean intercepting a per-request read (there isn't one to
   intercept); it means: whichever code later reads `this.someField` on that bean during a
   replayed request's execution must see the *replayed* value instead of the value TEST's own
   startup bound into it, without that override being visible to a different, concurrently
   replaying request. That's a materially different problem (per-request-scoped mutation of
   otherwise-shared singleton state, not per-request interception of a read call), and needs its
   own mechanism — a custom Spring `Scope` plus per-request bean instances, detailed in §7
   ("Part B"), rather than the advice-on-a-hot-method approach that works for (1).

Both are genuinely per-request in the sense the user needs — the mechanisms just have to differ
because Spring resolves them at different times.

## 5. Feature flag: opt-in, off by default, zero footprint when off

This entire feature — both Part A and Part B — must sit behind a single dedicated config flag,
**`arex.spring.config`** (boolean; matches the existing `arex.enable.debug`-style naming used for
other opt-in switches in `ConfigConstants`, e.g. `ConfigConstants.ENABLE_DEBUG`). When the flag is
`false` or simply not set, the agent must do nothing related to Spring configuration at all, and an
application running in that default mode must be provably unaffected — not just "behaviorally
unaffected" but untouched at the bytecode level. Two properties are non-negotiable:

1. **Default is off.** No existing deployment changes behavior just from upgrading the agent jar.
2. **All new code paths for this feature are gated by the flag**, and gated at the point that
   gives the strongest possible guarantee for each part.

### 5.1 How the gate actually works — no changes to shared/core agent code

The agent's existing module installer (`arex-agent-core/.../InstrumentationInstaller.java:119-141`)
already has exactly the right shape for this, without needing to modify it at all:

```java
private AgentBuilder installModule(AgentBuilder builder, ModuleInstrumentation module, boolean retransform) {
    ...
    if (CollectionUtil.isEmpty(module.instrumentationTypes())) {
        LOGGER.warn("[arex] filtered empty instrumentation module: {}", moduleName);
        return builder;   // <-- no TypeInstrumentation is ever installed; ByteBuddy never touches these classes
    }
    ...
}
```

`installModule` already skips ByteBuddy installation entirely for a module whose
`instrumentationTypes()` returns an empty list — this is existing behavior, not something we need
to add. So both new modules implement the gate **inside their own `instrumentationTypes()`**,
nowhere else:

```java
// SpringConfigModuleInstrumentation (Part A) and SpringBeanConfigModuleInstrumentation (Part B)
@Override
public List<TypeInstrumentation> instrumentationTypes() {
    if (!Config.get().getBoolean(ConfigConstants.ENABLE_SPRING_CONFIG, false)) {
        return Collections.emptyList();
    }
    return asList(new SpringPropertyResolverInstrumentation(), ...);
}
```

When `arex.spring.config` is unset or `false`:

- `PropertySourcesPropertyResolver` (Part A's only advice target) is **never matched, never
  transformed, never touched** by ByteBuddy — the class loaded into the target JVM is bit-for-bit
  what it'd be without this feature existing at all.
- Part B's context-refresh hook (the only ByteBuddy advice Part B needs — see §7.3/§7.2) never
  fires, so its `BeanFactoryPostProcessor` (bean-definition/scoped-proxy rewriting) and
  `BeanPostProcessor` (`@Value`/`@ConfigurationProperties` capture) are never registered with the
  application context at all — **no application bean definition is ever modified**, and Spring's
  own internals (`AutowiredAnnotationBeanPostProcessor`, `ConfigurationPropertiesBindingPostProcessor`,
  etc.) are untouched by us either way, since Part B's design (§7.3) deliberately uses Spring's
  public `BeanPostProcessor`/`BeanFactoryPostProcessor` SPI rather than advising those classes
  directly. The application's beans stay exactly as declared, singleton and unproxied, identical to
  today.
- No new `ArexContext`/`ContextManager` code paths run either, since nothing calls into
  `SpringConfigExtractor`/`ArexReplayScope` if the advice that would call them was never woven in.

This is a stronger guarantee than a runtime `if (flag) {...}` check sprinkled through advice bodies
would give — it means zero bytecode modification of Spring/application classes, not just zero
*observable behavior change*, which directly matches "the original code is not touched."

### 5.2 Where the flag is read from, and when it's evaluated

Same mechanism as every other agent config value: `Config.get().getBoolean(key, false)`
(`arex-instrumentation-api/.../config/Config.java:160`), settable the same way other `arex.*` flags
already are for this agent (system property `-Darex.spring.config=true`, the agent's config
file/config-center value, etc. — whichever channel the existing config loader already supports;
no new loading mechanism needed). `instrumentationTypes()` is evaluated once at agent premain
(`InstrumentationInstaller.install()`), and again for any module in the `retransform` list
(`ConfigConstants.RETRANSFORM_MODULE`) if the agent's live-config-reload mechanism is used — that
retransform path is existing, shared infrastructure and needs no changes to pick this flag up if a
team wants to flip it without restarting the JVM.

### 5.3 One flag (decided)

`arex.spring.config` gates both Part A and Part B — confirmed, matching the user's request for one
on/off switch for "the spring config recording/replaying mechanism" (see §10). Worth noting for the
record: Part B has a materially larger footprint when *on* than Part A (§8.3 — it rewrites bean
definitions and adds proxy indirection to affected beans in every environment, not just where
`ArexContext` is active), so a finer-grained split (`arex.spring.config.env` for Part A,
`arex.spring.config.bean` for Part B) would have been possible with no architectural change — each
module already gates itself independently in `instrumentationTypes()` — but one flag is the
decision going forward.

## 6. Proposed design — Part A: dynamic `Environment` reads

### 6.1 New module: `arex-instrumentation/config/arex-spring-config`

Mirrors `config/arex-apollo`'s shape:

```
arex-instrumentation/config/arex-spring-config/
  pom.xml                                  (depends on spring-core/spring-context, provided scope)
  src/main/java/io/arex/inst/config/spring/
    SpringConfigChecker.java               (classpath / DISABLE_MODULE gate)
    SpringConfigExtractor.java             (record()/replay() primitives via MockUtils)
    SpringPropertyResolverInstrumentation.java   (the ByteBuddy hook, see 5.2)
    SpringConfigModuleInstrumentation.java (@AutoService(ModuleInstrumentation.class), name "spring-config")
```

Registered in `arex-instrumentation/pom.xml` next to the `arex-apollo` module entry, and added as
a dependency in `arex-agent/pom.xml` the same way Apollo is.

### 6.2 Instrumentation point: `PropertySourcesPropertyResolver#getProperty`

Spring Boot/Framework funnel essentially every dynamic property read — `Environment.getProperty`,
`getRequiredProperty`, `resolvePlaceholders`, `${...}` SpEL resolution — through one method:

```
org.springframework.core.env.PropertySourcesPropertyResolver
    #getProperty(String key, Class<T> targetValueType, boolean resolveNestedPlaceholders)
```

This is the same "find the one convergence point" strategy Apollo used for
`RemoteConfigRepository#loadApolloConfig()`. Hooking this single method (rather than every
`Environment` overload) covers all of `Environment.getProperty(String)`,
`getProperty(String, Class)`, `getProperty(String, String default)`, `getRequiredProperty(...)`,
and placeholder resolution, with one advice class.

```java
public class SpringPropertyResolverInstrumentation extends TypeInstrumentation {
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("org.springframework.core.env.PropertySourcesPropertyResolver");
    }
    public List<MethodInstrumentation> methodAdvices() {
        ElementMatcher<MethodDescription> matcher = named("getProperty")
                .and(takesArguments(3))
                .and(takesArgument(0, named("java.lang.String")));
        return singletonList(new MethodInstrumentation(matcher, PropertyAdvice.class.getName()));
    }

    public static class PropertyAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
        public static Object onEnter(@Advice.Argument(0) String key,
                                      @Advice.Argument(1) Class<?> targetType) {
            return SpringConfigExtractor.tryReplay(key, targetType); // null unless replaying + hit
        }

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(@Advice.Enter Object replayed,
                                   @Advice.Argument(0) String key,
                                   @Advice.Return(readOnly = false) Object result) {
            if (replayed != null) {
                result = replayed;      // short-circuit: use recorded value
                return;
            }
            SpringConfigExtractor.tryRecord(key, result); // real value resolved normally; record it
        }
    }
}
```

This is structurally identical to `ApolloRemoteConfigRepositoryInstrumentation.LoadAdvice` (enter
returns a non-default value to skip the real call; exit overwrites the return value) — the same
idiom already used and tested in this codebase.

Re-entrancy: `resolveNestedPlaceholders=true` can recursively call back into `getProperty` while
resolving a `${other.key}` reference inside a value. `SpringConfigExtractor` guards this with a
cheap `ThreadLocal<Boolean>` re-entrancy flag so nested resolutions aren't independently
recorded/replayed as if they were top-level reads (they'll naturally be captured correctly as part
of the outer key's resolved — already-substituted — value).

### 6.3 `SpringConfigExtractor`: record once per generation, with a per-key correctness fallback

**Revised from an earlier draft of this proposal**, which recorded every read under the reading
request's own `recordId` — genuinely per-request. That was the right *default* assumption for a
surface Spring can re-evaluate per call, but the user has confirmed that in the target
applications, Spring configuration is actually consistent across all traffic within one
environment (it only changes on redeploy, not per request). Once that's true, per-request
recording has no correctness benefit — every one of those repeated writes is capturing the same
value again — so it should use the same **generation/snapshot model as Part B** (§7.3) instead of
inventing separate per-request machinery: **one shared "Spring config generation" per environment,
covering both Part A's dynamic keys and Part B's `@Value`/`@ConfigurationProperties` fields**,
recorded once and referenced by every case captured under it, rather than two parallel mechanisms.

This is a genuine simplification, not just an optimization: it means Part A and Part B use one
generation id, one entry-point-mocker pointer-stamping mechanism, and one replay-time
"fetch this environment's snapshot" step — building one mechanism instead of two. The isolation
guarantee is unaffected either way (see the restatement at the end of this section) — it never
actually depended on per-request granularity, only on each case's entry-point mocker pointing back
at its own origin environment's snapshot, which both parts already do.

To stay correct even if some key turns out **not** to be as stable as expected, recording keeps a
lightweight per-key fallback: the first time a key is seen in a generation, its value is cached in
memory and recorded once; if a *later* read of that same key ever comes back different, that's a
signal this specific key doesn't fit the "constant per environment" assumption, and recording falls
back to the original per-request behavior **just for that key**, with a one-time warning so it's
visible for follow-up. The common case (every key genuinely constant, per the user's confirmation)
pays for exactly one write per distinct key per generation; the rare/unexpected case (a key that
does vary) degrades gracefully to exactly the same safety Part A had before this revision, instead
of silently locking in a stale first-seen value.

```java
public class SpringConfigExtractor {
    private static final String ENV_KEY_PREFIX = "spring-env:"; // avoids colliding with Apollo's
                                                                  // namespace-named operationNames,
                                                                  // and with Part B's "spring-bean:"
                                                                  // keys, in the shared CONFIG_FILE
                                                                  // category
    // shared with Part B (§7.3) — one generation id for the whole environment's Spring config
    private static final AtomicReference<String> CURRENT_GENERATION_ID = SpringConfigGeneration.CURRENT;
    private static final ConcurrentHashMap<String, Object> RECORDED_THIS_GENERATION = new ConcurrentHashMap<>();

    public static Object tryReplay(String key, Class<?> targetType) {
        if (!ContextManager.needReplay()) {
            return null; // not a replay request on this thread: let real resolution run
        }
        if (reentrant()) {
            return null;
        }
        // tier 1: this request's own case may hold a per-key fallback override (see tryRecord)
        Mocker ownCase = MockUtils.createConfigFile(ENV_KEY_PREFIX + key);
        Object replayed = MockUtils.replayBody(ownCase);
        if (replayed != null) {
            return replayed;
        }
        // tier 2: fall back to this case's recorded generation snapshot
        String generationId = (String) ContextManager.currentContext().getAttachment(GENERATION_ATTACHMENT_KEY);
        if (StringUtil.isNotEmpty(generationId)) {
            Mocker generationMocker = MockUtils.create(MockCategoryType.CONFIG_FILE, ENV_KEY_PREFIX + key);
            generationMocker.setRecordId(generationId); // the one deliberate recordId override in Part A
            replayed = MockUtils.replayBody(generationMocker);
        }
        if (replayed == null) {
            LogManager.info("spring.config.replay.miss", key); // observability, see 8.2
            return null; // fail-open (default, see 8.2): caller falls back to real resolution
        }
        return replayed;
    }

    public static void tryRecord(String key, Object resolvedValue) {
        if (!ContextManager.needRecord() || resolvedValue == null || reentrant()) {
            return;
        }
        if (!SpringConfigFilter.shouldCapture(key)) { // include/exclude patterns, see 8.1
            return;
        }
        Object cached = RECORDED_THIS_GENERATION.putIfAbsent(key, resolvedValue);
        if (cached == null) {
            recordUnderGeneration(key, resolvedValue); // first time this generation: one write, done
            return;
        }
        if (!cached.equals(resolvedValue)) {
            LogManager.warn("spring.config.env.unstable-key", key); // visible: this key isn't constant
            recordUnderOwnCase(key, resolvedValue); // per-request fallback, only for this key
        }
        // else: unchanged since first-seen — nothing to do, this is the common case
    }

    private static void recordUnderGeneration(String key, Object value) {
        Mocker mocker = MockUtils.create(MockCategoryType.CONFIG_FILE, ENV_KEY_PREFIX + key);
        mocker.setRecordId(CURRENT_GENERATION_ID.get());
        mocker.getTargetResponse().setBody(Serializer.serializeWithType(value));
        MockUtils.recordMocker(mocker);
    }

    private static void recordUnderOwnCase(String key, Object value) {
        Mocker mocker = MockUtils.createConfigFile(ENV_KEY_PREFIX + key); // recordId = this request's own caseId
        mocker.getTargetResponse().setBody(Serializer.serializeWithType(value));
        MockUtils.recordMocker(mocker);
    }
}
```

A few things worth calling out explicitly:

- `recordUnderGeneration` is the one place in Part A that deliberately overwrites `recordId` away
  from the reading request's own `caseId` — same move Apollo makes (§2.3) and Part B makes (§7.3),
  but now scoped to a value that's actually shared across many requests on purpose, not a
  workaround for a JVM-singleton constraint. `recordUnderOwnCase` (the per-key fallback path) keeps
  the original, non-overridden behavior for the rare key that turns out not to be constant.
- Exactly like Part B, the current generation id needs to be discoverable at replay time from the
  *case being replayed*, not the TEST host's own current generation — stamped on the entry-point
  mocker at record time and echoed back by the replay orchestrator, reusing the identical mechanism
  §7.3/§7.4 already need (one pointer now covers both parts, rather than two separate ones).
- **Isolation guarantee, restated for this revision — and why this isn't Apollo's problem again.**
  §3's whole argument against Apollo's model was that `currentReplayConfigBatchNo` is a single
  mutable field, read live by whichever request happens to be replaying *at that moment* on the
  TEST agent, so concurrent replays of different origin cases contend for it. This design doesn't
  do that: `CURRENT_GENERATION_ID` is only ever read/written on the **recording** side (each
  PROD-N agent tracking its own generation as it records new cases) — the **replaying** TEST agent
  never maintains or consults a "current generation" of its own at all. Tier 2 of `tryReplay` looks
  up whatever generation id is stamped on *this specific case's own entry-point mocker*, fetched
  from storage per request, not from any live mutable field on the TEST agent. So a PROD-2 case's
  entry-point mocker points at a generation id minted on PROD-2's agent at record time; a
  concurrently-replaying PROD-1 case points at PROD-1's — both are static, stored facts about each
  case, looked up independently per request, with no shared mutable state anywhere on the replay
  path. Tier-1 per-request overrides are still keyed by each request's own `recordId`/`replayId`
  exactly as before this revision.
- Async continuations of the same request (thread-pool hops) still carry the right context because
  `TraceContextManager`/`ArexContext` propagation across executors is handled centrally by the
  existing `arex-instrumentation/internal/arex-executors` module — nothing new is needed here.
- Generation rotation: if the target applications never change Spring config without a redeploy
  (no Spring Cloud Config / `@RefreshScope` live refresh), the generation id only needs to be minted
  once, at first use per JVM, and never rotates again — the simplest version of this design. If a
  live-refresh mechanism *is* in use, the same rotation trigger Part B needs anyway (an
  `EnvironmentChangeEvent`/`RefreshScopeRefreshedEvent` listener minting a new generation id and
  clearing `RECORDED_THIS_GENERATION`) covers both parts at once. This is folded into open question
  1 in §11 rather than assumed either way.

### 6.4 Mocker category: reuse `CONFIG_FILE` (decided)

No new `MockCategoryType` — this reuses the existing `MockCategoryType.CONFIG_FILE`
(`createSkipComparison("ConfigFile")`) that Apollo already uses, via the existing
`MockUtils.createConfigFile(String)` factory. This keeps the AREX platform's existing "ConfigFile"
UI/report grouping intact and requires no new category, no `arex-agent-bootstrap` change, and no
change to `MockUtils`.

Because Apollo and Spring dynamic-config now share one category, they also share one
`operationName` key space per `recordId`, so a naming convention is needed to guarantee the two
producers can never collide on the same storage key even if, say, an Apollo namespace happens to be
named the same as a Spring property key. Prefix every Spring-originated `operationName`:

```java
private static final String ENV_KEY_PREFIX = "spring-env:";

Mocker mocker = MockUtils.createConfigFile(ENV_KEY_PREFIX + key);
```

Apollo's own `operationName`s are plain namespace names (e.g. `"application"`) and are never
prefixed this way, so `"spring-env:server.port"` can't collide with an Apollo namespace called
`server.port`, nor with a plain dynamic-read key from this module recorded without the prefix by
mistake. (Part B, §7.3, uses its own `"spring-bean:"` prefix for the same reason — see there.)

This also means the code in §6.3 above should read `MockUtils.createConfigFile(ENV_KEY_PREFIX + key)`
wherever it says `MockUtils.createSpringConfig(key)` — no new `MockUtils` factory method is added at
all, and `MockUtils.replayMocker`'s existing `isNotConfigFile` special-case (which already lets a
`ConfigFile` mocker attempt replay even on an otherwise-invalid case) applies automatically, with no
change needed there either.

### 6.5 Value type handling

Unlike Apollo (always a `java.util.Properties` blob), Spring properties are requested with
arbitrary target types (`String`, `Integer`, `Boolean`, `Duration`, `DataSize`, or a custom type
via `ConversionService`). Use `Serializer.serializeWithType`/`deserializeWithType`
(`arex-instrumentation-api/.../serializer/Serializer.java:72-90`), which already exists precisely
for "store the type alongside the value" cases elsewhere in the codebase — no new serialization
mechanism is needed.

### 6.6 No changes needed to the request-lifecycle wiring

Because record/replay is driven entirely by the existing `ContextManager.needRecord()`/`needReplay()`
checks (already true/false correctly for the current request by the time any business code runs),
there is **no need** for the Apollo-style `RequestHandler` dance (`ApolloServletV3RequestHandler`
reading `arex-record-id`/`arex_replay_prepare_dependency` headers, stamping a `configBatchNo`
attribute, etc.). Spring config "just works" as soon as `ArexContext` exists for the thread, the
same as every other dependency mocker. This removes an entire layer of Apollo's design that exists
only to compensate for the global-state approach — one more reason the per-request model is
simpler, not just safer.

## 7. Proposed design — Part B: `@Value` and `@ConfigurationProperties`

### 7.1 The problem, precisely

Unlike Part A, there is no per-request *read* to intercept: the value is resolved once, at
context-refresh time, into a field on a singleton bean. To satisfy "overwrite the startup value,
every time we replay a request, without leaking into other concurrently-replaying requests," we
need the *value visible when business code later reads `this.field`* to depend on which request is
currently executing on that thread — i.e., we need per-request-scoped state layered on top of an
otherwise-singleton bean, without changing the bean's declared scope from the application's point
of view and without a global mutable field (which would have exactly the same cross-talk problem
under concurrent replay that made Apollo's Config-object model unsafe for us in §3).

Directly reflecting a new value onto the *existing* singleton instance's field, then reflecting it
back afterward, is not safe here: two threads replaying different origin cases concurrently would
race on the same field (thread A's overwrite could be visible to thread B's read, or A's "restore"
could stomp on B's still-in-flight override). We need each replaying request to effectively see
**its own object**, not a shared one.

### 7.2 Mechanism: a custom Spring `Scope` + per-request bean clones

Spring already has a first-class, well-tested mechanism for "the same injection point transparently
resolves to a different underlying instance depending on some notion of 'current scope'" — it's how
`@RequestScope`, `@SessionScope`, and Spring Cloud's `@RefreshScope` all work: the injected
reference is a stable CGLIB proxy; each method call on the proxy asks a `Scope` implementation
(`org.springframework.beans.factory.config.Scope#get(String name, ObjectFactory<?> objectFactory)`)
for "the current target," on the calling thread, at call time. We can reuse this exact SPI instead
of inventing new bytecode weaving:

1. **Identify affected bean definitions.** A `BeanFactoryPostProcessor`, installed by instrumenting
   the Spring context refresh path (same general technique `arex-component-scan` already uses on
   `SpringApplication.run`), scans bean definitions for classes containing a `@Value`-annotated
   field or annotated `@ConfigurationProperties`, and rewrites matching definitions to be scoped
   under a new custom scope name, `"arex-replay"`, with `proxyMode = ScopedProxyMode.TARGET_CLASS`
   (the same proxying Spring itself uses for `@RefreshScope`). This only touches bean definitions
   that actually carry recordable config — the vast majority of beans in an app are untouched.
2. **Register `ArexReplayScope`** (`org.springframework.beans.factory.config.Scope`) against that
   scope name:
   - `get(name, objectFactory)`:
     - If `ContextManager.needReplay()` is false for the current thread (normal production traffic,
       or record-mode traffic — see §7.3) → return one instance created lazily via
       `objectFactory.getObject()` and cached for the lifetime of the JVM. This is the "no override"
       fast path and behaves exactly like a plain singleton, at the cost of one extra scope-lookup
       per proxied-bean method call — the same overhead Spring already imposes on every
       `@RequestScope`/`@SessionScope` bean in production apps today.
     - If replaying → look in the current `ArexContext.attachments` (already the mechanism used for
       per-request caches elsewhere, e.g. `ArexContext.getCachedReplayResultMap()`) for an
       already-created override instance of this bean, keyed by bean name. If absent: obtain the
       real singleton instance (the cached one from the branch above), allocate a **field-for-field
       shallow clone** of it via `org.springframework.objenesis.SpringObjenesis` (already bundled
       with `spring-core`, and already how Spring itself instantiates CGLIB/scoped-proxy targets
       without invoking constructors — reusing it means no new dependency and no risk of re-running
       `@PostConstruct`/constructor side effects on the clone), reflectively overwrite only the
       specific fields for which a recorded override exists for this request's snapshot (§7.4), and
       cache the clone in `ArexContext.attachments` for the rest of this request. Subsequent calls
       within the same request reuse the same clone, so a bean's state is internally consistent for
       the whole request.
   - `remove(name)`: drop the cached clone from `ArexContext.attachments` — in practice a no-op
     beyond what `ArexContext.clear()` already does at request end (`ContextManager.remove()`), so
     no new cleanup lifecycle is required.

Because the decision and the clone both live on `ArexContext` (thread-local, one per in-flight
request, removed at request end), two concurrently-replaying requests — even ones replaying cases
originally recorded in different production environments — resolve to two distinct Java objects.
There is no shared mutable field anywhere in this path, so there is no race and no cross-talk **by
construction**, directly satisfying "we don't want to use other traffic's `@Value` or
`@ConfigurationProperties` to replay."

Note this is a strictly better position than Apollo was in for its own JVM-global workaround
(§3): here, the scope's `get()` runs synchronously *on the request's own thread*, so it can consult
`ContextManager.currentContext()` directly — no batch id, no time window, no JVM-global flag needed
for the substitution itself. (A snapshot/version id is still needed, but only for the much narrower
job described next, in §7.4 — not for substitution safety.)

### 7.3 Record: capture once per config "generation," not once per request

Because the underlying value genuinely doesn't change per request — only when the app is
redeployed or (if used) a `@RefreshScope`/Spring Cloud Config refresh event fires — recording once
per request would be pure waste. Instead, mirror Apollo's "batch/version" bookkeeping (the one part
of its design that *does* transfer directly, because here the granularity-of-change problem is the
same one Apollo actually has):

- **Capture point for `@Value` — not the field-injection advice from an earlier draft of this
  proposal.** `@Value` can be injected onto a field, a setter method, or a constructor parameter,
  and Spring resolves each through a *different* internal path:
  `AutowiredAnnotationBeanPostProcessor$AutowiredFieldElement#inject` (fields),
  `AutowiredAnnotationBeanPostProcessor$AutowiredMethodElement#inject` (setters/methods — verified
  as a distinct class against Spring 6.1.6), and constructor parameters are resolved inside
  `ConstructorResolver#autowireConstructor`, not through either `InjectedElement` subclass at all.
  Hooking only the field-injection class (as an earlier draft of this proposal did) would silently
  miss `@Value` on setters or constructors. Instead, capture **after** the bean is fully built,
  independent of which injection style populated each field: register a normal
  `BeanPostProcessor#postProcessAfterInitialization(Object bean, String beanName)` (ordered to run
  after `AutowiredAnnotationBeanPostProcessor` and `ConfigurationPropertiesBindingPostProcessor`,
  e.g. via `Ordered.LOWEST_PRECEDENCE`), and for each bean, walk its class hierarchy
  (`ReflectionUtils.doWithFields`, the same utility Spring itself uses internally) looking for
  fields annotated `@Value`; for each match, reflectively read `field.get(bean)` and record
  `(beanName, field.getName(), value)`. Because this reads the field's *final* value after
  construction rather than intercepting the injection call itself, it works identically regardless
  of whether that `@Value` came from a field, a setter, or a constructor parameter — one mechanism
  covers all three. It's also registered via `BeanPostProcessor`, a public, stable Spring SPI,
  rather than advising Spring's private internal implementation classes the way the field-only
  version would have — lower risk of breaking across Spring versions.
- **Capture point for `@ConfigurationProperties` — the same `BeanPostProcessor`, not a separate
  advice.** An earlier draft of this proposal hooked
  `ConfigurationPropertiesBindingPostProcessor#postProcessBeforeInitialization` directly, but since
  the custom `BeanPostProcessor` above already runs after that binding completes (ordering it last
  guarantees this), it can just as easily handle this case in the same pass: for each bean, check
  `AnnotatedElementUtils.hasAnnotation(bean.getClass(), ConfigurationProperties.class)`; if true,
  serialize the whole bean via `Serializer.serializeWithType` and record it as one unit keyed by
  `beanName` (simpler than field-by-field, since `@ConfigurationProperties` beans are typically
  plain data objects with no other state mixed in). This means Part B's entire record side needs
  **no ByteBuddy method advice at all** — just one `BeanPostProcessor` registered by the same
  `BeanFactoryPostProcessor`/context-refresh hook that also installs the scoped-proxy rewriting in
  §7.2, which is a meaningfully smaller and lower-risk surface than instrumenting Spring's binding
  internals directly.
- Both are recorded under the same reused `MockCategoryType.CONFIG_FILE` category (§6.4's decision
  applies here too — no new category), with `operationName` prefixed `"spring-bean:"` followed by
  `beanName` (whole-object case) or `beanName + "." + fieldName` (per-field `@Value` case) to keep
  this producer's key space distinct from both Apollo's and Part A's `"spring-env:"` keys within the
  same category. `recordId` is set to **the same per-environment "Spring config generation" id
  Part A now uses (§6.3's revision)** — one shared `SpringConfigGeneration.CURRENT`
  (`AtomicReference<String>`), minted at context-refresh completion and re-minted only on an actual
  refresh event, covering both `@Value`/`@ConfigurationProperties` and dynamic `Environment` keys
  together rather than two separate ids. Directly analogous to
  `ApolloConfigExtractor.RECORD_CONFIG_BATCH_NO`, but here it's pure bookkeeping with no
  correctness burden, since only the record side ever reads/writes it, and only one generation is
  ever "current" at a time in real wall-clock time on one environment's JVM.
- Exactly like Apollo's `configBatchNo` attribute, stamp this snapshot id onto every entry-point
  mocker recorded while it's current, via the existing `RequestHandler.postHandle` hook
  (`request.setAttribute(ArexConstants-style key, currentSnapshotId)`, copied into the entry-point
  mocker's request attributes the same way `ApolloServletV3RequestHandler` already does it). This
  is how a later replay of that case knows which bean-config generation to ask for.

### 7.4 Replay: resolve the snapshot per request, apply per request, never globally

1. On `handleAfterCreateContext` (before business logic runs), read the replay orchestrator's
   `arex-record-id` header as usual, plus a new header/attachment carrying the case's recorded
   snapshot id (echoed back by the replay side exactly the way `arex_replay_prepare_dependency`
   already works for Apollo). Store it via `ContextManager.setAttachment("springBeanConfigVersion",
   snapshotId)` — **on the per-request `ArexContext`**, not anywhere global.
2. `ArexReplayScope.get()` (§7.2), when creating a request's clone of a bean, reads that attachment
   and queries the storage backend for `CONFIG_FILE` mockers matching
   `(recordId = snapshotId, operationName = "spring-bean:" + beanName[.fieldName])`, then applies
   them to the clone.
3. Because step 1's attachment lives on the replaying request's own `ArexContext`, two concurrently
   replaying requests carrying different snapshot ids simply produce different clones — there is no
   point in this flow where one request's snapshot id could influence another's resolution.
4. Missing snapshot/field data on replay: same fail-open-with-logging posture as §8.2's dynamic-read
   fallback — the clone simply keeps the TEST-host-bound value for any field with no recorded
   override, with a `spring.beanConfig.replay.miss` log line for visibility.

### 7.5 Practical limitations (worth stating up front)

- Only affects beans managed by Spring's container that get selected for scoped-proxy rewriting.
  A `@Value` parameter consumed inline inside a `@Bean` factory method (not a field on a bean) is
  baked into whatever that factory method constructs and can't be intercepted this way unless the
  *resulting* bean is itself proxied.
- CGLIB class-proxying (what `ScopedProxyMode.TARGET_CLASS` uses) requires the bean's class and
  relevant methods to be non-`final` — an existing, previously-solved constraint (Spring's own
  `@RefreshScope` has the identical requirement), not a new risk this design introduces. **Confirmed
  not applicable to the target application** — no `final` classes/methods on the beans this would
  proxy, so this constraint isn't expected to block anything here; still worth a one-time classpath
  scan before rollout in case that changes later (e.g. a new dependency introduces a `final`
  `@ConfigurationProperties` class), but it's not a design risk for the current codebase.
- Objects created manually with `new` outside Spring's container are out of reach, as with any
  DI-based mechanism.
- Immutable `@ConfigurationProperties` using constructor binding (records / `@ConstructorBinding`)
  need the "whole object" replacement path (§7.3's `@ConfigurationProperties` handling already
  treats the bean as one serializable unit for this reason) rather than field-by-field reflection.

### 7.6 Alternative considered: reflective overwrite + serialized execution

A much simpler alternative exists if the team would rather avoid the scoped-proxy machinery:
reflectively overwrite the real singleton's fields immediately before invoking the request's
top-level handler, and restore them in a `finally` block immediately after — but only while holding
a single global lock around that whole section, so no two "foreign-config" replays can ever be
in-flight at once. This avoids any bean-definition rewriting or CGLIB proxying at the cost of
**serializing all replay execution** that touches these fields (effectively all replay traffic, in
practice) — likely an unacceptable throughput hit for anything beyond light, sequential replay runs.
Documented here as a lower-effort fallback, not the plan — §7.2's scoped-proxy/clone mechanism is
the decided approach (§10), specifically because it avoids this option's throughput cost.

## 8. Cross-cutting concerns

### 8.1 Hot-path cost and payload volume

`PropertySourcesPropertyResolver#getProperty` is called very frequently — far more often than a
typical DB/HTTP call site — including entirely outside of any request (startup, background
threads). Mitigations:

- `ContextManager.needRecord()`/`needReplay()` is a cheap `ThreadLocal` + map lookup and is the
  **first** check in both `tryReplay`/`tryRecord`; for the overwhelming majority of calls (no
  active `ArexContext`) this is a couple of pointer dereferences, matching the cost profile
  already accepted for every other instrumented hot method in the agent.
- `SpringConfigFilter` (include/exclude key prefixes), configured the same way
  `Config.get().getIncludeServiceOperations()`/exclude patterns already work for operation
  filtering — but **off by default**: with no configuration at all, `shouldCapture(key)` returns
  `true` unconditionally and every key on `application.yml` is recorded/replayed, per the user's
  explicit preference (record everything unless told otherwise). Configuring
  `arex.spring.config.include`/`arex.spring.config.exclude` key-prefix lists narrows this down for
  teams that do want to bound payload volume or skip noisy framework-internal namespaces
  (`spring.*`, `server.*`, `management.*`, `logging.*`) — entirely opt-in, never assumed.
- Repeated reads of the same key within one request are naturally handled by the existing
  `ArexContext.calculateSequence()` ordering (same mechanism already used for repeated identical
  DB queries in one case) — no special-casing required.

### 8.2 Missing key on replay (fallback policy) — decided: fail-open

"Missing key on replay" means: a replaying request (say, replaying a case originally recorded in
PROD-2) asks for some config key or `@Value`/`@ConfigurationProperties` field, and the storage
query for that specific `recordId`/snapshot comes back with nothing — most likely because that key
was never actually read/captured during the original PROD-2 recording (e.g. a code path added since
then now reads a key that didn't exist at record time), rather than because of any environment
mix-up. There are exactly two ways to handle that gap, and they trade off differently:

**Option 1 — fail-open (decided default, matching Apollo's own behavior on a miss).**
`tryReplay` returns `null`, and the code falls through to whatever value is *locally*
available — the TEST host's own `application.yml`-bound value, or (for Part B) whatever value the
bean already held before cloning. The replayed request keeps running to completion using that local
value for just that one key.

- *Pro*: the replay still runs end-to-end; a gap in recorded data for one config key doesn't abort
  or invalidate the whole case.
- *Con*: if that local/TEST value is materially different from what PROD-2 actually had for that
  key, business logic can genuinely take a different branch than it did in production — and when
  the replay comparison later reports a mismatch between the replayed response and the originally
  recorded one, that mismatch's *root cause* (missing config data) looks identical to a real
  regression bug. Without the accompanying miss-log/metric, this is hard to tell apart from an
  actual bug, and could produce misleading replay reports.

**Option 2 — strict (fail loudly on a miss).** `tryReplay` (or the Part B clone step) treats a miss
as a signal that this replay cannot be trusted for that key, and does something visible instead of
silently substituting a value — options range from marking just that one field/key as "unresolved"
(e.g. a sentinel value the business code will visibly choke on) up to marking the *whole case's*
replay result as "incomplete/inconclusive" rather than pass/fail, so it's excluded from (or flagged
separately in) the comparison report.

- *Pro*: a config-data gap is surfaced as exactly what it is — missing recorded data — rather than
  being indistinguishable from a real behavioral regression. Directly serves the "don't let the
  wrong environment's config quietly influence a replay" concern from the original ask.
- *Con*: more cases end up "inconclusive" rather than pass/fail, especially right after this feature
  first ships (before recording has had time to capture every key every code path might ask for) —
  lower apparent replay coverage/completion rate, at least initially, in exchange for
  trustworthiness of the cases that do complete.

**Decided: fail-open is the default**, prioritizing replay completion rate over strict provenance
guarantees on individual missing keys — cheap to implement (`tryReplay`'s existing
`if (replayed == null)` branch simply falls through to the real resolution, no new code path), and
consistent with Apollo's existing behavior on a miss. Two things carry over from the trade-off above
regardless of the choice: every miss still logs/emits a metric (`spring.config.replay.miss`), so
gaps stay visible rather than silent even though they no longer block the replay; and **strict mode
remains available as an opt-in** (`arex.spring.config.strict-replay=true`) for teams that would
rather have a config-data gap surfaced explicitly (as "incomplete/inconclusive") than risk it being
indistinguishable from a real regression in the comparison report — worth revisiting per-team if
false-positive diffs traced back to missing config data turn out to be common in practice.

### 8.3 `@Value`/`@ConfigurationProperties` — covered by Part B, with its own overhead profile

Unlike the previous draft of this proposal, these are **in scope** — see §7 for the full design.
Two things worth restating here since they affect the whole app, not just replay traffic:

- Part B's scoped-proxy rewriting only applies to bean definitions that actually carry `@Value`/
  `@ConfigurationProperties`, but it applies to *all* of them, in *every* environment (PROD
  included, since PROD needs to be recordable too) — not just on the TEST replay host. This is a
  materially bigger footprint than Part A (which only costs anything on threads with an active
  `ArexContext`), and is the main reason Part B sits behind its own explicit opt-in rather than
  being bundled unconditionally with Part A. See §5 for the feature flag.
- The two parts remain independently useful: a team could enable Part A alone (dynamic `Environment`
  reads) without paying Part B's proxying cost, if their app's config-sensitive behavior lives
  entirely in dynamic reads rather than `@Value` fields.

### 8.4 Interaction with existing Apollo config feature

Independent modules, independent instrumentation surfaces (Part A's `PropertySourcesPropertyResolver`
advice and Part B's `BeanPostProcessor`/`BeanFactoryPostProcessor` registration vs Apollo's
`RemoteConfigRepository`/`DefaultConfig` advice), independently toggleable (`arex.spring.config` vs
Apollo's own
`ApolloConfigChecker`/`DISABLE_MODULE` gate) — but **not** independent at the storage layer, since
both now write `CONFIG_FILE`-category mockers (§6.4's decision to reuse the existing category rather
than add new ones). The `"spring-env:"`/`"spring-bean:"` `operationName` prefixes (§6.4, §7.3) exist
specifically to keep that shared key space collision-free between the two producers — this is the
one place where "independent" needs a caveat, and it's handled entirely by the prefix convention,
with no shared mutable state or coordination required between the two features at runtime.

### 8.5 Multi-environment safety — explicit restatement

Concretely, for the PROD-1/2/3 → single-TEST-agent topology described in §1:

- Recording happens independently in each production environment. A request handled in PROD-2
  reads PROD-2's own local `Environment` (because that's simply what's running there) and records
  each key it touches under **that request's own `recordId`** — there is no notion of "environment"
  in the stored data at all, and none is needed: the `recordId` already uniquely identifies "the
  specific request that was recorded in PROD-2, at that time, with PROD-2's config."
- Replay happens only on the TEST agent, whose own local `application.yml` is irrelevant to any
  key that was successfully captured — `tryReplay` looks up the mocker by the replaying request's
  own `recordId`/`replayId` (which is the id of the original PROD-2 recording, passed in via the
  `arex-record-id` header exactly as it already is for every other dependency category), so the
  query can only ever return values captured during that one PROD-2 request.
- Because isolation rides on `recordId`, not on any global or agent-level state, it does not
  matter whether the TEST agent replays that PROD-2 case sequentially or concurrently with a
  PROD-1 or PROD-3 case — each replaying thread has its own `ArexContext` (via `ThreadLocal`
  `TraceContextManager`), so two concurrent replays literally cannot see each other's recorded
  values. This is the same guarantee that already makes concurrent database/HTTP-mock replay
  across environments safe today; Spring config replay is designed to ride on that exact guarantee
  rather than inventing a new one.
- **Re-recording cost — resolved.** An earlier draft of this proposal recorded every dynamic
  `Environment` read under the reading request's own `recordId`, which for a service doing
  1,000 req/s reading 20 distinct keys per request meant up to 20,000 redundant writes/second even
  for config that never changes. Since the user confirmed Spring configuration is genuinely
  consistent across all traffic within one environment, §6.3 now records each key **once per
  generation** (shared with Part B's own generation id, §7.3) instead of once per request, with an
  automatic per-key fallback to the original per-request behavior if a specific key is ever observed
  to actually vary. This removes the volume concern for the common case (every key constant, per the
  user's confirmation) while staying correct for any exception, without reintroducing Apollo's
  actual problem — see §6.3's explicit isolation restatement for why the shared generation id here
  is safe in a way `ApolloConfigExtractor`'s JVM-global state was not.

## 9. Rollout

- Off by default via `arex.spring.config` (§5) is the actual gate — this replaces the need for a
  separate "ship disabled behind `DISABLE_MODULE` for one release" step, since the feature is
  designed to stay opt-in indefinitely, not just during an initial rollout window.
- Land the include/exclude filter with a conservative default allow-list before recording is
  enabled anywhere near production traffic, to bound payload volume (Part A).
- Add module + advice unit tests mirroring the existing `Apollo*Test` suite structure
  (`ApolloConfigExtractorTest`, `ApolloRemoteConfigRepositoryInstrumentationTest`, etc.) plus an
  integration test under `arex-integration-tests/` exercising record→replay round-trip for a
  couple of property types (String, Integer, Boolean) — for Part A.
- For Part B specifically, given the bean-definition rewriting and CGLIB proxying involved: add a
  dedicated integration test module exercising a representative app with `@Value` fields,
  `@ConfigurationProperties` beans, and at least one bean with a final method/class (to confirm the
  expected, documented limitation in §7.5 rather than a silent failure), plus a test that with
  `arex.spring.config` unset, a business app's bean definitions and instantiated bean classes are
  identical (via reflection/`getClass()` checks) to the same app running without the agent at all —
  a concrete, automatable check for the "zero footprint when off" requirement in §5.

## 10. Decisions made so far

- **Mocker category**: reuse `MockCategoryType.CONFIG_FILE` for both Part A and Part B — no new
  category. Implemented via the `"spring-env:"`/`"spring-bean:"` `operationName` prefix convention
  in §6.4/§7.3/§8.4.
- **`@Value`/`@ConfigurationProperties` are in scope**, recorded and replayed with the same
  no-cross-talk guarantee as dynamic `Environment` reads — this is all of §7 (Part B).
- **Default filter behavior (Part A, and by extension Part B — see below)**: record/replay
  *everything* by default; an include/exclude list is available but off by default, only narrowing
  scope if the team explicitly configures one. This replaces the "exclude framework-internal
  namespaces by default" suggestion from an earlier draft of this proposal. Concretely, in §8.1's
  `SpringConfigFilter.shouldCapture(key)`: with no configuration at all, it returns `true`
  unconditionally (equivalent to the filter not existing); configuring
  `arex.spring.config.include`/`arex.spring.config.exclude` (key-prefix lists, same convention as
  the existing `Config.get().getIncludeServiceOperations()`/exclude mechanism) switches on
  filtering. The same default (capture every `@Value` field and every `@ConfigurationProperties`
  bean unless the team configures an exclude list) is proposed for Part B for consistency, even
  though the request that prompted this was specifically about `application.yml` — flag this back
  to the user if Part B should instead default to something narrower given its larger footprint
  (§8.3).
- **Feature flag**: single `arex.spring.config` flag gating both parts (§5), matching the user's
  explicit request for one on/off switch for "the spring config recording/replaying mechanism."
- **Fallback policy on a replay-time cache miss: fail-open**, with strict mode available as an
  opt-in (`arex.spring.config.strict-replay=true`) rather than the default. See §8.2 for the full
  trade-off write-up and the reasoning kept there for why strict remains available.
- **Part B mechanism: the scoped-proxy/clone approach (§7.2)**, not the reflective-overwrite +
  global-lock alternative (§7.6) — chosen for concurrency-safety with no replay-throughput
  penalty, at the cost of the extra engineering effort (custom `Scope`, bean-definition rewriting)
  §7.2 describes. §7.6 stays documented as the lower-effort fallback if this turns out to be more
  than the team wants to build, but it's not the plan.
- **One flag** (`arex.spring.config`) gating both Part A and Part B — no split into
  `arex.spring.config.env`/`arex.spring.config.bean`. §5.3's rationale for a possible split
  (Part B's always-on proxying footprint vs Part A's request-only cost) still applies as a fact
  about the design, it just isn't reason enough on its own to have two flags.
- **Part A records once per generation, not once per request** (§6.3, revised), sharing Part B's
  generation id (§7.3) rather than using two separate mechanisms — confirmed based on the user's
  statement that Spring configuration is consistent across all traffic within one environment. A
  per-key fallback to per-request recording remains as a correctness safety net for any key that
  turns out not to be constant, logged as `spring.config.env.unstable-key` when it triggers.

## 11. Open questions for the team

1. **Does generation rotation need a live-refresh trigger?** §6.3/§7.3's shared generation id only
   needs to be minted once, at first use per JVM, if the target applications never change Spring
   configuration without a redeploy. If any of them use Spring Cloud Config / `@RefreshScope` live
   refresh, the design needs an `EnvironmentChangeEvent`/`RefreshScopeRefreshedEvent` listener to
   mint a new generation and clear the record-side cache when a refresh actually happens — otherwise
   requests recorded after a live refresh would be incorrectly attributed to the pre-refresh
   generation. Confirming whether any target app uses live refresh determines whether this listener
   is needed at all for the initial implementation. **Left open** — not blocking: the two variants
   aren't a fork in the design, just a small addition on top of it. Implementation can start with
   the simpler "mint once at startup, never rotate" version now, and the refresh-event listener can
   be added later, once confirmed, without changing anything else in §6.3/§7.3.

## 12. What has been implemented

Both parts are built and running in `arex-instrumentation/config/arex-spring-config`, verified
end-to-end against a real target app (`arex-spring-config-demo`, a separate Spring Boot 3.2.5
project) through the actual AREX platform — agent, storage service, and the `arex-schedule`
replay webhook — not just synthetic tests. Building against a real app surfaced several things
only visible in practice; the sections below describe what's actually running and where it
differs from earlier sections of this proposal, and why.

### 12.1 Module and files

`arex-instrumentation/config/arex-spring-config/src/main/java/io/arex/inst/config/spring/`:

- `SpringConfigChecker` — the `arex.spring.config` flag gate.
- `SpringConfigModuleInstrumentation` — registers all four `TypeInstrumentation`s below, gated
  entirely behind the flag (empty list when off, so `InstrumentationInstaller` never installs any
  bytecode advice for this module at all).
- **Part A**: `SpringPropertyResolverInstrumentation` (plain-Spring
  `PropertySourcesPropertyResolver`), `SpringBootConfigurationPropertyResolverInstrumentation`
  (Spring Boot's actual resolver, `ConfigurationPropertySourcesPropertyResolver` — see §12.2),
  `SpringPropertyAdvice` (shared advice body for both), `SpringConfigExtractor` (buffer/cache/
  record/replay logic), `SpringConfigServletV3RequestHandler` /
  `SpringConfigServletV5RequestHandler` (flush the per-request buffer at request exit).
- **Part B**: `SpringApplicationRunInstrumentation` (builds the registry once at startup),
  `SpringBeanConfigRegistry` (the scanned set of eligible beans/fields),
  `DispatcherServletInstrumentation` (apply-on-enter / restore-on-exit, guaranteed via
  `onThrowable`), `SpringBeanConfigExtractor` (the per-bean-lock reflective overwrite logic).

### 12.2 Where the implementation differs from earlier sections of this proposal, and why

- **One aggregate document per case, not one per key/field.** §6.3/§7.3 as originally written
  described per-key/per-field storage (Part A) and a generation-id scheme (Part B). What's
  actually built records every `Environment` key touched during a request (Part A) or every
  registered `@Value`/`@ConfigurationProperties` field (Part B) as one JSON map in one document
  per case, per part — see the record-volume/replay-latency discussion that motivated this. The
  operationName is a fixed string per part (`spring-config-env`, `spring-config-properties`), not
  the `*`-suffixed sentinel or per-key names used briefly during implementation.
- **Part A records per-request, not per-generation.** Simpler than the "record once per
  generation" revision in §6.3 — every record-mode request re-records its own touched keys, with
  no dedup layer. Accepted as a known, explicit trade-off (§8.5) rather than built.
- **Part B uses reflective overwrite + a per-bean lock (§7.6), not the scoped-proxy/clone
  mechanism §10 originally decided on.** Discovered while implementing: a CGLIB-style proxy only
  intercepts *method calls*. `@ConfigurationProperties` beans are routinely read via direct field
  access from another bean (exactly how the demo app's `NotificationHoursController` reads
  `notificationProperties.hours`, deliberately mirroring the target application's own style) — a
  proxy is structurally blind to that access pattern, not just a worse engineering trade-off. See
  the corrected recommendation and reasoning that replaced §10's original decision.
- **Framework-noise exclusion**, not anticipated in the original design: Spring Boot registers
  dozens of its own internal `@ConfigurationProperties` beans (`ServerProperties`,
  `WebMvcProperties`, `JacksonProperties`, ...). Left unfiltered, they bloated every recorded case
  and risked a reflection failure on nested framework objects (`ClassLoader`/`File`/
  `ClassPathResource` fields) that would have silently broken replay for the application's own
  fields too, since one failure rolls back the whole request's overrides. `SpringBeanConfigRegistry`
  now excludes anything under `org.springframework.*`.
- **The feature flag is read via `System.getProperty(...)` directly**, not through
  `Config.get()`/`ConfigManager` as §5.1 originally sketched. `Config.get()`'s properties map is
  populated by an explicit, hardcoded whitelist synced from a remote config fetch that hasn't
  happened yet at the point `instrumentationTypes()` runs at premain — the same reason
  `ConfigConstants.DISABLE_MODULE` also bypasses that path.
- **Part A instruments two classes, not one.** Spring Boot's `ApplicationServletEnvironment`
  overrides `createPropertyResolver()` to return
  `org.springframework.boot.context.properties.source.ConfigurationPropertySourcesPropertyResolver`
  — a separate class from `org.springframework.core.env.PropertySourcesPropertyResolver`, with its
  own private `getProperty(String, Class, boolean)` method of the same shape. Without also
  instrumenting it, Part A would silently do nothing for a Spring Boot app's own
  `Environment.getProperty()` calls — the vast majority of real target apps — while still
  appearing to work for `@Value` placeholder resolution (which uses a separate, plain-Spring
  resolver instance internally, unrelated to the app's main `Environment`).
- **Part B's hook is `DispatcherServlet#doDispatch`**, not the `BeanPostProcessor`/
  `BeanFactoryPostProcessor` capture-on-first-use design in §7.2/§7.3. `onEnter` applies overrides;
  `onExit(onThrowable = Throwable.class)` always restores them and captures for recording,
  regardless of what the request handler does — the guarantee that a failing replayed request
  can never leave shared bean state corrupted for later traffic.
- **Required `recordEnvironment(1)` on every replay-side query mocker** (Part A and Part B alike),
  matching Apollo's own convention — found by tracing a real replay miss all the way into the
  storage service's source: `AREXMockerMongoRepositoryProvider#buildRecordFilters` filters on this
  field, and nothing defaults it on the query path (only the save path server-side defaults it).

### 12.3 What was verified, and how

- **Zero footprint when off**: re-checked after every major change (including after adding Part B
  and after the aggregation refactor) — byte-identical endpoint behavior with `arex.spring.config`
  unset.
- **Recording**: confirmed via direct MongoDB inspection (`RollingConfigFileMocker`) that exactly
  one clean aggregate document is written per case per part, containing exactly the expected
  keys/fields (and, after the framework-noise fix, nothing else).
- **Single-environment replay**: confirmed via the actual `arex-schedule` webhook — not header
  simulation alone — that a replayed case returns its originally-recorded values even when the
  replay host's own live local config differs.
- **Multi-environment replay**: recorded 3 distinct "production environment" cases for the same
  two endpoints with entirely different config, replayed all 3 through the real schedule webhook
  against one shared TEST host running a 4th, different local config. All 8 resulting
  `ReplayCompareResult` documents matched their own original recording.
- **True concurrency**: fired multiple replay requests for different origin cases at the exact
  same instant (not sequentially) against the same shared bean, repeated across several rounds,
  mixed with ordinary non-replay traffic running concurrently. No cross-contamination in any run —
  each request got exactly its own recorded value, and normal traffic was unaffected by replay
  overrides happening on other threads at the same time.
- **Part A and Part B together**: added a combined endpoint touching both mechanisms in one
  request; confirmed both replay correctly within the same case, not just in isolation.

### 12.4 Further hardening after real-app testing (Phase 0-4)

Testing against a real target application (not just the demo) surfaced further gaps in Part B, all
fixed since the sections above were written except one left deliberately open (noted at the end).
Full file-by-file detail, tests, and live verification for each is in `SPRING_CONFIG_CHANGES.md`;
summarized here so this proposal's account of "what's actually running" stays current:

- **Phase 0** — a type mismatch on one field (`IllegalArgumentException`) wasn't caught by the
  per-field isolation §12.2 already relies on (only `IllegalAccessException` was), aborting
  override for the whole request instead of just that field. Now isolated per-field.
- **Phase 1** — §7.5's note that constructor-bound `@ConfigurationProperties` "need the whole
  object replacement path" was correct but unimplemented for the case where that object is a
  **Java record**: `Field.set()` throws `IllegalAccessException` on a record component even with
  `setAccessible(true)`, unlike an ordinary final field. Fixed by reconstructing a new record
  instance via its canonical constructor and swapping the reference in whatever field holds it.
- **Phase 2** — a `@Value` field read once and passed unchanged into another bean's constructor
  (the same shape §7.5's first bullet describes for a `@Bean` factory-method parameter, but for
  any constructor, not only factory methods) is invisible to field-based discovery: the source
  field overwrites fine, but nothing re-reads it once copied. Fixed with a narrow, straight-line
  bytecode scanner (`OneHopFieldCopyScanner`) that finds exactly this one-hop passthrough shape and
  registers the derived field too.
- **Phase 3** — two further gaps found only via real-app testing, neither exercised by the original
  demo app: (a) the same one-hop scanner silently failed for any AOP-advised target bean
  (`@Transactional`/`@Cacheable`/`@Async`/custom `@Aspect`) or any `@Configuration`-enhanced class —
  both are CGLIB-generated at runtime with no loadable `.class` resource, and a CGLIB proxy
  additionally doesn't share field storage with the object it wraps, so correct class resolution
  alone wasn't sufficient; (b) a record accessor method call (`someRecord.someField()`) on a record
  injected directly as a `@Bean` parameter wasn't a recognized source shape at all — extended the
  scanner to recognize it.

- **Phase 4** — three further gaps, again found only via real-app testing: (a) a field whose live
  value is `null` at record time was skipped entirely, and replay couldn't tell "never recorded"
  apart from "recorded as null" - both looked like a missing map entry, so the field silently fell
  back to whatever the *replay host's own local config* produced. Fixed with an explicit null
  marker written at record time, restoring `null` at replay time. (b) A plain enum field round-
  tripped as a `String`, not the enum: the shared serializer's default typing embeds no type info
  for a class it treats as `final` (every plain enum with no per-constant class bodies), and its
  blind `Object.class` deserialize target has no way to recover the intended type. Fixed locally,
  using the field's own reflected type (already available) instead of touching the shared
  serializer. (c) A record source's own component fields were registered for replay even though no
  in-place update path exists for them (only a holder's reference field can be swapped) - every one
  was attempted and failed, every request. Fixed by no longer registering them at all.

Each was found and fixed via live reproduction against `arex-spring-config-demo` (extended with
matching fixtures for Phase 3's two gaps and Phase 4's null/enum gaps), then confirmed via the real
`arex-schedule` webhook, including under genuine concurrent, multi-environment replay (20 distinct
environments, 80 cases, fired concurrently against one shared replay target, zero
cross-contamination) — same verification discipline as §12.3.

One further gap, found alongside Phase 4's three but with no clean fix identified, is deliberately
left unaddressed: a record whose compact constructor performs a one-way, non-idempotent transform
on one of its own components (filtering/sorting/mapping raw input down to something smaller) gets
that transform silently re-applied during reconstruct-and-swap, since Java gives no way to invoke a
record's canonical constructor without also running its compact constructor. The only
architecturally-correct fix identified - capturing raw pre-binding property values and reconstructing
via Spring Boot's own constructor-binding `Binder` instead of from accessor output - is
substantially larger than anything built for this feature so far. See `SPRING_CONFIG_CHANGES.md`
for the full writeup.

## 13. Debugging: tracing a request through the logs

Every log line below only fires on genuine record- or replay-mode traffic — a request with no
active `ArexContext` never reaches any of them, so none of this adds log volume to ordinary
production traffic when nothing is being recorded or replayed.

### 13.1 Making the logs visible

These are plain `LogManager.info`/`.warn` calls, subject to whatever the agent's underlying SLF4J
SimpleLogger threshold is — which defaults to a level that suppresses INFO. If you don't see
*any* of these lines even during traffic you know is being recorded/replayed, set the level
explicitly:

```
-Dshaded.org.slf4j.simpleLogger.defaultLogLevel=info
```

Also check **both** of the agent's log files under `arex.log.path` (default relative to where the
agent runs, e.g. `logs/`): `arex.startup.<date>.log` and `arex.<date>.log`. In testing, agent
lifecycle/config-negotiation output (module install decisions, config sync) consistently landed
in the `startup` file even well after actual startup, while request-thread-originated output
(everything in this section) landed in the other one. Don't conclude a line "didn't fire" without
checking both files.

### 13.2 What each log line means

| Title | Fires when | A normal value looks like | A problem looks like |
|---|---|---|---|
| `spring.config.module` | Once, at agent install | `"enabled - installing Part A ... Part B ..."` | `"disabled (arex.spring.config is not \"true\")..."` when you expected it on — the fastest way to rule out "did the flag even take" |
| `spring.bean.config.registry` | Once, right after Spring context startup | Lists every bean name found eligible for Part B | Your bean is missing from the list — check it's a singleton by this point, actually has `@Value` fields or the `@ConfigurationProperties` annotation, and isn't under `org.springframework.*` |
| `spring.config.env.record.flush` (Part A) | Once per record-mode request that made ≥1 dynamic read | Lists the keys captured | Missing entirely for a request you expected to record — `ContextManager.needRecord()` was false; check sampling/`DISABLE_RECORD` outside this feature |
| `spring.bean.config.record` (Part B) | Once per record-mode request | Field/bean counts | Counts don't match what you expect from the registry log — check the registry log for what's actually being watched |
| `spring.config.env.replay.fetch` (Part A) | Once per replay-mode request, on the first dynamic read | `"N key(s) found: [...]"` | `"0 key(s) found"` — the aggregate document itself wasn't found; check recordId/appId/category directly in MongoDB (§ earlier in this conversation for how) |
| `spring.config.replay.miss` (Part A) | Per specific key not present in the fetched aggregate | Expected for keys the original request never touched | Unexpected for a key you know was recorded — the fetched aggregate didn't contain it; re-check the record-side flush log for that same original case |
| `spring.bean.config.replay.apply` / `.replay.miss` (Part B) | Once per replay-mode request | `.apply`: `"overrode N field(s) across M bean(s): [...]"` | `.miss`: either no aggregate found at all, or one was found but matched zero registered fields (registry/recording mismatch) |
| `spring.bean.config.replay.restore` | Once per replayed request, guaranteed via `onThrowable` | Always present whenever `.replay.apply` fired earlier in the same request | **Absent** despite `.replay.apply` having fired — this would mean the guaranteed-restore path itself failed, a serious bug, not an application-level miss |
| `spring.bean.config.replay.error` / `.field.read` / `.field.write` (warn) | A reflection failure | Should be rare post the framework-noise fix | Recurring — the bean/field named in the accompanying exception likely holds a type that doesn't round-trip through serialization cleanly |

### 13.3 Worked example

A real log sequence from testing (`/api/combined`, recorded then replayed):

```
[spring.config.module] enabled - installing Part A ... Part B ...
[spring.bean.config.registry] registered 3 bean(s) ...: [businessHoursController, ...]

--- a record-mode request arrives ---
[spring.bean.config.record] recorded 6 field(s) across 3 bean(s)
[spring.config.env.record.flush] recorded 1 dynamic key(s) for recordId=AREX-...: [demo.dynamic-message]

--- later, a replay-mode request arrives for that same case ---
[spring.bean.config.replay.apply] overrode 6 field(s) across 3 bean(s): [...]
[spring.config.env.replay.fetch] fetched aggregate for recordId=AREX-...: 1 key(s) found: [demo.dynamic-message]
[spring.bean.config.replay.restore] restored original values for 3 bean(s)
```

The order reflects the actual mechanism, not an arbitrary sequence: Part B's `replay.apply` fires
on `doDispatch` **entry**, before the controller runs at all; Part A's `replay.fetch` fires
**whenever** the controller code happens to call `Environment.getProperty(...)` (mid-request, and
only if it does); Part B's `replay.restore` fires on `doDispatch` **exit**, guaranteed regardless
of what the controller did — including if it threw.

### 13.4 When logs alone aren't enough

For anything the logs don't make obvious (e.g., confirming exactly what got stored, or whether a
document is missing versus just not what you expected), go straight to MongoDB rather than
inferring from logs — `docker exec arex-mongodb mongosh "mongodb://arex:iLoveArex@localhost:27017/arex_storage_db"`,
querying `RollingConfigFileMocker`/`PinnedConfigFileMocker` by `appId`/`recordId`/`operationName`
(`spring-config-env` or `spring-config-properties`), decoding the zstd+base64 `targetResponse`
body. This was the actual ground truth used throughout implementation whenever log output was
ambiguous or insufficient on its own.
