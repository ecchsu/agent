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

## What's *not* changed

No existing instrumentation module, no existing shared runtime class (`MockUtils`, `ContextManager`,
`ArexContext`, `Serializer`, etc.), and no existing servlet/Apollo code was modified — this feature
is entirely additive, reusing the existing `MockCategoryType.CONFIG_FILE` category and existing
`RequestHandler`/`ModuleInstrumentation` extension points rather than changing their behavior for
other categories.
